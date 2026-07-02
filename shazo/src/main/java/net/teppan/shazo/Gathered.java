package net.teppan.shazo;

import java.util.Iterator;
import java.util.List;

/**
 * One page of a {@link Repository#gather(Object, Page) gather}: the entities in
 * the requested window, plus whether more matches exist beyond it.
 *
 * <p>{@code hasMore} is determined exactly (by probing one row past the window),
 * not guessed from a full page — a result whose size equals the page limit but
 * that ends the match set reports {@code hasMore() == false}.
 *
 * <p>Iterable for convenience:
 * <pre>{@code
 * for (Order o : orders.gather(query, Page.of(50))) { ... }
 * }</pre>
 *
 * @param items   the entities in this page, in catalog order; never {@code null}
 * @param hasMore {@code true} if matches exist beyond this page
 * @param <T>     the domain type
 */
public record Gathered<T>(List<T> items, boolean hasMore) implements Iterable<T> {

    /**
     * Copies {@code items} into an immutable list.
     */
    public Gathered {
        items = List.copyOf(items);
    }

    /**
     * Returns {@code true} if this page contains no entities.
     *
     * @return whether the page is empty
     */
    public boolean isEmpty() {
        return items.isEmpty();
    }

    /**
     * Returns the number of entities in this page.
     *
     * @return the page's entity count
     */
    public int size() {
        return items.size();
    }

    @Override
    public Iterator<T> iterator() {
        return items.iterator();
    }
}
