package net.teppan.shazo.http;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.teppan.shazo.Repository;
import net.teppan.shazo.http.internal.RepositoryRequestHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;

/**
 * Jakarta Servlet that exposes a {@link Repository} over HTTP for remote
 * access by {@link HttpRepositoryAdapter}.
 *
 * <p>Each POST request carries one encoded repository operation (see
 * {@link net.teppan.shazo.http.internal.Protocol} for the wire format).
 * The servlet dispatches to the wrapped repository and writes the result
 * back as {@code application/octet-stream}.
 *
 * <h2>Deployment</h2>
 * <p>Register the servlet programmatically via
 * {@code ServletContext.addServlet()} — the constructor requires arguments
 * so traditional no-arg {@code web.xml} deployment is not supported.
 *
 * <pre>{@code
 * // In a ServletContextListener or embedded server setup:
 * Repository<Person> repo  = new JdbcRepository<>(dataSource, personDescriber);
 * Codec<Person>      codec = Codec.java(Person.class);
 * var servlet = new HttpRepositoryServlet<>(repo, codec);
 *
 * ServletRegistration.Dynamic reg =
 *     servletContext.addServlet("personRepo", servlet);
 * reg.addMapping("/api/persons");
 * reg.setLoadOnStartup(1);
 * }</pre>
 *
 * <h2>Client side</h2>
 * <p>Use {@link HttpRepositoryAdapter} with the same {@link Codec} to call
 * the servlet transparently as a {@link Repository}.
 *
 * @param <T> the domain type managed by the wrapped repository
 * @see HttpRepositoryAdapter
 * @see Codec
 */
public class HttpRepositoryServlet<T> extends HttpServlet {

    private static final Logger log =
        LoggerFactory.getLogger(HttpRepositoryServlet.class);

    /** Shared handler that decodes requests and dispatches to the repository. */
    private final RepositoryRequestHandler<T> handler;

    /**
     * Constructs an {@code HttpRepositoryServlet} that delegates repository
     * operations to {@code repository}, encoding objects with {@code codec}.
     *
     * @param repository the backing repository; never {@code null}
     * @param codec      the codec for domain objects; never {@code null}
     */
    public HttpRepositoryServlet(Repository<T> repository, Codec<T> codec) {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(codec, "codec");
        this.handler = new RepositoryRequestHandler<>(repository, codec);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        log.debug("HttpRepositoryServlet initialized");
    }

    /**
     * Reads a repository operation from the request body, dispatches it to
     * the wrapped repository, and writes the encoded result to the response.
     *
     * @param req  the HTTP request carrying the encoded operation
     * @param resp the HTTP response to write the result into
     * @throws IOException if reading the request or writing the response fails
     */
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        var baos = new ByteArrayOutputStream();
        handler.handle(req.getInputStream(), baos);

        byte[] response = baos.toByteArray();
        resp.setContentType("application/octet-stream");
        resp.setContentLength(response.length);
        resp.getOutputStream().write(response);
    }
}
