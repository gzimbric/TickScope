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

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Prometheus exporter for Paper with no runtime dependencies — the HTTP server is the
 * JDK's own, and the exposition format is written directly.
 *
 * <p>Concurrency contract: Bukkit reads happen in platform-owned scheduler tasks (Paper's main
 * thread, Folia's global region, or an entity's owning region). The HTTP handler only serialises
 * the most recent immutable snapshot, so a scrape never blocks a tick thread.
 */
public final class TickScope extends JavaPlugin {

    private MetricsHttpServer http;
    private PlatformScheduler scheduler;
    private PlatformScheduler.TaskHandle sampler;
    private PlatformScheduler.TaskHandle entitySampler;
    private FoliaPlayerSampler foliaPlayers;
    private MetricsCollector collector;
    private EventCounters events;
    private final AtomicLong foliaPlayerSampleGeneration = new AtomicLong();
    private volatile Snapshot latest;
    private Settings active;

    private record Settings(String serverId, String bind, int port, String path, String token,
                            long interval, boolean perWorld, boolean byType, long typeInterval) {
        boolean sameEndpoint(Settings other) {
            return other != null && bind.equals(other.bind) && port == other.port
                    && path.equals(other.path) && token.equals(other.token);
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        events = new EventCounters();
        getServer().getPluginManager().registerEvents(events, this);

        try {
            scheduler = PlatformScheduler.create(this);
            Settings settings = readSettings();
            configureCollector(settings);
            startHttp(settings);
            active = settings;
            logServing(settings);
        } catch (IllegalArgumentException | IOException e) {
            getLogger().severe("Could not start TickScope — " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (sampler != null) sampler.cancel();
        if (entitySampler != null) entitySampler.cancel();
        stopHttp();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> {
                Snapshot s = latest;
                sender.sendMessage("TickScope " + getDescription().getVersion());
                for (Map.Entry<String, String> e : collector.describe().entrySet()) {
                    sender.sendMessage("  " + e.getKey() + ": " + e.getValue());
                }
                sender.sendMessage("  endpoint: http://" + active.bind + ":" + active.port
                        + active.path);
                sender.sendMessage("  collection-interval: " + active.interval + " ticks");
                sender.sendMessage("  entity-interval: "
                        + (active.byType && !scheduler.isFolia()
                        ? active.typeInterval + " ticks" : "disabled"));
                // State only -- never the token itself.
                sender.sendMessage("  auth: " + (active.token.isEmpty() ? "none" : "required"));
                if (scheduler.isFolia()) {
                    sender.sendMessage("  tick-metrics: regional TPS"
                            + (s.regionMspt().isEmpty() ? " (regional MSPT unavailable)"
                            : " and average MSPT"));
                    sender.sendMessage("  region-tps-windows: " + s.regionTps().size());
                    sender.sendMessage("  region-mspt-windows: " + s.regionMspt().size());
                } else {
                    sender.sendMessage(String.format(
                            "  mspt avg %.2f  p95 %.2f  max %.2f  (%d samples)",
                            s.ticks().avgMs(), s.ticks().p95Ms(), s.ticks().maxMs(),
                            s.ticks().samples()));
                }
                sender.sendMessage(String.format(
                        "  players %d/%d  worlds %d  collection %.3f ms",
                        s.playersOnline(), s.playersMax(), s.worlds().size(),
                        s.collectionSeconds() * 1000));
            }
            case "reload" -> {
                sender.sendMessage("Reloading TickScope…");
                reloadConfig();
                if (reloadRuntime()) {
                    sender.sendMessage("Reloaded.");
                } else {
                    sender.sendMessage("Reload failed; the previous configuration is still active.");
                }
            }
            default -> sender.sendMessage("Usage: /" + label + " <status|reload>");
        }
        return true;
    }

    private Settings readSettings() {
        String serverId = required("server-id", "paper");
        String bind = required("bind-address", "127.0.0.1");
        int port = getConfig().getInt("port", 9101);
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        String path = required("path", "/metrics");
        if (!path.startsWith("/") || path.length() > 1 && path.endsWith("/")
                || path.indexOf('?') >= 0 || path.indexOf('#') >= 0) {
            throw new IllegalArgumentException(
                    "path must be an absolute path without a trailing '/', query, or fragment");
        }
        String token = Objects.requireNonNullElse(
                getConfig().getString("auth-token"), "").trim();
        long interval = Math.max(20L,
                getConfig().getLong("collection-interval-ticks", 100L));
        boolean perWorld = getConfig().getBoolean("per-world", true);
        boolean byType = getConfig().getBoolean("entity-types.enabled", true);
        long typeInterval = Math.max(100L,
                getConfig().getLong("entity-types.interval-ticks", 600L));
        return new Settings(serverId, bind, port, path, token, interval,
                perWorld, byType, typeInterval);
    }

    private String required(String key, String fallback) {
        String value = Objects.requireNonNullElse(getConfig().getString(key), fallback).trim();
        if (value.isEmpty()) throw new IllegalArgumentException(key + " must not be empty");
        return value;
    }

    private void configureCollector(Settings settings) {
        // Invalidate entity-scheduler callbacks that may still be completing from the previous
        // configuration. A Folia task already handed to a player cannot be synchronously canceled.
        foliaPlayerSampleGeneration.incrementAndGet();
        if (sampler != null) sampler.cancel();
        if (entitySampler != null) entitySampler.cancel();
        sampler = null;
        entitySampler = null;

        String pluginVersion = getDescription().getVersion();
        String paperVersion = getServer().getVersion();
        String javaVersion = System.getProperty("java.version", "unknown");
        collector = new MetricsCollector(settings.serverId, settings.perWorld, events,
                pluginVersion, paperVersion, javaVersion,
                scheduler.isFolia() ? "folia" : "paper");
        if (scheduler.isFolia()) {
            // onEnable and player commands do not necessarily own world data on Folia. Publish a
            // placeholder and let the first global-region task replace it on the next tick.
            latest = collector.initialSnapshot();
            foliaPlayers = new FoliaPlayerSampler(scheduler);
            sampler = scheduler.repeatGlobal(this::collectFolia, 1L, settings.interval);
            if (settings.byType) {
                getLogger().warning("Entity-type metrics are disabled on Folia because a "
                        + "world-wide entity walk crosses region ownership boundaries");
            }
        } else {
            // Paper configuration is applied on the main thread, so publish a real sample now.
            latest = collector.collectPaper();
            sampler = scheduler.repeatGlobal(
                    () -> latest = collector.collectPaper(), settings.interval, settings.interval);
            if (settings.byType) {
                entitySampler = scheduler.repeatGlobal(
                        collector::collectEntityTypes, 40L, settings.typeInterval);
            }
        }
    }

    private void collectFolia() {
        latest = collector.collectFolia();
        long generation = foliaPlayerSampleGeneration.incrementAndGet();
        MetricsCollector samplingCollector = collector;
        foliaPlayers.sample(getServer().getOnlinePlayers(), players -> {
            if (foliaPlayerSampleGeneration.get() == generation) {
                samplingCollector.updateFoliaPlayers(players);
            }
        });
    }

    private boolean reloadRuntime() {
        final Settings next;
        try {
            next = readSettings();
        } catch (IllegalArgumentException e) {
            getLogger().warning("Reload rejected — " + e.getMessage());
            return false;
        }

        if (!next.sameEndpoint(active)) {
            Settings previous = active;
            stopHttp();
            try {
                startHttp(next);
            } catch (IOException | IllegalArgumentException e) {
                getLogger().warning("Could not apply new endpoint — " + e.getMessage());
                try {
                    startHttp(previous);
                } catch (IOException | IllegalArgumentException rollback) {
                    getLogger().severe("Could not restore previous endpoint — "
                            + rollback.getMessage());
                    getServer().getPluginManager().disablePlugin(this);
                }
                return false;
            }
        }

        configureCollector(next);
        active = next;
        logServing(next);
        return true;
    }

    private void startHttp(Settings settings) throws IOException {
        MetricsHttpServer candidate = new MetricsHttpServer(settings.bind, settings.port,
                settings.path, settings.token,
                () -> PrometheusWriter.render(latest).getBytes(StandardCharsets.UTF_8));
        try {
            candidate.start();
        } catch (RuntimeException e) {
            candidate.close();
            throw e;
        }
        http = candidate;
    }

    private void stopHttp() {
        if (http != null) {
            http.close();
            http = null;
        }
    }

    private void logServing(Settings settings) {
        getLogger().info("Serving " + settings.path + " on " + settings.bind + ":"
                + settings.port + " as server=\"" + settings.serverId + "\" (every "
                + settings.interval + " ticks, " + (scheduler.isFolia() ? "Folia" : "Paper")
                + ", "
                + (settings.token.isEmpty() ? "no auth)" : "auth required)"));
    }
}
