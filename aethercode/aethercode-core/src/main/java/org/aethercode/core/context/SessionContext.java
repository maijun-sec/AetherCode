package org.aethercode.core.context;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * auto-captured context that gets prepended to the system prompt. Modelled
 * on the TS {@code services/SessionMemory/sessionMemory.ts}.
 *
 * <p>The capturer walks the working directory and shells out to {@code git} to
 * learn the branch + dirty status, picks up a small fixed subset of env vars,
 * and records the OS / hostname / timestamp. The full snapshot is rendered
 * into a system-prompt block.
 */
public record SessionContext(
        Path cwd,
        String gitBranch,
        boolean gitDirty,
        String gitHead,
        String os,
        String hostname,
        String user,
        Instant capturedAt,
        Map<String, String> env
) {

    public SessionContext {
        if (cwd == null) cwd = Path.of("").toAbsolutePath();
        if (env == null) env = Map.of();
    }

    /**
     * Render the context as a system-prompt block. Always includes a header so
     * the model knows the block is bounded.
     */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("<aethercode-context>\n");
        sb.append("cwd:         ").append(cwd).append('\n');
        if (gitBranch != null) {
            sb.append("git.branch:  ").append(gitBranch);
            sb.append(gitDirty ? " (dirty)\n" : "\n");
        }
        if (gitHead != null) {
            sb.append("git.head:    ").append(gitHead).append('\n');
        }
        sb.append("os:          ").append(os == null ? "unknown" : os).append('\n');
        sb.append("hostname:    ").append(hostname == null ? "unknown" : hostname).append('\n');
        if (user != null) sb.append("user:        ").append(user).append('\n');
        sb.append("captured_at: ").append(capturedAt).append('\n');
        for (var e : env.entrySet()) {
            sb.append("env.").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        }
        sb.append("</aethercode-context>\n");
        return sb.toString();
    }

    /** a builder for tests — capture normally goes through {@link SessionContextCapturer}. */
    public static Builder builder() { return new Builder(); }
    public static final class Builder {
        private Path cwd;
        private String gitBranch;
        private boolean gitDirty;
        private String gitHead;
        private String os;
        private String hostname;
        private String user;
        private Instant capturedAt = Instant.now();
        private final Map<String, String> env = new LinkedHashMap<>();
        public Builder cwd(Path p) { this.cwd = p; return this; }
        public Builder gitBranch(String b) { this.gitBranch = b; return this; }
        public Builder gitDirty(boolean d) { this.gitDirty = d; return this; }
        public Builder gitHead(String h) { this.gitHead = h; return this; }
        public Builder os(String o) { this.os = o; return this; }
        public Builder hostname(String h) { this.hostname = h; return this; }
        public Builder user(String u) { this.user = u; return this; }
        public Builder env(String k, String v) { this.env.put(k, v); return this; }
        public Builder capturedAt(Instant t) { this.capturedAt = t; return this; }
        public SessionContext build() {
            return new SessionContext(cwd, gitBranch, gitDirty, gitHead, os, hostname, user, capturedAt, env);
        }
    }
}
