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
        assertTrue(rendered.contains("# TYPE mc_tick_samples gauge\n"));
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
        Snapshot snapshot = withTicks(base,
                new Snapshot.Ticks(0.001234567, 0.001, 0.002, 0.0012, 0.0018, 0.001999999, 6));

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
                List.of(new Snapshot.WorldStat("world", 361, 3, 10, 6)),
                List.of(new Snapshot.WorldTotals("world", 812, 44)),
                base.regionTps(), base.regionTickDurations(), base.proc());

        String rendered = PrometheusWriter.render(snapshot);

        assertTrue(rendered.contains("mc_world_chunks{server=\"paper\",world=\"world\"} 361\n"));
        assertTrue(rendered.contains("mc_world_players{server=\"paper\",world=\"world\"} 3\n"));
        assertTrue(rendered.contains("mc_world_view_distance{server=\"paper\",world=\"world\"} 10\n"));
        assertTrue(rendered.contains("mc_world_simulation_distance{server=\"paper\",world=\"world\"} 6\n"));
        assertTrue(rendered.contains("mc_world_entities{server=\"paper\",world=\"world\"} 812\n"));
        assertTrue(rendered.contains("mc_world_tile_entities{server=\"paper\",world=\"world\"} 44\n"));
    }

    @Test
    void omitsScannedWorldTotalsWhenNoScanHasRun() {
        Snapshot base = Snapshot.empty("folia", "1", "Folia", "21", "folia");
        Snapshot snapshot = with(base, 0d, 0d, 0,
                List.of(new Snapshot.WorldStat("world", 361, 3, 10, 6)),
                List.of(), base.regionTps(), base.regionTickDurations(), base.proc());

        String rendered = PrometheusWriter.render(snapshot);

        assertTrue(rendered.contains("mc_world_chunks"));
        assertFalse(rendered.contains("mc_world_entities"));
        assertFalse(rendered.contains("mc_world_tile_entities"));
    }

    @Test
    void rendersTheTickHistogramWithCumulativeBuckets() {
        String rendered = PrometheusWriter.render(fullPaperSnapshot());

        assertTrue(rendered.contains("# TYPE mc_tick_time_seconds histogram\n"));
        assertTrue(rendered.contains("mc_tick_time_seconds_bucket{server=\"paper\",le=\"0.005\"} 1\n"));
        assertTrue(rendered.contains("mc_tick_time_seconds_bucket{server=\"paper\",le=\"0.05\"} 2\n"));
        assertTrue(rendered.contains("mc_tick_time_seconds_bucket{server=\"paper\",le=\"0.25\"} 2\n"));
        assertTrue(rendered.contains("mc_tick_time_seconds_bucket{server=\"paper\",le=\"0.5\"} 3\n"));
        assertTrue(rendered.contains("mc_tick_time_seconds_bucket{server=\"paper\",le=\"1\"} 3\n"));
        assertTrue(rendered.contains("mc_tick_time_seconds_bucket{server=\"paper\",le=\"2.5\"} 3\n"));
        assertTrue(rendered.contains("mc_tick_time_seconds_bucket{server=\"paper\",le=\"+Inf\"} 3\n"));
        assertTrue(rendered.contains("mc_tick_time_seconds_sum{server=\"paper\"} "
                + Double.toString(0.004 + 0.05 + 0.3) + "\n"));
        assertTrue(rendered.contains("mc_tick_time_seconds_count{server=\"paper\"} 3\n"));

        Snapshot folia = Snapshot.empty("folia", "1", "Folia", "21", "folia");
        assertFalse(PrometheusWriter.render(folia).contains("mc_tick_time_seconds"));
    }

    @Test
    void rendersMemoryPoolsAndBufferPools() {
        String rendered = PrometheusWriter.render(fullPaperSnapshot());

        assertTrue(rendered.contains("mc_jvm_memory_pool_used_bytes{server=\"paper\",pool=\"G1 Old Gen\",area=\"heap\"} 100\n"));
        assertTrue(rendered.contains("mc_jvm_memory_pool_committed_bytes{server=\"paper\",pool=\"G1 Old Gen\",area=\"heap\"} 200\n"));
        assertTrue(rendered.contains("mc_jvm_memory_pool_max_bytes{server=\"paper\",pool=\"CodeHeap 'non-nmethods'\",area=\"nonheap\"} -1\n"));
        assertTrue(rendered.contains("mc_jvm_memory_pool_collection_used_bytes{server=\"paper\",pool=\"G1 Old Gen\",area=\"heap\"} 90\n"));
        assertFalse(rendered.contains("mc_jvm_memory_pool_collection_used_bytes{server=\"paper\",pool=\"CodeHeap"),
                "pools without a collection usage are absent from that family");
        assertTrue(rendered.contains("mc_jvm_buffer_pool_used_bytes{server=\"paper\",pool=\"direct\"} 4096\n"));
        assertTrue(rendered.contains("mc_jvm_buffer_pool_capacity_bytes{server=\"paper\",pool=\"direct\"} 4096\n"));
        assertTrue(rendered.contains("mc_jvm_buffer_pool_buffers{server=\"paper\",pool=\"direct\"} 4\n"));
        assertTrue(rendered.contains("mc_jvm_buffer_pool_buffers{server=\"paper\",pool=\"mapped\"} 0\n"));
    }

    @Test
    void rendersServerConfigurationAndChunkCounters() {
        String rendered = PrometheusWriter.render(fullPaperSnapshot());

        assertTrue(rendered.contains("mc_server_view_distance{server=\"paper\"} 10\n"));
        assertTrue(rendered.contains("mc_server_simulation_distance{server=\"paper\"} 6\n"));
        assertTrue(rendered.contains("mc_server_online_mode{server=\"paper\"} 1\n"));
        assertTrue(rendered.contains("mc_server_whitelist_enabled{server=\"paper\"} 1\n"));
        assertTrue(rendered.contains("mc_server_whitelist_enforced{server=\"paper\"} 0\n"));
        assertTrue(rendered.contains("mc_world_chunk_loads_total{server=\"paper\",world=\"world\"} 900\n"));
        assertTrue(rendered.contains("mc_world_chunk_unloads_total{server=\"paper\",world=\"world\"} 850\n"));
        assertTrue(rendered.contains("mc_world_chunk_generations_total{server=\"paper\",world=\"nether\"} 1\n"));

        // Unknown until the server has been asked; never a made-up zero.
        String placeholder = PrometheusWriter.render(Snapshot.empty("paper", "1", "Paper", "17"));
        assertFalse(placeholder.contains("mc_server_"));
        assertFalse(placeholder.contains("mc_world_chunk_"));
    }

    @Test
    void rendersDescriptorsAndRejectedConnectionsOnlyWhenTheyAreKnown() {
        Snapshot paper = fullPaperSnapshot();
        var health = new CollectionHealth(true, false);
        var stats = new ScrapeStats();
        stats.rejectedPerAddress();

        String full = PrometheusWriter.render(paper, health, new Snapshot.Descriptors(120, 65536), stats);
        assertTrue(full.contains("mc_process_open_fds{server=\"paper\"} 120\n"));
        assertTrue(full.contains("mc_process_max_fds{server=\"paper\"} 65536\n"));
        assertTrue(full.contains("mc_http_connections_rejected_total{server=\"paper\",reason=\"per_address\"} 1\n"));
        assertTrue(full.contains("mc_http_connections_rejected_total{server=\"paper\",reason=\"saturated\"} 0\n"));

        String bare = PrometheusWriter.render(paper, health);
        assertFalse(bare.contains("mc_process_open_fds"), "unavailable on this JVM means absent");
        assertFalse(bare.contains("mc_http_connections_rejected_total"), "no endpoint, no endpoint counters");
    }

    @Test
    void rendersFoliaRegionMinimumAndMaximumDistinctly() {
        Snapshot base = Snapshot.empty("folia", "1", "Folia", "21", "folia");
        Snapshot snapshot = with(base, 0d, 0d, 0, base.worlds(), base.worldTotals(),
                List.of(new Snapshot.RegionTps("1m", 3, 17.5, 19.25, 20.0)),
                List.of(new Snapshot.RegionTickDuration("1m", 3, 0.004, 0.0215, 0.048)),
                base.proc());

        String rendered = PrometheusWriter.render(snapshot);
        assertTrue(rendered.contains("mc_folia_region_tps{server=\"folia\",window=\"1m\",statistic=\"min\"} 17.5\n"));
        assertTrue(rendered.contains("mc_folia_region_tps{server=\"folia\",window=\"1m\",statistic=\"max\"} 20\n"));
        assertTrue(rendered.contains("mc_folia_region_tps_samples{server=\"folia\",window=\"1m\"} 3\n"));
        assertTrue(rendered.contains("mc_folia_region_tick_duration_seconds{server=\"folia\",window=\"1m\",statistic=\"min\"} 0.004\n"));
        assertTrue(rendered.contains("mc_folia_region_tick_duration_seconds{server=\"folia\",window=\"1m\",statistic=\"max\"} 0.048\n"));
    }

    /**
     * The metric names are a stability contract (wiki Metric-Stability.md): a rename or
     * removal is a breaking change, so the complete set is pinned here. Additions go on the
     * list and into the wiki's metrics reference in the same change.
     */
    @Test
    void emitsExactlyTheDocumentedMetricFamilies() {
        var paperHealth = new CollectionHealth(true, false);
        var stats = new ScrapeStats();
        String paper = PrometheusWriter.render(fullPaperSnapshot(), paperHealth,
                new Snapshot.Descriptors(1, 2), stats);
        Snapshot foliaBase = Snapshot.empty("folia", "1", "Folia", "21", "folia");
        Snapshot folia = with(foliaBase, 0.03, 0.05, 2, foliaBase.worlds(), foliaBase.worldTotals(),
                List.of(new Snapshot.RegionTps("1m", 2, 19, 19.5, 20)),
                List.of(new Snapshot.RegionTickDuration("1m", 2, 0.004, 0.005, 0.006)),
                foliaBase.proc());
        var foliaHealth = new CollectionHealth(false, true);
        foliaHealth.players(2, 2);
        String foliaRendered = PrometheusWriter.render(folia, foliaHealth, new Snapshot.Descriptors(1, 2), stats);

        java.util.Set<String> emitted = new java.util.TreeSet<>();
        for (String line : (paper + foliaRendered).split("\n")) {
            if (line.startsWith("# TYPE ")) emitted.add(line.split(" ")[2] + " " + line.split(" ")[3]);
        }
        java.util.Set<String> documented = new java.util.TreeSet<>(List.of(
                "mc_tickscope_info gauge",
                "mc_tick_duration_seconds gauge", "mc_tick_samples gauge", "mc_tps gauge",
                "mc_tick_time_seconds histogram",
                "mc_folia_region_tps gauge", "mc_folia_region_tps_samples gauge",
                "mc_folia_region_tick_duration_seconds gauge", "mc_folia_region_tick_duration_samples gauge",
                "mc_players_online gauge", "mc_players_max gauge", "mc_plugins gauge",
                "mc_server_view_distance gauge", "mc_server_simulation_distance gauge",
                "mc_server_online_mode gauge", "mc_server_whitelist_enabled gauge",
                "mc_server_whitelist_enforced gauge",
                "mc_player_ping_seconds gauge",
                "mc_world_chunks gauge", "mc_world_players gauge",
                "mc_world_view_distance gauge", "mc_world_simulation_distance gauge",
                "mc_world_entities gauge", "mc_world_tile_entities gauge", "mc_world_entities_by_type gauge",
                "mc_world_chunk_loads_total counter", "mc_world_chunk_unloads_total counter",
                "mc_world_chunk_generations_total counter",
                "mc_jvm_memory_used_bytes gauge", "mc_jvm_memory_committed_bytes gauge",
                "mc_jvm_memory_max_bytes gauge", "mc_jvm_memory_init_bytes gauge",
                "mc_jvm_memory_pool_used_bytes gauge", "mc_jvm_memory_pool_committed_bytes gauge",
                "mc_jvm_memory_pool_max_bytes gauge", "mc_jvm_memory_pool_collection_used_bytes gauge",
                "mc_jvm_buffer_pool_used_bytes gauge", "mc_jvm_buffer_pool_capacity_bytes gauge",
                "mc_jvm_buffer_pool_buffers gauge",
                "mc_jvm_threads_current gauge", "mc_jvm_threads_daemon gauge", "mc_jvm_threads_peak gauge",
                "mc_jvm_threads_started_total counter", "mc_jvm_classes_loaded gauge",
                "mc_jvm_gc_collections_total counter", "mc_jvm_gc_seconds_total counter",
                "mc_process_cpu_load_ratio gauge", "mc_system_cpu_load_ratio gauge",
                "mc_process_cpu_seconds_total counter", "mc_process_start_time_seconds gauge",
                "mc_uptime_seconds gauge", "mc_process_open_fds gauge", "mc_process_max_fds gauge",
                "mc_events_total counter",
                "mc_collection_duration_seconds gauge", "mc_entity_collection_duration_seconds gauge",
                "mc_collection_enabled gauge", "mc_collection_last_success_timestamp_seconds gauge",
                "mc_collection_failures_total counter",
                "mc_folia_player_samples_completed gauge", "mc_folia_player_samples_expected gauge",
                "mc_http_connections_rejected_total counter"));
        assertEquals(documented, emitted);
    }

    @Test
    void omitsTickDurationStatisticsUntilTheRingHasSamples() {
        // A server that has not ticked yet has no tick duration, not a 0 s one.
        String fresh = PrometheusWriter.render(Snapshot.empty("paper", "1", "Paper", "17"));
        assertFalse(fresh.contains("mc_tick_duration_seconds{"));
        assertFalse(fresh.contains("# TYPE mc_tick_duration_seconds"));
        assertTrue(fresh.contains("mc_tick_samples{server=\"paper\"} 0\n"));
        assertTrue(fresh.contains("mc_tps{server=\"paper\",window=\"1m\"} 0\n"));

        Snapshot base = Snapshot.empty("paper", "1", "Paper", "17");
        Snapshot ticking = withTicks(base, new Snapshot.Ticks(0.002, 0.001, 0.004, 0.002, 0.004, 0.004, 4));
        String rendered = PrometheusWriter.render(ticking);
        assertTrue(rendered.contains("mc_tick_duration_seconds{server=\"paper\",statistic=\"p99\"} 0.004\n"));
        assertTrue(rendered.contains("mc_tick_samples{server=\"paper\"} 4\n"));
    }

    @Test
    void writesEveryFamilyAsOneContiguousBlockOnBothPlatforms() {
        Snapshot paper = fullPaperSnapshot();
        var paperHealth = new CollectionHealth(true, false);
        paperHealth.success("main");
        paperHealth.failure("world");
        var stats = new ScrapeStats();
        stats.rejectedPerAddress();
        assertContiguousFamilies(PrometheusWriter.render(paper, paperHealth,
                new Snapshot.Descriptors(120, 65536), stats));

        Snapshot foliaBase = Snapshot.empty("folia", "1", "Folia", "21", "folia");
        Snapshot folia = with(foliaBase, 0.03, 0.05, 2,
                List.of(new Snapshot.WorldStat("world", 300, 2, 10, 8)), List.of(),
                List.of(new Snapshot.RegionTps("5s", 2, 19, 19.5, 20),
                        new Snapshot.RegionTps("1m", 2, 19, 19.5, 20)),
                List.of(new Snapshot.RegionTickDuration("5s", 2, 0.004, 0.005, 0.006),
                        new Snapshot.RegionTickDuration("1m", 2, 0.004, 0.005, 0.006)),
                foliaBase.proc());
        var foliaHealth = new CollectionHealth(false, true);
        foliaHealth.players(1, 2);
        String rendered = PrometheusWriter.render(folia, foliaHealth);
        assertContiguousFamilies(rendered);
        assertTrue(rendered.indexOf("stage=\"main\"") < rendered.indexOf("stage=\"world\""),
                "stages keep their declared order");
    }

    /**
     * The text format requires all samples of a family in one group behind its HELP and TYPE.
     * Prometheus tolerates interleaving; stricter parsers split such a family into fragments.
     */
    private static void assertContiguousFamilies(String exposition) {
        java.util.Set<String> typed = new java.util.HashSet<>();
        java.util.Set<String> finished = new java.util.HashSet<>();
        String current = null;
        for (String line : exposition.split("\n")) {
            if (line.startsWith("# TYPE ")) {
                String name = line.split(" ")[2];
                assertTrue(typed.add(name), name + " has two TYPE lines");
                assertFalse(finished.contains(name), name + " typed after its samples");
                continue;
            }
            if (line.startsWith("#") || line.isEmpty()) continue;
            int end = line.indexOf('{');
            if (end < 0) end = line.indexOf(' ');
            String name = line.substring(0, end);
            // A histogram's samples carry the _bucket, _sum and _count suffixes of one family.
            for (String suffix : new String[]{"_bucket", "_sum", "_count"}) {
                if (name.endsWith(suffix) && typed.contains(name.substring(0, name.length() - suffix.length()))) {
                    name = name.substring(0, name.length() - suffix.length());
                }
            }
            if (!name.equals(current)) {
                if (current != null) finished.add(current);
                assertFalse(finished.contains(name), name + " resumes after another family");
                current = name;
            }
            assertTrue(typed.contains(name), name + " has a sample before its TYPE line");
        }
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
                base.entityCollectionSeconds(), base.ticks(), base.tickHistogram(), base.tps(),
                base.playersOnline(), base.playersMax(), base.plugins(),
                pingAverageSeconds, pingMaximumSeconds, pingSamples, base.server(), worlds, worldTotals,
                base.entityTypes(), base.chunkEvents(), regionTps, regionTickDurations, base.jvm(), proc,
                base.events());
    }

    private static Snapshot withTicks(Snapshot base, Snapshot.Ticks ticks) {
        return new Snapshot(base.serverId(), base.tickScopeVersion(), base.paperVersion(),
                base.javaVersion(), base.platform(), base.collectionSeconds(),
                base.entityCollectionSeconds(), ticks, base.tickHistogram(), base.tps(),
                base.playersOnline(), base.playersMax(), base.plugins(),
                base.pingAverageSeconds(), base.pingMaximumSeconds(), base.pingSamples(),
                base.server(), base.worlds(), base.worldTotals(), base.entityTypes(),
                base.chunkEvents(), base.regionTps(), base.regionTickDurations(), base.jvm(),
                base.proc(), base.events());
    }

    /**
     * Realistic expositions for external linting: {@code promtool check metrics <
     * target/exposition/paper.prom}. Every family is populated on the Paper file, every
     * Folia family on the Folia file, and the startup file is what a scrape sees before the
     * first collection.
     */
    @Test
    void writesSampleExpositionsForExternalLinting() throws java.io.IOException {
        java.nio.file.Path dir = java.nio.file.Path.of("target", "exposition");
        java.nio.file.Files.createDirectories(dir);
        var stats = new ScrapeStats();
        stats.rejectedSaturated();
        var paperHealth = new CollectionHealth(true, false);
        paperHealth.success("main");
        paperHealth.success("world");
        java.nio.file.Files.writeString(dir.resolve("paper.prom"), PrometheusWriter.render(
                fullPaperSnapshot(), paperHealth, new Snapshot.Descriptors(812, 1048576), stats));

        Snapshot foliaBase = Snapshot.empty("folia\"1", "1", "Canvas 26.2", "21", "folia");
        Snapshot folia = with(foliaBase, 0.03, 0.05, 2,
                List.of(new Snapshot.WorldStat("world", 300, 2, 10, 8)), List.of(),
                List.of(new Snapshot.RegionTps("5s", 2, 19, 19.5, 20), new Snapshot.RegionTps("1m", 2, 19, 19.5, 20)),
                List.of(new Snapshot.RegionTickDuration("5s", 2, 0.004, 0.005, 0.006)),
                new Snapshot.Proc(0.1, 0.2, 30, 1.7e9, 42));
        var foliaHealth = new CollectionHealth(false, true);
        foliaHealth.success("main");
        foliaHealth.players(1, 2);
        java.nio.file.Files.writeString(dir.resolve("folia.prom"), PrometheusWriter.render(
                folia, foliaHealth, Snapshot.Descriptors.UNAVAILABLE, stats));

        java.nio.file.Files.writeString(dir.resolve("startup.prom"), PrometheusWriter.render(
                Snapshot.empty("paper", "1", "Paper", "17"), new CollectionHealth(true, false),
                Snapshot.Descriptors.UNAVAILABLE, new ScrapeStats()));
    }

    /** A Paper snapshot with every family populated, including the ones added in 2.1. */
    static Snapshot fullPaperSnapshot() {
        var histogram = new TickHistogram();
        histogram.record(0.004);
        histogram.record(0.05);
        histogram.record(0.3);
        return new Snapshot("paper", "1", "Paper", "17", "paper", 0.001, 0.2,
                new Snapshot.Ticks(0.002, 0.001, 0.004, 0.002, 0.004, 0.004, 4),
                histogram.snapshot(),
                new double[]{20, 19.5, 19}, 3, 20, 7, 0.03, 0.05, 3,
                new Snapshot.ServerInfo(10, 6, true, true, false),
                List.of(new Snapshot.WorldStat("world", 300, 2, 10, 6), new Snapshot.WorldStat("nether", 50, 1, 8, 5)),
                List.of(new Snapshot.WorldTotals("world", 800, 40), new Snapshot.WorldTotals("nether", 90, 4)),
                List.of(new Snapshot.TypeCount("world", "zombie", 12), new Snapshot.TypeCount("nether", "ghast", 2)),
                List.of(new Snapshot.WorldChunkEvents("nether", 5, 4, 1), new Snapshot.WorldChunkEvents("world", 900, 850, 120)),
                List.of(), List.of(),
                new Snapshot.Jvm(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13,
                        List.of(new Snapshot.Gc("G1 Young Generation", 5, 0.1),
                                new Snapshot.Gc("G1 Old Generation", 1, Double.NaN)),
                        List.of(new Snapshot.MemoryPool("G1 Old Gen", "heap", 100, 200, 300, 90),
                                new Snapshot.MemoryPool("CodeHeap 'non-nmethods'", "nonheap", 10, 20, -1, Double.NaN)),
                        List.of(new Snapshot.BufferPool("direct", 4, 4096, 4096),
                                new Snapshot.BufferPool("mapped", 0, 0, 0))),
                new Snapshot.Proc(0.1, 0.2, 30, 1.7e9, 42),
                java.util.Map.of("join", 1L, "quit", 2L));
    }
}
