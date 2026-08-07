/*
 * ZimbriMetrics - a Prometheus exporter for Paper servers.
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
package cc.zimbri.metrics;

import java.util.List;
import java.util.Map;

/**
 * One immutable sample set. The main-thread collector publishes these; HTTP threads
 * only ever read the latest one, so a scrape never touches the server thread.
 */
record Snapshot(
        String serverId,
        double collectionSeconds,
        Ticks ticks,
        double[] tps,
        int playersOnline,
        int playersMax,
        int plugins,
        double pingAvgMs,
        int pingMaxMs,
        List<WorldStat> worlds,
        List<TypeCount> entityTypes,
        Jvm jvm,
        Proc proc,
        Map<String, Long> events) {

    record Ticks(double avgMs, double minMs, double maxMs,
                 double p50Ms, double p95Ms, double p99Ms, int samples) {
        static final Ticks EMPTY = new Ticks(0, 0, 0, 0, 0, 0, 0);
    }

    record WorldStat(String name, int entities, int tileEntities, int chunks, int players) {}

    record TypeCount(String world, String type, int count) {}

    record Gc(String name, long count, double seconds) {}

    record Jvm(long heapUsed, long heapCommitted, long heapMax, long heapInit,
               long nonHeapUsed, long nonHeapCommitted, long nonHeapMax, long nonHeapInit,
               int threadsCurrent, int threadsDaemon, int threadsPeak, long threadsStarted,
               long classesLoaded, List<Gc> gcs) {}

    record Proc(double processCpuRatio, double systemCpuRatio,
                double processCpuSeconds, double startTimeSeconds, double uptimeSeconds) {}

    static Snapshot empty(String serverId) {
        return new Snapshot(serverId, 0d, Ticks.EMPTY, new double[]{0, 0, 0},
                0, 0, 0, 0d, 0, List.of(), List.of(),
                new Jvm(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, List.of()),
                new Proc(0, 0, 0, 0, 0), Map.of());
    }
}
