package net.teppan.shazo;

import java.util.Optional;

/**
 * A named, type-safe registry of {@link Repository} instances.
 *
 * <p>{@code RepositoryRegistry} is an injectable, instance-based alternative
 * to static service locators. Create registries with {@link #create()} and
 * pass them through constructors to keep components testable and isolated.
 *
 * <h2>Registering and resolving repositories</h2>
 * <pre>{@code
 * var registry = RepositoryRegistry.create();
 * registry.register("persons", Person.class, personRepository);
 *
 * // Optional lookup:
 * repository.lookup("persons", Person.class)
 *     .ifPresent(repo -> ...);
 *
 * // Mandatory lookup (throws if absent):
 * Repository<Person> repo = registry.require("persons", Person.class);
 * }</pre>
 *
 * <h2>Workgroup scoping</h2>
 * <pre>{@code
 * // Register under "billing.persons":
 * RepositoryRegistry billing = registry.forWorkgroup("billing");
 * billing.register("persons", Person.class, billingPersonRepo);
 *
 * // Look up under "billing.persons":
 * var repo = billing.require("persons", Person.class);
 * }</pre>
 *
 * @see #create()
 */
public interface RepositoryRegistry {

    /**
     * Registers a repository under the given name and type.
     * Replaces any previously registered repository for the same name and type.
     *
     * @param name       the logical name
     * @param type       the domain type; used as part of the lookup key
     * @param repository the repository to register; never {@code null}
     * @param <T>        the domain type
     */
    <T> void register(String name, Class<T> type, Repository<T> repository);

    /**
     * Looks up a repository by name and type.
     *
     * @param name the logical name
     * @param type the domain type
     * @param <T>  the domain type
     * @return an {@link Optional} containing the registered repository, or empty
     */
    <T> Optional<Repository<T>> lookup(String name, Class<T> type);

    /**
     * Returns the registered repository for the given name and type,
     * throwing when none is registered.
     *
     * @param name the logical name
     * @param type the domain type
     * @param <T>  the domain type
     * @return the registered repository; never {@code null}
     * @throws IllegalStateException if no repository is registered for the name and type
     */
    default <T> Repository<T> require(String name, Class<T> type) {
        return lookup(name, type).orElseThrow(() ->
            new IllegalStateException(
                "No repository registered for name='" + name
                + "', type=" + type.getName()));
    }

    /**
     * Returns a view of this registry scoped to a given workgroup.
     * All names in the scoped view are internally prefixed with
     * {@code workgroup + "."}.
     *
     * <p>Multiple scoped views may be derived from the same parent registry
     * without interfering with each other.
     *
     * @param workgroup the workgroup identifier; never {@code null}
     * @return a workgroup-scoped view of this registry
     */
    default RepositoryRegistry forWorkgroup(String workgroup) {
        var parent = this;
        var prefix = workgroup + ".";
        return new RepositoryRegistry() {
            @Override
            public <T> void register(String name, Class<T> type, Repository<T> repo) {
                parent.register(prefix + name, type, repo);
            }

            @Override
            public <T> Optional<Repository<T>> lookup(String name, Class<T> type) {
                return parent.lookup(prefix + name, type);
            }

            @Override
            public <T> Repository<T> require(String name, Class<T> type) {
                return lookup(name, type).orElseThrow(() ->
                    new IllegalStateException(
                        "No repository registered for name='" + prefix + name
                        + "', type=" + type.getName()));
            }
        };
    }

    /**
     * Creates a new, empty thread-safe {@code RepositoryRegistry}.
     *
     * @return a new registry instance
     */
    static RepositoryRegistry create() {
        return new net.teppan.shazo.internal.DefaultRepositoryRegistry();
    }
}
