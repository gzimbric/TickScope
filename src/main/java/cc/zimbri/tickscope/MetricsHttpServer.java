/*
 * TickScope - a Prometheus exporter for Paper servers.
 * Copyright (C) 2026 Gabe Zimbric
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package cc.zimbri.tickscope;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The metrics endpoint: a deliberately small HTTP/1.1 responder written straight onto a
 * {@link ServerSocket}, serving one path and nothing else.
 *
 * <p>This exists instead of {@code com.sun.net.httpserver} because that implementation parses
 * request headers on its executor threads and applies no request deadline unless
 * {@code sun.net.httpserver.maxReqTime} is set. That property is process-global and read once,
 * when the JDK server implementation first initialises, so a plugin that creates an HTTP server
 * before TickScope makes it impossible to set from here. A client could therefore open a
 * connection, send a partial request line such as {@code GET /metr}, and hold a worker forever —
 * before any bearer token was inspected, so authentication offered no protection. Enough such
 * clients silenced the endpoint completely, and a scraper only saw timeouts.
 *
 * <p>Owning the socket makes the deadline ours. Every connection gets a read timeout and a hard
 * overall deadline enforced by a watchdog from the moment it is accepted, so a stalled client is
 * disconnected rather than parked on a worker or left waiting in the queue, and the endpoint
 * keeps serving. Enforcing this needed no dependency: the exposition format is already written
 * by hand, and so now is the handful of response headers that carry it.
 */
final class MetricsHttpServer implements AutoCloseable {

    static final String CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";
    private static final String BEARER = "Bearer ";

    /** A scrape that has not finished sending its request by now is not a scrape. */
    private static final int READ_TIMEOUT_MS = 3_000;
    /** Enforced even when a worker is blocked writing to a client that never reads. */
    private static final long EXCHANGE_DEADLINE_MS = 15_000L;
    private static final int MAX_LINE_BYTES = 8_192;
    private static final int MAX_HEADER_LINES = 64;
    /** RFC 9112 asks servers to tolerate an empty line ahead of the request line. */
    private static final int MAX_LEADING_BLANK_LINES = 2;
    /** Small on purpose: rendering is cheap and a scrape should never need concurrency. */
    private static final int WORKER_THREADS = 4;
    /*
     * Shallow on purpose. Workers plus queue bound how many stalled clients the server will
     * hold at once; beyond that, connections are closed immediately rather than queued ahead
     * of a real scrape. This is bounded degradation, not immunity: a burst of stalled clients
     * can still delay a scrape by up to the exchange deadline, because a client that keeps
     * trickling bytes resets the read timeout and only the watchdog ends it. What it guarantees
     * is that the delay is bounded and self-healing, where previously one client silenced the
     * endpoint outright.
     */
    private static final int QUEUE_DEPTH = 8;
    /** Leave worker capacity for other scrapers when one address opens slow requests. */
    static final int DEFAULT_CONNECTIONS_PER_ADDRESS = 2;
    /** Workers plus queue: the most connections the server holds at once, from anywhere. */
    static final int MAX_HELD_CONNECTIONS = WORKER_THREADS + QUEUE_DEPTH;
    /** A renderer that keeps failing is reported once a minute, not once per scrape. */
    private static final long FAILURE_REPORT_INTERVAL_NANOS = 60_000_000_000L;

    /** Tunables that are constants in production and shortened by tests. */
    record Options(int maxConnectionsPerAddress, int readTimeoutMs, long exchangeDeadlineMs) {
        static Options withConnectionsPerAddress(int maxConnectionsPerAddress) {
            return new Options(maxConnectionsPerAddress, READ_TIMEOUT_MS, EXCHANGE_DEADLINE_MS);
        }
    }

    private final ServerSocket listener;
    private final ThreadPoolExecutor workers;
    private final ScheduledThreadPoolExecutor deadlines;
    private final Thread acceptor;
    private final String path;
    private final Supplier<byte[]> body;
    private final Consumer<Throwable> failures;
    private final ScrapeStats stats;
    private final int maxConnectionsPerAddress;
    private final int readTimeoutMs;
    private final long exchangeDeadlineMs;
    private volatile boolean running = true;
    /** Immutable and replaced whole, so a reload on a command thread is safe for the workers. */
    private volatile List<byte[]> tokens;
    /** Every accepted socket with the watchdog task that will close it, queued or served. */
    private final Map<Socket, ScheduledFuture<?>> connections = new HashMap<>();
    private final AtomicReference<Long> lastFailureReport = new AtomicReference<>();

    /** Thrown while reading a request whose line is longer than the server will buffer. */
    private static final class RequestTooLarge extends RuntimeException {
        private final int status;
        private final String reason;

        private RequestTooLarge(int status, String reason) {
            super(reason, null, false, false);
            this.status = status;
            this.reason = reason;
        }
    }

    MetricsHttpServer(String bindAddress, int port, String path, Collection<String> tokens,
                      Supplier<byte[]> body, Consumer<Throwable> failures, ScrapeStats stats,
                      Options options) throws IOException {
        this.path = path;
        this.body = body;
        this.failures = failures;
        this.stats = stats;
        this.maxConnectionsPerAddress = options.maxConnectionsPerAddress();
        this.readTimeoutMs = options.readTimeoutMs();
        this.exchangeDeadlineMs = options.exchangeDeadlineMs();
        tokens(tokens);

        ServerSocket candidate = new ServerSocket();
        try {
            candidate.setReuseAddress(true);
            candidate.bind(new InetSocketAddress(bindAddress, port), 0);
        } catch (IOException | RuntimeException e) {
            closeQuietly(candidate);
            throw e;
        }
        this.listener = candidate;

        this.workers = new ThreadPoolExecutor(WORKER_THREADS, WORKER_THREADS, 0L,
                TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(QUEUE_DEPTH),
                daemonThreads("TickScope-http"), new ThreadPoolExecutor.AbortPolicy());
        // Constructed directly rather than through Executors so the cancelled watchdog task of
        // every completed scrape leaves the queue at once instead of lingering, with its closed
        // socket, until its deadline would have fired.
        this.deadlines = new ScheduledThreadPoolExecutor(1, daemonThreads("TickScope-http-deadline"));
        this.deadlines.setRemoveOnCancelPolicy(true);
        this.acceptor = new Thread(this::acceptLoop, "TickScope-http-accept");
        this.acceptor.setDaemon(true);
    }

    void start() {
        acceptor.start();
    }

    /** The bound port, which differs from the requested one only when port 0 was asked for. */
    int port() {
        return listener.getLocalPort();
    }

    /**
     * Replaces the accepted tokens without touching the listener, so a rotation never closes a
     * scrape in flight. An empty collection disables authentication.
     */
    void tokens(Collection<String> accepted) {
        List<byte[]> encoded = new ArrayList<>(accepted.size());
        for (String token : accepted) encoded.add(token.getBytes(StandardCharsets.UTF_8));
        this.tokens = List.copyOf(encoded);
    }

    @Override
    public void close() {
        synchronized (connections) {
            running = false;
            closeQuietly(listener);   // unblocks accept()
            for (Socket connection : connections.keySet()) closeQuietly(connection);
            connections.clear();
        }
        workers.shutdownNow();
        deadlines.shutdownNow();
    }

    /** Convenience for one configured token, or none when it is empty. */
    static boolean authorised(String token, String header) {
        return authorised(token.isEmpty() ? List.of() : List.of(token.getBytes(StandardCharsets.UTF_8)),
                header);
    }

    /**
     * Constant-time bearer check. String.equals returns as soon as two bytes differ, which hands
     * the token back a byte at a time to anyone who can time the response; MessageDigest.isEqual
     * compares the whole array regardless. Every accepted token is compared, with no early exit,
     * so the number of tokens is not observable either. No configured token disables auth.
     *
     * <p>The scheme is matched case-insensitively because RFC 9110 defines it that way, and a
     * scrape configured with a lowercase {@code bearer} type otherwise failed in a way that
     * looks exactly like a mistyped token.
     *
     * <p>This stops unauthenticated reads. It is not confidentiality — the endpoint is plain HTTP
     * and the token crosses the wire in the clear, so put a TLS-terminating proxy in front of it
     * if the scrape path is not one you control.
     */
    static boolean authorised(List<byte[]> tokens, String header) {
        if (tokens.isEmpty()) return true;
        if (header == null || header.length() < BEARER.length()
                || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return false;
        }
        byte[] presented = header.substring(BEARER.length()).trim().getBytes(StandardCharsets.UTF_8);
        boolean accepted = false;
        for (byte[] token : tokens) accepted |= MessageDigest.isEqual(presented, token);
        return accepted;
    }

    private void acceptLoop() {
        while (running) {
            Socket connection;
            try {
                connection = listener.accept();
            } catch (IOException e) {
                if (!running || listener.isClosed()) return;
                // A transient failure such as exhausting file descriptors must not spin the
                // accept loop at full speed.
                sleepQuietly();
                continue;
            }
            synchronized (connections) {
                if (!running) {
                    closeQuietly(connection);
                    return;
                }
                long sameAddress = connections.keySet().stream()
                        .filter(open -> open.getInetAddress().equals(connection.getInetAddress()))
                        .count();
                if (sameAddress >= maxConnectionsPerAddress) {
                    stats.rejectedPerAddress();
                    closeQuietly(connection);
                    continue;
                }
                // The deadline starts here, not when a worker gets to the connection: a socket
                // waiting in the queue behind stalled ones would otherwise have no deadline at
                // all until it was dequeued.
                ScheduledFuture<?> deadline;
                try {
                    deadline = deadlines.schedule(() -> closeQuietly(connection),
                            exchangeDeadlineMs, TimeUnit.MILLISECONDS);
                } catch (RejectedExecutionException closing) {
                    closeQuietly(connection);
                    return;
                }
                connections.put(connection, deadline);
            }
            try {
                connection.setSoTimeout(readTimeoutMs);
                connection.setTcpNoDelay(true);
                workers.execute(() -> serve(connection));
            } catch (RejectedExecutionException saturated) {
                // Workers and queue are all held. Dropping the connection keeps the bound on
                // how much work stalled clients can force the server to hold open.
                stats.rejectedSaturated();
                release(connection);
            } catch (IOException | RuntimeException e) {
                release(connection);
            }
        }
    }

    /**
     * Forgets the connection before closing it. A client sees the close first, and one that
     * reconnects at once must not find its previous socket still counted against the
     * per-address cap.
     */
    private void release(Socket connection) {
        ScheduledFuture<?> deadline;
        synchronized (connections) {
            deadline = connections.remove(connection);
        }
        if (deadline != null) deadline.cancel(false);
        closeQuietly(connection);
    }

    private void serve(Socket connection) {
        try {
            InputStream in = new BufferedInputStream(connection.getInputStream(), 4096);
            String requestLine = readLine(in, 414, "URI Too Long");
            for (int blank = 0; requestLine != null && requestLine.isEmpty(); blank++) {
                if (blank == MAX_LEADING_BLANK_LINES) {
                    respond(connection, 400, "Bad Request", null, null, 0);
                    return;
                }
                requestLine = readLine(in, 414, "URI Too Long");
            }
            if (requestLine == null) return;

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                respond(connection, 400, "Bad Request", null, null, 0);
                return;
            }

            String authorization = null;
            int seen = 0;
            String line;
            while ((line = readLine(in, 431, "Request Header Fields Too Large")) != null
                    && !line.isEmpty()) {
                if (++seen > MAX_HEADER_LINES) {
                    respond(connection, 431, "Request Header Fields Too Large", null, null, 0);
                    return;
                }
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Authorization")) {
                    authorization = line.substring(colon + 1).trim();
                }
            }
            handle(connection, parts[0], parts[1], authorization);
        } catch (RequestTooLarge e) {
            // The limit is deliberate, so say so instead of closing in silence.
            try {
                respond(connection, e.status, e.reason, null, null, 0);
            } catch (IOException | RuntimeException gone) {
                // The client left before hearing why.
            }
        } catch (IOException | RuntimeException e) {
            // Timed out, disconnected, or malformed. There is no one left to tell.
        } finally {
            release(connection);
        }
    }

    private void handle(Socket connection, String method, String target, String authorization)
            throws IOException {
        // Authentication comes first: a refused scrape learns nothing about the server, not
        // even whether the path it guessed is the configured one.
        if (!authorised(tokens, authorization)) {
            respond(connection, 401, "Unauthorized", null, null, 0, "WWW-Authenticate: Bearer");
            return;
        }
        String requestPath;
        try {
            requestPath = new URI(target).getPath();
        } catch (URISyntaxException e) {
            respond(connection, 400, "Bad Request", null, null, 0);
            return;
        }
        if (requestPath == null || !requestPath.equals(path)) {
            respond(connection, 404, "Not Found", null, null, 0);
            return;
        }
        boolean head = method.equals("HEAD");
        if (!head && !method.equals("GET")) {
            respond(connection, 405, "Method Not Allowed", null, null, 0, "Allow: GET, HEAD");
            return;
        }
        byte[] payload;
        try {
            payload = body.get();
            if (payload == null) throw new IllegalStateException("the renderer returned no body");
        } catch (RuntimeException e) {
            // A renderer bug would otherwise look like a dead endpoint: every scrape closed
            // without a byte, and nothing in the log to say why.
            report(e);
            respond(connection, 500, "Internal Server Error", null, null, 0);
            return;
        }
        respond(connection, 200, "OK", CONTENT_TYPE, head ? null : payload, payload.length);
    }

    private void report(Throwable failure) {
        long now = System.nanoTime();
        Long last = lastFailureReport.get();
        if (last != null && now - last < FAILURE_REPORT_INTERVAL_NANOS) return;
        if (lastFailureReport.compareAndSet(last, now)) failures.accept(failure);
    }

    private static void respond(Socket connection, int status, String reason, String contentType,
                                byte[] payload, int contentLength, String... extraHeaders)
            throws IOException {
        StringBuilder head = new StringBuilder(256);
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n");
        if (contentType != null) {
            head.append("Content-Type: ").append(contentType).append("\r\n");
        }
        head.append("Content-Length: ").append(contentLength).append("\r\n");
        for (String extra : extraHeaders) {
            head.append(extra).append("\r\n");
        }
        // No keep-alive: one scrape per connection keeps the state machine trivial, and the
        // deadline above can then simply close the socket.
        head.append("Connection: close\r\n\r\n");

        OutputStream out = connection.getOutputStream();
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (payload != null && payload.length > 0) {
            out.write(payload);
        }
        out.flush();
    }

    /**
     * Reads one CRLF-terminated line, refusing to buffer more than a request line should be.
     * The status names which limit was hit, since a request line and a header line are
     * refused with different codes.
     */
    private static String readLine(InputStream in, int tooLongStatus, String tooLongReason)
            throws IOException {
        // Decoded as UTF-8, which is what every scraper sends. Widening each byte to a char
        // instead made a token with any non-ASCII character fail every scrape, in a way that
        // looked exactly like a mistyped token.
        ByteArrayOutputStream line = new ByteArrayOutputStream(128);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                byte[] bytes = line.toByteArray();
                int end = bytes.length;
                if (end > 0 && bytes[end - 1] == '\r') end--;
                return new String(bytes, 0, end, StandardCharsets.UTF_8);
            }
            if (line.size() >= MAX_LINE_BYTES) {
                throw new RequestTooLarge(tooLongStatus, tooLongReason);
            }
            line.write(c);
        }
        return line.size() == 0 ? null : line.toString(StandardCharsets.UTF_8);
    }

    private static ThreadFactory daemonThreads(String name) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, name + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(100L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException | RuntimeException e) {
            // Closing is best effort.
        }
    }
}
