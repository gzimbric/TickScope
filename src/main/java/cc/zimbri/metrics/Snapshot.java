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
