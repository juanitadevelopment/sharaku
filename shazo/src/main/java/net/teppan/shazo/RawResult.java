package net.teppan.shazo;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * An immutable tabular result returned by a storage operation.
 *
 * <p>Each row is a column-name-to-raw-value mapping with two guarantees:
 *
 * <ul>
 *   <li><strong>Column order is preserved.</strong> Iterating a row
 *       ({@code keySet()}/{@code entrySet()}) yields the columns in the order
 *       the backend produced them — for a JDBC backend, the {@code SELECT}
 *       list order. Tabular consumers (grids, reports, CSV exports) can rely
 *       on it.</li>
 *   <li><strong>Lookups are case-insensitive.</strong> An infuser can use
 *       {@code row.get("id")} regardless of whether the backend reports
 *       {@code ID}, {@code id}, or {@code Id} — this decouples describers from
 *       a backend's column-casing conventions (for example H2 upper-cases
 *       names by default). If two columns differ only in case, both are kept
 *       in order and a lookup resolves to the first.</li>
 * </ul>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * RawResult result = ...;
 *
 * // Extract a single value from the first row:
 * String name = result.firstValue("name", Producer.asString()).orElse("unknown");
 *
 * // Map all rows to a domain type:
 * List<Person> people = result.rows().stream()
 *     .map(row -> new Person(
 *         (String) row.get("id"),
 *         (String) row.get("name"),
 *         ((Number) row.get("age")).intValue()))
 *     .toList();
 * }</pre>
 *
 * @param rows the result rows; never {@code null}, elements are never {@code null}
 */
public record RawResult(List<Map<String, Object>> rows) {

    /**
     * Compact constructor — copies each row into an immutable map that keeps
     * the source's column order while resolving lookups case-insensitively.
     */
    public RawResult {
        rows = rows.stream().map(row -> (Map<String, Object>) new Row(row)).toList();
    }

    /**
     * An immutable row: iteration follows the source map's insertion order
     * (the backend's column order), while {@code get}/{@code containsKey}
     * resolve column names case-insensitively.
     */
    private static final class Row extends AbstractMap<String, Object> {

        private final Map<String, Object> ordered;    // insertion order, original keys
        private final Map<String, String> canonical;  // folded key -> first original key

        Row(Map<String, Object> source) {
            var byInsertion = new LinkedHashMap<String, Object>(source);
            var byFoldedKey = new HashMap<String, String>(byInsertion.size());
            for (String key : byInsertion.keySet()) {
                byFoldedKey.putIfAbsent(fold(key), key);
            }
            this.ordered   = Collections.unmodifiableMap(byInsertion);
            this.canonical = byFoldedKey;
        }

        private static String fold(String key) {
            return key.toLowerCase(Locale.ROOT);
        }

        private String resolve(Object key) {
            return (key instanceof String s) ? canonical.get(fold(s)) : null;
        }

        @Override
        public Object get(Object key) {
            String actual = resolve(key);
            return actual == null ? null : ordered.get(actual);
        }

        @Override
        public boolean containsKey(Object key) {
            return resolve(key) != null;
        }

        @Override
        public Set<Entry<String, Object>> entrySet() {
            return ordered.entrySet();   // unmodifiable, insertion-ordered
        }

        @Override
        public int size() {
            return ordered.size();
        }
    }

    /**
     * Returns an empty result with no rows.
     *
     * @return a {@code RawResult} containing zero rows
     */
    public static RawResult empty() {
        return new RawResult(List.of());
    }

    /**
     * Constructs a result from the given rows (defensively copied).
     *
     * @param rows the result rows
     * @return a new {@code RawResult}
     */
    public static RawResult of(List<Map<String, Object>> rows) {
        return new RawResult(rows);
    }

    /**
     * Returns {@code true} if this result contains no rows.
     *
     * @return {@code true} when the row count is zero
     */
    public boolean isEmpty() {
        return rows.isEmpty();
    }

    /**
     * Returns the number of rows in this result.
     *
     * @return the row count; never negative
     */
    public int size() {
        return rows.size();
    }

    /**
     * Returns the first row, if any.
     *
     * @return an {@link Optional} containing the first row's column map, or empty
     */
    public Optional<Map<String, Object>> first() {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    /**
     * Extracts and converts a column value from the first row using a
     * {@link Producer}.
     *
     * @param column   the column name (case-insensitive, like all row lookups)
     * @param producer the value converter
     * @param <T>      the target type
     * @return the produced value, or empty if there are no rows or the column
     *         is absent from the first row
     */
    public <T> Optional<T> firstValue(String column, Producer<T> producer) {
        return first()
                .filter(row -> row.containsKey(column))
                .map(row -> producer.produce(row.get(column)));
    }
}
