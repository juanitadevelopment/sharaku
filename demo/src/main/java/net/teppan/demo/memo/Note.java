package net.teppan.demo.memo;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A note with a cover page (title, author, updated-at) and an ordered list of pages.
 *
 * <p>The same record is used as a stored entity and as a query criterion.
 * Factory helpers follow the same conventions as {@link Memo}:
 *
 * <ul>
 *   <li>{@link #byId(String)} — identity criterion (retrieve / delete / contains)</li>
 *   <li>{@link #search(String, String)} — partial-match filter for catalog</li>
 *   <li>{@link #all()} — no filter; catalog returns every note</li>
 *   <li>{@link #create(String, String, List)} — new entity with generated id</li>
 * </ul>
 */
public record Note(String id, String title, String authorName, Instant updatedAt, List<Page> pages)
        implements Item {

    /** A single page inside a note. */
    public record Page(int pageNumber, String body) {}

    public Note {
        pages = List.copyOf(pages);
    }

    /** Returns a criterion that matches by id only. */
    public static Note byId(String id) {
        return new Note(id, null, null, null, List.of());
    }

    /** Returns a catalog filter that matches all notes. */
    public static Note all() {
        return new Note(null, null, null, null, List.of());
    }

    /**
     * Returns a catalog filter.
     * Non-null values are matched as case-insensitive substrings.
     */
    public static Note search(String titleLike, String authorLike) {
        return new Note(null, titleLike, authorLike, null, List.of());
    }

    /** Creates a new note with a generated UUID and the current timestamp. */
    public static Note create(String title, String authorName, List<Page> pages) {
        return new Note(UUID.randomUUID().toString(), title, authorName, Instant.now(), pages);
    }
}
