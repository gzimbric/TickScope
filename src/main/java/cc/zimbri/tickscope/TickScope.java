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
import java.io.File;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import java.util.logging.Logger;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Prometheus exporter for Paper with no runtime dependencies — both the HTTP endpoint and the
 * exposition format are written directly.
 *
 * <p>Concurrency contract: Bukkit reads happen in platform-owned scheduler tasks (Paper's main
 * thread, Folia's global region, or an entity's owning region). The HTTP handler only serialises
 * the most recent immutable snapshot, so a scrape never blocks a tick thread.
 *
 * <p>Folia runs player commands on their own regions, so two administrators can enter
 * {@code /tickscope reload} concurrently. Every runtime transition is therefore taken under
 * {@link #runtimeLock}; without it, two reloads could each stop the endpoint the other had just
 * started and leave a listener running with no handle to close it.
 */
public final class TickScope extends JavaPlugin {

    private MetricsHttpServer http;
    private PlatformScheduler scheduler;
    private PlatformScheduler.TaskHandle sampler;
    private PlatformScheduler.TaskHandle scanSampler;
    private FoliaPlayerSampler foliaPlayers;
    private EventCounters events;
    private final AtomicLong foliaPlayerSampleGeneration = new AtomicLong();
    private final ReentrantLock runtimeLock = new ReentrantLock();
    private volatile MetricsCollector collector;
    private volatile Snapshot latest;
    private volatile Settings active;
    private boolean stopped;

    record Settings(String serverId, String bind, int port, String path, String token,
                            long interval, boolean perWorld, boolean byType, long scanInterval,
                            Set<String> excludedWorlds, Set<String> allowedTypes) {
        boolean sameEndpoint(Settings other) {
            return other != null && bind.equals(other.bind) && port == other.port
                    && path.equals(other.path) && token.equals(other.token);
        }
    }

    private enum ReloadResult { RELOADED, REJECTED, ROLLED_BACK, DISABLED }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        events = new EventCounters();
        getServer().getPluginManager().registerEvents(events, this);

        runtimeLock.lock();
        try {
            scheduler = PlatformScheduler.create(this);
            Settings settings = readSettings();
            configureCollector(settings);
            startHttp(settings);
            active = settings;
            logServing(settings);
        } catch (IllegalArgumentException | IOException | InvalidConfigurationException e) {
            getLogger().severe("Could not start TickScope — " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        } finally {
            runtimeLock.unlock();
        }
    }

    @Override
    public void onDisable() {
        runtimeLock.lock();
        try {
            stopped = true;
            if (sampler != null) sampler.cancel();
            if (scanSampler != null) scanSampler.cancel();
            sampler = null;
            scanSampler = null;
            if (foliaPlayers != null) foliaPlayers.close();
            stopHttp();
        } finally {
            runtimeLock.unlock();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> status(sender);
            case "reload" -> {
                sender.sendMessage("Reloading TickScope…");
                ReloadResult result;
                runtimeLock.lock();
                try {
                    result = reloadRuntime();
                } finally {
                    runtimeLock.unlock();
                }
                switch (result) {
                    case RELOADED -> sender.sendMessage("Reloaded.");
                    case REJECTED -> sender.sendMessage(
                            "Reload rejected; the configuration is not valid and the previous one "
                                    + "is still active. The server log says which setting.");
                    case ROLLED_BACK -> sender.sendMessage(
                            "Could not open the new endpoint; rolled back to the previous one.");
                    case DISABLED -> sender.sendMessage(
                            "Reload failed and the previous endpoint could not be reopened. "
                                    + "TickScope has been disabled.");
                }
            }
            default -> sender.sendMessage("Usage: /" + label + " <status|reload>");
        }
        return true;
    }

    private void status(CommandSender sender) {
        Snapshot s = latest;
        Settings settings = active;
        sender.sendMessage("TickScope " + getDescription().getVersion());
        for (Map.Entry<String, String> e : collector.describe().entrySet()) {
            sender.sendMessage("  " + e.getKey() + ": " + e.getValue());
        }
        sender.sendMessage("  endpoint: " + endpointUrl(settings));
        sender.sendMessage("  collection-interval: " + settings.interval + " ticks");
        sender.sendMessage("  world-scan-interval: "
                + (scheduler.isFolia() ? "disabled on Folia" : settings.scanInterval + " ticks"));
        for (var entry : collector.health().snapshot().entrySet()) {
            var health = entry.getValue();
            if (!health.enabled()) continue;
            String age = health.lastSuccess() == 0 ? "not yet collected"
                    : String.format(Locale.ROOT, "%.1f s ago",
                            Math.max(0, System.currentTimeMillis() / 1000d - health.lastSuccess()));
            sender.sendMessage("  " + entry.getKey() + " collection: " + age
                    + ", failures " + health.failures());
        }
        sender.sendMessage("  world exclusions: " + settings.excludedWorlds.size()
                + ", entity allowlist: " + settings.allowedTypes.size());
        // State only -- never the token itself.
        sender.sendMessage("  auth: " + (settings.token.isEmpty() ? "none" : "required"));
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

    /** An IPv6 literal has to be bracketed inside a URL authority to be a valid URL. */
    private static String endpointUrl(Settings settings) {
        String host = settings.bind.indexOf(':') >= 0 ? "[" + settings.bind + "]" : settings.bind;
        return "http://" + host + ":" + settings.port + settings.path;
    }

    private Settings readSettings() throws IOException, InvalidConfigurationException {
        return readSettings(new File(getDataFolder(), "config.yml"), getLogger());
    }

    static Settings readSettings(File file, Logger logger)
            throws IOException, InvalidConfigurationException {
        // Unlike loadConfiguration/reloadConfig, load propagates malformed YAML and I/O errors.
        YamlConfiguration candidate = new YamlConfiguration();
        candidate.load(file);
        return parseSettings(candidate, logger);
    }

    static Settings parseSettings(FileConfiguration config, Logger logger) {
        String serverId = required(config, "server-id", "paper");
        String bind = required(config, "bind-address", "127.0.0.1");
        long configuredPort = integral(config, "port", 9101L);
        int port = checkedPort(configuredPort);
        String path = required(config, "path", "/metrics");
        if (!path.startsWith("/") || path.length() > 1 && path.endsWith("/")
                || path.indexOf('?') >= 0 || path.indexOf('#') >= 0) {
            throw new IllegalArgumentException(
                    "path must be an absolute path without a trailing '/', query, or fragment");
        }
        String token = string(config, "auth-token", "");
        long interval = atLeast(logger, "collection-interval-ticks",
                integral(config, "collection-interval-ticks", 100L), 20L);
        boolean perWorld = bool(config, "per-world", true);
        boolean byType = bool(config, "entity-types.enabled", true);
        long scanInterval = atLeast(logger, "entity-types.interval-ticks",
                integral(config, "entity-types.interval-ticks", 600L), 100L);
        return new Settings(serverId, bind, port, path, token, interval,
                perWorld, byType, scanInterval, stringSet(config, "exclude-worlds"),
                stringSet(config, "entity-types.allowlist"));
    }

    private static Set<String> stringSet(FileConfiguration config, String key) {
        Object value = config.get(key);
        if (value == null) return Set.of();
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(key + " must be a list of strings");
        }
        Set<String> result = new LinkedHashSet<>();
        for (Object item : list) {
            if (!(item instanceof String text) || text.isBlank()) {
                throw new IllegalArgumentException(key + " must contain non-empty strings");
            }
            result.add(text.trim());
        }
        return Set.copyOf(result);
    }

    private static String string(FileConfiguration config, String key, String fallback) {
        Object value = config.get(key);
        if (value == null) return fallback;
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        return text.trim();
    }

    private static String required(FileConfiguration config, String key, String fallback) {
        String value = string(config, key, fallback);
        if (value.isEmpty()) throw new IllegalArgumentException(key + " must not be empty");
        return value;
    }

    /**
     * Bukkit's typed getters return the supplied default whenever the stored value has another
     * type, so a quoted number such as {@code port: "9200"} silently bound the default port and
     * {@code per-world: "false"} silently stayed enabled. The configuration on disk has to mean
     * what it says, so a wrong type is reported rather than replaced.
     */
    private static long integral(FileConfiguration config, String key, long fallback) {
        Object value = config.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Integer) && !(value instanceof Long)) {
            throw new IllegalArgumentException(
                    key + " must be a whole number, but the configuration has " + quoted(value));
        }
        return ((Number) value).longValue();
    }

    private static boolean bool(FileConfiguration config, String key, boolean fallback) {
        Object value = config.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Boolean)) {
            throw new IllegalArgumentException(
                    key + " must be true or false, but the configuration has " + quoted(value));
        }
        return (Boolean) value;
    }

    /** Too-fast intervals are raised rather than refused, but never in silence. */
    private static long atLeast(Logger logger, String key, long value, long minimum) {
        if (value >= minimum) return value;
        logger.warning(key + " is " + value + ", which is below the supported minimum of "
                + minimum + "; using " + minimum);
        return minimum;
    }

    private static int checkedPort(long port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535, but is " + port);
        }
        return (int) port;
    }

    private static String quoted(Object value) {
        return value instanceof String ? "\"" + value + "\"" : String.valueOf(value);
    }

    private void configureCollector(Settings settings) {
        if (sampler != null) sampler.cancel();
        if (scanSampler != null) scanSampler.cancel();
        sampler = null;
        scanSampler = null;

        String pluginVersion = getDescription().getVersion();
        String paperVersion = getServer().getVersion();
        String javaVersion = System.getProperty("java.version", "unknown");
        // Carry the scan cache across the reload, otherwise every reload blanks the entity and
        // tile-entity series until the next scan lands.
        MetricsCollector previous = collector;
        collector = new MetricsCollector(settings.serverId, settings.perWorld, settings.byType,
                events, pluginVersion, paperVersion, javaVersion,
                scheduler.isFolia() ? "folia" : "paper",
                previous == null ? null : previous.heavyWorldData(),
                settings.excludedWorlds, settings.allowedTypes);

        if (scheduler.isFolia()) {
            // onEnable and player commands do not necessarily own world data on Folia. Publish a
            // placeholder and let the first global-region task replace it on the next tick.
            latest = collector.initialSnapshot();
            if (foliaPlayers == null) foliaPlayers = new FoliaPlayerSampler(scheduler);
            sampler = scheduler.repeatGlobal(() -> guarded(this::collectFolia), 1L, settings.interval);
            // The world scan reads chunk and entity data the global region does not own, so it
            // has no safe home on Folia at all.
            getLogger().info("Per-world entity, tile-entity and entity-type metrics are "
                    + "unavailable on Folia: walking a world crosses region ownership boundaries. "
                    + "Chunk and player counts are still published.");
        } else {
            // Paper configuration is applied on the main thread, so publish a real sample now.
            latest = collector.collectPaper();
            sampler = scheduler.repeatGlobal(
                    () -> guarded(() -> latest = collector.collectPaper()), settings.interval, settings.interval);
            if (settings.perWorld || settings.byType) {
                scanSampler = scheduler.repeatGlobal(
                        () -> guarded(collector::collectHeavyWorldData), 40L, settings.scanInterval);
            }
        }
    }

    private void guarded(Runnable collection) {
        try {
            collection.run();
        } catch (RuntimeException e) {
            getLogger().warning("Collection failed; retaining the last sample: " + e.getMessage());
        }
    }

    private void collectFolia() {
        // Capture one configuration under the same lock used by reload. Already-running
        // entity callbacks retain their old collector and cannot update its replacement.
        runtimeLock.lock();
        try {
            if (stopped) return;
            var online = List.copyOf(getServer().getOnlinePlayers());
            MetricsCollector samplingCollector = collector;
            long generation = foliaPlayerSampleGeneration.incrementAndGet();
            foliaPlayers.sample(online,
                    players -> samplingCollector.updateFoliaPlayers(generation, players));
            latest = samplingCollector.collectFolia(online.size());
        } finally {
            runtimeLock.unlock();
        }
    }

    private ReloadResult reloadRuntime() {
        final Settings next;
        try {
            next = readSettings();
        } catch (IllegalArgumentException | IOException | InvalidConfigurationException e) {
            getLogger().warning("Reload rejected — " + e.getMessage());
            return ReloadResult.REJECTED;
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
                    return ReloadResult.DISABLED;
                }
                return ReloadResult.ROLLED_BACK;
            }
        }

        configureCollector(next);
        active = next;
        logServing(next);
        return ReloadResult.RELOADED;
    }

    private void startHttp(Settings settings) throws IOException {
        MetricsHttpServer candidate = new MetricsHttpServer(settings.bind, settings.port,
                settings.path, settings.token,
                () -> PrometheusWriter.render(latest, collector.health()).getBytes(StandardCharsets.UTF_8));
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
