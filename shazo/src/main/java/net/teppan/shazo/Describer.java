package net.teppan.shazo;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Bridges a domain type {@code T} to a storage system by providing five
 * command-generation strategies — covering all seven {@link Repository}
 * operations, since {@code find} and {@code gather} are derived from
 * {@code catalog} + {@code retrieve} — together with the {@link Infuser},
 * the optional {@code key} extractor, and the {@link Verifier} that
 * interpret the resulting {@link RawResult}.
 *
 * <p>The command type {@code C} is fixed at compile time, so a describer is
 * bound to a single storage backend: a {@code Describer<T, SqlCommand>} can
 * only drive a JDBC repository, a {@code Describer<T, FileCommand>} only a
 * file-system repository, and so on. Mismatches are caught by the compiler
 * rather than at runtime.
 *
 * <p>An operation that requires no storage action returns an empty list.
 *
 * <h2>Creating a Describer via the builder</h2>
 * <pre>{@code
 * Describer<Person, SqlCommand> d = Describer.<Person, SqlCommand>builder()
 *     .contains(p  -> List.of(SqlCommand.of(
 *         "SELECT 1 FROM person WHERE id = ?", p.id())))
 *     .store(p     -> List.of(SqlCommand.of(
 *         "MERGE INTO person (id, name, age) VALUES (?, ?, ?)", p.id(), p.name(), p.age())))
 *     .delete(p    -> List.of(SqlCommand.of(
 *         "DELETE FROM person WHERE id = ?", p.id())))
 *     .retrieve(p  -> List.of(SqlCommand.of(
 *         "SELECT id, name, age FROM person WHERE id = ?", p.id())))
 *     .catalog(p   -> List.of(SqlCommand.of(   // the table; one row per entity
 *         "SELECT id, name, age FROM person ORDER BY name")))
 *     .key(row     -> new Person((String) row.get("id"), null, 0))  // catalog row -> key query
 *     .infuser(results -> {
 *         var row = results.primary().first().orElseThrow();
 *         return new Person((String) row.get("id"), (String) row.get("name"),
 *                           ((Number) row.get("age")).intValue());
 *     })
 *     .build();
 * }</pre>
 *
 * @param <T> the domain type described
 * @param <C> the storage command type this describer produces
 * @see Repository
 * @see Command
 * @see #builder()
 */
public interface Describer<T, C extends Command> {

    /**
     * Returns commands that verify whether {@code query} exists in storage.
     * The {@link #verifier()} criterion determines whether
     * {@link Repository#contains} returns {@code true}.
     *
     * @param query the query object
     * @return commands to execute; never {@code null}; empty for a no-op
     */
    List<C> containsCommands(T query);

    /**
     * Returns commands that persist {@code entity} in storage.
     *
     * @param entity the entity to store
     * @return commands to execute; never {@code null}; empty for a no-op
     */
    List<C> storeCommands(T entity);

    /**
     * Returns commands that remove the entity described by {@code entity}
     * from storage.
     *
     * @param entity the entity to delete
     * @return commands to execute; never {@code null}; empty for a no-op
     */
    List<C> deleteCommands(T entity);

    /**
     * Returns commands that retrieve an entity matching {@code query}.
     *
     * @param query the query object
     * @return commands to execute; never {@code null}; empty for a no-op
     */
    List<C> retrieveCommands(T query);

    /**
     * Returns commands that fetch a collection of entities matching
     * {@code query}.
     *
     * @param query the query object; may act as filter criteria
     * @return commands to execute; never {@code null}; empty for a no-op
     */
    List<C> catalogCommands(T query);

    /**
     * Returns commands that fetch only the given {@code page} of the matches —
     * the paged variant of {@link #catalogCommands(Object)} — or {@code null}
     * if this describer has no dedicated paged query.
     *
     * <p>Optional efficiency hook: when present (via
     * {@link Builder#catalogPaged}), the repository pushes the window down to
     * the storage (e.g. {@code LIMIT ? OFFSET ?} in the dialect of your choice)
     * instead of fetching from the front and discarding. When absent, paged
     * operations still work — the repository bounds the fetch itself — so
     * implementing this is never required for correctness.
     *
     * <p>The commands must apply <em>both</em> {@code page.offset()} and
     * {@code page.limit()}; the repository may probe with a limit one larger
     * than the caller's to detect whether more matches exist.
     *
     * @param query the query object; may act as filter criteria
     * @param page  the window to fetch; never {@code null}
     * @return commands to execute, or {@code null} when unsupported
     */
    default List<C> catalogCommands(T query, Page page) {
        return null;
    }

    /**
     * Returns the {@link Infuser} that assembles one entity (root plus any
     * children) from the per-command {@link Results} of a {@code retrieve}.
     *
     * @return the infuser; never {@code null}
     */
    Infuser<T> infuser();

    /**
     * Returns the key extractor that turns one row of a {@link #catalogCommands}
     * result — which yields the matching primary keys — into a key-bearing query
     * object suitable for {@link Repository#retrieve}.
     *
     * <p>This is what lets {@link Repository#gather} and {@link Repository#find}
     * "catalog the keys, then retrieve each": for a record, return a sparse
     * instance with only the key fields set, e.g.
     * {@code row -> new Order((String) row.get("id"), null, ...)}. It is the
     * modern, non-invasive replacement for the original framework's
     * {@code Gatherable.setFindKey} — the knowledge lives in the describer, not
     * in the domain type.
     *
     * <p>Optional: a describer that does not support {@code find}/{@code gather}
     * (it has no meaningful key — like the original framework's non-{@code
     * Gatherable} objects) may return {@code null}, in which case those
     * operations raise {@link UnsupportedOperationException}.
     *
     * @return the key extractor, or {@code null} if find/gather are unsupported
     */
    java.util.function.Function<java.util.Map<String, Object>, T> key();

    /**
     * Returns the {@link Verifier} used by {@link Repository#contains}.
     * Defaults to {@link Verifier#nonEmpty()}.
     *
     * @return the verifier; never {@code null}
     */
    default Verifier verifier() {
        return Verifier.nonEmpty();
    }

    // ── Builder ──────────────────────────────────────────────────────────────

    /**
     * Returns a new {@link Builder} for constructing a {@code Describer<T, C>}.
     *
     * @param <T> the domain type
     * @param <C> the storage command type
     * @return a fresh builder instance
     */
    static <T, C extends Command> Builder<T, C> builder() {
        return new Builder<>();
    }

    /**
     * Fluent builder for a {@link Describer}.
     * All five command generators and the {@code infuser} are required.
     * {@code key} is optional — a describer built without it does not support
     * {@code find}/{@code gather} — and {@link #verifier} defaults to
     * {@link Verifier#nonEmpty()}.
     *
     * @param <T> the domain type
     * @param <C> the storage command type
     */
    final class Builder<T, C extends Command> {

        private Function<T, List<C>> containsFn;
        private Function<T, List<C>> storeFn;
        private Function<T, List<C>> deleteFn;
        private Function<T, List<C>> retrieveFn;
        private Function<T, List<C>> catalogFn;
        private java.util.function.BiFunction<T, Page, List<C>> catalogPagedFn;
        private Infuser<T> infuser;
        private Function<java.util.Map<String, Object>, T> key;
        private Verifier verifier = Verifier.nonEmpty();

        private Builder() {}

        /**
         * Sets the command generator for {@link Repository#contains}.
         *
         * @param fn the generator; never {@code null}
         * @return this builder
         */
        public Builder<T, C> contains(Function<T, List<C>> fn) {
            this.containsFn = Objects.requireNonNull(fn, "contains");
            return this;
        }

        /**
         * Sets the command generator for {@link Repository#store}.
         *
         * @param fn the generator; never {@code null}
         * @return this builder
         */
        public Builder<T, C> store(Function<T, List<C>> fn) {
            this.storeFn = Objects.requireNonNull(fn, "store");
            return this;
        }

        /**
         * Sets the command generator for {@link Repository#delete}.
         *
         * @param fn the generator; never {@code null}
         * @return this builder
         */
        public Builder<T, C> delete(Function<T, List<C>> fn) {
            this.deleteFn = Objects.requireNonNull(fn, "delete");
            return this;
        }

        /**
         * Sets the command generator for {@link Repository#retrieve}.
         *
         * @param fn the generator; never {@code null}
         * @return this builder
         */
        public Builder<T, C> retrieve(Function<T, List<C>> fn) {
            this.retrieveFn = Objects.requireNonNull(fn, "retrieve");
            return this;
        }

        /**
         * Sets the command generator for {@link Repository#catalog}.
         *
         * @param fn the generator; never {@code null}
         * @return this builder
         */
        public Builder<T, C> catalog(Function<T, List<C>> fn) {
            this.catalogFn = Objects.requireNonNull(fn, "catalog");
            return this;
        }

        /**
         * Sets the optional paged-catalog generator, letting
         * {@link Repository#gather(Object, Page)} and
         * {@link Repository#catalog(Object, Page)} push the window down to the
         * storage instead of fetching from the front and discarding. Map
         * {@code page.offset()} and {@code page.limit()} into your query in
         * whatever dialect the backend speaks, e.g.
         *
         * <pre>{@code
         * .catalogPaged((q, page) -> List.of(SqlCommand.of(
         *     "SELECT id FROM orders WHERE customer = ? ORDER BY id LIMIT ? OFFSET ?",
         *     q.customer(), page.limit(), page.offset())))
         * }</pre>
         *
         * <p>Never required: without it, paged operations fall back to a
         * repository-bounded fetch of the leading {@code offset + limit} rows.
         *
         * @param fn the paged generator; never {@code null}
         * @return this builder
         */
        public Builder<T, C> catalogPaged(java.util.function.BiFunction<T, Page, List<C>> fn) {
            this.catalogPagedFn = Objects.requireNonNull(fn, "catalogPaged");
            return this;
        }

        /**
         * Sets the {@link Infuser} for single-entity retrieval.
         *
         * @param infuser the infuser; never {@code null}
         * @return this builder
         */
        public Builder<T, C> infuser(Infuser<T> infuser) {
            this.infuser = Objects.requireNonNull(infuser, "infuser");
            return this;
        }

        /**
         * Sets the key extractor that turns a {@code catalog} (primary-key) row
         * into a key-bearing query object, enabling {@link Repository#gather} and
         * {@link Repository#find} to retrieve each match.
         *
         * @param key the key extractor; never {@code null}
         * @return this builder
         */
        public Builder<T, C> key(Function<java.util.Map<String, Object>, T> key) {
            this.key = Objects.requireNonNull(key, "key");
            return this;
        }

        /**
         * Overrides the default {@link Verifier}
         * (default: {@link Verifier#nonEmpty()}).
         *
         * @param verifier the verifier; never {@code null}
         * @return this builder
         */
        public Builder<T, C> verifier(Verifier verifier) {
            this.verifier = Objects.requireNonNull(verifier, "verifier");
            return this;
        }

        /**
         * Builds an immutable {@link Describer}.
         *
         * @return a new {@code Describer<T, C>}
         * @throws IllegalStateException if any required field has not been set
         */
        public Describer<T, C> build() {
            requireSet(containsFn, "contains");
            requireSet(storeFn,    "store");
            requireSet(deleteFn,   "delete");
            requireSet(retrieveFn, "retrieve");
            requireSet(catalogFn,  "catalog");
            requireSet(infuser,    "infuser");
            // key is optional: only describers that support find/gather need it.

            // capture finals for the anonymous class
            var c = containsFn; var s = storeFn; var d = deleteFn;
            var r = retrieveFn; var g = catalogFn; var gp = catalogPagedFn;
            var inf = infuser; var k = key; var ver = verifier;

            return new Describer<>() {
                @Override public List<C> containsCommands(T q) { return c.apply(q); }
                @Override public List<C> storeCommands(T e)    { return s.apply(e); }
                @Override public List<C> deleteCommands(T e)   { return d.apply(e); }
                @Override public List<C> retrieveCommands(T q) { return r.apply(q); }
                @Override public List<C> catalogCommands(T q)  { return g.apply(q); }
                @Override public List<C> catalogCommands(T q, Page p) {
                    return gp == null ? null : gp.apply(q, p);
                }
                @Override public Infuser<T>    infuser()       { return inf; }
                @Override public java.util.function.Function<java.util.Map<String, Object>, T> key() { return k; }
                @Override public Verifier      verifier()      { return ver; }
            };
        }

        private void requireSet(Object field, String name) {
            if (field == null) {
                throw new IllegalStateException(
                    "Describer.Builder: '" + name + "' is required but was not set");
            }
        }
    }
}
