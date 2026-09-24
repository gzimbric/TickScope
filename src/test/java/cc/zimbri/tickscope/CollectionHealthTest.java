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
import static org.junit.jupiter.api.Assertions.*;

class CollectionHealthTest {
    @Test void incompleteCoverageIsVisibleAndNoDataDoesNotRefreshSuccess() {
        var health = new CollectionHealth(false, true);
        health.players(1, 2);
        var partial = health.snapshot().get("players");
        assertTrue(partial.lastSuccess() > 0);
        assertEquals(1, partial.completed());
        assertEquals(2, partial.expected());
        assertEquals(1, partial.failures());
        health.players(0, 2);
        assertEquals(partial.lastSuccess(), health.snapshot().get("players").lastSuccess());
        assertEquals(2, health.snapshot().get("players").failures());
        health.players(0, 0);
        assertEquals(2, health.snapshot().get("players").failures());
        assertTrue(health.snapshot().get("players").lastSuccess() >= partial.lastSuccess());
        assertFalse(health.snapshot().get("world").enabled());
    }

    @Test void rendersEnabledStagesAndCoverageWithoutBukkitReads() {
        var health = new CollectionHealth(false, true);
        health.players(1, 3);
        String metrics = PrometheusWriter.render(Snapshot.empty("test", "test", "test", "17", "folia"), health);
        assertTrue(metrics.contains("mc_collection_enabled{server=\"test\",stage=\"world\"} 0\n"));
        assertTrue(metrics.contains("mc_folia_player_samples_completed{server=\"test\"} 1\n"));
        assertTrue(metrics.contains("mc_folia_player_samples_expected{server=\"test\"} 3\n"));
    }
}
