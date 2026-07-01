package net.teppan.shazo;

/**
 * Thrown by {@link Repository#find} when no entity matching the query exists
 * in storage.
 *
 * <p>This exception is checked because a missing entity is a normal, expected
 * query outcome that callers are responsible for handling explicitly.
 * Use {@link Repository#retrieve} and {@link java.util.Optional} when absence
 * is equally valid as presence.
 *
 * @see Repository#retrieve
 * @see Repository#find
 */
public final class NotFoundException extends ShazoException {

    /** Human-readable description of the query that produced no result. */
    private final String queryDescription;

    /**
     * Constructs a {@code NotFoundException} for the given query.
     *
     * @param queryDescription a human-readable description of the query
     *                         that produced no result
     */
    public NotFoundException(String queryDescription) {
        super("No entity found for: " + queryDescription);
        this.queryDescription = queryDescription;
    }

    /**
     * Returns a human-readable description of the query that found no match.
     *
     * @return the query description supplied at construction
     */
    public String queryDescription() {
        return queryDescription;
    }
}
