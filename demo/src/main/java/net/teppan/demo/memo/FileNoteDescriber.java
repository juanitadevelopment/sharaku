package net.teppan.demo.memo;

import net.teppan.shazo.Describer;
import net.teppan.shazo.Infuser;
import net.teppan.shazo.file.FileCommand;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@link Describer} for {@link Note} targeting the
 * {@link net.teppan.shazo.file.FileRepository} (file-system backend).
 *
 * <p>Each note is stored as a plain-text file named {@code <id>.note}.
 * The file format is:
 *
 * <pre>
 * id: &lt;uuid&gt;
 * title: &lt;text&gt;
 * author: &lt;text&gt;
 * updated: &lt;ISO-8601 instant&gt;
 *
 * === P1 ===
 * &lt;body of page 1&gt;
 * === P2 ===
 * &lt;body of page 2&gt;
 * </pre>
 *
 * <p>Page bodies must not contain a line matching {@code ^=== P\d+ ===$} exactly,
 * as this pattern is used as the page delimiter.
 */
public final class FileNoteDescriber implements Describer<Note, FileCommand> {

    private static final Pattern PAGE_HEADER = Pattern.compile("^=== P(\\d+) ===$");

    @Override
    public List<FileCommand> containsCommands(Note query) {
        return List.of(new FileCommand.Read(filename(query.id())));
    }

    @Override
    public List<FileCommand> storeCommands(Note entity) {
        return List.of(new FileCommand.Write(filename(entity.id()), encode(entity)));
    }

    @Override
    public List<FileCommand> deleteCommands(Note entity) {
        return List.of(new FileCommand.Delete(filename(entity.id())));
    }

    @Override
    public List<FileCommand> retrieveCommands(Note query) {
        return List.of(new FileCommand.Read(filename(query.id())));
    }

    @Override
    public List<FileCommand> catalogCommands(Note query) {
        return List.of(new FileCommand.List(
            "*.note",
            FileNoteDescriber::parseRow,
            row -> matchesQuery(row, query)));
    }

    @Override
    public Infuser<Note> infuser() {
        return results -> results.primary().first()
            .map(row -> decode((String) row.get("_content")))
            .orElseThrow(() -> new IllegalStateException("Note file not found"));
    }

    @Override
    public java.util.function.Function<java.util.Map<String, Object>, Note> key() {
        return row -> Note.byId((String) row.get("id"));
    }

    // ── File format ───────────────────────────────────────────────────────────

    private static String encode(Note n) {
        var sb = new StringBuilder();
        sb.append("id: ").append(n.id()).append("\n");
        sb.append("title: ").append(escapeHeader(n.title())).append("\n");
        sb.append("author: ").append(escapeHeader(n.authorName())).append("\n");
        sb.append("updated: ").append(n.updatedAt()).append("\n");
        sb.append("\n");
        for (var page : n.pages()) {
            sb.append("=== P").append(page.pageNumber()).append(" ===\n");
            var body = page.body() != null ? page.body() : "";
            sb.append(body);
            if (!body.isEmpty() && !body.endsWith("\n")) sb.append("\n");
        }
        return sb.toString();
    }

    private static Note decode(String content) {
        int    sep           = content.indexOf("\n\n");
        String headerSection = sep >= 0 ? content.substring(0, sep) : content;
        String bodySection   = sep >= 0 ? content.substring(sep + 2) : "";

        String id = null, title = null, author = null;
        Instant updated = null;

        for (var line : headerSection.split("\n", -1)) {
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

        return new Note(id, title, author, updated, parsePages(bodySection));
    }

    private static List<Note.Page> parsePages(String body) {
        // Strip a single trailing newline so split doesn't yield a spurious empty element.
        if (body.endsWith("\n")) body = body.substring(0, body.length() - 1);

        var pages = new ArrayList<Note.Page>();
        int currentPage = -1;
        var currentBody = new StringBuilder();

        for (var line : body.split("\n", -1)) {
            var m = PAGE_HEADER.matcher(line);
            if (m.matches()) {
                if (currentPage > 0) {
                    pages.add(new Note.Page(currentPage, currentBody.toString()));
                }
                currentPage = Integer.parseInt(m.group(1));
                currentBody = new StringBuilder();
            } else if (currentPage > 0) {
                if (currentBody.length() > 0) currentBody.append("\n");
                currentBody.append(line);
            }
        }
        if (currentPage > 0) {
            pages.add(new Note.Page(currentPage, currentBody.toString()));
        }
        return pages;
    }

    private static Map<String, Object> parseRow(String content) {
        Note n = decode(content);
        var row = new HashMap<String, Object>();
        row.put("id",          n.id()         != null ? n.id()         : "");
        row.put("title",       n.title()      != null ? n.title()      : "");
        row.put("author_name", n.authorName() != null ? n.authorName() : "");
        row.put("updated_at",  n.updatedAt()  != null ? n.updatedAt()  : Instant.EPOCH);
        return Map.copyOf(row);
    }

    private static Note rowToNote(Map<String, Object> row) {
        return new Note(
            (String)  row.get("id"),
            (String)  row.get("title"),
            (String)  row.get("author_name"),
            (Instant) row.get("updated_at"),
            List.of());
    }

    // ── Filtering ─────────────────────────────────────────────────────────────

    private static boolean matchesQuery(Map<String, Object> row, Note query) {
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
        return id + ".note";
    }
}
