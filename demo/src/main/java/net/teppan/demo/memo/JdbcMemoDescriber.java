package net.teppan.demo.memo;

import net.teppan.shazo.jdbc.SqlCommand;
import net.teppan.shazo.Describer;
import net.teppan.shazo.Infuser;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link Describer} for {@link Memo} targeting the
 * {@link net.teppan.shazo.jdbc.JdbcRepository} (JDBC / H2 backend).
 *
 * <p>All five operations produce {@link SqlCommand} commands.
 * The catalog operation builds a dynamic query from non-null filter fields:
 * a non-null {@link Memo#title()} adds {@code title ILIKE ?},
 * and a non-null {@link Memo#authorName()} adds {@code author_name ILIKE ?}.
 * Both conditions are combined with {@code AND} when both are present.
 */
public final class JdbcMemoDescriber implements Describer<Memo, SqlCommand> {

    @Override
    public List<SqlCommand> containsCommands(Memo query) {
        return List.of(SqlCommand.of(
            "SELECT 1 FROM memos WHERE id = ?", query.id()));
    }

    @Override
    public List<SqlCommand> storeCommands(Memo entity) {
        return List.of(SqlCommand.of(
            "MERGE INTO memos (id, title, body, author_name, updated_at) KEY (id) VALUES (?, ?, ?, ?, ?)",
            entity.id(), entity.title(), entity.body(), entity.authorName(),
            LocalDateTime.ofInstant(entity.updatedAt(), ZoneOffset.UTC)));
    }

    @Override
    public List<SqlCommand> deleteCommands(Memo entity) {
        return List.of(SqlCommand.of(
            "DELETE FROM memos WHERE id = ?", entity.id()));
    }

    @Override
    public List<SqlCommand> retrieveCommands(Memo query) {
        return List.of(SqlCommand.of(
            "SELECT id, title, body, author_name, updated_at FROM memos WHERE id = ?",
            query.id()));
    }

    @Override
    public List<SqlCommand> catalogCommands(Memo query) {
        var sql        = new StringBuilder("SELECT id, title, body, author_name, updated_at FROM memos");
        var params     = new ArrayList<Object>();
        var conditions = new ArrayList<String>();

        if (query.title() != null) {
            conditions.add("title ILIKE ?");
            params.add("%" + query.title() + "%");
        }
        if (query.authorName() != null) {
            conditions.add("author_name ILIKE ?");
            params.add("%" + query.authorName() + "%");
        }
        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }
        sql.append(" ORDER BY updated_at DESC");

        return List.of(new SqlCommand("result", sql.toString(), params));
    }

    @Override
    public Infuser<Memo> infuser() {
        return results -> results.primary().first()
            .map(JdbcMemoDescriber::rowToMemo)
            .orElseThrow(() -> new IllegalStateException("Memo not found"));
    }

    @Override
    public java.util.function.Function<Map<String, Object>, Memo> key() {
        return row -> Memo.byId((String) row.get("id"));
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private static Memo rowToMemo(Map<String, Object> row) {
        return new Memo(
            (String) row.get("id"),
            (String) row.get("title"),
            (String) row.get("body"),
            (String) row.get("author_name"),
            toInstant(row.get("updated_at")));
    }

    private static Instant toInstant(Object value) {
        return switch (value) {
            case LocalDateTime ldt     -> ldt.toInstant(ZoneOffset.UTC);
            case java.sql.Timestamp ts -> ts.toInstant();
            default -> throw new IllegalStateException(
                "Unexpected timestamp type: " + value.getClass().getName());
        };
    }
}
