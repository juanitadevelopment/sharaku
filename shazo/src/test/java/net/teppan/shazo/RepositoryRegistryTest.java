package net.teppan.shazo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link RepositoryRegistry}.
 */
class RepositoryRegistryTest {

    record Person(String id, String name) {}
    record Order(String id, double total) {}

    private RepositoryRegistry registry;
    private InMemoryRepository<Person> personRepo;
    private InMemoryRepository<Order> orderRepo;

    @BeforeEach
    void setUp() {
        registry   = RepositoryRegistry.create();
        personRepo = new InMemoryRepository<>(Person::id);
        orderRepo  = new InMemoryRepository<>(Order::id);
    }

    // ── register + lookup ─────────────────────────────────────────────────────

    @Test
    void lookupReturnsEmptyBeforeRegistration() {
        assertThat(registry.lookup("persons", Person.class)).isEmpty();
    }

    @Test
    void lookupReturnsRepositoryAfterRegistration() {
        registry.register("persons", Person.class, personRepo);

        assertThat(registry.lookup("persons", Person.class)).contains(personRepo);
    }

    @Test
    void registrationOfDifferentTypesUnderSameNameAreIndependent() {
        registry.register("things", Person.class, personRepo);
        registry.register("things", Order.class,  orderRepo);

        assertThat(registry.lookup("things", Person.class)).contains(personRepo);
        assertThat(registry.lookup("things", Order.class)).contains(orderRepo);
    }

    @Test
    void laterRegistrationReplacesEarlier() {
        var first  = new InMemoryRepository<Person>(Person::id);
        var second = new InMemoryRepository<Person>(Person::id);
        registry.register("persons", Person.class, first);
        registry.register("persons", Person.class, second);

        assertThat(registry.lookup("persons", Person.class)).contains(second);
    }

    // ── require ───────────────────────────────────────────────────────────────

    @Test
    void requireReturnsRepositoryWhenRegistered() {
        registry.register("persons", Person.class, personRepo);

        assertThat(registry.require("persons", Person.class)).isSameAs(personRepo);
    }

    @Test
    void requireThrowsIllegalStateExceptionWhenAbsent() {
        assertThatThrownBy(() -> registry.require("persons", Person.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("persons")
            .hasMessageContaining(Person.class.getName());
    }

    // ── forWorkgroup ──────────────────────────────────────────────────────────

    @Test
    void workgroupScopedRegistrationIsIsolatedByPrefix() {
        var billing   = registry.forWorkgroup("billing");
        var logistics = registry.forWorkgroup("logistics");

        billing.register("persons", Person.class, personRepo);

        assertThat(billing.lookup("persons", Person.class)).contains(personRepo);
        assertThat(logistics.lookup("persons", Person.class)).isEmpty();
    }

    @Test
    void workgroupScopedRequireThrowsWhenAbsent() {
        var wg = registry.forWorkgroup("billing");
        assertThatThrownBy(() -> wg.require("persons", Person.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("billing.persons");
    }

    @Test
    void workgroupRegistriesShareUnderlyingStore() {
        var wg1 = registry.forWorkgroup("wg1");
        var wg2 = registry.forWorkgroup("wg1");

        wg1.register("persons", Person.class, personRepo);

        assertThat(wg2.lookup("persons", Person.class)).contains(personRepo);
    }

    // ── independent instances ─────────────────────────────────────────────────

    @Test
    void twoRegistriesAreIndependent() {
        var reg2 = RepositoryRegistry.create();
        registry.register("persons", Person.class, personRepo);

        assertThat(reg2.lookup("persons", Person.class)).isEmpty();
    }
}
