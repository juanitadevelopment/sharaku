package net.teppan.backbone;

import net.teppan.backbone.testsupport.Await;
import net.teppan.shazo.ShazoException;
import net.teppan.shazo.Describer;
import net.teppan.shazo.jdbc.JdbcRepository;
import net.teppan.shazo.jdbc.Repositories;
import net.teppan.shazo.jdbc.SqlCommand;
import net.teppan.shazo.jdbc.h2.H2DataSources;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.Serializable;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The <b>outbox relay</b> pattern — the official recipe for a write that must
 * reach a <em>second</em> database and cannot tolerate the small crash window an
 * {@linkplain ServiceRunner.Builder#route enlisted route} leaves open (§2.7 L2).
 *
 * <p>The service commits its business change <em>and</em> a durable event to the
 * primary database in one transaction. A subscriber then relays that event to
 * the other system, idempotently, on the outbox poller thread. Because the event
 * is committed atomically with the business change and delivered at-least-once
 * with retry and dead-lettering, <b>no write is ever lost</b> — the two databases
 * are guaranteed to converge, even across a crash. The price is that convergence
 * is <em>asynchronous</em>: the second database lags by the poll interval.
 *
 * <p>These tests double as executable documentation of that guarantee, and of
 * how it differs from an enlisted route (synchronous, but with a not-2PC window).
 *
 * <p>Domain: an order is placed in the primary database; a shipment must be
 * requested from a separate "shipping" database (a stand-in for another system
 * with its own operational boundary).
 */
class OutboxRelayPatternTest {

    record Order(String id, String customer) {}
    record Shipment(String orderId, String status) {}

    /** The event carrying the cross-database intent; must be serializable. */
    record ShipmentRequested(String orderId, String customer) implements Serializable {}

    private DataSource primaryDs;    // where the order (and outbox) live
    private DataSource shippingDs;   // the other system's database
    private Describer<Order, SqlCommand> orders;
    private Describer<Shipment, SqlCommand> shipments;
    private JdbcRepository<Shipment> shippingRepo;

    @BeforeEach
    void setUp() throws Exception {
        primaryDs  = H2DataSources.inMemory("relay_primary_" + System.nanoTime());
        shippingDs = H2DataSources.inMemory("relay_shipping_" + System.nanoTime());
        try (var conn = primaryDs.getConnection(); var st = conn.createStatement()) {
            st.execute("CREATE TABLE orders (id VARCHAR(36) PRIMARY KEY, customer VARCHAR(200))");
        }
        try (var conn = shippingDs.getConnection(); var st = conn.createStatement()) {
            st.execute("CREATE TABLE shipment (order_id VARCHAR(36) PRIMARY KEY, status VARCHAR(40))");
        }
        orders = Describer.<Order, SqlCommand>builder()
            .contains(o -> List.of(SqlCommand.of("SELECT 1 FROM orders WHERE id = ?", o.id())))
            .store(o    -> List.of(SqlCommand.of(
                "MERGE INTO orders (id, customer) KEY (id) VALUES (?, ?)", o.id(), o.customer())))
            .delete(o   -> List.of(SqlCommand.of("DELETE FROM orders WHERE id = ?", o.id())))
            .retrieve(o -> List.of(SqlCommand.of("SELECT id, customer FROM orders WHERE id = ?", o.id())))
            .catalog(o  -> List.of(SqlCommand.of("SELECT id, customer FROM orders")))
            .infuser(r  -> r.primary().first().map(row -> new Order(
                (String) row.get("id"), (String) row.get("customer"))).orElseThrow())
            .key(row -> new Order((String) row.get("id"), null))
            .build();
        shipments = Describer.<Shipment, SqlCommand>builder()
            .contains(s -> List.of(SqlCommand.of("SELECT 1 FROM shipment WHERE order_id = ?", s.orderId())))
            // MERGE ⇒ storing the same shipment twice is a no-op: the relay is idempotent.
            .store(s    -> List.of(SqlCommand.of(
                "MERGE INTO shipment (order_id, status) KEY (order_id) VALUES (?, ?)",
                s.orderId(), s.status())))
            .delete(s   -> List.of(SqlCommand.of("DELETE FROM shipment WHERE order_id = ?", s.orderId())))
            .retrieve(s -> List.of(SqlCommand.of(
                "SELECT order_id, status FROM shipment WHERE order_id = ?", s.orderId())))
            .catalog(s  -> List.of(SqlCommand.of("SELECT order_id, status FROM shipment")))
            .infuser(r  -> r.primary().first().map(row -> new Shipment(
                (String) row.get("order_id"), (String) row.get("status"))).orElseThrow())
            .key(row -> new Shipment((String) row.get("order_id"), null))
            .build();
        shippingRepo = new JdbcRepository<>(shippingDs, shipments);
    }

    private boolean orderPersisted(String id) throws Exception {
        return new JdbcRepository<>(primaryDs, orders).contains(new Order(id, null));
    }

    private boolean shipmentExists(String orderId) throws Exception {
        return shippingRepo.contains(new Shipment(orderId, null));
    }

    private long shipmentCount() throws Exception {
        try (var conn = shippingDs.getConnection();
             var st = conn.createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM shipment")) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    /** Builds a relay runner whose subscriber writes to the shipping database. */
    private ServiceRunner relayRunner(Consumer<ShipmentRequested> relay) {
        return ServiceRunner.builder()
            .dataSource(primaryDs)
            .describers(Repositories.builder().register(Order.class, orders).build())
            .durableEvents(ShipmentRequested.class)
            .outboxPollInterval(Duration.ofMillis(40))
            .subscribe(ShipmentRequested.class, relay)
            .build();
    }

    /** The service: place the order and request its shipment, atomically. */
    private String placeOrder(ServiceRunner runner, String customer) throws AppServiceException {
        String id = java.util.UUID.randomUUID().toString();
        return runner.run(ctx -> {
            ctx.repository(Order.class).store(new Order(id, customer));
            ctx.publish(new ShipmentRequested(id, customer));   // committed with the order
            return id;
        }, Principal.system());
    }

    // ── Happy path: both databases converge ───────────────────────────────────

    @Test
    void relay_convergesBothDatabases() throws Exception {
        Consumer<ShipmentRequested> relay = e -> store(new Shipment(e.orderId(), "REQUESTED"));
        try (var runner = relayRunner(relay)) {
            String id = placeOrder(runner, "Acme");

            assertThat(orderPersisted(id)).isTrue();          // primary: synchronous
            Await.until(() -> shipmentExists(id));             // shipping: eventually
            assertThat(shippingRepo.retrieve(new Shipment(id, null)))
                .map(Shipment::status).contains("REQUESTED");
        }
    }

    // ── Durability window: primary committed, relay not yet — nothing lost ─────

    @Test
    void relay_survivesCrashBetweenCommitAndDelivery() throws Exception {
        var down = new AtomicBoolean(true);   // shipping system unreachable at first
        Consumer<ShipmentRequested> relay = e -> {
            if (down.get()) throw new RuntimeException("shipping system down");
            store(new Shipment(e.orderId(), "REQUESTED"));
        };
        try (var runner = relayRunner(relay)) {
            String id = placeOrder(runner, "Globex");

            // This is exactly the state a crash "between the commits" would leave
            // with an enlisted route — except here it is safe: the order is
            // durable, the shipment is not yet made, and the intent is preserved
            // as a PENDING outbox row that will be redelivered on restart.
            assertThat(orderPersisted(id)).isTrue();
            assertThat(shipmentExists(id)).isFalse();
            Await.until(() -> runner.pendingEventCount().orElse(0) >= 1);

            down.set(false);                  // shipping system recovers
            Await.until(() -> shipmentExists(id));            // relay drains automatically
            Await.until(() -> runner.pendingEventCount().orElse(1) == 0);
        }
    }

    // ── At-least-once + idempotent: a lost ack does not double-ship ────────────

    @Test
    void relay_isIdempotentUnderRedelivery() throws Exception {
        var attempts = new AtomicInteger();
        Consumer<ShipmentRequested> relay = e -> {
            store(new Shipment(e.orderId(), "REQUESTED"));    // succeeds every attempt
            if (attempts.incrementAndGet() < 2) {
                // The write landed but the "ack" was lost: the outbox redelivers.
                throw new RuntimeException("ack lost after store");
            }
        };
        try (var runner = relayRunner(relay)) {
            String id = placeOrder(runner, "Initech");

            Await.until(() -> attempts.get() >= 2);
            Await.until(() -> shipmentExists(id));
            // Redelivered at least twice, yet the idempotent MERGE leaves one row.
            assertThat(shipmentCount()).isEqualTo(1);
        }
    }

    // ── Contrast: an enlisted route is synchronous (but not 2PC) ───────────────

    @Test
    void enlistedRoute_isSynchronousWithinTheService() throws Exception {
        // The same two-database write, done synchronously: the shipment is part of
        // the service's own commit boundary — visible immediately when run()
        // returns, no poller involved, and read-your-writes holds inside the
        // service. The tradeoff (see the README): the routes commit sequentially
        // before the primary, so a crash *between* those commits can leave them
        // inconsistent. When that window is unacceptable, use the relay above.
        try (var runner = ServiceRunner.builder()
                .dataSource(primaryDs)
                .describers(Repositories.builder().register(Order.class, orders).build())
                .route(shippingDs, Repositories.builder().register(Shipment.class, shipments).build())
                .build()) {

            String id = java.util.UUID.randomUUID().toString();
            boolean readYourWrites = runner.run(ctx -> {
                ctx.repository(Order.class).store(new Order(id, "Umbrella"));
                ctx.repository(Shipment.class).store(new Shipment(id, "REQUESTED"));
                // read-your-writes on the route, within the same service:
                return ctx.retrieve(Shipment.class, new Shipment(id, null)).isPresent();
            }, Principal.system());

            assertThat(readYourWrites).isTrue();
            assertThat(orderPersisted(id)).isTrue();          // both already durable,
            assertThat(shipmentExists(id)).isTrue();          // synchronously — no wait
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Relay-side store, adapting the checked ShazoException to the Consumer contract. */
    private void store(Shipment shipment) {
        try {
            shippingRepo.store(shipment);
        } catch (ShazoException e) {
            throw new RuntimeException(e);   // surfaces to the outbox → retried
        }
    }
}
