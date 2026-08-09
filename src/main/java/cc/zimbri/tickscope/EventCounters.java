/*
 * TickScope - a Prometheus exporter for Paper servers.
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
package cc.zimbri.tickscope;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * Monotonic event counters, the one thing UnifiedMetrics exposes that cannot be sampled —
 * a gauge read every 5s would miss joins between reads.
 *
 * <p>Listeners are MONITOR/ignoreCancelled so counting never influences gameplay, and
 * LongAdder keeps the async chat handler off a contended lock.
 */
final class EventCounters implements Listener {

    private final Map<String, LongAdder> counts = new LinkedHashMap<>();

    EventCounters() {
        for (String k : new String[]{"login", "join", "quit", "chat", "death"}) {
            counts.put(k, new LongAdder());
        }
    }

    private void bump(String key) {
        LongAdder a = counts.get(key);
        if (a != null) a.increment();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLogin(AsyncPlayerPreLoginEvent e) {
        recordLogin(e.getLoginResult());
    }

    void recordLogin(AsyncPlayerPreLoginEvent.Result result) {
        if (result == AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            bump("login");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) { bump("join"); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) { bump("quit"); }

    // Paper's Adventure chat event, not the deprecated Bukkit AsyncPlayerChatEvent —
    // that one is not guaranteed to fire on a modern chat pipeline.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) { bump("chat"); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) { bump("death"); }

    Map<String, Long> snapshot() {
        Map<String, Long> out = new LinkedHashMap<>();
        counts.forEach((k, v) -> out.put(k, v.sum()));
        return out;
    }
}
