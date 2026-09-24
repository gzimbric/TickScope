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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsCollectorTest {

    @Test
    void summarisesTickTimesWithNearestRankQuantiles() {
        Snapshot.Ticks ticks = MetricsCollector.summarise(new long[]{
                4_000_000, 0, 2_000_000, 1_000_000, 3_000_000
        });

        assertEquals(0.0025, ticks.averageSeconds());
        assertEquals(0.001, ticks.minimumSeconds());
        assertEquals(0.004, ticks.maximumSeconds());
        assertEquals(0.002, ticks.p50Seconds());
        assertEquals(0.004, ticks.p95Seconds());
        assertEquals(0.004, ticks.p99Seconds());
        assertEquals(4, ticks.samples());
    }

    @Test
    void treatsUnavailableMxBeanReadingsAsMissingRatherThanZero() {
        // These beans use -1 for "cannot supply this", and OpenJ9 returns it on the first
        // CPU-load call, which is the one made moments after startup.
        assertTrue(Double.isNaN(MetricsCollector.ratio(-1d)));
        assertTrue(Double.isNaN(MetricsCollector.ratio(Double.NaN)));
        assertTrue(Double.isNaN(MetricsCollector.cpuSeconds(-1L)));

        assertEquals(0d, MetricsCollector.ratio(0d));
        assertEquals(0.25d, MetricsCollector.ratio(0.25d));
        assertEquals(2d, MetricsCollector.cpuSeconds(2_000_000_000L));
    }

    @Test
    void emptyAndUnfilledTickWindowsProduceAnEmptySummary() {
        assertEquals(Snapshot.Ticks.EMPTY, MetricsCollector.summarise(null));
        assertEquals(Snapshot.Ticks.EMPTY, MetricsCollector.summarise(new long[0]));
        assertEquals(Snapshot.Ticks.EMPTY, MetricsCollector.summarise(new long[]{0, 0}));
    }
}
