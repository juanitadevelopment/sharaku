package net.teppan.demo.orders;

import net.teppan.backbone.AppContext;
import net.teppan.backbone.AppServiceException;
import net.teppan.backbone.Principal;
import net.teppan.backbone.ServiceRunner;
import net.teppan.shazo.Describer;
import net.teppan.shazo.ShazoException;
import net.teppan.shazo.jdbc.Repositories;
import net.teppan.shazo.jdbc.SchemaManager;
import net.teppan.shazo.jdbc.SqlCommand;
import net.teppan.shazo.jdbc.embedded.EmbeddedDataSource;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A small end-to-end showcase of {@code net.teppan.backbone} on top of
 * {@code net.teppan.shazo}, modelled (in miniature) on the original soba order
 * system. It exercises every pillar of the redesign:
 *
 * <ul>
 *   <li><b>Transactional services</b> — {@code placeOrder} stores the order and
 *       writes an audit row in one transaction (via {@link AppContext#repository}
 *       and {@link AppContext#connection}); a failure rolls both back.</li>
 *   <li><b>Nested composition</b> — the audit write is a nested service joined
 *       to the same transaction with {@link AppContext#call}.</li>
 *   <li><b>Durable domain events</b> — {@code OrderPlaced} is published and
 *       delivered after commit through the transactional outbox.</li>
 *   <li><b>Registered services</b> — {@code orderCount} is invoked by name.</li>
 *   <li><b>Management surface</b> — service names and pending-event count.</li>
 * </ul>
 *
 * <p>The class is both runnable ({@link #main}) and used by the showcase test.
 */
public final class OrderShowcase implements AutoCloseable {

    private static final String SCHEMA = "net/teppan/demo/orders/schema/";

    /** Describer mapping {@link Order} to the {@code orders} table. */
    static final Describer<Order, SqlCommand> ORDERS = Describer.<Order, SqlCommand>builder()
        .contains(o -> List.of(SqlCommand.of("SELECT 1 FROM orders WHERE id = ?", o.id())))
        .store(o    -> List.of(SqlCommand.of(
            "MERGE INTO orders (id, customer, status) KEY (id) VALUES (?, ?, ?)",
            o.id(), o.customer(), o.status())))
        .delete(o   -> List.of(SqlCommand.of("DELETE FROM orders WHERE id = ?", o.id())))
        .retrieve(o -> List.of(SqlCommand.of(
            "SELECT id, customer, status FROM orders WHERE id = ?", o.id())))
        .catalog(o  -> List.of(SqlCommand.of(
            "SELECT id, customer, status FROM orders ORDER BY id")))
        .key(row    -> new Order((String) row.get("id"), null, null))
        .infuser(results -> results.primary().first().map(OrderShowcase::toOrder).orElseThrow())
        .build();

    private static Order toOrder(java.util.Map<String, Object> row) {
        return new Order((String) row.get("id"), (String) row.get("customer"),
            (String) row.get("status"));
    }

    private final ServiceRunner runner;
    private final List<String> confirmationsSent = new CopyOnWriteArrayList<>();

    /**
     * Builds the showcase over {@code dataSource}, applying the schema and wiring
     * a runner with durable events and an {@code OrderPlaced} subscriber.
     *
     * @param dataSource the database; never {@code null}
     */
    public OrderShowcase(DataSource dataSource) {
        try {
            SchemaManager.apply(dataSource, SCHEMA);
        } catch (ShazoException e) {
            throw new IllegalStateException("Failed to apply orders schema", e);
        }
        this.runner = ServiceRunner.builder()
            .dataSource(dataSource)
            // Storage binding lives here, in the wiring — services name only Order.class.
            .describers(Repositories.builder().register(Order.class, ORDERS).build())
            .durableEvents(OrderPlaced.class)
            // "Send a confirmation" — delivered after the order's transaction commits.
            .subscribe(OrderPlaced.class, e -> confirmationsSent.add(e.orderId()))
            .register("orderCount", ctx -> ctx.repository(Order.class).catalog(Order.all()).size())
            .build();
    }

    /**
     * Places an order: stores it, records an audit entry in the same
     * transaction (as a nested service), and publishes {@link OrderPlaced}.
     *
     * @param customer the customer name
     * @return the new order id
     * @throws AppServiceException if the transaction fails
     */
    public String placeOrder(String customer) throws AppServiceException {
        String id = UUID.randomUUID().toString();
        return runner.run(ctx -> {
            ctx.repository(Order.class).store(new Order(id, customer, "NEW"));
            ctx.call(audit(id, "PLACED"));          // joins this transaction
            ctx.publish(new OrderPlaced(id, customer));
            return id;
        }, Principal.system());
    }

    /**
     * Marks an order shipped, recording an audit entry atomically.
     *
     * @param id the order id
     * @throws AppServiceException if the order is missing or the transaction fails
     */
    public void shipOrder(String id) throws AppServiceException {
        runner.run(ctx -> {
            var repo = ctx.repository(Order.class);
            var order = repo.retrieve(Order.byId(id))
                .orElseThrow(() -> new AppServiceException("No such order: " + id));
            repo.store(new Order(order.id(), order.customer(), "SHIPPED"));
            ctx.call(audit(id, "SHIPPED"));
            return null;
        }, Principal.system());
    }

    /** Returns a nested audit-writing service capturing the given parameters. */
    private static net.teppan.backbone.AppService<Void> audit(String orderId, String action) {
        return ctx -> {
            try (var ps = ctx.connection().prepareStatement(
                    "INSERT INTO order_audit (order_id, action) VALUES (?, ?)")) {
                ps.setString(1, orderId);
                ps.setString(2, action);
                ps.executeUpdate();
            }
            return null;
        };
    }

    /** Retrieves an order by id. */
    public Optional<Order> find(String id) throws AppServiceException {
        return runner.run(ctx -> ctx.repository(Order.class).retrieve(Order.byId(id)),
            Principal.system());
    }

    /** Invokes the registered {@code orderCount} service by name. */
    public int orderCount() throws AppServiceException {
        return runner.execute("orderCount", Principal.system());
    }

    /** Order ids for which a confirmation was delivered (after commit). */
    public List<String> confirmationsSent() {
        return List.copyOf(confirmationsSent);
    }

    /** The backbone runner, for management/introspection in the showcase test. */
    public ServiceRunner runner() {
        return runner;
    }

    @Override
    public void close() {
        runner.close();
    }

    // ── Runnable demo ───────────────────────────────────────────────────────────

    /**
     * Runs a short scenario against an in-memory database and prints the result.
     *
     * @param args ignored
     * @throws Exception if the scenario fails
     */
    public static void main(String[] args) throws Exception {
        DataSource ds = EmbeddedDataSource.inMemory("orders-showcase");
        try (var showcase = new OrderShowcase(ds)) {
            String a = showcase.placeOrder("Acme Corp");
            String b = showcase.placeOrder("Globex");
            showcase.shipOrder(a);

            // Give the outbox poller a moment to deliver the confirmations.
            Thread.sleep(500);

            System.out.println("orders        : " + showcase.orderCount());
            System.out.println("order A       : " + showcase.find(a).orElseThrow());
            System.out.println("order B       : " + showcase.find(b).orElseThrow());
            System.out.println("confirmations : " + showcase.confirmationsSent());
            System.out.println("services      : " + showcase.runner().serviceNames());
            System.out.println("pending events: " + showcase.runner().pendingEventCount());
        }
    }
}
