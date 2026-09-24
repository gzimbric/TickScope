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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Independent of snapshots so failures remain visible when snapshot publication stops. */
final class CollectionHealth {
    record Reading(boolean enabled, double lastSuccess, long failures, int completed, int expected) {}
    private final Map<String, AtomicReference<Reading>> stages = new LinkedHashMap<>();

    CollectionHealth(boolean worldScan, boolean folia) {
        stages.put("main", stage(true));
        stages.put("world", stage(worldScan));
        stages.put("players", stage(folia));
    }

    private static AtomicReference<Reading> stage(boolean enabled) {
        return new AtomicReference<>(new Reading(enabled, 0, 0, 0, 0));
    }

    void success(String stage) {
        stages.get(stage).updateAndGet(r -> new Reading(r.enabled(), now(), r.failures(),
                r.completed(), r.expected()));
    }

    void failure(String stage) {
        stages.get(stage).updateAndGet(r -> new Reading(r.enabled(), r.lastSuccess(),
                r.failures() + 1, r.completed(), r.expected()));
    }

    void players(int completed, int expected) {
        stages.get("players").updateAndGet(r -> new Reading(r.enabled(),
                completed > 0 || expected == 0 ? now() : r.lastSuccess(),
                r.failures() + (completed < expected ? 1 : 0), completed, expected));
    }

    Map<String, Reading> snapshot() {
        Map<String, Reading> copy = new LinkedHashMap<>();
        stages.forEach((name, reading) -> copy.put(name, reading.get()));
        return Map.copyOf(copy);
    }

    private static double now() { return System.currentTimeMillis() / 1000d; }
}
