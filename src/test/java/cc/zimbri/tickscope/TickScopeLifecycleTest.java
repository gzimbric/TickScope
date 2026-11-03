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
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginLoader;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the real plugin object through enable, reload and disable against a proxied server.
 *
 * <p>Bukkit only constructs plugins through its own class loader, so the instance is allocated
 * without running a constructor and then initialised through {@code JavaPlugin.init}, the same
 * entry point the plugin loader uses. The Folia branch is reached by swapping the scheduler for
 * a fake Folia one before a reload, because {@code PlatformScheduler.create} always picks Paper
 * on the test classpath.
 */
class TickScopeLifecycleTest {

    @TempDir Path dir;
    private Server previousServer;
    private final AtomicBoolean failCollection = new AtomicBoolean();
    private final List<Player> online = new ArrayList<>();
    private final List<Timer> timers = new ArrayList<>();
    private final List<Runnable> globalTasks = new ArrayList<>();
    private final List<Runnable> entityTasks = new ArrayList<>();
    private final List<String> messages = new ArrayList<>();
    private int disableCalls;
    private TickScope plugin;
    private int port;

    /** A Paper scheduler task as the plugin sees it. */
    private static final class Timer {
        final Runnable runnable;
        final long period;
        boolean cancelled;

        Timer(Runnable runnable, long period) {
            this.runnable = runnable;
            this.period = period;
        }
    }

    private final PlatformScheduler folia = new PlatformScheduler() {
        @Override public TaskHandle repeatGlobal(Runnable task, long delay, long period) {
            globalTasks.add(task);
            return () -> globalTasks.remove(task);
        }

        @Override public boolean executeFor(Player player, Runnable task, Runnable retired) {
            entityTasks.add(task);
            return true;
        }

        @Override public boolean isFolia() {
            return true;
        }
    };

    @BeforeEach
    void install() throws Exception {
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        PluginManager manager = (PluginManager) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PluginManager.class}, (p, m, a) -> switch (m.getName()) {
                    case "getPlugins" -> new Plugin[0];
                    case "registerEvents" -> null;
                    case "disablePlugin" -> {
                        // What the plugin loader does: setEnabled(false), which runs onDisable.
                        disableCalls++;
                        setEnabled((JavaPlugin) a[0], false);
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        BukkitScheduler scheduler = (BukkitScheduler) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{BukkitScheduler.class}, (p, m, a) -> switch (m.getName()) {
                    case "runTaskTimer" -> {
                        Timer timer = new Timer((Runnable) a[1], (Long) a[3]);
                        timers.add(timer);
                        yield Proxy.newProxyInstance(getClass().getClassLoader(),
                                new Class<?>[]{BukkitTask.class}, (p2, m2, a2) -> switch (m2.getName()) {
                                    case "cancel" -> {
                                        timer.cancelled = true;
                                        yield null;
                                    }
                                    case "isCancelled" -> timer.cancelled;
                                    default -> throw new UnsupportedOperationException(m2.getName());
                                });
                    }
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        Logger logger = Logger.getLogger("TickScopeLifecycleTest");
        Server server = (Server) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Server.class}, (p, m, a) -> switch (m.getName()) {
                    case "getVersion", "getName" -> "test";
                    case "getLogger" -> logger;
                    case "getPluginManager" -> manager;
                    case "getScheduler" -> scheduler;
                    case "getWorlds" -> List.of();
                    case "getOnlinePlayers" -> List.copyOf(online);
                    case "getTickTimes" -> new long[]{1_000_000};
                    case "getTPS" -> new double[]{20, 20, 20};
                    case "getMaxPlayers" -> {
                        if (failCollection.get()) throw new IllegalStateException("collection failure");
                        yield 20;
                    }
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        Field field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        previousServer = (Server) field.get(null);
        field.set(null, server);

        plugin = allocate();
        File dataFolder = dir.resolve("TickScope").toFile();
        assertTrue(dataFolder.mkdirs());
        Method init = null;
        for (Method candidate : JavaPlugin.class.getDeclaredMethods()) {
            if (candidate.getName().equals("init") && candidate.getParameterCount() == 6
                    && candidate.getParameterTypes()[0] == PluginLoader.class) {
                init = candidate;
            }
        }
        assertNotNull(init, "JavaPlugin.init(PluginLoader, Server, PluginDescriptionFile, File, File, ClassLoader)");
        init.setAccessible(true);
        init.invoke(plugin, null, server, new PluginDescriptionFile("TickScope", "test",
                TickScope.class.getName()), dataFolder, dir.resolve("TickScope.jar").toFile(),
                getClass().getClassLoader());
        writeConfig();
    }

    @AfterEach
    void restore() throws Exception {
        if (plugin != null && plugin.isEnabled()) setEnabled(plugin, false);
        Field field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        field.set(null, previousServer);
    }

    @Test
    void aFailedEnableDisablesThePluginAndALaterEnableStartsCleanly() throws Exception {
        try (ServerSocket occupied = new ServerSocket(port)) {
            enable();
            assertFalse(plugin.isEnabled(), "the plugin disabled itself from onEnable");
            assertEquals(1, disableCalls);
            assertTrue(timers.stream().allMatch(t -> t.cancelled), "onDisable cancelled the tasks");
            assertNull(field("http"));
            assertTrue(noTickScopeThreads());
            assertEquals(port, occupied.getLocalPort());
        }

        // The operator frees the port and enables the plugin again without a restart.
        enable();
        assertTrue(plugin.isEnabled());
        assertEquals(200, scrape().status);
        assertFalse((boolean) field("stopped"));
    }

    @Test
    void aReEnabledPluginCollectsAgainOnFolia() throws Exception {
        online.add(player(30));
        enable();
        switchToFolia();
        runGlobal();
        assertEquals(20, latest().playersMax());
        Object sampler = field("foliaPlayers");
        assertEquals(1, entityTasks.size());
        runEntityTasks();

        disable();
        assertNull(field("foliaPlayers"), "onDisable lets go of the closed sampler");
        assertTrue(globalTasks.isEmpty(), "onDisable cancelled the global task");
        assertTrue(noTickScopeThreads());

        enable();
        assertFalse((boolean) field("stopped"), "a new enable clears the shutdown flag");
        switchToFolia();
        runGlobal();
        assertEquals(20, latest().playersMax(), "collection runs again after the re-enable");
        assertNotSame(sampler, field("foliaPlayers"), "a fresh sampler replaces the closed one");
        assertEquals(1, entityTasks.size(), "the fresh sampler schedules the player's task");
        runEntityTasks();
        runGlobal();
        assertTrue(scrape().body.contains("mc_player_ping_seconds{server=\"paper\",statistic=\"avg\"} 0.03\n"));
    }

    @Test
    void aFoliaReloadKeepsTheSnapshotAndTheLastPlayerSample() throws Exception {
        online.add(player(30));
        online.add(player(50));
        enable();
        switchToFolia();
        runGlobal();
        runEntityTasks();
        runGlobal();
        runEntityTasks();
        Snapshot real = latest();
        assertEquals(2, real.pingSamples());
        MetricsCollector before = collector();

        reload();
        assertSame(real, latest(), "a reload keeps the snapshot it already has");
        assertNotSame(before, collector());
        assertEquals(2, collector().foliaPlayerSample().pingSamples(), "the accepted sample is carried");

        // The first cycle under the new configuration starts a batch; until it lands the
        // carried sample keeps the ping and regional series present.
        runGlobal();
        assertEquals(2, latest().pingSamples());
        assertTrue(scrape().body.contains("mc_player_ping_seconds{server=\"paper\",statistic=\"avg\"} 0.04\n"));
        assertEquals(0d, collector().health().snapshot().get("players").lastSuccess(),
                "health starts fresh under the new configuration");
        runEntityTasks();
        assertTrue(collector().health().snapshot().get("players").lastSuccess() > 0);
    }

    @Test
    void aRenamedServerPublishesAPlaceholderWithRealJvmReadings() throws Exception {
        enable();
        switchToFolia();
        runGlobal();

        writeConfig("server-id: \"renamed\"");
        reload();
        Snapshot placeholder = latest();
        assertEquals("renamed", placeholder.serverId());
        assertEquals(0, placeholder.playersMax(), "the old snapshot is not republished under a new label");
        assertTrue(placeholder.jvm().heapUsed() > 0);
        assertTrue(placeholder.proc().uptimeSeconds() > 0);
        String body = scrape().body;
        assertTrue(body.contains("mc_players_max{server=\"renamed\"} 0\n"), body);
        assertFalse(body.contains("mc_uptime_seconds{server=\"renamed\"} 0\n"), body);

        runGlobal();
        assertEquals(20, latest().playersMax());
    }

    @Test
    void aFailingFirstSampleDoesNotLeaveAReloadHalfApplied() throws Exception {
        enable();
        assertEquals(2, activeTimers(), "main sampler and world scan");

        failCollection.set(true);
        writeConfig("server-id: \"renamed\"");
        reload();
        assertEquals(2, activeTimers(), "replacement tasks were scheduled despite the failure");
        assertEquals("renamed", ((TickScope.Settings) field("active")).serverId());
        String body = scrape().body;
        assertTrue(body.contains("mc_collection_failures_total{server=\"renamed\",stage=\"main\"} 1\n"), body);

        failCollection.set(false);
        runPaperSampler();
        assertTrue(scrape().body.contains("mc_players_max{server=\"renamed\"} 20\n"));
    }

    @Test
    void aRejectedReloadLeavesTheRuntimeUntouched() throws Exception {
        enable();
        Object http = field("http");
        MetricsCollector before = collector();

        writeConfig("port: \"oops\"");
        messages.clear();
        plugin.onCommand(sender(), null, "tickscope", new String[]{"reload"});
        assertTrue(messages.get(messages.size() - 1).startsWith("Reload rejected"));
        assertSame(http, field("http"));
        assertSame(before, collector());
        assertEquals(2, activeTimers());
        assertEquals(200, scrape().status);
    }

    @Test
    void aTokenOnlyReloadKeepsTheListenerAndAcceptsTheNewTokens() throws Exception {
        enable();
        Object http = field("http");
        writeConfig("auth-token: \"first\"");
        reload();
        assertSame(http, field("http"), "tokens are swapped in place, the socket stays open");
        assertEquals(401, scrape(null).status);
        assertEquals(200, scrape("Bearer first").status);

        writeConfig("auth-token: \"first\"", "auth-tokens: [second]");
        reload();
        assertSame(http, field("http"));
        assertEquals(200, scrape("Bearer first").status);
        assertEquals(200, scrape("Bearer second").status);
        messages.clear();
        plugin.onCommand(sender(), null, "tickscope", new String[]{"status"});
        assertTrue(messages.contains("  auth: required (2 accepted tokens)"), messages.toString());

        writeConfig("auth-token: \"second\"");
        reload();
        assertEquals(401, scrape("Bearer first").status, "the retired token stops working at once");
        assertEquals(200, scrape("Bearer second").status);
    }

    @Test
    void theRenderedBodyFollowsHealthChangesWithoutANewSnapshot() throws Exception {
        enable();
        Snapshot snapshot = latest();
        assertTrue(scrape().body.contains("mc_collection_failures_total{server=\"paper\",stage=\"world\"} 0\n"));
        collector().health().failure("world");
        assertSame(snapshot, latest(), "no new snapshot was published");
        assertTrue(scrape().body.contains("mc_collection_failures_total{server=\"paper\",stage=\"world\"} 1\n"),
                "a health change alone re-renders the shared body");
    }

    @Test
    void statusSaysWhenNoWorldScanIsScheduled() throws Exception {
        writeConfig("per-world: false", "entity-types:", "  enabled: false");
        enable();
        assertEquals(1, activeTimers(), "only the main sampler runs");
        messages.clear();
        plugin.onCommand(sender(), null, "tickscope", new String[]{"status"});
        assertTrue(messages.contains("  world-scan-interval: disabled"), messages.toString());
        assertFalse(messages.stream().anyMatch(m -> m.contains("world collection")), messages.toString());
    }

    @Test
    void statusBeforeAStartupReportsNotRunning() {
        plugin.onCommand(sender(), null, "tickscope", new String[]{"status"});
        assertTrue(messages.stream().anyMatch(m -> m.contains("not running")), messages.toString());
    }

    // ---- driving the plugin ------------------------------------------------------------

    private void enable() throws Exception {
        setEnabled(plugin, true);
    }

    private void disable() throws Exception {
        setEnabled(plugin, false);
        assertFalse(plugin.isEnabled());
    }

    private void reload() {
        messages.clear();
        plugin.onCommand(sender(), null, "tickscope", new String[]{"reload"});
        assertEquals("Reloaded.", messages.get(messages.size() - 1));
    }

    private void switchToFolia() throws Exception {
        set("scheduler", folia);
        reload();
    }

    private void runGlobal() {
        assertEquals(1, globalTasks.size(), "exactly one Folia collection task");
        globalTasks.get(0).run();
    }

    private void runEntityTasks() {
        List<Runnable> pending = List.copyOf(entityTasks);
        entityTasks.clear();
        for (Runnable task : pending) task.run();
    }

    private void runPaperSampler() {
        List<Timer> active = timers.stream().filter(t -> !t.cancelled && t.period == 100).toList();
        assertEquals(1, active.size(), "exactly one active main sampler");
        active.get(0).runnable.run();
    }

    private long activeTimers() {
        return timers.stream().filter(t -> !t.cancelled).count();
    }

    private Snapshot latest() throws Exception {
        return (Snapshot) field("latest");
    }

    private MetricsCollector collector() throws Exception {
        return (MetricsCollector) field("collector");
    }

    /** Lines replace the defaults with the same key; everything else is left at its default. */
    private void writeConfig(String... lines) throws IOException {
        StringBuilder yaml = new StringBuilder("bind-address: \"127.0.0.1\"\n");
        boolean portGiven = false;
        for (String line : lines) {
            yaml.append(line).append('\n');
            portGiven |= line.startsWith("port:");
        }
        if (!portGiven) yaml.append("port: ").append(port).append('\n');
        Files.writeString(dir.resolve("TickScope").resolve("config.yml"), yaml.toString());
    }

    private CommandSender sender() {
        return (CommandSender) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{CommandSender.class}, (p, m, a) -> {
                    if (m.getName().equals("sendMessage") && a[0] instanceof String s) messages.add(s);
                    return null;
                });
    }

    private static Player player(int ping) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getPing" -> ping;
                    case "getLocation" -> new Location(null, 0, 0, 0);
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "Player(" + ping + ")";
                    default -> method.getReturnType().isPrimitive() ? 0 : null;
                });
    }

    private static boolean noTickScopeThreads() throws InterruptedException {
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (Thread.getAllStackTraces().keySet().stream()
                    .noneMatch(t -> t.isAlive() && t.getName().startsWith("TickScope-http"))) return true;
            Thread.sleep(20);
        }
        return false;
    }

    private record Response(int status, String body) {}

    private Response scrape() throws IOException {
        return scrape(null);
    }

    private Response scrape(String authorization) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            String request = "GET /metrics HTTP/1.1\r\nHost: x\r\n"
                    + (authorization == null ? "" : "Authorization: " + authorization + "\r\n") + "\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String status = reader.readLine();
            StringBuilder body = new StringBuilder();
            String line;
            boolean inBody = false;
            while ((line = reader.readLine()) != null) {
                if (inBody) body.append(line).append('\n');
                else if (line.isEmpty()) inBody = true;
            }
            return new Response(Integer.parseInt(status.split(" ")[1]), body.toString());
        }
    }

    // ---- reflection ----------------------------------------------------------------------

    /**
     * Bukkit's plugin constructor insists on its own class loader, so the instance is created
     * without one. Field initialisers are skipped by that, hence every final field is set here
     * and checked, so that adding one to the plugin fails this harness loudly.
     */
    private static TickScope allocate() throws Exception {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeType.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        TickScope instance = (TickScope) unsafeType.getMethod("allocateInstance", Class.class)
                .invoke(theUnsafe.get(null), TickScope.class);
        set(instance, "foliaPlayerSampleGeneration", new AtomicLong());
        set(instance, "runtimeLock", new ReentrantLock());
        set(instance, "renderLock", new Object());
        for (Field field : TickScope.class.getDeclaredFields()) {
            if (Modifier.isFinal(field.getModifiers()) && !Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                assertNotNull(field.get(instance), "final field " + field.getName() + " needs a value in allocate()");
            }
        }
        return instance;
    }

    private static void setEnabled(JavaPlugin plugin, boolean enabled) throws Exception {
        Method setEnabled = JavaPlugin.class.getDeclaredMethod("setEnabled", boolean.class);
        setEnabled.setAccessible(true);
        setEnabled.invoke(plugin, enabled);
    }

    private void set(String name, Object value) throws Exception {
        set(plugin, name, value);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = TickScope.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private Object field(String name) throws Exception {
        Field field = TickScope.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(plugin);
    }
}
