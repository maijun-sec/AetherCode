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
import org.aethercode.protocol.rpc.model.ModelGetParams;
import org.aethercode.protocol.rpc.model.ModelListParams;
import org.aethercode.protocol.rpc.model.ModelSetParams;
import org.aethercode.protocol.rpc.model.ModelSetResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Phase 1.2 (T-1-19 / design.md §3.1): model/{list, get, set}
 * DTOs. model/set is the mid-session switch.
 */
class ModelRpcDtosT119Test {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void modelListParamsAcceptsAllShapes() throws Exception {
        ModelListParams p = new ModelListParams("anthropic");
        String json = MAPPER.writeValueAsString(p);
        assertEquals("anthropic", MAPPER.readTree(json).get("provider").asText());

        ModelListParams none = new ModelListParams(null);
        String nj = MAPPER.writeValueAsString(none);
        // No "provider" field is omitted from the wire (NON_NULL).
        assertEquals(false, MAPPER.readTree(nj).has("provider"));
    }

    @Test
    void modelGetParamsRequiresName() {
        assertThrows(IllegalArgumentException.class,
                () -> new ModelGetParams(""));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelGetParams(null));
    }

    @Test
    void modelSetParamsRequiresBothFields() {
        assertThrows(IllegalArgumentException.class,
                () -> new ModelSetParams(null, "claude-sonnet-4-5"));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelSetParams("s-1", null));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelSetParams("s-1", ""));
    }

    @Test
    void modelSetResultCarriesPreviousName() throws Exception {
        ModelSetResult r = new ModelSetResult("s-1", "claude-sonnet-4-5", true,
                "gpt-5");
        String json = MAPPER.writeValueAsString(r);
        JsonNode n = MAPPER.readTree(json);
        assertEquals("claude-sonnet-4-5", n.get("name").asText());
        assertEquals("gpt-5", n.get("previousName").asText());
        assertEquals(true, n.get("ok").asBoolean());
    }
}
