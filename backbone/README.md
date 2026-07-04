# Backbone

A minimal **transactional service and domain-event runtime** for Java, built on
the [shazo](../shazo) persistence abstraction.

Backbone gives a plain Java application the small set of "application server"
services that a data-driven system keeps re-inventing — a transactional unit of
work per request, composable services, domain events delivered *after* commit,
scheduled jobs, and basic runtime introspection — without a heavyweight
container, dynamic proxies, or XML.

> **Status:** early release (`0.3.2`). API may still change before `1.0.0`.

## Requirements

- **Java 21+**
- **shazo** — lives in the same [`sharaku`](..) repo and is always version-aligned
  (`0.3.2`); no separate install

## What it gives you

| Concern | Type | Summary |
|---|---|---|
| Transactional service runner | `ServiceRunner` | Runs each service as one transaction; commits, then delivers events |
| Request context / unit of work | `AppContext` | Transaction-scoped repositories, principal, tenant, locale |
| Service unit | `AppService<R>` | A lambda `AppContext -> R` |
| Identity | `Principal` | Immutable id + roles; `anonymous()` / `system()` |
| Domain events | `ServiceRunner.subscribe` + `Outbox` | In-process or durable (transactional outbox), delivered after commit |
| External-event intake | `PersistentEventQueue` | Durable, restart-surviving, idempotent queue for events arriving from outside |
| Blob storage | `BlobStore` | Opaque binary content stored atomically with the business row referencing it |
| Multi-database services | `ServiceRunner.route` / outbox relay | One service across several databases: synchronous enlisted routes, or at-least-once relay |
| Scheduling | `TimerScheduler`, `CronExpression` | Interval, 6-field cron, and one-shot (deadline) jobs as system units of work |
| Operations | `BackboneConsole` | One surface to view and control the runner and scheduler at run time |

## Core idea: a service is a transaction

```java
record Order(String id, String customer, String status) {}
record OrderPlaced(String orderId) implements java.io.Serializable {}

try (var runner = ServiceRunner.builder()
        .dataSource(dataSource)
        .describers(Repositories.builder()                 // storage binding lives in the wiring
            .register(Order.class, orderDescriber)
            .register(Audit.class, auditDescriber)
            .build())
        .durableEvents(OrderPlaced.class)                 // at-least-once, survives restart
        .subscribe(OrderPlaced.class, e -> mailer.confirm(e.orderId()))
        .register("placeOrder", ctx -> {
            ctx.store(order, audit);                       // both types, one transaction
            ctx.publish(new OrderPlaced(order.id()));      // delivered only after commit
            return order.id();
        })
        .build()) {

    String id = runner.execute("placeOrder", principal);
}
```

Services name only domain types (`ctx.store(order)`, `ctx.repository(Order.class)`,
`ctx.retrieve(Order.class, key)`); which describer — and therefore which storage —
backs each type is configured once, in the wiring. No JDBC types leak into service code.

- **Atomic across types** — everything a service stores shares the transaction.
- **Events after commit** — published events reach subscribers only once the
  transaction commits; a failure rolls everything back and discards them.
- **Nested composition** — `ctx.call(otherService)` joins the same transaction.
- **Multitenancy** — `tenantRouter(tenant -> dataSource)` plus `forTenant(...)` /
  `withTenant(...)` (see [Multi-tenancy](#multi-tenancy)).

### The describer registry (storage in the wiring, not in services)

A service should not name the storage. With a
[`Repositories`](../shazo) registry, the
describers (which carry the JDBC command type) are declared once at wiring time,
and services address persistence purely by domain type — `ctx.store(...)`
(varargs), `ctx.delete(...)`, `ctx.repository(Order.class)`, and
`ctx.retrieve(Order.class, key)`:

```java
var describers = Repositories.builder()
    .register(Order.class,   orderDescriber)
    .register(Booking.class, bookingDescriber)
    .build();

var runner = ServiceRunner.builder().dataSource(ds).describers(describers) /* ... */ .build();

// inside a service:
ctx.store(order, booking);                       // both types, one transaction
Repository<Order> orders = ctx.repository(Order.class);
Optional<Order> o = ctx.retrieve(Order.class, new Order(id, null));
```

## Durable events (transactional outbox)

With `durableEvents(...)`, published events are written to a `backbone_outbox`
table **in the same transaction** as the business change, then delivered
asynchronously by a poller — at-least-once, surviving restarts. There is never a
committed change without its event, nor an event without its change. Subscribers
must be idempotent. (Without `durableEvents`, events are delivered in-process,
synchronously, right after commit.) `ctx.publish(...)` is varargs, so several
events can be raised in one call: `ctx.publish(orderPlaced, bookingConfirmed)`.

### Dead-letters and triage

An event that keeps failing is not retried forever. After
`outboxMaxAttempts(...)` failed deliveries (default `10`) it moves to a terminal
**dead-letter** state and the poller skips it; an event whose payload cannot be
decoded is dead-lettered immediately. Dead-lettered events can be inspected and,
once the cause is fixed, requeued or discarded:

```java
runner.deadLetterCount();              // OptionalLong
for (OutboxEntry e : runner.deadLetterEvents(50)) {
    log.warn("stuck event {} ({}) after {} attempts: {}",
        e.id(), e.type(), e.attempts(), e.lastError().orElse(""));
}
runner.retryEvent(id);                 // requeue (resets the attempt count)
runner.discardEvent(id);              // drop permanently
```

`pendingEvents(int)` lists events still awaiting delivery the same way.

## Durable intake queues (external events in)

The outbox is for events a *service* produces. The mirror case — events arriving
**from outside** the application that must be accepted durably and handed to
listeners — is a **persistent event queue**: a named, restart-surviving intake
point that fans one event out to many listeners, each running after the receive
commits, on a poller thread, in its own transaction.

Declare the queue and its listeners on the builder, then resolve it to receive:

```java
try (var runner = ServiceRunner.builder()
        .dataSource(dataSource)
        .persistentQueue("orders", ExternalOrder.class)          // named durable queue
        .persistentQueueListener("orders", e -> fulfil(e))       // shared across tenants
        .persistentQueueListener("orders", e -> audit(e))        // fan-out
        .build()) {

    PersistentEventQueue<ExternalOrder> orders = runner.persistentQueue("orders");

    // Intake from the outside world. The sender's message id makes it idempotent:
    // a re-sent event (the sender's own at-least-once) is collapsed at intake.
    boolean accepted = orders.receive(messageId, new ExternalOrder("o-1"));
}
```

- **Idempotent receive** — the dedup marker and the queued row commit atomically,
  so a duplicate external send is not enqueued twice. `receive` returns `false`
  for a message id already seen. `publish(event)` is the fire-and-forget form for
  app-generated events (fresh id, always enqueued).
- **Separate transaction** — listeners run after the receive commits, on the
  poller, each in its own unit of work.
- **At-least-once + triage** — same engine as the outbox: `deadLetterCount()`,
  `peekDeadLetters(n)`, `retry(id)` (the old queue browser's replay), `discard(id)`.
- **Per tenant** — with a `tenantRouter`, `runner.persistentQueue("orders", tenant)`
  gives each tenant its own queue on its own data source (its own
  `backbone_pq_orders` table), sharing the declared listeners.

This revives the framework's original `PersistentEventQueue` (the durable peer of
the in-memory `TransientEventQueue`), rebuilt on the modern outbox engine.

## Blob storage

`BlobStore` holds opaque binary content — attachments, exported documents —
that is too large or unstructured for a typed `Repository` column. Its point is
the same one the outbox makes for events: writing on the caller's own
connection means the blob commits or rolls back **atomically** with whatever
business row references it, so "the file was written but the row wasn't" (or
the reverse) cannot happen.

```java
var blobs = new BlobStore(dataSource);   // applies its schema migration once

try (var conn = dataSource.getConnection()) {
    conn.setAutoCommit(false);
    var ref = blobs.store(conn, fileStream, new BlobMeta("invoice.pdf", "application/pdf"));
    attachmentRepo.store(new Attachment(orderId, ref.id()));   // same connection
    conn.commit();                                             // both or neither
}

try (var content = blobs.open(ref.id())) {   // streamed, never fully materialized
    content.transferTo(response.getOutputStream());
}
```

- **`store(Connection, InputStream, BlobMeta)`** joins the caller's transaction;
  **`store(InputStream, BlobMeta)`** is the standalone form for blobs with no
  surrounding transaction to join.
- **Streaming both ways** — `store` writes via `setBinaryStream` rather than
  buffering into a `byte[]`, and `open` returns a stream backed directly by the
  result set. The returned `BlobRef`'s size and SHA-256 digest are measured as a
  side effect of that same streaming write, not a separate pass.
- **Opaque on purpose** — a blob is a name, a media type, and bytes, never a
  Java object graph; there is no `Object`-accepting overload, so this cannot
  become a second, less-scrutinized deserialization path.
- **`metadata(id)`** reads the `BlobRef` without the content; **`delete(id)`**
  removes it.
- Not yet included (v1 is deliberately DB-backed only): deduplication by
  digest, a retention/GC policy, or an external backend such as S3.

## Multi-database services

A service can span more than one physical database. There are two ways to do it,
with different guarantees — pick by whether you need the second write to be
*synchronous* or *never lost*.

### Enlisted routes — synchronous, shared commit boundary

Declare a **route**: a second data source and the types it serves. The service
names only the domain type; which database backs it is decided in the wiring,
exactly as `describers(...)` does for the primary.

```java
try (var runner = ServiceRunner.builder()
        .dataSource(ediDataSource)
        .describers(Repositories.builder().register(Order.class, orderDescriber).build())
        // a second DB, serving Part — the modern form of the old BOR.xml
        // <repository name="ORS"> + <target> group:
        .route(orsDataSource, Repositories.builder().register(Part.class, partDescriber).build())
        .build()) {

    runner.run(ctx -> {
        ctx.repository(Order.class).store(order);   // primary DB
        ctx.repository(Part.class).store(part);     // routed DB — same service
        return null;
    }, principal);
}
```

- **Resolution** — a type resolves against the primary registry first, then each
  route in declaration order. A type registered on both resolves to the primary.
- **Lazy enlistment** — a route's connection is opened only when the service first
  touches one of its types, then pinned for the rest of the execution. Reads on a
  route see the service's own writes (read-your-writes); untouched routes cost
  nothing.
- **Shared commit boundary** — on success the routes commit first and the primary
  commits last (sealing the outbox events). If the service throws, the primary and
  every route roll back together. If a route commit fails, the primary is still
  open and the whole execution rolls back — a plain, retryable `AppServiceException`.
- **Not two-phase commit** — the commits are sequential. A crash *between* a route
  commit and the primary commit (or between two routes) leaves them inconsistent.
  This is the same narrow window the original framework's multi-connection commit
  had. When that window is unacceptable, use the relay below.
- **Non-transactional backends** — an HTTP/file/shell repository has no transaction
  to enlist. Register it with `route(Type.class, repository)`: it takes effect
  immediately, per operation, and is **not** rolled back with the service.

`ctx.store(...)`/`delete`/`contains` stay on the primary transaction only; reach a
routed type through `ctx.repository(Type.class)` or the typed reads.

### Outbox relay — asynchronous, at-least-once, nothing lost

When the second write must never be lost — a real cross-system integration, or the
route's crash window is simply not acceptable — commit it to the primary as a
**durable event** and relay it to the other database from an idempotent listener:

```java
try (var runner = ServiceRunner.builder()
        .dataSource(primaryDataSource)
        .describers(Repositories.builder().register(Order.class, orderDescriber).build())
        .durableEvents(ShipmentRequested.class)
        // relay: runs after commit, on the poller, retried until it succeeds
        .subscribe(ShipmentRequested.class, e -> shippingRepo.store(
            new Shipment(e.orderId(), "REQUESTED")))     // idempotent (MERGE) on the 2nd DB
        .build()) {

    runner.run(ctx -> {
        ctx.repository(Order.class).store(order);
        ctx.publish(new ShipmentRequested(order.id(), order.customer()));  // committed with the order
        return id;
    }, principal);
}
```

The event is committed atomically with the business change and delivered
at-least-once with retry and dead-lettering, so the two databases are guaranteed
to converge — even across a crash right after the primary commit: the intent
survives as a pending outbox row and is redelivered on restart. The cost is that
the second database lags by the poll interval, and the relay must be idempotent
(a lost ack means redelivery). See `OutboxRelayPatternTest` for the guarantees
spelled out as tests.

### Which one?

| Your need | Use |
|---|---|
| The second write must be visible **immediately** inside the service (read-your-writes / synchronous consistency), and a rare crash-window inconsistency is tolerable | **Enlisted route** |
| The second write must **never be lost** — cross-system integration, separate operational boundary — and eventual (async) convergence is fine | **Outbox relay** |
| Both databases must be **all-or-nothing with zero window** | Neither — that needs XA/2PC, which is out of scope by design |

## Multi-tenancy

Configure how a tenant maps to a data source, then bind the tenant **once** —
never on every call:

```java
var runner = ServiceRunner.builder()
        .tenantRouter(tenant -> dataSourceFor(tenant))   // tenant -> DataSource
        .describers(describers)
        .durableEvents(OrderPlaced.class)
        .register("placeOrder", ctx -> { ctx.store(order); return order.id(); })
        .build();

// bind once, reuse — routes to acme's data source (and its own outbox)
var acme = runner.forTenant("acme");
acme.execute("placeOrder", principal);
acme.execute("ship", principal);

// or establish an ambient tenant for a request boundary (e.g. a web filter):
runner.withTenant("acme", () -> {
    runner.execute("placeOrder", principal);   // picks up "acme" implicitly
    return null;
});
```

Inside a service, `ctx.tenant()` reports the current tenant. Durable events are
**per tenant**: each tenant's events are written to and polled from that tenant's
own data source (its own `backbone_outbox`).

The mapping itself is your choice, all behind the same `tenant -> DataSource` seam:

| Strategy | How `tenantRouter` returns the data source |
|---|---|
| **database-per-tenant** | a distinct `DataSource` per tenant (no wrapper) |
| **schema-per-tenant** | `new SessionInitDataSource(shared, "SET SCHEMA " + tenant)` |
| **row-level security** | `new SessionInitDataSource(shared, "SET app.current_tenant = '" + tenant + "'")` — the database's RLS policies enforce isolation |

[`SessionInitDataSource`](../shazo) is from
shazo. The same application code runs unchanged across strategies — e.g. schema
isolation on H2 in tests, PostgreSQL RLS in production.

## Scheduling

```java
try (var scheduler = TimerScheduler.builder().dataSource(dataSource).build()) {
    scheduler.schedule("nightly", "0 0 2 * * *", ctx -> cleanup(ctx)); // 6-field cron
    scheduler.schedule("heartbeat", Duration.ofSeconds(30), ctx -> ping(ctx));

    // one-shot deadline: expire this request in 48 hours (fires once, then COMPLETED)
    scheduler.schedule("expire-" + id, Instant.now().plus(Duration.ofHours(48)),
        ctx -> approvals.expire(ctx, id));
}
```

Each job runs as a `Principal.system()` unit of work; jobs can be
`suspend`/`resume`/`cancel`led and inspected via `jobStatuses()`. One-shot jobs
live in memory only — for deadlines that must survive a restart, persist them and
rearm on startup.

### Tenant-scoped and fan-out jobs

A scheduler built with `tenantRouter(...)` can bind a job to one tenant, or fan a
single schedule out across many — each firing runs as its own tenant-scoped unit
of work (its `ctx` routes to that tenant's data source):

```java
try (var scheduler = TimerScheduler.builder().tenantRouter(router).build()) {
    // one tenant
    scheduler.schedule("acme-nightly", "0 0 2 * * *", "acme", ctx -> cleanup(ctx));

    // every tenant, re-resolved at each firing (add a tenant, it's picked up)
    scheduler.scheduleForEachTenant("nightly", Duration.ofHours(24),
        () -> tenantRegistry.activeTenants(),
        ctx -> cleanup(ctx));   // ctx.tenant() reports which tenant this run is for
}
```

## Operations console

A backbone is assembled from independent parts; `BackboneConsole` binds them into
one operational surface, so a CLI, an admin HTTP endpoint, or JMX can see and
control the live system from a single object.

```java
var console = BackboneConsole.builder()
        .serviceRunner(runner)
        .scheduler(scheduler)        // optional
        .build();

ConsoleSnapshot s = console.snapshot();   // services, outbox counts, job statuses

// Outbox triage
console.deadLetters(50);                   // inspect stuck events
console.retryAllDeadLetters();             // requeue them after a fix

// Job control
console.suspendAllJobs();                  // e.g. before maintenance
console.resumeAllJobs();
console.suspendJob("nightly");
```

The console is a typed API and holds no resources of its own; it does not own
the runner or scheduler, so closing those remains the caller's responsibility.

## Getting shazo

Backbone and shazo live in the same [`sharaku`](..) repository and build
together, so backbone depends on shazo through a project dependency —
`api(project(":shazo"))`. There is nothing to install or version-match; the two
always move as one.

## Using backbone as a dependency

Backbone is published from the `sharaku` monorepo via [JitPack](https://jitpack.io),
which builds shazo transitively:

```kotlin
repositories { maven { url = uri("https://jitpack.io") } }
dependencies {
    implementation("com.github.juanitadevelopment.sharaku:backbone:0.3.2")
}
```

A single dependency is enough — shazo comes along transitively.

## Build

From the repository root:

```sh
./gradlew :backbone:test      # run the test suite
./gradlew :backbone:jar       # build the library jar
./gradlew :backbone:javadoc   # generate API docs
```

## License

Licensed under the [Apache License, Version 2.0](LICENSE). See [NOTICE](NOTICE)
for attribution.
