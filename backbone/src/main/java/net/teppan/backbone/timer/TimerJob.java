package net.teppan.backbone.timer;

import net.teppan.backbone.AppContext;

/**
 * A scheduled background task executed by {@link TimerScheduler}.
 *
 * <p>Implement as a lambda; the job runs as a {@link net.teppan.backbone.Principal#system()}
 * unit of work, receiving a transactional {@link AppContext}.
 *
 * <pre>{@code
 * TimerJob cleanup = ctx -> {
 *     try (var ps = ctx.connection().prepareStatement(
 *             "DELETE FROM session WHERE expires_at < CURRENT_TIMESTAMP")) {
 *         ps.executeUpdate();
 *     }
 * };
 * scheduler.schedule("session-cleanup", Duration.ofHours(1), cleanup);
 * }</pre>
 *
 * <p><strong>Current limitations.</strong> The scheduler's internal runner has
 * no describer registry and no event subscribers: use
 * {@link AppContext#connection()} for storage access
 * ({@link AppContext#repository(Class)} and {@code store}/{@code retrieve} by
 * type throw {@link IllegalStateException}), and events published via
 * {@link AppContext#publish} from a timer job are <em>not delivered</em> to
 * anyone. To publish real events from scheduled work, have the job invoke your
 * application's own {@code ServiceRunner}.
 *
 * @see TimerScheduler
 */
@FunctionalInterface
public interface TimerJob {

    /**
     * Executes this job.
     *
     * <p>Exceptions are caught and logged by {@link TimerScheduler}; they do
     * not affect subsequent executions.
     *
     * @param ctx the system context for this execution; never {@code null}
     * @throws Exception if the job fails
     */
    void run(AppContext ctx) throws Exception;
}
