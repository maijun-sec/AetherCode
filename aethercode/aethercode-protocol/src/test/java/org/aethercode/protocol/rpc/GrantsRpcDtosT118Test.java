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
