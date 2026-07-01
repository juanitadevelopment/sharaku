package net.teppan.shazo;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The per-command results of one operation, keyed by {@link Command#name()}.
 *
 * <p>An aggregate {@code retrieve} runs several queries — a root and its
 * children — as separate commands, instead of one wide {@code JOIN} (which would
 * multiply rows). Each command's rows are kept separate under its name, and an
 * {@link Infuser} assembles the object graph from them. This is how a 1:N:N
 * structure is built without a cartesian explosion.
 *
 * <pre>{@code
 * Infuser<Order> infuser = results -> {
 *     var head     = results.of("order").first().orElseThrow();
 *     var lines    = results.of("lines").rows().stream().map(Line::from).toList();
 *     var comments = results.of("comments").rows().stream().map(Comment::from).toList();
 *     return new Order((String) head.get("id"), ..., lines, comments);
 * };
 * }</pre>
 *
 * <p>For a single-command operation, {@link #primary()} returns that command's
 * result. {@link #of(String)} returns an empty {@link RawResult} for a name that
 * produced no command, so child accesses are null-safe.
 */
public final class Results {

    private final LinkedHashMap<String, RawResult> byName;

    /**
     * Constructs a {@code Results} from an ordered map of command name to result.
     * Insertion order is preserved; the first entry is the {@linkplain #primary()
     * primary}.
     *
     * @param byName the per-command results in command order; never {@code null}
     */
    public Results(Map<String, RawResult> byName) {
        this.byName = new LinkedHashMap<>(byName);
    }

    /**
     * Returns the result of the command with the given name, or an empty result
     * if no such command ran.
     *
     * @param name the command name
     * @return that command's rows, or an empty {@link RawResult}
     */
    public RawResult of(String name) {
        return byName.getOrDefault(name, RawResult.empty());
    }

    /**
     * Returns the first command's result — the root, for an aggregate, and the
     * sole result for a single-command operation.
     *
     * @return the primary result; an empty {@link RawResult} if there were none
     */
    public RawResult primary() {
        var it = byName.values().iterator();
        return it.hasNext() ? it.next() : RawResult.empty();
    }

    /**
     * Returns whether the {@linkplain #primary() primary} result has no rows —
     * i.e. the root query matched nothing.
     *
     * @return {@code true} if the primary result is empty
     */
    public boolean isEmpty() {
        return primary().isEmpty();
    }
}
