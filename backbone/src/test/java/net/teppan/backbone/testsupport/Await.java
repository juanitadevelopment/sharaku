package net.teppan.backbone.testsupport;

/**
 * Test-only helper: poll a condition until it holds or a deadline passes.
 *
 * <p>Asynchronous behaviour (outbox delivery, timer jobs, queue draining) makes
 * a fixed {@code Thread.sleep(...)} both slow (it always waits the whole window)
 * and flaky (under CI load the window may be too short). Polling a condition
 * returns as soon as the expectation is met and only fails after a generous
 * deadline, so it is both faster in the common case and more robust under load.
 *
 * <p>Use it for "wait until something eventually happens". It is <em>not</em> a
 * substitute for a fixed wait that asserts something <em>never</em> happens
 * (a negative assertion) — those genuinely need to elapse a window.
 */
public final class Await {

    private static final long DEFAULT_TIMEOUT_MS = 3_000;
    private static final long POLL_MS            = 20;

    private Await() {}

    /** A boolean condition that may throw while being evaluated (e.g. a DB read). */
    @FunctionalInterface
    public interface Condition {
        boolean test() throws Exception;
    }

    /** Polls {@code condition} until it holds or the default 3s deadline passes. */
    public static void until(Condition condition) throws Exception {
        until(condition, DEFAULT_TIMEOUT_MS);
    }

    /**
     * Polls {@code condition} until it holds or {@code timeoutMs} elapses.
     *
     * @throws AssertionError if the condition is still false at the deadline
     */
    public static void until(Condition condition, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.test()) return;
            Thread.sleep(POLL_MS);
        }
        if (!condition.test()) {   // final check closes the poll/deadline race
            throw new AssertionError("condition not met within " + timeoutMs + "ms");
        }
    }
}
