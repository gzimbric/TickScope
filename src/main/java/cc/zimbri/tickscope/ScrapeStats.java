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

import java.util.concurrent.atomic.LongAdder;

/**
 * What the endpoint turned away. A connection closed by the per-address cap or by a saturated
 * worker pool gets no response, so from the scraper's side it is indistinguishable from a
 * network fault; these counters are the only trace. They live in the plugin, not the server,
 * so an endpoint restart on reload does not reset them.
 */
final class ScrapeStats {

    private final LongAdder perAddress = new LongAdder();
    private final LongAdder saturated = new LongAdder();

    void rejectedPerAddress() {
        perAddress.increment();
    }

    void rejectedSaturated() {
        saturated.increment();
    }

    long perAddress() {
        return perAddress.sum();
    }

    long saturated() {
        return saturated.sum();
    }

    /** Changes rarely, so it can key the rendered-body cache without defeating it. */
    long total() {
        return perAddress.sum() + saturated.sum();
    }
}
