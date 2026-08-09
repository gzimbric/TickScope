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

class MetricsCollectorTest {

    @Test
    void summarisesTickTimesWithNearestRankQuantiles() {
        Snapshot.Ticks ticks = MetricsCollector.summarise(new long[]{
                4_000_000, 0, 2_000_000, 1_000_000, 3_000_000
        });

        assertEquals(2.5, ticks.avgMs());
        assertEquals(1.0, ticks.minMs());
        assertEquals(4.0, ticks.maxMs());
        assertEquals(2.0, ticks.p50Ms());
        assertEquals(4.0, ticks.p95Ms());
        assertEquals(4.0, ticks.p99Ms());
        assertEquals(4, ticks.samples());
    }

    @Test
    void emptyAndUnfilledTickWindowsProduceAnEmptySummary() {
        assertEquals(Snapshot.Ticks.EMPTY, MetricsCollector.summarise(null));
        assertEquals(Snapshot.Ticks.EMPTY, MetricsCollector.summarise(new long[0]));
        assertEquals(Snapshot.Ticks.EMPTY, MetricsCollector.summarise(new long[]{0, 0}));
    }
}
