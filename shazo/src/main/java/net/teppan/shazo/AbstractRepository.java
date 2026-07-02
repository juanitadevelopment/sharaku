package net.teppan.shazo;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Skeletal {@link Repository} implementation that drives all seven operations
 * through a {@link Describer}, leaving only storage execution to subclasses.
 *
 * <p>Subclasses implement {@link #execute(List)} to run a list of commands of
 * type {@code C} against their storage backend and return a {@link RawResult}.
 * The seven {@link Repository} methods are implemented here by composing the
 * describer's command generators with the execution result ({@code find} and
 * {@code gather} are derived: catalog the matching keys, then retrieve each).
 *
 * <p>Because the command type {@code C} is a type parameter, a subclass that
 * binds {@code C} to a concrete command type (e.g. {@code SqlCommand}) can only
 * be constructed with a matching {@code Describer}, and {@link #execute(List)}
 * never has to defend against unsupported command types.
 *
 * <h2>Subclassing</h2>
 * <pre>{@code
 * public final class MyRepository<T> extends AbstractRepository<T, MyCommand> {
 *     public MyRepository(MyBackend backend, Describer<T, MyCommand> describer) {
 *         super(describer);
 *         this.backend = backend;
 *     }
 *
 *     @Override
 *     protected RawResult execute(List<MyCommand> commands) throws ShazoException {
 *         // translate commands to backend calls and collect rows
 *     }
 * }
 * }</pre>
 *
 * @param <T> the domain type managed by this repository
 * @param <C> the storage command type executed by this repository
 * @see Describer
 * @see net.teppan.shazo.jdbc.JdbcRepository
 */
public abstract class AbstractRepository<T, C extends Command> implements Repository<T> {

    private final Describer<T, C> describer;

    /**
     * Constructs an {@code AbstractRepository} with the given describer.
     *
     * @param describer the describer for domain type {@code T}; never {@code null}
     */
    protected AbstractRepository(Describer<T, C> describer) {
        this.describer = Objects.requireNonNull(describer, "describer");
    }

    /**
     * Returns the describer configured for this repository.
     *
     * @return the describer; never {@code null}
     */
    protected final Describer<T, C> describer() {
        return describer;
    }

    @Override
    public boolean contains(T query) throws ShazoException {
        var result = execute(describer.containsCommands(query));
        return describer.verifier().verify(result);
    }

    @Override
    public void store(T entity) throws ShazoException {
        execute(describer.storeCommands(entity));
    }

    @Override
    public void delete(T entity) throws ShazoException {
        execute(describer.deleteCommands(entity));
    }

    @Override
    public Optional<T> retrieve(T query) throws ShazoException {
        // Each retrieve command (root, children, ...) is executed separately and
        // its rows kept under the command's name; the infuser assembles them. The
        // root (primary) being empty means "not found".
        var results = executeEach(describer.retrieveCommands(query));
        if (results.isEmpty()) return Optional.empty();
        return Optional.of(describer.infuser().infuse(results));
    }

    @Override
    public T find(T query) throws ShazoException, NotFoundException, MultipleFoundException {
        // Catalog yields one row per matching entity (its primary key), so the
        // count is the entity count — correct even for aggregates whose retrieve
        // joins children.
        var key = requireKey();
        var keyRows = catalog(query).rows();
        if (keyRows.isEmpty()) {
            throw new NotFoundException(query.toString());
        }
        if (keyRows.size() > 1) {
            throw new MultipleFoundException(query.toString(), keyRows.size());
        }
        return retrieve(key.apply(keyRows.getFirst()))
            .orElseThrow(() -> new NotFoundException(query.toString()));
    }

    @Override
    public RawResult catalog(T query) throws ShazoException {
        return execute(describer.catalogCommands(query));
    }

    @Override
    public RawResult catalog(T query, Page page) throws ShazoException {
        var window = fetchWindow(query, page);
        return RawResult.of(window.rows());
    }

    @Override
    public List<T> gather(T query) throws ShazoException {
        // Catalog the matching keys, then retrieve each as a full object.
        var key = requireKey();
        var out = new ArrayList<T>();
        for (var row : catalog(query).rows()) {
            retrieve(key.apply(row)).ifPresent(out::add);
        }
        return out;
    }

    @Override
    public Gathered<T> gather(T query, Page page) throws ShazoException {
        // Catalog only the page's keys (plus a one-row probe for hasMore), then
        // retrieve each — capping memory and per-key round trips at the limit.
        var key = requireKey();
        var window = fetchWindow(query, page);
        var out = new ArrayList<T>(window.rows().size());
        for (var row : window.rows()) {
            retrieve(key.apply(row)).ifPresent(out::add);
        }
        return new Gathered<>(out, window.hasMore());
    }

    /**
     * Fetches the page's catalog rows plus a one-row probe past the window, so
     * {@code hasMore} is exact. Prefers the describer's
     * {@linkplain Describer#catalogCommands(Object, Page) paged catalog} (the
     * storage applies offset and limit); otherwise falls back to the plain
     * catalog with the fetch {@linkplain #execute(List, int) capped} at
     * {@code offset + limit + 1} rows and the offset skipped here.
     */
    private Window fetchWindow(T query, Page page) throws ShazoException {
        // Probe one row past the caller's window to decide hasMore exactly.
        var probe = new Page(page.offset(), page.limit() + 1);

        List<java.util.Map<String, Object>> rows;
        var pagedCommands = describer.catalogCommands(query, probe);
        if (pagedCommands != null) {
            // The storage already applied offset and limit+1.
            rows = execute(pagedCommands).rows();
        } else {
            // Cap the fetch at what the window needs, then skip the offset here.
            int cap = probe.offset() + probe.limit();
            var all = execute(describer.catalogCommands(query), cap).rows();
            rows = all.subList(Math.min(probe.offset(), all.size()), all.size());
        }

        boolean hasMore = rows.size() > page.limit();
        if (hasMore) {
            rows = rows.subList(0, page.limit());
        }
        return new Window(rows, hasMore);
    }

    private record Window(List<java.util.Map<String, Object>> rows, boolean hasMore) {}

    private java.util.function.Function<java.util.Map<String, Object>, T> requireKey() {
        var key = describer.key();
        if (key == null) {
            throw new UnsupportedOperationException(
                "This describer has no key(); find/gather are unsupported");
        }
        return key;
    }

    /**
     * Executes each command separately and returns the per-command results keyed
     * by {@link Command#name()}, so an aggregate {@code retrieve} can be assembled
     * from a root and its children without a wide join. The default runs each
     * command through {@link #execute(List)}; backends may override to run them on
     * a single connection.
     *
     * @param commands the commands to execute, in order; never {@code null}
     * @return the per-command results
     * @throws ShazoException if any command fails
     */
    protected Results executeEach(List<C> commands) throws ShazoException {
        var byName = new java.util.LinkedHashMap<String, RawResult>();
        for (C command : commands) {
            byName.put(command.name(), execute(List.of(command)));
        }
        return new Results(byName);
    }

    /**
     * Executes a list of commands against the backing storage system
     * and returns the aggregated result.
     *
     * <p>Commands are executed in list order. If any command fails, this method
     * must throw {@link ShazoException}; the state of partially applied commands
     * is backend-specific (e.g., within a JDBC transaction, they are rolled back).
     *
     * <p>An empty list is a valid no-op and must return an empty {@link RawResult}.
     *
     * @param commands the commands to execute; never {@code null}
     * @return the aggregated result of all commands; never {@code null}
     * @throws ShazoException if any command fails
     */
    protected abstract RawResult execute(List<C> commands) throws ShazoException;

    /**
     * Executes commands with a hint that at most {@code maxRows} result rows are
     * needed, used by paged operations to bound the fetch. The default ignores
     * the hint and delegates to {@link #execute(List)} — always correct, since
     * the caller slices the result — and backends that can cap at the source
     * (e.g. JDBC via {@code Statement.setMaxRows}) override it. The hint is per
     * invocation, not per command; returning more rows than the hint is
     * permitted.
     *
     * @param commands the commands to execute; never {@code null}
     * @param maxRows  the largest number of rows the caller will use; &ge; 1
     * @return the aggregated result of all commands; never {@code null}
     * @throws ShazoException if any command fails
     */
    protected RawResult execute(List<C> commands, int maxRows) throws ShazoException {
        return execute(commands);
    }
}
