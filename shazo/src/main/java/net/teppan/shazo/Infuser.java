package net.teppan.shazo;

/**
 * Assembles a single domain object from the {@link Results} of a {@code retrieve}.
 *
 * <p>{@code Infuser} is the one place domain objects are constructed. It is
 * given the per-command results — a root and, for an aggregate, its children
 * (each fetched by its own query, keyed by {@link Command#name()}) — and builds
 * the object graph. Multiple queries instead of one wide {@code JOIN} means a
 * 1:N:N structure is assembled without a cartesian explosion.
 *
 * <p>{@code Infuser} is a {@link FunctionalInterface}; a lambda is the idiomatic
 * implementation, particularly for immutable records:
 *
 * <pre>{@code
 * // flat entity (single command)
 * Infuser<Person> person = results -> {
 *     var row = results.primary().first().orElseThrow();
 *     return new Person((String) row.get("id"), (String) row.get("name"),
 *                       ((Number) row.get("age")).intValue());
 * };
 *
 * // aggregate (root + children)
 * Infuser<Order> order = results -> {
 *     var head  = results.of("order").first().orElseThrow();
 *     var lines = results.of("lines").rows().stream().map(Line::from).toList();
 *     return new Order((String) head.get("id"), ..., lines);
 * };
 * }</pre>
 *
 * @param <T> the domain type constructed by this infuser
 * @see Describer#infuser()
 * @see Results
 * @see Producer
 */
@FunctionalInterface
public interface Infuser<T> {

    /**
     * Assembles an instance of {@code T} from the per-command results.
     *
     * @param results the per-command storage results; never {@code null}
     * @return a fully populated instance of {@code T}; never {@code null}
     * @throws IllegalStateException if the results lack the data required to
     *                               construct {@code T}
     */
    T infuse(Results results);
}
