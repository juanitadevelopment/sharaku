package net.teppan.shazo.file;

import net.teppan.shazo.AbstractRepository;
import net.teppan.shazo.Describer;
import net.teppan.shazo.RawResult;
import net.teppan.shazo.ShazoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A {@link net.teppan.shazo.Repository} implementation backed by the local
 * file system, executing {@link FileCommand} directives produced by a
 * {@link Describer}.
 *
 * <p>All file operations are confined to the {@code baseDirectory} supplied at
 * construction (created automatically if absent). File names that resolve
 * outside that directory — for example via {@code ../} segments or an absolute
 * path — are rejected with {@link ShazoException}.
 *
 * <h2>Concurrency and durability</h2>
 * <p>This repository is thread-safe. Writes are serialized against each other
 * and against reads by a {@link ReentrantReadWriteLock}, and each write is
 * performed atomically (content is staged in a temporary file and then moved
 * into place), so a reader never observes a partially written file and a crash
 * cannot leave a half-written one.
 *
 * <h2>Creating a repository</h2>
 * <pre>{@code
 * FileRepository<Memo> repo = new FileRepository<>(
 *     Path.of("./memos"),
 *     new FileMemoDescriber());
 * }</pre>
 *
 * <h2>Command semantics</h2>
 * <table class="striped">
 * <caption>FileCommand variants</caption>
 * <thead><tr><th>Command</th><th>Effect</th><th>Result rows</th></tr></thead>
 * <tbody>
 *   <tr><td>{@link FileCommand.Write}</td>
 *       <td>Writes (or overwrites) a file atomically</td><td>none</td></tr>
 *   <tr><td>{@link FileCommand.Delete}</td>
 *       <td>Deletes a file; no-op if absent</td><td>none</td></tr>
 *   <tr><td>{@link FileCommand.Read}</td>
 *       <td>Reads a file</td>
 *       <td>one row {@code {"_content": text}} or empty if absent</td></tr>
 *   <tr><td>{@link FileCommand.List}</td>
 *       <td>Lists matching files, parses each, filters by predicate</td>
 *       <td>one row per matching file (keys from {@code rowParser})</td></tr>
 * </tbody>
 * </table>
 *
 * <p>Because this repository is typed {@code AbstractRepository<T, FileCommand>},
 * only {@link FileCommand} directives can reach {@link #execute(List)}; supplying
 * a describer for any other command type is a compile-time error. An empty
 * command list is a silent no-op.
 *
 * @param <T> the domain type managed by this repository
 * @see FileCommand
 * @see Describer
 */
public final class FileRepository<T> extends AbstractRepository<T, FileCommand> {

    private static final Logger log = LoggerFactory.getLogger(FileRepository.class);

    private final Path baseDirectory;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * Constructs a {@code FileRepository} rooted at {@code baseDirectory}.
     *
     * @param baseDirectory the directory where files are stored; created
     *                      automatically if absent; never {@code null}
     * @param describer     the describer for domain type {@code T}; never {@code null}
     * @throws UncheckedIOException if the directory cannot be created
     */
    public FileRepository(Path baseDirectory, Describer<T, FileCommand> describer) {
        super(describer);
        this.baseDirectory = Objects.requireNonNull(baseDirectory, "baseDirectory")
            .toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.baseDirectory);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create base directory: " + this.baseDirectory, e);
        }
    }

    @Override
    protected RawResult execute(List<FileCommand> commands) throws ShazoException {
        var rows = new ArrayList<Map<String, Object>>();
        for (var command : commands) {
            boolean mutating = command instanceof FileCommand.Write
                            || command instanceof FileCommand.Delete;
            var lk = mutating ? lock.writeLock() : lock.readLock();
            lk.lock();
            try {
                switch (command) {
                    case FileCommand.Write  w   -> executeWrite(w);
                    case FileCommand.Delete d   -> executeDelete(d);
                    case FileCommand.Read   r   -> rows.addAll(executeRead(r));
                    case FileCommand.List   lst -> rows.addAll(executeList(lst));
                }
            } finally {
                lk.unlock();
            }
        }
        return RawResult.of(rows);
    }

    // ── File operations ───────────────────────────────────────────────────────

    private void executeWrite(FileCommand.Write cmd) throws ShazoException {
        var path = resolve(cmd.name());
        log.debug("File.Write {}", path);
        try {
            // Stage in a temp file on the same filesystem, then move into place
            // so readers see either the old or the new file, never a partial one.
            var tmp = Files.createTempFile(baseDirectory, ".shazo", ".tmp");
            try {
                Files.writeString(tmp, cmd.content());
                try {
                    Files.move(tmp, path,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp); // no-op once the move has consumed tmp
            }
        } catch (IOException e) {
            throw new ShazoException("Failed to write file: " + path, e);
        }
    }

    private void executeDelete(FileCommand.Delete cmd) throws ShazoException {
        var path = resolve(cmd.name());
        log.debug("File.Delete {}", path);
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new ShazoException("Failed to delete file: " + path, e);
        }
    }

    private List<Map<String, Object>> executeRead(FileCommand.Read cmd) throws ShazoException {
        var path = resolve(cmd.name());
        log.debug("File.Read {}", path);
        try {
            var content = Files.readString(path);
            return List.of(Map.of("_content", content));
        } catch (NoSuchFileException e) {
            return List.of();
        } catch (IOException e) {
            throw new ShazoException("Failed to read file: " + path, e);
        }
    }

    private List<Map<String, Object>> executeList(FileCommand.List cmd) throws ShazoException {
        log.debug("File.List {}  glob={}", baseDirectory, cmd.glob());
        try (var stream = Files.newDirectoryStream(baseDirectory, cmd.glob())) {
            var rows = new ArrayList<Map<String, Object>>();
            for (var path : stream) {
                if (!Files.isRegularFile(path)) continue;
                try {
                    var content = Files.readString(path);
                    var row     = cmd.rowParser().apply(content);
                    if (cmd.predicate().test(row)) {
                        rows.add(row);
                    }
                } catch (UncheckedIOException e) {
                    throw new ShazoException("Failed to read file during listing: " + path, e.getCause());
                }
            }
            return rows;
        } catch (IOException e) {
            throw new ShazoException("Failed to list directory: " + baseDirectory, e);
        }
    }

    // ── Path helper ───────────────────────────────────────────────────────────

    /**
     * Resolves {@code name} against the base directory, rejecting any name that
     * would escape it (path traversal).
     */
    private Path resolve(String name) throws ShazoException {
        var resolved = baseDirectory.resolve(name).normalize();
        if (!resolved.startsWith(baseDirectory)) {
            throw new ShazoException(
                "File name escapes base directory: " + name);
        }
        return resolved;
    }
}
