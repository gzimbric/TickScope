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

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * Keeps Folia-only API references behind reflection so the same Java 17 jar can still load on
 * Paper 1.18.2. Modern Paper exposes the Folia scheduler interfaces too, but Folia itself is
 * detected by its RegionizedServer class so Paper retains its exact server-wide tick metrics.
 */
interface PlatformScheduler {

    TaskHandle repeatGlobal(Runnable task, long delay, long period);

    boolean executeFor(Player player, Runnable task, Runnable retired);

    boolean isFolia();

    interface TaskHandle {
        void cancel();
    }

    static PlatformScheduler create(Plugin plugin) {
        boolean folia;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            folia = true;
        } catch (ClassNotFoundException notFolia) {
            folia = false;
        }
        if (!folia) return new Paper(plugin);
        try {
            return new Folia(plugin);
        } catch (ReflectiveOperationException e) {
            // Detection already established that this is Folia. A scheduler class that has moved
            // is an API break and must say so; sharing a catch with the detection lookup would
            // have silently selected Paper's scheduler, which Folia then refuses to run.
            throw new IllegalStateException("Folia scheduler API is unavailable", e);
        }
    }

    final class Paper implements PlatformScheduler {
        private final Plugin plugin;

        private Paper(Plugin plugin) {
            this.plugin = plugin;
        }

        @Override
        public TaskHandle repeatGlobal(Runnable task, long delay, long period) {
            BukkitTask scheduled = plugin.getServer().getScheduler()
                    .runTaskTimer(plugin, task, delay, period);
            return scheduled::cancel;
        }

        @Override
        public boolean executeFor(Player player, Runnable task, Runnable retired) {
            plugin.getServer().getScheduler().runTask(plugin, task);
            return true;
        }

        @Override
        public boolean isFolia() {
            return false;
        }
    }

    final class Folia implements PlatformScheduler {
        private final Plugin plugin;
        private final Object globalScheduler;
        private final Method repeatGlobal;
        private final Method entityScheduler;
        private final Method runEntity;
        private final Method cancelTask;

        private Folia(Plugin plugin) throws ReflectiveOperationException {
            this.plugin = plugin;
            Class<?> globalType = Class.forName(
                    "io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler");
            Class<?> entityType = Class.forName(
                    "io.papermc.paper.threadedregions.scheduler.EntityScheduler");
            Class<?> scheduledType = Class.forName(
                    "io.papermc.paper.threadedregions.scheduler.ScheduledTask");

            globalScheduler = Class.forName("org.bukkit.Server")
                    .getMethod("getGlobalRegionScheduler").invoke(plugin.getServer());
            repeatGlobal = globalType.getMethod("runAtFixedRate", Plugin.class,
                    Consumer.class, long.class, long.class);
            entityScheduler = Class.forName("org.bukkit.entity.Entity")
                    .getMethod("getScheduler");
            runEntity = entityType.getMethod("run", Plugin.class, Consumer.class, Runnable.class);
            cancelTask = scheduledType.getMethod("cancel");
        }

        @Override
        public TaskHandle repeatGlobal(Runnable task, long delay, long period) {
            try {
                Consumer<Object> callback = ignored -> task.run();
                Object scheduled = repeatGlobal.invoke(
                        globalScheduler, plugin, callback, delay, period);
                return () -> invoke(cancelTask, scheduled);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw schedulerFailure(e);
            }
        }

        @Override
        public boolean executeFor(Player player, Runnable task, Runnable retired) {
            try {
                Object scheduler = entityScheduler.invoke(player);
                Consumer<Object> callback = ignored -> task.run();
                return runEntity.invoke(scheduler, plugin, callback, retired) != null;
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw schedulerFailure(e);
            }
        }

        @Override
        public boolean isFolia() {
            return true;
        }

        private static void invoke(Method method, Object target) {
            try {
                method.invoke(target);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw schedulerFailure(e);
            }
        }

        private static IllegalStateException schedulerFailure(ReflectiveOperationException e) {
            Throwable cause = e instanceof InvocationTargetException invocation
                    ? invocation.getCause() : e;
            return new IllegalStateException("Folia scheduler operation failed", cause);
        }
    }
}
