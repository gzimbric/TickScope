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
        assertEquals(List.of("secret"), active.tokens());
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
        assertEquals(32, defaults.scanChunksPerTick());
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

    @Test void normalisesAllowlistEntriesToRegistryKeys() {
        var config = new YamlConfiguration();
        // The forms operators copy from logs, commands and the Bukkit enum all mean the same entity.
        config.set("entity-types.allowlist", List.of("Zombie", "minecraft:chicken", " ITEM_FRAME ", "zombie"));
        assertEquals(Set.of("zombie", "chicken", "item_frame"),
                TickScope.parseSettings(config, logger).allowedTypes());
        for (String bad : List.of("zom bie", "minecraft:", "zombie!", "")) {
            config.set("entity-types.allowlist", List.of(bad));
            assertThrows(IllegalArgumentException.class,
                    () -> TickScope.parseSettings(config, logger), "allowlist entry " + bad);
        }
        // World names are exact and case-sensitive; they are not normalised.
        config.set("entity-types.allowlist", List.of());
        config.set("exclude-worlds", List.of("Lobby"));
        assertEquals(Set.of("Lobby"), TickScope.parseSettings(config, logger).excludedWorlds());
    }

    @Test void rejectsPathsTheEndpointCouldNeverServe() {
        var config = new YamlConfiguration();
        for (String bad : List.of("//metrics", "/met%20rics", "metrics", "/metrics/", "/m?x", "/m#f", "")) {
            config.set("path", bad);
            assertThrows(IllegalArgumentException.class,
                    () -> TickScope.parseSettings(config, logger), "path " + bad);
        }
        // A scraper percent-encodes a space, and the request path is compared decoded, so
        // these are reachable and stay allowed.
        for (String ok : List.of("/", "/metrics", "/my metrics", "/a//b", "/métriques")) {
            config.set("path", ok);
            assertEquals(ok, TickScope.parseSettings(config, logger).path());
        }
    }

    @Test void acceptsTheBracketedFormOfAnIpv6BindAddress() {
        var config = new YamlConfiguration();
        config.set("bind-address", "[::1]");
        assertEquals("::1", TickScope.parseSettings(config, logger).bind());
        config.set("bind-address", "::1");
        assertEquals("::1", TickScope.parseSettings(config, logger).bind());
        config.set("bind-address", "0.0.0.0");
        assertEquals("0.0.0.0", TickScope.parseSettings(config, logger).bind());
    }

    @Test void rejectsABlankTokenButAcceptsAnEmptyOne() {
        var config = new YamlConfiguration();
        config.set("auth-token", "   ");
        assertThrows(IllegalArgumentException.class, () -> TickScope.parseSettings(config, logger));
        config.set("auth-token", "");
        assertEquals(List.of(), TickScope.parseSettings(config, logger).tokens());
        config.set("auth-token", " s3cret ");
        assertEquals(List.of("s3cret"), TickScope.parseSettings(config, logger).tokens());
    }

    @Test void acceptsSeveralTokensForRotationAndBoundsTheirNumber() {
        var config = new YamlConfiguration();
        config.set("auth-token", "old");
        config.set("auth-tokens", List.of("new", "old", " spare "));
        var settings = TickScope.parseSettings(config, logger);
        assertEquals(List.of("old", "new", "spare"), settings.tokens(), "auth-token first, deduplicated, trimmed");
        // Tokens are swapped in place, so they never count as an endpoint change.
        config.set("auth-tokens", List.of("rotated"));
        assertTrue(settings.sameEndpoint(TickScope.parseSettings(config, logger)));

        config.set("auth-token", "");
        config.set("auth-tokens", List.of("only"));
        assertEquals(List.of("only"), TickScope.parseSettings(config, logger).tokens());
        config.set("auth-tokens", List.of("a", "b", "c", "d", "e", "f", "g", "h", "i"));
        assertThrows(IllegalArgumentException.class, () -> TickScope.parseSettings(config, logger));
        config.set("auth-tokens", "not-a-list");
        assertThrows(IllegalArgumentException.class, () -> TickScope.parseSettings(config, logger));
        config.set("auth-tokens", List.of(""));
        assertThrows(IllegalArgumentException.class, () -> TickScope.parseSettings(config, logger));
    }

    @Test void boundsTheConnectionsServedPerAddress() {
        var config = new YamlConfiguration();
        assertEquals(2, TickScope.parseSettings(config, logger).maxConnectionsPerAddress());
        for (int bad : List.of(0, -1, 13)) {
            config.set("max-connections-per-address", bad);
            assertThrows(IllegalArgumentException.class,
                    () -> TickScope.parseSettings(config, logger), "cap " + bad);
        }
        config.set("max-connections-per-address", 4);
        var raised = TickScope.parseSettings(config, logger);
        assertEquals(4, raised.maxConnectionsPerAddress());
        // The listener is built from the cap, so changing it is an endpoint change.
        assertFalse(raised.sameEndpoint(TickScope.parseSettings(new YamlConfiguration(), logger)));
    }

    @Test void rejectsWrongValueTypesInsteadOfApplyingDefaults() {
        var config = new YamlConfiguration();
        // The javadoc's own motivating cases: a quoted boolean or number must not silently
        // become the default.
        config.set("per-world", "false");
        assertThrows(IllegalArgumentException.class, () -> TickScope.parseSettings(config, logger));
        config.set("per-world", false);
        config.set("entity-types.enabled", "true");
        assertThrows(IllegalArgumentException.class, () -> TickScope.parseSettings(config, logger));
        config.set("entity-types.enabled", false);
        assertFalse(TickScope.parseSettings(config, logger).perWorld());
        assertFalse(TickScope.parseSettings(config, logger).byType());
        config.set("server-id", 42);
        assertThrows(IllegalArgumentException.class, () -> TickScope.parseSettings(config, logger));
    }

    @Test void raisesTooFastIntervalsToTheMinimumInsteadOfRejectingThem() {
        var config = new YamlConfiguration();
        config.set("collection-interval-ticks", 5);
        config.set("entity-types.interval-ticks", 50);
        var settings = TickScope.parseSettings(config, logger);
        assertEquals(20, settings.interval());
        assertEquals(100, settings.scanInterval());
        config.set("collection-interval-ticks", 20);
        config.set("entity-types.interval-ticks", 100);
        assertEquals(20, TickScope.parseSettings(config, logger).interval());
        assertEquals(100, TickScope.parseSettings(config, logger).scanInterval());
    }

    @Test void validatesChunkBudget() {
        var config = new YamlConfiguration();
        for (int bad : List.of(0, -1, 1001)) {
            config.set("entity-types.chunks-per-tick", bad);
            assertThrows(IllegalArgumentException.class,
                    () -> TickScope.parseSettings(config, logger));
        }
        config.set("entity-types.chunks-per-tick", 16);
        assertEquals(16, TickScope.parseSettings(config, logger).scanChunksPerTick());
    }
}
