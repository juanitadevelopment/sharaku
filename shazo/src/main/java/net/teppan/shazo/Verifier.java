package net.teppan.shazo;

/**
 * Decides whether a {@link RawResult} satisfies a storage operation's
 * success criterion.
 *
 * <p>The default verifier for all {@link Describer} implementations is
 * {@link #nonEmpty()} — the operation is considered successful when the
 * result contains at least one row. Override {@link Describer#verifier()}
 * to use a custom criterion.
 *
 * <p>{@code Verifier} is a {@link FunctionalInterface}; lambda expressions
 * are valid implementations.
 *
 * @see Describer#verifier()
 * @see Repository#contains
 */
@FunctionalInterface
public interface Verifier {

    /**
     * Returns {@code true} if {@code result} satisfies this verifier's criterion.
     *
     * @param result the raw storage result; never {@code null}
     * @return {@code true} if the result should be treated as a successful match
     */
    boolean verify(RawResult result);

    // ── Built-in implementations ─────────────────────────────────────────────

    /**
     * Returns a verifier that passes when the result contains at least one row.
     * This is the default verifier used by {@link Describer#verifier()}.
     *
     * @return the non-empty verifier
     */
    static Verifier nonEmpty() {
        return result -> !result.isEmpty();
    }

    /**
     * Returns a verifier that always passes, regardless of row count.
     * Use when the operation has no meaningful success criterion (e.g., delete
     * that is idempotent).
     *
     * @return the always-true verifier
     */
    static Verifier always() {
        return result -> true;
    }

    /**
     * Returns a verifier that passes when the result contains at least
     * {@code n} rows.
     *
     * @param n the minimum row count; must be ≥ 0
     * @return a min-rows verifier
     * @throws IllegalArgumentException if {@code n} is negative
     */
    static Verifier minRows(int n) {
        if (n < 0) throw new IllegalArgumentException("n must be >= 0, got " + n);
        return result -> result.size() >= n;
    }

    /**
     * Returns a verifier that passes when the result contains exactly
     * {@code n} rows.
     *
     * @param n the required row count; must be ≥ 0
     * @return an exact-rows verifier
     * @throws IllegalArgumentException if {@code n} is negative
     */
    static Verifier exactRows(int n) {
        if (n < 0) throw new IllegalArgumentException("n must be >= 0, got " + n);
        return result -> result.size() == n;
    }
}
