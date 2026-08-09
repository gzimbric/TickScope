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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TickScopeTest {

    @Test
    void emptyTokenDisablesAuthentication() {
        assertTrue(TickScope.authorised("", null));
    }

    @Test
    void acceptsOnlyTheCompleteBearerToken() {
        assertTrue(TickScope.authorised("secret", "Bearer secret"));
        assertFalse(TickScope.authorised("secret", null));
        assertFalse(TickScope.authorised("secret", "Basic secret"));
        assertFalse(TickScope.authorised("secret", "Bearer secre"));
        assertFalse(TickScope.authorised("secret", "Bearer secret-extra"));
    }
}
