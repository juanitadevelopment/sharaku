package net.teppan.demo.orders;

/**
 * A tiny order domain object for the backbone showcase: an id, the customer,
 * and a lifecycle status ({@code NEW} → {@code SHIPPED}).
 *
 * @param id       unique order id
 * @param customer customer name
 * @param status   lifecycle status
 */
public record Order(String id, String customer, String status) {

    /** Returns an id-only criterion for retrieve/contains/delete. */
    public static Order byId(String id) {
        return new Order(id, null, null);
    }

    /** Returns a criterion matching every order (for catalog). */
    public static Order all() {
        return new Order(null, null, null);
    }
}
