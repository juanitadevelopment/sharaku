package net.teppan.demo.memo;

import net.teppan.shazo.Describer;
import net.teppan.shazo.Infuser;
import net.teppan.shazo.file.FileCommand;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@link Describer} for {@link Memo} targeting the
 * {@link net.teppan.shazo.file.FileRepository} (file-system backend).
 *
 * <p>Each memo is stored as a plain-text file named {@code <id>.memo}.
 * The file format is:
 *
 * <pre>
 * id: &lt;uuid&gt;
 * title: &lt;text&gt;
 * author: &lt;text&gt;
 * updated: &lt;ISO-8601 instant&gt;
 *
 * &lt;body&gt;
 * </pre>
 *
 * <p>The five {@link Repository} operations map to {@link FileCommand} as follows:
 *
 * <table class="striped">
 * <caption>Operation to command mapping</caption>
 * <thead><tr><th>Operation</th><th>Command</th></tr></thead>
 * <tbody>
 *   <tr><td>contains</td> <td>{@link FileCommand.Read}</td></tr>
 *   <tr><td>store</td>    <td>{@link FileCommand.Write}</td></tr>
 *   <tr><td>delete</td>   <td>{@link FileCommand.Delete}</td></tr>
 *   <tr><td>retrieve</td> <td>{@link FileCommand.Read}</td></tr>
 *   <tr><td>catalog</td>  <td>{@link FileCommand.List} with inline parse + predicate</td></tr>
 * </tbody>
 * </table>
 *
 * <p>Catalog filtering (title and author case-insensitive substring match) is
 * pushed entirely into the {@link FileCommand.List} predicate, keeping the
 * {@link Gatherer} a simple row-to-{@link Memo} mapping with no further logic.
 */
public final class FileMemoDescriber implements Describer<Memo, FileCommand> {

    @Override
    public List<FileCommand> containsCommands(Memo query) {
        return List.of(new FileCommand.Read(filename(query.id())));
    }

    @Override
    public List<FileCommand> storeCommands(Memo entity) {
        return List.of(new FileCommand.Write(filename(entity.id()), encode(entity)));
    }

    @Override
    public List<FileCommand> deleteCommands(Memo entity) {
        return List.of(new FileCommand.Delete(filename(entity.id())));
    }

    @Override
    public List<FileCommand> retrieveCommands(Memo query) {
        return List.of(new FileCommand.Read(filename(query.id())));
    }

    @Override
    public List<FileCommand> catalogCommands(Memo query) {
        return List.of(new FileCommand.List(
            "*.memo",
            FileMemoDescriber::parseRow,
            row -> matchesQuery(row, query)));
    }

    @Override
    public Infuser<Memo> infuser() {
        return results -> results.primary().first()
            .map(row -> decode((String) row.get("_content")))
            .orElseThrow(() -> new IllegalStateException("Memo file not found"));
    }

    @Override
    public java.util.function.Function<java.util.Map<String, Object>, Memo> key() {
        return row -> Memo.byId((String) row.get("id"));
    }

    // ── File format ───────────────────────────────────────────────────────────

    private static String encode(Memo m) {
        return "id: "      + m.id()                        + "\n"
             + "title: "   + escapeHeader(m.title())       + "\n"
             + "author: "  + escapeHeader(m.authorName())  + "\n"
             + "updated: " + m.updatedAt()                 + "\n"
             + "\n"
             + (m.body() == null ? "" : m.body());
    }

    private static Memo decode(String content) {
        int sep       = content.indexOf("\n\n");
        String header = sep >= 0 ? content.substring(0, sep) : content;
        String body   = sep >= 0 ? content.substring(sep + 2) : "";

        String  id      = null;
        String  title   = null;
        String  author  = null;
        Instant updated = null;

        for (var line : header.split("\n", -1)) {
            int colon = line.indexOf(": ");
            if (colon < 0) continue;
            var key = line.substring(0, colon).strip();
            var val = line.substring(colon + 2);
            switch (key) {
                case "id"      -> id      = val;
                case "title"   -> title   = unescapeHeader(val);
                case "author"  -> author  = unescapeHeader(val);
                case "updated" -> {
                    try { updated = Instant.parse(val); }
                    catch (DateTimeParseException ignored) {}
                }
                default -> {}
            }
        }
        return new Memo(id, title, body, author, updated);
    }

    private static Map<String, Object> parseRow(String content) {
        Memo m = decode(content);
        var row = new HashMap<String, Object>();
        row.put("id",          m.id());
        row.put("title",       m.title()      != null ? m.title()      : "");
        row.put("body",        m.body()       != null ? m.body()       : "");
        row.put("author_name", m.authorName() != null ? m.authorName() : "");
        row.put("updated_at",  m.updatedAt()  != null ? m.updatedAt()  : Instant.EPOCH);
        return Map.copyOf(row);
    }

    private static Memo rowToMemo(Map<String, Object> row) {
        return new Memo(
            (String)  row.get("id"),
            (String)  row.get("title"),
            (String)  row.get("body"),
            (String)  row.get("author_name"),
            (Instant) row.get("updated_at"));
    }

    // ── Filtering ─────────────────────────────────────────────────────────────

    private static boolean matchesQuery(Map<String, Object> row, Memo query) {
        if (query.title() != null
                && !containsIgnoreCase((String) row.get("title"), query.title())) return false;
        if (query.authorName() != null
                && !containsIgnoreCase((String) row.get("author_name"), query.authorName())) return false;
        return true;
    }

    private static boolean containsIgnoreCase(String text, String fragment) {
        if (text == null) return false;
        return text.toLowerCase(Locale.ROOT).contains(fragment.toLowerCase(Locale.ROOT));
    }

    // ── Header encoding ───────────────────────────────────────────────────────

    private static String escapeHeader(String value) {
        if (value == null) return "";
        return value.replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String unescapeHeader(String value) {
        return value.replace("\\n", "\n").replace("\\r", "\r");
    }

    private static String filename(String id) {
        return id + ".memo";
    }
}
