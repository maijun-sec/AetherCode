package org.aethercode.core.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * rename the user-tier skill root from
 * {@code ~/.minimax/skills/} to {@code ~/.aethercode/skills/}
 * (and the agent root from {@code ~/.minimax/agents/} to
 * {@code ~/.aethercode/agents/} — not exercised here, but the
 * same constructor accepts both userRoots / projectRoots lists
 * so the rename is symmetric). Add an
 * {@link SkillRegistry#addSkill(String, SkillRegistry.Scope, String)}
 * method that installs a SKILL.md into the user- or project-tier
 * root and re-scans, so the desktop's {@code /skill add} slash
 * command has a real engine-side hook.
 *
 * <p>The legacy path was {@code ~/.minimax/skills/}, the
 * historical Mavis layout. The afterward path is
 * {@code ~/.aethercode/skills/}, aligned with the project-tier
 * {@code <cwd>/.aethercode/skills/} name and with the existing
 * {@code ~/.aethercode/mcp.json} location the daemon already
 * watches. Old {@code ~/.minimax/skills/} content is NOT
 * migrated automatically — the user moves files manually if
 * they had content there.
 */
class SkillRegistryR210Test {

    @Test
    void addSkill_projectWritesToProjectRootAndReloads(@TempDir Path projectRoot) throws IOException {
        // The user-tier root is unused here; we only need a
        // project-tier root to exercise the PROJECT branch of
        // addSkill.
        SkillRegistry reg = new SkillRegistry(
                List.of(projectRoot),
                List.of(),
                java.time.Duration.ofSeconds(10));
        // Pre-write the registry's "first" name, otherwise
        // list() is empty.
        String body = "---\n" +
                "name: my-skill\n" +
                "description: adds 1 + 1\n" +
                "---\n" +
                "\n" +
                "Return 2.\n";
        boolean ok = reg.addSkill("my-skill",
                SkillRegistry.Scope.PROJECT, body);
        assertThat(ok).as("PROJECT-scoped addSkill should succeed").isTrue();
        // File lands under <projectRoot>/<name>/SKILL.md
        Path expected = projectRoot.resolve("my-skill").resolve("SKILL.md");
        assertThat(Files.isRegularFile(expected))
                .as("SKILL.md should exist on disk at " + expected)
                .isTrue();
        // ...and the registry picked it up via the post-write
        // reload() — the very next list() call sees it.
        assertThat(reg.list())
                .extracting(SkillRegistry.SkillMeta::name)
                .as("reload() inside addSkill should surface the new skill")
                .contains("my-skill");
        assertThat(reg.getBody("my-skill")).hasValueSatisfying(b ->
                assertThat(b).contains("Return 2."));
    }

    @Test
    void addSkill_globalWritesToUserRootAndReloads(@TempDir Path userRoot) throws IOException {
        SkillRegistry reg = new SkillRegistry(
                List.of(),
                List.of(userRoot),
                java.time.Duration.ofSeconds(10));
        String body = "---\nname: global-skill\ndescription: d\n---\nbody\n";
        boolean ok = reg.addSkill("global-skill",
                SkillRegistry.Scope.GLOBAL, body);
        assertThat(ok).as("GLOBAL-scoped addSkill should succeed").isTrue();
        Path expected = userRoot.resolve("global-skill").resolve("SKILL.md");
        assertThat(Files.isRegularFile(expected))
                .as("GLOBAL-scoped SKILL.md should exist at " + expected)
                .isTrue();
        assertThat(reg.list())
                .extracting(SkillRegistry.SkillMeta::name)
                .contains("global-skill");
    }

    @Test
    void addSkill_refusesUnsafeNameWithPathTraversal(@TempDir Path projectRoot) {
        // ../ would climb out of the project root; the
        // SkillRegistry guard rejects it BEFORE touching the
        // filesystem. The check is structural (regex) so
        // it works on every platform.
        SkillRegistry reg = new SkillRegistry(
                List.of(projectRoot),
                List.of(),
                java.time.Duration.ofSeconds(10));
        // Names that must be rejected:
        //   - ".." / "." : parent / current dir
        //   - "../escape" : relative climb
        //   - "a/b"      : directory separator
        //   - "/abs"     : absolute path
        //   - "-foo"     : leading dash (looks like a CLI flag)
        //   - "_foo"     : leading underscore (hidden / unstable)
        //   - ".foo"     : leading dot
        //   - ""         : empty
        for (String evil : new String[]{
                "..", ".", "../escape", "a/b", "/abs",
                "-foo", "_foo", ".foo", ""}) {
            boolean ok = reg.addSkill(evil, SkillRegistry.Scope.PROJECT,
                    "---\nname: x\n---\n");
            assertThat(ok)
                    .as("addSkill must refuse unsafe name \"" + evil + "\"")
                    .isFalse();
        }
        // Sanity: a valid name still works.
        boolean ok = reg.addSkill("ok-name.v2", SkillRegistry.Scope.PROJECT,
                "---\nname: ok-name.v2\n---\n");
        assertThat(ok).as("a valid name must be accepted").isTrue();
        // No file landed anywhere under projectRoot
        // except the one valid write.
        try (Stream<Path> walk = Files.walk(projectRoot)) {
            long files = walk.filter(Files::isRegularFile).count();
            assertThat(files)
                    .as("only the valid SKILL.md should have been written")
                    .isEqualTo(1L);
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
    }

    @Test
    void addSkill_rejectsWhenScopeRootIsMissing(@TempDir Path emptyDir) {
        // No project roots AND no user roots — the registry
        // has no place to write the skill. addSkill must
        // return false WITHOUT throwing.
        SkillRegistry reg = new SkillRegistry(
                List.of(),
                List.of(),
                java.time.Duration.ofSeconds(10));
        boolean ok = reg.addSkill("orphan", SkillRegistry.Scope.GLOBAL,
                "---\nname: orphan\n---\n");
        assertThat(ok).as("GLOBAL with no user roots should fail").isFalse();
        ok = reg.addSkill("orphan", SkillRegistry.Scope.PROJECT,
                "---\nname: orphan\n---\n");
        assertThat(ok).as("PROJECT with no project roots should fail").isFalse();
    }

    @Test
    void addSkill_picksFirstRootWhenMultipleConfigured(@TempDir Path userA, @TempDir Path userB) throws IOException {
        // Multi-root: addSkill writes to the FIRST user root
        // (the engine's userRoots is List<Path>; the desktop
        // passes the resolved `~/.aethercode/skills/` as the
        // first entry). A second root is intentionally left
        // empty so a regression that walked all roots would
        // still be detectable.
        SkillRegistry reg = new SkillRegistry(
                List.of(),
                List.of(userA, userB),
                java.time.Duration.ofSeconds(10));
        boolean ok = reg.addSkill("first-only", SkillRegistry.Scope.GLOBAL,
                "---\nname: first-only\n---\n");
        assertThat(ok).isTrue();
        assertThat(Files.isRegularFile(
                userA.resolve("first-only").resolve("SKILL.md")))
                .as("first user root should receive the write")
                .isTrue();
        try (Stream<Path> walk = Files.walk(userB)) {
            assertThat(walk.filter(Files::isRegularFile).count())
                    .as("second user root should NOT receive the write")
                    .isEqualTo(0L);
        }
    }

    @Test
    void r210_userTierRenameSourcepin() throws IOException {
        // Source-pin: the user-tier default path inside the
        // engine code base (Main.java, AetherCodeEngine.java,
        // DaemonRunner.java, SkillRegistry.java) MUST be
        // `.aethercode` afterward. The legacy string
        // `~/.minimax` is still in the legacy
        // `resolveMavisHome()` helper, so the source-pin
        // for THIS contract is "the user-skill / user-agent
        // path construction must use `resolveAethercodeHome()`
        // (or equivalent `.aethercode` resolution) — NOT
        // `resolveMavisHome()` or any other `.minimax`
        // fallback".
        //
        // We assert against the source files of the
        // affected modules. A refactor that re-introduces
        // the `.minimax` user-tier path silently regresses
        // R210 — this test fails.
        //
        // The aethercode-core module's test cwd is the
        // project root (aethercode/), so we walk up to
        // aethercode-cli / aethercode-sdk via ../<module>.
        java.nio.file.Path coreRoot = java.nio.file.Path.of(
                "src", "main", "java", "org", "aethercode", "core", "skill", "SkillRegistry.java");
        java.nio.file.Path cliRoot = java.nio.file.Path.of(
                "..", "aethercode-cli", "src", "main", "java", "org", "aethercode", "cli", "Main.java");
        java.nio.file.Path daemonRunner = java.nio.file.Path.of(
                "..", "aethercode-cli", "src", "main", "java", "org", "aethercode", "cli", "DaemonRunner.java");
        java.nio.file.Path engine = java.nio.file.Path.of(
                "..", "aethercode-sdk", "src", "main", "java", "org", "aethercode", "sdk", "AetherCodeEngine.java");
        java.nio.file.Path[] sourceFiles = { coreRoot, cliRoot, daemonRunner, engine };
        // Patterns that indicate the R210 regression: a
        // user-skill / user-agent root resolved via the
        // legacy `~/.minimax` path. We look for the exact
        // substring combinations that legacy used.
        String[] bannedSubstrings = {
                ".resolve(\".minimax\").resolve(\"skills\")",
                ".resolve(\".minimax\").resolve(\"agents\")",
                "resolveMavisHome().resolve(\"skills\")",
                "resolveMavisHome().resolve(\"agents\")",
        };
        for (java.nio.file.Path src : sourceFiles) {
            String content = Files.readString(src);
            for (String banned : bannedSubstrings) {
                if (content.contains(banned)) {
                    throw new AssertionError(
                            src + " still references the 历史 user-tier path \"" + banned
                                    + "\". R210 renamed it to ~/.aethercode/skills and ~/.aethercode/agents.");
                }
            }
        }
        // Positive pin: Main.java must call the new
        // resolveAethercodeHome() helper for the user-tier
        // path. The other files inherit the Path values
        // through the engine, so they don't need the
        // helper to be visible.
        String mainContent = Files.readString(cliRoot);
        assertThat(mainContent)
                .as("Main.java must call resolveAethercodeHome() for the user-tier path")
                .contains("resolveAethercodeHome()");
    }
}
