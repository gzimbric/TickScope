package cc.zimbri.metrics;

import java.util.Locale;
import java.util.Map;

/** Renders a {@link Snapshot} as Prometheus text exposition format 0.0.4. */
final class PrometheusWriter {

    private PrometheusWriter() {}

    static String render(Snapshot s) {
        StringBuilder b = new StringBuilder(8192);
        String srv = esc(s.serverId());

        // ---- tick timing ----------------------------------------------------
        // Percentiles come from Paper's raw per-tick nanosecond array, so these are
        // exact over the sample window rather than a bucketed estimate.
        help(b, "mc_mspt_ms", "gauge", "Milliseconds per tick. One tick is 50ms.");
        for (var q : new Object[][]{{"avg", s.ticks().avgMs()}, {"min", s.ticks().minMs()},
                {"max", s.ticks().maxMs()}, {"p50", s.ticks().p50Ms()},
                {"p95", s.ticks().p95Ms()}, {"p99", s.ticks().p99Ms()}}) {
            b.append("mc_mspt_ms{server=\"").append(srv).append("\",quantile=\"")
             .append(q[0]).append("\"} ").append(num((double) q[1])).append('\n');
        }
        gauge(b, "mc_tick_samples", "Ticks in the current sample window", srv, s.ticks().samples());

        help(b, "mc_tps", "gauge", "Ticks per second, capped at 20");
        String[] windows = {"1m", "5m", "15m"};
        double[] tps = s.tps();
        for (int i = 0; i < windows.length && i < tps.length; i++) {
            b.append("mc_tps{server=\"").append(srv).append("\",window=\"")
             .append(windows[i]).append("\"} ").append(num(tps[i])).append('\n');
        }

        // ---- server ---------------------------------------------------------
        gauge(b, "mc_players_online", "Players currently connected", srv, s.playersOnline());
        gauge(b, "mc_players_max", "Configured player slots", srv, s.playersMax());
        gauge(b, "mc_plugins", "Loaded plugins", srv, s.plugins());
        gauge(b, "mc_player_ping_avg_ms", "Mean player ping", srv, s.pingAvgMs());
        gauge(b, "mc_player_ping_max_ms", "Worst player ping", srv, s.pingMaxMs());

        // ---- per world ------------------------------------------------------
        if (!s.worlds().isEmpty()) {
            help(b, "mc_world_entities", "gauge", "Entities per world");
            for (Snapshot.WorldStat w : s.worlds()) worldLine(b, "mc_world_entities", srv, w.name(), w.entities());
            help(b, "mc_world_tile_entities", "gauge", "Tile entities per world");
            for (Snapshot.WorldStat w : s.worlds()) worldLine(b, "mc_world_tile_entities", srv, w.name(), w.tileEntities());
            help(b, "mc_world_chunks", "gauge", "Loaded chunks per world");
            for (Snapshot.WorldStat w : s.worlds()) worldLine(b, "mc_world_chunks", srv, w.name(), w.chunks());
            help(b, "mc_world_players", "gauge", "Players per world");
            for (Snapshot.WorldStat w : s.worlds()) worldLine(b, "mc_world_players", srv, w.name(), w.players());
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
        help(b, "mc_jvm_memory_bytes_used", "gauge", "JVM memory in use");
        areaLine(b, "mc_jvm_memory_bytes_used", srv, "heap", j.heapUsed());
        areaLine(b, "mc_jvm_memory_bytes_used", srv, "nonheap", j.nonHeapUsed());
        help(b, "mc_jvm_memory_bytes_committed", "gauge", "JVM memory committed by the OS");
        areaLine(b, "mc_jvm_memory_bytes_committed", srv, "heap", j.heapCommitted());
        areaLine(b, "mc_jvm_memory_bytes_committed", srv, "nonheap", j.nonHeapCommitted());
        help(b, "mc_jvm_memory_bytes_max", "gauge", "JVM memory ceiling (-1 when unbounded)");
        areaLine(b, "mc_jvm_memory_bytes_max", srv, "heap", j.heapMax());
        areaLine(b, "mc_jvm_memory_bytes_max", srv, "nonheap", j.nonHeapMax());
        help(b, "mc_jvm_memory_bytes_init", "gauge", "JVM memory requested at startup");
        areaLine(b, "mc_jvm_memory_bytes_init", srv, "heap", j.heapInit());
        areaLine(b, "mc_jvm_memory_bytes_init", srv, "nonheap", j.nonHeapInit());

        gauge(b, "mc_jvm_threads_current", "Live threads", srv, j.threadsCurrent());
        gauge(b, "mc_jvm_threads_daemon", "Live daemon threads", srv, j.threadsDaemon());
        gauge(b, "mc_jvm_threads_peak", "Peak live threads since start", srv, j.threadsPeak());
        counter(b, "mc_jvm_threads_started_total", "Threads started since JVM start", srv, j.threadsStarted());
        gauge(b, "mc_jvm_classes_loaded", "Classes currently loaded", srv, j.classesLoaded());

        if (!j.gcs().isEmpty()) {
            help(b, "mc_jvm_gc_collections_total", "counter", "GC cycles by collector");
            for (Snapshot.Gc g : j.gcs()) gcLine(b, "mc_jvm_gc_collections_total", srv, g.name(), g.count());
            help(b, "mc_jvm_gc_seconds_total", "counter", "Time spent in GC by collector");
            for (Snapshot.Gc g : j.gcs()) gcLine(b, "mc_jvm_gc_seconds_total", srv, g.name(), g.seconds());
        }

        // ---- process --------------------------------------------------------
        Snapshot.Proc p = s.proc();
        gauge(b, "mc_process_cpu_load_ratio", "CPU load of the server process, 0-1", srv, p.processCpuRatio());
        gauge(b, "mc_system_cpu_load_ratio", "CPU load of the whole host, 0-1", srv, p.systemCpuRatio());
        counter(b, "mc_process_cpu_seconds_total", "CPU seconds consumed by the process", srv, p.processCpuSeconds());
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

    private static void counter(StringBuilder b, String name, String doc, String srv, double v) {
        help(b, name, "counter", doc);
        b.append(name).append("{server=\"").append(srv).append("\"} ").append(num(v)).append('\n');
    }

    private static void worldLine(StringBuilder b, String name, String srv, String world, double v) {
        b.append(name).append("{server=\"").append(srv).append("\",world=\"")
         .append(esc(world)).append("\"} ").append(num(v)).append('\n');
    }

    private static void areaLine(StringBuilder b, String name, String srv, String area, double v) {
        b.append(name).append("{server=\"").append(srv).append("\",area=\"")
         .append(area).append("\"} ").append(num(v)).append('\n');
    }

    private static void gcLine(StringBuilder b, String name, String srv, String gc, double v) {
        b.append(name).append("{server=\"").append(srv).append("\",gc=\"")
         .append(esc(gc)).append("\"} ").append(num(v)).append('\n');
    }

    /** Locale.ROOT matters: a comma decimal separator would corrupt the exposition. */
    private static String num(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        return String.format(Locale.ROOT, "%.4f", v);
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
