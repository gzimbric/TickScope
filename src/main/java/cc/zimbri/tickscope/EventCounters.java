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
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Predicate;

/**
 * Monotonic event counters, the one thing UnifiedMetrics exposes that cannot be sampled —
 * a gauge read every 5s would miss joins between reads.
 *
 * <p>Listeners run at MONITOR priority, so they observe the outcome of every other plugin
 * without changing it, and LongAdder keeps the async chat handler off a contended lock.
 * Whether a cancelled event counts is decided per event below: ignoreCancelled filters what
 * is counted, nothing more.
 *
 * <p>On Folia the chunk and player events arrive on whichever region owns them, possibly
 * several at once; nothing here reads the chunk or the player, only a world name.
 */
final class EventCounters implements Listener {

    private final Map<String, LongAdder> counts = new LinkedHashMap<>();
    private final ConcurrentHashMap<String, WorldCounters> worlds = new ConcurrentHashMap<>();

    private record WorldCounters(LongAdder loads, LongAdder unloads, LongAdder generated) {
        private WorldCounters() {
            this(new LongAdder(), new LongAdder(), new LongAdder());
        }
    }

    EventCounters() {
        // New names go at the end so the exposition order of the existing ones never moves.
        for (String k : new String[]{"login", "join", "quit", "chat", "death", "command", "kick"}) {
            counts.put(k, new LongAdder());
        }
    }

    private void bump(String key) {
        LongAdder a = counts.get(key);
        if (a != null) a.increment();
    }

    // No ignoreCancelled here: AsyncPlayerPreLoginEvent does not implement Cancellable, so the
    // flag would only imply a guard that does not exist. The result check below is the filter.
    @EventHandler(priority = EventPriority.MONITOR)
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
    //
    // Cancelled chat is counted on purpose. Most chat-formatting plugins cancel the event and
    // deliver the message themselves, and Paper carries a cancelled legacy event over into
    // this one, so skipping cancelled events left the counter at zero on exactly the servers
    // where players talk the most. The series therefore counts messages players sent.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(AsyncChatEvent e) { bump("chat"); }

    // EntityDeathEvent is cancellable, so a plugin that revives the player cancels it before
    // this MONITOR handler runs; counting it anyway reported deaths that never happened.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent e) { bump("death"); }

    // A cancelled command never ran. Console, RCON and plugin-dispatched commands do not fire
    // this event; panels polling RCON every few seconds would otherwise drown the player signal.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) { bump("command"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKick(PlayerKickEvent e) { bump("kick"); }

    // Neither chunk event is cancellable. Only the world name is read: on Folia the chunk
    // belongs to the region delivering the event and must not be touched from anywhere else.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent e) {
        recordChunkLoad(e.getWorld().getName(), e.isNewChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent e) {
        recordChunkUnload(e.getWorld().getName());
    }

    void recordChunkLoad(String world, boolean generated) {
        WorldCounters c = worlds.computeIfAbsent(world, ignored -> new WorldCounters());
        c.loads.increment();
        if (generated) c.generated.increment();
    }

    void recordChunkUnload(String world) {
        worlds.computeIfAbsent(world, ignored -> new WorldCounters()).unloads.increment();
    }

    Map<String, Long> snapshot() {
        Map<String, Long> out = new LinkedHashMap<>();
        counts.forEach((k, v) -> out.put(k, v.sum()));
        return out;
    }

    /**
     * Counts keep running for every world; the filter decides what is published, so excluding
     * and re-including a world never resets its series.
     */
    List<Snapshot.WorldChunkEvents> chunkEvents(Predicate<String> included) {
        List<Snapshot.WorldChunkEvents> out = new ArrayList<>();
        worlds.forEach((world, c) -> {
            if (included.test(world)) {
                out.add(new Snapshot.WorldChunkEvents(world, c.loads.sum(), c.unloads.sum(),
                        c.generated.sum()));
            }
        });
        out.sort((a, b) -> a.world().compareTo(b.world()));
        return out;
    }
}
