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

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class SettingsTest {
    private final Logger logger = Logger.getLogger("SettingsTest");
    @TempDir Path directory;

    @Test void rejectsMalformedAndMissingFilesInsteadOfApplyingDefaults() throws Exception {
        Path file = directory.resolve("config.yml");
        Files.writeString(file, "auth-token: secret\nport: 9200\n");
        var active = TickScope.readSettings(file.toFile(), logger);
        Files.writeString(file, "auth-token: [unterminated\n");
        assertThrows(InvalidConfigurationException.class,
                () -> TickScope.readSettings(file.toFile(), logger));
        assertEquals("secret", active.token());
        assertEquals(9200, active.port());
        Files.delete(file);
        assertThrows(IOException.class, () -> TickScope.readSettings(file.toFile(), logger));
    }

    @Test void validatesPortBeforeNarrowingAndRejectsWrongTypes() {
        for (Object port : List.of(4294976397L, Long.MAX_VALUE, Long.MIN_VALUE, 0, -1, 65536, "9200", 9200.5)) {
            var config = new YamlConfiguration(); config.set("port", port);
            assertThrows(IllegalArgumentException.class, () -> TickScope.parseSettings(config, logger), "port=" + port);
        }
        for (int port : List.of(1, 9101, 65535)) {
            var config = new YamlConfiguration(); config.set("port", port);
            assertEquals(port, TickScope.parseSettings(config, logger).port());
        }
    }

    @Test void suppliesDefaultsAndValidatesFilters() {
        var config = new YamlConfiguration();
        var defaults = TickScope.parseSettings(config, logger);
        assertEquals(9101, defaults.port());
        assertTrue(defaults.excludedWorlds().isEmpty());
        config.set("exclude-worlds", List.of("lobby"));
        config.set("entity-types.allowlist", List.of("zombie", "chicken"));
        var filtered = TickScope.parseSettings(config, logger);
        assertEquals(Set.of("lobby"), filtered.excludedWorlds());
        assertEquals(Set.of("zombie", "chicken"), filtered.allowedTypes());
        config.set("exclude-worlds", "lobby");
        assertThrows(IllegalArgumentException.class, () -> TickScope.parseSettings(config, logger));
        config.set("exclude-worlds", List.of(42));
        assertThrows(IllegalArgumentException.class, () -> TickScope.parseSettings(config, logger));
    }
}
