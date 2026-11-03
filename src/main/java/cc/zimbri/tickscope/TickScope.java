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
import java.util.logging.Level;
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
    // Process-lifetime state: created once, so counters survive reloads and re-enables.
    private EventCounters events;
    private ScrapeStats scrapeStats;
    private final AtomicLong foliaPlayerSampleGeneration = new AtomicLong();
    private final ReentrantLock runtimeLock = new ReentrantLock();
    private final Object renderLock = new Object();
    private volatile MetricsCollector collector;
    private volatile Snapshot latest;
    private volatile RenderedMetrics rendered;
    private volatile Settings active;
    private boolean stopped;

    private record RenderedMetrics(Snapshot snapshot, MetricsCollector collector,
                                   long healthRevision, long rejectedConnections, byte[] bytes) {}

    record Settings(String serverId, String bind, int port, String path, List<String> tokens,
                            long interval, boolean perWorld, boolean byType, long scanInterval,
                            int scanChunksPerTick,
                            Set<String> excludedWorlds, Set<String> allowedTypes,
                            int maxConnectionsPerAddress) {
        /**
         * Everything the listener is built from; a change to any of it means a restart. Tokens
         * are deliberately not part of it: they are swapped in place so a rotation never closes
         * a scrape in flight.
         */
        boolean sameEndpoint(Settings other) {
            return other != null && bind.equals(other.bind) && port == other.port
                    && path.equals(other.path)
                    && maxConnectionsPerAddress == other.maxConnectionsPerAddress;
        }
    }

    private enum ReloadResult { RELOADED, ROLLED_BACK, DISABLED }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        runtimeLock.lock();
        try {
            // onDisable left these behind. Without clearing them a re-enabled plugin would run
            // its Folia collection task against a closed sampler and a set stop flag forever.
            stopped = false;
            foliaPlayers = null;
            scheduler = PlatformScheduler.create(this);
            // A plugin manager can disable and enable this same instance without restarting
            // the server, and Bukkit unregisters listeners on disable. The counters are kept so
            // their series stay monotonic across that; only the registration is repeated.
            if (events == null) events = new EventCounters();
            if (scrapeStats == null) scrapeStats = new ScrapeStats();
            getServer().getPluginManager().registerEvents(events, this);
            Settings settings = readSettings();
            configureCollector(settings);
            startHttp(settings);
            active = settings;
            logServing(settings);
        } catch (IllegalArgumentException | IOException | InvalidConfigurationException e) {
            getLogger().severe("Could not start TickScope — " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        } catch (RuntimeException e) {
            // Paper disables a plugin whose onEnable throws, but the stack trace it prints does
            // not say which plugin state was reached. Disabling here keeps the message and the
            // cleanup in one place.
            getLogger().log(Level.SEVERE, "Could not start TickScope", e);
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
            // Each step is isolated: a scheduler that refuses to cancel a task must not leave
            // the listener bound to the port until the JVM exits.
            try {
                cancelSamplers();
            } catch (RuntimeException e) {
                getLogger().warning("Could not cancel a collection task: " + e.getMessage());
            }
            try {
                if (foliaPlayers != null) foliaPlayers.close();
            } finally {
                foliaPlayers = null;
            }
            stopHttp();
        } finally {
            runtimeLock.unlock();
        }
    }

    private void cancelSamplers() {
        PlatformScheduler.TaskHandle main = sampler;
        PlatformScheduler.TaskHandle scan = scanSampler;
        sampler = null;
        scanSampler = null;
        try {
            if (main != null) main.cancel();
        } finally {
            if (scan != null) scan.cancel();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> status(sender);
            case "reload" -> {
                sender.sendMessage("Reloading TickScope…");
                // The file is read and validated before the lock is taken: a rejected file
                // changes nothing, and Folia's global-region task, which shares the lock, must
                // never wait on disk I/O.
                final Settings next;
                try {
                    next = readSettings();
                } catch (IllegalArgumentException | IOException | InvalidConfigurationException e) {
                    getLogger().warning("Reload rejected — " + e.getMessage());
                    sender.sendMessage(
                            "Reload rejected; the configuration is not valid and the previous one "
                                    + "is still active. The server log says which setting.");
                    return true;
                }
                ReloadResult result;
                runtimeLock.lock();
                try {
                    result = reloadRuntime(next);
                } finally {
                    runtimeLock.unlock();
                }
                switch (result) {
                    case RELOADED -> sender.sendMessage("Reloaded.");
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
        MetricsCollector current = collector;
        sender.sendMessage("TickScope " + getDescription().getVersion());
        if (s == null || settings == null || current == null || scheduler == null) {
            // Startup did not get as far as an endpoint; the log has the reason.
            sender.sendMessage("  not running: startup failed, see the server log");
            return;
        }
        for (Map.Entry<String, String> e : current.describe().entrySet()) {
            sender.sendMessage("  " + e.getKey() + ": " + e.getValue());
        }
        sender.sendMessage("  endpoint: " + endpointUrl(settings));
        sender.sendMessage("  collection-interval: " + settings.interval + " ticks");
        sender.sendMessage("  world-scan-interval: "
                + (scheduler.isFolia() ? "disabled on Folia"
                : settings.perWorld || settings.byType ? settings.scanInterval + " ticks"
                : "disabled"));
        for (var entry : current.health().snapshot().entrySet()) {
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
        // State only -- never a token itself.
        sender.sendMessage("  auth: " + (settings.tokens.isEmpty() ? "none"
                : "required (" + settings.tokens.size() + " accepted token"
                + (settings.tokens.size() == 1 ? ")" : "s)")));
        if (scheduler.isFolia()) {
            sender.sendMessage("  tick-metrics: regional TPS"
                    + (s.regionTickDurations().isEmpty() ? " (regional MSPT unavailable)"
                    : " and average MSPT"));
            sender.sendMessage("  region-tps-windows: " + s.regionTps().size());
            sender.sendMessage("  region-mspt-windows: " + s.regionTickDurations().size());
        } else {
            sender.sendMessage(String.format(
                    "  mspt avg %.2f  p95 %.2f  max %.2f  (%d samples)",
                    s.ticks().averageSeconds() * 1000,
                    s.ticks().p95Seconds() * 1000,
                    s.ticks().maximumSeconds() * 1000,
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
        String bind = unbracketed(required(config, "bind-address", "127.0.0.1"));
        long configuredPort = integral(config, "port", 9101L);
        int port = checkedPort(configuredPort);
        String path = required(config, "path", "/metrics");
        if (!path.startsWith("/") || path.length() > 1 && path.endsWith("/")
                || path.indexOf('?') >= 0 || path.indexOf('#') >= 0) {
            throw new IllegalArgumentException(
                    "path must be an absolute path without a trailing '/', query, or fragment");
        }
        // A request target is parsed as a URI before it is compared with this string, so a
        // path that cannot come out of that parser is unreachable: "//" starts an authority,
        // and a literal '%' is decoded away. Better refused now than served as 404 forever.
        if (path.startsWith("//") || path.indexOf('%') >= 0) {
            throw new IllegalArgumentException(
                    "path must not start with '//' or contain '%'");
        }
        List<String> tokens = tokens(config);
        long interval = atLeast(logger, "collection-interval-ticks",
                integral(config, "collection-interval-ticks", 100L), 20L);
        boolean perWorld = bool(config, "per-world", true);
        boolean byType = bool(config, "entity-types.enabled", true);
        long scanInterval = atLeast(logger, "entity-types.interval-ticks",
                integral(config, "entity-types.interval-ticks", 600L), 100L);
        long scanChunks = integral(config, "entity-types.chunks-per-tick", 32L);
        if (scanChunks < 1 || scanChunks > 1000) {
            throw new IllegalArgumentException("entity-types.chunks-per-tick must be between 1 and 1000");
        }
        long connections = integral(config, "max-connections-per-address",
                (long) MetricsHttpServer.DEFAULT_CONNECTIONS_PER_ADDRESS);
        if (connections < 1 || connections > MetricsHttpServer.MAX_HELD_CONNECTIONS) {
            throw new IllegalArgumentException("max-connections-per-address must be between 1 and "
                    + MetricsHttpServer.MAX_HELD_CONNECTIONS);
        }
        return new Settings(serverId, bind, port, path, tokens, interval,
                perWorld, byType, scanInterval, (int) scanChunks,
                stringSet(config, "exclude-worlds"),
                registryKeys(config, "entity-types.allowlist"),
                (int) connections);
    }

    /** The most tokens accepted at once; rotation needs two, anything more is a mistake. */
    static final int MAX_TOKENS = 8;

    /**
     * {@code auth-token} and {@code auth-tokens} together, in that order, so one token keeps
     * working as before and a rotation adds the next one beside it.
     */
    private static List<String> tokens(FileConfiguration config) {
        String single = string(config, "auth-token", "");
        if (single.isEmpty() && config.get("auth-token") instanceof String raw && !raw.isEmpty()) {
            // Whitespace is trimmed, so a token of only spaces would have disabled
            // authentication without anyone meaning it to.
            throw new IllegalArgumentException(
                    "auth-token must not be blank; leave it empty to disable authentication");
        }
        Set<String> tokens = new LinkedHashSet<>();
        if (!single.isEmpty()) tokens.add(single);
        tokens.addAll(stringList(config, "auth-tokens"));
        if (tokens.size() > MAX_TOKENS) {
            throw new IllegalArgumentException("at most " + MAX_TOKENS + " tokens may be accepted at once");
        }
        return List.copyOf(tokens);
    }

    private static Set<String> stringSet(FileConfiguration config, String key) {
        return Set.copyOf(stringList(config, key));
    }

    /** The configured strings, trimmed, in the order written, without blanks or duplicates. */
    private static List<String> stringList(FileConfiguration config, String key) {
        Object value = config.get(key);
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(key + " must be a list of strings");
        }
        Set<String> result = new LinkedHashSet<>();
        for (Object item : list) {
            if (!(item instanceof String text)) {
                throw new IllegalArgumentException(key + " must contain only strings, but has "
                        + quoted(item));
            }
            if (text.isBlank()) {
                throw new IllegalArgumentException(key + " must not contain an empty entry");
            }
            result.add(text.trim());
        }
        return List.copyOf(result);
    }

    /**
     * Entity types are matched by registry key, which is lowercase and carries no namespace
     * in the exported label. Operators copy names from logs and commands, where they appear
     * as {@code minecraft:zombie} or {@code ZOMBIE}, and an entry in that form silently
     * matched nothing. Normalising here keeps the configuration meaning what it says.
     */
    private static Set<String> registryKeys(FileConfiguration config, String key) {
        Set<String> normalised = new LinkedHashSet<>();
        for (String entry : stringSet(config, key)) {
            String name = entry.toLowerCase(Locale.ROOT);
            int namespace = name.indexOf(':');
            if (namespace >= 0) name = name.substring(namespace + 1);
            if (name.isEmpty() || !name.chars().allMatch(c -> c == '_' || c == '/' || c == '.'
                    || c == '-' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9')) {
                throw new IllegalArgumentException(key + " entries must be registry names such "
                        + "as zombie or item_frame, but has " + quoted(entry));
            }
            normalised.add(name);
        }
        return Set.copyOf(normalised);
    }

    /** {@code [::1]} is how URLs and Prometheus targets write the address; bind() wants {@code ::1}. */
    private static String unbracketed(String bind) {
        if (bind.length() > 2 && bind.startsWith("[") && bind.endsWith("]")) {
            return bind.substring(1, bind.length() - 1);
        }
        return bind;
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
        cancelSamplers();

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
        // The player batch in flight during a reload completes into the collector it started
        // under, so without this the replacement would publish no ping or regional series until
        // its own first batch lands, one interval later.
        if (previous != null) collector.carryFoliaPlayers(previous.foliaPlayersState());

        // A previous snapshot is still true after a reload; only its server label can be
        // stale. The placeholder is for the first enable, and for Folia, where onEnable and
        // player commands do not necessarily own world data and the first global-region task
        // fills it in on the next tick.
        Snapshot current = latest;
        boolean keepCurrent = current != null && current.serverId().equals(settings.serverId);

        if (scheduler.isFolia()) {
            if (!keepCurrent) latest = collector.initialSnapshot();
            if (foliaPlayers == null) foliaPlayers = new FoliaPlayerSampler(scheduler);
            sampler = scheduler.repeatGlobal(() -> guarded(this::collectFolia), 1L, settings.interval);
            // The world scan reads chunk and entity data the global region does not own, so it
            // has no safe home on Folia at all.
            getLogger().info("Per-world entity, tile-entity and entity-type metrics are "
                    + "unavailable on Folia: walking a world crosses region ownership boundaries. "
                    + "Chunk and player counts are still published.");
        } else {
            // Paper configuration is applied on the main thread, so publish a real sample now.
            // The sample is guarded like every scheduled one: a failing Bukkit read must not
            // escape with the tasks cancelled and nothing scheduled to replace them.
            Snapshot first = null;
            try {
                first = collector.collectPaper();
            } catch (RuntimeException e) {
                getLogger().warning("Collection failed; retaining the last sample: " + e.getMessage());
            }
            if (first != null) latest = first;
            else if (!keepCurrent) latest = collector.initialSnapshot();
            sampler = scheduler.repeatGlobal(
                    () -> guarded(() -> latest = collector.collectPaper()), settings.interval, settings.interval);
            if (settings.perWorld || settings.byType) {
                scanSampler = scheduler.repeatGlobal(
                        () -> guarded(() -> collector.collectHeavyWorldDataStep(
                                settings.scanChunksPerTick, settings.scanInterval)), 40L, 1L);
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

    private ReloadResult reloadRuntime(Settings next) {
        if (next.sameEndpoint(active)) {
            http.tokens(next.tokens);
        } else {
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
                settings.path, settings.tokens, this::renderMetrics,
                failure -> getLogger().log(Level.WARNING, "Rendering the metrics body failed", failure),
                scrapeStats,
                MetricsHttpServer.Options.withConnectionsPerAddress(settings.maxConnectionsPerAddress));
        try {
            candidate.start();
        } catch (RuntimeException e) {
            candidate.close();
            throw e;
        }
        http = candidate;
    }

    /**
     * Share one rendered body across scrapes until collection, health or the endpoint's own
     * counters change.
     */
    private byte[] renderMetrics() {
        Snapshot snapshot = latest;
        MetricsCollector current = collector;
        long revision = current.health().revision();
        long rejected = scrapeStats.total();
        RenderedMetrics cache = rendered;
        if (cache != null && cache.snapshot == snapshot && cache.collector == current
                && cache.healthRevision == revision && cache.rejectedConnections == rejected) {
            return cache.bytes;
        }
        synchronized (renderLock) {
            cache = rendered;
            if (cache != null && cache.snapshot == snapshot && cache.collector == current
                    && cache.healthRevision == revision && cache.rejectedConnections == rejected) {
                return cache.bytes;
            }
            byte[] bytes = PrometheusWriter.render(snapshot, current.health(), scrapeStats)
                    .getBytes(StandardCharsets.UTF_8);
            rendered = new RenderedMetrics(snapshot, current, revision, rejected, bytes);
            return bytes;
        }
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
                + (settings.tokens.isEmpty() ? "no auth)" : "auth required)"));
    }
}
