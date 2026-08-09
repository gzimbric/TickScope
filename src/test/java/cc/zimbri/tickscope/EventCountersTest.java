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

import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EventCountersTest {

    @Test
    void countsOnlyAllowedPreLogins() {
        EventCounters counters = new EventCounters();

        counters.recordLogin(AsyncPlayerPreLoginEvent.Result.ALLOWED);
        counters.recordLogin(AsyncPlayerPreLoginEvent.Result.KICK_OTHER);

        assertEquals(1L, counters.snapshot().get("login"));
    }
}
