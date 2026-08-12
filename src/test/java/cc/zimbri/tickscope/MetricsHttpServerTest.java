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

    private MetricsHttpServer server;
    private String token = "";

    @BeforeEach
    void start() throws IOException {
        server = new MetricsHttpServer("127.0.0.1", 0, "/metrics", token,
                () -> PAYLOAD.getBytes(StandardCharsets.UTF_8));
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) server.close();
    }

    private void restartWithToken(String configured) throws IOException {
        server.close();
        token = configured;
        server = new MetricsHttpServer("127.0.0.1", 0, "/metrics", token,
                () -> PAYLOAD.getBytes(StandardCharsets.UTF_8));
        server.start();
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
        MetricsHttpServer replacement = new MetricsHttpServer("127.0.0.1", port, "/metrics", "",
                () -> PAYLOAD.getBytes(StandardCharsets.UTF_8));
        replacement.start();
        try {
            assertEquals(port, replacement.port());
        } finally {
            replacement.close();
        }
        server = null;
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
