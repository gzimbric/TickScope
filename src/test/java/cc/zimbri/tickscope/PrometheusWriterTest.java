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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertTrue(rendered.contains("# TYPE mc_jvm_memory_used_bytes gauge\n"));
        assertFalse(rendered.contains("mc_mspt_ms"));
        assertFalse(rendered.contains("mc_jvm_memory_bytes_used"));
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
        Snapshot base = Snapshot.empty("paper", "2", "Paper", "17");
        Snapshot snapshot = new Snapshot(base.serverId(), base.tickScopeVersion(),
                base.paperVersion(), base.javaVersion(), base.platform(),
                base.collectionSeconds(), base.entityCollectionSeconds(),
                new Snapshot.Ticks(0.001234567, 0.001, 0.002, 0.0012, 0.0018, 0.001999999, 6),
                base.tps(), base.playersOnline(), base.playersMax(), base.plugins(),
                base.pingAverageSeconds(), base.pingMaximumSeconds(), base.pingSamples(),
                base.worlds(), base.worldTotals(), base.entityTypes(), base.regionTps(),
                base.regionTickDurations(), base.jvm(), base.proc(), base.events());

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
        assertFalse(rendered.contains("# TYPE mc_tick_duration_seconds"));
        assertFalse(rendered.contains("# TYPE mc_tps"));
    }

    @Test
    void rendersRegionalFoliaTpsAndTickDuration() {
        Snapshot base = Snapshot.empty("folia", "1", "Canvas", "25", "folia");
        Snapshot snapshot = with(base, 0d, 0d, 0, base.worlds(), base.worldTotals(),
                List.of(new Snapshot.RegionTps("15s", 2, 19.5, 19.75, 20.0)),
                List.of(new Snapshot.RegionTickDuration("15s", 2, 0.0045, 0.00525, 0.006)),
                base.proc());

        String rendered = PrometheusWriter.render(snapshot);

        assertTrue(rendered.contains(
                "mc_folia_region_tps{server=\"folia\",window=\"15s\",statistic=\"avg\"} 19.75\n"));
        assertTrue(rendered.contains(
                "mc_folia_region_tick_duration_seconds{server=\"folia\",window=\"15s\","
                        + "statistic=\"avg\"} 0.00525\n"));
        assertTrue(rendered.contains(
                "mc_folia_region_tick_duration_samples{server=\"folia\",window=\"15s\"} 2\n"));
    }

    @Test
    void omitsPingWhenNoPlayerWasMeasured() {
        Snapshot base = Snapshot.empty("paper", "1", "Paper", "17");

        String rendered = PrometheusWriter.render(
                with(base, 0d, 0d, 0, base.worlds(), base.worldTotals(),
                        base.regionTps(), base.regionTickDurations(), base.proc()));

        assertFalse(rendered.contains("mc_player_ping_seconds"));
    }

    @Test
    void rendersPingOnceAPlayerHasBeenMeasured() {
        Snapshot base = Snapshot.empty("paper", "1", "Paper", "17");

        String rendered = PrometheusWriter.render(
                with(base, 0.0515, 0.088, 2, base.worlds(), base.worldTotals(),
                        base.regionTps(), base.regionTickDurations(), base.proc()));

        assertTrue(rendered.contains("mc_player_ping_seconds{server=\"paper\",statistic=\"avg\"} 0.0515\n"));
        assertTrue(rendered.contains("mc_player_ping_seconds{server=\"paper\",statistic=\"max\"} 0.088\n"));
    }

    @Test
    void omitsCpuSeriesThePlatformCannotSupply() {
        Snapshot base = Snapshot.empty("paper", "1", "Paper", "17");
        Snapshot unavailable = with(base, 0d, 0d, 0, base.worlds(), base.worldTotals(),
                base.regionTps(), base.regionTickDurations(),
                new Snapshot.Proc(Double.NaN, Double.NaN, Double.NaN, 1.77e9, 42d));

        String rendered = PrometheusWriter.render(unavailable);

        assertFalse(rendered.contains("mc_process_cpu_load_ratio"));
        assertFalse(rendered.contains("mc_system_cpu_load_ratio"));
        assertFalse(rendered.contains("mc_process_cpu_seconds_total"));
        assertTrue(rendered.contains("mc_uptime_seconds{server=\"paper\"} 42\n"));
    }

    @Test
    void separatesCounterReadWorldSeriesFromScannedTotals() {
        Snapshot base = Snapshot.empty("paper", "1", "Paper", "17");
        Snapshot snapshot = with(base, 0d, 0d, 0,
                List.of(new Snapshot.WorldStat("world", 361, 3)),
                List.of(new Snapshot.WorldTotals("world", 812, 44)),
                base.regionTps(), base.regionTickDurations(), base.proc());

        String rendered = PrometheusWriter.render(snapshot);

        assertTrue(rendered.contains("mc_world_chunks{server=\"paper\",world=\"world\"} 361\n"));
        assertTrue(rendered.contains("mc_world_players{server=\"paper\",world=\"world\"} 3\n"));
        assertTrue(rendered.contains("mc_world_entities{server=\"paper\",world=\"world\"} 812\n"));
        assertTrue(rendered.contains("mc_world_tile_entities{server=\"paper\",world=\"world\"} 44\n"));
    }

    @Test
    void omitsScannedWorldTotalsWhenNoScanHasRun() {
        Snapshot base = Snapshot.empty("folia", "1", "Folia", "21", "folia");
        Snapshot snapshot = with(base, 0d, 0d, 0,
                List.of(new Snapshot.WorldStat("world", 361, 3)),
                List.of(), base.regionTps(), base.regionTickDurations(), base.proc());

        String rendered = PrometheusWriter.render(snapshot);

        assertTrue(rendered.contains("mc_world_chunks"));
        assertFalse(rendered.contains("mc_world_entities"));
        assertFalse(rendered.contains("mc_world_tile_entities"));
    }

    private static Snapshot with(Snapshot base, double pingAverageSeconds,
                                 double pingMaximumSeconds, int pingSamples,
                                 List<Snapshot.WorldStat> worlds,
                                 List<Snapshot.WorldTotals> worldTotals,
                                 List<Snapshot.RegionTps> regionTps,
                                 List<Snapshot.RegionTickDuration> regionTickDurations,
                                 Snapshot.Proc proc) {
        return new Snapshot(base.serverId(), base.tickScopeVersion(), base.paperVersion(),
                base.javaVersion(), base.platform(), base.collectionSeconds(),
                base.entityCollectionSeconds(), base.ticks(), base.tps(),
                base.playersOnline(), base.playersMax(), base.plugins(),
                pingAverageSeconds, pingMaximumSeconds, pingSamples, worlds, worldTotals,
                base.entityTypes(), regionTps, regionTickDurations, base.jvm(), proc,
                base.events());
    }
}
