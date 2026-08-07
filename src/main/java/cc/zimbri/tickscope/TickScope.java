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

import com.sun.net.httpserver.HttpServer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Prometheus exporter for Paper with no runtime dependencies — the HTTP server is the
 * JDK's own, and the exposition format is written directly.
 *
 * <p>Concurrency contract: all Bukkit reads happen in the scheduler tasks below, on the
 * main thread. The HTTP handler only serialises the most recent published snapshot, so a
 * scrape can never block or race the server thread.
 */
public final class TickScope extends JavaPlugin {

    private static final String CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    private HttpServer http;
    private BukkitTask sampler;
    private BukkitTask entitySampler;
    private MetricsCollector collector;
    private volatile Snapshot latest;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        String serverId = getConfig().getString("server-id", "paper");
        String bind = getConfig().getString("bind-address", "127.0.0.1");
        int port = getConfig().getInt("port", 9101);
        String path = getConfig().getString("path", "/metrics");
        long interval = Math.max(20L, getConfig().getLong("collection-interval-ticks", 100L));
        boolean perWorld = getConfig().getBoolean("per-world", true);
        boolean byType = getConfig().getBoolean("entity-types.enabled", true);
        long typeInterval = Math.max(100L, getConfig().getLong("entity-types.interval-ticks", 600L));

        latest = Snapshot.empty(serverId);

        EventCounters events = new EventCounters();
        getServer().getPluginManager().registerEvents(events, this);
        collector = new MetricsCollector(serverId, perWorld, events);

        sampler = getServer().getScheduler()
                .runTaskTimer(this, () -> latest = collector.collect(), 20L, interval);
        if (byType) {
            // The only O(entities) reading, so it gets a slower cadence of its own.
            entitySampler = getServer().getScheduler()
                    .runTaskTimer(this, collector::collectEntityTypes, 40L, typeInterval);
        }

        try {
            http = HttpServer.create(new InetSocketAddress(bind, port), 0);
            http.createContext(path, exchange -> {
                byte[] body = PrometheusWriter.render(latest).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", CONTENT_TYPE);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });
            // One daemon thread on purpose: scrapes are cheap string building, and a pool
            // here would only compete with the server for CPU.
            http.setExecutor(Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "TickScope-http");
                t.setDaemon(true);
                return t;
            }));
            http.start();
            getLogger().info("Serving " + path + " on " + bind + ":" + port
                    + " as server=\"" + serverId + "\" (every " + interval + " ticks)");
        } catch (IOException e) {
            getLogger().severe("Could not bind " + bind + ":" + port + " — " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (sampler != null) sampler.cancel();
        if (entitySampler != null) entitySampler.cancel();
        if (http != null) http.stop(0);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase();
        switch (sub) {
            case "status" -> {
                Snapshot s = latest;
                sender.sendMessage("TickScope " + getPluginMeta().getVersion());
                for (Map.Entry<String, String> e : collector.describe().entrySet()) {
                    sender.sendMessage("  " + e.getKey() + ": " + e.getValue());
                }
                sender.sendMessage(String.format(
                        "  mspt avg %.2f  p95 %.2f  max %.2f  (%d samples)",
                        s.ticks().avgMs(), s.ticks().p95Ms(), s.ticks().maxMs(), s.ticks().samples()));
                sender.sendMessage(String.format(
                        "  players %d/%d  worlds %d  collection %.3f ms",
                        s.playersOnline(), s.playersMax(), s.worlds().size(),
                        s.collectionSeconds() * 1000));
            }
            case "reload" -> {
                sender.sendMessage("Reloading TickScope…");
                onDisable();
                reloadConfig();
                onEnable();
                sender.sendMessage("Reloaded.");
            }
            default -> sender.sendMessage("Usage: /" + label + " <status|reload>");
        }
        return true;
    }
}
