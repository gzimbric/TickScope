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
    private static final double MILLIS_PER_SECOND = 1_000d;

    private final String serverId;
    private final String tickScopeVersion;
    private final String paperVersion;
    private final String javaVersion;
    private final String platform;
    private final boolean perWorld;
    private final boolean byType;
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
     * Everything that needs to walk the world rather than read a counter, kept on its own
     * slower cadence. It is carried across a reload so that reconfiguring does not blank the
     * series until the next scan.
     */
    private volatile HeavyWorldData heavy;
    private volatile PlayerSample foliaPlayers = PlayerSample.EMPTY;

    MetricsCollector(String serverId, boolean perWorld, boolean byType, EventCounters events,
                     String tickScopeVersion, String paperVersion, String javaVersion,
                     String platform, HeavyWorldData carried) {
        this.serverId = serverId;
        this.perWorld = perWorld;
        this.byType = byType;
        this.events = events;
        this.tickScopeVersion = tickScopeVersion;
        this.paperVersion = paperVersion;
        this.javaVersion = javaVersion;
        this.platform = platform;
        this.heavy = carried == null ? HeavyWorldData.EMPTY : carried;
    }

    Snapshot collectPaper() {
        return collect(false, 0);
    }

    /**
     * @param onlinePlayers read on the global region, because the per-player sample that
     *                      supplies ping only completes after this snapshot is published.
     */
    Snapshot collectFolia(int onlinePlayers) {
        return collect(true, onlinePlayers);
    }

    Snapshot initialSnapshot() {
        return Snapshot.empty(serverId, tickScopeVersion, paperVersion, javaVersion, platform);
    }

    void updateFoliaPlayers(PlayerSample players) {
        foliaPlayers = players;
    }

    /** The cached scan results, so a replacement collector can start where this one left off. */
    HeavyWorldData heavyWorldData() {
        return heavy;
    }

    private Snapshot collect(boolean folia, int foliaOnlinePlayers) {
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
                // Only genuine counter reads belong here: getFullChunksCount and the world's
                // own player list. Entity and tile-entity totals iterate, so they live on the
                // slower scan instead.
                worlds.add(new Snapshot.WorldStat(w.getName(), w.getChunkCount(), w.getPlayerCount()));
            }
        }

        PlayerSample playerSample = folia ? foliaPlayers : paperPlayers();
        HeavyWorldData scan = heavy;

        Snapshot.Jvm jvm = jvm();
        Snapshot.Proc proc = proc();
        Map<String, Long> eventCounts = events.snapshot();
        double collectionSeconds = (System.nanoTime() - started) / NANOS_PER_SEC;
        return new Snapshot(serverId, tickScopeVersion, paperVersion, javaVersion, platform,
                collectionSeconds, scan.seconds(), ticks, tps,
                folia ? foliaOnlinePlayers : playerSample.online(), Bukkit.getMaxPlayers(),
                Bukkit.getPluginManager().getPlugins().length,
                playerSample.pingAverageSeconds(), playerSample.pingMaximumSeconds(),
                playerSample.pingSamples(),
                List.copyOf(worlds), scan.totals(), scan.types(),
                playerSample.regionTps(), playerSample.regionTickDurations(),
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
        return new PlayerSample(online,
                online == 0 ? 0d : (double) pingSum / online / MILLIS_PER_SECOND,
                pingMax / MILLIS_PER_SECOND, online, List.of(), List.of());
    }

    /**
     * The readings that cost more than a counter read, on their own cadence.
     *
     * <p>Paper's {@code getTileEntityCount()} iterates every visible chunk holder, and before
     * 26.x {@code getEntityCount()} walked every entity in the world, so neither is the O(1)
     * counter this once assumed. The entity-type breakdown is O(entities) by nature. All three
     * are gathered here, well away from the collection interval.
     */
    void collectHeavyWorldData() {
        long started = System.nanoTime();
        List<Snapshot.WorldTotals> totals = new ArrayList<>();
        List<Snapshot.TypeCount> types = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) {
            if (perWorld) {
                totals.add(new Snapshot.WorldTotals(
                        w.getName(), w.getEntityCount(), w.getTileEntityCount()));
            }
            if (byType) {
                Map<String, Integer> byTypeName = new HashMap<>();
                for (Entity e : w.getEntities()) {
                    byTypeName.merge(typeName(e.getType()), 1, Integer::sum);
                }
                byTypeName.forEach((type, n) -> types.add(new Snapshot.TypeCount(w.getName(), type, n)));
            }
        }
        heavy = new HeavyWorldData(List.copyOf(totals), List.copyOf(types),
                (System.nanoTime() - started) / NANOS_PER_SEC);
    }

    private Snapshot.Jvm jvm() {
        MemoryUsage heapUsage = memory.getHeapMemoryUsage();
        MemoryUsage non = memory.getNonHeapMemoryUsage();
        List<Snapshot.Gc> gcs = new ArrayList<>(gcBeans.size());
        for (GarbageCollectorMXBean gc : gcBeans) {
            long count = gc.getCollectionCount();
            long millis = gc.getCollectionTime();
            // -1 means this collector does not report the value. A counter that is missing
            // must not be published as a real counter sitting at zero.
            if (count < 0L) continue;
            gcs.add(new Snapshot.Gc(gc.getName(), count,
                    millis < 0L ? Double.NaN : millis / 1000d));
        }
        return new Snapshot.Jvm(
                heapUsage.getUsed(), heapUsage.getCommitted(), heapUsage.getMax(), heapUsage.getInit(),
                non.getUsed(), non.getCommitted(), non.getMax(), non.getInit(),
                threads.getThreadCount(), threads.getDaemonThreadCount(),
                threads.getPeakThreadCount(), threads.getTotalStartedThreadCount(),
                classes.getLoadedClassCount(), List.copyOf(gcs));
    }

    private Snapshot.Proc proc() {
        return new Snapshot.Proc(ratio(os.getProcessCpuLoad()), ratio(os.getCpuLoad()),
                cpuSeconds(os.getProcessCpuTime()),
                runtime.getStartTime() / 1000d, runtime.getUptime() / 1000d);
    }

    /**
     * These beans report a negative value, and OpenJ9 reports -1 on the first call, when a
     * reading is unavailable. Clamping that to zero made an unsupported platform look like a
     * completely idle one; NaN keeps the series out of the exposition instead.
     */
    static double ratio(double value) {
        return Double.isNaN(value) || value < 0d ? Double.NaN : value;
    }

    static double cpuSeconds(long nanos) {
        return nanos < 0L ? Double.NaN : nanos / NANOS_PER_SEC;
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
                sum / n / NANOS_PER_SEC,
                v[0] / NANOS_PER_SEC,
                v[n - 1] / NANOS_PER_SEC,
                quantile(v, 0.50) / NANOS_PER_SEC,
                quantile(v, 0.95) / NANOS_PER_SEC,
                quantile(v, 0.99) / NANOS_PER_SEC,
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
        m.put("entity-type-series", String.valueOf(heavy.types().size()));
        return m;
    }

    /** Results of the slow world scan, kept together so they can survive a reload as a unit. */
    record HeavyWorldData(List<Snapshot.WorldTotals> totals, List<Snapshot.TypeCount> types,
                          double seconds) {
        static final HeavyWorldData EMPTY = new HeavyWorldData(List.of(), List.of(), 0d);

        HeavyWorldData {
            totals = List.copyOf(totals);
            types = List.copyOf(types);
        }
    }

    record PlayerSample(int online, double pingAverageSeconds, double pingMaximumSeconds,
                        int pingSamples, List<Snapshot.RegionTps> regionTps,
                        List<Snapshot.RegionTickDuration> regionTickDurations) {
        static final PlayerSample EMPTY = new PlayerSample(
                0, 0d, 0d, 0, List.of(), List.of());

        PlayerSample {
            regionTps = List.copyOf(regionTps);
            regionTickDurations = List.copyOf(regionTickDurations);
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
