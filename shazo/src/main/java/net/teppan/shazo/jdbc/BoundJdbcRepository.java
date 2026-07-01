package net.teppan.shazo.jdbc;

import net.teppan.shazo.AbstractRepository;
import net.teppan.shazo.Describer;
import net.teppan.shazo.RawResult;
import net.teppan.shazo.ShazoException;

import java.sql.Connection;
import java.util.List;

/**
 * A {@link net.teppan.shazo.Repository} implementation pinned to a single
 * JDBC {@link Connection}, used internally by
 * {@link JdbcRepository#transact(StorageTask)}.
 *
 * <p>This class is package-private. Callers receive it as a {@code Repository<T>}
 * through the {@link StorageTask} parameter, which is the only appropriate
 * scope for it.
 */
final class BoundJdbcRepository<T> extends AbstractRepository<T, SqlCommand> {

    private final Connection connection;

    BoundJdbcRepository(Connection connection, Describer<T, SqlCommand> describer) {
        super(describer);
        this.connection = connection;
    }

    @Override
    protected RawResult execute(List<SqlCommand> commands) throws ShazoException {
        return JdbcRepository.executeOnConnection(connection, commands);
    }
}
