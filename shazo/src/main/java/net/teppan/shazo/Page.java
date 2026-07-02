package net.teppan.shazo;

import java.io.Serializable;

/**
 * A window onto a result set: skip {@code offset} matches, take at most
 * {@code limit}.
 *
 * <p>Passed to {@link Repository#gather(Object, Page)} and
 * {@link Repository#catalog(Object, Page)} to bound how much of a match set is
 * fetched and materialized. Walking a result a page at a time:
 *
 * <pre>{@code
 * var page = Page.of(50);
 * var slice = orders.gather(query, page);
 * while (slice.hasMore()) {
 *     process(slice);
 *     slice = orders.gather(query, page = page.next());
 * }
 * process(slice);
 * }</pre>
 *
 * <p>Paging is offset-based, so a stable overall order matters: the describer's
 * {@code catalog} query should carry an {@code ORDER BY} (or the backend
 * equivalent), otherwise pages may overlap or skip rows between calls.
 *
 * @param offset the number of leading matches to skip; must be &ge; 0
 * @param limit  the maximum number of matches to return; must be &ge; 1
 */
public record Page(int offset, int limit) implements Serializable {

    /**
     * Validates the window.
     *
     * @throws IllegalArgumentException if {@code offset} is negative or
     *                                  {@code limit} is less than 1
     */
    public Page {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0: " + offset);
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1: " + limit);
        }
    }

    /**
     * Returns the first page of the given size.
     *
     * @param limit the page size; must be &ge; 1
     * @return a page at offset 0
     */
    public static Page of(int limit) {
        return new Page(0, limit);
    }

    /**
     * Returns the page immediately after this one (same size).
     *
     * @return a page advanced by {@code limit}
     */
    public Page next() {
        return new Page(offset + limit, limit);
    }
}
