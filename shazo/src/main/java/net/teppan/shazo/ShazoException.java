package net.teppan.shazo;

/**
 * Checked exception signalling a recoverable, caller-actionable storage failure.
 *
 * <p>Use {@code ShazoException} only when the caller can meaningfully react —
 * for example, by retrying, surfacing the error to the user, or falling back
 * to a secondary repository. Programming errors such as a misconfigured
 * {@link Describer} or a missing dependency throw {@link RuntimeException}
 * instead.
 *
 * @see NotFoundException
 */
public class ShazoException extends Exception {

    /**
     * Constructs a {@code ShazoException} with the given detail message.
     *
     * @param message the detail message
     */
    public ShazoException(String message) {
        super(message);
    }

    /**
     * Constructs a {@code ShazoException} wrapping an underlying cause.
     *
     * @param message the detail message
     * @param cause   the root cause
     */
    public ShazoException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Constructs a {@code ShazoException} whose message is derived from
     * the given cause.
     *
     * @param cause the root cause
     */
    public ShazoException(Throwable cause) {
        super(cause);
    }
}
