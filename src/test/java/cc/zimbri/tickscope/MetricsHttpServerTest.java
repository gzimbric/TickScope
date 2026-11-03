/*
 * TickScope - a Prometheus exporter for Paper servers.
 * Copyright (C) 2026 Gabe Zimbric
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package cc.zimbri.tickscope;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsHttpServerTest {

    private static final String PAYLOAD = "mc_up 1\n";
    private static final int READ_TIMEOUT_MS = 3_000;
    private static final long DEADLINE_MS = 15_000L;

    private MetricsHttpServer server;
    private String token = "";
    private final List<Throwable> reported = new ArrayList<>();
    private final ScrapeStats stats = new ScrapeStats();

    @BeforeEach
    void start() throws IOException {
        server = server(token, () -> PAYLOAD.getBytes(StandardCharsets.UTF_8));
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) server.close();
    }

    private MetricsHttpServer server(String token, java.util.function.Supplier<byte[]> body)
            throws IOException {
        return server(token, body, 2, READ_TIMEOUT_MS, DEADLINE_MS);
    }

    private MetricsHttpServer server(String token, java.util.function.Supplier<byte[]> body,
                                     int perAddress, int readTimeoutMs, long deadlineMs)
            throws IOException {
        return new MetricsHttpServer("127.0.0.1", 0, "/metrics",
                token.isEmpty() ? List.of() : List.of(token), body, reported::add, stats,
                new MetricsHttpServer.Options(perAddress, readTimeoutMs, deadlineMs));
    }

    private void restart(MetricsHttpServer replacement) {
        server.close();
        server = replacement;
        server.start();
    }

    private void restartWithToken(String configured) throws IOException {
        token = configured;
        restart(server(token, () -> PAYLOAD.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void servesTheExpositionOnTheConfiguredPath() throws IOException {
        Response response = request("GET /metrics HTTP/1.1", null);

        assertEquals(200, response.status);
        assertTrue(response.header("content-type").startsWith("text/plain; version=0.0.4"));
        assertEquals(PAYLOAD, response.body);
        assertEquals(String.valueOf(PAYLOAD.length()), response.header("content-length"));
    }

    @Test
    void headReportsTheBodyLengthWithoutSendingIt() throws IOException {
        Response response = request("HEAD /metrics HTTP/1.1", null);

        assertEquals(200, response.status);
        assertEquals(String.valueOf(PAYLOAD.length()), response.header("content-length"));
        assertEquals("", response.body);
    }

    @Test
    void refusesOtherPathsAndMethods() throws IOException {
        assertEquals(404, request("GET /elsewhere HTTP/1.1", null).status);

        Response post = request("POST /metrics HTTP/1.1", null);
        assertEquals(405, post.status);
        assertEquals("GET, HEAD", post.header("allow"));
    }

    @Test
    void ignoresAQueryStringOnTheMetricsPath() throws IOException {
        assertEquals(200, request("GET /metrics?debug=1 HTTP/1.1", null).status);
    }

    @Test
    void requiresTheConfiguredBearerToken() throws IOException {
        restartWithToken("s3cret");

        Response anonymous = request("GET /metrics HTTP/1.1", null);
        assertEquals(401, anonymous.status);
        assertEquals("Bearer", anonymous.header("www-authenticate"));
        assertEquals("", anonymous.body, "a refused scrape should learn nothing");

        assertEquals(401, request("GET /metrics HTTP/1.1", "Bearer wrong").status);
        assertEquals(200, request("GET /metrics HTTP/1.1", "Bearer s3cret").status);
        // RFC 9110 makes the scheme case-insensitive.
        assertEquals(200, request("GET /metrics HTTP/1.1", "bearer s3cret").status);
    }

    /**
     * The regression probe for the defect this server exists to fix: one client that connects
     * and never finishes its request line used to occupy the only worker forever, silencing
     * the endpoint for everyone, without ever presenting a token.
     */
    @Test
    void aStalledClientCannotSilenceTheEndpoint() throws IOException {
        try (Socket stalled = new Socket("127.0.0.1", server.port())) {
            stalled.getOutputStream().write("GET /metr".getBytes(StandardCharsets.US_ASCII));
            stalled.getOutputStream().flush();

            for (int attempt = 0; attempt < 3; attempt++) {
                assertEquals(200, request("GET /metrics HTTP/1.1", null).status,
                        "a scrape must still succeed while a partial request is held open");
            }
        }
    }

    @Test
    void oneAddressCannotOccupyEveryWorker() throws IOException {
        try (Socket first = new Socket("127.0.0.1", server.port());
             Socket second = new Socket("127.0.0.1", server.port());
             Socket third = new Socket("127.0.0.1", server.port())) {
            first.getOutputStream().write("GET /metr".getBytes(StandardCharsets.US_ASCII));
            second.getOutputStream().write("GET /metr".getBytes(StandardCharsets.US_ASCII));
            third.setSoTimeout(2000);
            assertEquals(-1, third.getInputStream().read());
        }
    }

    /**
     * Enough stalled clients can still occupy every worker, so the guarantee is that the
     * endpoint recovers on its own rather than staying down until the clients disconnect.
     */
    @Test
    void recoversOnItsOwnFromAFloodOfStalledClients() throws IOException {
        List<Socket> stalled = new ArrayList<>();
        try {
            for (int i = 0; i < 40; i++) {
                Socket socket = new Socket("127.0.0.1", server.port());
                socket.getOutputStream().write("GET /me".getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                stalled.add(socket);
            }

            boolean served = false;
            long giveUpAt = System.nanoTime() + 20_000_000_000L;
            while (!served && System.nanoTime() < giveUpAt) {
                try {
                    served = request("GET /metrics HTTP/1.1", null).status == 200;
                } catch (IOException retry) {
                    // Saturated: the server drops connections rather than queueing them
                    // behind stalled ones. Wait for a worker to time out and try again.
                    served = false;
                }
                if (!served) {
                    try {
                        Thread.sleep(250L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            assertTrue(served, "the endpoint must recover while the stalled clients are still connected");
        } finally {
            for (Socket socket : stalled) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
    }

    @Test
    void closingTheServerReleasesThePort() throws IOException {
        int port = server.port();
        server.close();

        // Rebinding immediately proves the listener is really gone, which is what a reload
        // that changes only the token depends on.
        MetricsHttpServer replacement = new MetricsHttpServer("127.0.0.1", port, "/metrics", List.of(),
                () -> PAYLOAD.getBytes(StandardCharsets.UTF_8), reported::add, stats,
                MetricsHttpServer.Options.withConnectionsPerAddress(2));
        replacement.start();
        try {
            assertEquals(port, replacement.port());
        } finally {
            replacement.close();
        }
        server = null;
    }


    @Test
    void shutdownClosesActiveAndQueuedSockets() throws Exception {
        restart(server("", () -> PAYLOAD.getBytes(StandardCharsets.UTF_8), 5, READ_TIMEOUT_MS, DEADLINE_MS));
        List<Socket> clients = new ArrayList<>();
        try {
            for (int i = 0; i < 5; i++) clients.add(new Socket("127.0.0.1", server.port()));
            var field = MetricsHttpServer.class.getDeclaredField("workers");
            field.setAccessible(true);
            var workers = (java.util.concurrent.ThreadPoolExecutor) field.get(server);
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (workers.getQueue().size() != 1 && System.nanoTime() < deadline) Thread.sleep(5);
            assertEquals(1, workers.getQueue().size());
            Object queued = workers.getQueue().peek();
            server.close();
            for (Socket client : clients) {
                client.setSoTimeout(1000);
                try { assertEquals(-1, client.getInputStream().read()); }
                catch (java.net.SocketException closed) { /* A reset is also a closed connection. */ }
            }
            java.lang.ref.Reference.reachabilityFence(queued);
            assertTrue(workers.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS));
        } finally { for (Socket client : clients) client.close(); }
    }


    @Test
    void shutdownUnblocksAWriterWhoseClientNeverReads() throws Exception {
        var rendering = new java.util.concurrent.CountDownLatch(1);
        restart(server("", () -> {
            rendering.countDown();
            return new byte[16 * 1024 * 1024];
        }));
        try (Socket client = new Socket()) {
            client.setReceiveBufferSize(1024);
            client.connect(new java.net.InetSocketAddress("127.0.0.1", server.port()));
            client.getOutputStream().write("GET /metrics HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            assertTrue(rendering.await(2, java.util.concurrent.TimeUnit.SECONDS));
            var field = MetricsHttpServer.class.getDeclaredField("workers");
            field.setAccessible(true);
            var workers = (java.util.concurrent.ThreadPoolExecutor) field.get(server);
            server.close();
            assertTrue(workers.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS),
                    "Closing must release writers without waiting for the client or watchdog");
        }
    }

    @Test
    void aRendererFailureIsReportedAndAnsweredWith500() throws IOException {
        var broken = new java.util.concurrent.atomic.AtomicBoolean(true);
        restart(server("", () -> {
            if (broken.get()) throw new IllegalStateException("renderer bug");
            return PAYLOAD.getBytes(StandardCharsets.UTF_8);
        }));

        Response first = request("GET /metrics HTTP/1.1", null);
        assertEquals(500, first.status, "a scrape must not just see the connection closed");
        assertEquals("0", first.header("content-length"));
        assertEquals(500, request("GET /metrics HTTP/1.1", null).status);
        assertEquals(1, reported.size(), "a persistent fault is reported once, not once per scrape");
        assertEquals("renderer bug", reported.get(0).getMessage());

        broken.set(false);
        assertEquals(200, request("GET /metrics HTTP/1.1", null).status, "the workers survive it");
    }

    @Test
    void aConfiguredTokenIsCheckedBeforeThePathAndMethod() throws IOException {
        restartWithToken("s3cret");

        // Without the token, nothing distinguishes the configured path from any other.
        assertEquals(401, request("GET /elsewhere HTTP/1.1", null).status);
        assertEquals(401, request("POST /metrics HTTP/1.1", null).status);
        assertEquals(404, request("GET /elsewhere HTTP/1.1", "Bearer s3cret").status);
        assertEquals(405, request("POST /metrics HTTP/1.1", "Bearer s3cret").status);
    }

    @Test
    void overlongLinesAreRefusedWithAStatusInsteadOfSilence() throws IOException {
        String longTarget = "/metrics?" + "x".repeat(9_000);
        Response line = raw("GET " + longTarget + " HTTP/1.1\r\nHost: x\r\n\r\n");
        assertEquals(414, line.status);

        Response header = raw("GET /metrics HTTP/1.1\r\nCookie: " + "y".repeat(9_000) + "\r\n\r\n");
        assertEquals(431, header.status);
    }

    @Test
    void blankLinesAheadOfTheRequestLineAreIgnored() throws IOException {
        // RFC 9112 asks servers to tolerate this; a client's stray CRLF is not a bad request.
        assertEquals(200, raw("\r\nGET /metrics HTTP/1.1\r\nHost: x\r\n\r\n").status);
        assertEquals(400, raw("\r\n\r\n\r\nGET /metrics HTTP/1.1\r\nHost: x\r\n\r\n").status,
                "but not an unbounded run of them");
    }

    /**
     * The deadline has to start at accept time: a connection waiting in the queue behind
     * stalled ones is never read, so neither the read timeout nor a deadline scheduled by the
     * worker that eventually dequeues it could end it.
     */
    @Test
    void aQueuedConnectionIsClosedByTheDeadlineBeforeAnyWorkerReachesIt() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        restart(server("", () -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return PAYLOAD.getBytes(StandardCharsets.UTF_8);
        }, 16, READ_TIMEOUT_MS, 400L));
        List<Socket> occupying = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                Socket worker = new Socket("127.0.0.1", server.port());
                worker.getOutputStream().write("GET /metrics HTTP/1.1\r\nHost: x\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII));
                occupying.add(worker);
            }
            try (Socket queued = new Socket("127.0.0.1", server.port())) {
                queued.setSoTimeout(5_000);
                queued.getOutputStream().write("GET /metrics HTTP/1.1\r\nHost: x\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII));
                long started = System.nanoTime();
                try {
                    assertEquals(-1, queued.getInputStream().read());
                } catch (java.net.SocketException reset) {
                    // A reset is also a closed connection.
                }
                long heldMs = (System.nanoTime() - started) / 1_000_000L;
                assertTrue(heldMs < 3_000, "closed by the 400 ms deadline, not by anything slower: " + heldMs);
            }
        } finally {
            release.countDown();
            for (Socket socket : occupying) socket.close();
        }
    }

    /** A client that keeps trickling bytes never trips the read timeout; the deadline ends it. */
    @Test
    void aDripFeedingClientIsDisconnectedAtTheDeadline() throws Exception {
        restart(server("", () -> PAYLOAD.getBytes(StandardCharsets.UTF_8), 2, 2_000, 500L));
        try (Socket drip = new Socket("127.0.0.1", server.port())) {
            drip.setSoTimeout(5_000);
            OutputStream out = drip.getOutputStream();
            long started = System.nanoTime();
            boolean closed = false;
            for (int i = 0; i < 40 && !closed; i++) {
                try {
                    out.write('G');
                    out.flush();
                    Thread.sleep(100L);
                } catch (IOException gone) {
                    closed = true;
                }
            }
            long heldMs = (System.nanoTime() - started) / 1_000_000L;
            assertTrue(closed || drip.getInputStream().read() == -1, "the deadline closed the socket");
            assertTrue(heldMs < 2_000, "well before the 2 s read timeout could have: " + heldMs);
        }
    }

    @Test
    void completedScrapesDoNotRetainTheirWatchdogTasks() throws Exception {
        for (int i = 0; i < 20; i++) assertEquals(200, request("GET /metrics HTTP/1.1", null).status);
        var field = MetricsHttpServer.class.getDeclaredField("deadlines");
        field.setAccessible(true);
        var deadlines = (java.util.concurrent.ScheduledThreadPoolExecutor) field.get(server);
        assertTrue(deadlines.getRemoveOnCancelPolicy());
        long giveUpAt = System.nanoTime() + 2_000_000_000L;
        while (deadlines.getQueue().size() != 0 && System.nanoTime() < giveUpAt) Thread.sleep(5);
        assertEquals(0, deadlines.getQueue().size(), "cancelled deadlines leave the queue at once");
    }

    @Test
    void acceptsAnyConfiguredTokenAndSwapsThemWithoutRestarting() throws IOException {
        restart(new MetricsHttpServer("127.0.0.1", 0, "/metrics", List.of("old", "new"),
                () -> PAYLOAD.getBytes(StandardCharsets.UTF_8), reported::add, stats,
                MetricsHttpServer.Options.withConnectionsPerAddress(2)));
        int port = server.port();
        assertEquals(200, request("GET /metrics HTTP/1.1", "Bearer old").status);
        assertEquals(200, request("GET /metrics HTTP/1.1", "Bearer new").status);
        assertEquals(401, request("GET /metrics HTTP/1.1", "Bearer other").status);

        // Rotation: the old token is dropped in place, the listener never closes.
        server.tokens(List.of("new"));
        assertEquals(port, server.port());
        assertEquals(401, request("GET /metrics HTTP/1.1", "Bearer old").status);
        assertEquals(200, request("GET /metrics HTTP/1.1", "Bearer new").status);
        server.tokens(List.of());
        assertEquals(200, request("GET /metrics HTTP/1.1", null).status, "no token means no auth");
    }

    @Test
    void connectionsTurnedAwayAreCountedByReason() throws Exception {
        try (Socket first = new Socket("127.0.0.1", server.port());
             Socket second = new Socket("127.0.0.1", server.port());
             Socket third = new Socket("127.0.0.1", server.port())) {
            first.getOutputStream().write("GET /metr".getBytes(StandardCharsets.US_ASCII));
            second.getOutputStream().write("GET /metr".getBytes(StandardCharsets.US_ASCII));
            third.setSoTimeout(2000);
            assertEquals(-1, third.getInputStream().read());
        }
        assertEquals(1, stats.perAddress());
        assertEquals(0, stats.saturated());

        // With the cap lifted, workers plus queue fill up and the next connection is dropped.
        restart(server("", () -> PAYLOAD.getBytes(StandardCharsets.UTF_8), 100, 500, DEADLINE_MS));
        List<Socket> stalled = new ArrayList<>();
        try {
            for (int i = 0; i < MetricsHttpServer.MAX_HELD_CONNECTIONS; i++) {
                Socket socket = new Socket("127.0.0.1", server.port());
                socket.getOutputStream().write("GET /metr".getBytes(StandardCharsets.US_ASCII));
                stalled.add(socket);
            }
            long giveUpAt = System.nanoTime() + 5_000_000_000L;
            while (stats.saturated() == 0 && System.nanoTime() < giveUpAt) {
                try (Socket extra = new Socket("127.0.0.1", server.port())) {
                    extra.setSoTimeout(200);
                    try {
                        extra.getInputStream().read();
                    } catch (IOException dropped) {
                        // Either dropped at once or still queued; the counter decides.
                    }
                }
            }
            assertTrue(stats.saturated() >= 1, "a saturated pool is counted, not silent");
        } finally {
            for (Socket socket : stalled) socket.close();
        }
    }

    @Test
    void everyResponseClosesTheConnectionAndMalformedRequestsGet400() throws IOException {
        assertEquals("close", request("GET /metrics HTTP/1.1", null).header("connection"));
        assertEquals("close", request("GET /elsewhere HTTP/1.1", null).header("connection"));
        assertEquals(400, raw("GET\r\nHost: x\r\n\r\n").status, "a request line without a target");
        assertEquals(400, raw("GET /%zz HTTP/1.1\r\nHost: x\r\n\r\n").status, "a target that is not a URI");
        assertEquals(200, raw("GET http://127.0.0.1:9101/metrics HTTP/1.1\r\nHost: x\r\n\r\n").status,
                "the absolute form a proxy sends");
    }

    @Test
    void aTokenWithNonAsciiCharactersAuthenticatesWhenSentAsUtf8() throws IOException {
        restartWithToken("päss-Zürich");

        // Prometheus, curl and every other scraper send the header as UTF-8 bytes.
        assertEquals(200, raw(("GET /metrics HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer päss-Zürich\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8)).status);
        assertEquals(401, raw(("GET /metrics HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer pass-Zurich\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8)).status);
    }

    private Response raw(String request) throws IOException {
        return raw(request.getBytes(StandardCharsets.ISO_8859_1));
    }

    /** Sends raw bytes and reads whatever comes back; status -1 means the server just closed. */
    private Response raw(byte[] request) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(request);
            socket.getOutputStream().flush();
            try {
                return Response.read(socket);
            } catch (IOException closedWithoutResponse) {
                return new Response(-1, List.of(), "");
            }
        }
    }

    private Response request(String requestLine, String authorization) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(10_000);
            StringBuilder out = new StringBuilder();
            out.append(requestLine).append("\r\nHost: 127.0.0.1\r\n");
            if (authorization != null) {
                out.append("Authorization: ").append(authorization).append("\r\n");
            }
            out.append("\r\n");
            OutputStream stream = socket.getOutputStream();
            stream.write(out.toString().getBytes(StandardCharsets.ISO_8859_1));
            stream.flush();
            return Response.read(socket);
        }
    }

    private record Response(int status, List<String> headers, String body) {
        static Response read(Socket socket) throws IOException {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String statusLine = reader.readLine();
            if (statusLine == null || statusLine.isEmpty()) {
                throw new IOException("connection closed without a response");
            }

            List<String> headers = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                headers.add(line);
            }
            StringBuilder body = new StringBuilder();
            int c;
            while ((c = reader.read()) != -1) {
                body.append((char) c);
            }
            return new Response(Integer.parseInt(statusLine.split(" ")[1]), headers, body.toString());
        }

        String header(String name) {
            for (String header : headers) {
                int colon = header.indexOf(':');
                if (colon > 0 && header.substring(0, colon).trim().equalsIgnoreCase(name)) {
                    return header.substring(colon + 1).trim();
                }
            }
            return "";
        }
    }
}
