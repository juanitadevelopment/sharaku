package net.teppan.shazo.http;

import net.teppan.shazo.Gathered;
import net.teppan.shazo.MultipleFoundException;
import net.teppan.shazo.NotFoundException;
import net.teppan.shazo.Page;
import net.teppan.shazo.RawResult;
import net.teppan.shazo.Repository;
import net.teppan.shazo.ShazoException;
import net.teppan.shazo.http.internal.Frames;
import net.teppan.shazo.http.internal.Protocol;
import net.teppan.shazo.http.internal.RowCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A {@link Repository} that calls a remote {@link HttpRepositoryServlet}
 * over HTTP, making remote storage transparent to the caller.
 *
 * <p>Each repository method encodes its argument with the configured
 * {@link Codec}, POSTs it to the endpoint, and decodes the response.
 * The {@link Codec} on the adapter must match the one used by the servlet.
 *
 * <p>{@code HttpRepositoryAdapter} implements {@link AutoCloseable}; close it
 * when no longer needed to release the underlying {@link HttpClient}.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * URI endpoint = URI.create("http://host:8080/api/persons");
 * Codec<Person> codec = Codec.java(Person.class);
 *
 * try (var repo = new HttpRepositoryAdapter<>(endpoint, codec)) {
 *     repo.store(new Person("42", "Alice"));
 *     Optional<Person> found = repo.retrieve(new Person("42", null));
 * }
 * }</pre>
 *
 * @param <T> the domain type managed by the remote repository
 * @see HttpRepositoryServlet
 * @see Codec
 */
public final class HttpRepositoryAdapter<T> implements Repository<T>, AutoCloseable {

    private static final Logger log =
        LoggerFactory.getLogger(HttpRepositoryAdapter.class);

    /**
     * Default per-request timeout applied when none is specified. A request that
     * has not completed within this window fails with {@link ShazoException}
     * rather than blocking the calling thread indefinitely.
     */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final URI        endpoint;
    private final Codec<T>   codec;
    private final HttpClient client;
    private final Duration   requestTimeout;

    /**
     * Constructs an adapter using a new default {@link HttpClient} and the
     * {@link #DEFAULT_REQUEST_TIMEOUT}.
     *
     * @param endpoint the URL of the {@link HttpRepositoryServlet}; never {@code null}
     * @param codec    the codec for domain objects; must match the servlet's codec;
     *                 never {@code null}
     */
    public HttpRepositoryAdapter(URI endpoint, Codec<T> codec) {
        this(endpoint, codec, HttpClient.newHttpClient(), DEFAULT_REQUEST_TIMEOUT);
    }

    /**
     * Constructs an adapter using a new default {@link HttpClient} and a
     * caller-supplied per-request timeout.
     *
     * @param endpoint       the URL of the {@link HttpRepositoryServlet}; never {@code null}
     * @param codec          the codec for domain objects; must match the servlet's
     *                       codec; never {@code null}
     * @param requestTimeout the maximum time to wait for each request to complete;
     *                       must be positive
     */
    public HttpRepositoryAdapter(URI endpoint, Codec<T> codec, Duration requestTimeout) {
        this(endpoint, codec, HttpClient.newHttpClient(), requestTimeout);
    }

    /**
     * Constructs an adapter using a caller-supplied {@link HttpClient} and the
     * {@link #DEFAULT_REQUEST_TIMEOUT}.
     *
     * @param endpoint the URL of the {@link HttpRepositoryServlet}; never {@code null}
     * @param codec    the codec for domain objects; must match the servlet's codec;
     *                 never {@code null}
     * @param client   the {@link HttpClient} to use for all requests; never {@code null}
     */
    public HttpRepositoryAdapter(URI endpoint, Codec<T> codec, HttpClient client) {
        this(endpoint, codec, client, DEFAULT_REQUEST_TIMEOUT);
    }

    /**
     * Constructs an adapter using a caller-supplied {@link HttpClient} and
     * per-request timeout. Use this constructor to configure authentication,
     * proxies, connect timeout (on the client), and the response timeout.
     *
     * <p>Note the distinction: a connect timeout set on the {@link HttpClient}
     * bounds only connection establishment, whereas {@code requestTimeout} is
     * applied per {@link HttpRequest} and bounds the whole request/response
     * exchange — a slow or stuck server hitting it fails the call rather than
     * hanging the calling thread.
     *
     * @param endpoint       the URL of the {@link HttpRepositoryServlet}; never {@code null}
     * @param codec          the codec for domain objects; must match the servlet's
     *                       codec; never {@code null}
     * @param client         the {@link HttpClient} to use for all requests; never {@code null}
     * @param requestTimeout the maximum time to wait for each request to complete;
     *                       must be positive
     */
    public HttpRepositoryAdapter(URI endpoint, Codec<T> codec, HttpClient client,
                                 Duration requestTimeout) {
        this.endpoint       = Objects.requireNonNull(endpoint, "endpoint");
        this.codec          = Objects.requireNonNull(codec,    "codec");
        this.client         = Objects.requireNonNull(client,   "client");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive: " + requestTimeout);
        }
    }

    // ── Repository methods ────────────────────────────────────────────────────

    @Override
    public boolean contains(T query) throws ShazoException {
        log.debug("contains → {}", endpoint);
        byte[] body = buildRequest(Protocol.OP_CONTAINS, query);
        byte[] resp = post(body);
        try {
            var in = new DataInputStream(new ByteArrayInputStream(resp));
            byte status = in.readByte();
            checkException(status, in);
            return in.readByte() != 0;
        } catch (IOException e) {
            throw new ShazoException("Failed to parse contains response", e);
        }
    }

    @Override
    public void store(T entity) throws ShazoException {
        log.debug("store → {}", endpoint);
        byte[] resp = post(buildRequest(Protocol.OP_STORE, entity));
        checkVoidResponse(resp);
    }

    @Override
    public void delete(T entity) throws ShazoException {
        log.debug("delete → {}", endpoint);
        byte[] resp = post(buildRequest(Protocol.OP_DELETE, entity));
        checkVoidResponse(resp);
    }

    @Override
    public Optional<T> retrieve(T query) throws ShazoException {
        log.debug("retrieve → {}", endpoint);
        byte[] body = buildRequest(Protocol.OP_RETRIEVE, query);
        byte[] resp = post(body);
        try {
            var in = new DataInputStream(new ByteArrayInputStream(resp));
            byte status = in.readByte();
            if (status == Protocol.STATUS_NOT_FOUND) return Optional.empty();
            checkException(status, in);
            return Optional.of(codec.decode(in.readAllBytes()));
        } catch (IOException e) {
            throw new ShazoException("Failed to parse retrieve response", e);
        }
    }

    /**
     * Finds the unique entity matching {@code query}. Unlike earlier versions,
     * uniqueness is enforced <em>on the server</em>: the adapter invokes the
     * remote repository's {@code find}, so this method faithfully throws
     * {@link NotFoundException} when nothing matches and
     * {@link MultipleFoundException} when several do — the strict contract holds
     * across the wire.
     */
    @Override
    public T find(T query) throws ShazoException, NotFoundException, MultipleFoundException {
        log.debug("find → {}", endpoint);
        byte[] resp = post(buildRequest(Protocol.OP_FIND, query));
        try {
            var in = new DataInputStream(new ByteArrayInputStream(resp));
            byte status = in.readByte();
            if (status == Protocol.STATUS_NOT_FOUND) {
                throw new NotFoundException(query.toString());
            }
            if (status == Protocol.STATUS_MULTIPLE_FOUND) {
                throw new MultipleFoundException(query.toString(), in.readInt());
            }
            checkException(status, in);
            return codec.decode(in.readAllBytes());
        } catch (IOException e) {
            throw new ShazoException("Failed to parse find response", e);
        }
    }

    /**
     * Catalogs the matching rows as a raw {@link RawResult} table. The server
     * runs {@code catalog} and streams the rows back in a typed, scalar-only
     * cell format (no Java object graph is deserialized), so the same tabular
     * contract that a local {@code JdbcRepository} offers is available remotely.
     */
    @Override
    public RawResult catalog(T query) throws ShazoException {
        log.debug("catalog → {}", endpoint);
        byte[] resp = post(buildRequest(Protocol.OP_CATALOG, query));
        try {
            var in = new DataInputStream(new ByteArrayInputStream(resp));
            checkException(in.readByte(), in);
            return RowCodec.read(in);
        } catch (IOException e) {
            throw new ShazoException("Failed to parse catalog response", e);
        }
    }

    /**
     * Catalogs one page of the matching rows. The window is executed on the
     * server, so only the page's rows cross the wire.
     */
    @Override
    public RawResult catalog(T query, Page page) throws ShazoException {
        Objects.requireNonNull(page, "page");
        log.debug("catalog(page) → {}", endpoint);
        byte[] resp = post(buildPagedRequest(Protocol.OP_CATALOG_PAGED, query, page));
        try {
            var in = new DataInputStream(new ByteArrayInputStream(resp));
            checkException(in.readByte(), in);
            return RowCodec.read(in);
        } catch (IOException e) {
            throw new ShazoException("Failed to parse paged catalog response", e);
        }
    }

    @Override
    public List<T> gather(T query) throws ShazoException {
        log.debug("gather → {}", endpoint);
        byte[] body = buildRequest(Protocol.OP_GATHER, query);
        byte[] resp = post(body);
        try {
            var in = new DataInputStream(new ByteArrayInputStream(resp));
            byte status = in.readByte();
            checkException(status, in);
            int count   = Frames.readBounded(in, "gather count");
            var results = new ArrayList<T>(count);
            for (int i = 0; i < count; i++) {
                byte[] bytes = in.readNBytes(Frames.readBounded(in, "item length"));
                results.add(codec.decode(bytes));
            }
            return List.copyOf(results);
        } catch (IOException e) {
            throw new ShazoException("Failed to parse gather response", e);
        }
    }

    /**
     * Gathers one page of the matching entities. The window (and the has-more
     * probe) is executed on the server, so only the page's entities cross the
     * wire.
     */
    @Override
    public Gathered<T> gather(T query, Page page) throws ShazoException {
        Objects.requireNonNull(page, "page");
        log.debug("gather(page) → {}", endpoint);
        byte[] resp = post(buildPagedRequest(Protocol.OP_GATHER_PAGED, query, page));
        try {
            var in = new DataInputStream(new ByteArrayInputStream(resp));
            byte status = in.readByte();
            checkException(status, in);
            boolean hasMore = in.readByte() != 0;
            int count       = Frames.readBounded(in, "gather count");
            var results = new ArrayList<T>(count);
            for (int i = 0; i < count; i++) {
                byte[] bytes = in.readNBytes(Frames.readBounded(in, "item length"));
                results.add(codec.decode(bytes));
            }
            return new Gathered<>(results, hasMore);
        } catch (IOException e) {
            throw new ShazoException("Failed to parse paged gather response", e);
        }
    }

    // ── AutoCloseable ─────────────────────────────────────────────────────────

    /**
     * Shuts down the underlying {@link HttpClient}.
     * After this call the adapter must not be used.
     */
    @Override
    public void close() {
        client.close();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private byte[] buildRequest(byte op, T entity) throws ShazoException {
        byte[] payload = codec.encode(entity);
        var baos = new ByteArrayOutputStream(1 + payload.length);
        baos.write(op);
        baos.write(payload, 0, payload.length);
        return baos.toByteArray();
    }

    private byte[] buildPagedRequest(byte op, T entity, Page page) throws ShazoException {
        byte[] payload = codec.encode(entity);
        var baos = new ByteArrayOutputStream(9 + payload.length);
        try (var out = new DataOutputStream(baos)) {
            out.writeByte(op);
            out.writeInt(page.offset());
            out.writeInt(page.limit());
            out.write(payload);
        } catch (IOException e) {
            throw new ShazoException("Failed to build paged request", e);
        }
        return baos.toByteArray();
    }

    private byte[] post(byte[] body) throws ShazoException {
        var request = HttpRequest.newBuilder()
            .uri(endpoint)
            .timeout(requestTimeout)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .header("Content-Type", "application/octet-stream")
            .build();
        try {
            var response = client.send(request,
                HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new ShazoException(
                    "HTTP " + response.statusCode() + " from " + endpoint);
            }
            return response.body();
        } catch (HttpTimeoutException e) {
            throw new ShazoException(
                "HTTP request to " + endpoint + " timed out after " + requestTimeout, e);
        } catch (IOException e) {
            throw new ShazoException("HTTP request to " + endpoint + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ShazoException("HTTP request interrupted", e);
        }
    }

    private void checkVoidResponse(byte[] resp) throws ShazoException {
        try {
            var in = new DataInputStream(new ByteArrayInputStream(resp));
            checkException(in.readByte(), in);
        } catch (IOException e) {
            throw new ShazoException("Failed to parse response", e);
        }
    }

    private static void checkException(byte status, DataInputStream in)
            throws ShazoException, IOException {
        if (status == Protocol.STATUS_EXCEPTION) {
            String msg = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            throw new ShazoException(msg);
        }
    }
}
