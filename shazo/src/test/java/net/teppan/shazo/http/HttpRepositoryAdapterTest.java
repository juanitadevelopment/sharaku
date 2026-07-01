package net.teppan.shazo.http;

import com.sun.net.httpserver.HttpServer;
import net.teppan.shazo.MultipleFoundException;
import net.teppan.shazo.NotFoundException;
import net.teppan.shazo.RawResult;
import net.teppan.shazo.ShazoException;
import net.teppan.shazo.http.internal.RepositoryRequestHandler;
import org.junit.jupiter.api.*;

import java.io.*;
import java.io.Serializable;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;

/**
 * Full round-trip tests for {@link HttpRepositoryAdapter} +
 * {@link RepositoryRequestHandler} using the JDK built-in
 * {@code com.sun.net.httpserver.HttpServer} as the test transport.
 *
 * <p>No servlet container is required: the handler is wired directly to the
 * HTTP exchange, which is the same call path that
 * {@link HttpRepositoryServlet#doPost} uses.
 */
class HttpRepositoryAdapterTest {

    // ── Domain type ──────────────────────────────────────────────────────────

    record Person(String id, String name) implements Serializable {}

    // ── In-memory store ──────────────────────────────────────────────────────

    static class PersonStore implements net.teppan.shazo.Repository<Person> {

        private final ConcurrentHashMap<String, Person> data = new ConcurrentHashMap<>();

        @Override public boolean contains(Person q) { return data.containsKey(q.id()); }
        @Override public void    store(Person p)    { data.put(p.id(), p); }
        @Override public void    delete(Person p)   { data.remove(p.id()); }

        @Override
        public Optional<Person> retrieve(Person q) {
            return Optional.ofNullable(data.get(q.id()));
        }

        @Override
        public Person find(Person q) throws ShazoException {
            // id given → unique lookup; otherwise match by name (may be ambiguous),
            // so the strict find contract (NotFound / MultipleFound) is exercised.
            if (q.id() != null) {
                return retrieve(q).orElseThrow(() -> new NotFoundException(q.id()));
            }
            var byName = data.values().stream()
                .filter(p -> Objects.equals(p.name(), q.name())).toList();
            if (byName.isEmpty()) throw new NotFoundException("name=" + q.name());
            if (byName.size() > 1) throw new MultipleFoundException("name=" + q.name(), byName.size());
            return byName.getFirst();
        }

        @Override
        public RawResult catalog(Person q) {
            // Emit a mix of scalar types to exercise the typed row codec.
            var rows = new ArrayList<Map<String, Object>>();
            for (Person p : data.values()) {
                var row = new LinkedHashMap<String, Object>();
                row.put("id", p.id());
                row.put("name", p.name());
                row.put("age", 42);
                row.put("score", new java.math.BigDecimal("3.14"));
                row.put("created", java.sql.Timestamp.valueOf("2026-07-01 12:00:00"));
                row.put("avatar", new byte[]{1, 2, 3});
                row.put("nickname", null);
                rows.add(row);
            }
            return RawResult.of(rows);
        }

        @Override
        public List<Person> gather(Person q) {
            return List.copyOf(data.values());
        }
    }

    // ── Test fixtures ────────────────────────────────────────────────────────

    private HttpServer            server;
    private HttpRepositoryAdapter<Person> adapter;

    @BeforeEach
    void setUp() throws Exception {
        var store   = new PersonStore();
        var codec   = Codec.java(Person.class);
        var handler = new RepositoryRequestHandler<>(store, codec);

        // port 0 = OS picks an available port
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/repo", exchange -> {
            var baos = new ByteArrayOutputStream();
            handler.handle(exchange.getRequestBody(), baos);
            byte[] body = baos.toByteArray();
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        int port = server.getAddress().getPort();
        adapter = new HttpRepositoryAdapter<>(
            URI.create("http://localhost:" + port + "/repo"), codec);
    }

    @AfterEach
    void tearDown() {
        adapter.close();
        server.stop(0);
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    void containsReturnsFalseForMissingEntity() throws ShazoException {
        assertThat(adapter.contains(new Person("99", null))).isFalse();
    }

    @Test
    void containsReturnsTrueAfterStore() throws ShazoException {
        adapter.store(new Person("1", "Alice"));
        assertThat(adapter.contains(new Person("1", null))).isTrue();
    }

    @Test
    void retrieveReturnsEmptyForMissingEntity() throws ShazoException {
        assertThat(adapter.retrieve(new Person("no-such", null))).isEmpty();
    }

    @Test
    void storeAndRetrieveRoundtrip() throws ShazoException {
        adapter.store(new Person("2", "Bob"));
        assertThat(adapter.retrieve(new Person("2", null)))
            .contains(new Person("2", "Bob"));
    }

    @Test
    void findThrowsNotFoundWhenAbsent() {
        assertThatThrownBy(() -> adapter.find(new Person("ghost", null)))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void findReturnsUniqueMatch() throws ShazoException {
        adapter.store(new Person("1", "Alice"));
        assertThat(adapter.find(new Person("1", null))).isEqualTo(new Person("1", "Alice"));
    }

    @Test
    void findThrowsMultipleFoundOverTheWire() throws ShazoException {
        // Two people share a name; a strict find by name must surface
        // MultipleFoundException from the server, not silently pick one.
        adapter.store(new Person("1", "Dup"));
        adapter.store(new Person("2", "Dup"));
        assertThatThrownBy(() -> adapter.find(new Person(null, "Dup")))
            .isInstanceOf(MultipleFoundException.class);
    }

    @Test
    void catalogRoundTripsTypedRows() throws ShazoException {
        adapter.store(new Person("a", "Alice"));
        RawResult table = adapter.catalog(new Person(null, null));

        assertThat(table.size()).isEqualTo(1);
        var row = table.first().orElseThrow();
        assertThat(row.get("id")).isEqualTo("a");
        assertThat(row.get("name")).isEqualTo("Alice");
        assertThat(row.get("age")).isEqualTo(42);                       // Integer
        assertThat(row.get("score")).isEqualTo(new java.math.BigDecimal("3.14"));
        assertThat(row.get("created"))
            .isEqualTo(java.sql.Timestamp.valueOf("2026-07-01 12:00:00"));
        assertThat((byte[]) row.get("avatar")).containsExactly(1, 2, 3);
        assertThat(row.get("nickname")).isNull();
    }

    @Test
    void catalogReturnsEmptyTableWhenNoEntities() throws ShazoException {
        assertThat(adapter.catalog(new Person(null, null)).isEmpty()).isTrue();
    }

    @Test
    void deleteRemovesEntity() throws ShazoException {
        adapter.store(new Person("3", "Carol"));
        adapter.delete(new Person("3", null));
        assertThat(adapter.contains(new Person("3", null))).isFalse();
    }

    @Test
    void catalogReturnsAllStoredEntities() throws ShazoException {
        adapter.store(new Person("a", "Alice"));
        adapter.store(new Person("b", "Bob"));
        var result = adapter.gather(new Person(null, null));
        assertThat(result).containsExactlyInAnyOrder(
            new Person("a", "Alice"),
            new Person("b", "Bob"));
    }

    @Test
    void catalogReturnsEmptyWhenNoEntities() throws ShazoException {
        assertThat(adapter.gather(new Person(null, null))).isEmpty();
    }

    // ── Codec.java() ─────────────────────────────────────────────────────────

    @Test
    void javaCodecRoundtrips() throws ShazoException {
        var codec  = Codec.java(Person.class);
        var person = new Person("id", "name");
        assertThat(codec.decode(codec.encode(person))).isEqualTo(person);
    }

    @Test
    void javaCodecRejectsClassOutsideAllowlist() throws ShazoException {
        // A payload whose top-level class is not on the allowlist must be rejected.
        record Evil(String cmd) implements Serializable {}
        var permissive = Codec.java(Evil.class);   // can encode Evil
        byte[] payload = permissive.encode(new Evil("rm -rf /"));

        var guarded = Codec.java(Person.class);     // only Person allowed
        assertThatThrownBy(() -> guarded.decode(payload))
            .isInstanceOf(ShazoException.class);
    }
}
