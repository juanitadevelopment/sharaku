package net.teppan.backbone.event;

import net.teppan.backbone.testsupport.Await;
import net.teppan.shazo.jdbc.h2.H2DataSources;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.Serializable;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the first-class durable intake queue that revives the legacy
 * {@code PersistentEventQueue} use case (consideration.md §2.6): durably accept
 * events from an external app and hand them to registered listeners in a
 * transaction wholly separate from receipt.
 */
class PersistentEventQueueTest {

    /** An event arriving from some external system. */
    record ExternalOrder(String orderId) implements Serializable {}

    private DataSource freshDs() {
        return H2DataSources.inMemory("pq_" + System.nanoTime());
    }

    private PersistentEventQueue<ExternalOrder> queue(DataSource ds, int maxAttempts) {
        return new PersistentEventQueue<>("orders", ExternalOrder.class, ds,
            Duration.ofMillis(50), Duration.ofDays(1), maxAttempts, List.of());
    }

    @Test
    void deliversExternalEventToRegisteredListener() throws Exception {
        var received = new CopyOnWriteArrayList<ExternalOrder>();
        var latch = new CountDownLatch(1);

        try (var q = queue(freshDs(), Outbox.DEFAULT_MAX_ATTEMPTS)) {
            q.subscribe(e -> { received.add(e); latch.countDown(); });

            boolean accepted = q.receive("evt-1", new ExternalOrder("order-1"));

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

        try (var q = queue(ds, Outbox.DEFAULT_MAX_ATTEMPTS)) {
            q.subscribe(e -> {
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

            q.receive("evt-2", new ExternalOrder("order-2"));
            assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(listenerThread).hasSize(1);
        assertThat(listenerThread.get(0)).isNotEqualTo(callerThread);
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
        var received = new CopyOnWriteArrayList<ExternalOrder>();
        var firstDelivered = new CountDownLatch(1);

        try (var q = queue(freshDs(), Outbox.DEFAULT_MAX_ATTEMPTS)) {
            q.subscribe(e -> { received.add(e); firstDelivered.countDown(); });

            // Same messageId sent twice (the external sender's own at-least-once).
            boolean first  = q.receive("evt-3", new ExternalOrder("order-3"));
            boolean second = q.receive("evt-3", new ExternalOrder("order-3"));

            assertThat(first).isTrue();
            assertThat(second).isFalse();                    // collapsed at intake
            assertThat(firstDelivered.await(3, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(200);                               // allow any stray 2nd delivery
            assertThat(received).containsExactly(new ExternalOrder("order-3"));
        }
    }

    @Test
    void publishAlwaysEnqueuesUnderAFreshId() throws Exception {
        var received = new CopyOnWriteArrayList<ExternalOrder>();

        try (var q = queue(freshDs(), Outbox.DEFAULT_MAX_ATTEMPTS)) {
            q.subscribe(received::add);

            q.publish(new ExternalOrder("order-a"));
            q.publish(new ExternalOrder("order-a"));   // same payload, still enqueued

            Await.until(() -> received.size() == 2);
            assertThat(received).containsExactly(
                new ExternalOrder("order-a"), new ExternalOrder("order-a"));
        }
    }

    @Test
    void fansOutToAllSubscribers() throws Exception {
        var a = new CopyOnWriteArrayList<ExternalOrder>();
        var b = new CopyOnWriteArrayList<ExternalOrder>();

        try (var q = queue(freshDs(), Outbox.DEFAULT_MAX_ATTEMPTS)) {
            q.subscribe(a::add);
            q.subscribe(b::add);

            q.receive("evt-5", new ExternalOrder("order-5"));

            Await.until(() -> !a.isEmpty() && !b.isEmpty());
            assertThat(a).containsExactly(new ExternalOrder("order-5"));
            assertThat(b).containsExactly(new ExternalOrder("order-5"));
        }
    }

    @Test
    void redeliversAtLeastOnceWhenAListenerFailsThenRecovers() throws Exception {
        var attempts = new AtomicInteger();
        var succeeded = new CountDownLatch(1);

        try (var q = queue(freshDs(), Outbox.DEFAULT_MAX_ATTEMPTS)) {
            q.subscribe(e -> {
                if (attempts.incrementAndGet() < 2) {
                    throw new RuntimeException("downstream not ready");
                }
                succeeded.countDown();
            });

            q.receive("evt-6", new ExternalOrder("order-6"));

            assertThat(succeeded.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(attempts.get()).isGreaterThanOrEqualTo(2);   // listeners must be idempotent
        }
    }

    @Test
    void deadLettersAfterMaxAttemptsThenRetryRedelivers() throws Exception {
        var down = new AtomicBoolean(true);
        var delivered = new AtomicInteger();

        try (var q = queue(freshDs(), 2)) {   // dead-letter after 2 failed attempts
            q.subscribe(e -> {
                if (down.get()) throw new RuntimeException("downstream down");
                delivered.incrementAndGet();
            });

            q.receive("evt-7", new ExternalOrder("order-7"));

            Await.until(() -> q.deadLetterCount() == 1);
            var dead = q.peekDeadLetters(10);
            assertThat(dead).hasSize(1);

            // Fix the downstream and replay (the old queue browser's replay).
            down.set(false);
            assertThat(q.retry(dead.get(0).id())).isTrue();

            Await.until(() -> delivered.get() == 1);
            assertThat(q.deadLetterCount()).isZero();
        }
    }

    @Test
    void rejectsInvalidQueueName() {
        var ds = freshDs();
        assertThatThrownBy(() -> new PersistentEventQueue<>("bad-name", ExternalOrder.class, ds))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Queue name");
    }
}
