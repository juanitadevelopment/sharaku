package net.teppan.demo.orders;

import net.teppan.demo.testsupport.Await;
import net.teppan.shazo.jdbc.h2.H2DataSources;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end check of the outbox relay showcase: an order placed in the primary
 * database is relayed to a shipment in a separate shipping database, and the two
 * converge without the service ever touching the second database directly.
 */
class ShipmentRelayShowcaseTest {

    private DataSource primary;
    private DataSource shipping;
    private ShipmentRelayShowcase showcase;

    @BeforeEach
    void setUp() {
        primary  = H2DataSources.inMemory("relay_orders_" + System.nanoTime());
        shipping = H2DataSources.inMemory("relay_shipping_" + System.nanoTime());
        showcase = new ShipmentRelayShowcase(primary, shipping);
    }

    @AfterEach
    void tearDown() {
        showcase.close();
    }

    @Test
    void placingAnOrderRelaysAShipmentToTheOtherDatabase() throws Exception {
        String id = showcase.placeOrder("Acme");

        // The order is durable in the primary database immediately.
        assertThat(showcase.findOrder(id)).get()
            .extracting(Order::customer, Order::status)
            .containsExactly("Acme", "NEW");

        // The shipment appears in the shipping database once the relay runs.
        Await.until(() -> showcase.findShipment(id).isPresent());
        assertThat(showcase.findShipment(id)).get()
            .extracting(Shipment::status).isEqualTo("REQUESTED");

        // The outbox drains: no event left pending.
        Await.until(() -> showcase.runner().pendingEventCount().orElse(1) == 0);
    }
}
