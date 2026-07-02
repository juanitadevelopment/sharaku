package net.teppan.backbone;

import java.util.List;

/**
 * Thrown when a service's <em>post-commit</em> work fails — after its
 * transaction has already committed.
 *
 * <p>A {@link ServiceRunner} runs a service in a transaction and, only once that
 * transaction has committed, delivers the events the service
 * {@linkplain AppContext#publish published} in-process and runs its
 * {@linkplain AppContext#afterCommit after-commit} actions. A failure here is
 * fundamentally different from a failure <em>inside</em> the service: the
 * business change is already durable and <strong>will not be rolled back</strong>.
 *
 * <p>This distinction matters for recovery. A plain {@link AppServiceException}
 * from {@code execute}/{@code run} means the transaction rolled back, so the
 * caller may safely retry the whole operation. A {@code PostCommitException}
 * means the opposite: retrying the service would re-apply an already-committed
 * change (a double-execution). Catch this type first to compensate for the
 * failed side effect instead — e.g. re-enqueue a notification — without redoing
 * the committed work.
 *
 * <pre>{@code
 * try {
 *     runner.execute("placeOrder", principal);
 * } catch (PostCommitException e) {
 *     // The order IS placed. Only after-commit side effects failed.
 *     alerting.warn("post-commit side effect failed", e);
 * } catch (AppServiceException e) {
 *     // The order was NOT placed (rolled back); safe to retry.
 *     retry("placeOrder");
 * }
 * }</pre>
 *
 * <p>The individual failures (one per failed event listener or after-commit
 * action) are attached both as {@linkplain #getSuppressed() suppressed}
 * exceptions and via {@link #failures()}.
 *
 * <p>Note that durable ({@linkplain ServiceRunner.Builder#durableEvents outbox})
 * events are <em>not</em> a source of this exception: they are delivered
 * asynchronously by the outbox poller with its own at-least-once retry, so a
 * listener failure there never propagates back to {@code execute}/{@code run}.
 *
 * @see ServiceRunner
 * @see AppContext#afterCommit(Runnable)
 */
public final class PostCommitException extends AppServiceException {

    private final transient List<Throwable> failures;

    /**
     * Constructs a {@code PostCommitException} carrying the post-commit failures.
     *
     * @param message  the detail message
     * @param failures the individual post-commit failures; each is also added as
     *                 a suppressed exception. Never {@code null}
     */
    public PostCommitException(String message, List<Throwable> failures) {
        super(message);
        this.failures = List.copyOf(failures);
        this.failures.forEach(this::addSuppressed);
    }

    /**
     * Returns the individual post-commit failures — one per event listener or
     * after-commit action that threw. Same set as {@link #getSuppressed()}.
     *
     * @return an immutable list of the failures; never {@code null}
     */
    public List<Throwable> failures() {
        return failures;
    }
}
