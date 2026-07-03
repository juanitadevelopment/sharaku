package net.teppan.shazo.composite;

import net.teppan.shazo.MultipleFoundException;
import net.teppan.shazo.NotFoundException;
import net.teppan.shazo.RawResult;
import net.teppan.shazo.Repository;
import net.teppan.shazo.ShazoException;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A {@link Repository} composite that <em>replicates</em> write operations to
 * several delegate repositories of the <strong>same</strong> domain type while
 * routing read operations to a single primary.
 *
 * <p>This is useful for scenarios such as:
 * <ul>
 *   <li>Mirroring writes to a primary database and a backup</li>
 *   <li>Populating a cache alongside the authoritative store</li>
 *   <li>Multi-region write fan-out</li>
 * </ul>
 *
 * <h2>Semantics</h2>
 * <ul>
 *   <li>{@code store} and {@code delete} — fan out to all repositories in
 *       declaration order. If any delegate throws, remaining delegates are
 *       still attempted; the first exception is re-thrown after all delegates
 *       have been called.</li>
 *   <li>{@code contains}, {@code retrieve}, {@code find}, {@code catalog},
 *       {@code gather} — served exclusively from the primary repository.</li>
 * </ul>
 *
 * <p><strong>This is not the original framework's {@code MixedRepository}.</strong>
 * That class dispatched each object to <em>one</em> backend chosen by its type
 * (a routing dispatcher with a default fallback). This composite instead writes
 * <em>every</em> object to <em>all</em> delegates — same type, many stores. The
 * legacy type→backend routing has no equivalent here; for a service that spans
 * several databases by type, use a {@code ServiceRunner} route instead (see the
 * backbone module). The name {@code MixedRepository} is intentionally retired to
 * avoid resurrecting that confusion.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * var replicated = ReplicatingRepository.of(primaryRepo, replicaRepo, cacheRepo);
 * replicated.store(entity);    // written to primary, replica, and cache
 * replicated.retrieve(query);  // read from primary only
 * }</pre>
 *
 * @param <T> the domain type managed by this repository
 */
public final class ReplicatingRepository<T> implements Repository<T> {

    private final Repository<T> primary;
    private final List<Repository<T>> all;

    /**
     * Creates a {@code ReplicatingRepository} from two or more delegate
     * repositories. {@code first} is designated as the primary and is used for
     * all read operations ({@code contains}, {@code retrieve}, {@code find},
     * {@code catalog}, {@code gather}).
     * Write operations ({@code store}, {@code delete}) fan out to every delegate.
     *
     * @param <T>   the domain type
     * @param first the primary repository (used for reads); never {@code null}
     * @param rest  additional repositories that receive write fan-out; none may be {@code null}
     * @return a new {@code ReplicatingRepository} backed by all supplied delegates
     */
    @SafeVarargs
    public static <T> ReplicatingRepository<T> of(Repository<T> first, Repository<T>... rest) {
        Objects.requireNonNull(first, "first");
        var all = new java.util.ArrayList<Repository<T>>();
        all.add(first);
        for (var r : rest) all.add(Objects.requireNonNull(r));
        return new ReplicatingRepository<>(first, List.copyOf(all));
    }

    private ReplicatingRepository(Repository<T> primary, List<Repository<T>> all) {
        this.primary = primary;
        this.all = all;
    }

    // ── Read operations — primary only ───────────────────────────────────────

    @Override
    public boolean contains(T query) throws ShazoException {
        return primary.contains(query);
    }

    @Override
    public Optional<T> retrieve(T query) throws ShazoException {
        return primary.retrieve(query);
    }

    @Override
    public T find(T query) throws ShazoException, NotFoundException, MultipleFoundException {
        return primary.find(query);
    }

    @Override
    public RawResult catalog(T query) throws ShazoException {
        return primary.catalog(query);
    }

    @Override
    public RawResult catalog(T query, net.teppan.shazo.Page page) throws ShazoException {
        return primary.catalog(query, page);
    }

    @Override
    public List<T> gather(T query) throws ShazoException {
        return primary.gather(query);
    }

    @Override
    public net.teppan.shazo.Gathered<T> gather(T query, net.teppan.shazo.Page page)
            throws ShazoException {
        return primary.gather(query, page);
    }

    // ── Write operations — fan-out to all ────────────────────────────────────

    @Override
    public void store(T entity) throws ShazoException {
        fanOut(repo -> repo.store(entity));
    }

    @Override
    public void delete(T entity) throws ShazoException {
        fanOut(repo -> repo.delete(entity));
    }

    // ── Fan-out helper ───────────────────────────────────────────────────────

    @FunctionalInterface
    private interface ThrowingConsumer<T> {
        void accept(T value) throws ShazoException;
    }

    private void fanOut(ThrowingConsumer<Repository<T>> action) throws ShazoException {
        ShazoException firstFailure = null;
        for (var repo : all) {
            try {
                action.accept(repo);
            } catch (ShazoException e) {
                if (firstFailure == null) firstFailure = e;
            }
        }
        if (firstFailure != null) throw firstFailure;
    }
}
