package net.teppan.demo.memo;

import java.time.Instant;
import java.util.UUID;

/**
 * A memo note with title, body, author and last-modified timestamp.
 *
 * <p>The same record is used both as a stored entity and as a query criterion.
 * Null fields in a query are ignored by the repository:
 *
 * <ul>
 *   <li>{@link #byId(String)} — identity criterion (retrieve / delete / contains)</li>
 *   <li>{@link #search(String, String)} — partial-match filter for catalog</li>
 *   <li>{@link #all()} — no filter; catalog returns every memo</li>
 *   <li>{@link #create(String, String, String)} — new entity with generated id and current time</li>
 * </ul>
 */
public record Memo(String id, String title, String body, String authorName, Instant updatedAt)
        implements Item {

    /** Returns a criterion that matches by id only. */
    public static Memo byId(String id) {
        return new Memo(id, null, null, null, null);
    }

    /**
     * Returns a catalog filter.
     * Non-null values are matched as case-insensitive substrings.
     */
    public static Memo search(String titleLike, String authorLike) {
        return new Memo(null, titleLike, null, authorLike, null);
    }

    /** Returns a catalog filter that matches all memos. */
    public static Memo all() {
        return new Memo(null, null, null, null, null);
    }

    /** Creates a new memo with a generated UUID and the current timestamp. */
    public static Memo create(String title, String body, String authorName) {
        return new Memo(UUID.randomUUID().toString(), title, body, authorName, Instant.now());
    }
}
