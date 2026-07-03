package net.teppan.demo.orders;

import java.io.Serializable;

/**
 * Domain event published when an order needs a shipment created in a
 * <em>separate</em> shipping database. {@link Serializable} so it can travel
 * through the backbone {@linkplain net.teppan.backbone.event.Outbox outbox} and
 * be relayed to the other system at-least-once.
 *
 * @param orderId  the order that needs shipping
 * @param customer the customer name
 */
public record ShipmentRequested(String orderId, String customer) implements Serializable {
}
