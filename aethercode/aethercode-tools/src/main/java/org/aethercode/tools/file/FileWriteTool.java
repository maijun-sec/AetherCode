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

package org.aethercode.tools.file;

import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.ToolDef;
import org.aethercode.core.tool.Tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Write a file. Mirrors the TS {@code FileWriteTool}.
 *
 * <p>Two write modes (R720-B1):
 * <ul>
 *   <li>{@code overwrite} (default for backward compat) — truncate + write, replacing any
 *       existing content.</li>
 *   <li>{@code append} — open in append mode, writing {@code content} at end-of-file.</li>
 * </ul>
 *
 * <p>Default-mode safety net: when {@code mode} is omitted AND the target path already
 * exists with size {@code > LARGE_FILE_THRESHOLD_BYTES} (5 KB), the tool refuses the call
 * rather than truncating silently. This closes the Q19 RAG-Day-2 incident where a model
 * issued an apparent "append" intent via {@code file_write} and instead wiped 130 lines
 * of {@code progress.md} history. The model's only path forward is one of:
 * <ol>
 *   <li>explicit {@code mode="append"} — append the new content after existing bytes</li>
 *   <li>explicit {@code mode="overwrite"} — acknowledged truncation</li>
 *   <li>{@code file_edit} — targeted in-place edit</li>
 *   <li>{@code file_read} first to load current content into context, then
 *       {@code mode="overwrite"} with the full intended payload</li>
 * </ol>
 *
 * <p>Safety: refuses to write outside {@code cwd} unless {@code AETHERCODE_ALLOW_ANY_PATH=1}
 * is set (matches the pre-R720 sandbox).
 *
 * <p>Project policy note: the {@code progress.md} / cumulative-history files are exactly the
 * targets this guard protects. The 5 KB threshold reflects "real history" — files smaller
 * than that are usually plain config or in-progress drafts where truncate-by-default is the
 * sane behaviour. The threshold is not user-configurable from the tool surface; embed a
 * custom build if you need a different gate.
 */
public class FileWriteTool {

    public static final String NAME = "file_write";
    /**
     * Files larger than this byte count and {@code mode}-less writes hit the
     * soft-rejection branch. 5 KB is "real history" territory: small config /
     * drafts stay auto-overwritable, anything that looks like accumulated
     * changes does not.
     */
    public static final long LARGE_FILE_THRESHOLD_BYTES = 5 * 1024L;

    public static final String MODE_OVERWRITE = "overwrite";
    public static final String MODE_APPEND    = "append";

    /** The message the tool returns to the model when it refuses a
     *  default-mode overwrite against a large existing file. Written
     *  to be readable by the model without translating — describes
     *  the four recovery paths in priority order. */
    public static final String REFUSE_LARGE_FILE_MESSAGE =
            "Refusing to truncate a large existing file without an explicit " +
            "`mode`. Either (a) re-issue with `mode=\"append\"` to add new content " +
            "after the existing bytes, (b) re-issue with `mode=\"overwrite\"` only " +
            "after calling `file_read` to confirm the current content, or (c) use " +
            "`file_edit` for a targeted in-place change.";

    public static Tool build() {
        var props = new LinkedHashMap<String, Map<String, Object>>();
        props.put("file_path", Tools.stringProp("Absolute path of the file to write."));
        props.put("content",   Tools.stringProp("The new content of the file."));
        props.put("mode",      Tools.stringProp(
                "Write mode. One of 'overwrite' (default; truncate + write) or " +
                "'append' (add to end of file). Defaulting to 'overwrite' is " +
                "blocked when the target already exists and exceeds 5KB — pass " +
                "the mode explicitly in that case."));
        Map<String, Object> schema = Tools.objectSchema(props, "file_path", "content");
        // `mode` is optional — Tools.stringProp marks it as
        // non-required unless the schema insists; we add it as a
        // non-required property so existing call sites that don't
        // pass mode still validate.
        @SuppressWarnings("unchecked")
        Map<String, Object> modeSchema = (Map<String, Object>) schema.get("properties");
        ((Map<String, Object>) modeSchema.get("mode")).put("default", MODE_OVERWRITE);
        ((Map<String, Object>) modeSchema.get("mode")).put("enum",
                java.util.List.of(MODE_OVERWRITE, MODE_APPEND));
        return Tools.build(new ToolDef(
                NAME,
                "Write a file. Pass `mode=\"append\"` to add content without " +
                        "truncating the existing file, or `mode=\"overwrite\"` to " +
                        "explicitly replace it. Without `mode`, large existing " +
                        "files (>5KB) are protected against silent truncation — " +
                        "the call returns an error so the model can pick the " +
                        "correct mode. Refuses to write outside the current " +
                        "working directory unless AETHERCODE_ALLOW_ANY_PATH=1.",
                schema,
                (input, ctx) -> CompletableFuture.completedFuture(call(input, ctx))
        ));
    }

    public static Tool.ToolResult call(Map<String, Object> input, Tool.CallContext ctx) {
        String pathStr = (String) input.get("file_path");
        String content = (String) input.get("content");
        if (pathStr == null || pathStr.isBlank()) {
            return Tool.ToolResult.error("file_path is required");
        }
        if (content == null) content = "";
        String mode = (String) input.get("mode");
        if (mode == null || mode.isBlank()) mode = MODE_OVERWRITE;
        if (!MODE_OVERWRITE.equals(mode) && !MODE_APPEND.equals(mode)) {
            return Tool.ToolResult.error(
                    "unsupported mode '" + mode + "'; expected 'overwrite' or 'append'");
        }

        // ★ R415: RESOLVE AGAINST THE SESSION'S CWD ★ a relative path used
        // to resolve against the JVM's user.dir — the daemon's launch
        // directory — so a model that wrote "src/Foo.java" in a session
        // pointed at D:/tmp/proj aimed at <launch dir>/src/Foo.java and
        // was then refused as "outside the working directory". The model
        // was right; the resolution was wrong.
        Path path = org.aethercode.core.scope.SessionPaths.resolve(
                pathStr, org.aethercode.core.scope.SessionPaths.cwdOf(ctx));
        if (path == null) {
            return Tool.ToolResult.error("file_path is not a valid path: " + pathStr);
        }

        // ★ R415: THE WRITE SCOPE ★ the tree is the boundary, the
        // tool-owned directories are off limits, and a project without a
        // build system does not get one invented in it.
        org.aethercode.core.scope.ScopeDecision scope =
                org.aethercode.core.scope.WriteScope.fromContext(ctx).check(path);
        if (!scope.allowed()) {
            return Tool.ToolResult.error(scope.message());
        }

        boolean exists = Files.exists(path);
        long existingSize = exists ? safeSize(path) : 0L;

        // R720-B1: when the caller didn't pick a mode AND the
        // file is already "real history" (>=5KB), refuse rather
        // than truncate. Pin the gate so a small-file write
        // remains a normal overwrite (avoids tripping on every
        // tiny file the model writes during a fresh setup).
        boolean modeOmitted = !input.containsKey("mode");
        if (modeOmitted && MODE_OVERWRITE.equals(mode)
                && exists && existingSize > LARGE_FILE_THRESHOLD_BYTES) {
            return Tool.ToolResult.error(REFUSE_LARGE_FILE_MESSAGE
                    + " (target size: " + existingSize + " bytes)");
        }

        try {
            // ★ R407: THE STATE BEFORE THIS WRITE ★ the checkpoint that
            // precedes this turn has to undo it, and the only moment that
            // knows whether the file existed is the moment before the
            // write. Recorded here, not in the tool result: the result
            // is the model's output, not a contract.
            org.aethercode.core.transcript.FileTouchRecorder.noteBeforeWrite(ctx, path);
            // ★ R415: THE CONTENT BEFORE THIS WRITE ★ the consistency
            // check needs to know which declaration MOVED, and that needs
            // both sides. This tool overwrites without reading, so the
            // "before" has to be taken here — the same moment the
            // recorder is told about the write.
            String before = readBefore(path);
            Files.createDirectories(path.getParent());
            if (MODE_APPEND.equals(mode)) {
                // append path: CREATE if absent, APPEND otherwise.
                // WRITE is excluded so existing bytes aren't truncated.
                Files.writeString(path, content, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                long after = Files.size(path);
                return Tool.ToolResult.of("appended " + content.length()
                        + " chars (now " + after + " bytes) to " + path);
            }
            // overwrite path (default + explicit). CREATE
            // so we don't error on a non-existent path;
            // TRUNCATE_EXISTING so a re-write replaces
            // (CREATE alone would only truncate if the
            // file was already there in some JDKs).
            Files.writeString(path, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            long after = Files.size(path);
            // ★ R415: THE IMMEDIATE ADVISORY ★ if this write moved a
            // value-bearing declaration, the engine checks whether the
            // COPIES of it moved with it — and says so in the tool
            // result, which is where the model is looking.
            String advisory = org.aethercode.core.consistency.ConsistencyAdvisory
                    .check(ctx, path, before, content);
            // ★ R415: THE ESCAPE IS SAID OUT LOUD ★ when the scope is
            // switched off, a write that the rules would have refused
            // still happens — and the result says so, because the user
            // is reading "wrote N bytes" and has no other way to know
            // the boundary was not applied.
            return Tool.ToolResult.of(scope.applyNotice(
                    org.aethercode.core.consistency.ConsistencyAdvisory.append(
                            "wrote " + after + " bytes to " + path, advisory)));
        } catch (IOException e) {
            return Tool.ToolResult.error("write failed: " + e.getMessage());
        }
    }

    /**
     * R415: the file's content before the write, or null.
     *
     * <p>★ BIG FILES ARE NOT READ ★ the advisory only needs the text to
     * compare the declarations; reading a 50 MB file to find out that it
     * holds no constant would be a poor trade.
     */
    private static String readBefore(Path path) {
        try {
            if (!Files.isRegularFile(path)) return null;
            if (Files.size(path) > 1_048_576L) return null;
            byte[] bytes = Files.readAllBytes(path);
            int probe = Math.min(bytes.length, 8192);
            for (int i = 0; i < probe; i++) {
                if (bytes[i] == 0) return null;
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public static boolean isDestructive(Map<String, Object> input) {
        // append is non-destructive even when the file
        // exists; overwrite is destructive. The dispatcher
        // / permission-prompt layer can use this to gate
        // auto-approval for overwrite-only calls.
        String mode = (String) input.get("mode");
        return !MODE_APPEND.equals(mode);
    }

    /**
     * Best-effort size probe: returns 0 when the file
     * isn't a regular file (e.g. a directory) or when the
     * OS denies stat. Keeps the gate robust against
     * edge cases without an exception path.
     */
    private static long safeSize(Path path) {
        try {
            if (!Files.isRegularFile(path)) return 0L;
            return Files.size(path);
        } catch (IOException | SecurityException ex) {
            return 0L;
        }
    }

    /**
     * Is this path inside the session's working directory?
     *
     * <p>★ R415: THE RULE MOVED ★ this used to be a private copy of the
     * "inside the cwd" test, and {@code FileReadTool} carried a SECOND
     * copy of it — and the two copies were not the same rule: this one
     * compared with {@code startsWith} on the whole string, so
     * {@code D:\tmp\abcde} counted as inside {@code D:\tmp\abc}. A
     * boundary that a one-letter suffix defeats is not a boundary.
     *
     * <p>★ IT STAYS PUBLIC ★ the tests and the permission layer call it;
     * what changed is that it now has ONE implementation
     * ({@code SessionPaths.isInsideSession}) instead of two that drifted.
     */
    public static boolean isPathAllowed(Path p, Tool.CallContext ctx) {
        return org.aethercode.core.scope.SessionPaths.isInsideSession(p, ctx);
    }
}
