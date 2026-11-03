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

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventCountersTest {

    /**
     * Chat-formatting plugins cancel the event and deliver the message themselves, so a
     * handler that skipped cancelled chat counted nothing on the busiest servers. A cancelled
     * death, by contrast, never happened.
     */
    @Test
    void countsCancelledChatButNotCancelledDeaths() throws NoSuchMethodException {
        EventHandler chat = EventCounters.class.getMethod("onChat", AsyncChatEvent.class)
                .getAnnotation(EventHandler.class);
        assertEquals(EventPriority.MONITOR, chat.priority());
        assertFalse(chat.ignoreCancelled());

        EventHandler death = EventCounters.class.getMethod("onDeath", PlayerDeathEvent.class)
                .getAnnotation(EventHandler.class);
        assertEquals(EventPriority.MONITOR, death.priority());
        assertTrue(death.ignoreCancelled());
    }

    @Test
    void countsChunkEventsPerWorldAndFiltersOnlyAtSnapshotTime() {
        EventCounters counters = new EventCounters();
        counters.recordChunkLoad("world", true);
        counters.recordChunkLoad("world", false);
        counters.recordChunkUnload("world");
        counters.recordChunkLoad("lobby", false);

        assertEquals(java.util.List.of(
                new Snapshot.WorldChunkEvents("lobby", 1, 0, 0),
                new Snapshot.WorldChunkEvents("world", 2, 1, 1)), counters.chunkEvents(w -> true),
                "sorted by world name, generated counted as a load as well");
        assertEquals(java.util.List.of(new Snapshot.WorldChunkEvents("world", 2, 1, 1)),
                counters.chunkEvents(w -> !w.equals("lobby")));
        // Excluding a world hides it; re-including it does not restart its counters.
        assertEquals(new Snapshot.WorldChunkEvents("lobby", 1, 0, 0), counters.chunkEvents(w -> true).get(0));
    }

    @Test
    void everyEventSeriesExistsFromTheStartInAStableOrder() throws NoSuchMethodException {
        EventCounters counters = new EventCounters();
        assertEquals(java.util.List.of("login", "join", "quit", "chat", "death", "command", "kick"),
                new java.util.ArrayList<>(counters.snapshot().keySet()));
        assertTrue(counters.snapshot().values().stream().allMatch(v -> v == 0L));
        // A cancelled command never ran and a cancelled kick never happened.
        for (String handler : java.util.List.of("onCommand", "onKick")) {
            EventHandler annotation = java.util.Arrays.stream(EventCounters.class.getMethods())
                    .filter(m -> m.getName().equals(handler)).findFirst().orElseThrow()
                    .getAnnotation(EventHandler.class);
            assertEquals(EventPriority.MONITOR, annotation.priority(), handler);
            assertTrue(annotation.ignoreCancelled(), handler);
        }
    }

    @Test
    void countsOnlyAllowedPreLogins() {
        EventCounters counters = new EventCounters();

        counters.recordLogin(AsyncPlayerPreLoginEvent.Result.ALLOWED);
        counters.recordLogin(AsyncPlayerPreLoginEvent.Result.KICK_OTHER);

        assertEquals(1L, counters.snapshot().get("login"));
    }
}
