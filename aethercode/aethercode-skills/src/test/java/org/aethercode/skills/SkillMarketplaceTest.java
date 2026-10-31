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

package org.aethercode.skills;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SkillMarketplaceTest {

    @Test
    void builtinsArePresent() {
        SkillMarketplace m = new SkillMarketplace();
        assertThat(m.size()).isGreaterThanOrEqualTo(4);
        assertThat(m.get("frontend-review")).isNotNull();
        assertThat(m.get("security-audit")).isNotNull();
    }

    @Test
    void allReturnsBuiltinList() {
        SkillMarketplace m = new SkillMarketplace();
        assertThat(m.all()).hasSize(m.size());
    }

    @Test
    void addAndGet() {
        SkillMarketplace m = new SkillMarketplace();
        Skill custom = new Skill("custom", "user skill", "body", List.of(), null);
        m.add(custom);
        assertThat(m.get("custom")).isSameAs(custom);
    }

    @Test
    void addWithNullSkillIgnored() {
        SkillMarketplace m = new SkillMarketplace();
        int size = m.size();
        m.add(null);
        assertThat(m.size()).isEqualTo(size);
    }

    @Test
    void addOverwritesByName() {
        SkillMarketplace m = new SkillMarketplace();
        Skill s1 = new Skill("x", "v1", "b1", List.of(), null);
        Skill s2 = new Skill("x", "v2", "b2", List.of(), null);
        m.add(s1);
        m.add(s2);
        assertThat(m.get("x")).isSameAs(s2);
    }

    @Test
    void installAddsToInstalledSet() {
        SkillMarketplace m = new SkillMarketplace();
        m.install("frontend-review");
        assertThat(m.isInstalled("frontend-review")).isTrue();
        assertThat(m.isInstalled("security-audit")).isFalse();
    }

    @Test
    void installBySkillAddsAndMarks() {
        SkillMarketplace m = new SkillMarketplace();
        Skill s = new Skill("manual", "d", "b", List.of(), null);
        m.install(s);
        assertThat(m.get("manual")).isSameAs(s);
        assertThat(m.isInstalled("manual")).isTrue();
    }

    @Test
    void installUnknownNameIsNoop() {
        SkillMarketplace m = new SkillMarketplace();
        m.install("does-not-exist");
        assertThat(m.isInstalled("does-not-exist")).isFalse();
    }

    @Test
    void searchEmptyReturnsAll() {
        SkillMarketplace m = new SkillMarketplace();
        int n = m.size();
        assertThat(m.search("")).hasSize(n);
        assertThat(m.search(null)).hasSize(n);
    }

    @Test
    void searchByName() {
        SkillMarketplace m = new SkillMarketplace();
        List<Skill> r = m.search("frontend");
        assertThat(r).extracting(Skill::name).contains("frontend-review");
    }

    @Test
    void searchByDescription() {
        SkillMarketplace m = new SkillMarketplace();
        // "accessibility" is in the description of frontend-review
        List<Skill> r = m.search("accessibility");
        assertThat(r).extracting(Skill::name).contains("frontend-review");
    }

    @Test
    void searchIsCaseInsensitive() {
        SkillMarketplace m = new SkillMarketplace();
        assertThat(m.search("SQL")).isNotEmpty();
    }

    @Test
    void fromDirsLoadsUserSkills(@TempDir Path tmp) throws Exception {
        // create a .aethercode/skills/ dir with one user skill
        Path skillsDir = tmp.resolve(".aethercode").resolve("skills");
        Files.createDirectories(skillsDir);
        Files.writeString(skillsDir.resolve("my-team-skill.md"), """
                ---
                name: my-team-skill
                description: team specific
                ---
                body text here
                """);
        SkillMarketplace m = SkillMarketplace.fromDirs(List.of(skillsDir));
        assertThat(m.get("my-team-skill")).isNotNull();
        // built-ins still present
        assertThat(m.get("frontend-review")).isNotNull();
    }

    @Test
    void fromDirsMissingDirIsIgnored() {
        SkillMarketplace m = SkillMarketplace.fromDirs(List.of(Path.of("/does/not/exist")));
        // still has builtins
        assertThat(m.get("frontend-review")).isNotNull();
    }

    @Test
    void addAllAcceptsEmpty() {
        SkillMarketplace m = new SkillMarketplace();
        m.addAll(List.of());
        assertThat(m.size()).isEqualTo(new SkillMarketplace().size());
    }
}
