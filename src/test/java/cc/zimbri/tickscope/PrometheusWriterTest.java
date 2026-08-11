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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrometheusWriterTest {

    @Test
    void rendersBuildInformationAndEscapesLabelValues() {
        Snapshot snapshot = Snapshot.empty("srv\"\\\n", "1.2.1", "Paper \"test\"", "17");

        String rendered = PrometheusWriter.render(snapshot);

        assertTrue(rendered.contains("# TYPE mc_tickscope_info gauge\n"));
        assertTrue(rendered.contains(
                "mc_tickscope_info{server=\"srv\\\"\\\\\\n\",version=\"1.2.1\","
                        + "paper=\"Paper \\\"test\\\"\",java=\"17\",platform=\"paper\"} 1\n"));
        assertTrue(rendered.contains("# TYPE mc_entity_collection_duration_seconds gauge\n"));
        assertTrue(rendered.contains("# TYPE mc_tick_duration_seconds gauge\n"));
        assertTrue(rendered.contains("# TYPE mc_player_ping_seconds gauge\n"));
        assertTrue(rendered.contains("# TYPE mc_jvm_memory_used_bytes gauge\n"));
        assertTrue(!rendered.contains("mc_mspt_ms"));
        assertTrue(!rendered.contains("mc_player_ping_avg_ms"));
        assertTrue(!rendered.contains("mc_jvm_memory_bytes_used"));
    }

    @Test
    void snapshotDefensivelyCopiesTickArrays() {
        Snapshot snapshot = Snapshot.empty("paper", "1", "Paper", "17");
        double[] tps = snapshot.tps();
        tps[0] = 99;

        assertEquals(0, snapshot.tps()[0]);
    }

    @Test
    void preservesSubMillisecondPrecisionInSecondBasedMetrics() {
        Snapshot empty = Snapshot.empty("paper", "2", "Paper", "17");
        Snapshot snapshot = new Snapshot(empty.serverId(), empty.tickScopeVersion(),
                empty.paperVersion(), empty.javaVersion(), empty.platform(),
                empty.collectionSeconds(), empty.entityCollectionSeconds(),
                new Snapshot.Ticks(0.001234567, 0.001, 0.002,
                        0.0012, 0.0018, 0.001999999, 6),
                empty.tps(), empty.playersOnline(), empty.playersMax(), empty.plugins(),
                empty.pingAverageSeconds(), empty.pingMaximumSeconds(),
                empty.worlds(), empty.entityTypes(), empty.regionTps(),
                empty.regionTickDurations(), empty.jvm(), empty.proc(), empty.events());

        String rendered = PrometheusWriter.render(snapshot);

        assertTrue(rendered.contains(
                "mc_tick_duration_seconds{server=\"paper\",statistic=\"avg\"} 0.001234567\n"));
        assertTrue(rendered.contains(
                "mc_tick_duration_seconds{server=\"paper\",statistic=\"p99\"} 0.001999999\n"));
    }

    @Test
    void foliaDoesNotMislabelGlobalTickDataAsServerMetrics() {
        Snapshot snapshot = Snapshot.empty("folia", "1", "Folia", "21", "folia");

        String rendered = PrometheusWriter.render(snapshot);

        assertTrue(rendered.contains("platform=\"folia\""));
        assertTrue(!rendered.contains("# TYPE mc_tick_duration_seconds"));
        assertTrue(!rendered.contains("# TYPE mc_tps"));
    }

    @Test
    void rendersRegionalFoliaTpsAndMspt() {
        Snapshot empty = Snapshot.empty("folia", "1", "Canvas", "25", "folia");
        Snapshot snapshot = new Snapshot(empty.serverId(), empty.tickScopeVersion(),
                empty.paperVersion(), empty.javaVersion(), empty.platform(),
                empty.collectionSeconds(), empty.entityCollectionSeconds(), empty.ticks(),
                empty.tps(), empty.playersOnline(), empty.playersMax(), empty.plugins(),
                empty.pingAverageSeconds(), empty.pingMaximumSeconds(),
                empty.worlds(), empty.entityTypes(),
                List.of(new Snapshot.RegionTps("15s", 2, 19.5, 19.75, 20.0)),
                List.of(new Snapshot.RegionTickDuration(
                        "15s", 2, 0.0045, 0.00525, 0.006)),
                empty.jvm(), empty.proc(), empty.events());

        String rendered = PrometheusWriter.render(snapshot);

        assertTrue(rendered.contains(
                "mc_folia_region_tps{server=\"folia\",window=\"15s\",statistic=\"avg\"} 19.75\n"));
        assertTrue(rendered.contains(
                "mc_folia_region_tick_duration_seconds{server=\"folia\",window=\"15s\","
                        + "statistic=\"avg\"} 0.00525\n"));
        assertTrue(rendered.contains(
                "mc_folia_region_tick_duration_samples{server=\"folia\",window=\"15s\"} 2\n"));
    }
}
