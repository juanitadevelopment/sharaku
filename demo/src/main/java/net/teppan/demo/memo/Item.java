package net.teppan.demo.memo;

import java.time.Instant;

/** Common interface for objects that appear in the mixed list view. */
public sealed interface Item permits Memo, Note {
    String id();
    String title();
    String authorName();
    Instant updatedAt();
}
