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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One immutable sample set. A platform-owned collector publishes these; HTTP threads only ever
 * read the latest one, so a scrape never touches a Paper main thread or Folia region thread.
 *
 * <p>Readings that the platform cannot supply are represented as {@link Double#NaN} rather than
 * zero, and the writer omits those series entirely. A JVM that cannot report CPU load should not
 * be indistinguishable from an idle one.
 */
record Snapshot(
        String serverId,
        String tickScopeVersion,
        String paperVersion,
        String javaVersion,
        String platform,
        double collectionSeconds,
        double entityCollectionSeconds,
        Ticks ticks,
        double[] tps,
        int playersOnline,
        int playersMax,
        int plugins,
        double pingAverageSeconds,
        double pingMaximumSeconds,
        int pingSamples,
        List<WorldStat> worlds,
        List<WorldTotals> worldTotals,
        List<TypeCount> entityTypes,
        List<RegionTps> regionTps,
        List<RegionTickDuration> regionTickDurations,
        Jvm jvm,
        Proc proc,
        Map<String, Long> events) {

    Snapshot {
        tps = tps.clone();
        worlds = List.copyOf(worlds);
        worldTotals = List.copyOf(worldTotals);
        entityTypes = List.copyOf(entityTypes);
        regionTps = List.copyOf(regionTps);
        regionTickDurations = List.copyOf(regionTickDurations);
        events = Collections.unmodifiableMap(new LinkedHashMap<>(events));
    }

    @Override
    public double[] tps() {
        return tps.clone();
    }

    record Ticks(double averageSeconds, double minimumSeconds, double maximumSeconds,
                 double p50Seconds, double p95Seconds, double p99Seconds, int samples) {
        static final Ticks EMPTY = new Ticks(0, 0, 0, 0, 0, 0, 0);
    }

    /** Per-world readings that are genuine O(1) counter reads and can be sampled every cycle. */
    record WorldStat(String name, int chunks, int players) {}

    /**
     * Per-world readings that are not counter reads. Paper's getTileEntityCount iterates every
     * visible chunk holder, and before 26.x getEntityCount walked every entity in the world, so
     * these are sampled on the slower scan cadence rather than every collection.
     */
    record WorldTotals(String name, int entities, int tileEntities) {}

    record TypeCount(String world, String type, int count) {}

    /** Folia TPS summaries sampled at player-owned regions; samples may share a region. */
    record RegionTps(String window, int samples, double min, double avg, double max) {}

    /** Folia tick-duration summaries sampled at player-owned regions; samples may share a region. */
    record RegionTickDuration(String window, int samples,
                              double minimumSeconds, double averageSeconds,
                              double maximumSeconds) {}

    record Gc(String name, long count, double seconds) {}

    record Jvm(long heapUsed, long heapCommitted, long heapMax, long heapInit,
               long nonHeapUsed, long nonHeapCommitted, long nonHeapMax, long nonHeapInit,
               int threadsCurrent, int threadsDaemon, int threadsPeak, long threadsStarted,
               long classesLoaded, List<Gc> gcs) {}

    /** CPU readings are NaN when the platform cannot supply them. */
    record Proc(double processCpuRatio, double systemCpuRatio,
                double processCpuSeconds, double startTimeSeconds, double uptimeSeconds) {}

    static Snapshot empty(String serverId, String tickScopeVersion,
                          String paperVersion, String javaVersion) {
        return empty(serverId, tickScopeVersion, paperVersion, javaVersion, "paper");
    }

    static Snapshot empty(String serverId, String tickScopeVersion,
                          String paperVersion, String javaVersion, String platform) {
        return new Snapshot(serverId, tickScopeVersion, paperVersion, javaVersion,
                platform,
                0d, 0d, Ticks.EMPTY, new double[]{0, 0, 0},
                0, 0, 0, 0d, 0d, 0,
                List.of(), List.of(), List.of(), List.of(), List.of(),
                new Jvm(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, List.of()),
                new Proc(0, 0, 0, 0, 0), Map.of());
    }
}
