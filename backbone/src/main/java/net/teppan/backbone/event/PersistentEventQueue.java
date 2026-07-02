package net.teppan.backbone.event;

import net.teppan.shazo.ShazoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * A durable, at-least-once {@link EventQueue} that accepts events — typically
 * from <em>outside</em> the application — and hands them to registered
 * {@linkplain #subscribe subscribers} in a transaction wholly separate from
 * receipt.
 *
 * <p>This is the modern counterpart of the legacy {@code PersistentEventQueue}
 * (the "guaranteed" queue that {@link TransientEventQueue} is the transient peer
 * of): a named, restart-surviving intake point that fans one event out to many
 * listeners, each running after the receive commits, on a poller thread, in its
 * own unit of work — exactly as the original's listeners did.
 *
 * <h2>How it works</h2>
 * <p>Each queue owns its own partition (table {@code backbone_pq_<name>}) driven
 * by the same engine as the transactional {@link Outbox}, so it inherits the
 * outbox's poller, dead-lettering, retry/discard triage, and processed-row
 * purge. Delivery is <strong>at-least-once</strong>; subscribers must be
 * idempotent.
 *
 * <h2>Idempotent intake</h2>
 * <p>{@link #receive(String, Object)} accepts an event under a caller-supplied
 * {@code messageId}. The dedup marker and the queued row commit atomically, so a
 * re-sent external event (the sender's own at-least-once) is collapsed at
 * intake rather than delivered twice. {@link #publish(Object)} is the
 * fire-and-forget form for app-generated events: it accepts under a fresh random
 * id, so every call is enqueued.
 *
 * <h2>Multi-tenancy</h2>
 * <p>When obtained from a tenant-routed {@link net.teppan.backbone.ServiceRunner}
 * (via {@link net.teppan.backbone.ServiceRunner#persistentQueue(String, String)}),
 * each tenant gets its own queue instance on that tenant's data source, sharing
 * the same declared subscribers.
 *
 * <p>Events must be {@link java.io.Serializable}; their concrete types are
 * declared up front so the deserialization allowlist can reject anything else.
 *
 * @param <E> the base event type this queue carries
 * @see Outbox
 * @see net.teppan.backbone.ServiceRunner.Builder#persistentQueue(String, Class, Class[])
 */
public final class PersistentEventQueue<E> implements EventQueue<E> {

    private static final Logger log = LoggerFactory.getLogger(PersistentEventQueue.class);

    /** Prefix for the per-queue backing table. */
    static final String TABLE_PREFIX = "backbone_pq_";

    /** Queue names must be safe SQL identifiers — they are interpolated into DDL. */
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    private final String name;
    private final DataSource dataSource;
    private final String dedupTable;
    private final Outbox outbox;
    private final List<Consumer<E>> listeners = new CopyOnWriteArrayList<>();

    /**
     * Creates and starts a persistent event queue with default tuning
     * (200&nbsp;ms poll, 7-day retention, {@value Outbox#DEFAULT_MAX_ATTEMPTS}
     * delivery attempts) carrying a single event type.
     *
     * @param name       the queue name; must match {@code [A-Za-z][A-Za-z0-9_]*}
     * @param baseType   the (serializable) event type carried; never {@code null}
     * @param dataSource the database backing this queue; never {@code null}
     */
    public PersistentEventQueue(String name, Class<E> baseType, DataSource dataSource) {
        this(name, baseType, dataSource,
            Duration.ofMillis(200), Duration.ofDays(7), Outbox.DEFAULT_MAX_ATTEMPTS, List.of());
    }

    /**
     * Creates and starts a persistent event queue.
     *
     * @param name         the queue name; must match {@code [A-Za-z][A-Za-z0-9_]*}
     * @param baseType     the base event type carried; never {@code null}
     * @param dataSource   the database backing this queue; never {@code null}
     * @param pollInterval how long the poller waits when idle; never {@code null}
     * @param retention    how long processed rows are kept; never {@code null}
     * @param maxAttempts  delivery attempts before dead-lettering; must be &ge; 1
     * @param payloadTypes additional concrete serializable subtypes to allowlist
     *                     for decoding (beyond {@code baseType}); never {@code null}
     */
    public PersistentEventQueue(String name, Class<E> baseType, DataSource dataSource,
                                Duration pollInterval, Duration retention, int maxAttempts,
                                List<Class<?>> payloadTypes) {
        this.name = validateName(name);
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(baseType, "baseType");
        Objects.requireNonNull(payloadTypes, "payloadTypes");

        String table = TABLE_PREFIX + name;
        this.dedupTable = table + "_dedup";

        var allowed = new java.util.ArrayList<Class<?>>();
        allowed.add(baseType);
        allowed.addAll(payloadTypes);

        this.outbox = new Outbox(dataSource, this::dispatch, allowed,
            pollInterval, retention, maxAttempts, table);
        ensureDedupTable();
    }

    /** The name of this queue. @return the queue name; never {@code null} */
    public String name() {
        return name;
    }

    /**
     * Durably accepts {@code event} under {@code messageId}, unless that id was
     * already accepted. The dedup marker and the queued row commit atomically, so
     * intake is all-or-nothing and a duplicate external send is not re-enqueued.
     * Durability begins at the returned {@code true}.
     *
     * @param messageId the sender's idempotency key; never {@code null}
     * @param event     the event to accept; never {@code null}
     * @return {@code true} if newly accepted, {@code false} if {@code messageId}
     *         was already seen (a duplicate)
     */
    public boolean receive(String messageId, E event) {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(event, "event");
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                if (!markSeen(conn, messageId)) {
                    conn.rollback();
                    return false;                       // duplicate external send
                }
                outbox.write(conn, List.of(event));
                conn.commit();                          // durability starts here
            } catch (ShazoException | SQLException e) {
                conn.rollback();
                throw new IllegalStateException("Failed to receive into queue " + name, e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to receive into queue " + name, e);
        }
        outbox.poke();                                  // deliver promptly
        return true;
    }

    /**
     * Accepts an application-generated {@code event} under a fresh random id, so
     * it is always enqueued. For events arriving from outside the application,
     * prefer {@link #receive(String, Object)} with the sender's message id so
     * re-sends are de-duplicated.
     *
     * @param event the event to enqueue; never {@code null}
     */
    @Override
    public void publish(E event) {
        receive(UUID.randomUUID().toString(), event);
    }

    /**
     * Registers a subscriber for every event delivered by this queue. Subscribers
     * run on the poller thread, after the receive commits, each in its own new
     * transaction; a subscriber that throws triggers at-least-once redelivery of
     * the whole event, so all subscribers must be idempotent.
     *
     * @param listener the event consumer; never {@code null}
     */
    @Override
    public void subscribe(Consumer<E> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Returns the number of events awaiting delivery, clamped to
     * {@code int} range; see {@link #pendingCount()} for the exact value.
     *
     * @return the pending event count
     */
    @Override
    public int size() {
        return (int) Math.min(outbox.pendingCount(), Integer.MAX_VALUE);
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    @Override
    public void close() {
        outbox.close();
    }

    // ── Triage passthrough (queue-browser / replay parity) ────────────────────

    /**
     * Returns the number of events awaiting delivery (excludes dead-lettered).
     *
     * @return the pending count
     */
    public long pendingCount() {
        return outbox.pendingCount();
    }

    /**
     * Returns the number of dead-lettered events — those that exhausted their
     * delivery attempts or could not be decoded.
     *
     * @return the dead-letter count
     */
    public long deadLetterCount() {
        return outbox.deadLetterCount();
    }

    /**
     * Returns up to {@code limit} dead-lettered events, oldest first, for triage.
     *
     * @param limit the maximum number of entries; must be &ge; 0
     * @return the dead-lettered entries (metadata only)
     */
    public List<OutboxEntry> peekDeadLetters(int limit) {
        return outbox.peekDeadLetters(limit);
    }

    /**
     * Requeues a dead-lettered event for delivery, resetting its attempt count —
     * the modern equivalent of the legacy queue browser's replay.
     *
     * @param id the queue row id (from {@link #peekDeadLetters(int)})
     * @return {@code true} if a dead-lettered row was requeued
     */
    public boolean retry(long id) {
        return outbox.retry(id);
    }

    /**
     * Permanently discards an event, whatever its status.
     *
     * @param id the queue row id
     * @return {@code true} if a row was deleted
     */
    public boolean discard(long id) {
        return outbox.discard(id);
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void dispatch(Object event) {
        // Runs on the poller thread, after commit, with no ambient transaction:
        // any DB work a listener does opens its own new transaction.
        for (var l : listeners) {
            l.accept((E) event);   // may throw => at-least-once redelivery
        }
    }

    private boolean markSeen(Connection conn, String messageId) throws SQLException {
        try (var ps = conn.prepareStatement(
                "INSERT INTO " + dedupTable + " (message_id) VALUES (?)")) {
            ps.setString(1, messageId);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            if (e.getSQLState() != null && e.getSQLState().startsWith("23")) {
                return false;      // integrity-constraint violation => already seen
            }
            throw e;
        }
    }

    private void ensureDedupTable() {
        try (var conn = dataSource.getConnection();
             var st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + dedupTable + " ("
                + "message_id  VARCHAR(200) PRIMARY KEY, "
                + "received_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create dedup table " + dedupTable, e);
        }
    }

    private static String validateName(String name) {
        Objects.requireNonNull(name, "name");
        if (!VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                "Queue name must match [A-Za-z][A-Za-z0-9_]*: " + name);
        }
        return name;
    }
}
