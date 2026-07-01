package net.teppan.shazo.jdbc;

import net.teppan.shazo.Repository;
import net.teppan.shazo.ShazoException;

/**
 * A unit of work executed within a {@link JdbcRepository} transaction.
 *
 * <p>Implementations receive a {@link Repository} bound to an active JDBC
 * connection with auto-commit disabled. The transaction is committed when
 * {@link JdbcRepository#transact} returns normally; it is rolled back if
 * the task throws.
 *
 * <pre>{@code
 * repo.transact(r -> {
 *     r.store(new Person("1", "Alice", 30));
 *     r.store(new Person("2", "Bob", 25));
 *     return 2;          // number of stored entities
 * });
 *
 * // Void tasks return null:
 * repo.transact(r -> {
 *     r.delete(staleRecord);
 *     return null;
 * });
 * }</pre>
 *
 * @param <T> the domain type of the repository
 * @param <R> the result type; use {@link Void} and return {@code null} for
 *            side-effect-only tasks
 * @see JdbcRepository#transact(StorageTask)
 */
@FunctionalInterface
public interface StorageTask<T, R> {

    /**
     * Executes the task using the given transactional repository.
     *
     * @param repository the repository bound to the current transaction
     * @return the task result; may be {@code null} for {@code Void} tasks
     * @throws ShazoException if the task fails and the transaction should roll back
     */
    R execute(Repository<T> repository) throws ShazoException;
}
