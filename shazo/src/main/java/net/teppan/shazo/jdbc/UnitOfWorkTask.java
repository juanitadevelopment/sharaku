package net.teppan.shazo.jdbc;

import net.teppan.shazo.ShazoException;

/**
 * A unit of work to be executed within a single transaction by a
 * {@link Transactor}.
 *
 * <p>Implement as a lambda. The task receives a {@link UnitOfWork} from which it
 * obtains transaction-scoped repositories. Returning normally commits the
 * transaction; throwing any exception rolls it back.
 *
 * @param <R> the task's result type (use {@link Void} and return {@code null}
 *            when there is no result)
 * @see Transactor#execute(UnitOfWorkTask)
 */
@FunctionalInterface
public interface UnitOfWorkTask<R> {

    /**
     * Performs the unit of work.
     *
     * @param uow the transactional unit of work; never {@code null}
     * @return the task result
     * @throws ShazoException if the work fails (triggers a rollback)
     */
    R perform(UnitOfWork uow) throws ShazoException;
}
