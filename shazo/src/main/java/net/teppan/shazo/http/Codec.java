package net.teppan.shazo.http;

import net.teppan.shazo.ShazoException;
import net.teppan.shazo.http.internal.SerializationCodec;

import java.io.Serializable;

/**
 * Converts a domain object of type {@code T} to a byte array for HTTP
 * transport and reconstructs it on the other side.
 *
 * <p>Use {@link #java(Class, Class[])} to obtain a default codec backed by Java
 * object serialization (requires {@code T} to implement {@link Serializable}).
 * For custom encoding (JSON, Protobuf, etc.) supply your own implementation.
 *
 * <h2>Example — custom JSON codec</h2>
 * <pre>{@code
 * Codec<Person> codec = new Codec<>() {
 *     public byte[] encode(Person p) { return objectMapper.writeValueAsBytes(p); }
 *     public Person decode(byte[] b) { return objectMapper.readValue(b, Person.class); }
 * };
 * }</pre>
 *
 * @param <T> the domain type to encode and decode
 * @see HttpRepositoryServlet
 * @see HttpRepositoryAdapter
 */
public interface Codec<T> {

    /**
     * Encodes {@code value} to a byte array.
     *
     * @param value the object to encode; must not be {@code null}
     * @return the encoded representation; never {@code null}
     * @throws ShazoException if encoding fails
     */
    byte[] encode(T value) throws ShazoException;

    /**
     * Reconstructs a {@code T} from its encoded representation.
     *
     * @param bytes the byte array produced by {@link #encode}; never {@code null}
     * @return the decoded object; never {@code null}
     * @throws ShazoException if decoding fails
     */
    T decode(byte[] bytes) throws ShazoException;

    /**
     * Returns a {@code Codec} that uses Java object serialization, guarded by a
     * deserialization allowlist.
     *
     * <p>Requires {@code T} to implement {@link Serializable}. The returned codec
     * is stateless and safe for concurrent use.
     *
     * <p>Decoding accepts only {@code type}, the optional {@code alsoAllowed}
     * classes, and a curated set of immutable JDK value packages; any other
     * class in the byte stream is rejected. List every additional non-JDK type
     * that may appear inside {@code T} (for example a sealed sub-type held in a
     * field). See {@link SerializationCodec} for the exact policy and limits.
     *
     * @param type        the domain type to encode and decode; never {@code null}
     * @param alsoAllowed additional classes permitted during decoding
     * @param <T>         a serializable domain type
     * @return a Java-serialization-backed codec with a deserialization filter
     */
    static <T extends Serializable> Codec<T> java(Class<T> type, Class<?>... alsoAllowed) {
        var allowed = new java.util.ArrayList<Class<?>>();
        allowed.add(type);
        java.util.Collections.addAll(allowed, alsoAllowed);
        return new SerializationCodec<>(
            SerializationCodec.allowlistFilter(allowed.toArray(Class<?>[]::new)));
    }
}
