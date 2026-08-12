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

import java.util.Map;

/** Renders a {@link Snapshot} as Prometheus text exposition format 0.0.4. */
final class PrometheusWriter {

    private PrometheusWriter() {}

    static String render(Snapshot s) {
        StringBuilder b = new StringBuilder(8192);
        String srv = esc(s.serverId());

        help(b, "mc_tickscope_info", "gauge", "TickScope and runtime version information");
        b.append("mc_tickscope_info{server=\"").append(srv)
         .append("\",version=\"").append(esc(s.tickScopeVersion()))
         .append("\",paper=\"").append(esc(s.paperVersion()))
         .append("\",java=\"").append(esc(s.javaVersion()))
         .append("\",platform=\"").append(esc(s.platform()))
         .append("\"} 1\n");

        // ---- tick timing ----------------------------------------------------
        // Percentiles come from Paper's raw per-tick nanosecond array, so these are
        // exact over the sample window rather than a bucketed estimate.
        if (!s.platform().equals("folia")) {
            help(b, "mc_tick_duration_seconds", "gauge",
                    "Duration of one server tick in seconds");
            for (var value : new Object[][]{
                    {"avg", s.ticks().averageSeconds()},
                    {"min", s.ticks().minimumSeconds()},
                    {"max", s.ticks().maximumSeconds()},
                    {"p50", s.ticks().p50Seconds()},
                    {"p95", s.ticks().p95Seconds()},
                    {"p99", s.ticks().p99Seconds()}}) {
                statisticLine(b, "mc_tick_duration_seconds", srv,
                        (String) value[0], (double) value[1]);
            }
            gauge(b, "mc_tick_samples", "Ticks in the current sample window", srv,
                    s.ticks().samples());

            help(b, "mc_tps", "gauge", "Ticks per second, capped at 20");
            String[] windows = {"1m", "5m", "15m"};
            double[] tps = s.tps();
            for (int i = 0; i < windows.length && i < tps.length; i++) {
                b.append("mc_tps{server=\"").append(srv).append("\",window=\"")
                 .append(windows[i]).append("\"} ").append(num(tps[i])).append('\n');
            }
        } else {
            if (!s.regionTps().isEmpty()) {
                help(b, "mc_folia_region_tps", "gauge",
                        "TPS across player-active Folia region samples");
                help(b, "mc_folia_region_tps_samples", "gauge",
                        "Player-location samples in the Folia TPS summary");
                for (Snapshot.RegionTps region : s.regionTps()) {
                    foliaRegionLine(b, "mc_folia_region_tps", srv,
                            region.window(), "min", region.min());
                    foliaRegionLine(b, "mc_folia_region_tps", srv,
                            region.window(), "avg", region.avg());
                    foliaRegionLine(b, "mc_folia_region_tps", srv,
                            region.window(), "max", region.max());
                    foliaSamplesLine(b, "mc_folia_region_tps_samples", srv,
                            region.window(), region.samples());
                }
            }
            if (!s.regionTickDurations().isEmpty()) {
                help(b, "mc_folia_region_tick_duration_seconds", "gauge",
                        "Average tick duration across player-active Folia region samples");
                help(b, "mc_folia_region_tick_duration_samples", "gauge",
                        "Player-location samples in the Folia tick-duration summary");
                for (Snapshot.RegionTickDuration region : s.regionTickDurations()) {
                    foliaRegionLine(b, "mc_folia_region_tick_duration_seconds", srv,
                            region.window(), "min", region.minimumSeconds());
                    foliaRegionLine(b, "mc_folia_region_tick_duration_seconds", srv,
                            region.window(), "avg", region.averageSeconds());
                    foliaRegionLine(b, "mc_folia_region_tick_duration_seconds", srv,
                            region.window(), "max", region.maximumSeconds());
                    foliaSamplesLine(b, "mc_folia_region_tick_duration_samples", srv,
                            region.window(), region.samples());
                }
            }
        }

        // ---- server ---------------------------------------------------------
        gauge(b, "mc_players_online", "Players currently connected", srv, s.playersOnline());
        gauge(b, "mc_players_max", "Configured player slots", srv, s.playersMax());
        gauge(b, "mc_plugins", "Loaded plugins", srv, s.plugins());
        if (s.pingSamples() > 0) {
            help(b, "mc_player_ping_seconds", "gauge", "Player network latency in seconds");
            statisticLine(b, "mc_player_ping_seconds", srv, "avg", s.pingAverageSeconds());
            statisticLine(b, "mc_player_ping_seconds", srv, "max", s.pingMaximumSeconds());
        }

        // ---- per world ------------------------------------------------------
        if (!s.worlds().isEmpty()) {
            help(b, "mc_world_chunks", "gauge", "Loaded chunks per world");
            for (Snapshot.WorldStat w : s.worlds()) worldLine(b, "mc_world_chunks", srv, w.name(), w.chunks());
            help(b, "mc_world_players", "gauge", "Players per world");
            for (Snapshot.WorldStat w : s.worlds()) worldLine(b, "mc_world_players", srv, w.name(), w.players());
        }

        // Sampled on the slower scan cadence, and absent on Folia where a world-wide walk
        // would cross region ownership boundaries.
        if (!s.worldTotals().isEmpty()) {
            help(b, "mc_world_entities", "gauge", "Entities per world");
            for (Snapshot.WorldTotals w : s.worldTotals()) worldLine(b, "mc_world_entities", srv, w.name(), w.entities());
            help(b, "mc_world_tile_entities", "gauge", "Tile entities per world");
            for (Snapshot.WorldTotals w : s.worldTotals()) worldLine(b, "mc_world_tile_entities", srv, w.name(), w.tileEntities());
        }

        if (!s.entityTypes().isEmpty()) {
            help(b, "mc_world_entities_by_type", "gauge", "Entities per world broken down by type");
            for (Snapshot.TypeCount t : s.entityTypes()) {
                b.append("mc_world_entities_by_type{server=\"").append(srv)
                 .append("\",world=\"").append(esc(t.world()))
                 .append("\",type=\"").append(esc(t.type()))
                 .append("\"} ").append(t.count()).append('\n');
            }
        }

        // ---- jvm ------------------------------------------------------------
        Snapshot.Jvm j = s.jvm();
        help(b, "mc_jvm_memory_used_bytes", "gauge", "JVM memory in use");
        areaLine(b, "mc_jvm_memory_used_bytes", srv, "heap", j.heapUsed());
        areaLine(b, "mc_jvm_memory_used_bytes", srv, "nonheap", j.nonHeapUsed());
        help(b, "mc_jvm_memory_committed_bytes", "gauge", "JVM memory committed by the OS");
        areaLine(b, "mc_jvm_memory_committed_bytes", srv, "heap", j.heapCommitted());
        areaLine(b, "mc_jvm_memory_committed_bytes", srv, "nonheap", j.nonHeapCommitted());
        help(b, "mc_jvm_memory_max_bytes", "gauge", "JVM memory ceiling (-1 when unbounded)");
        areaLine(b, "mc_jvm_memory_max_bytes", srv, "heap", j.heapMax());
        areaLine(b, "mc_jvm_memory_max_bytes", srv, "nonheap", j.nonHeapMax());
        help(b, "mc_jvm_memory_init_bytes", "gauge", "JVM memory requested at startup");
        areaLine(b, "mc_jvm_memory_init_bytes", srv, "heap", j.heapInit());
        areaLine(b, "mc_jvm_memory_init_bytes", srv, "nonheap", j.nonHeapInit());

        gauge(b, "mc_jvm_threads_current", "Live threads", srv, j.threadsCurrent());
        gauge(b, "mc_jvm_threads_daemon", "Live daemon threads", srv, j.threadsDaemon());
        gauge(b, "mc_jvm_threads_peak", "Peak live threads since start", srv, j.threadsPeak());
        counter(b, "mc_jvm_threads_started_total", "Threads started since JVM start", srv, j.threadsStarted());
        gauge(b, "mc_jvm_classes_loaded", "Classes currently loaded", srv, j.classesLoaded());

        if (!j.gcs().isEmpty()) {
            help(b, "mc_jvm_gc_collections_total", "counter", "GC cycles by collector");
            for (Snapshot.Gc g : j.gcs()) gcLine(b, "mc_jvm_gc_collections_total", srv, g.name(), g.count());
            help(b, "mc_jvm_gc_seconds_total", "counter", "Time spent in GC by collector");
            for (Snapshot.Gc g : j.gcs()) {
                if (!Double.isNaN(g.seconds())) gcLine(b, "mc_jvm_gc_seconds_total", srv, g.name(), g.seconds());
            }
        }

        // ---- process --------------------------------------------------------
        Snapshot.Proc p = s.proc();
        optionalGauge(b, "mc_process_cpu_load_ratio", "CPU load of the server process, 0-1", srv, p.processCpuRatio());
        optionalGauge(b, "mc_system_cpu_load_ratio",
                "CPU load of the JVM's operating environment (host, or container when containerised), 0-1",
                srv, p.systemCpuRatio());
        optionalCounter(b, "mc_process_cpu_seconds_total", "CPU seconds consumed by the process", srv, p.processCpuSeconds());
        gauge(b, "mc_process_start_time_seconds", "Process start, unix seconds", srv, p.startTimeSeconds());
        gauge(b, "mc_uptime_seconds", "JVM uptime", srv, p.uptimeSeconds());

        // ---- events ---------------------------------------------------------
        if (!s.events().isEmpty()) {
            help(b, "mc_events_total", "counter", "Player events since server start");
            for (Map.Entry<String, Long> e : s.events().entrySet()) {
                b.append("mc_events_total{server=\"").append(srv).append("\",event=\"")
                 .append(esc(e.getKey())).append("\"} ").append(e.getValue()).append('\n');
            }
        }

        // ---- self -----------------------------------------------------------
        gauge(b, "mc_collection_duration_seconds",
                "Time the last main-thread collection took", srv, s.collectionSeconds());
        gauge(b, "mc_entity_collection_duration_seconds",
                "Time the last entity-by-type collection took", srv,
                s.entityCollectionSeconds());
        return b.toString();
    }

    private static void help(StringBuilder b, String name, String type, String doc) {
        b.append("# HELP ").append(name).append(' ').append(doc).append('\n')
         .append("# TYPE ").append(name).append(' ').append(type).append('\n');
    }

    private static void gauge(StringBuilder b, String name, String doc, String srv, double v) {
        help(b, name, "gauge", doc);
        b.append(name).append("{server=\"").append(srv).append("\"} ").append(num(v)).append('\n');
    }

    /** Skips the family entirely when the platform could not supply the reading. */
    private static void optionalGauge(StringBuilder b, String name, String doc, String srv, double v) {
        if (!Double.isNaN(v)) gauge(b, name, doc, srv, v);
    }

    private static void optionalCounter(StringBuilder b, String name, String doc, String srv, double v) {
        if (!Double.isNaN(v)) counter(b, name, doc, srv, v);
    }

    private static void counter(StringBuilder b, String name, String doc, String srv, double v) {
        help(b, name, "counter", doc);
        b.append(name).append("{server=\"").append(srv).append("\"} ").append(num(v)).append('\n');
    }

    private static void worldLine(StringBuilder b, String name, String srv, String world, double v) {
        b.append(name).append("{server=\"").append(srv).append("\",world=\"")
         .append(esc(world)).append("\"} ").append(num(v)).append('\n');
    }

    private static void statisticLine(StringBuilder b, String name, String srv,
                                      String statistic, double value) {
        b.append(name).append("{server=\"").append(srv).append("\",statistic=\"")
         .append(statistic).append("\"} ").append(num(value)).append('\n');
    }

    private static void areaLine(StringBuilder b, String name, String srv, String area, double v) {
        b.append(name).append("{server=\"").append(srv).append("\",area=\"")
         .append(area).append("\"} ").append(num(v)).append('\n');
    }

    private static void gcLine(StringBuilder b, String name, String srv, String gc, double v) {
        b.append(name).append("{server=\"").append(srv).append("\",gc=\"")
         .append(esc(gc)).append("\"} ").append(num(v)).append('\n');
    }

    private static void foliaRegionLine(StringBuilder b, String name, String srv,
                                        String window, String statistic, double value) {
        b.append(name).append("{server=\"").append(srv).append("\",window=\"")
         .append(window).append("\",statistic=\"").append(statistic).append("\"} ")
         .append(num(value)).append('\n');
    }

    private static void foliaSamplesLine(StringBuilder b, String name, String srv,
                                         String window, int samples) {
        b.append(name).append("{server=\"").append(srv).append("\",window=\"")
         .append(window).append("\"} ").append(samples).append('\n');
    }

    /** Double.toString always uses a period and preserves enough precision to round-trip. */
    private static String num(double v) {
        if (Double.isNaN(v)) return "NaN";
        if (v == Double.POSITIVE_INFINITY) return "+Inf";
        if (v == Double.NEGATIVE_INFINITY) return "-Inf";
        if (v == Math.rint(v) && !Double.isInfinite(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
