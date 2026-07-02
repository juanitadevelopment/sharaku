package net.teppan.demo.orders;

import java.io.Serializable;

/**
 * Domain event published when an order is placed. {@link Serializable} so it can
 * travel through the backbone {@linkplain net.teppan.backbone.event.Outbox outbox}.
 *
 * @param orderId  the placed order's id
 * @param customer the customer name
 */
public record OrderPlaced(String orderId, String customer) implements Serializable {
}
