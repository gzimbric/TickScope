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

import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Collects player-owned values without ever reading a player from another Folia region. */
final class FoliaPlayerSampler {

    private static final String[] WINDOWS = {"5s", "15s", "1m", "5m", "15m"};
    private static final double MILLIS_PER_SECOND = 1_000d;

    private final PlatformScheduler scheduler;
    private final Method getRegionTps;
    private final Method getRegionMspt;

    FoliaPlayerSampler(PlatformScheduler scheduler) {
        this.scheduler = scheduler;
        this.getRegionTps = findRegionMethod("getRegionTPS");
        this.getRegionMspt = findRegionMethod("getRegionAverageTickTimes");
    }

    void sample(Collection<? extends Player> currentPlayers,
                Consumer<MetricsCollector.PlayerSample> completed) {
        List<? extends Player> players = new ArrayList<>(currentPlayers);
        if (players.isEmpty()) {
            completed.accept(MetricsCollector.PlayerSample.EMPTY);
            return;
        }

        Accumulator accumulator = new Accumulator(players.size(), completed);
        for (Player player : players) {
            AtomicBoolean finished = new AtomicBoolean();
            Runnable retired = () -> accumulator.retired(finished);
            try {
                boolean accepted = scheduler.executeFor(player,
                        () -> accumulator.record(player, readRegionMetrics(player), finished),
                        retired);
                if (!accepted) retired.run();
            } catch (RuntimeException e) {
                retired.run();
            }
        }
    }

    private RegionMetrics readRegionMetrics(Player player) {
        Location location = player.getLocation();
        return new RegionMetrics(invokeRegionMethod(getRegionTps, player, location),
                invokeRegionMethod(getRegionMspt, player, location));
    }

    private static double[] invokeRegionMethod(Method method, Player player, Location location) {
        if (method == null) return null;
        try {
            return (double[]) method.invoke(player.getServer(), location);
        } catch (IllegalAccessException | InvocationTargetException e) {
            return null;
        }
    }

    private static Method findRegionMethod(String name) {
        try {
            return Server.class.getMethod(name, Location.class);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private record RegionMetrics(double[] tps, double[] mspt) {}

    private static final class Accumulator {
        private final int players;
        private final AtomicInteger remaining;
        private final AtomicInteger nextSlot = new AtomicInteger();
        private final AtomicInteger pingSamples = new AtomicInteger();
        private final AtomicLong pingSum = new AtomicLong();
        private final AtomicInteger pingMax = new AtomicInteger();
        private final double[][] regionTps;
        private final boolean[][] regionPresent;
        private final double[][] regionMspt;
        private final boolean[][] regionMsptPresent;
        private final Consumer<MetricsCollector.PlayerSample> completed;

        private Accumulator(int players, Consumer<MetricsCollector.PlayerSample> completed) {
            this.players = players;
            this.remaining = new AtomicInteger(players);
            this.regionTps = new double[WINDOWS.length][players];
            this.regionPresent = new boolean[WINDOWS.length][players];
            this.regionMspt = new double[WINDOWS.length][players];
            this.regionMsptPresent = new boolean[WINDOWS.length][players];
            this.completed = completed;
        }

        private void record(Player player, RegionMetrics metrics, AtomicBoolean finished) {
            if (!finished.compareAndSet(false, true)) return;
            try {
                int slot = nextSlot.getAndIncrement();
                int ping = Math.max(0, player.getPing());
                pingSum.addAndGet(ping);
                pingMax.accumulateAndGet(ping, Math::max);
                pingSamples.incrementAndGet();
                if (metrics.tps() != null) {
                    for (int i = 0; i < WINDOWS.length && i < metrics.tps().length; i++) {
                        regionTps[i][slot] = metrics.tps()[i];
                        regionPresent[i][slot] = true;
                    }
                }
                if (metrics.mspt() != null) {
                    for (int i = 0; i < WINDOWS.length && i < metrics.mspt().length; i++) {
                        regionMspt[i][slot] = metrics.mspt()[i];
                        regionMsptPresent[i][slot] = true;
                    }
                }
            } finally {
                finishOne();
            }
        }

        private void retired(AtomicBoolean finished) {
            if (finished.compareAndSet(false, true)) finishOne();
        }

        private void finishOne() {
            if (remaining.decrementAndGet() != 0) return;
            List<Snapshot.RegionTps> regions = new ArrayList<>();
            List<Snapshot.RegionTickDuration> tickDurations = new ArrayList<>();
            for (int window = 0; window < WINDOWS.length; window++) {
                int count = 0;
                double sum = 0;
                double min = Double.POSITIVE_INFINITY;
                double max = Double.NEGATIVE_INFINITY;
                for (int player = 0; player < players; player++) {
                    if (!regionPresent[window][player]) continue;
                    double value = regionTps[window][player];
                    count++;
                    sum += value;
                    min = Math.min(min, value);
                    max = Math.max(max, value);
                }
                if (count > 0) {
                    regions.add(new Snapshot.RegionTps(
                            WINDOWS[window], count, min, sum / count, max));
                }

                count = 0;
                sum = 0;
                min = Double.POSITIVE_INFINITY;
                max = Double.NEGATIVE_INFINITY;
                for (int player = 0; player < players; player++) {
                    if (!regionMsptPresent[window][player]) continue;
                    double value = regionMspt[window][player];
                    count++;
                    sum += value;
                    min = Math.min(min, value);
                    max = Math.max(max, value);
                }
                if (count > 0) {
                    tickDurations.add(new Snapshot.RegionTickDuration(
                            WINDOWS[window], count,
                            min / MILLIS_PER_SECOND,
                            sum / count / MILLIS_PER_SECOND,
                            max / MILLIS_PER_SECOND));
                }
            }
            int successfulPings = pingSamples.get();
            completed.accept(new MetricsCollector.PlayerSample(players,
                    successfulPings == 0 ? 0d
                            : (double) pingSum.get() / successfulPings / MILLIS_PER_SECOND,
                    pingMax.get() / MILLIS_PER_SECOND, successfulPings,
                    List.copyOf(regions), List.copyOf(tickDurations)));
        }
    }
}
