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

class PrometheusWriterTest {

    @Test
    void rendersBuildInformationAndEscapesLabelValues() {
        Snapshot snapshot = Snapshot.empty("srv\"\\\n", "1.2.1", "Paper \"test\"", "17");

        String rendered = PrometheusWriter.render(snapshot);

        assertTrue(rendered.contains("# TYPE mc_tickscope_info gauge\n"));
        assertTrue(rendered.contains(
                "mc_tickscope_info{server=\"srv\\\"\\\\\\n\",version=\"1.2.1\","
                        + "paper=\"Paper \\\"test\\\"\",java=\"17\",platform=\"paper\"} 1\n"));
        assertTrue(rendered.contains("# TYPE mc_entity_collection_duration_seconds gauge\n"));
    }

    @Test
    void snapshotDefensivelyCopiesTickArrays() {
        Snapshot snapshot = Snapshot.empty("paper", "1", "Paper", "17");
        double[] tps = snapshot.tps();
        tps[0] = 99;

        assertEquals(0, snapshot.tps()[0]);
    }

    @Test
    void foliaDoesNotMislabelGlobalTickDataAsServerMetrics() {
        Snapshot snapshot = Snapshot.empty("folia", "1", "Folia", "21", "folia");

        String rendered = PrometheusWriter.render(snapshot);

        assertTrue(rendered.contains("platform=\"folia\""));
        assertTrue(!rendered.contains("# TYPE mc_mspt_ms"));
        assertTrue(!rendered.contains("# TYPE mc_tps"));
    }
}
