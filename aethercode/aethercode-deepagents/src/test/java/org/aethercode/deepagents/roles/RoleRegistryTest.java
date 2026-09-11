package org.aethercode.deepagents.roles;

import org.aethercode.deepagents.middleware.SubAgent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * prior round.2 (O-9): tests for {@link RoleRegistry} and the standard
 * role presets.
 */
class RoleRegistryTest {

    // -- standard role presets ---------------------------------------

    @Test
    void standardRolesAreFive() {
        List<Role> all = RoleRegistry.standardRoles();
        assertEquals(5, all.size());
        assertEquals("planner",   all.get(0).name());
        assertEquals("researcher",all.get(1).name());
        assertEquals("coder",     all.get(2).name());
        assertEquals("reviewer",  all.get(3).name());
        assertEquals("executor",  all.get(4).name());
    }

    @Test
    void everyStandardRoleHasNonEmptyDescriptionAndPrompt() {
        for (Role r : RoleRegistry.standardRoles()) {
            assertNotNull(r.description());
            assertFalse(r.description().isEmpty(), r.name() + " has empty description");
            assertNotNull(r.systemPrompt());
            assertFalse(r.systemPrompt().isEmpty(), r.name() + " has empty system prompt");
        }
    }

    @Test
    void freshRegistryAlreadyContainsTheFivePresets() {
        RoleRegistry reg = new RoleRegistry();
        assertEquals(5, reg.size());
        for (String n : new String[]{"planner", "researcher", "coder", "reviewer", "executor"}) {
            assertTrue(reg.contains(n), "missing preset " + n);
        }
    }

    // -- register / replace / unregister -----------------------------

    @Test
    void registerAddsNewRole() {
        RoleRegistry reg = new RoleRegistry();
        Role custom = Role.builder("designer", "designs UIs")
                .systemPrompt("produce ASCII art")
                .build();
        reg.register(custom);
        assertEquals(6, reg.size());
        assertSame(custom, reg.get("designer").orElseThrow());
    }

    @Test
    void registerOverwritesExistingName() {
        RoleRegistry reg = new RoleRegistry();
        Role replacement = Role.builder("coder", "specialised coder")
                .systemPrompt("...")
                .build();
        reg.register(replacement);
        assertEquals(5, reg.size(), "register replaces, not appends");
        assertSame(replacement, reg.get("coder").orElseThrow());
    }

    @Test
    void replaceReturnsPrevious() {
        RoleRegistry reg = new RoleRegistry();
        Role before = reg.get("reviewer").orElseThrow();
        Role newReviewer = Role.builder("reviewer", "tighter reviewer")
                .systemPrompt("...").build();
        Optional<Role> prev = reg.replace(newReviewer);
        assertTrue(prev.isPresent());
        assertSame(before, prev.get());
        assertSame(newReviewer, reg.get("reviewer").orElseThrow());
    }

    @Test
    void unregisterRemovesAndReturnsTrue() {
        RoleRegistry reg = new RoleRegistry();
        assertTrue(reg.unregister("planner"));
        assertFalse(reg.contains("planner"));
        assertEquals(4, reg.size());
    }

    @Test
    void unregisterMissingReturnsFalse() {
        RoleRegistry reg = new RoleRegistry();
        assertFalse(reg.unregister("ghost"));
    }

    // -- listeners ---------------------------------------------------

    @Test
    void listenerSeesAddsAndReplacements() {
        RoleRegistry reg = new RoleRegistry();
        List<Role> added = new ArrayList<>();
        reg.addListener(new RoleRegistry.RoleRegistryListener() {
            @Override public void onAdded(Role role, Role replaced) {
                added.add(role);
            }
        });
        Role r1 = Role.builder("scribe", "writes docs").build();
        reg.register(r1);
        Role r2 = Role.builder("scribe", "writes more docs").build();
        reg.register(r2);
        assertEquals(2, added.size());
        assertSame(r1, added.get(0));
        assertSame(r2, added.get(1));
    }

    @Test
    void listenerSeesRemovals() {
        RoleRegistry reg = new RoleRegistry();
        AtomicInteger removals = new AtomicInteger(0);
        reg.addListener(new RoleRegistry.RoleRegistryListener() {
            @Override public void onRemoved(Role role) {
                removals.incrementAndGet();
            }
        });
        reg.unregister("planner");
        reg.unregister("ghost"); // no event for missing
        assertEquals(1, removals.get());
    }

    // -- compile to SubAgent[] -------------------------------------

    @Test
    void asSubAgentsReturnsFiveSubAgents() {
        RoleRegistry reg = new RoleRegistry();
        List<SubAgent> subs = reg.asSubAgents();
        assertEquals(5, subs.size());
        for (int i = 0; i < subs.size(); i++) {
            Role r = RoleRegistry.standardRoles().get(i);
            assertEquals(r.name(), subs.get(i).name());
            assertEquals(r.description(), subs.get(i).description());
        }
    }

    // -- Role.allows() -----------------------------------------------

    @Test
    void emptyToolListMeansOpenAllow() {
        Role r = Role.builder("x", "y").build();
        assertTrue(r.allows("any-tool"));
        assertTrue(r.allows("another-tool"));
    }

    @Test
    void explicitToolListRestrictsAllowedSet() {
        Role r = Role.builder("reviewer", "...").tools("read_file", "glob").build();
        assertTrue(r.allows("read_file"));
        assertTrue(r.allows("glob"));
        assertFalse(r.allows("rm"));
        assertFalse(r.allows("bash"));
    }

    @Test
    void roleToMapIsStable() {
        Role r = Role.builder("x", "y")
                .systemPrompt("hi").tools("a", "b").build();
        java.util.Map<String, Object> m = r.toMap();
        assertEquals("x", m.get("name"));
        assertEquals("y", m.get("description"));
        assertEquals("hi", m.get("systemPrompt"));
        @SuppressWarnings("unchecked")
        java.util.List<String> tools = (java.util.List<String>) m.get("tools");
        assertEquals(2, tools.size());
        assertEquals("a", tools.get(0));
    }
}
