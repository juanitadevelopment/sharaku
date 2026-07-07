package net.teppan.shazo;

import java.util.List;
import java.util.Optional;

/**
 * The core persistence contract at the heart of Shazo.
 *
 * <p>All operations accept a domain object of type {@code T} and delegate
 * the translation to storage commands to a {@link Describer}. Implementations
 * must be thread-safe.
 *
 * <h2>Operation semantics</h2>
 * <table class="striped">
 * <caption>Repository operations</caption>
 * <thead>
 *   <tr><th>Operation</th><th>Description</th><th>Returns</th></tr>
 * </thead>
 * <tbody>
 *   <tr><td>{@code contains}</td>
 *       <td>Tests whether a matching entity exists</td>
 *       <td>{@code boolean}</td></tr>
 *   <tr><td>{@code store}</td>
 *       <td>Persists an entity; creates or replaces</td>
 *       <td>{@code void}</td></tr>
 *   <tr><td>{@code delete}</td>
 *       <td>Removes a matching entity; no-op if absent</td>
 *       <td>{@code void}</td></tr>
 *   <tr><td>{@code retrieve}</td>
 *       <td>Leniently fetches one match (the first); empty if none</td>
 *       <td>{@code Optional<T>}</td></tr>
 *   <tr><td>{@code find}</td>
 *       <td>Strictly fetches the unique match; throws if none or several</td>
 *       <td>{@code T}</td></tr>
 *   <tr><td>{@code catalog}</td>
 *       <td>Fetches all matching rows as a raw table (no object mapping)</td>
 *       <td>{@code RawResult}</td></tr>
 *   <tr><td>{@code gather}</td>
 *       <td>Fetches all matching entities, gathered into typed objects</td>
 *       <td>{@code List<T>}</td></tr>
 *   <tr><td>{@code catalog(query, page)}</td>
 *       <td>Fetches one {@link Page} of the matching rows</td>
 *       <td>{@code RawResult}</td></tr>
 *   <tr><td>{@code gather(query, page)}</td>
 *       <td>Fetches one {@link Page} of the matching entities, with a has-more flag</td>
 *       <td>{@code Gathered<T>}</td></tr>
 * </tbody>
 * </table>
 *
 * <p>{@code catalog} returns the result in table form — a {@link RawResult} of
 * named-column rows — for callers that are themselves tabular (a UI grid, a
 * report, a CSV/JSON export) and would only pay to re-flatten domain objects.
 * {@code gather} runs the same query and builds objects by retrieving each
 * matching key (via the describer's {@link Infuser}).
 *
 * @param <T> the domain type managed by this repository
 * @see Describer
 * @see AbstractRepository
 */
public interface Repository<T> {

    /**
     * Returns {@code true} if a matching entity exists in storage.
     *
     * <p>An existence check, not a fetch: the describer's {@code contains}
     * command should test for a row (e.g. {@code SELECT 1 ... WHERE key = ?})
     * without materializing the entity — use {@link #retrieve}/{@link #find}
     * when you actually need the object. The condition is whatever the describer
     * writes; {@code contains} does not itself narrow to a primary key.
     *
     * @param query an object whose fields identify the entity to look up
     * @return {@code true} if a match exists
     * @throws ShazoException if the underlying storage system reports an error
     */
    boolean contains(T query) throws ShazoException;

    /**
     * Persists {@code entity}, creating it if absent or replacing it if present.
     *
     * @param entity the entity to persist
     * @throws ShazoException if the storage operation fails
     */
    void store(T entity) throws ShazoException;

    /**
     * Removes the entity matching {@code entity} from storage.
     * Has no effect when no matching entity exists.
     *
     * @param entity an object identifying the entity to remove
     * @throws ShazoException if the storage operation fails
     */
    void delete(T entity) throws ShazoException;

    /**
     * Retrieves the entity matching {@code query}.
     * Returns {@link Optional#empty()} when no match is found.
     *
     * @param query an object whose fields identify the entity to retrieve
     * @return an {@link Optional} containing the matching entity, or empty
     * @throws ShazoException if the storage operation fails
     */
    Optional<T> retrieve(T query) throws ShazoException;

    /**
     * Finds <em>the</em> unique entity matching {@code query}, treating both
     * absence and ambiguity as errors. This is the strict, integrity-checking
     * fetch for a key that should identify exactly one entity (a primary key, or
     * a business key expected to be unique).
     *
     * <p>Contrast with {@link #retrieve}, which leniently returns the first match
     * (or empty) and never complains about several. Use {@code find} when "more
     * than one" would be a bug; use {@code retrieve}/{@link #gather} when several
     * matches are acceptable.
     *
     * @param query an object whose fields identify the entity to find
     * @return the single matching entity; never {@code null}
     * @throws NotFoundException     if no matching entity exists
     * @throws MultipleFoundException if more than one entity matches
     * @throws ShazoException        if the storage operation fails
     */
    T find(T query) throws ShazoException, NotFoundException, MultipleFoundException;

    /**
     * Returns all rows matching {@code query} as a raw table, without mapping
     * them to domain objects. Use this when the consumer is itself tabular (a UI
     * grid, a report, a CSV/JSON export) and materializing — then re-flattening —
     * domain objects would be wasted work. Returns an empty {@link RawResult}
     * when no matches exist.
     *
     * <p><b>Row order is the describer's, not the framework's:</b> shazo injects
     * no ordering — the rows come back in whatever order the describer's catalog
     * command specifies (its {@code ORDER BY}, if any) or, without one, in the
     * backend's unspecified order. Give the catalog command a stable
     * {@code ORDER BY} when order matters (it is also what offset paging via
     * {@link #catalog(Object, Page)} assumes).
     *
     * @param query an object that serves as filter criteria
     * @return the matching rows in table form; never {@code null}
     * @throws ShazoException if the storage operation fails
     */
    RawResult catalog(T query) throws ShazoException;

    /**
     * Returns the given {@code page} of the rows matching {@code query} as a raw
     * table: skip {@code page.offset()} rows, return at most {@code page.limit()}.
     *
     * <p>This default fetches the full catalog and slices it, which is correct
     * everywhere but does not bound the fetch; storage-backed implementations
     * override it to fetch only what the page needs. Offset paging assumes the
     * catalog query has a stable order (e.g. an {@code ORDER BY}).
     *
     * @param query an object that serves as filter criteria
     * @param page  the window to return; never {@code null}
     * @return the page's rows in table form; never {@code null}
     * @throws ShazoException if the storage operation fails
     */
    default RawResult catalog(T query, Page page) throws ShazoException {
        java.util.Objects.requireNonNull(page, "page");
        var rows = catalog(query).rows();
        int from = Math.min(page.offset(), rows.size());
        int to   = Math.min(from + page.limit(), rows.size());
        return RawResult.of(rows.subList(from, to));
    }

    /**
     * Returns all entities matching {@code query} as an immutable list, by
     * cataloging the matching keys and retrieving each into a typed object (so
     * the describer must declare {@code key(...)}). Returns an empty list when no
     * matches exist.
     *
     * <p>For match sets that may be large, prefer {@link #gather(Object, Page)},
     * which bounds both the memory and the per-key retrieves.
     *
     * <p>Order follows the catalog command's — see {@link #catalog(Object)}.
     *
     * <p><b>Read consistency:</b> a bare {@code gather} runs its catalog and each
     * per-key retrieve as separate operations, each of which may borrow a
     * <em>different</em> pooled connection — so a concurrent commit between them
     * can make the result reflect more than one snapshot (a key cataloged but
     * then deleted simply drops out). When you need a single consistent snapshot,
     * run the {@code gather} inside a transaction (a {@code ServiceRunner} service
     * or {@code JdbcRepository.transact}), which binds every sub-query to one
     * connection.
     *
     * @param query an object that serves as filter criteria
     * @return an immutable list of matching entities; never {@code null}
     * @throws ShazoException if the storage operation fails
     */
    List<T> gather(T query) throws ShazoException;

    /**
     * Returns the given {@code page} of the entities matching {@code query},
     * plus whether more matches exist beyond it — the bounded companion of
     * {@link #gather(Object)}: only the page's keys are retrieved into objects,
     * so memory and per-key round trips are capped at {@code page.limit()}.
     *
     * <pre>{@code
     * var page = Page.of(50);
     * var slice = orders.gather(query, page);
     * while (slice.hasMore()) {
     *     process(slice);
     *     slice = orders.gather(query, page = page.next());
     * }
     * process(slice);
     * }</pre>
     *
     * <p>This default fetches the full result and slices it, which is correct
     * everywhere but does not bound the fetch; storage-backed implementations
     * override it to fetch only what the page needs. Offset paging assumes the
     * catalog query has a stable order (e.g. an {@code ORDER BY}).
     *
     * @param query an object that serves as filter criteria
     * @param page  the window to return; never {@code null}
     * @return the page's entities and a has-more flag; never {@code null}
     * @throws ShazoException if the storage operation fails
     */
    default Gathered<T> gather(T query, Page page) throws ShazoException {
        java.util.Objects.requireNonNull(page, "page");
        var all = gather(query);
        int from = Math.min(page.offset(), all.size());
        int to   = Math.min(from + page.limit(), all.size());
        return new Gathered<>(all.subList(from, to), to < all.size());
    }
}
