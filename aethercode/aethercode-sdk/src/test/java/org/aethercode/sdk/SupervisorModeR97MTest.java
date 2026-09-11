package org.aethercode.sdk;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * prior round.7 tests: SupervisorMode stub.
 *
 * <p>The stub ships the supervisor-side registry
 * of child daemons. The actual subprocess
 * management (ProcessBuilder + WS proxy) is
 * deferred.
 */
class SupervisorModeR97MTest {

    @Test
    void registerChildStoresInfo() {
        SupervisorMode sup = SupervisorMode.instance();
        SupervisorMode.ChildInfo ci = sup.registerChild("projectA", 18000, "D:/tmp/A");
        assertEquals("projectA", ci.childId());
        assertEquals(18000, ci.httpPort());
        assertEquals("D:/tmp/A", ci.cwd());
    }

    @Test
    void registerChildRejectsBlankId() {
        SupervisorMode sup = SupervisorMode.instance();
        assertThrows(IllegalArgumentException.class,
                () -> sup.registerChild(null, 18001, "D:/tmp"));
        assertThrows(IllegalArgumentException.class,
                () -> sup.registerChild("", 18001, "D:/tmp"));
    }

    @Test
    void unregisterChildRemovesFromList() {
        SupervisorMode sup = SupervisorMode.instance();
        sup.registerChild("temp-child", 18002, "D:/tmp/temp");
        assertNotNull(sup.getChild("temp-child"));
        boolean ok = sup.unregisterChild("temp-child");
        assertTrue(ok);
        assertNull(sup.getChild("temp-child"));
    }

    @Test
    void listChildrenIncludesAll() {
        SupervisorMode sup = SupervisorMode.instance();
        sup.registerChild("p1", 18010, "D:/tmp/p1");
        sup.registerChild("p2", 18011, "D:/tmp/p2");
        Map<String, SupervisorMode.ChildInfo> list = sup.listChildren();
        assertTrue(list.size() >= 2);
        assertTrue(list.containsKey("p1"));
        assertTrue(list.containsKey("p2"));
    }

    @Test
    void childInfoWireSnapshot() {
        SupervisorMode sup = SupervisorMode.instance();
        SupervisorMode.ChildInfo ci = sup.registerChild("wire-test", 18100, "D:/tmp/wire");
        Map<String, Object> snap = ci.toWireSnapshot();
        assertEquals("wire-test", snap.get("childId"));
        assertEquals(18100, snap.get("httpPort"));
        assertEquals("D:/tmp/wire", snap.get("cwd"));
        assertNotNull(snap.get("registeredAtMs"));
    }
}
