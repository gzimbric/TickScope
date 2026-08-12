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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
 * overall deadline enforced by a watchdog, so a stalled client is disconnected rather than
 * parked on a worker, and the endpoint keeps serving. Enforcing this needed no dependency: the
 * exposition format is already written by hand, and so now is the handful of response headers
 * that carry it.
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
    /** Small on purpose: rendering is cheap and a scrape should never need concurrency. */
    private static final int WORKER_THREADS = 4;
    /*
     * Shallow on purpose. Workers plus queue bound how many stalled clients the server will
     * hold at once; beyond that, connections are closed immediately rather than queued ahead
     * of a real scrape. This is bounded degradation, not immunity: a burst of stalled clients
     * can still delay a scrape by up to the read timeout. What it guarantees is that the delay
     * is bounded and self-healing, where previously one client silenced the endpoint outright.
     */
    private static final int QUEUE_DEPTH = 8;

    private final ServerSocket listener;
    private final ThreadPoolExecutor workers;
    private final ScheduledExecutorService deadlines;
    private final Thread acceptor;
    private final String path;
    private final String token;
    private final Supplier<byte[]> body;
    private volatile boolean running = true;

    MetricsHttpServer(String bindAddress, int port, String path, String token,
                      Supplier<byte[]> body) throws IOException {
        this.path = path;
        this.token = token;
        this.body = body;

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
        this.deadlines = Executors.newSingleThreadScheduledExecutor(
                daemonThreads("TickScope-http-deadline"));
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

    @Override
    public void close() {
        running = false;
        closeQuietly(listener);   // unblocks accept()
        workers.shutdownNow();
        deadlines.shutdownNow();
    }

    /**
     * Constant-time bearer check. String.equals returns as soon as two bytes differ, which hands
     * the token back a byte at a time to anyone who can time the response; MessageDigest.isEqual
     * compares the whole array regardless. An empty configured token disables auth entirely.
     *
     * <p>The scheme is matched case-insensitively because RFC 9110 defines it that way, and a
     * scrape configured with a lowercase {@code bearer} type otherwise failed in a way that
     * looks exactly like a mistyped token.
     *
     * <p>This stops unauthenticated reads. It is not confidentiality — the endpoint is plain HTTP
     * and the token crosses the wire in the clear, so put a TLS-terminating proxy in front of it
     * if the scrape path is not one you control.
     */
    static boolean authorised(String token, String header) {
        if (token.isEmpty()) return true;
        if (header == null || header.length() < BEARER.length()
                || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return false;
        }
        return MessageDigest.isEqual(
                header.substring(BEARER.length()).trim().getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
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
            try {
                connection.setSoTimeout(READ_TIMEOUT_MS);
                connection.setTcpNoDelay(true);
                workers.execute(() -> serve(connection));
            } catch (IOException | RuntimeException e) {
                // RejectedExecutionException lands here too: the pool is saturated.
                // Saturated or unusable. Dropping the connection keeps the bound on how much
                // work one client can force the server to hold open.
                closeQuietly(connection);
            }
        }
    }

    private void serve(Socket connection) {
        ScheduledFuture<?> deadline = deadlines.schedule(
                () -> closeQuietly(connection), EXCHANGE_DEADLINE_MS, TimeUnit.MILLISECONDS);
        try {
            InputStream in = new BufferedInputStream(connection.getInputStream(), 4096);
            String requestLine = readLine(in);
            if (requestLine == null) return;

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                respond(connection, 400, "Bad Request", null, null, 0);
                return;
            }

            String authorization = null;
            int seen = 0;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
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
        } catch (IOException | RuntimeException e) {
            // Timed out, disconnected, or malformed. There is no one left to tell.
        } finally {
            deadline.cancel(false);
            closeQuietly(connection);
        }
    }

    private void handle(Socket connection, String method, String target, String authorization)
            throws IOException {
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
        if (!authorised(token, authorization)) {
            // Empty body: a refused scrape learns nothing about the server.
            respond(connection, 401, "Unauthorized", null, null, 0, "WWW-Authenticate: Bearer");
            return;
        }
        byte[] payload = body.get();
        respond(connection, 200, "OK", CONTENT_TYPE, head ? null : payload, payload.length);
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

    /** Reads one CRLF-terminated line, refusing to buffer more than a request line should be. */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder(128);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                int end = line.length();
                if (end > 0 && line.charAt(end - 1) == '\r') {
                    line.setLength(end - 1);
                }
                return line.toString();
            }
            if (line.length() >= MAX_LINE_BYTES) {
                throw new IOException("request line exceeds " + MAX_LINE_BYTES + " bytes");
            }
            line.append((char) c);
        }
        return line.length() == 0 ? null : line.toString();
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
