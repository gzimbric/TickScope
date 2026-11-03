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

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Buckets every server tick as it ends, so {@code histogram_quantile} works across any window
 * and across servers.
 *
 * <p>The existing statistic gauges are exact but describe one five-second window per sample;
 * this is the cumulative distribution instead. It is fed by Paper's per-tick event rather than
 * by re-reading {@code Bukkit.getTickTimes()}: the array's length and indexing are not part of
 * the API and changed in 1.21, so no sampling cadence could promise to see each tick exactly
 * once. The event fires once per tick on the main thread, and costs a bucket lookup.
 *
 * <p>Paper only. Folia has no single server tick, and publishing its global region here would
 * be the misleading figure the server-wide gauges already refuse to be.
 */
final class TickHistogram implements Listener {

    private final long[] counts = new long[Snapshot.TickHistogram.BOUNDS.length + 1];
    private double sumSeconds;
    private long count;

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTickEnd(ServerTickEndEvent e) {
        record(e.getTickDuration() / 1000d);
    }

    /** The event thread and the collector are both the main thread; the lock is for safety. */
    synchronized void record(double seconds) {
        double[] bounds = Snapshot.TickHistogram.BOUNDS;
        int bucket = 0;
        while (bucket < bounds.length && seconds > bounds[bucket]) bucket++;
        counts[bucket]++;
        sumSeconds += seconds;
        count++;
    }

    synchronized Snapshot.TickHistogram snapshot() {
        long[] cumulative = new long[counts.length];
        long running = 0;
        for (int i = 0; i < counts.length; i++) {
            running += counts[i];
            cumulative[i] = running;
        }
        return new Snapshot.TickHistogram(cumulative, sumSeconds, count);
    }
}
