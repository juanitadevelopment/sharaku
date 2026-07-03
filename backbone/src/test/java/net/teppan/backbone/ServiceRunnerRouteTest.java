package net.teppan.backbone;

import net.teppan.shazo.Describer;
import net.teppan.shazo.NotFoundException;
import net.teppan.shazo.RawResult;
import net.teppan.shazo.Repository;
import net.teppan.shazo.ShazoException;
import net.teppan.shazo.jdbc.JdbcRepository;
import net.teppan.shazo.jdbc.Repositories;
import net.teppan.shazo.jdbc.SqlCommand;
import net.teppan.shazo.jdbc.h2.H2DataSources;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for enlisted routes: a service working against the primary database and
 * one or more secondary databases declared via
 * {@link ServiceRunner.Builder#route(DataSource, Repositories)}, with a shared
 * commit boundary (routes commit first, primary last; failure rolls back all).
 */
class ServiceRunnerRouteTest {

    record Item(String id, String name) {}
    record Part(String code, String label) {}
    record Note(String id, String text) {}

    private DataSource primaryDs;
    private DataSource routeDs;
    private Describer<Item, SqlCommand> items;
    private Describer<Part, SqlCommand> parts;
    private Repositories primaryRepos;
    private Repositories routeRepos;

    @BeforeEach
    void setUp() throws Exception {
        primaryDs = H2DataSources.inMemory("route_p_" + System.nanoTime());
        routeDs   = H2DataSources.inMemory("route_r_" + System.nanoTime());
        try (var conn = primaryDs.getConnection(); var st = conn.createStatement()) {
            st.execute("CREATE TABLE item (id VARCHAR(36) PRIMARY KEY, name VARCHAR(200))");
        }
        try (var conn = routeDs.getConnection(); var st = conn.createStatement()) {
            st.execute("CREATE TABLE part (code VARCHAR(36) PRIMARY KEY, label VARCHAR(200))");
        }
        items = Describer.<Item, SqlCommand>builder()
            .contains(i -> List.of(SqlCommand.of("SELECT 1 FROM item WHERE id = ?", i.id())))
            .store(i    -> List.of(SqlCommand.of(
                "MERGE INTO item (id, name) KEY (id) VALUES (?, ?)", i.id(), i.name())))
            .delete(i   -> List.of(SqlCommand.of("DELETE FROM item WHERE id = ?", i.id())))
            .retrieve(i -> List.of(SqlCommand.of("SELECT id, name FROM item WHERE id = ?", i.id())))
            .catalog(i  -> List.of(SqlCommand.of("SELECT id, name FROM item")))
            .infuser(r  -> r.primary().first().map(row -> new Item(
                (String) row.get("id"), (String) row.get("name"))).orElseThrow())
            .key(row -> new Item((String) row.get("id"), null))
            .build();
        parts = Describer.<Part, SqlCommand>builder()
            .contains(p -> List.of(SqlCommand.of("SELECT 1 FROM part WHERE code = ?", p.code())))
            .store(p    -> List.of(SqlCommand.of(
                "MERGE INTO part (code, label) KEY (code) VALUES (?, ?)", p.code(), p.label())))
            .delete(p   -> List.of(SqlCommand.of("DELETE FROM part WHERE code = ?", p.code())))
            .retrieve(p -> List.of(SqlCommand.of("SELECT code, label FROM part WHERE code = ?", p.code())))
            .catalog(p  -> List.of(SqlCommand.of("SELECT code, label FROM part")))
            .infuser(r  -> r.primary().first().map(row -> new Part(
                (String) row.get("code"), (String) row.get("label"))).orElseThrow())
            .key(row -> new Part((String) row.get("code"), null))
            .build();
        primaryRepos = Repositories.builder().register(Item.class, items).build();
        routeRepos   = Repositories.builder().register(Part.class, parts).build();
    }

    private boolean itemPersisted(String id) throws Exception {
        return new JdbcRepository<>(primaryDs, items).contains(new Item(id, null));
    }

    private boolean partPersisted(String code) throws Exception {
        return new JdbcRepository<>(routeDs, parts).contains(new Part(code, null));
    }

    private ServiceRunner runnerWithRoute() {
        return ServiceRunner.builder()
            .dataSource(primaryDs).describers(primaryRepos)
            .route(routeDs, routeRepos)
            .build();
    }

    // ── Transparent resolution ────────────────────────────────────────────────

    @Test
    void routedType_readsTransparently() throws Exception {
        new JdbcRepository<>(routeDs, parts).store(new Part("P1", "Bolt"));
        var runner = runnerWithRoute();

        Part found = runner.run(
            ctx -> ctx.retrieve(Part.class, new Part("P1", null)).orElseThrow(),
            Principal.system());

        assertThat(found.label()).isEqualTo("Bolt");
    }

    @Test
    void primaryRegistryWins_whenTypeRegisteredOnBothSides() throws Exception {
        // Register Item on the route too; the route DB has no ITEM table, so if
        // the route were (wrongly) chosen the store would fail.
        var overlappingRoute = Repositories.builder()
            .register(Item.class, items).register(Part.class, parts).build();
        var runner = ServiceRunner.builder()
            .dataSource(primaryDs).describers(primaryRepos)
            .route(routeDs, overlappingRoute)
            .build();

        runner.run(ctx -> {
            ctx.repository(Item.class).store(new Item("i1", "Ichi"));
            return null;
        }, Principal.system());

        assertThat(itemPersisted("i1")).isTrue();
    }

    @Test
    void unknownType_throwsIllegalArgument() {
        var runner = runnerWithRoute();
        assertThatThrownBy(() -> runner.run(ctx -> ctx.repository(Note.class), Principal.system()))
            .isInstanceOf(AppServiceException.class)
            .cause().isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Note");
    }

    @Test
    void storeFacade_staysPrimaryOnly() {
        var runner = runnerWithRoute();
        assertThatThrownBy(() -> runner.run(ctx -> {
            ctx.store(new Part("P9", "Nut"));   // Part lives on the route, not primary
            return null;
        }, Principal.system()))
            .isInstanceOf(AppServiceException.class)
            .cause().isInstanceOf(IllegalArgumentException.class);
    }

    // ── Shared commit boundary ────────────────────────────────────────────────

    @Test
    void success_commitsPrimaryAndRouteTogether() throws Exception {
        var runner = runnerWithRoute();

        runner.run(ctx -> {
            ctx.repository(Item.class).store(new Item("i1", "Ichi"));
            ctx.repository(Part.class).store(new Part("P1", "Bolt"));
            return null;
        }, Principal.system());

        assertThat(itemPersisted("i1")).isTrue();
        assertThat(partPersisted("P1")).isTrue();
    }

    @Test
    void serviceFailure_rollsBackPrimaryAndRoute() throws Exception {
        var runner = runnerWithRoute();

        assertThatThrownBy(() -> runner.run(ctx -> {
            ctx.repository(Item.class).store(new Item("i1", "Ichi"));
            ctx.repository(Part.class).store(new Part("P1", "Bolt"));
            throw new AppServiceException("business failure");
        }, Principal.system())).isInstanceOf(AppServiceException.class);

        assertThat(itemPersisted("i1")).isFalse();
        assertThat(partPersisted("P1")).isFalse();
    }

    @Test
    void readYourWrites_withinServiceOnRoute() throws Exception {
        var runner = runnerWithRoute();

        Optional<Part> seen = runner.run(ctx -> {
            ctx.repository(Part.class).store(new Part("P1", "Bolt"));
            return ctx.retrieve(Part.class, new Part("P1", null));
        }, Principal.system());

        assertThat(seen).map(Part::label).contains("Bolt");
    }

    @Test
    void routeCommitFailure_rollsBackPrimary_asRetryableFailure() throws Exception {
        var failingRoute = decorated(routeDs, conn -> failOnCommit(conn), new AtomicInteger());
        var runner = ServiceRunner.builder()
            .dataSource(primaryDs).describers(primaryRepos)
            .route(failingRoute, routeRepos)
            .build();

        assertThatThrownBy(() -> runner.run(ctx -> {
            ctx.repository(Item.class).store(new Item("i1", "Ichi"));
            ctx.repository(Part.class).store(new Part("P1", "Bolt"));
            return null;
        }, Principal.system()))
            .isInstanceOf(AppServiceException.class)
            .isNotInstanceOf(PostCommitException.class);   // rolled back ⇒ retryable

        assertThat(itemPersisted("i1")).isFalse();
        assertThat(partPersisted("P1")).isFalse();
    }

    // ── Lazy enlistment ───────────────────────────────────────────────────────

    @Test
    void untouchedRoute_opensNoConnection() throws Exception {
        var opened = new AtomicInteger();
        var counting = decorated(routeDs, UnaryOperator.identity(), opened);
        var runner = ServiceRunner.builder()
            .dataSource(primaryDs).describers(primaryRepos)
            .route(counting, routeRepos)
            .build();

        runner.run(ctx -> {
            ctx.repository(Item.class).store(new Item("i1", "Ichi"));
            return null;
        }, Principal.system());

        assertThat(opened.get()).isZero();
    }

    @Test
    void touchedRoute_pinsOneConnectionPerExecution() throws Exception {
        var opened = new AtomicInteger();
        var counting = decorated(routeDs, UnaryOperator.identity(), opened);
        var runner = ServiceRunner.builder()
            .dataSource(primaryDs).describers(primaryRepos)
            .route(counting, routeRepos)
            .build();

        runner.run(ctx -> {
            ctx.repository(Part.class).store(new Part("P1", "Bolt"));
            ctx.repository(Part.class).store(new Part("P2", "Nut"));
            ctx.retrieve(Part.class, new Part("P1", null));
            return null;
        }, Principal.system());

        assertThat(opened.get()).isEqualTo(1);
    }

    // ── Per-operation (non-enlisted) routes ───────────────────────────────────

    @Test
    void instanceRoute_takesEffectImmediately_andIsNotRolledBack() {
        var notes = new InMemoryRepository<Note>();
        var runner = ServiceRunner.builder()
            .dataSource(primaryDs).describers(primaryRepos)
            .route(Note.class, notes)
            .build();

        assertThatThrownBy(() -> runner.run(ctx -> {
            ctx.repository(Note.class).store(new Note("n1", "memo"));
            throw new AppServiceException("business failure");
        }, Principal.system())).isInstanceOf(AppServiceException.class);

        // Documented semantics: per-operation routes are immediate — the store
        // above survives the rolled-back service.
        assertThat(notes.stored).extracting(Note::id).containsExactly("n1");
    }

    // ── Test doubles ──────────────────────────────────────────────────────────

    /** Wraps {@code ds}, counting and decorating every vended connection. */
    private static DataSource decorated(DataSource ds, UnaryOperator<Connection> decorator,
                                        AtomicInteger opened) {
        return new DataSource() {
            @Override public Connection getConnection() throws SQLException {
                opened.incrementAndGet();
                return decorator.apply(ds.getConnection());
            }
            @Override public Connection getConnection(String user, String password)
                    throws SQLException {
                opened.incrementAndGet();
                return decorator.apply(ds.getConnection(user, password));
            }
            @Override public PrintWriter getLogWriter() throws SQLException {
                return ds.getLogWriter();
            }
            @Override public void setLogWriter(PrintWriter out) throws SQLException {
                ds.setLogWriter(out);
            }
            @Override public void setLoginTimeout(int seconds) throws SQLException {
                ds.setLoginTimeout(seconds);
            }
            @Override public int getLoginTimeout() throws SQLException {
                return ds.getLoginTimeout();
            }
            @Override public java.util.logging.Logger getParentLogger() {
                return java.util.logging.Logger.getGlobal();
            }
            @Override public <T> T unwrap(Class<T> iface) throws SQLException {
                return ds.unwrap(iface);
            }
            @Override public boolean isWrapperFor(Class<?> iface) throws SQLException {
                return ds.isWrapperFor(iface);
            }
        };
    }

    /** A connection view whose {@code commit()} always fails. */
    private static Connection failOnCommit(Connection real) {
        return (Connection) Proxy.newProxyInstance(
            Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
                if ("commit".equals(method.getName())) {
                    throw new SQLException("simulated route commit failure");
                }
                try {
                    return method.invoke(real, args);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw e.getCause();
                }
            });
    }

    /** A trivial non-transactional repository, standing in for an HTTP/file backend. */
    private static final class InMemoryRepository<T> implements Repository<T> {
        final List<T> stored = new ArrayList<>();

        @Override public boolean contains(T query) {
            return stored.contains(query);
        }
        @Override public void store(T entity) {
            stored.add(entity);
        }
        @Override public void delete(T entity) {
            stored.remove(entity);
        }
        @Override public Optional<T> retrieve(T query) {
            return stored.stream().filter(query::equals).findFirst();
        }
        @Override public T find(T query) throws ShazoException {
            return retrieve(query).orElseThrow(() -> new NotFoundException("not found"));
        }
        @Override public RawResult catalog(T query) {
            throw new UnsupportedOperationException();
        }
        @Override public List<T> gather(T query) {
            return List.copyOf(stored);
        }
    }
}
