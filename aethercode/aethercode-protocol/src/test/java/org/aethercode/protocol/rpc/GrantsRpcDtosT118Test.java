/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.aethercode.protocol.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aethercode.protocol.rpc.grants.GrantsClearParams;
import org.aethercode.protocol.rpc.grants.GrantsClearResult;
import org.aethercode.protocol.rpc.grants.GrantsRevokeParams;
import org.aethercode.protocol.rpc.grants.GrantsSetPresetParams;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Phase 1.2 (T-1-18 / design.md §3.1): grants/{list, revoke,
 * clear, setPreset} DTOs.
 */
class GrantsRpcDtosT118Test {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void grantsRevokeParamsRequiresId() {
        assertThrows(IllegalArgumentException.class,
                () -> new GrantsRevokeParams(""));
        assertThrows(IllegalArgumentException.class,
                () -> new GrantsRevokeParams(null));
    }

    @Test
    void grantsClearParamsRequiresScope() {
        assertThrows(IllegalArgumentException.class,
                () -> new GrantsClearParams("", null));
        assertThrows(IllegalArgumentException.class,
                () -> new GrantsClearParams(null, null));
    }

    @Test
    void grantsSetPresetParamsAcceptsOnlyKnownPresets() {
        for (String p : new String[]{"permissive", "cautious", "strict"}) {
            new GrantsSetPresetParams(p); // no throw
        }
        assertThrows(IllegalArgumentException.class,
                () -> new GrantsSetPresetParams("unknown"));
        assertThrows(IllegalArgumentException.class,
                () -> new GrantsSetPresetParams(""));
        assertThrows(IllegalArgumentException.class,
                () -> new GrantsSetPresetParams(null));
    }

    @Test
    void grantsResultsRoundtrip() throws Exception {
        GrantsClearResult res = new GrantsClearResult(true, 5, "user");
        String json = MAPPER.writeValueAsString(res);
        JsonNode n = MAPPER.readTree(json);
        assertEquals(true, n.get("ok").asBoolean());
        assertEquals(5, n.get("revoked").asInt());
        assertEquals("user", n.get("scope").asText());
    }
}
