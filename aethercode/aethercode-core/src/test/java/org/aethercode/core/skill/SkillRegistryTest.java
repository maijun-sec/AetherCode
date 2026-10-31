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

package org.aethercode.core.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SkillRegistryTest {

    @Test
    void parsesSimpleFrontmatter() {
        Frontmatter.Parsed p = Frontmatter.parse("""
                ---
                name: hello
                description: Says hi
                ---
                # body
                some content
                """);
        assertEquals("hello", Frontmatter.string(p.fields(), "name"));
        assertEquals("Says hi", Frontmatter.string(p.fields(), "description"));
        assertTrue(p.body().startsWith("# body"), "body should start with # body, was: " + p.body());
    }

    @Test
    void parsesBlockScalar() {
        Frontmatter.Parsed p = Frontmatter.parse("""
                ---
                name: long
                description: |
                  this is a multi
                  line description
                  with three lines
                ---
                body
                """);
        String desc = Frontmatter.string(p.fields(), "description");
        assertNotNull(desc);
        assertTrue(desc.contains("multi"), "block scalar should keep newlines/words, was: " + desc);
        assertTrue(desc.contains("three lines"));
    }

    @Test
    void parsesLocalizedMap() {
        Frontmatter.Parsed p = Frontmatter.parse("""
                ---
                name: localized
                descriptions:
                  zh-Hans: "中文描述"
                  en: "English desc"
                displayNames:
                  zh-Hans: "中文名"
                ---
                # body
                """);
        assertEquals("中文描述", Frontmatter.localized(p.fields(), "descriptions", "fallback"));
        assertEquals("中文名", Frontmatter.localized(p.fields(), "displayNames", "fallback"));
        assertEquals("fallback", Frontmatter.localized(p.fields(), "missing", "fallback"));
    }

    @Test
    void noFrontmatterReturnsEmptyFields() {
        Frontmatter.Parsed p = Frontmatter.parse("# plain markdown\n\nno frontmatter here");
        assertTrue(p.fields().isEmpty());
        assertEquals("# plain markdown\n\nno frontmatter here", p.body());
    }

    @Test
    void unterminatedFrontmatterIsTreatedAsBody() {
        Frontmatter.Parsed p = Frontmatter.parse("---\nname: x\n");
        assertTrue(p.fields().isEmpty(), "unterminated should not yield fields");
    }

    @Test
    void registryLoadsFromBothRoots(@TempDir Path tmp) throws IOException {
        Path user = tmp.resolve("user-skills");
        Path project = tmp.resolve(".aethercode/skills");
        Files.createDirectories(user.resolve("greet"));
        Files.createDirectories(project.resolve("build"));
        Files.writeString(user.resolve("greet/SKILL.md"), """
                ---
                name: greet
                description: Greet the user
                ---
                # greet body
                """);
        Files.writeString(project.resolve("build/SKILL.md"), """
                ---
                name: build
                description: Build the project
                ---
                # build body
                """);
        SkillRegistry reg = new SkillRegistry(List.of(project), List.of(user), Duration.ofMinutes(1));
        List<SkillRegistry.SkillMeta> all = reg.list();
        assertEquals(2, all.size());
        assertTrue(all.stream().anyMatch(m -> "greet".equals(m.name()) && "user".equals(m.source())));
        assertTrue(all.stream().anyMatch(m -> "build".equals(m.name()) && "project".equals(m.source())));
    }

    @Test
    void projectTierWinsOnNameCollision(@TempDir Path tmp) throws IOException {
        Path user = tmp.resolve("user-skills");
        Path project = tmp.resolve(".aethercode/skills");
        Files.createDirectories(user.resolve("shared"));
        Files.createDirectories(project.resolve("shared"));
        Files.writeString(user.resolve("shared/SKILL.md"), "---\nname: shared\ndescription: USER\n---\nUSER BODY\n");
        Files.writeString(project.resolve("shared/SKILL.md"), "---\nname: shared\ndescription: PROJECT\n---\nPROJECT BODY\n");
        SkillRegistry reg = new SkillRegistry(List.of(project), List.of(user), Duration.ofMinutes(1));
        Optional<String> body = reg.getBody("shared");
        assertTrue(body.isPresent());
        assertTrue(body.get().contains("PROJECT BODY"), "project body should win, was: " + body.get());
    }

    @Test
    void systemPromptBlockRendersAllSkills(@TempDir Path tmp) throws IOException {
        Path user = tmp.resolve("user-skills");
        Files.createDirectories(user.resolve("a"));
        Files.createDirectories(user.resolve("b"));
        Files.writeString(user.resolve("a/SKILL.md"), "---\nname: a\ndescription: first\n---\nA\n");
        Files.writeString(user.resolve("b/SKILL.md"), "---\nname: b\ndescription: second\n---\nB\n");
        SkillRegistry reg = new SkillRegistry(List.of(), List.of(user), Duration.ofMinutes(1));
        String block = reg.renderSystemPromptBlock();
        assertTrue(block.contains("<available_skills>"));
        assertTrue(block.contains("<name>a</name>"));
        assertTrue(block.contains("<name>b</name>"));
        assertTrue(block.contains("</available_skills>"));
    }

    @Test
    void missingDirIsTolerated(@TempDir Path tmp) {
        // Non-existent paths should not throw.
        SkillRegistry reg = new SkillRegistry(
                List.of(tmp.resolve("nope")),
                List.of(tmp.resolve("also-nope")),
                Duration.ofMinutes(1));
        assertTrue(reg.list().isEmpty());
    }

    @Test
    void bodyPreservesMarkdown(@TempDir Path tmp) throws IOException {
        Path user = tmp.resolve("user-skills");
        Files.createDirectories(user.resolve("md"));
        Files.writeString(user.resolve("md/SKILL.md"), """
                ---
                name: md
                description: markdown body
                ---
                # heading
                - one
                - two
                """);
        SkillRegistry reg = new SkillRegistry(List.of(), List.of(user), Duration.ofMinutes(1));
        String body = reg.getBody("md").orElseThrow();
        assertTrue(body.contains("# heading"));
        assertTrue(body.contains("- one"));
    }
}
