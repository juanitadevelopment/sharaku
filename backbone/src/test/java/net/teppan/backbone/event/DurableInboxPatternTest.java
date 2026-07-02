package net.teppan.backbone.event;

import net.teppan.shazo.jdbc.h2.H2DataSources;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.Serializable;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proof-of-concept for consideration.md §2.6: recover the old
 * {@code PersistentEventQueue} use case — <em>durably accept events from an
 * external app and hand them safely to registered listeners</em> — on top of
 * the existing {@link Outbox}, without adding anything to the published API.
 *
 * <p>The pattern is direction (a): repurpose {@code Outbox} as a durable
 * <em>inbox</em>. {@link DurableInbox} below is the whole pattern in ~40 lines:
 * <ol>
 *   <li>an {@code Outbox} whose deliverer fans out to type-keyed listeners;</li>
 *   <li>a {@code receive(messageId, event)} ingress that writes the event in a
 *       receive transaction and commits — durability begins at that commit;</li>
 *   <li>inbound de-duplication by {@code messageId}, in the <em>same</em>
 *       transaction as the outbox write, so a re-sent external event is not
 *       double-enqueued (gap 3 in §2.6).</li>
 * </ol>
 *
 * <p>These tests pin down the properties that matter for the PEQ use case:
 * delivery happens after commit, on a different thread, in a transaction wholly
 * separate from receipt; it is at-least-once (so listeners must be idempotent);
 * and duplicate external sends are collapsed at intake.
 *
 * <p>What this PoC does <em>not</em> solve (and why §2.6 still favors a
 * first-class API, direction (b)): the ingress is still coupled to a JDBC
 * {@code Connection}/transaction, and everything before the receive-tx commit
 * relies on the sender retrying.
 */
class DurableInboxPatternTest {

    /** An event arriving from some external system. */
    record ExternalOrder(String orderId) implements Serializable {}

    // ── The pattern itself ────────────────────────────────────────────────────

    /** A durable inbox built entirely from the existing {@link Outbox}. */
    static final class DurableInbox implements AutoCloseable {
        private final DataSource ds;
        private final Outbox outbox;
        private final Map<Class<?>, List<Consumer<?>>> listeners = new ConcurrentHashMap<>();

        DurableInbox(DataSource ds, List<Class<?>> eventTypes) {
            this.ds = ds;
            this.outbox = new Outbox(ds, this::dispatch, eventTypes,
                    Duration.ofMillis(50), Duration.ofDays(1));
            ensureDedupTable();
        }

        <E> void subscribe(Class<E> type, Consumer<E> listener) {
            listeners.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(listener);
        }

        /**
         * Ingress from the outside world. Durably accepts {@code event} unless
         * {@code messageId} was already accepted. The dedup marker and the
         * outbox row commit atomically, so intake is all-or-nothing.
         *
         * @return {@code true} if newly accepted, {@code false} if a duplicate
         */
        boolean receive(String messageId, Object event) throws SQLException {
            try (var conn = ds.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    if (!markSeen(conn, messageId)) {
                        conn.rollback();
                        return false;               // duplicate external send
                    }
                    outbox.write(conn, List.of(event));
                    conn.commit();                  // durability starts here
                } catch (Exception e) {
                    conn.rollback();
                    throw (e instanceof SQLException s) ? s : new SQLException(e);
                }
            }
            outbox.poke();                          // deliver promptly
            return true;
        }

        private boolean markSeen(Connection conn, String messageId) throws SQLException {
            try (var ps = conn.prepareStatement(
                    "INSERT INTO inbox_dedup (message_id) VALUES (?)")) {
                ps.setString(1, messageId);
                ps.executeUpdate();
                return true;
            } catch (SQLException e) {
                if (e.getSQLState() != null && e.getSQLState().startsWith("23")) {
                    return false;                   // integrity-constraint => seen
                }
                throw e;
            }
        }

        @SuppressWarnings("unchecked")
        private void dispatch(Object event) {
            // Runs on the outbox poller thread, after commit, with no ambient
            // transaction: any DB work a listener does is its own new tx.
            for (var l : listeners.getOrDefault(event.getClass(), List.of())) {
                ((Consumer<Object>) l).accept(event);   // may throw => at-least-once retry
            }
        }

        private void ensureDedupTable() {
            try (var conn = ds.getConnection();
                 var st = conn.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS inbox_dedup ("
                        + "message_id VARCHAR(200) PRIMARY KEY, "
                        + "received_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            } catch (SQLException e) {
                throw new IllegalStateException("cannot create inbox_dedup", e);
            }
        }

        @Override public void close() { outbox.close(); }
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    private DataSource freshDs() {
        return H2DataSources.inMemory("inbox_" + System.nanoTime());
    }

    @Test
    void deliversExternalEventToRegisteredListener() throws Exception {
        var ds = freshDs();
        var received = new CopyOnWriteArrayList<ExternalOrder>();
        var latch = new CountDownLatch(1);

        try (var inbox = new DurableInbox(ds, List.of(ExternalOrder.class))) {
            inbox.subscribe(ExternalOrder.class, e -> { received.add(e); latch.countDown(); });

            boolean accepted = inbox.receive("evt-1", new ExternalOrder("order-1"));

            assertThat(accepted).isTrue();
            assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(received).containsExactly(new ExternalOrder("order-1"));
        }
    }

    @Test
    void listenerRunsAfterCommitOnAnotherThreadInItsOwnTransaction() throws Exception {
        var ds = freshDs();
        try (var conn = ds.getConnection(); var st = conn.createStatement()) {
            st.execute("CREATE TABLE processed_orders (order_id VARCHAR(50) PRIMARY KEY)");
        }

        String callerThread = Thread.currentThread().getName();
        var listenerThread = new CopyOnWriteArrayList<String>();
        var done = new CountDownLatch(1);

        try (var inbox = new DurableInbox(ds, List.of(ExternalOrder.class))) {
            inbox.subscribe(ExternalOrder.class, e -> {
                listenerThread.add(Thread.currentThread().getName());
                // The listener does its own DB work in a brand-new transaction,
                // exactly as the old PEQ listeners did.
                try (var c = ds.getConnection();
                     var ps = c.prepareStatement(
                             "INSERT INTO processed_orders (order_id) VALUES (?)")) {
                    c.setAutoCommit(false);
                    ps.setString(1, e.orderId());
                    ps.executeUpdate();
                    c.commit();
                } catch (SQLException ex) {
                    throw new RuntimeException(ex);
                }
                done.countDown();
            });

            inbox.receive("evt-2", new ExternalOrder("order-2"));
            assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();
        }

        // Delivery happened on the poller thread, not the receiving caller's.
        assertThat(listenerThread).hasSize(1);
        assertThat(listenerThread.get(0)).isNotEqualTo(callerThread);

        // The listener's own transaction committed independently of receipt.
        try (var conn = ds.getConnection();
             var ps = conn.prepareStatement("SELECT COUNT(*) FROM processed_orders WHERE order_id = ?")) {
            ps.setString(1, "order-2");
            try (var rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Test
    void deduplicatesResentExternalEventsAtIntake() throws Exception {
        var ds = freshDs();
        var received = new CopyOnWriteArrayList<ExternalOrder>();
        var firstDelivered = new CountDownLatch(1);

        try (var inbox = new DurableInbox(ds, List.of(ExternalOrder.class))) {
            inbox.subscribe(ExternalOrder.class, e -> { received.add(e); firstDelivered.countDown(); });

            // Same messageId sent twice (the external sender's own at-least-once).
            boolean first  = inbox.receive("evt-3", new ExternalOrder("order-3"));
            boolean second = inbox.receive("evt-3", new ExternalOrder("order-3"));

            assertThat(first).isTrue();
            assertThat(second).isFalse();                    // collapsed at intake
            assertThat(firstDelivered.await(3, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(200);                               // allow any stray 2nd delivery
            assertThat(received).containsExactly(new ExternalOrder("order-3"));
        }
    }

    @Test
    void redeliversAtLeastOnceWhenAListenerFailsThenRecovers() throws Exception {
        var ds = freshDs();
        var attempts = new AtomicInteger();
        var succeeded = new CountDownLatch(1);

        try (var inbox = new DurableInbox(ds, List.of(ExternalOrder.class))) {
            inbox.subscribe(ExternalOrder.class, e -> {
                if (attempts.incrementAndGet() < 2) {
                    throw new RuntimeException("downstream not ready");
                }
                succeeded.countDown();
            });

            inbox.receive("evt-4", new ExternalOrder("order-4"));

            assertThat(succeeded.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(attempts.get()).isGreaterThanOrEqualTo(2);   // => listeners must be idempotent
        }
    }
}
