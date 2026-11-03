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

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CollectorIntegrationTest {
    private Server previous;
    private List<World> worlds = List.of();
    private List<org.bukkit.entity.Player> online = List.of();
    private final AtomicBoolean fail = new AtomicBoolean();

    @BeforeEach void installServer() throws Exception {
        Field field = Bukkit.class.getDeclaredField("server"); field.setAccessible(true);
        previous = (Server) field.get(null);
        PluginManager manager = (PluginManager) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PluginManager.class}, (p, m, a) -> new Plugin[0]);
        Server server = (Server) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Server.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getWorlds" -> worlds;
                    case "getTickTimes" -> new long[]{1_000_000};
                    case "getTPS" -> new double[]{20, 20, 20};
                    case "getOnlinePlayers" -> online;
                    case "getMaxPlayers" -> { if (fail.get()) throw new IllegalStateException("probe"); yield 20; }
                    case "getPluginManager" -> manager;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        field.set(null, server);
    }
    @AfterEach void restoreServer() throws Exception {
        Field field = Bukkit.class.getDeclaredField("server"); field.setAccessible(true); field.set(null, previous);
    }
    private MetricsCollector collector(boolean perWorld, boolean byType, MetricsCollector.HeavyWorldData cache,
                                       Set<String> excluded, Set<String> allowed) {
        return new MetricsCollector("test", perWorld, byType, new EventCounters(), "test", "test", "17",
                "paper", cache, excluded, allowed);
    }
    @Test void disabledMetricsDisappearImmediatelyAndEnabledCacheSurvives() {
        var cache = new MetricsCollector.HeavyWorldData(List.of(new Snapshot.WorldTotals("world", 8, 2)),
                List.of(new Snapshot.TypeCount("world", "zombie", 8)), .001);
        for (boolean perWorld : List.of(false, true)) {
            for (boolean byType : List.of(false, true)) {
                var sample = collector(perWorld, byType, cache, Set.of(), Set.of()).collectPaper();
                assertEquals(perWorld ? cache.totals() : List.of(), sample.worldTotals());
                assertEquals(byType ? cache.types() : List.of(), sample.entityTypes());
            }
        }
        String rendered = PrometheusWriter.render(collector(false, false, cache, Set.of(), Set.of()).collectPaper());
        assertFalse(rendered.contains("mc_world_entities"));
    }
    @Test void excludedWorldIsNeverScannedAndAllowlistKeepsFullTotals() {
        Entity zombie = entity(EntityType.ZOMBIE), chicken = entity(EntityType.CHICKEN);
        worlds = List.of(world("lobby", true, List.of()), world("world", false, List.of(zombie, chicken)));
        var c = collector(true, true, null, Set.of("lobby"), Set.of("zombie"));
        c.collectHeavyWorldData();
        var sample = c.collectPaper();
        assertEquals(List.of(new Snapshot.WorldTotals("world", 2, 3)), sample.worldTotals());
        assertEquals(List.of(new Snapshot.TypeCount("world", "zombie", 1)), sample.entityTypes());
        assertEquals(1, sample.worlds().size());
        var narrowed = collector(true, true, c.heavyWorldData(), Set.of("world"), Set.of("chicken"));
        assertTrue(narrowed.heavyWorldData().totals().isEmpty());
        assertTrue(narrowed.heavyWorldData().types().isEmpty());
    }
    @Test void narrowingOnlyTheAllowlistKeepsCachedTotalsAndDropsCachedTypes() {
        var cache = new MetricsCollector.HeavyWorldData(List.of(new Snapshot.WorldTotals("world", 8, 2)),
                List.of(new Snapshot.TypeCount("world", "zombie", 5), new Snapshot.TypeCount("world", "chicken", 3)),
                .001);
        var narrowed = collector(true, true, cache, Set.of(), Set.of("chicken"));
        assertEquals(cache.totals(), narrowed.heavyWorldData().totals(), "totals do not depend on the allowlist");
        assertEquals(List.of(new Snapshot.TypeCount("world", "chicken", 3)), narrowed.heavyWorldData().types());
    }

    @Test void aFailingScanIsAbandonedCountedAndRetriedAfterTheInterval() {
        AtomicInteger listings = new AtomicInteger();
        AtomicBoolean broken = new AtomicBoolean(true);
        Chunk chunk = (Chunk) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Chunk.class}, (p, m, a) -> switch (m.getName()) {
                    case "isLoaded" -> true;
                    case "getEntities" -> {
                        if (broken.get()) throw new IllegalStateException("entity read failed");
                        yield new Entity[]{entity(EntityType.ZOMBIE)};
                    }
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        worlds = List.of((World) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{World.class}, (p, m, a) -> switch (m.getName()) {
                    case "getName" -> "world";
                    case "getLoadedChunks" -> { listings.incrementAndGet(); yield new Chunk[]{chunk}; }
                    case "getTileEntityCount" -> 1;
                    default -> throw new UnsupportedOperationException(m.getName());
                }));
        var c = collector(true, true, null, Set.of(), Set.of());
        assertThrows(IllegalStateException.class, () -> c.collectHeavyWorldDataStep(32, 3));
        assertEquals(1, c.health().snapshot().get("world").failures());
        assertEquals(1, listings.get());
        // The failed scan is dropped, and the interval is waited out before the next attempt.
        broken.set(false);
        for (int i = 0; i < 3; i++) {
            c.collectHeavyWorldDataStep(32, 3);
            assertEquals(1, listings.get(), "cooldown tick " + i);
        }
        c.collectHeavyWorldDataStep(32, 3);
        assertEquals(2, listings.get(), "a fresh scan starts from a fresh chunk list");
        assertEquals(List.of(new Snapshot.WorldTotals("world", 1, 1)), c.heavyWorldData().totals());
        assertTrue(c.health().snapshot().get("world").lastSuccess() > 0);
    }

    @Test void theScanIntervalIsTheIdleGapBetweenScans() {
        AtomicInteger listings = new AtomicInteger();
        Chunk chunk = (Chunk) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Chunk.class}, (p, m, a) -> switch (m.getName()) {
                    case "isLoaded" -> true;
                    case "getEntities" -> new Entity[0];
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        worlds = List.of((World) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{World.class}, (p, m, a) -> switch (m.getName()) {
                    case "getName" -> "world";
                    case "getLoadedChunks" -> { listings.incrementAndGet(); yield new Chunk[]{chunk, chunk}; }
                    case "getTileEntityCount" -> 0;
                    default -> throw new UnsupportedOperationException(m.getName());
                }));
        var c = collector(true, false, null, Set.of(), Set.of());
        c.collectHeavyWorldDataStep(1, 10);     // tick 1: first chunk
        c.collectHeavyWorldDataStep(1, 10);     // tick 2: second chunk
        c.collectHeavyWorldDataStep(1, 10);     // tick 3: finishes the world, publishes
        assertEquals(1, c.heavyWorldData().totals().size());
        for (int tick = 0; tick < 10; tick++) {
            c.collectHeavyWorldDataStep(1, 10);
            assertEquals(1, listings.get(), "idle tick " + tick);
        }
        c.collectHeavyWorldDataStep(1, 10);
        assertEquals(2, listings.get(), "the next scan starts after exactly interval idle ticks");
    }

    @Test void paperPingIsAveragedAndNegativeReadingsAreClamped() {
        online = List.of(player(30), player(-5), player(45));
        var sample = collector(false, false, null, Set.of(), Set.of()).collectPaper();
        assertEquals(3, sample.playersOnline());
        assertEquals(3, sample.pingSamples());
        assertEquals(0.025, sample.pingAverageSeconds(), 1e-12);
        assertEquals(0.045, sample.pingMaximumSeconds(), 1e-12);
    }

    @Test void placeholderSnapshotCarriesRealJvmProcessAndEventReadings() {
        var events = new EventCounters();
        events.recordLogin(org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.ALLOWED);
        var c = new MetricsCollector("test", true, true, events, "test", "test", "17", "folia", null,
                Set.of(), Set.of());
        Snapshot placeholder = c.initialSnapshot();
        assertEquals(0, placeholder.playersMax(), "values a region owns stay unknown");
        assertTrue(placeholder.jvm().heapUsed() > 0);
        assertTrue(placeholder.proc().uptimeSeconds() > 0);
        assertEquals(1L, placeholder.events().get("login"));
        String rendered = PrometheusWriter.render(placeholder, c.health());
        assertFalse(rendered.contains("mc_uptime_seconds{server=\"test\"} 0\n"));
        assertFalse(rendered.contains("mc_jvm_memory_used_bytes{server=\"test\",area=\"heap\"} 0\n"));
    }
    @Test void failureIsVisibleWithoutPublishingANewSnapshotAndRecoveryWorks() {
        var c = collector(false, false, null, Set.of(), Set.of());
        var good = c.collectPaper();
        double success = c.health().snapshot().get("main").lastSuccess();
        assertTrue(success > 0);
        fail.set(true);
        assertThrows(IllegalStateException.class, c::collectPaper);
        assertEquals(success, c.health().snapshot().get("main").lastSuccess());
        assertEquals(1, c.health().snapshot().get("main").failures());
        assertTrue(PrometheusWriter.render(good, c.health()).contains(
                "mc_collection_failures_total{server=\"test\",stage=\"main\"} 1\n"));
        fail.set(false); c.collectPaper();
        assertTrue(c.health().snapshot().get("main").lastSuccess() >= success);
    }
    @Test void worldScanRespectsPerTickChunkBudgetAndPublishesOnlyCompleteTotals() {
        AtomicInteger visited = new AtomicInteger();
        Chunk[] chunks = new Chunk[3];
        for (int i = 0; i < chunks.length; i++) {
            chunks[i] = (Chunk) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Chunk.class}, (p, m, a) -> switch (m.getName()) {
                        case "isLoaded" -> true;
                        case "getEntities" -> {
                            visited.incrementAndGet();
                            yield new Entity[]{entity(EntityType.ZOMBIE)};
                        }
                        case "getTileEntities" -> new BlockState[1];
                        default -> throw new UnsupportedOperationException(m.getName());
                    });
        }
        worlds = List.of((World) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{World.class}, (p, m, a) -> switch (m.getName()) {
                    case "getName" -> "world";
                    case "getLoadedChunks" -> chunks;
                    case "getChunkCount" -> chunks.length;
                    case "getTileEntityCount" -> 3;
                    case "getPlayerCount" -> 0;
                    default -> throw new UnsupportedOperationException(m.getName());
                }));
        var c = collector(true, true, null, Set.of(), Set.of());
        for (int step = 1; step <= 3; step++) {
            c.collectHeavyWorldDataStep(1, 600);
            assertEquals(step, visited.get());
            assertTrue(c.heavyWorldData().totals().isEmpty());
        }
        c.collectHeavyWorldDataStep(1, 600);
        assertEquals(List.of(new Snapshot.WorldTotals("world", 3, 3)),
                c.heavyWorldData().totals());
        assertEquals(List.of(new Snapshot.TypeCount("world", "zombie", 3)),
                c.heavyWorldData().types());
        c.collectHeavyWorldDataStep(1, 600);
        assertEquals(3, visited.get(), "a completed scan waits for its next interval");
    }
    private Entity entity(EntityType type) {
        return (Entity) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Entity.class},
                (p, m, a) -> type);
    }
    private org.bukkit.entity.Player player(int ping) {
        return (org.bukkit.entity.Player) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{org.bukkit.entity.Player.class}, (p, m, a) -> switch (m.getName()) {
                    case "getPing" -> ping;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }
    private World world(String name, boolean excluded, List<Entity> entities) {
        Chunk chunk = (Chunk) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Chunk.class}, (p, m, a) -> switch (m.getName()) {
                    case "isLoaded" -> true;
                    case "getEntities" -> entities.toArray(Entity[]::new);
                    case "getTileEntities" -> new BlockState[3];
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        return (World) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{World.class}, (p, m, a) -> {
            if (m.getName().equals("getName")) return name;
            if (excluded) throw new AssertionError("Excluded world was read: " + m.getName());
            return switch (m.getName()) {
                case "getChunkCount" -> 10;
                case "getTileEntityCount" -> 3;
                case "getPlayerCount" -> 0;
                case "getLoadedChunks" -> new Chunk[]{chunk};
                default -> throw new UnsupportedOperationException(m.getName());
            };
        });
    }
}
