package net.teppan.backbone;

import net.teppan.backbone.event.PersistentEventQueue;
import net.teppan.backbone.testsupport.Await;
import net.teppan.shazo.jdbc.SessionInitDataSource;
import net.teppan.shazo.jdbc.h2.H2DataSources;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.Serializable;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wiring of {@link PersistentEventQueue} through {@link ServiceRunner}: builder
 * declaration + listeners, resolution, and per-tenant isolation.
 */
class ServiceRunnerPersistentQueueTest {

    record ExternalOrder(String orderId) implements Serializable {}

    @Test
    void runnerDeliversToDeclaredListener() throws Exception {
        var ds = H2DataSources.inMemory("rpq_" + System.nanoTime());
        var received = new CopyOnWriteArrayList<ExternalOrder>();

        try (var runner = ServiceRunner.builder()
                .dataSource(ds)
                .persistentQueue("orders", ExternalOrder.class)
                .persistentQueueListener("orders", (ExternalOrder e) -> received.add(e))
                .build()) {

            assertThat(runner.persistentQueueNames()).containsExactly("orders");

            PersistentEventQueue<ExternalOrder> q = runner.persistentQueue("orders");
            assertThat(q.receive("m-1", new ExternalOrder("order-1"))).isTrue();

            Await.until(() -> received.size() == 1);
            assertThat(received).containsExactly(new ExternalOrder("order-1"));
        }
    }

    @Test
    void perTenantQueuesAreIsolated() throws Exception {
        var shared = H2DataSources.inMemory("rpq_mt_" + System.nanoTime());
        try (var conn = shared.getConnection(); var st = conn.createStatement()) {
            for (var t : List.of("acme", "globex")) {
                st.execute("CREATE SCHEMA IF NOT EXISTS " + t);
            }
        }
        Function<String, DataSource> router =
            tenant -> new SessionInitDataSource(shared, "SET SCHEMA " + tenant);

        var received = new CopyOnWriteArrayList<ExternalOrder>();

        try (var runner = ServiceRunner.builder()
                .tenantRouter(router)
                .persistentQueue("orders", ExternalOrder.class)
                .persistentQueueListener("orders", (ExternalOrder e) -> received.add(e))
                .build()) {

            var acme   = runner.<ExternalOrder>persistentQueue("orders", "acme");
            var globex = runner.<ExternalOrder>persistentQueue("orders", "globex");

            // The SAME message id is accepted on both tenants: dedup tables are
            // per-tenant (each in its own schema), so there is no cross-talk.
            assertThat(acme.receive("m-1", new ExternalOrder("acme-1"))).isTrue();
            assertThat(globex.receive("m-1", new ExternalOrder("globex-1"))).isTrue();

            Await.until(() -> received.size() == 2);
            assertThat(received).containsExactlyInAnyOrder(
                new ExternalOrder("acme-1"), new ExternalOrder("globex-1"));

            // Re-sending m-1 to acme is now a duplicate for acme only.
            assertThat(acme.receive("m-1", new ExternalOrder("acme-1"))).isFalse();
            assertThat(globex.receive("m-2", new ExternalOrder("globex-2"))).isTrue();
        }
    }

    @Test
    void unknownQueueThrows() {
        var ds = H2DataSources.inMemory("rpq_" + System.nanoTime());
        try (var runner = ServiceRunner.builder().dataSource(ds).build()) {
            assertThatThrownBy(() -> runner.persistentQueue("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown persistent queue");
        }
    }

    @Test
    void listenerWithoutDeclarationFailsAtBuild() {
        var ds = H2DataSources.inMemory("rpq_" + System.nanoTime());
        assertThatThrownBy(() -> ServiceRunner.builder()
                .dataSource(ds)
                .persistentQueueListener("orphan", (ExternalOrder e) -> { })
                .build())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("orphan");
    }
}
