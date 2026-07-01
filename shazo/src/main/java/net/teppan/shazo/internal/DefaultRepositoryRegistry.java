package net.teppan.shazo.internal;

import net.teppan.shazo.Repository;
import net.teppan.shazo.RepositoryRegistry;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe default implementation of {@link RepositoryRegistry}.
 *
 * <p>Not part of the public API — obtain instances via
 * {@link RepositoryRegistry#create()}.
 */
public final class DefaultRepositoryRegistry implements RepositoryRegistry {

    /** Constructs an empty registry. */
    public DefaultRepositoryRegistry() {}

    private record RegistryKey(String name, String typeName) {}

    private final ConcurrentHashMap<RegistryKey, Repository<?>> store =
        new ConcurrentHashMap<>();

    @Override
    public <T> void register(String name, Class<T> type, Repository<T> repository) {
        store.put(new RegistryKey(name, type.getName()), repository);
    }

    @Override
    public <T> Optional<Repository<T>> lookup(String name, Class<T> type) {
        var entry = store.get(new RegistryKey(name, type.getName()));
        if (entry == null) return Optional.empty();
        // Safe: register() guarantees that RegistryKey(name, type.getName())
        // maps to a Repository<T> where T == type. The type name is the key.
        @SuppressWarnings("unchecked")
        var typed = (Repository<T>) entry;
        return Optional.of(typed);
    }
}
