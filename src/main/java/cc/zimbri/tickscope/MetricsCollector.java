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

import com.sun.management.OperatingSystemMXBean;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
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
import java.util.Locale;
import java.util.Map;

/**
 * Gathers a {@link Snapshot}. Bukkit reads happen on the owning platform scheduler: Paper's main
 * thread or Folia's global region. Player-owned Folia reads are supplied by
 * {@link FoliaPlayerSampler}; an HTTP thread never calls this class.
 */
final class MetricsCollector {

    private static final double NANOS_PER_SEC = 1_000_000_000d;
    private static final double NANOS_PER_MS = 1_000_000d;

    private final String serverId;
    private final String tickScopeVersion;
    private final String paperVersion;
    private final String javaVersion;
    private final String platform;
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
    private volatile double entityCollectionSeconds;
    private volatile PlayerSample foliaPlayers = PlayerSample.EMPTY;

    MetricsCollector(String serverId, boolean perWorld, EventCounters events,
                     String tickScopeVersion, String paperVersion, String javaVersion,
                     String platform) {
        this.serverId = serverId;
        this.perWorld = perWorld;
        this.events = events;
        this.tickScopeVersion = tickScopeVersion;
        this.paperVersion = paperVersion;
        this.javaVersion = javaVersion;
        this.platform = platform;
    }

    Snapshot collectPaper() {
        return collect(false);
    }

    Snapshot collectFolia() {
        return collect(true);
    }

    Snapshot initialSnapshot() {
        return Snapshot.empty(serverId, tickScopeVersion, paperVersion, javaVersion, platform);
    }

    void updateFoliaPlayers(PlayerSample players) {
        foliaPlayers = players;
    }

    private Snapshot collect(boolean folia) {
        long started = System.nanoTime();

        // Folia has no single server tick. Publishing its global-region tick history as server
        // MSPT would be technically valid data with a misleading meaning, so those Paper-only
        // series are deliberately absent. Region TPS and MSPT are sampled separately at player
        // locations when the server exposes the corresponding regional APIs.
        Snapshot.Ticks ticks = folia ? Snapshot.Ticks.EMPTY : summarise(Bukkit.getTickTimes());
        double[] tps = folia ? new double[0] : Bukkit.getTPS();

        List<Snapshot.WorldStat> worlds = new ArrayList<>();
        if (perWorld) {
            for (World w : Bukkit.getWorlds()) {
                // Paper maintains these as counters, so they are O(1) — no entity walk.
                worlds.add(new Snapshot.WorldStat(w.getName(), w.getEntityCount(),
                        w.getTileEntityCount(), w.getChunkCount(), w.getPlayerCount()));
            }
        }

        PlayerSample playerSample = folia ? foliaPlayers : paperPlayers();

        Snapshot.Jvm jvm = jvm();
        Snapshot.Proc proc = proc();
        Map<String, Long> eventCounts = events.snapshot();
        double collectionSeconds = (System.nanoTime() - started) / NANOS_PER_SEC;
        return new Snapshot(serverId, tickScopeVersion, paperVersion, javaVersion, platform,
                collectionSeconds, entityCollectionSeconds, ticks, tps,
                playerSample.online(), Bukkit.getMaxPlayers(),
                Bukkit.getPluginManager().getPlugins().length,
                playerSample.pingAvgMs(), playerSample.pingMaxMs(),
                List.copyOf(worlds), entityTypes, playerSample.regionTps(),
                playerSample.regionMspt(),
                jvm, proc, eventCounts);
    }

    private PlayerSample paperPlayers() {
        int online = 0;
        long pingSum = 0;
        int pingMax = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            online++;
            int ping = Math.max(0, player.getPing());
            pingSum += ping;
            pingMax = Math.max(pingMax, ping);
        }
        return new PlayerSample(online, online == 0 ? 0d : (double) pingSum / online,
                pingMax, List.of(), List.of());
    }

    /** Separate cadence: this one is O(entities), unlike everything in collect(). */
    void collectEntityTypes() {
        long started = System.nanoTime();
        List<Snapshot.TypeCount> out = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) {
            Map<String, Integer> byType = new HashMap<>();
            for (Entity e : w.getEntities()) {
                byType.merge(typeName(e.getType()), 1, Integer::sum);
            }
            byType.forEach((type, n) -> out.add(new Snapshot.TypeCount(w.getName(), type, n)));
        }
        entityTypes = List.copyOf(out);
        entityCollectionSeconds = (System.nanoTime() - started) / NANOS_PER_SEC;
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
        m.put("platform", platform);
        m.put("per-world", String.valueOf(perWorld));
        m.put("entity-type-series", String.valueOf(entityTypes.size()));
        return m;
    }

    record PlayerSample(int online, double pingAvgMs, int pingMaxMs,
                        List<Snapshot.RegionTps> regionTps,
                        List<Snapshot.RegionMspt> regionMspt) {
        static final PlayerSample EMPTY = new PlayerSample(
                0, 0d, 0, List.of(), List.of());

        PlayerSample {
            regionTps = List.copyOf(regionTps);
            regionMspt = List.copyOf(regionMspt);
        }
    }

    /**
     * The Minecraft registry name rather than the Bukkit enum name. Bukkit realigned the
     * EntityType enum with the registry in 1.20.5, renaming several constants — ENDER_CRYSTAL
     * became END_CRYSTAL — so enum names label the same entity differently depending on the
     * server version, and a dashboard query silently stops matching. The registry key is stable
     * across every supported version. EntityType.UNKNOWN has no key, hence the fallback.
     */
    private static String typeName(EntityType t) {
        try {
            return t.getKey().getKey();
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            return t.name().toLowerCase(Locale.ROOT);
        }
    }
}
