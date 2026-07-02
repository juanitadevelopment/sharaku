package net.teppan.shazo.http.internal;

import net.teppan.shazo.MultipleFoundException;
import net.teppan.shazo.NotFoundException;
import net.teppan.shazo.Repository;
import net.teppan.shazo.ShazoException;
import net.teppan.shazo.http.Codec;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Server-side protocol handler that reads a request from an {@link InputStream},
 * dispatches to a {@link Repository}, and writes the response to an
 * {@link OutputStream}.
 *
 * <p>This class is independent of the transport layer: {@link
 * net.teppan.shazo.http.HttpRepositoryServlet} delegates to it inside
 * {@code doPost()}, and tests can call {@link #handle} directly without
 * a servlet container.
 *
 * <h2>Wire format</h2>
 * <pre>
 * Request:
 *   byte     operation code (Protocol.OP_*)
 *   [paged ops only: int32 offset, int32 limit]
 *   byte[]   codec.encode(T)   (remainder of stream)
 *
 * Response:
 *   byte     status (Protocol.STATUS_*)
 *   byte[]   payload:
 *     STATUS_OK + OP_CONTAINS      →  1 byte (0=false, 1=true)
 *     STATUS_OK + OP_STORE         →  empty
 *     STATUS_OK + OP_DELETE        →  empty
 *     STATUS_OK + OP_RETRIEVE      →  codec.encode(T)
 *     STATUS_OK + OP_FIND          →  codec.encode(T)
 *     STATUS_OK + OP_GATHER        →  int32 count, then for each item:
 *                                       int32 len, byte[len] codec.encode(item)
 *     STATUS_OK + OP_GATHER_PAGED  →  1 byte hasMore (0/1), then as OP_GATHER
 *     STATUS_OK + OP_CATALOG       →  RowCodec table (typed rows)
 *     STATUS_OK + OP_CATALOG_PAGED →  RowCodec table (the page's rows)
 *     STATUS_NOT_FOUND             →  empty  (retrieve / find)
 *     STATUS_MULTIPLE_FOUND        →  int32 match count  (find)
 *     STATUS_EXCEPTION             →  UTF-8 error message
 * </pre>
 *
 * @param <T> the domain type managed by the wrapped repository
 */
public final class RepositoryRequestHandler<T> {

    private final Repository<T> repository;
    private final Codec<T>      codec;

    /**
     * Constructs a handler wrapping the given repository with the given codec.
     *
     * @param repository the repository to dispatch operations to; never {@code null}
     * @param codec      the codec for (de)serializing domain objects; never {@code null}
     */
    public RepositoryRequestHandler(Repository<T> repository, Codec<T> codec) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.codec      = Objects.requireNonNull(codec, "codec");
    }

    /**
     * Reads one request from {@code rawIn}, dispatches it to the repository,
     * and writes the response to {@code rawOut}.
     *
     * <p>This method never throws — any error is encoded into the response
     * as a {@link Protocol#STATUS_EXCEPTION} frame.
     *
     * @param rawIn  the request input stream; never {@code null}
     * @param rawOut the response output stream; never {@code null}
     * @throws IOException if writing the response fails at the I/O level
     */
    public void handle(InputStream rawIn, OutputStream rawOut) throws IOException {
        var out = new DataOutputStream(rawOut);

        byte   op;
        byte[] payload;
        net.teppan.shazo.Page page = null;
        try {
            var in = new DataInputStream(rawIn);
            op = in.readByte();
            if (op == Protocol.OP_GATHER_PAGED || op == Protocol.OP_CATALOG_PAGED) {
                page = new net.teppan.shazo.Page(in.readInt(), in.readInt());
            }
            payload = in.readAllBytes();
        } catch (IOException | IllegalArgumentException e) {
            writeException(out, "Failed to read request: " + e.getMessage());
            return;
        }

        try {
            switch (op) {
                case Protocol.OP_CONTAINS -> {
                    boolean result = repository.contains(codec.decode(payload));
                    out.writeByte(Protocol.STATUS_OK);
                    out.writeByte(result ? 1 : 0);
                }
                case Protocol.OP_STORE -> {
                    repository.store(codec.decode(payload));
                    out.writeByte(Protocol.STATUS_OK);
                }
                case Protocol.OP_DELETE -> {
                    repository.delete(codec.decode(payload));
                    out.writeByte(Protocol.STATUS_OK);
                }
                case Protocol.OP_RETRIEVE -> {
                    var result = repository.retrieve(codec.decode(payload));
                    if (result.isPresent()) {
                        out.writeByte(Protocol.STATUS_OK);
                        out.write(codec.encode(result.get()));
                    } else {
                        out.writeByte(Protocol.STATUS_NOT_FOUND);
                    }
                }
                case Protocol.OP_FIND -> {
                    try {
                        var result = repository.find(codec.decode(payload));
                        out.writeByte(Protocol.STATUS_OK);
                        out.write(codec.encode(result));
                    } catch (NotFoundException e) {
                        out.writeByte(Protocol.STATUS_NOT_FOUND);
                    } catch (MultipleFoundException e) {
                        out.writeByte(Protocol.STATUS_MULTIPLE_FOUND);
                        out.writeInt(e.count());
                    }
                }
                case Protocol.OP_GATHER -> {
                    var results = repository.gather(codec.decode(payload));
                    out.writeByte(Protocol.STATUS_OK);
                    out.writeInt(results.size());
                    for (var item : results) {
                        byte[] encoded = codec.encode(item);
                        out.writeInt(encoded.length);
                        out.write(encoded);
                    }
                }
                case Protocol.OP_GATHER_PAGED -> {
                    var slice = repository.gather(codec.decode(payload), page);
                    out.writeByte(Protocol.STATUS_OK);
                    out.writeByte(slice.hasMore() ? 1 : 0);
                    out.writeInt(slice.size());
                    for (var item : slice) {
                        byte[] encoded = codec.encode(item);
                        out.writeInt(encoded.length);
                        out.write(encoded);
                    }
                }
                case Protocol.OP_CATALOG -> {
                    var table = repository.catalog(codec.decode(payload));
                    out.writeByte(Protocol.STATUS_OK);
                    RowCodec.write(out, table);
                }
                case Protocol.OP_CATALOG_PAGED -> {
                    var table = repository.catalog(codec.decode(payload), page);
                    out.writeByte(Protocol.STATUS_OK);
                    RowCodec.write(out, table);
                }
                default -> writeException(out, "Unknown operation code: " + op);
            }
        } catch (ShazoException e) {
            writeException(out, e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    private static void writeException(DataOutputStream out, String message) throws IOException {
        out.writeByte(Protocol.STATUS_EXCEPTION);
        out.write(message.getBytes(StandardCharsets.UTF_8));
    }
}
