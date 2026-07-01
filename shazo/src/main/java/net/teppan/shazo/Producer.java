package net.teppan.shazo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.function.Function;

/**
 * Converts a raw storage value (typically a {@link String}, {@link Number},
 * or {@code null} from a JDBC result set or shell output) to a typed Java value.
 *
 * <p>{@code Producer} is a {@link FunctionalInterface}: lambda expressions
 * and method references are valid implementations.
 *
 * <h2>Using built-in producers</h2>
 * <pre>{@code
 * Producer<String>  s = Producer.asString();
 * Producer<Integer> i = Producer.asInteger();
 * Producer<Long>    l = Producer.asLong();
 *
 * // Compose:
 * Producer<String> trimmed = Producer.asString().andThen(String::strip);
 * }</pre>
 *
 * @param <T> the target type produced by this converter
 * @see RawResult#firstValue(String, Producer)
 */
@FunctionalInterface
public interface Producer<T> {

    /**
     * Converts {@code rawValue} to an instance of {@code T}.
     *
     * @param rawValue the raw value from storage; may be {@code null}
     * @return the converted value; may be {@code null} when {@code rawValue} is {@code null}
     */
    T produce(Object rawValue);

    // ── Composition ──────────────────────────────────────────────────────────

    /**
     * Returns a producer that first applies this producer and then maps
     * the result through {@code fn}.
     *
     * @param fn  the post-processing function
     * @param <R> the final target type
     * @return a composed producer
     */
    default <R> Producer<R> andThen(Function<? super T, ? extends R> fn) {
        return v -> fn.apply(this.produce(v));
    }

    // ── Built-in factories ───────────────────────────────────────────────────

    /**
     * Returns a producer that converts any value to a {@link String}
     * via {@code toString()}, preserving {@code null}.
     *
     * @return a {@code String} producer
     */
    static Producer<String> asString() {
        return v -> v == null ? null : v.toString();
    }

    /**
     * Returns a producer that converts numeric and string values to
     * {@link Integer}.
     *
     * @return an {@code Integer} producer
     */
    static Producer<Integer> asInteger() {
        return v -> switch (v) {
            case null      -> null;
            case Integer i -> i;
            case Number  n -> n.intValue();
            case String  s -> Integer.parseInt(s);
            default -> throw new ClassCastException(
                "Cannot convert " + v.getClass().getName() + " to Integer");
        };
    }

    /**
     * Returns a producer that converts numeric and string values to
     * {@link Long}.
     *
     * @return a {@code Long} producer
     */
    static Producer<Long> asLong() {
        return v -> switch (v) {
            case null     -> null;
            case Long   l -> l;
            case Number n -> n.longValue();
            case String s -> Long.parseLong(s);
            default -> throw new ClassCastException(
                "Cannot convert " + v.getClass().getName() + " to Long");
        };
    }

    /**
     * Returns a producer that converts numeric and string values to
     * {@link Double}.
     *
     * @return a {@code Double} producer
     */
    static Producer<Double> asDouble() {
        return v -> switch (v) {
            case null      -> null;
            case Double  d -> d;
            case Number  n -> n.doubleValue();
            case String  s -> Double.parseDouble(s);
            default -> throw new ClassCastException(
                "Cannot convert " + v.getClass().getName() + " to Double");
        };
    }

    /**
     * Returns a producer that converts numeric and string values to
     * {@link BigDecimal}.
     *
     * @return a {@code BigDecimal} producer
     */
    static Producer<BigDecimal> asBigDecimal() {
        return v -> switch (v) {
            case null          -> null;
            case BigDecimal bd -> bd;
            case Number      n -> BigDecimal.valueOf(n.doubleValue());
            case String      s -> new BigDecimal(s);
            default -> throw new ClassCastException(
                "Cannot convert " + v.getClass().getName() + " to BigDecimal");
        };
    }

    /**
     * Returns a producer that coerces values to {@link Boolean}.
     * Numbers coerce by {@code intValue() != 0}; strings by
     * {@link Boolean#parseBoolean}.
     *
     * @return a {@code Boolean} producer
     */
    static Producer<Boolean> asBoolean() {
        return v -> switch (v) {
            case null      -> null;
            case Boolean b -> b;
            case Number  n -> n.intValue() != 0;
            case String  s -> Boolean.parseBoolean(s);
            default -> throw new ClassCastException(
                "Cannot convert " + v.getClass().getName() + " to Boolean");
        };
    }

    /**
     * Returns a producer that converts {@link java.sql.Date} instances or
     * ISO-8601 date strings to {@link LocalDate}.
     *
     * @return a {@code LocalDate} producer
     */
    static Producer<LocalDate> asLocalDate() {
        return v -> switch (v) {
            case null        -> null;
            case LocalDate d -> d;
            case java.sql.Date d -> d.toLocalDate();
            case String      s -> LocalDate.parse(s);
            default -> throw new ClassCastException(
                "Cannot convert " + v.getClass().getName() + " to LocalDate");
        };
    }

    /**
     * Returns a producer that converts {@link java.sql.Timestamp} instances or
     * ISO-8601 datetime strings to {@link LocalDateTime}.
     *
     * @return a {@code LocalDateTime} producer
     */
    static Producer<LocalDateTime> asLocalDateTime() {
        return v -> switch (v) {
            case null                  -> null;
            case LocalDateTime      dt -> dt;
            case java.sql.Timestamp ts -> ts.toLocalDateTime();
            case String              s -> LocalDateTime.parse(s);
            default -> throw new ClassCastException(
                "Cannot convert " + v.getClass().getName() + " to LocalDateTime");
        };
    }
}
