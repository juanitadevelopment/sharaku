package net.teppan.shazo;

/**
 * Thrown by {@link Repository#find} when more than one entity matches the query,
 * i.e. the result was expected to be unique but was ambiguous.
 *
 * <p>This is a data-integrity signal: {@code find} is for fetching <em>the</em>
 * single entity identified by a key (a primary key, or a business key that
 * should be unique). More than one match means either the key is not as unique
 * as assumed, or the query is wrong. When several matches are an acceptable
 * outcome, use {@link Repository#gather} or {@link Repository#retrieve} instead.
 *
 * @see Repository#find
 * @see NotFoundException
 */
public final class MultipleFoundException extends ShazoException {

    /** Human-readable description of the over-matching query. */
    private final String queryDescription;

    /** The number of entities that matched. */
    private final int count;

    /**
     * Constructs a {@code MultipleFoundException}.
     *
     * @param queryDescription a human-readable description of the query
     * @param count            the number of matching entities found
     */
    public MultipleFoundException(String queryDescription, int count) {
        super("Expected a unique entity but found " + count + " for: " + queryDescription);
        this.queryDescription = queryDescription;
        this.count = count;
    }

    /**
     * Returns a human-readable description of the query that matched several rows.
     *
     * @return the query description supplied at construction
     */
    public String queryDescription() {
        return queryDescription;
    }

    /**
     * Returns the number of entities that matched the query.
     *
     * @return the match count
     */
    public int count() {
        return count;
    }
}
