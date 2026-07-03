package net.teppan.demo.orders;

import net.teppan.backbone.AppServiceException;
import net.teppan.backbone.Principal;
import net.teppan.backbone.ServiceRunner;
import net.teppan.shazo.Describer;
import net.teppan.shazo.ShazoException;
import net.teppan.shazo.jdbc.JdbcRepository;
import net.teppan.shazo.jdbc.Repositories;
import net.teppan.shazo.jdbc.SchemaManager;
import net.teppan.shazo.jdbc.SqlCommand;
import net.teppan.shazo.jdbc.h2.H2DataSources;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The <b>outbox relay</b> pattern end-to-end (see backbone's "Multi-database
 * services"): one service that writes an order to its <em>primary</em> database
 * and must also create a shipment in a <em>separate</em> shipping database —
 * without ever losing that second write, even across a crash.
 *
 * <p>Placing an order commits, in one primary transaction, both the order row
 * and a durable {@link ShipmentRequested} event. A subscriber then relays that
 * event to the shipping database, idempotently, on the outbox poller thread.
 * Because the event is committed atomically with the order and delivered
 * at-least-once, the two databases are guaranteed to converge; the only cost is
 * that the shipment appears a poll interval later.
 *
 * <p>Contrast with {@link OrderShowcase}, which keeps everything in one database.
 * When the second write instead needs to be <em>synchronous</em>, an enlisted
 * {@link ServiceRunner.Builder#route route} is the other tool — see the backbone
 * README for the trade-off.
 *
 * <p>Runnable via {@link #main} and exercised by the showcase test.
 */
public final class ShipmentRelayShowcase implements AutoCloseable {

    private static final String ORDERS_SCHEMA = "net/teppan/demo/orders/schema/";

    /** Describer mapping {@link Shipment} to the shipping database's table. */
    static final Describer<Shipment, SqlCommand> SHIPMENTS =
        Describer.<Shipment, SqlCommand>builder()
            .contains(s -> List.of(SqlCommand.of(
                "SELECT 1 FROM shipment WHERE order_id = ?", s.orderId())))
            // MERGE ⇒ relaying the same shipment twice is a no-op: idempotent.
            .store(s    -> List.of(SqlCommand.of(
                "MERGE INTO shipment (order_id, status) KEY (order_id) VALUES (?, ?)",
                s.orderId(), s.status())))
            .delete(s   -> List.of(SqlCommand.of(
                "DELETE FROM shipment WHERE order_id = ?", s.orderId())))
            .retrieve(s -> List.of(SqlCommand.of(
                "SELECT order_id, status FROM shipment WHERE order_id = ?", s.orderId())))
            .catalog(s  -> List.of(SqlCommand.of(
                "SELECT order_id, status FROM shipment ORDER BY order_id")))
            .key(row    -> Shipment.forOrder((String) row.get("order_id")))
            .infuser(results -> results.primary().first().map(row -> new Shipment(
                (String) row.get("order_id"), (String) row.get("status"))).orElseThrow())
            .build();

    private final DataSource shippingDs;
    private final JdbcRepository<Shipment> shippingRepo;
    private final ServiceRunner runner;

    /**
     * Builds the showcase over two databases.
     *
     * @param primaryDs  the order (and outbox) database; never {@code null}
     * @param shippingDs the separate shipping database; never {@code null}
     */
    public ShipmentRelayShowcase(DataSource primaryDs, DataSource shippingDs) {
        try {
            SchemaManager.apply(primaryDs, ORDERS_SCHEMA);
        } catch (ShazoException e) {
            throw new IllegalStateException("Failed to apply orders schema", e);
        }
        this.shippingDs = shippingDs;
        createShippingTable(shippingDs);
        this.shippingRepo = new JdbcRepository<>(shippingDs, SHIPMENTS);

        this.runner = ServiceRunner.builder()
            .dataSource(primaryDs)
            .describers(Repositories.builder().register(Order.class, OrderShowcase.ORDERS).build())
            .durableEvents(ShipmentRequested.class)
            // The relay: after the order commits, request the shipment in the
            // other database. Runs on the poller, retried until it succeeds; the
            // MERGE-backed store makes redelivery harmless.
            .subscribe(ShipmentRequested.class, this::relayShipment)
            .build();
    }

    private void relayShipment(ShipmentRequested e) {
        try {
            shippingRepo.store(new Shipment(e.orderId(), "REQUESTED"));
        } catch (ShazoException ex) {
            // Surfacing to the outbox schedules a retry (at-least-once).
            throw new IllegalStateException("shipment relay failed for " + e.orderId(), ex);
        }
    }

    /**
     * Places an order in the primary database and requests its shipment in the
     * same transaction (as a durable event, relayed afterwards).
     *
     * @param customer the customer name
     * @return the new order id
     * @throws AppServiceException if the transaction fails
     */
    public String placeOrder(String customer) throws AppServiceException {
        String id = UUID.randomUUID().toString();
        return runner.run(ctx -> {
            ctx.repository(Order.class).store(new Order(id, customer, "NEW"));
            ctx.publish(new ShipmentRequested(id, customer));   // committed with the order
            return id;
        }, Principal.system());
    }

    /** Retrieves an order from the primary database. */
    public Optional<Order> findOrder(String id) throws AppServiceException {
        return runner.run(ctx -> ctx.repository(Order.class).retrieve(Order.byId(id)),
            Principal.system());
    }

    /** Reads a shipment straight from the shipping database (bypassing the runner). */
    public Optional<Shipment> findShipment(String orderId) throws ShazoException {
        return shippingRepo.retrieve(Shipment.forOrder(orderId));
    }

    /** The backbone runner, for outbox introspection in the showcase test. */
    public ServiceRunner runner() {
        return runner;
    }

    @Override
    public void close() {
        runner.close();
    }

    private static void createShippingTable(DataSource ds) {
        try (var conn = ds.getConnection(); var st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS shipment ("
                + "order_id VARCHAR(36) PRIMARY KEY, status VARCHAR(40) NOT NULL)");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Failed to create shipping table", e);
        }
    }

    // ── Runnable demo ───────────────────────────────────────────────────────────

    /**
     * Runs a short scenario against two in-memory databases and prints how the
     * shipment converges in the second one after the order commits in the first.
     *
     * @param args ignored
     * @throws Exception if the scenario fails
     */
    public static void main(String[] args) throws Exception {
        DataSource primary  = H2DataSources.inMemory("relay-orders");
        DataSource shipping = H2DataSources.inMemory("relay-shipping");
        try (var showcase = new ShipmentRelayShowcase(primary, shipping)) {
            String id = showcase.placeOrder("Acme Corp");

            System.out.println("order placed      : " + showcase.findOrder(id).orElseThrow());
            System.out.println("shipment (t+0)    : " + showcase.findShipment(id)
                + "   <- not yet; relayed asynchronously");

            // Give the outbox poller a moment to relay to the shipping database.
            Thread.sleep(500);

            System.out.println("shipment (t+0.5s) : " + showcase.findShipment(id).orElseThrow());
            System.out.println("pending events    : " + showcase.runner().pendingEventCount());
        }
    }
}
