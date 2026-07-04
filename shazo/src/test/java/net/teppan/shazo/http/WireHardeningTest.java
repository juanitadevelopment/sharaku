package net.teppan.shazo.http;

import com.sun.net.httpserver.HttpServer;
import net.teppan.shazo.ShazoException;
import net.teppan.shazo.http.internal.Protocol;
import net.teppan.shazo.http.internal.RowCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Hardening tests for the HTTP client path: a per-request timeout so a stuck
 * server cannot hang the caller, and bounds checks so a malformed or hostile
 * response cannot drive an unbounded allocation or leak an unchecked exception
 * out of the {@code ShazoException} contract.
 */
class WireHardeningTest {

    record Thing(String id) implements Serializable {}

    private HttpServer server;
    private HttpRepositoryAdapter<Thing> adapter;

    /**
     * Starts a server whose single handler runs {@code responder} to produce the
     * raw response bytes, ignoring the request, and wires an adapter to it.
     */
    private void serve(RawResponder responder, Duration requestTimeout) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/repo", exchange -> {
            exchange.getRequestBody().readAllBytes();  // drain request
            byte[] body;
            try {
                body = responder.respond();
            } catch (Exception e) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        int port = server.getAddress().getPort();
        adapter = new HttpRepositoryAdapter<>(
            URI.create("http://localhost:" + port + "/repo"),
            Codec.java(Thing.class), requestTimeout);
    }

    @AfterEach
    void tearDown() {
        if (adapter != null) adapter.close();
        if (server  != null) server.stop(0);
    }

    @FunctionalInterface
    interface RawResponder { byte[] respond() throws Exception; }

    // ── request timeout ─────────────────────────────────────────────────────────

    @Test
    void slowServerTripsRequestTimeoutInsteadOfHanging() throws IOException {
        serve(() -> {
            Thread.sleep(5_000);           // far longer than the request timeout
            return new byte[]{Protocol.STATUS_OK};
        }, Duration.ofMillis(250));

        long start = System.nanoTime();
        assertThatThrownBy(() -> adapter.contains(new Thing("1")))
            .isInstanceOf(ShazoException.class)
            .hasMessageContaining("timed out");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).isLessThan(4_000);   // returned via timeout, not the 5s sleep
    }

    @Test
    void rejectsNonPositiveRequestTimeout() {
        assertThatThrownBy(() -> new HttpRepositoryAdapter<>(
                URI.create("http://localhost:1/repo"),
                Codec.java(Thing.class), Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ── malformed frames (adapter) ───────────────────────────────────────────────

    @Test
    void gatherWithOversizedCountFailsCleanlyNotWithOom() throws IOException {
        // STATUS_OK then a count of Integer.MAX_VALUE, with no item bytes to back it.
        serve(() -> {
            var baos = new ByteArrayOutputStream();
            var out  = new DataOutputStream(baos);
            out.writeByte(Protocol.STATUS_OK);
            out.writeInt(Integer.MAX_VALUE);
            return baos.toByteArray();
        }, Duration.ofSeconds(5));

        assertThatThrownBy(() -> adapter.gather(new Thing("1")))
            .isInstanceOf(ShazoException.class);
    }

    @Test
    void gatherWithNegativeItemLengthFailsCleanly() throws IOException {
        serve(() -> {
            var baos = new ByteArrayOutputStream();
            var out  = new DataOutputStream(baos);
            out.writeByte(Protocol.STATUS_OK);
            out.writeInt(1);      // claims one item…
            out.writeInt(-1);     // …with a negative length
            return baos.toByteArray();
        }, Duration.ofSeconds(5));

        assertThatThrownBy(() -> adapter.gather(new Thing("1")))
            .isInstanceOf(ShazoException.class);
    }

    // ── malformed frames (RowCodec, unit) ────────────────────────────────────────

    @Test
    void rowCodecRejectsOversizedColumnCount() throws IOException {
        var baos = new ByteArrayOutputStream();
        new DataOutputStream(baos).writeInt(Integer.MAX_VALUE);   // columnCount, nothing after
        var in = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
        assertThatThrownBy(() -> RowCodec.read(in)).isInstanceOf(IOException.class);
    }

    @Test
    void rowCodecRejectsNegativeColumnCount() throws IOException {
        var baos = new ByteArrayOutputStream();
        new DataOutputStream(baos).writeInt(-1);
        var in = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
        assertThatThrownBy(() -> RowCodec.read(in))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("negative");
    }

    @Test
    void catalogWithOversizedColumnCountFailsCleanly() throws IOException {
        serve(() -> {
            var baos = new ByteArrayOutputStream();
            var out  = new DataOutputStream(baos);
            out.writeByte(Protocol.STATUS_OK);
            out.writeInt(Integer.MAX_VALUE);   // columnCount
            return baos.toByteArray();
        }, Duration.ofSeconds(5));

        assertThatThrownBy(() -> adapter.catalog(new Thing("1")))
            .isInstanceOf(ShazoException.class);
    }
}
