package net.teppan.demo.orders;

/**
 * A shipment record living in a separate "shipping" database — a stand-in for
 * another system with its own operational boundary, reached via the outbox
 * relay pattern.
 *
 * @param orderId the order this shipment is for (its key)
 * @param status  the shipment status ({@code REQUESTED} → …)
 */
public record Shipment(String orderId, String status) {

    /** Returns an id-only criterion for retrieve/contains. */
    public static Shipment forOrder(String orderId) {
        return new Shipment(orderId, null);
    }
}
