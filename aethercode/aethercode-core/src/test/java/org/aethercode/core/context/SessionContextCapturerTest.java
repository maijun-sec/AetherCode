package org.aethercode.core.context;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SessionContextCapturerTest {

    @Test
    void captureFillsCwdOsUserHostname() {
        SessionContextCapturer cap = new SessionContextCapturer(
                cwd -> new String[]{"main", "abc1234", "0"},
                () -> "test-host",
                () -> "TestOS",
                () -> "tester",
                Map::of);
        SessionContext ctx = cap.capture(Path.of("/tmp"));
        assertThat(ctx.cwd()).isEqualTo(Path.of("/tmp"));
        assertThat(ctx.os()).isEqualTo("TestOS");
        assertThat(ctx.hostname()).isEqualTo("test-host");
        assertThat(ctx.user()).isEqualTo("tester");
    }

    @Test
    void captureReadsGitBranchAndHeadFromShell(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        java.nio.file.Files.createDirectory(tmp.resolve(".git"));
        SessionContextCapturer cap = new SessionContextCapturer(
                cwd -> new String[]{"feature/login", "deadbeef", "1"},
                () -> "host", () -> "OS", () -> "user", Map::of);
        SessionContext ctx = cap.capture(tmp);
        assertThat(ctx.gitBranch()).isEqualTo("feature/login");
        assertThat(ctx.gitHead()).isEqualTo("deadbeef");
        assertThat(ctx.gitDirty()).isTrue();
    }

    @Test
    void capturePicksUpEnvSubset() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("AETHERCODE_MODEL", "claude-sonnet-4-5");
        env.put("USER", "alice");
        env.put("UNRELATED", "ignore-me");
        SessionContextCapturer cap = new SessionContextCapturer(
                cwd -> new String[]{"", "", "0"},
                () -> "h", () -> "o", () -> "u", () -> env);
        SessionContext ctx = cap.capture(Path.of("/x"));
        assertThat(ctx.env()).containsKey("AETHERCODE_MODEL").containsKey("USER");
        assertThat(ctx.env()).doesNotContainKey("UNRELATED");
    }

    @Test
    void captureWithoutGitDirSkipsShell() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        SessionContextCapturer cap = new SessionContextCapturer(
                cwd -> { calls.incrementAndGet(); return new String[]{"main", "h", "0"}; },
                () -> "h", () -> "o", () -> "u", Map::of);
        SessionContext ctx = cap.capture(Path.of("/no-git-here-please"));
        assertThat(calls.get()).isZero();
        assertThat(ctx.gitBranch()).isNull();
    }

    @Test
    void renderIsBoundedByTags() {
        SessionContext ctx = SessionContext.builder()
                .cwd(Path.of("/x"))
                .os("TestOS")
                .hostname("h")
                .user("u")
                .gitBranch("main")
                .build();
        String out = ctx.render();
        assertThat(out).startsWith("<aethercode-context>");
        assertThat(out).endsWith("</aethercode-context>\n");
        assertThat(out).contains("os:          TestOS");
        assertThat(out).contains("git.branch:  main");
    }

    @Test
    void renderSkipsNullGitFields() {
        SessionContext ctx = SessionContext.builder()
                .cwd(Path.of("/x"))
                .os("TestOS")
                .build();
        String out = ctx.render();
        assertThat(out).doesNotContain("git.branch");
        assertThat(out).doesNotContain("git.head");
    }

    @Test
    void renderMarksDirty() {
        SessionContext ctx = SessionContext.builder()
                .cwd(Path.of("/x"))
                .gitBranch("feature").gitDirty(true).build();
        assertThat(ctx.render()).contains("(dirty)");
    }

    @Test
    void renderIncludesEnv() {
        SessionContext ctx = SessionContext.builder()
                .cwd(Path.of("/x"))
                .env("FOO", "bar")
                .build();
        assertThat(ctx.render()).contains("env.FOO: bar");
    }
}
