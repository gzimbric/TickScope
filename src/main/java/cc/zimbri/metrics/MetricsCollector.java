package cc.zimbri.metrics;

import com.sun.management.OperatingSystemMXBean;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.lang.management.ClassLoadingMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gathers a {@link Snapshot}. Every Bukkit read here must happen on the main thread —
 * {@link #collect()} and {@link #collectEntityTypes()} are only ever called from
 * scheduler tasks, never from an HTTP thread.
 */
final class MetricsCollector {

    private static final double NANOS_PER_SEC = 1_000_000_000d;
    private static final double NANOS_PER_MS = 1_000_000d;

    private final String serverId;
    private final boolean perWorld;
    private final EventCounters events;

    private final OperatingSystemMXBean os =
            (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    private final ClassLoadingMXBean classes = ManagementFactory.getClassLoadingMXBean();
    private final RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
    private final List<GarbageCollectorMXBean> gcBeans =
            ManagementFactory.getGarbageCollectorMXBeans();

    /**
     * Entity-by-type is the only reading that needs a real iteration rather than a
     * Paper counter, so it runs on its own slower cadence and is cached here.
     */
    private volatile List<Snapshot.TypeCount> entityTypes = List.of();

    MetricsCollector(String serverId, boolean perWorld, EventCounters events) {
        this.serverId = serverId;
        this.perWorld = perWorld;
        this.events = events;
    }

    Snapshot collect() {
        long started = System.nanoTime();

        Snapshot.Ticks ticks = summarise(Bukkit.getTickTimes());
        double[] tps = Bukkit.getTPS();

        List<Snapshot.WorldStat> worlds = new ArrayList<>();
        if (perWorld) {
            for (World w : Bukkit.getWorlds()) {
                // Paper maintains these as counters, so they are O(1) — no entity walk.
                worlds.add(new Snapshot.WorldStat(w.getName(), w.getEntityCount(),
                        w.getTileEntityCount(), w.getChunkCount(), w.getPlayerCount()));
            }
        }

        int online = 0;
        long pingSum = 0;
        int pingMax = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            online++;
            int ping = p.getPing();
            pingSum += ping;
            if (ping > pingMax) pingMax = ping;
        }

        double collectionSeconds = (System.nanoTime() - started) / NANOS_PER_SEC;
        return new Snapshot(serverId, collectionSeconds, ticks, tps,
                online, Bukkit.getMaxPlayers(),
                Bukkit.getPluginManager().getPlugins().length,
                online == 0 ? 0d : (double) pingSum / online, pingMax,
                List.copyOf(worlds), entityTypes, jvm(), proc(), events.snapshot());
    }

    /** Separate cadence: this one is O(entities), unlike everything in collect(). */
    void collectEntityTypes() {
        List<Snapshot.TypeCount> out = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) {
            Map<String, Integer> byType = new HashMap<>();
            for (Entity e : w.getEntities()) {
                byType.merge(e.getType().name().toLowerCase(), 1, Integer::sum);
            }
            byType.forEach((type, n) -> out.add(new Snapshot.TypeCount(w.getName(), type, n)));
        }
        entityTypes = List.copyOf(out);
    }

    private Snapshot.Jvm jvm() {
        MemoryUsage heap = memory.getHeapMemoryUsage();
        MemoryUsage non = memory.getNonHeapMemoryUsage();
        List<Snapshot.Gc> gcs = new ArrayList<>(gcBeans.size());
        for (GarbageCollectorMXBean gc : gcBeans) {
            gcs.add(new Snapshot.Gc(gc.getName(), Math.max(0, gc.getCollectionCount()),
                    Math.max(0, gc.getCollectionTime()) / 1000d));
        }
        return new Snapshot.Jvm(
                heap.getUsed(), heap.getCommitted(), heap.getMax(), heap.getInit(),
                non.getUsed(), non.getCommitted(), non.getMax(), non.getInit(),
                threads.getThreadCount(), threads.getDaemonThreadCount(),
                threads.getPeakThreadCount(), threads.getTotalStartedThreadCount(),
                classes.getLoadedClassCount(), List.copyOf(gcs));
    }

    private Snapshot.Proc proc() {
        // These return -1 when the platform cannot supply them; clamp so the exposition
        // never carries a negative ratio.
        double procCpu = Math.max(0d, os.getProcessCpuLoad());
        double sysCpu = Math.max(0d, os.getCpuLoad());
        double procSeconds = Math.max(0L, os.getProcessCpuTime()) / NANOS_PER_SEC;
        return new Snapshot.Proc(procCpu, sysCpu, procSeconds,
                runtime.getStartTime() / 1000d, runtime.getUptime() / 1000d);
    }

    /**
     * Paper hands back the raw per-tick durations in nanoseconds, so the percentiles are
     * computed here rather than approximated — no histogram library needed. Unfilled
     * slots read as zero for the first few minutes and are dropped.
     */
    static Snapshot.Ticks summarise(long[] raw) {
        if (raw == null || raw.length == 0) return Snapshot.Ticks.EMPTY;
        long[] v = new long[raw.length];
        int n = 0;
        for (long x : raw) {
            if (x > 0) v[n++] = x;
        }
        if (n == 0) return Snapshot.Ticks.EMPTY;
        v = Arrays.copyOf(v, n);
        Arrays.sort(v);
        double sum = 0;
        for (long x : v) sum += x;
        return new Snapshot.Ticks(
                sum / n / NANOS_PER_MS,
                v[0] / NANOS_PER_MS,
                v[n - 1] / NANOS_PER_MS,
                quantile(v, 0.50) / NANOS_PER_MS,
                quantile(v, 0.95) / NANOS_PER_MS,
                quantile(v, 0.99) / NANOS_PER_MS,
                n);
    }

    private static double quantile(long[] sorted, double p) {
        int i = (int) Math.ceil(p * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, i))];
    }

    Map<String, String> describe() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("server-id", serverId);
        m.put("per-world", String.valueOf(perWorld));
        m.put("entity-type-series", String.valueOf(entityTypes.size()));
        return m;
    }
}
