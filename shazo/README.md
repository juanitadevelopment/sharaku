# Shazo

A small object-persistence abstraction for Java. One repository interface, many
storage backends — JDBC, the file system, external shell commands, and remote
HTTP — behind a single typed contract.

Shazo separates **what** to persist (your domain object) from **how** it maps to
storage (a `Describer` that emits typed commands), so the same domain code can be
backed by a relational database in production and a directory of files in a test,
without changing a line of business logic.

> **Status:** early release (`0.2.2`). The core API is stable and fully tested,
> but minor breaking changes are still possible before `1.0.0`.

## Requirements

- **Java 21+** (uses records, pattern-matching `switch`, and virtual threads)
- Build: Gradle (wrapper included)

## The core contract

Every backend implements the same interface:

```java
public interface Repository<T> {
    boolean      contains(T query)  throws ShazoException;
    void         store(T entity)    throws ShazoException;
    void         delete(T entity)   throws ShazoException;
    Optional<T>  retrieve(T query)  throws ShazoException;  // lenient: first match or empty
    T            find(T query)      throws ShazoException;  // strict: the unique match, or NotFound / MultipleFound
    List<T>      gather(T query)    throws ShazoException;  // the matching entities
    RawResult    catalog(T query)   throws ShazoException;  // the matching rows, as a table

    Gathered<T>  gather(T query, Page page)  throws ShazoException;  // one page + has-more flag
    RawResult    catalog(T query, Page page) throws ShazoException;  // one page of rows
}
```

The same domain object doubles as the query: pass a sparsely-populated instance
(e.g. only the id) to look something up.

Two read shapes, deliberately distinct:

- **`catalog`** returns the result as a **table** (`RawResult` of named-column rows)
  — for consumers that are themselves tabular (a UI grid, a report, a CSV/JSON
  export) and would only pay to re-flatten objects.
- **`retrieve` / `find` / `gather`** return **objects**. `retrieve` leniently takes
  the first match; `find` is strict (exactly one — else `NotFoundException` or
  `MultipleFoundException`); `gather` returns all.

### Paging (bounded reads)

For match sets that may be large, both read shapes take a `Page` — skip
`offset`, take at most `limit` — so memory and per-key retrieves are capped:

```java
var page = Page.of(50);
var slice = orders.gather(query, page);          // Gathered<Order>: items + hasMore
while (slice.hasMore()) {
    process(slice);
    slice = orders.gather(query, page = page.next());
}
process(slice);
```

`hasMore()` is exact (the repository probes one row past the window), and it
works with **any existing describer, unchanged** — the fetch is bounded
driver-side (`Statement.setMaxRows`), never by rewriting your SQL. When a
deep-offset walk needs the database to apply the window itself, declare an
optional paged catalog in your own dialect:

```java
.catalogPaged((q, page) -> List.of(SqlCommand.of(
    "SELECT id FROM orders WHERE customer = ? ORDER BY id LIMIT ? OFFSET ?",
    q.customer(), page.limit(), page.offset())))
```

Offset paging assumes the catalog query has a stable order — give it an
`ORDER BY`.

## How it fits together

```
Repository<T>            ← the contract your code depends on
   ▲
AbstractRepository<T,C>   ← template method; turns a Describer into storage calls
   ▲
JdbcRepository / FileRepository / ShellRepository / …

Describer<T, C extends Command>
   ├─ produces typed commands (SqlCommand / FileCommand / ShellCommand)
   ├─ Infuser<T> : Results → one entity   (the sole object builder; assembles a
   │               root and its children from per-command results — see below)
   ├─ key        : a catalog row → a key-bearing query, so find/gather can
   │               "catalog the keys, then retrieve each" (optional)
   └─ Verifier   : does a result count as "found"?
```

`retrieve`/`find` run the describer's commands, keep each command's rows under
its `name` (a `Results`), and let the `Infuser` assemble the object. `gather`
catalogs the matching keys and retrieves each; `find` catalogs, checks the count
is exactly one, then retrieves.

A `Describer` is parameterized by its command type `C`, so a
`Describer<Memo, SqlCommand>` can only be paired with a JDBC repository and a
`Describer<Memo, FileCommand>` only with a file repository — mismatches are
**compile-time** errors, not runtime surprises.

## Quick start (JDBC)

```java
record Person(String id, String name, int age) {}

// 1. A data source (embedded H2 here, from the optional shazo-h2 module;
//    any javax.sql.DataSource works — HikariCP over PostgreSQL, etc.)
DataSource ds = H2DataSources.inMemory("demo");

// 2. (optional) run versioned migrations from the classpath
SchemaManager.apply(ds, "db/migration/");   // V001__*.sql, V002__*.sql, ...

// 3. Describe how Person maps to SQL
Describer<Person, SqlCommand> describer = Describer.<Person, SqlCommand>builder()
    .contains(p -> List.of(SqlCommand.of("SELECT 1 FROM person WHERE id = ?", p.id())))
    .store(p    -> List.of(SqlCommand.of(
        "MERGE INTO person (id, name, age) KEY (id) VALUES (?, ?, ?)",
        p.id(), p.name(), p.age())))
    .delete(p   -> List.of(SqlCommand.of("DELETE FROM person WHERE id = ?", p.id())))
    .retrieve(p -> List.of(SqlCommand.of(
        "SELECT id, name, age FROM person WHERE id = ?", p.id())))
    .catalog(p  -> List.of(SqlCommand.of("SELECT id, name, age FROM person ORDER BY name")))
    .key(row    -> new Person((String) row.get("id"), null, 0))   // catalog row -> key query
    .infuser(results -> {
        var row = results.primary().first().orElseThrow();
        return new Person((String) row.get("id"), (String) row.get("name"),
                          ((Number) row.get("age")).intValue());
    })
    .build();

// 4. Use it
var repo = new JdbcRepository<>(ds, describer);
repo.store(new Person("1", "Alice", 30));
Optional<Person> alice = repo.retrieve(new Person("1", null, 0));  // first match or empty
Person           bob   = repo.find(new Person("2", null, 0));      // unique, or throws
List<Person>     all   = repo.gather(new Person(null, null, 0));   // objects
RawResult        table = repo.catalog(new Person(null, null, 0));  // rows, for a grid/report
```

Column lookups in the infuser are **case-insensitive**, so `row.get("id")` works
whether the driver reports `id`, `ID`, or `Id`. `find`/`gather` work by cataloging
the matching keys and retrieving each, so a describer must declare `key(...)` to
support them.

### Transactions

```java
repo.transact(r -> {
    r.store(alice);
    r.store(bob);
    return null;        // commits on normal return, rolls back on exception
});
```

### Aggregates (1:N, 1:N:N) without joins

A `retrieve` can run **several named commands** — a root and its children — and
the `Infuser` assembles them from the per-command `Results`. Each relationship is
its own query, so a deep object graph is built without a wide `JOIN` (and without
the cartesian row explosion that joining several child collections causes):

```java
Describer.<Order, SqlCommand>builder()
    // ...
    .retrieve(o -> List.of(
        SqlCommand.named("order", "SELECT * FROM orders WHERE id = ?", o.id()),
        SqlCommand.named("lines", "SELECT * FROM order_line WHERE order_id = ?", o.id())))
    .infuser(results -> {
        var head  = results.of("order").first().orElseThrow();
        var lines = results.of("lines").rows().stream().map(Line::from).toList();
        return new Order((String) head.get("id"), ..., lines);
    })
    .key(row -> new Order((String) row.get("id"), null, List.of()))
    .build();
```

Because `find` counts **entities** (one catalog row per entity), it never
mistakes an aggregate's many child rows for "multiple found".

### Storing several types at once

`Repository<T>` is precise but makes you name a describer per call. When you'd
rather "store anything", register describers by type with `Repositories` and
dispatch on the object's runtime class — including a varargs `store(...)`:

```java
var repos = Repositories.builder()
    .register(Order.class,   orderDescriber)
    .register(Booking.class, bookingDescriber)
    .build();

new Transactor(dataSource).execute(uow -> {
    repos.in(uow).store(order, booking);   // both types, one transaction
    return null;
});

Optional<Order> o = repos.in(uow).retrieve(Order.class, new Order(id, null));
```

You can also get a plain `Repository<T>` by domain type alone —
`repos.in(uow).repository(Order.class)` — when you want the handle without
naming the describer's command type.

## Backends

| Backend | Class | Command type | Notes |
|---|---|---|---|
| Relational DB | `JdbcRepository<T>` | `SqlCommand` | any `DataSource`; `transact(...)` |
| File system | `FileRepository<T>` | `FileCommand` | atomic writes, path-traversal guarded, thread-safe |
| Shell command | `ShellRepository<T>` | `ShellCommand` | `ProcessBuilder`; per-process timeout |
| Remote HTTP | `HttpRepositoryAdapter<T>` / `HttpRepositoryServlet<T>` | — | binary protocol; pluggable `Codec` |
| In-memory cache | `CacheRepository<T>` | — | TTL decorator over any repository |
| Fan-out | `MixedRepository<T>` | — | writes to many, reads from a primary |
| Async | `AsyncRepository<T>` | — | `CompletableFuture` wrapper on virtual threads |

### File backend

```java
var repo = new FileRepository<>(Path.of("./data"), new FileMemoDescriber());
```

Writes are staged in a temp file and atomically moved into place, all operations
are guarded by a read/write lock, and file names that try to escape the base
directory (`../…`, absolute paths) are rejected.

### Decorators

```java
// TTL cache keyed by id
var cached = new CacheRepository<>(jdbcRepo, Duration.ofMinutes(10), Person::id);

// mirror writes to a replica; read from the primary
var mixed  = MixedRepository.of(primaryRepo, replicaRepo);

// non-blocking access (closes its executor)
try (var async = new AsyncRepository<>(jdbcRepo)) {
    async.retrieve(new Person("1", null, 0))
         .thenAccept(opt -> opt.ifPresent(System.out::println));
}
```

### Remote HTTP

The HTTP transport is **contract-transparent**: every `Repository<T>` method —
`contains` / `store` / `delete` / `retrieve` / `find` / `gather` / `catalog` —
works over the wire with the same semantics as a local repository. `find` runs
on the server, so it still throws `NotFoundException` / `MultipleFoundException`;
`catalog` streams raw rows back in a typed, scalar-only cell format (String,
number, `Boolean`, `BigDecimal`, `byte[]`, SQL date/time) rather than a Java
object graph — so a `RawResult` crosses the wire without ever deserializing an
arbitrary object.

The client and server share a `Codec`. The default Java-serialization codec is
guarded by a deserialization **allowlist** — you must declare the permitted
type(s), which blocks gadget-chain payloads over the wire:

```java
Codec<Person> codec = Codec.java(Person.class);

// server side (any servlet container)
var servlet = new HttpRepositoryServlet<>(backingRepo, codec);

// client side
try (var repo = new HttpRepositoryAdapter<>(URI.create("http://host/api/persons"), codec)) {
    repo.store(new Person("1", "Alice", 30));
}
```

## Schema migrations

`SchemaManager` applies `V<n>__<description>.sql` scripts from a classpath
location in version order, inside a transaction, tracking what has run in a
`_shazo_schema_migrations` table. Applied scripts are checksummed: editing one
after it has run is detected and refused (add a new version instead). The
statement splitter understands SQL comments, quoted strings/identifiers, and
PostgreSQL dollar-quoted blocks.

```java
SchemaManager.apply(dataSource, "net/teppan/myapp/schema/");
```

`H2DataSources` (in the optional **`shazo-h2`** module) provides H2 data sources
(file, in-memory, server) with PostgreSQL-compatibility options preset. Core
`shazo` ships no JDBC driver — add `shazo-h2` only if you want this convenience;
otherwise supply your own `DataSource`.

## Production databases (PostgreSQL, MySQL, …)

Core `shazo` targets **any `javax.sql.DataSource`** and bundles no JDBC driver,
so a production database is the "bring your own `DataSource`" case: add the
driver you want (at the version matching your server) and a connection pool such
as HikariCP. There is deliberately no `shazo-postgres` module — a driver plus a
pool is all it takes, and both are things you configure per app. (The `shazo-h2`
module exists only because embedded H2 is the zero-setup dev/test on-ramp, the
one case where the library creating the `DataSource` for you is a real
convenience.)

```kotlin
dependencies {
    runtimeOnly("org.postgresql:postgresql:42.7.3")
    implementation("com.zaxxer:HikariCP:5.1.0")
}
```

```java
// 1. A pooled DataSource for your server (credentials from config/secrets)
var cfg = new HikariConfig();
cfg.setJdbcUrl("jdbc:postgresql://db.internal:5432/app");
cfg.setUsername(System.getenv("DB_USER"));
cfg.setPassword(System.getenv("DB_PASSWORD"));
cfg.setMaximumPoolSize(16);
DataSource ds = new HikariDataSource(cfg);

// 2. Migrations, describer, repository — identical to the H2 quick start
SchemaManager.apply(ds, "net/teppan/myapp/schema/");
var repo = new JdbcRepository<>(ds, personDescriber);
```

Everything above the `DataSource` — describers, repositories, `Transactor`,
`SchemaManager`, the whole `net.teppan.backbone` runtime — is unchanged from the
H2 examples; only the `DataSource` differs. For Postgres integration tests,
point HikariCP at a [Testcontainers](https://testcontainers.org) instance the
same way.

## Tenant-scoped connections

`SessionInitDataSource` wraps a `DataSource` and runs initialization SQL on every
borrowed connection — the seam for multi-tenant strategies that share a database:

```java
// schema-per-tenant: each connection is scoped to the tenant's schema
DataSource acme = new SessionInitDataSource(shared, "SET SCHEMA acme");
// row-level security: tag the session; the DB's RLS policies do the isolation
DataSource acme = new SessionInitDataSource(shared, "SET app.current_tenant = 'acme'");
```

(Database-per-tenant needs no wrapper — just route the tenant to its own
`DataSource`.) The `net.teppan.backbone` runtime builds on this for its
`forTenant` / `withTenant` API.

## Build

```sh
./gradlew test     # run the test suite
./gradlew jar      # build the library jar
./gradlew javadoc  # generate API docs
```

## License

Licensed under the [Apache License, Version 2.0](LICENSE). See [NOTICE](NOTICE)
for attribution.
