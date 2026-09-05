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

import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TickScopeTest {

    @Test
    void emptyTokenDisablesAuthentication() {
        assertTrue(MetricsHttpServer.authorised("", null));
    }

    @Test
    void acceptsOnlyTheCompleteBearerToken() {
        assertTrue(MetricsHttpServer.authorised("secret", "Bearer secret"));
        assertFalse(MetricsHttpServer.authorised("secret", null));
        assertFalse(MetricsHttpServer.authorised("secret", "Basic secret"));
        assertFalse(MetricsHttpServer.authorised("secret", "Bearer secre"));
        assertFalse(MetricsHttpServer.authorised("secret", "Bearer secret-extra"));
    }

    @Test
    void treatsTheAuthenticationSchemeAsCaseInsensitive() {
        // RFC 9110 defines the scheme as case-insensitive, and Prometheus lets the operator
        // write the authorization type themselves.
        assertTrue(MetricsHttpServer.authorised("secret", "bearer secret"));
        assertTrue(MetricsHttpServer.authorised("secret", "BEARER secret"));
        assertFalse(MetricsHttpServer.authorised("secret", "bearer wrong"));
    }

    @Test
    void acceptsLateSamplesButNeverOutOfOrderOnes() {
        MetricsCollector collector = new MetricsCollector("test", false, false,
                new EventCounters(), "test", "test", "17", "folia", null);
        var sample = new MetricsCollector.PlayerSample(1, 12, 12, 1,
                java.util.List.of(), java.util.List.of());
        assertTrue(collector.updateFoliaPlayers(5, sample));
        assertFalse(collector.updateFoliaPlayers(4, MetricsCollector.PlayerSample.EMPTY));
        assertTrue(collector.updateFoliaPlayers(7, sample));
        assertFalse(collector.updateFoliaPlayers(7, MetricsCollector.PlayerSample.EMPTY));
        assertEquals(sample, collector.foliaPlayerSample());
    }
    @Test
    void concurrentFoliaUpdatesKeepTheNewestSampleAndCoverage() throws Exception {
        var collector = new MetricsCollector("test", false, false, new EventCounters(),
                "test", "test", "17", "folia", null);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        var start = new java.util.concurrent.CountDownLatch(1);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        try {
            for (int i = 1; i <= 1000; i++) {
                final int generation = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    collector.updateFoliaPlayers(generation, new MetricsCollector.PlayerSample(
                            generation, generation, generation, generation, java.util.List.of(), java.util.List.of()));
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) future.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(1000, collector.foliaPlayerSample().pingMaxMs());
            assertEquals(1000, collector.health().snapshot().get("players").completed());
            assertFalse(collector.updateFoliaPlayers(999, MetricsCollector.PlayerSample.EMPTY));
        } finally { pool.shutdownNow(); }
    }

}
