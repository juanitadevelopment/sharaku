package net.teppan.shazo;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link RawResult}'s row semantics: column order preservation,
 * case-insensitive lookups, and immutability.
 */
class RawResultTest {

    private static Map<String, Object> orderedRow() {
        // Deliberately non-alphabetical: order must come from insertion, not sorting.
        var row = new LinkedHashMap<String, Object>();
        row.put("zeta",   1);
        row.put("alpha",  2);
        row.put("midway", 3);
        return row;
    }

    @Test
    void preservesColumnOrder() {
        var result = RawResult.of(List.of(orderedRow()));

        assertThat(result.rows().getFirst().keySet())
            .containsExactly("zeta", "alpha", "midway");
    }

    @Test
    void lookupsAreCaseInsensitive() {
        var result = RawResult.of(List.of(orderedRow()));
        var row = result.rows().getFirst();

        assertThat(row.get("ZETA")).isEqualTo(1);
        assertThat(row.get("Alpha")).isEqualTo(2);
        assertThat(row.containsKey("MIDWAY")).isTrue();
        assertThat(row.get("missing")).isNull();
        assertThat(row.containsKey("missing")).isFalse();
    }

    @Test
    void firstValueResolvesCaseInsensitively() {
        var result = RawResult.of(List.of(orderedRow()));

        assertThat(result.firstValue("ALPHA", Producer.asInteger())).contains(2);
        assertThat(result.firstValue("nope", Producer.asInteger())).isEmpty();
    }

    @Test
    void duplicateCaseKeysAreKeptInOrderAndLookupResolvesToTheFirst() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", "lower");
        row.put("ID", "upper");
        var result = RawResult.of(List.of(row));
        var stored = result.rows().getFirst();

        assertThat(stored.keySet()).containsExactly("id", "ID");  // both kept, in order
        assertThat(stored.get("Id")).isEqualTo("lower");          // first one wins
    }

    @Test
    void rowsAreImmutable() {
        var result = RawResult.of(List.of(orderedRow()));
        var row = result.rows().getFirst();

        assertThatThrownBy(() -> row.put("new", 9))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> row.remove("zeta"))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
