package org.aethercode.deepagents.selfimprove;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R241.2 (O-3): tests for {@link ReasoningBank} and
 * {@link ReasoningUnit}.
 */
class ReasoningBankTest {

    @Test
    void addAndGetRoundTrip() {
        ReasoningBank bank = new ReasoningBank();
        ReasoningUnit u = ReasoningUnit.of("file_edit",
                "forgot to set cwd", "always pass cwd explicitly", "edit /etc/hosts failed");
        bank.add(u);
        assertEquals(1, bank.size());
        assertEquals(1, bank.sizeForKind("file_edit"));
        Optional<ReasoningUnit> back = bank.get(u.id());
        assertTrue(back.isPresent());
        assertEquals("forgot to set cwd", back.get().errorPattern());
    }

    @Test
    void recallForSortsByUtilityThenUsesThenCreatedAt() {
        ReasoningBank bank = new ReasoningBank();
        ReasoningUnit low = ReasoningUnit.of("k", "a", "b", "c")
                .withUtility(0.2);
        ReasoningUnit high = ReasoningUnit.of("k", "a", "b", "c")
                .withUtility(0.9);
        ReasoningUnit mid = ReasoningUnit.of("k", "a", "b", "c")
                .withUtility(0.5);
        bank.add(low);
        bank.add(high);
        bank.add(mid);
        List<ReasoningUnit> top = bank.recallFor("k", 3);
        assertEquals(3, top.size());
        assertEquals(high.id(), top.get(0).id());
        assertEquals(mid.id(), top.get(1).id());
        assertEquals(low.id(), top.get(2).id());
    }

    @Test
    void recallForFiltersByTaskKind() {
        ReasoningBank bank = new ReasoningBank();
        bank.add(ReasoningUnit.of("a", "x", "y", "z"));
        bank.add(ReasoningUnit.of("b", "x", "y", "z"));
        assertEquals(1, bank.recallFor("a").size());
        assertEquals(1, bank.recallFor("b").size());
        assertEquals(0, bank.recallFor("c").size());
    }

    @Test
    void recallForRespectsNLimit() {
        ReasoningBank bank = new ReasoningBank();
        for (int i = 0; i < 5; i++) {
            bank.add(ReasoningUnit.of("k", "e" + i, "f" + i, ""));
        }
        assertEquals(2, bank.recallFor("k", 2).size());
        assertEquals(3, bank.recallFor("k", 3).size());
    }

    @Test
    void touchBumpsUsesAndUtilityCappedAtOne() {
        ReasoningBank bank = new ReasoningBank();
        ReasoningUnit u = ReasoningUnit.of("k", "e", "f", "")
                .withUtility(0.95);
        bank.add(u);
        for (int i = 0; i < 20; i++) bank.touch(u.id());
        ReasoningUnit back = bank.get(u.id()).orElseThrow();
        assertEquals(20L, back.uses());
        assertEquals(1.0, back.utility(), 0.0001);
    }

    @Test
    void touchUnknownIdReturnsEmpty() {
        ReasoningBank bank = new ReasoningBank();
        assertFalse(bank.touch("does-not-exist").isPresent());
    }

    @Test
    void parseHandlesCanonicalFormat() {
        ReasoningBank bank = new ReasoningBank();
        String response = """
                error_pattern: forgot to set cwd
                fix_strategy: always pass cwd explicitly
                example: edit_file /etc/hosts failed
                """;
        ReasoningUnit u = bank.parse("file_edit", response);
        assertEquals("file_edit", u.taskKind());
        assertEquals("forgot to set cwd", u.errorPattern());
        assertEquals("always pass cwd explicitly", u.fixStrategy());
        assertEquals("edit_file /etc/hosts failed", u.example());
    }

    @Test
    void parseHandlesUnstructuredResponseAsFixStrategy() {
        ReasoningBank bank = new ReasoningBank();
        ReasoningUnit u = bank.parse("misc", "I just tried to read a file but the path was wrong.");
        assertEquals("misc", u.taskKind());
        // No "key: value" lines → entire response becomes fix
        assertNotNull(u.fixStrategy());
        assertFalse(u.fixStrategy().isEmpty());
        // error pattern is the "(unspecified)" fallback
        assertEquals("(unspecified)", u.errorPattern());
    }

    @Test
    void parseHandlesEqualsSign() {
        ReasoningBank bank = new ReasoningBank();
        String response = """
                error: timeout
                fix: increase the timeout to 30s
                example:
                """;
        ReasoningUnit u = bank.parse("shell", response);
        assertEquals("timeout", u.errorPattern());
        assertEquals("increase the timeout to 30s", u.fixStrategy());
    }

    @Test
    void parseCaseInsensitiveKeys() {
        ReasoningBank bank = new ReasoningBank();
        String response = "Error_Pattern: x\nFIX_STRATEGY: y";
        ReasoningUnit u = bank.parse("k", response);
        assertEquals("x", u.errorPattern());
        assertEquals("y", u.fixStrategy());
    }

    @Test
    void parseTruncatesLongExample() {
        ReasoningBank bank = new ReasoningBank();
        String longExample = "x".repeat(500);
        String response = "error_pattern: a\nfix_strategy: b\nexample: " + longExample;
        ReasoningUnit u = bank.parse("k", response);
        assertTrue(u.example().length() <= 240,
                "example should be truncated to <= 240 chars, got " + u.example().length());
    }

    @Test
    void histogramReportsPerKindCounts() {
        ReasoningBank bank = new ReasoningBank();
        bank.add(ReasoningUnit.of("a", "e", "f", ""));
        bank.add(ReasoningUnit.of("a", "e", "f", ""));
        bank.add(ReasoningUnit.of("b", "e", "f", ""));
        assertEquals(2, bank.histogram().get("a"));
        assertEquals(1, bank.histogram().get("b"));
    }

    @Test
    void clearWipesEverything() {
        ReasoningBank bank = new ReasoningBank();
        bank.add(ReasoningUnit.of("k", "e", "f", ""));
        bank.add(ReasoningUnit.of("k", "e", "f", ""));
        bank.clear();
        assertEquals(0, bank.size());
        assertEquals(0, bank.recallFor("k").size());
    }

    @Test
    void kindsReportsDistinctSet() {
        ReasoningBank bank = new ReasoningBank();
        bank.add(ReasoningUnit.of("a", "e", "f", ""));
        bank.add(ReasoningUnit.of("b", "e", "f", ""));
        bank.add(ReasoningUnit.of("a", "e2", "f2", ""));
        assertTrue(bank.kinds().contains("a"));
        assertTrue(bank.kinds().contains("b"));
        assertEquals(2, bank.kinds().size());
    }

    @Test
    void unitOfDefaults() {
        ReasoningUnit u = ReasoningUnit.of("k", "e", "f", "x");
        assertNotNull(u.id());
        assertEquals(0.5, u.utility(), 0.0001);
        assertEquals(0L, u.uses());
        assertNotNull(u.createdAt());
    }

    @Test
    void unitRejectsInvalidUtility() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ReasoningUnit("id", "k", "e", "f", "x", -0.1, 0L, java.time.Instant.now()));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ReasoningUnit("id", "k", "e", "f", "x", 1.1, 0L, java.time.Instant.now()));
    }

    @Test
    void toMapIsStable() {
        ReasoningUnit u = ReasoningUnit.of("k", "e", "f", "x");
        java.util.Map<String, Object> m = u.toMap();
        assertEquals("k", m.get("taskKind"));
        assertEquals("e", m.get("errorPattern"));
        assertEquals("f", m.get("fixStrategy"));
        assertEquals("x", m.get("example"));
        assertEquals(0.5, m.get("utility"));
        assertEquals(0L, m.get("uses"));
        assertNotNull(m.get("createdAt"));
    }
}
