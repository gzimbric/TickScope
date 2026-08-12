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

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TickScopeTest {

    @Test
    void emptyTokenDisablesAuthentication() {
        assertTrue(MetricsHttpServer.authorised("", null));
    }

    @Test
    void acceptsOnlyTheCompleteBearerToken() {
        assertTrue(MetricsHttpServer.authorised("secret", "Bearer secret"));
        assertFalse(MetricsHttpServer.authorised("secret", null));
        assertFalse(MetricsHttpServer.authorised("secret", "Basic secret"));
        assertFalse(MetricsHttpServer.authorised("secret", "Bearer secre"));
        assertFalse(MetricsHttpServer.authorised("secret", "Bearer secret-extra"));
    }

    @Test
    void treatsTheAuthenticationSchemeAsCaseInsensitive() {
        // RFC 9110 defines the scheme as case-insensitive, and Prometheus lets the operator
        // write the authorization type themselves.
        assertTrue(MetricsHttpServer.authorised("secret", "bearer secret"));
        assertTrue(MetricsHttpServer.authorised("secret", "BEARER secret"));
        assertFalse(MetricsHttpServer.authorised("secret", "bearer wrong"));
    }

    @Test
    void acceptsLateSamplesButNeverOutOfOrderOnes() {
        AtomicLong applied = new AtomicLong();

        assertTrue(TickScope.claimNewerSample(applied, 5L));
        // Batch 4 finished after batch 5: older data must not overwrite newer.
        assertFalse(TickScope.claimNewerSample(applied, 4L));
        // Batch 7 is late -- several cycles have started since -- but it is still the newest
        // data anyone has, so it wins rather than being discarded for being overdue.
        assertTrue(TickScope.claimNewerSample(applied, 7L));
        assertFalse(TickScope.claimNewerSample(applied, 7L));
        assertEquals(7L, applied.get());
    }
}
