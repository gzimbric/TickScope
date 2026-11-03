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

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TickHistogramTest {

    private static final double[] BOUNDS = Snapshot.TickHistogram.BOUNDS;

    @Test
    void bucketsEachTickOnceWithInclusiveUpperBounds() {
        var histogram = new TickHistogram();
        histogram.record(0.004);        // le=0.005
        histogram.record(0.05);         // exactly the tick budget: le=0.05, not the next bucket
        histogram.record(0.0500001);    // le=0.075
        histogram.record(3);            // beyond the last bound: overflow bucket only

        Snapshot.TickHistogram snapshot = histogram.snapshot();
        long[] cumulative = snapshot.cumulativeCounts();
        assertEquals(1, cumulative[0]);
        assertEquals(1, cumulative[4], "le=0.04 does not include the 0.05 tick");
        assertEquals(2, cumulative[5], "le=0.05 includes the tick on its bound");
        assertEquals(3, cumulative[6]);
        assertEquals(3, cumulative[BOUNDS.length - 1], "le=2.5 excludes the 3 s tick");
        assertEquals(4, cumulative[BOUNDS.length], "+Inf holds everything");
        assertEquals(4, snapshot.count());
        assertEquals(0.004 + 0.05 + 0.0500001 + 3, snapshot.sumSeconds(), 1e-12);
    }

    @Test
    void snapshotsAreIndependentOfLaterTicks() {
        var histogram = new TickHistogram();
        histogram.record(0.001);
        Snapshot.TickHistogram first = histogram.snapshot();
        histogram.record(0.001);
        long[] counts = first.cumulativeCounts();
        counts[0] = 99;
        assertEquals(1, first.cumulativeCounts()[0]);
        assertEquals(2, histogram.snapshot().cumulativeCounts()[0]);
    }

    @Test
    void theEventReportsMillisecondsAndIsConvertedToSeconds() {
        var histogram = new TickHistogram();
        histogram.onTickEnd(new ServerTickEndEvent(1, 25.0, 0));
        long[] cumulative = histogram.snapshot().cumulativeCounts();
        assertEquals(0, cumulative[2], "25 ms is above le=0.02");
        assertEquals(1, cumulative[3], "and within le=0.03");
        assertEquals(0.025, histogram.snapshot().sumSeconds(), 1e-12);
    }

    @Test
    void boundsAreSortedAndTheEmptyHistogramHasOneSlotPerBoundPlusOverflow() {
        for (int i = 1; i < BOUNDS.length; i++) assertTrue(BOUNDS[i] > BOUNDS[i - 1]);
        assertEquals(BOUNDS.length + 1, Snapshot.TickHistogram.EMPTY.cumulativeCounts().length);
        assertEquals(0, Snapshot.TickHistogram.EMPTY.count());
        assertFalse(PrometheusWriter.render(Snapshot.empty("paper", "1", "Paper", "17"))
                .contains("mc_tick_time_seconds"), "absent until the first tick is observed");
    }
}
