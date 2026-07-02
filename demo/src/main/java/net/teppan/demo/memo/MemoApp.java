package net.teppan.demo.memo;

import com.sun.net.httpserver.HttpServer;
import net.teppan.demo.memo.web.MemoHandler;
import net.teppan.shazo.Repository;
import net.teppan.shazo.ShazoException;
import net.teppan.shazo.file.FileRepository;
import net.teppan.shazo.jdbc.JdbcRepository;
import net.teppan.shazo.jdbc.SchemaManager;
import net.teppan.shazo.jdbc.h2.H2DataSources;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Entry point for the memo demo application.
 *
 * <h2>Usage</h2>
 * <pre>
 * # JDBC / H2 backend (default)
 * java -cp ... net.teppan.demo.memo.MemoApp --storage=jdbc --port=8080 --db-path=./memo-db
 *
 * # File-system backend
 * java -cp ... net.teppan.demo.memo.MemoApp --storage=file --port=8080 --file-dir=./items
 * </pre>
 *
 * <p>Demonstrates Shazo's key strength: switching the backing store from JDBC to
 * file-system requires only different {@link Repository} instances; the domain
 * objects and all application logic remain unchanged.
 */
public final class MemoApp {

    private MemoApp() {}

    /**
     * Application entry point.
     *
     * @param args command-line arguments
     * @throws ShazoException if schema setup fails
     * @throws IOException    if the HTTP server cannot bind
     */
    public static void main(String[] args) throws ShazoException, IOException {
        var opts    = parseArgs(args);
        int    port    = Integer.parseInt(opts.getOrDefault("port", "8080"));
        String storage = opts.getOrDefault("storage", "jdbc");

        Repository<Memo> memoRepo;
        Repository<Note> noteRepo;

        switch (storage) {
            case "jdbc" -> {
                var ds = H2DataSources.file(opts.getOrDefault("db-path", "./memo-db"));
                SchemaManager.apply(ds, "net/teppan/demo/memo/schema/");
                memoRepo = new JdbcRepository<>(ds, new JdbcMemoDescriber());
                noteRepo = new JdbcRepository<>(ds, new JdbcNoteDescriber());
            }
            case "file" -> {
                var dir = Path.of(opts.getOrDefault("file-dir", "./items"));
                memoRepo = new FileRepository<>(dir, new FileMemoDescriber());
                noteRepo = new FileRepository<>(dir, new FileNoteDescriber());
            }
            default -> throw new IllegalArgumentException("Unknown --storage value: " + storage);
        }

        var server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", new MemoHandler(memoRepo, noteRepo));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        System.out.printf("Memo app started on http://localhost:%d/  (storage=%s)%n", port, storage);
        System.out.println("Press Ctrl+C to stop.");
    }

    // ── Argument parsing ──────────────────────────────────────────────────────

    private static Map<String, String> parseArgs(String[] args) {
        var map = new HashMap<String, String>();
        for (var arg : args) {
            if (arg.startsWith("--")) {
                var body = arg.substring(2);
                var eq   = body.indexOf('=');
                if (eq > 0) {
                    map.put(body.substring(0, eq), body.substring(eq + 1));
                } else {
                    map.put(body, "true");
                }
            }
        }
        return map;
    }
}
