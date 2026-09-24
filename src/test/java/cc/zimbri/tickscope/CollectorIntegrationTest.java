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
import org.bukkit.Server;
import org.bukkit.World;
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
import static org.junit.jupiter.api.Assertions.*;

class CollectorIntegrationTest {
    private Server previous;
    private List<World> worlds = List.of();
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
                    case "getOnlinePlayers" -> List.of();
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
    private Entity entity(EntityType type) {
        return (Entity) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Entity.class},
                (p, m, a) -> type);
    }
    private World world(String name, boolean excluded, List<Entity> entities) {
        return (World) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{World.class}, (p, m, a) -> {
            if (m.getName().equals("getName")) return name;
            if (excluded) throw new AssertionError("Excluded world was read: " + m.getName());
            return switch (m.getName()) {
                case "getEntityCount" -> entities.size();
                case "getTileEntityCount" -> 3;
                case "getChunkCount" -> 10;
                case "getPlayerCount" -> 0;
                case "getEntities" -> entities;
                default -> throw new UnsupportedOperationException(m.getName());
            };
        });
    }
}
