package net.teppan.demo.testsupport;

/**
 * Test-only helper: poll a condition until it holds or a deadline passes.
 *
 * <p>Mirrors {@code net.teppan.backbone.testsupport.Await}; the demo module has
 * its own copy because test sources are not shared across Gradle modules and
 * pulling in test-fixtures would change what {@code :backbone} publishes.
 *
 * <p>Use it for "wait until something eventually happens", not for a fixed wait
 * that asserts something <em>never</em> happens.
 */
public final class Await {

    private static final long DEFAULT_TIMEOUT_MS = 3_000;
    private static final long POLL_MS            = 25;

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
