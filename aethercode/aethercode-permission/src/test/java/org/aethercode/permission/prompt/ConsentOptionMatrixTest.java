package org.aethercode.permission.prompt;

import org.aethercode.permission.categorize.CategoryResult;
import org.aethercode.permission.categorize.Risk;
import org.aethercode.permission.grants.GrantDecision;
import org.aethercode.permission.grants.GrantScope;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-251 / spec.md §3.3 / design.md §3.4: the 10-option consent
 * matrix model.
 *
 * <p>Verifies the spec's exact layout:
 * <ol>
 *   <li>1-2: ONCE (ephemeral, never persisted)</li>
 *   <li>3-4: SESSION (real grant, session scope)</li>
 *   <li>5-6: PROJECT (real grant, project scope)</li>
 *   <li>7-8: USER (real grant, user scope)</li>
 *   <li>9-10: WILDCARD (project scope, category-specific)</li>
 * </ol>
 */
class ConsentOptionMatrixTest {

    @Test
    void full10Options_emittedWhenCategorizerEmitsASubCategory() {
        CategoryResult cat = new CategoryResult(
                List.of("shell.package_install", "shell.command"),
                Risk.HIGH,
                List.of("shell.package_install.npm"));
        List<ConsentOption> opts = ConsentOptionMatrix.build(cat, "proj-1");
        assertEquals(10, opts.size(),
                "10 options when the categorizer emitted a category");
        // 1
        assertOption(opts.get(0), 1, OptionKind.ONCE,
                GrantScope.SESSION, GrantDecision.ALLOW);
        // 2
        assertOption(opts.get(1), 2, OptionKind.ONCE,
                GrantScope.SESSION, GrantDecision.DENY);
        // 3
        assertOption(opts.get(2), 3, OptionKind.STANDARD,
                GrantScope.SESSION, GrantDecision.ALLOW);
        // 4
        assertOption(opts.get(3), 4, OptionKind.STANDARD,
                GrantScope.SESSION, GrantDecision.DENY);
        // 5
        assertOption(opts.get(4), 5, OptionKind.STANDARD,
                GrantScope.PROJECT, GrantDecision.ALLOW);
        // 6
        assertOption(opts.get(5), 6, OptionKind.STANDARD,
                GrantScope.PROJECT, GrantDecision.DENY);
        // 7
        assertOption(opts.get(6), 7, OptionKind.STANDARD,
                GrantScope.USER, GrantDecision.ALLOW);
        // 8
        assertOption(opts.get(7), 8, OptionKind.STANDARD,
                GrantScope.USER, GrantDecision.DENY);
        // 9 — wildcard allow
        ConsentOption nine = opts.get(8);
        assertEquals(9, nine.index());
        assertEquals(OptionKind.WILDCARD, nine.kind());
        assertEquals(GrantScope.PROJECT, nine.scope());
        assertEquals(GrantDecision.ALLOW, nine.decision());
        assertEquals("shell.package_install", nine.wildcardSubCategory());
        // 10 — wildcard deny
        ConsentOption ten = opts.get(9);
        assertEquals(10, ten.index());
        assertEquals(OptionKind.WILDCARD, ten.kind());
        assertEquals(GrantScope.PROJECT, ten.scope());
        assertEquals(GrantDecision.DENY, ten.decision());
        assertEquals("shell.package_install", ten.wildcardSubCategory());
    }

    private static void assertOption(ConsentOption o, int index, OptionKind kind,
                                     GrantScope scope, GrantDecision decision) {
        assertEquals(index, o.index(), "option index");
        assertEquals(kind, o.kind(), "option kind");
        assertEquals(scope, o.scope(), "option scope");
        assertEquals(decision, o.decision(), "option decision");
    }

    @Test
    void only8Options_whenCategorizerEmitsNoCategories() {
        // A pure "low risk" call (e.g. read_file) emits no
        // categories. The matrix should only carry the
        // standard 8.
        CategoryResult cat = new CategoryResult(List.of(), Risk.LOW, List.of());
        List<ConsentOption> opts = ConsentOptionMatrix.build(cat, "p");
        assertEquals(8, opts.size(),
                "no category → no wildcard pair → only 8 options");
        for (ConsentOption o : opts) {
            assertFalse(o.isWildcard());
            assertNull(o.wildcardSubCategory());
        }
    }

    @Test
    void wildcardSubCategory_isFirstCategory() {
        CategoryResult cat = new CategoryResult(
                List.of("file.write", "file.overwrite_existing"),
                Risk.MEDIUM,
                List.of("file.write_file"));
        List<ConsentOption> opts = ConsentOptionMatrix.build(cat, "p");
        // The wildcard should be on the FIRST category in the
        // list (file.write, not file.overwrite_existing).
        ConsentOption nine = opts.get(8);
        assertEquals("file.write", nine.wildcardSubCategory());
    }

    @Test
    void onceOptions_areEphemeral() {
        CategoryResult cat = new CategoryResult(
                List.of("shell.command"), Risk.MEDIUM, List.of());
        List<ConsentOption> opts = ConsentOptionMatrix.build(cat, "p");
        ConsentOption one = opts.get(0);
        ConsentOption two = opts.get(1);
        // persistedScope() still returns SESSION for ONCE
        // (used for the audit log) but the runtime should not
        // actually write the grant to disk.
        assertEquals(OptionKind.ONCE, one.kind());
        assertEquals(OptionKind.ONCE, two.kind());
    }

    @Test
    void projectLabel_includesProjectId() {
        CategoryResult cat = new CategoryResult(
                List.of("shell.command"), Risk.MEDIUM, List.of());
        List<ConsentOption> opts = ConsentOptionMatrix.build(cat, "my-app");
        // Options 5/6 should mention the project id.
        assertTrue(opts.get(4).label().contains("my-app"),
                "project allow label should include project id");
        assertTrue(opts.get(5).label().contains("my-app"),
                "project deny label should include project id");
    }

    @Test
    void noProjectId_stillRenders() {
        CategoryResult cat = new CategoryResult(
                List.of("shell.command"), Risk.MEDIUM, List.of());
        List<ConsentOption> opts = ConsentOptionMatrix.build(cat, null);
        // Wildcard pair is still emitted (the category is
        // present, the project id is just absent from the
        // label).
        assertEquals(10, opts.size());
        assertTrue(opts.get(4).label().contains("this project"));
    }

    @Test
    void blankProjectId_stillRenders() {
        CategoryResult cat = new CategoryResult(
                List.of("shell.command"), Risk.MEDIUM, List.of());
        List<ConsentOption> opts = ConsentOptionMatrix.build(cat, "  ");
        assertEquals(10, opts.size());
        assertTrue(opts.get(4).label().contains("this project"));
    }

    @Test
    void options_areSequentiallyNumbered() {
        CategoryResult cat = new CategoryResult(
                List.of("shell.command"), Risk.MEDIUM, List.of());
        List<ConsentOption> opts = ConsentOptionMatrix.build(cat, "p");
        for (int i = 0; i < opts.size(); i++) {
            assertEquals(i + 1, opts.get(i).index());
        }
    }

    @Test
    void everyOption_hasHotkey() {
        CategoryResult cat = new CategoryResult(
                List.of("shell.command"), Risk.MEDIUM, List.of());
        List<ConsentOption> opts = ConsentOptionMatrix.build(cat, "p");
        for (ConsentOption o : opts) {
            assertNotNull(o.hotkey());
            assertFalse(o.hotkey().isBlank());
        }
    }

    @Test
    void hotkeys_areUnique() {
        CategoryResult cat = new CategoryResult(
                List.of("shell.command"), Risk.MEDIUM, List.of());
        List<ConsentOption> opts = ConsentOptionMatrix.build(cat, "p");
        var distinct = opts.stream().map(ConsentOption::hotkey).distinct().count();
        assertEquals((long) opts.size(), distinct,
                "every option must have a unique hotkey");
    }

    @Test
    void consentOption_isWildcardMatchesKind() {
        ConsentOption std = new ConsentOption(
                1, OptionKind.STANDARD, "x", "a",
                GrantScope.SESSION, GrantDecision.ALLOW, null);
        ConsentOption wild = new ConsentOption(
                9, OptionKind.WILDCARD, "y", "w",
                GrantScope.PROJECT, GrantDecision.ALLOW, "shell.command");
        assertFalse(std.isWildcard());
        assertTrue(wild.isWildcard());
    }

    @Test
    void consentOption_indexMustBePositive() {
        try {
            new ConsentOption(0, OptionKind.STANDARD, "x", "a",
                    GrantScope.SESSION, GrantDecision.ALLOW, null);
            assertFalse(true);
        } catch (IllegalArgumentException expected) { /* ok */ }
    }
}
