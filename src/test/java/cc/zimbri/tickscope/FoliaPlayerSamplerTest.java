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
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FoliaPlayerSamplerTest {

    @Test
    void emptyPlayerListCompletesSynchronously() {
        ControlledScheduler scheduler = new ControlledScheduler();
        AtomicReference<MetricsCollector.PlayerSample> result = new AtomicReference<>();

        new FoliaPlayerSampler(scheduler).sample(List.of(), result::set);

        assertEquals(MetricsCollector.PlayerSample.EMPTY, result.get());
        assertTrue(scheduler.pending.isEmpty());
    }

    @Test
    void retiredPlayerCompletesTheBatchAndCannotCompleteTwice() {
        ControlledScheduler scheduler = new ControlledScheduler();
        AtomicReference<MetricsCollector.PlayerSample> result = new AtomicReference<>();
        AtomicInteger completions = new AtomicInteger();

        new FoliaPlayerSampler(scheduler).sample(
                List.of(player(12), player(40)),
                sample -> {
                    result.set(sample);
                    completions.incrementAndGet();
                });

        assertNull(result.get());
        assertEquals(2, scheduler.pending.size());

        scheduler.pending.get(0).task.run();
        assertNull(result.get());
        scheduler.pending.get(1).retired.run();

        MetricsCollector.PlayerSample sample = result.get();
        assertNotNull(sample);
        assertEquals(1, completions.get());
        assertEquals(2, sample.online());
        assertEquals(12d, sample.pingAvgMs());
        assertEquals(12, sample.pingMaxMs());
        assertTrue(sample.regionTps().isEmpty());
        assertTrue(sample.regionMspt().isEmpty());

        scheduler.pending.get(1).retired.run();
        scheduler.pending.get(1).task.run();
        assertEquals(1, completions.get());
    }

    @Test
    void rejectedAndThrowingSchedulesStillCompleteTheBatch() {
        PlatformScheduler scheduler = new PlatformScheduler() {
            private int calls;

            @Override
            public TaskHandle repeatGlobal(Runnable task, long delay, long period) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean executeFor(Player player, Runnable task, Runnable retired) {
                if (calls++ == 0) return false;
                throw new IllegalStateException("player retired during scheduling");
            }

            @Override
            public boolean isFolia() {
                return true;
            }
        };
        AtomicReference<MetricsCollector.PlayerSample> result = new AtomicReference<>();

        new FoliaPlayerSampler(scheduler).sample(
                List.of(player(10), player(20)), result::set);

        MetricsCollector.PlayerSample sample = result.get();
        assertNotNull(sample);
        assertEquals(2, sample.online());
        assertEquals(0d, sample.pingAvgMs());
        assertEquals(0, sample.pingMaxMs());
        assertTrue(sample.regionTps().isEmpty());
        assertTrue(sample.regionMspt().isEmpty());
    }


    @Test
    void expiresPartialBatchesAndBoundsQueuedWorkPerPlayer() {
        ControlledScheduler scheduler = new ControlledScheduler();
        var sampler = new FoliaPlayerSampler(scheduler);
        Player responsive = player(12), lagging = player(40);
        var first = new AtomicReference<MetricsCollector.PlayerSample>();
        var second = new AtomicReference<MetricsCollector.PlayerSample>();
        sampler.sample(List.of(responsive, lagging), first::set);
        scheduler.pending.get(0).task.run();
        sampler.sample(List.of(responsive, lagging), second::set);
        assertEquals(1, first.get().pingSamples());
        assertEquals(2, first.get().online());
        assertEquals(3, scheduler.pending.size(), "Lagging player's second task must not be queued");
        scheduler.pending.get(2).task.run();
        assertEquals(1, second.get().pingSamples());
        scheduler.pending.get(1).task.run(); // Expired callback releases its ticket only.
        assertEquals(1, first.get().pingSamples());
        sampler.sample(List.of(responsive, lagging), ignored -> {});
        assertEquals(5, scheduler.pending.size(), "Player can be scheduled again after recovery");
        sampler.close();
        sampler.sample(List.of(responsive, lagging), ignored -> { throw new AssertionError(); });
        assertEquals(5, scheduler.pending.size());
    }

    @Test
    void manyCyclesDoNotQueueMoreWorkBehindAStalledPlayer() {
        ControlledScheduler scheduler = new ControlledScheduler();
        var sampler = new FoliaPlayerSampler(scheduler);
        Player lagging = player(40);
        var results = new ArrayList<MetricsCollector.PlayerSample>();
        for (int i = 0; i < 100; i++) sampler.sample(List.of(lagging), results::add);
        assertEquals(1, scheduler.pending.size());
        assertEquals(100, results.size());
        assertEquals(0, results.get(99).pingSamples());
        sampler.close();
    }

    private static Player player(int ping) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getPing" -> ping;
                    case "getLocation" -> new Location(null, 0, 0, 0);
                    case "toString" -> "TestPlayer(" + ping + ")";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == char.class) return '\0';
        throw new AssertionError("Unhandled primitive: " + type);
    }

    private static final class ControlledScheduler implements PlatformScheduler {
        private final List<Pending> pending = new ArrayList<>();

        @Override
        public TaskHandle repeatGlobal(Runnable task, long delay, long period) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean executeFor(Player player, Runnable task, Runnable retired) {
            pending.add(new Pending(task, retired));
            return true;
        }

        @Override
        public boolean isFolia() {
            return true;
        }
    }

    private record Pending(Runnable task, Runnable retired) {}
}
