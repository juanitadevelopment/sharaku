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
 * A {@link Repository} composite that fans write operations out to multiple
 * delegate repositories while routing read operations to a single primary.
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
 *   <li>{@code contains}, {@code retrieve}, {@code catalog} — served
 *       exclusively from the primary repository.</li>
 * </ul>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * var mixed = MixedRepository.of(primaryRepo, replicaRepo, cacheRepo);
 * mixed.store(entity);    // written to primary, replica, and cache
 * mixed.retrieve(query);  // read from primary only
 * }</pre>
 *
 * @param <T> the domain type managed by this repository
 */
public final class MixedRepository<T> implements Repository<T> {

    private final Repository<T> primary;
    private final List<Repository<T>> all;

    /**
     * Creates a {@code MixedRepository} from two or more delegate repositories.
     * {@code first} is designated as the primary and is used for all read
     * operations ({@code contains}, {@code retrieve}, {@code catalog}).
     * Write operations ({@code store}, {@code delete}) fan out to every delegate.
     *
     * @param <T>   the domain type
     * @param first the primary repository (used for reads); never {@code null}
     * @param rest  additional repositories that receive write fan-out; none may be {@code null}
     * @return a new {@code MixedRepository} backed by all supplied delegates
     */
    @SafeVarargs
    public static <T> MixedRepository<T> of(Repository<T> first, Repository<T>... rest) {
        Objects.requireNonNull(first, "first");
        var all = new java.util.ArrayList<Repository<T>>();
        all.add(first);
        for (var r : rest) all.add(Objects.requireNonNull(r));
        return new MixedRepository<>(first, List.copyOf(all));
    }

    private MixedRepository(Repository<T> primary, List<Repository<T>> all) {
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
    public List<T> gather(T query) throws ShazoException {
        return primary.gather(query);
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
