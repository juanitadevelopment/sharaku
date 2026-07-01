package net.teppan.shazo.http.internal;

import net.teppan.shazo.ShazoException;
import net.teppan.shazo.http.Codec;

import java.io.*;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

/**
 * Default {@link Codec} implementation using Java object serialization.
 * Requires {@code T} to implement {@link Serializable}.
 *
 * <p>Deserialization is the dangerous half of Java serialization: a hostile
 * byte stream can drive {@link ObjectInputStream#readObject()} to instantiate
 * arbitrary {@link Serializable} classes on the classpath, which is the basis
 * of "gadget chain" remote-code-execution attacks. Because the HTTP transport
 * feeds {@link #decode(byte[])} bytes received over the network, this codec
 * installs an {@link ObjectInputFilter} that:
 *
 * <ul>
 *   <li>allows only an explicit set of classes (the domain type and any extra
 *       types the caller lists) plus a curated set of immutable JDK value
 *       packages ({@code java.lang}, {@code java.util}, {@code java.time},
 *       {@code java.math}), and rejects everything else;</li>
 *   <li>caps stream depth, reference count, total bytes, and array length to
 *       bound resource consumption.</li>
 * </ul>
 *
 * <p>For full control, construct with a custom {@link ObjectInputFilter}.
 *
 * @param <T> a serializable domain type
 */
public final class SerializationCodec<T extends Serializable> implements Codec<T> {

    /** Maximum object-graph depth permitted while deserializing. */
    static final int  MAX_DEPTH = 32;
    /** Maximum number of object references permitted in a single stream. */
    static final int  MAX_REFS  = 10_000;
    /** Maximum number of stream bytes permitted for a single object. */
    static final long MAX_BYTES = 10_000_000L;
    /** Maximum array length permitted while deserializing. */
    static final int  MAX_ARRAY = 100_000;

    private final ObjectInputFilter filter;

    /**
     * Constructs a codec with an explicit deserialization filter.
     *
     * @param filter the filter applied to every {@link #decode} call; never {@code null}
     */
    public SerializationCodec(ObjectInputFilter filter) {
        this.filter = Objects.requireNonNull(filter, "filter");
    }

    @Override
    public byte[] encode(T value) throws ShazoException {
        var baos = new ByteArrayOutputStream();
        try (var oos = new ObjectOutputStream(baos)) {
            oos.writeObject(value);
        } catch (IOException e) {
            throw new ShazoException("Serialization failed", e);
        }
        return baos.toByteArray();
    }

    @Override
    @SuppressWarnings("unchecked")
    public T decode(byte[] bytes) throws ShazoException {
        // The cast is sound when the stream was produced by encode(); the
        // installed filter rejects any class outside the allowlist before it
        // can be instantiated, defending against gadget-chain payloads.
        try (var ois = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            ois.setObjectInputFilter(filter);
            return (T) ois.readObject();
        } catch (InvalidClassException e) {
            throw new ShazoException("Deserialization rejected by filter", e);
        } catch (IOException | ClassNotFoundException e) {
            throw new ShazoException("Deserialization failed", e);
        }
    }

    /**
     * Builds the default allowlist {@link ObjectInputFilter}: it permits the
     * given classes (and their array forms), primitive types, and immutable JDK
     * value packages, rejects all other classes, and enforces the {@code MAX_*}
     * resource limits.
     *
     * @param allowed the application classes to permit; never {@code null}
     * @return a filter suitable for untrusted input
     */
    public static ObjectInputFilter allowlistFilter(Class<?>... allowed) {
        Set<Class<?>> allowedSet = Set.copyOf(Arrays.asList(allowed));
        return info -> {
            if (info.depth()      > MAX_DEPTH) return ObjectInputFilter.Status.REJECTED;
            if (info.references() > MAX_REFS)  return ObjectInputFilter.Status.REJECTED;
            if (info.streamBytes() > MAX_BYTES) return ObjectInputFilter.Status.REJECTED;
            if (info.arrayLength() > MAX_ARRAY) return ObjectInputFilter.Status.REJECTED;

            Class<?> clazz = info.serialClass();
            if (clazz == null) return ObjectInputFilter.Status.UNDECIDED; // limit-only check

            // Unwrap arrays down to their element type.
            Class<?> element = clazz;
            while (element.isArray()) element = element.getComponentType();

            if (element.isPrimitive())       return ObjectInputFilter.Status.ALLOWED;
            if (allowedSet.contains(element)) return ObjectInputFilter.Status.ALLOWED;
            if (isSafeJdkType(element))       return ObjectInputFilter.Status.ALLOWED;
            return ObjectInputFilter.Status.REJECTED;
        };
    }

    private static boolean isSafeJdkType(Class<?> c) {
        String n = c.getName();
        return n.startsWith("java.lang.")
            || n.startsWith("java.util.")
            || n.startsWith("java.time.")
            || n.startsWith("java.math.");
    }
}
