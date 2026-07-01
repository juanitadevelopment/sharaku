package net.teppan.shazo.cache;

import net.teppan.shazo.MultipleFoundException;
import net.teppan.shazo.NotFoundException;
import net.teppan.shazo.RawResult;
import net.teppan.shazo.Repository;
import net.teppan.shazo.ShazoException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * A {@link Repository} decorator that adds a bounded, TTL-based in-memory
 * cache over any underlying repository.
 *
 * <p>Cache entries are keyed by a function applied to the query/entity object.
 * For record types this is typically an ID accessor; for arbitrary objects it
 * defaults to the object itself (relying on {@code equals}/{@code hashCode}).
 *
 * <h2>Caching semantics</h2>
 * <ul>
 *   <li>{@code retrieve} — served from cache if a non-expired entry exists;
 *       otherwise delegated and the result cached.</li>
 *   <li>{@code contains} — returns {@code true} immediately if a non-expired
 *       cache entry exists; otherwise delegated (result not cached).</li>
 *   <li>{@code store} — delegates to the underlying repository; invalidates
 *       the cache entry for the stored entity.</li>
 *   <li>{@code delete} — delegates to the underlying repository; invalidates
 *       the cache entry.</li>
 *   <li>{@code catalog} / {@code gather} — always delegate; results are not cached.</li>
 * </ul>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * var cached = new CacheRepository<>(
 *     jdbcRepository,
 *     Duration.ofMinutes(10),
 *     Person::id       // cache key extractor
 * );
 * }</pre>
 *
 * <p>The cache is fully thread-safe. A background sweeper removes expired
 * entries periodically to bound memory growth.
 *
 * @param <T> the domain type managed by this repository
 */
public final class CacheRepository<T> implements Repository<T>, AutoCloseable {

    private record CacheEntry<T>(T value, Instant expiresAt) {
        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    private final Repository<T> delegate;
    private final Duration ttl;
    private final Function<T, Object> keyExtractor;
    private final ConcurrentHashMap<Object, CacheEntry<T>> cache = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sweeper;

    /**
     * Constructs a {@code CacheRepository} using the query/entity object itself
     * as the cache key (relies on {@code equals}/{@code hashCode}).
     *
     * @param delegate the underlying repository; never {@code null}
     * @param ttl      the time-to-live for each cache entry; never {@code null}
     */
    public CacheRepository(Repository<T> delegate, Duration ttl) {
        this(delegate, ttl, t -> t);
    }

    /**
     * Constructs a {@code CacheRepository} with a custom key extractor.
     *
     * @param delegate     the underlying repository; never {@code null}
     * @param ttl          the time-to-live for each cache entry; never {@code null}
     * @param keyExtractor a function deriving the cache key from a query or
     *                     entity object; never {@code null}
     */
    public CacheRepository(Repository<T> delegate, Duration ttl,
                           Function<T, Object> keyExtractor) {
        this.delegate     = Objects.requireNonNull(delegate,     "delegate");
        this.ttl          = Objects.requireNonNull(ttl,          "ttl");
        this.keyExtractor = Objects.requireNonNull(keyExtractor, "keyExtractor");
        this.sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = Thread.ofVirtual().unstarted(r);
            t.setName("shazo-cache-sweeper");
            t.setDaemon(true);
            return t;
        });
        var sweepInterval = Math.max(ttl.toSeconds(), 1);
        sweeper.scheduleAtFixedRate(
            this::evictExpired, sweepInterval, sweepInterval, TimeUnit.SECONDS);
    }

    // ── Repository operations ────────────────────────────────────────────────

    @Override
    public boolean contains(T query) throws ShazoException {
        var key = keyExtractor.apply(query);
        var entry = cache.get(key);
        if (entry != null && !entry.isExpired()) return true;
        return delegate.contains(query);
    }

    @Override
    public void store(T entity) throws ShazoException {
        delegate.store(entity);
        cache.remove(keyExtractor.apply(entity));
    }

    @Override
    public void delete(T entity) throws ShazoException {
        delegate.delete(entity);
        cache.remove(keyExtractor.apply(entity));
    }

    @Override
    public Optional<T> retrieve(T query) throws ShazoException {
        var key = keyExtractor.apply(query);
        var entry = cache.get(key);
        if (entry != null && !entry.isExpired()) return Optional.of(entry.value());

        var result = delegate.retrieve(query);
        result.ifPresent(v ->
            cache.put(key, new CacheEntry<>(v, Instant.now().plus(ttl))));
        return result;
    }

    @Override
    public T find(T query) throws ShazoException, NotFoundException, MultipleFoundException {
        return delegate.find(query);   // strict check (incl. uniqueness) lives at the delegate
    }

    @Override
    public RawResult catalog(T query) throws ShazoException {
        return delegate.catalog(query);
    }

    @Override
    public List<T> gather(T query) throws ShazoException {
        return delegate.gather(query);
    }

    // ── Cache management ─────────────────────────────────────────────────────

    /**
     * Discards all cached entries immediately, regardless of TTL.
     */
    public void invalidateAll() {
        cache.clear();
    }

    /**
     * Discards the cached entry for the given query key, if any.
     *
     * @param query the query or entity whose cache entry should be removed
     */
    public void invalidate(T query) {
        cache.remove(keyExtractor.apply(query));
    }

    /**
     * Returns the number of entries currently held in the cache
     * (including entries that may have expired but not yet been swept).
     *
     * @return the current cache size
     */
    public int size() {
        return cache.size();
    }

    /** Shuts down the background sweeper thread. */
    @Override
    public void close() {
        sweeper.shutdownNow();
    }

    private void evictExpired() {
        cache.entrySet().removeIf(e -> e.getValue().isExpired());
    }
}
