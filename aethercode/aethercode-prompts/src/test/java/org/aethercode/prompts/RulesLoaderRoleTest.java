package org.aethercode.prompts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * tests for the per-agent-role rules subdirectory.
 * Each test stages a directory layout with both the
 * base {@code .aethercode/rules/} and a role-scoped
 * subdirectory {@code .aethercode/rules/roles/<role>/},
 * then asserts the loader picks up the right files in
 * the right order for the requested role.
 *
 * <p>Path-traversal resistance is exercised by
 * {@code load_safeRole_rejectsTraversal}, which passes
 * a malicious role name and verifies the loader
 * silently treats it as empty rather than reading
 * outside the rules root.
 */
class RulesLoaderRoleTest {

    @Test
    void load_roleEmpty_omitsRoleLayer(@TempDir Path cwd) throws IOException {
        Path rulesDir = cwd.resolve(".aethercode/rules");
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("base.md"), "base content");
        // Role dir present but role="" should be a no-op.
        Path roleDir = rulesDir.resolve("roles/coder");
        Files.createDirectories(roleDir);
        Files.writeString(roleDir.resolve("role.md"), "role content");
        String out = RulesLoader.load(cwd, null, "");
        assertThat(out).contains("base content")
                .doesNotContain("role content")
                .doesNotContain("(role-specific)");
    }

    @Test
    void load_roleLayerLoadedInAdditionToBase(@TempDir Path cwd) throws IOException {
        Path rulesDir = cwd.resolve(".aethercode/rules");
        Path roleDir = rulesDir.resolve("roles/coder");
        Files.createDirectories(roleDir);
        Files.writeString(rulesDir.resolve("base.md"), "BASE-CONTENT");
        Files.writeString(roleDir.resolve("role.md"),  "ROLE-CONTENT");
        String out = RulesLoader.load(cwd, null, "coder");
        assertThat(out).contains("BASE-CONTENT").contains("ROLE-CONTENT");
        // Base renders before role (so role rules can
        // build on the base context).
        assertThat(out.indexOf("BASE-CONTENT"))
                .isLessThan(out.indexOf("ROLE-CONTENT"));
    }

    @Test
    void load_roleLayerHeaderIsParenthetical(@TempDir Path cwd) throws IOException {
        // The role-scoped section gets a "(role-specific)"
        // parenthetical in the header so the model can
        // see at a glance which rules came from the
        // role subdirectory.
        Path rulesDir = cwd.resolve(".aethercode/rules");
        Path roleDir = rulesDir.resolve("roles/explorer");
        Files.createDirectories(roleDir);
        Files.writeString(roleDir.resolve("be-quiet.md"), "be quiet content");
        String out = RulesLoader.load(cwd, null, "explorer");
        assertThat(out).contains("# Project rules (role-specific)");
        assertThat(out).contains("be quiet content");
    }

    @Test
    void load_unknownRole_returnsBaseOnly(@TempDir Path cwd) throws IOException {
        // A role with no matching subdirectory falls
        // back to the base-only behaviour (no error,
        // no warning to the user beyond the debug log).
        Path rulesDir = cwd.resolve(".aethercode/rules");
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("base.md"), "base content");
        String out = RulesLoader.load(cwd, null, "ghost");
        assertThat(out).contains("base content").doesNotContain("(role-specific)");
    }

    @Test
    void load_safeRole_rejectsTraversal(@TempDir Path cwd) throws IOException {
        // A role that contains path-traversal characters
        // (or any unsafe char) is rejected: the loader
        // treats it as empty and never touches the
        // filesystem via the malicious path.
        String malicious = "../../etc";
        String out = RulesLoader.load(cwd, null, malicious);
        // Should be the same as load(cwd, null) — base
        // only, no role layer.
        assertThat(out).doesNotContain("(role-specific)");
        // No crash, no exception, no path outside cwd
        // was read.
    }

    @Test
    void load_safeRole_rejectsPathSeparator(@TempDir Path cwd) throws IOException {
        // Forward slashes are also rejected.
        assertThat(RulesLoader.safeRoleOrEmpty("a/b")).isEmpty();
        assertThat(RulesLoader.safeRoleOrEmpty("a\\b")).isEmpty();
        assertThat(RulesLoader.safeRoleOrEmpty("a b")).isEmpty();
        assertThat(RulesLoader.safeRoleOrEmpty("A")).isEmpty();  // uppercase
        assertThat(RulesLoader.safeRoleOrEmpty("a:b")).isEmpty();  // colon
    }

    @Test
    void load_safeRole_acceptsValidNames() {
        assertThat(RulesLoader.safeRoleOrEmpty("coder")).isEqualTo("coder");
        assertThat(RulesLoader.safeRoleOrEmpty("explore")).isEqualTo("explore");
        assertThat(RulesLoader.safeRoleOrEmpty("general-purpose")).isEqualTo("general-purpose");
        assertThat(RulesLoader.safeRoleOrEmpty("a1-b2_c3")).isEqualTo("a1-b2_c3");
    }

    @Test
    void load_safeRole_nullOrEmpty() {
        assertThat(RulesLoader.safeRoleOrEmpty(null)).isEmpty();
        assertThat(RulesLoader.safeRoleOrEmpty("")).isEmpty();
    }

    @Test
    void load_safeRole_tooLong() {
        // 65-char role name → rejected.
        String tooLong = "a".repeat(65);
        assertThat(RulesLoader.safeRoleOrEmpty(tooLong)).isEmpty();
        // 64-char role name → accepted.
        String justRight = "a".repeat(64);
        assertThat(RulesLoader.safeRoleOrEmpty(justRight)).isEqualTo(justRight);
    }

    @Test
    void load_roleLayerUsesIndexMd(@TempDir Path cwd) throws IOException {
        // The role subdirectory also honours an
        // index.md (prior round) for explicit ordering.
        Path roleDir = cwd.resolve(".aethercode/rules/roles/coder");
        Files.createDirectories(roleDir);
        Files.writeString(roleDir.resolve("zeta.md"),  "z");
        Files.writeString(roleDir.resolve("alpha.md"), "a");
        Files.writeString(roleDir.resolve("index.md"), "- alpha.md\n- zeta.md");
        String out = RulesLoader.load(cwd, null, "coder");
        int aIdx = out.indexOf("### alpha.md");
        int zIdx = out.indexOf("### zeta.md");
        assertThat(aIdx).isGreaterThanOrEqualTo(0);
        assertThat(zIdx).isGreaterThan(aIdx);
    }

    @Test
    void load_roleLayerRespectsDisableMarker(@TempDir Path cwd) throws IOException {
        // The per-file disable marker (prior round) works
        // inside a role subdirectory too.
        Path roleDir = cwd.resolve(".aethercode/rules/roles/coder");
        Files.createDirectories(roleDir);
        Files.writeString(roleDir.resolve("enabled.md"),  "ENABLED");
        Files.writeString(roleDir.resolve("disabled.md"),
                "<!-- aethercode: disabled -->\nNOT-ENABLED");
        String out = RulesLoader.load(cwd, null, "coder");
        assertThat(out).contains("ENABLED").doesNotContain("NOT-ENABLED");
    }

    @Test
    void load_combinesProjectAndGlobalRoleLayers(@TempDir Path cwd) throws IOException {
        // The four-layer combine: project base, project
        // role, global base, global role. All four
        // appear in the output, with role-specific
        // headers where appropriate.
        Path projectDir = cwd.resolve(".aethercode/rules");
        Path projectRoleDir = projectDir.resolve("roles/coder");
        Path home = Files.createTempDirectory("r93g-home");
        Path homeRulesDir = home.resolve(".aethercode/rules");
        Path homeRoleDir = homeRulesDir.resolve("roles/coder");
        Files.createDirectories(projectRoleDir);
        Files.createDirectories(homeRulesDir);
        Files.createDirectories(homeRoleDir);
        Files.writeString(projectDir.resolve("p-base.md"),      "P-BASE");
        Files.writeString(projectRoleDir.resolve("p-role.md"),   "P-ROLE");
        Files.writeString(homeRulesDir.resolve("g-base.md"),     "G-BASE");
        Files.writeString(homeRoleDir.resolve("g-role.md"),      "G-ROLE");
        String out = RulesLoader.load(cwd, home, "coder");
        assertThat(out).contains("P-BASE").contains("P-ROLE")
                .contains("G-BASE").contains("G-ROLE");
        // Layer order: project base → project role →
        // global base → global role.
        int pBase = out.indexOf("P-BASE");
        int pRole = out.indexOf("P-ROLE");
        int gBase = out.indexOf("G-BASE");
        int gRole = out.indexOf("G-ROLE");
        assertThat(pBase).isLessThan(pRole);
        assertThat(pRole).isLessThan(gBase);
        assertThat(gBase).isLessThan(gRole);
    }
}
