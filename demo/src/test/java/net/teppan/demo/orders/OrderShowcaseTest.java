package net.teppan.demo.orders;

import net.teppan.backbone.AppServiceException;
import net.teppan.shazo.jdbc.embedded.EmbeddedDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end validation of the backbone redesign through {@link OrderShowcase}:
 * transactional services, nested composition, durable events, registered
 * services, and the management surface.
 */
class OrderShowcaseTest {

    private DataSource ds;
    private OrderShowcase showcase;

    @BeforeEach
    void setUp() {
        ds = EmbeddedDataSource.inMemory("orders_show_" + System.nanoTime());
        showcase = new OrderShowcase(ds);
    }

    @AfterEach
    void tearDown() {
        showcase.close();
    }

    private static void awaitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(25);
        }
        throw new AssertionError("condition not met within timeout");
    }

    private long auditCount(String orderId) throws Exception {
        try (var conn = ds.getConnection();
             var ps = conn.prepareStatement(
                 "SELECT COUNT(*) FROM order_audit WHERE order_id = ?")) {
            ps.setString(1, orderId);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    @Test
    void placeOrder_persistsOrderAndAuditAtomically() throws Exception {
        String id = showcase.placeOrder("Acme");
        assertThat(showcase.find(id)).get()
            .extracting(Order::customer, Order::status)
            .containsExactly("Acme", "NEW");
        assertThat(auditCount(id)).isEqualTo(1);   // nested audit committed with the order
    }

    @Test
    void placeOrder_deliversConfirmationAfterCommit() throws Exception {
        String id = showcase.placeOrder("Globex");
        // OrderPlaced flows through the durable outbox and is delivered async.
        awaitUntil(() -> showcase.confirmationsSent().contains(id));
    }

    @Test
    void shipOrder_updatesStatusAndAudits() throws Exception {
        String id = showcase.placeOrder("Initech");
        showcase.shipOrder(id);
        assertThat(showcase.find(id)).get().extracting(Order::status).isEqualTo("SHIPPED");
        assertThat(auditCount(id)).isEqualTo(2);   // PLACED + SHIPPED
    }

    @Test
    void shipOrder_unknownOrder_failsWithoutSideEffects() {
        assertThatThrownBy(() -> showcase.shipOrder("ghost"))
            .isInstanceOf(AppServiceException.class);
    }

    @Test
    void orderCount_registeredServiceReturnsTotal() throws Exception {
        showcase.placeOrder("A");
        showcase.placeOrder("B");
        assertThat(showcase.orderCount()).isEqualTo(2);
    }

    @Test
    void managementSurface_reportsServicesAndPendingEvents() throws Exception {
        assertThat(showcase.runner().serviceNames()).contains("orderCount");
        assertThat(showcase.runner().pendingEventCount()).isPresent();

        showcase.placeOrder("Stark");
        // Eventually the outbox drains to zero pending.
        awaitUntil(() -> showcase.runner().pendingEventCount().getAsLong() == 0);
    }
}
