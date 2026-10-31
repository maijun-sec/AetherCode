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
import org.aethercode.protocol.rpc.compact.CompactRunParams;
import org.aethercode.protocol.rpc.compact.CompactRunResult;
import org.aethercode.protocol.rpc.compact.CompactStatusParams;
import org.aethercode.protocol.rpc.compact.CompactStatusResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Phase 1.2 (T-1-17 / design.md §3.1): the refined,
 * per-session compact/{status, run} DTOs.
 */
class CompactRpcDtosT117Test {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void compactStatusParamsRequiresSessionId() {
        assertThrows(IllegalArgumentException.class,
                () -> new CompactStatusParams(""));
        assertThrows(IllegalArgumentException.class,
                () -> new CompactStatusParams(null));
    }

    @Test
    void compactRunParamsRequiresSessionIdAndDefaultsMode() {
        assertThrows(IllegalArgumentException.class,
                () -> new CompactRunParams("", "aggressive"));
        assertThrows(IllegalArgumentException.class,
                () -> new CompactRunParams(null, "aggressive"));
        CompactRunParams p = new CompactRunParams("s-1", null);
        assertEquals("balanced", p.effectiveMode());
    }

    @Test
    void compactStatusAndRunResultsRoundtrip() throws Exception {
        CompactStatusResult status = new CompactStatusResult(
                "s-1", false, Map.of("tsMs", 1_700_000_000L), 1234L, 200_000L, 0.62);
        String sjson = MAPPER.writeValueAsString(status);
        JsonNode sn = MAPPER.readTree(sjson);
        assertEquals("s-1", sn.get("sessionId").asText());
        assertEquals(false, sn.get("autoCompactDisabled").asBoolean());
        assertEquals(0.62, sn.get("percent").asDouble(), 1e-9);

        CompactRunResult run = new CompactRunResult(
                "s-1", true, false, "summary", 50_000L, 12_000L, 250L);
        String rjson = MAPPER.writeValueAsString(run);
        JsonNode rn = MAPPER.readTree(rjson);
        assertEquals("summary", rn.get("layer").asText());
        assertEquals(50_000L, rn.get("tokensBefore").asLong());
        assertEquals(12_000L, rn.get("tokensAfter").asLong());
        assertEquals(250L, rn.get("elapsedMs").asLong());
    }
}
