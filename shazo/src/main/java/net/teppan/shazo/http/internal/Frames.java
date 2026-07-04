package net.teppan.shazo.http.internal;

import java.io.DataInputStream;
import java.io.IOException;

/**
 * Bounds checks for the length- and count-prefixed fields of the wire protocol.
 *
 * <p>The client decodes responses from a <strong>fully buffered</strong> byte
 * array (the HTTP body is read whole before parsing), so at any point the number
 * of bytes still available is known exactly. Every count or length read from the
 * stream must be backed by at least that many remaining bytes: a list cannot hold
 * more elements than there are bytes left to describe them (each element is at
 * least one byte), and a byte/string field cannot be longer than what remains.
 *
 * <p>Validating against {@link DataInputStream#available()} turns two failure
 * modes into a clean {@link IOException} (which the adapter maps to
 * {@code ShazoException}) instead of a crash: a hostile or corrupt response that
 * claims a huge count in a few bytes can no longer drive a giant pre-allocation
 * (out-of-memory), and a negative count/length can no longer escape as an
 * unchecked {@link IllegalArgumentException} from {@code new ArrayList<>(n)}.
 *
 * <p>The bound is deliberately loose (it does not model each element's exact
 * width) — it only has to be small enough to prevent unbounded allocation, and
 * "no more elements than remaining bytes" achieves that with no per-format
 * bookkeeping.
 */
public final class Frames {

    private Frames() {}

    /**
     * Reads a 32-bit count/length, rejecting a negative value or one larger than
     * the bytes still available in {@code in}.
     *
     * @param in   the fully-buffered input stream to read from
     * @param what a short label for the field, used in error messages
     * @return the validated non-negative value
     * @throws IOException if the value is negative or exceeds the remaining bytes
     */
    public static int readBounded(DataInputStream in, String what) throws IOException {
        int n = in.readInt();
        if (n < 0) {
            throw new IOException("Malformed frame: negative " + what + " " + n);
        }
        int remaining = in.available();
        if (n > remaining) {
            throw new IOException("Malformed frame: " + what + " " + n
                + " exceeds " + remaining + " remaining byte(s)");
        }
        return n;
    }
}
