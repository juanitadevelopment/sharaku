package net.teppan.demo.memo;

import net.teppan.shazo.Describer;
import net.teppan.shazo.Infuser;
import net.teppan.shazo.jdbc.SqlCommand;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * {@link Describer} for {@link Note} targeting the
 * {@link net.teppan.shazo.jdbc.JdbcRepository} (JDBC / H2 backend).
 *
 * <p>Notes span two tables: {@code notes} (cover metadata) and
 * {@code note_pages} (page content).  {@link #storeCommands} upserts the
 * cover row, deletes all existing pages, then inserts the current pages.
 * {@link #retrieveCommands} uses a LEFT JOIN so that notes with no pages
 * still return a row (with null page fields).
 */
public final class JdbcNoteDescriber implements Describer<Note, SqlCommand> {

    @Override
    public List<SqlCommand> containsCommands(Note query) {
        return List.of(SqlCommand.of("SELECT 1 FROM notes WHERE id = ?", query.id()));
    }

    @Override
    public List<SqlCommand> storeCommands(Note entity) {
        var cmds = new ArrayList<SqlCommand>();
        cmds.add(SqlCommand.of(
            "MERGE INTO notes (id, title, author_name, updated_at) KEY (id) VALUES (?, ?, ?, ?)",
            entity.id(), entity.title(), entity.authorName(),
            LocalDateTime.ofInstant(entity.updatedAt(), ZoneOffset.UTC)));
        cmds.add(SqlCommand.of("DELETE FROM note_pages WHERE note_id = ?", entity.id()));
        for (var page : entity.pages()) {
            cmds.add(SqlCommand.of(
                "INSERT INTO note_pages (note_id, page_number, body) VALUES (?, ?, ?)",
                entity.id(), page.pageNumber(), page.body()));
        }
        return List.copyOf(cmds);
    }

    @Override
    public List<SqlCommand> deleteCommands(Note entity) {
        // note_pages rows are removed via ON DELETE CASCADE
        return List.of(SqlCommand.of("DELETE FROM notes WHERE id = ?", entity.id()));
    }

    @Override
    public List<SqlCommand> retrieveCommands(Note query) {
        // Root and children as SEPARATE queries (no join, no cartesian); the
        // infuser assembles them by name.
        return List.of(
            SqlCommand.named("note",
                "SELECT id, title, author_name, updated_at FROM notes WHERE id = ?", query.id()),
            SqlCommand.named("pages",
                "SELECT page_number, body FROM note_pages WHERE note_id = ? ORDER BY page_number",
                query.id()));
    }

    @Override
    public List<SqlCommand> catalogCommands(Note query) {
        var conditions = new ArrayList<String>();
        var params     = new ArrayList<Object>();
        if (query.title() != null) {
            conditions.add("title ILIKE ?");
            params.add("%" + query.title() + "%");
        }
        if (query.authorName() != null) {
            conditions.add("author_name ILIKE ?");
            params.add("%" + query.authorName() + "%");
        }
        var sql = new StringBuilder("SELECT id, title, author_name, updated_at FROM notes");
        if (!conditions.isEmpty()) sql.append(" WHERE ").append(String.join(" AND ", conditions));
        sql.append(" ORDER BY updated_at DESC");
        return List.of(new SqlCommand("result", sql.toString(), params));
    }

    @Override
    public Infuser<Note> infuser() {
        return results -> {
            var head = results.of("note").first()
                .orElseThrow(() -> new IllegalStateException("Note not found"));
            var pages = results.of("pages").rows().stream()
                .map(r -> new Note.Page(
                    ((Number) r.get("page_number")).intValue(),
                    (String)  r.get("body")))
                .toList();
            return new Note(
                (String)  head.get("id"),
                (String)  head.get("title"),
                (String)  head.get("author_name"),
                toInstant(head.get("updated_at")),
                pages);
        };
    }

    @Override
    public Function<Map<String, Object>, Note> key() {
        return row -> Note.byId((String) row.get("id"));
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private static Instant toInstant(Object value) {
        return switch (value) {
            case LocalDateTime ldt     -> ldt.toInstant(ZoneOffset.UTC);
            case java.sql.Timestamp ts -> ts.toInstant();
            default -> throw new IllegalStateException(
                "Unexpected timestamp type: " + value.getClass().getName());
        };
    }
}
