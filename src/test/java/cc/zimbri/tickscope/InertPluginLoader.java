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

import org.bukkit.event.Event;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginLoader;
import org.bukkit.plugin.RegisteredListener;

import java.io.File;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Modern Paper's {@code JavaPlugin.init} resolves a {@link PluginLoader} through
 * {@code ServiceLoader}, which only the server jar provides. This inert one, registered in
 * {@code META-INF/services}, lets {@link TickScopeLifecycleTest} drive the real plugin object on
 * every supported API version. Nothing in the plugin ever calls it.
 */
public final class InertPluginLoader implements PluginLoader {

    public Plugin loadPlugin(File file) {
        throw new UnsupportedOperationException("test loader");
    }

    public PluginDescriptionFile getPluginDescription(File file) {
        throw new UnsupportedOperationException("test loader");
    }

    public Pattern[] getPluginFileFilters() {
        return new Pattern[0];
    }

    public Map<Class<? extends Event>, Set<RegisteredListener>> createRegisteredListeners(
            Listener listener, Plugin plugin) {
        return Map.of();
    }

    public void enablePlugin(Plugin plugin) {
        throw new UnsupportedOperationException("test loader");
    }

    public void disablePlugin(Plugin plugin) {
        throw new UnsupportedOperationException("test loader");
    }
}
