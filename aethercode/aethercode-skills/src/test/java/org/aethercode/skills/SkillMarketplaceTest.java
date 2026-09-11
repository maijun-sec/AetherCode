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
