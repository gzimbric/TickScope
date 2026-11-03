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
    void everyEventSeriesExistsFromTheStartInAStableOrder() {
        EventCounters counters = new EventCounters();
        assertEquals(java.util.List.of("login", "join", "quit", "chat", "death"),
                new java.util.ArrayList<>(counters.snapshot().keySet()));
        assertTrue(counters.snapshot().values().stream().allMatch(v -> v == 0L));
    }

    @Test
    void countsOnlyAllowedPreLogins() {
        EventCounters counters = new EventCounters();

        counters.recordLogin(AsyncPlayerPreLoginEvent.Result.ALLOWED);
        counters.recordLogin(AsyncPlayerPreLoginEvent.Result.KICK_OTHER);

        assertEquals(1L, counters.snapshot().get("login"));
    }
}
