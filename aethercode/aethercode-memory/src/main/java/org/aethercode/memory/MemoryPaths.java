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

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Resolves the on-disk location of the layered memory directories. Mirrors the TS
 * {@code memdir/paths.ts} rules.
 *
 * <ul>
 *   <li>USER     -&gt; {@code <memoryBase>/agent-memory/<agentType>/}</li>
 *   <li>PROJECT  -&gt; {@code <cwd>/.aethercode/agent-memory/<agentType>/}</li>
 *   <li>LOCAL    -&gt; {@code <cwd>/.aethercode/agent-memory-local/<agentType>/}</li>
 * </ul>
 *
 * <p>{@code memoryBase} defaults to {@code $HOME/.aethercode}, overridable via
 * {@code AETHERCODE_MEMORY_DIR}. The {@code agentType} is sanitised (colons -&gt; dashes) so
 * that plug-in namespaced agents can be used as directory names.
 */
public final class MemoryPaths {

    public static final String ENTRYPOINT_NAME = "MEMORY.md";
    public static final int MAX_ENTRYPOINT_LINES = 200;
    public static final int MAX_ENTRYPOINT_BYTES = 25_000;

    private static final Pattern COLON = Pattern.compile(":");

    private MemoryPaths() {}

    public static String sanitize(String agentType) {
        return COLON.matcher(agentType).replaceAll("-");
    }

    public static Path memoryBase() {
        String override = System.getenv("AETHERCODE_MEMORY_DIR");
        if (override != null && !override.isBlank()) return Path.of(override);
        return Path.of(System.getProperty("user.home")).resolve(".aethercode");
    }

    public static Path agentMemoryDir(String agentType, MemoryScope scope, Path cwd) {
        // resolve AUTO to a concrete scope (PROJECT in repo,
        // USER otherwise) before computing the path.
        if (scope == MemoryScope.AUTO) scope = scope.resolve(cwd);
        String safe = sanitize(agentType);
        Path base = memoryBase();
        return switch (scope) {
            case USER    -> base.resolve("agent-memory").resolve(safe);
            case PROJECT -> cwd.resolve(".aethercode").resolve("agent-memory").resolve(safe);
            case LOCAL   -> cwd.resolve(".aethercode").resolve("agent-memory-local").resolve(safe);
            // SESSION-scope memory lives in the
            // daemon's SQLite DB, not a per-cwd directory.
            // We return a sentinel path under the memory
            // base so callers that need a non-null Path
            // for the {@code entrypoint} helper get one;
            // the actual storage is the {@code SessionMemoryStore}
            // (see LayeredMemoryStore.sessionStore()).
            case SESSION -> base.resolve("agent-session-memory").resolve(safe);
            case AUTO    -> base.resolve("agent-memory").resolve(safe); // unreachable
        };
    }

    public static Path entrypoint(Path agentMemoryDir) {
        return agentMemoryDir.resolve(ENTRYPOINT_NAME);
    }
}
