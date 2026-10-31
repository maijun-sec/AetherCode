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

package org.aethercode.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TeamMemorySyncTest {

    @Test
    void putWritesToTeamDir(@TempDir Path tmp) throws Exception {
        Path team = tmp.resolve("team");
        Path local = tmp.resolve("local");
        TeamMemorySync s = new TeamMemorySync(team, local, "alice");
        String id = s.put("alpha", "first note body");
        assertThat(Files.exists(team.resolve(id + ".md"))).isTrue();
        assertThat(Files.exists(local.resolve(id + ".md"))).isFalse();
    }

    @Test
    void putFileHasFrontMatter(@TempDir Path tmp) throws Exception {
        Path team = tmp.resolve("team");
        TeamMemorySync s = new TeamMemorySync(team, tmp.resolve("local"), "bob");
        String id = s.put("title-here", "body content");
        String content = Files.readString(team.resolve(id + ".md"));
        assertThat(content).startsWith("---\n");
        assertThat(content).contains("id: " + id);
        assertThat(content).contains("author: bob");
        assertThat(content).contains("# title-here");
        assertThat(content).contains("body content");
    }

    @Test
    void listUnionsTeamAndLocal(@TempDir Path tmp) throws Exception {
        Path team = tmp.resolve("team");
        Path local = tmp.resolve("local");
        Files.createDirectories(team);
        Files.createDirectories(local);
        TeamMemorySync s = new TeamMemorySync(team, local, "alice");
        s.put("a", "team-a");
        Files.writeString(local.resolve("b.md"), "---\nid: local-b\nauthor: carol\ncreated_at: 2026-01-01T00:00:00Z\n---\n\n# b\n\nbody");
        List<TeamMemorySync.Note> notes = s.list();
        assertThat(notes).hasSize(2);
        // team one came after, sorted newest-first
        assertThat(notes.get(0).source()).isEqualTo(TeamMemorySync.Source.TEAM);
        assertThat(notes.get(1).source()).isEqualTo(TeamMemorySync.Source.LOCAL);
    }

    @Test
    void listDedupesById(@TempDir Path tmp) throws Exception {
        Path team = tmp.resolve("team");
        Path local = tmp.resolve("local");
        Files.createDirectories(team);
        Files.createDirectories(local);
        TeamMemorySync s = new TeamMemorySync(team, local, "alice");
        // put with a deterministic-looking id and write a local file with the SAME id
        Files.writeString(team.resolve("shared.md"), "---\nid: shared\nauthor: alice\ncreated_at: 2026-08-04T00:00:00Z\n---\n\n# shared\n\nteam-version");
        Files.writeString(local.resolve("shared-local.md"), "---\nid: shared\nauthor: someone\ncreated_at: 2026-01-01T00:00:00Z\n---\n\n# shared\n\nlocal-version");
        List<TeamMemorySync.Note> notes = s.list();
        assertThat(notes).hasSize(1);
        // team source wins
        assertThat(notes.get(0).source()).isEqualTo(TeamMemorySync.Source.TEAM);
    }

    @Test
    void listMissingDirsReturnsEmpty(@TempDir Path tmp) {
        TeamMemorySync s = new TeamMemorySync(tmp.resolve("none"), tmp.resolve("none2"), "x");
        assertThat(s.list()).isEmpty();
    }

    @Test
    void entrypointIsSkipped(@TempDir Path tmp) throws Exception {
        Path team = tmp.resolve("team");
        Files.createDirectories(team);
        Files.writeString(team.resolve(MemoryPaths.ENTRYPOINT_NAME), "user prefers java\n");
        TeamMemorySync s = new TeamMemorySync(team, tmp.resolve("local"), "alice");
        assertThat(s.list()).isEmpty();
    }

    @Test
    void malformedFileIsSkipped(@TempDir Path tmp) throws Exception {
        Path team = tmp.resolve("team");
        Files.createDirectories(team);
        Files.writeString(team.resolve("bad.md"), "this file has no front matter");
        TeamMemorySync s = new TeamMemorySync(team, tmp.resolve("local"), "alice");
        // parse() returns null for non-frontmatter files; list filters them out
        assertThat(s.list()).isEmpty();
    }

    @Test
    void readBodyReturnsFileContents(@TempDir Path tmp) throws Exception {
        Path team = tmp.resolve("team");
        Path local = tmp.resolve("local");
        TeamMemorySync s = new TeamMemorySync(team, local, "alice");
        String id = s.put("title", "hello body");
        List<TeamMemorySync.Note> notes = s.list();
        String body = TeamMemorySync.readBody(notes.get(0));
        assertThat(body).contains("hello body");
    }

    @Test
    void listNewestFirst(@TempDir Path tmp) throws Exception {
        Path team = tmp.resolve("team");
        Files.createDirectories(team);
        // older team note
        Files.writeString(team.resolve("old.md"), "---\nid: old\nauthor: a\ncreated_at: 2020-01-01T00:00:00Z\n---\n\n# old\n");
        TeamMemorySync s = new TeamMemorySync(team, tmp.resolve("local"), "a");
        s.put("new", "fresh");
        List<TeamMemorySync.Note> notes = s.list();
        // put() always generates a timestamped id; what we can assert reliably:
        // the just-put note is sorted first because its created_at is "now".
        assertThat(notes.get(0).createdAt().compareTo("2020-01-01T00:00:00Z")).isPositive();
        assertThat(notes.get(1).id()).isEqualTo("old");
    }
}
