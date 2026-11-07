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

package org.aethercode.workflows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R388c gate: a shipped workflow must not REFERENCE a skill that is
 * not installed.
 *
 * <p>The gap this pins: all six shipped workflows declared
 * {@code skills: [...]}, and <b>not one of those skills existed on
 * disk</b>. The {@link SkillComposer} treats a missing skill as a
 * WARNING, not an error — it emits
 * {@code "skill 'tdd' is not installed; skipping"} and the workflow
 * runs anyway, with the skill's content silently absent.
 *
 * <p>The result was that {@code tdd-feature.yaml} — a workflow whose
 * entire job is the red-green-refactor loop — shipped with its TDD
 * guidance living ONLY in the YAML's own {@code prompts:} block, and
 * the {@code skills: [tdd, code-review]} list was decoration. Every
 * workflow had the same shape: a list of skill names that named
 * nothing.
 *
 * <p>Why this must be a gate and not a runtime warning:
 * <ul>
 *   <li>A missing skill does not fail the run — it fails SILENTLY.
 *       Nothing in the workflow output says "this guidance is
 *       missing", so nobody notices.</li>
 *   <li>The workflow YAML and the skill files live in DIFFERENT
 *       places (a JAR resource vs the user's home directory). Nothing
 *       else connects them, so nothing else notices the drift.</li>
 *   <li>The skill files are USER-level: they may be installed on the
 *       author's machine and missing on CI. That is exactly why the
 *       check has to be explicit.</li>
 * </ul>
 *
 * <p>The check is therefore two-sided:
 * <ol>
 *   <li>every skill a shipped workflow references must be installed
 *       somewhere AetherCode looks (the user dir or the project dir)
 *       — otherwise the workflow silently loses its guidance;</li>
 *   <li>the installed skills must not be empty stubs — a skill file
 *       with no body teaches the model nothing.</li>
 * </ol>
 *
 * <p>The skill search roots mirror {@link SkillComposer}'s: the
 * project's {@code <cwd>/.aethercode/skills/} and the user's
 * {@code <userHome>/.aethercode/skills/}. The skill files are
 * resolved by name, with or without a {@code .md} suffix.
 */
class ShippedWorkflowSkillResolutionTest {

    /** the skills the shipped workflows reference. */
    private static final Set<String> REQUIRED_SKILLS = new LinkedHashSet<>(List.of(
            "tdd",
            "code-review",
            "security-audit-basics",
            "security-audit",
            "cwe-classifier",
            "debugging",
            "maven",
            "gradle",
            "semver",
            "changelog-writer"));

    @Test
    void everySkillReferencedByAShippedWorkflowIsInstalled(@TempDir Path tmp) throws Exception {
        Path userSkills = tmp.resolve("user-skills");
        Files.createDirectories(userSkills);
        Path projectSkills = tmp.resolve("project-skills");
        Files.createDirectories(projectSkills);

        // A skill installed in the USER dir (the normal case).
        writeSkill(userSkills, "tdd", "RED / GREEN / REFACTOR");
        // A skill installed in the PROJECT dir (overrides user).
        writeSkill(projectSkills, "code-review", "REVIEW BODY");
        writeSkill(userSkills, "security-audit-basics", "SECURITY BODY");
        // The rest of the referenced set. Every skill a
        // shipped workflow names must have a file —
        // the point of this test is that the name in
        // the YAML is not decoration.
        for (String skill : REQUIRED_SKILLS) {
            if (skill.equals("tdd") || skill.equals("code-review")
                    || skill.equals("security-audit-basics")) {
                continue;  // already written above
            }
            writeSkill(userSkills, skill, "BODY OF " + skill);
        }

        SkillComposer composer = new SkillComposer(userSkills, projectSkills);

        // Every skill referenced by every shipped workflow
        // must resolve.
        WorkflowLoader loader = new WorkflowLoader();
        for (String name : discoverWorkflowNames()) {
            Workflow wf = loader.loadResource("workflows/" + name + ".yaml");
            List<String> skills = wf.skills();
            if (skills == null || skills.isEmpty()) continue;
            for (String skill : skills) {
                assertThat(composer.lookup(skill))
                        .as("workflow '%s' references skill '%s', which must be installed — "
                                + "a missing skill is a SILENT warning, so the workflow "
                                + "would run without its guidance", name, skill)
                        .isNotNull();
            }
        }
    }

    @Test
    void aSkillWithAnEmptyBodyIsReportedAsMissing(@TempDir Path tmp) throws Exception {
        // A skill file with no body teaches the model nothing,
        // but it LOOKS installed — the lookup succeeds and
        // the "Injected skills" section would still LIST it,
        // so the prompt would LOOK complete while the
        // guidance was absent. Same failure mode as a
        // missing skill, but QUIETER.
        //
        // R388c: the composer treats an empty body as
        // MISSING, so it lands in the "Missing skills
        // (skipped)" section where it is visible.
        Path userSkills = tmp.resolve("user-skills");
        Files.createDirectories(userSkills);
        writeSkill(userSkills, "empty-skill", "");

        SkillComposer composer = new SkillComposer(userSkills, null);

        String out = composer.compose(List.of("empty-skill"));
        assertThat(out)
                .as("an empty skill must be reported as missing, not injected as nothing")
                .contains("'empty-skill' is not installed");
        assertThat(out)
                .as("an empty skill must NOT be listed as an injected skill")
                .doesNotContain("**empty-skill**");
    }

    @Test
    void theRealUserSkillsDirectoryProvidesEveryRequiredSkill() {
        // The gate above proves the COMPOSER works. This
        // one proves the SKILLS ARE ACTUALLY INSTALLED on
        // this machine — the gap this whole round closed.
        //
        // It is deliberately NOT skipped when the directory is
        // absent: a missing skills directory means the
        // workflows are running with no guidance at all,
        // which is the bug. The assertion says so.
        Path userSkills = Path.of(System.getProperty("user.home"), ".aethercode", "skills");
        assertThat(userSkills)
                .as("the user skills directory must exist — every shipped "
                        + "workflow references skills, and a missing skill is a "
                        + "SILENT warning, so the workflow would run with no "
                        + "guidance at all")
                .isDirectory();

        SkillComposer composer = new SkillComposer(userSkills, null);
        for (String skill : REQUIRED_SKILLS) {
            var resolved = composer.lookup(skill);
            assertThat(resolved)
                    .as("skill '%s' must be installed in %s", skill, userSkills)
                    .isNotNull();
            assertThat(resolved.body())
                    .as("skill '%s' must carry a body — an empty skill is "
                            + "silently injected as nothing", skill)
                    .isNotBlank();
            assertThat(resolved.description())
                    .as("skill '%s' must carry a description — it is what the "
                            + "model sees in the injected-skills list", skill)
                    .isNotBlank();
        }
    }

    @Test
    void everyShippedWorkflowsSkillIsInTheRequiredSet() {
        // Guard the OTHER direction of the allowlist: a
        // workflow that references a skill nobody has
        // written must FAIL the gate rather than pass
        // because the set happens to cover it by accident.
        // The set and the workflows must agree exactly.
        WorkflowLoader loader = new WorkflowLoader();
        Set<String> referenced = new LinkedHashSet<>();
        for (String name : discoverWorkflowNames()) {
            Workflow wf = loader.loadResource("workflows/" + name + ".yaml");
            if (wf.skills() != null) referenced.addAll(wf.skills());
        }
        assertThat(referenced)
                .as("the workflows' skill references and the required-skill set "
                        + "must agree exactly — a skill referenced but not in the set "
                        + "has no file written for it")
                .isEqualTo(REQUIRED_SKILLS);
    }

    // -- helpers ----------------------------------------------------------

    private static void writeSkill(Path dir, String name, String body) throws Exception {
        Files.writeString(dir.resolve(name + ".md"), """
                ---
                name: %s
                description: Test skill.
                ---

                %s
                """.formatted(name, body), StandardCharsets.UTF_8);
    }

    /** mirror of {@link ShippedWorkflowsTest}'s discovery. */
    private static List<String> discoverWorkflowNames() {
        List<String> names = new ArrayList<>();
        // Classpath resources have no portable "list
        // directory" API, so the shipped set is an
        // allowlist (the same one
        // ShippedWorkflowsTest uses).
        String[] shipped = {
                "tdd-feature", "security-audit",
                "add-changelog", "explain-failure",
                "code-review", "migrate-deps"
        };
        for (String n : shipped) names.add(n);
        return names;
    }
}
