package net.teppan.demo.memo.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.teppan.demo.memo.Item;
import net.teppan.demo.memo.Memo;
import net.teppan.demo.memo.Note;
import net.teppan.shazo.Repository;
import net.teppan.shazo.ShazoException;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * HTTP handler for the memo + note web UI.
 *
 * <h2>Routes</h2>
 * <table>
 * <thead><tr><th>Method</th><th>Path</th><th>Action</th></tr></thead>
 * <tbody>
 *   <tr><td>GET</td><td>/</td><td>redirect → /items</td></tr>
 *   <tr><td>GET</td><td>/items</td><td>mixed list / search</td></tr>
 *   <tr><td>POST</td><td>/memos</td><td>create memo → /items</td></tr>
 *   <tr><td>GET</td><td>/memos/{id}/edit</td><td>memo edit form</td></tr>
 *   <tr><td>POST</td><td>/memos/{id}/edit</td><td>save memo → /items</td></tr>
 *   <tr><td>POST</td><td>/memos/{id}/delete</td><td>delete memo → /items</td></tr>
 *   <tr><td>POST</td><td>/notes</td><td>create note → /notes/{id}/edit</td></tr>
 *   <tr><td>GET</td><td>/notes/{id}/edit</td><td>note edit form (cover + pages)</td></tr>
 *   <tr><td>POST</td><td>/notes/{id}/edit</td><td>save note → /notes/{id}/edit</td></tr>
 *   <tr><td>POST</td><td>/notes/{id}/delete</td><td>delete note → /items</td></tr>
 * </tbody>
 * </table>
 */
public final class MemoHandler implements HttpHandler {

    private static final DateTimeFormatter DT_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    // ── Routing ───────────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface RouteHandler {
        void handle(HttpExchange ex, String[] groups) throws IOException, ShazoException;
    }

    private record Route(String method, Pattern pattern, RouteHandler handler) {
        /** Returns a non-null groups array on match, null on no-match. */
        String[] match(String method, String path) {
            if (!this.method.equals(method)) return null;
            var m = pattern.matcher(path);
            if (!m.matches()) return null;
            var groups = new String[m.groupCount()];
            for (int i = 0; i < groups.length; i++) groups[i] = m.group(i + 1);
            return groups;
        }
    }

    private final Repository<Memo> memoRepo;
    private final Repository<Note> noteRepo;
    private final List<Route>      routes;

    public MemoHandler(Repository<Memo> memoRepo, Repository<Note> noteRepo) {
        this.memoRepo = memoRepo;
        this.noteRepo = noteRepo;
        this.routes   = buildRoutes();
    }

    private List<Route> buildRoutes() {
        return List.of(
            route("GET",  "^/$|^/memos$",              (ex, g) -> redirect(ex, "/items")),
            route("GET",  "^/items$",                  (ex, g) -> handleList(ex)),
            route("POST", "^/memos$",                  (ex, g) -> handleCreateMemo(ex)),
            route("GET",  "^/memos/([^/]+)/edit$",     (ex, g) -> handleEditMemoForm(ex, g[0])),
            route("POST", "^/memos/([^/]+)/edit$",     (ex, g) -> handleSaveMemo(ex, g[0])),
            route("POST", "^/memos/([^/]+)/delete$",   (ex, g) -> handleDeleteMemo(ex, g[0])),
            route("POST", "^/notes$",                  (ex, g) -> handleCreateNote(ex)),
            route("GET",  "^/notes/([^/]+)/edit$",     (ex, g) -> handleEditNoteForm(ex, g[0])),
            route("POST", "^/notes/([^/]+)/edit$",     (ex, g) -> handleSaveNote(ex, g[0])),
            route("POST", "^/notes/([^/]+)/delete$",   (ex, g) -> handleDeleteNote(ex, g[0]))
        );
    }

    private static Route route(String method, String pattern, RouteHandler handler) {
        return new Route(method, Pattern.compile(pattern), handler);
    }

    // ── Dispatch ──────────────────────────────────────────────────────────────

    @Override
    public void handle(HttpExchange ex) throws IOException {
        var method = ex.getRequestMethod().toUpperCase();
        var path   = ex.getRequestURI().getPath();
        try {
            for (var route : routes) {
                var groups = route.match(method, path);
                if (groups != null) {
                    route.handler().handle(ex, groups);
                    return;
                }
            }
            sendHtml(ex, 404, "<h1>404 Not Found</h1>");
        } catch (ShazoException e) {
            sendHtml(ex, 500, "<h1>Error</h1><pre>" + esc(e.getMessage()) + "</pre>");
        }
    }

    // ── Route handlers ────────────────────────────────────────────────────────

    private void handleList(HttpExchange ex) throws IOException, ShazoException {
        var params     = parseQuery(ex.getRequestURI().getRawQuery());
        var titleLike  = emptyToNull(params.get("title"));
        var authorLike = emptyToNull(params.get("author"));

        var memoQuery = (titleLike != null || authorLike != null)
            ? Memo.search(titleLike, authorLike) : Memo.all();
        var noteQuery = (titleLike != null || authorLike != null)
            ? Note.search(titleLike, authorLike) : Note.all();

        var items = new ArrayList<Item>();
        items.addAll(memoRepo.gather(memoQuery));
        items.addAll(noteRepo.gather(noteQuery));
        items.sort(Comparator.comparing(Item::updatedAt, Comparator.nullsLast(Comparator.reverseOrder())));

        sendHtml(ex, 200, renderList(items, titleLike, authorLike));
    }

    private void handleCreateMemo(HttpExchange ex) throws IOException, ShazoException {
        var params = parseQuery(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        var title  = params.getOrDefault("title", "").strip();
        var body   = params.getOrDefault("body", "");
        var author = emptyToNull(params.getOrDefault("author", "").strip());
        if (!title.isBlank()) {
            memoRepo.store(Memo.create(title, body, author));
        }
        redirect(ex, "/items");
    }

    private void handleEditMemoForm(HttpExchange ex, String id) throws IOException, ShazoException {
        var memo = memoRepo.find(Memo.byId(id));
        sendHtml(ex, 200, renderEditMemo(memo));
    }

    private void handleSaveMemo(HttpExchange ex, String id) throws IOException, ShazoException {
        var params   = parseQuery(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        var existing = memoRepo.find(Memo.byId(id));
        var title    = emptyToNull(params.getOrDefault("title", "").strip());
        var body     = params.getOrDefault("body", "");
        var author   = emptyToNull(params.getOrDefault("author", "").strip());
        memoRepo.store(new Memo(id,
            title  != null ? title  : existing.title(),
            body,
            author != null ? author : existing.authorName(),
            Instant.now()));
        redirect(ex, "/items");
    }

    private void handleDeleteMemo(HttpExchange ex, String id) throws IOException, ShazoException {
        memoRepo.delete(Memo.byId(id));
        redirect(ex, "/items");
    }

    private void handleCreateNote(HttpExchange ex) throws IOException, ShazoException {
        var params   = parseQuery(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        var title    = params.getOrDefault("title", "").strip();
        var author   = emptyToNull(params.getOrDefault("author", "").strip());
        var firstBody = params.getOrDefault("body", "").strip();
        if (title.isBlank()) { redirect(ex, "/items"); return; }
        var pages = firstBody.isBlank()
            ? List.<Note.Page>of()
            : List.of(new Note.Page(1, firstBody));
        var note = Note.create(title, author, pages);
        noteRepo.store(note);
        redirect(ex, "/notes/" + note.id() + "/edit");
    }

    private void handleEditNoteForm(HttpExchange ex, String id) throws IOException, ShazoException {
        var note = noteRepo.find(Note.byId(id));
        sendHtml(ex, 200, renderEditNote(note));
    }

    private void handleSaveNote(HttpExchange ex, String id) throws IOException, ShazoException {
        var params   = parseQuery(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        var existing = noteRepo.find(Note.byId(id));
        var title    = emptyToNull(params.getOrDefault("title", "").strip());
        var author   = emptyToNull(params.getOrDefault("author", "").strip());

        // Collect surviving pages (empty body → delete the page)
        var pages = new ArrayList<Note.Page>();
        for (var page : existing.pages()) {
            var body = params.getOrDefault("page_" + page.pageNumber(), "").strip();
            if (!body.isEmpty()) pages.add(new Note.Page(page.pageNumber(), body));
        }
        // Append new page if provided
        var newBody = params.getOrDefault("new_page", "").strip();
        if (!newBody.isEmpty()) {
            int next = pages.isEmpty() ? 1 : pages.get(pages.size() - 1).pageNumber() + 1;
            pages.add(new Note.Page(next, newBody));
        }
        // Renumber from 1
        var renumbered = new ArrayList<Note.Page>();
        for (int i = 0; i < pages.size(); i++) {
            renumbered.add(new Note.Page(i + 1, pages.get(i).body()));
        }

        noteRepo.store(new Note(id,
            title  != null ? title  : existing.title(),
            author != null ? author : existing.authorName(),
            Instant.now(), renumbered));
        redirect(ex, "/notes/" + id + "/edit");
    }

    private void handleDeleteNote(HttpExchange ex, String id) throws IOException, ShazoException {
        noteRepo.delete(Note.byId(id));
        redirect(ex, "/items");
    }

    // ── HTML rendering ────────────────────────────────────────────────────────

    private String renderList(List<Item> items, String titleFilter, String authorFilter) {
        var sb = new StringBuilder(header("メモ帳 & ノート"));

        // Search form
        sb.append("<h2>検索</h2>\n");
        sb.append("<form class=\"search\" method=\"get\" action=\"/items\">\n");
        sb.append("  <input name=\"title\" placeholder=\"タイトル（部分一致）\"");
        if (titleFilter != null) sb.append(" value=\"").append(esc(titleFilter)).append("\"");
        sb.append(">\n");
        sb.append("  <input name=\"author\" placeholder=\"記入者氏名（部分一致）\"");
        if (authorFilter != null) sb.append(" value=\"").append(esc(authorFilter)).append("\"");
        sb.append(">\n");
        sb.append("  <button type=\"submit\">検索</button>\n");
        if (titleFilter != null || authorFilter != null) {
            sb.append("  <button type=\"button\" onclick=\"location='/items'\">クリア</button>\n");
        }
        sb.append("</form>\n");

        // Mixed item table
        sb.append("<h2>一覧 (").append(items.size()).append(" 件)</h2>\n");
        if (items.isEmpty()) {
            sb.append("<p class=\"empty\">アイテムがありません。</p>\n");
        } else {
            sb.append("<table>\n<thead><tr>");
            sb.append("<th>種別</th><th>タイトル</th><th>記入者</th><th>最終更新</th><th></th>");
            sb.append("</tr></thead>\n<tbody>\n");
            for (var item : items) {
                sb.append("<tr>");
                switch (item) {
                    case Memo m -> {
                        sb.append("<td><span class=\"badge memo\">メモ</span></td>");
                        sb.append("<td><a href=\"/memos/").append(esc(m.id()))
                          .append("/edit\">").append(esc(m.title())).append("</a></td>");
                        sb.append("<td>").append(esc(m.authorName())).append("</td>");
                        sb.append("<td class=\"ts\">")
                          .append(m.updatedAt() != null ? DT_FMT.format(m.updatedAt()) : "")
                          .append("</td>");
                        sb.append("<td><form method=\"post\" action=\"/memos/").append(esc(m.id()))
                          .append("/delete\" onsubmit=\"return confirm('削除しますか？')\">")
                          .append("<button class=\"del-btn\" type=\"submit\">削除</button></form></td>");
                    }
                    case Note n -> {
                        sb.append("<td><span class=\"badge note\">ノート</span></td>");
                        sb.append("<td><a href=\"/notes/").append(esc(n.id()))
                          .append("/edit\">").append(esc(n.title())).append("</a></td>");
                        sb.append("<td>").append(esc(n.authorName())).append("</td>");
                        sb.append("<td class=\"ts\">")
                          .append(n.updatedAt() != null ? DT_FMT.format(n.updatedAt()) : "")
                          .append("</td>");
                        sb.append("<td><form method=\"post\" action=\"/notes/").append(esc(n.id()))
                          .append("/delete\" onsubmit=\"return confirm('削除しますか？')\">")
                          .append("<button class=\"del-btn\" type=\"submit\">削除</button></form></td>");
                    }
                }
                sb.append("</tr>\n");
            }
            sb.append("</tbody></table>\n");
        }

        // Create forms
        sb.append("<div class=\"create-row\">\n");

        // Memo create form
        sb.append("""
            <fieldset>
            <legend>新規メモ作成</legend>
            <form method="post" action="/memos">
            <div class="field"><label>タイトル</label><input name="title" required></div>
            <div class="field"><label>本文</label><textarea name="body"></textarea></div>
            <div class="field"><label>記入者氏名</label><input name="author"></div>
            <div class="field"><label></label><button class="create-btn" type="submit">作成</button></div>
            </form>
            </fieldset>
            """);

        // Note create form
        sb.append("""
            <fieldset>
            <legend>新規ノート作成</legend>
            <form method="post" action="/notes">
            <div class="field"><label>タイトル</label><input name="title" required></div>
            <div class="field"><label>記入者氏名</label><input name="author"></div>
            <div class="field"><label>最初のページ</label><textarea name="body" placeholder="省略可。後から編集画面で追加できます。"></textarea></div>
            <div class="field"><label></label><button class="create-btn note-btn" type="submit">作成</button></div>
            </form>
            </fieldset>
            """);

        sb.append("</div>\n");
        sb.append(footer());
        return sb.toString();
    }

    private String renderEditMemo(Memo memo) {
        var sb = new StringBuilder(header("メモを編集"));
        sb.append("<a href=\"/items\">← 一覧に戻る</a>\n");
        sb.append("<h2>").append(esc(memo.title())).append("</h2>\n");
        sb.append("<form method=\"post\" action=\"/memos/").append(esc(memo.id())).append("/edit\">\n");
        field(sb, "タイトル",
              "<input name=\"title\" value=\"" + esc(memo.title()) + "\" required>");
        field(sb, "本文",
              "<textarea name=\"body\" rows=\"10\">" + esc(memo.body()) + "</textarea>");
        field(sb, "記入者氏名",
              "<input name=\"author\" value=\"" + esc(memo.authorName()) + "\">");
        sb.append("<div class=\"field\"><label></label>");
        sb.append("<button class=\"create-btn\" type=\"submit\">保存</button>");
        sb.append(" <a href=\"/items\">キャンセル</a></div>\n");
        sb.append("</form>\n");

        // Delete
        sb.append("<form method=\"post\" action=\"/memos/").append(esc(memo.id()))
          .append("/delete\" onsubmit=\"return confirm('削除しますか？')\" style=\"margin-top:2em\">")
          .append("<button class=\"del-btn\" type=\"submit\">このメモを削除</button></form>\n");

        sb.append(footer());
        return sb.toString();
    }

    private String renderEditNote(Note note) {
        var sb = new StringBuilder(header("ノートを編集"));
        sb.append("<a href=\"/items\">← 一覧に戻る</a>\n");
        sb.append("<h2>").append(esc(note.title())).append("</h2>\n");
        sb.append("<form method=\"post\" action=\"/notes/").append(esc(note.id())).append("/edit\">\n");

        // Cover
        sb.append("<fieldset><legend>カバーページ</legend>\n");
        field(sb, "タイトル",
              "<input name=\"title\" value=\"" + esc(note.title()) + "\" required>");
        field(sb, "記入者氏名",
              "<input name=\"author\" value=\"" + esc(note.authorName()) + "\">");
        sb.append("</fieldset>\n");

        // Existing pages
        for (var page : note.pages()) {
            sb.append("<fieldset><legend>ページ ").append(page.pageNumber()).append("</legend>\n");
            sb.append("<textarea name=\"page_").append(page.pageNumber())
              .append("\" rows=\"8\">").append(esc(page.body())).append("</textarea>\n");
            sb.append("<small class=\"hint\">空欄にして保存するとこのページは削除されます。</small>\n");
            sb.append("</fieldset>\n");
        }

        // New page
        sb.append("<fieldset><legend>新しいページ</legend>\n");
        sb.append("<textarea name=\"new_page\" rows=\"8\" placeholder=\"新しいページの本文を入力…\"></textarea>\n");
        sb.append("</fieldset>\n");

        sb.append("<div class=\"field\"><label></label>");
        sb.append("<button class=\"create-btn\" type=\"submit\">保存</button>");
        sb.append(" <a href=\"/items\">キャンセル</a></div>\n");
        sb.append("</form>\n");

        // Delete
        sb.append("<form method=\"post\" action=\"/notes/").append(esc(note.id()))
          .append("/delete\" onsubmit=\"return confirm('削除しますか？')\" style=\"margin-top:2em\">")
          .append("<button class=\"del-btn\" type=\"submit\">このノートを削除</button></form>\n");

        sb.append(footer());
        return sb.toString();
    }

    // ── Page skeleton ─────────────────────────────────────────────────────────

    private static String header(String title) {
        return """
            <!DOCTYPE html>
            <html lang="ja">
            <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>%s</title>
            <style>
            body { font-family: sans-serif; max-width: 960px; margin: 40px auto; padding: 0 20px; color: #333; }
            h1 { border-bottom: 2px solid #4a90d9; padding-bottom: 8px; color: #4a90d9; }
            h2 { color: #555; font-size: 1rem; margin-top: 2em; }
            a { color: #4a90d9; }
            form.search { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 1em; }
            form.search input { padding: 6px 10px; border: 1px solid #ccc; border-radius: 4px; flex: 1; min-width: 140px; }
            form.search button { padding: 6px 16px; background: #4a90d9; color: white; border: none; border-radius: 4px; cursor: pointer; }
            form.search button:hover { background: #357abd; }
            table { width: 100%%; border-collapse: collapse; margin-top: 8px; }
            th { background: #f0f4ff; text-align: left; padding: 8px; border-bottom: 2px solid #ccc; }
            td { padding: 8px; border-bottom: 1px solid #eee; vertical-align: middle; }
            td.ts { white-space: nowrap; font-size: 0.85em; color: #888; }
            .badge { display: inline-block; font-size: 0.75em; padding: 2px 8px; border-radius: 10px; font-weight: bold; }
            .badge.memo { background: #e8f0fe; color: #1a73e8; }
            .badge.note { background: #fce8e6; color: #c62828; }
            .del-btn { background: #e05; color: white; border: none; border-radius: 3px; padding: 3px 10px; cursor: pointer; font-size: 0.8em; }
            .del-btn:hover { background: #c04; }
            .create-row { display: flex; gap: 16px; flex-wrap: wrap; margin-top: 2em; }
            .create-row fieldset { flex: 1; min-width: 280px; }
            fieldset { border: 1px solid #ccc; border-radius: 6px; padding: 16px; }
            legend { font-weight: bold; color: #555; padding: 0 6px; }
            .field { display: flex; gap: 8px; align-items: flex-start; margin-bottom: 10px; }
            .field label { min-width: 90px; text-align: right; font-size: 0.9em; padding-top: 6px; }
            .field input, .field textarea { flex: 1; padding: 6px 10px; border: 1px solid #ccc; border-radius: 4px; font-family: inherit; }
            .field textarea { min-height: 80px; resize: vertical; }
            .create-btn { background: #2a9; color: white; border: none; border-radius: 4px; padding: 8px 24px; cursor: pointer; font-size: 1em; }
            .create-btn:hover { background: #1a8; }
            .note-btn { background: #e06030; }
            .note-btn:hover { background: #c04020; }
            .empty { color: #999; text-align: center; padding: 32px; }
            .hint { color: #888; font-size: 0.8em; }
            </style>
            </head>
            <body>
            <h1>%s</h1>
            """.formatted(title, title);
    }

    private static String footer() {
        return "</body></html>\n";
    }

    private static void field(StringBuilder sb, String label, String input) {
        sb.append("<div class=\"field\"><label>").append(label).append("</label>")
          .append(input).append("</div>\n");
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private static void sendHtml(HttpExchange ex, int status, String html) throws IOException {
        var bytes = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (var out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void redirect(HttpExchange ex, String location) throws IOException {
        ex.getResponseHeaders().set("Location", location);
        ex.sendResponseHeaders(303, -1);
        ex.getResponseBody().close();
    }

    private static Map<String, String> parseQuery(String raw) {
        var map = new LinkedHashMap<String, String>();
        if (raw == null || raw.isBlank()) return map;
        for (var pair : raw.split("&")) {
            var eq = pair.indexOf('=');
            if (eq < 0) continue;
            var key = decode(pair.substring(0, eq));
            var val = decode(pair.substring(eq + 1));
            if (!key.isBlank()) map.put(key, val);
        }
        return map;
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

}
