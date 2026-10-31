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

        Path path = Path.of(pathStr).toAbsolutePath().normalize();

        if (!isPathAllowed(path, ctx)) {
            return Tool.ToolResult.error("write refused: " + path + " is outside the working directory");
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
            return Tool.ToolResult.of("wrote " + after + " bytes to " + path);
        } catch (IOException e) {
            return Tool.ToolResult.error("write failed: " + e.getMessage());
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

    private static boolean isPathAllowed(Path p, Tool.CallContext ctx) {
        if ("1".equals(System.getenv("AETHERCODE_ALLOW_ANY_PATH"))) return true;
        // prefer the SESSION's cwd from AppState (passed via
        // CallContext.extras by StreamingToolExecutor). Each session
        // is bound to a cwd (set by createSession / setCwd /
        // switchProject), and file_write must respect that — not the
        // JVM-launch cwd. legacy this code read the global
        // `aethercode.cwd` system property which only ever matched the
        // daemon's own --cwd argument, so a model that wrote to a
        // session whose cwd differed (very common: user creates a
        // session in D:\tmp\abc_1, then switches to a new one in
        // D:\work\foo) hit `write refused: ... is outside the working
        // directory` and gave up. The legacy fallback below
        // (system property) is kept for ad-hoc tool invocations
        // without an AppState.
        Path cwd = null;
        if (ctx != null) {
            Object appStateObj = ctx.extra("app_state");
            if (appStateObj != null) {
                try {
                    Object cwdObj = appStateObj.getClass().getMethod("cwd").invoke(appStateObj);
                    if (cwdObj instanceof Path) cwd = (Path) cwdObj;
                } catch (Exception reflect) {
                    // Fall through to system-property path.
                }
            }
        }
        if (cwd == null) {
            // Fallback: R16's original behaviour for ad-hoc usage
            // without an engine (CLI direct invocation).
            String engineCwd = System.getProperty("aethercode.cwd");
            cwd = (engineCwd != null && !engineCwd.isBlank())
                    ? Path.of(engineCwd).toAbsolutePath().normalize()
                    : Path.of("").toAbsolutePath().normalize();
        }
        // case-insensitive comparison on Windows. Without this, a model
        // that emits `D:\tmp\abc\pom.xml` (uppercase D, the Windows
        // canonical form) is rejected when the user passed `--cwd d:\tmp\abc`
        // (lowercase d, picocli preserves case). `Path.startsWith` is
        // case-sensitive even on Windows; we lower-case both sides to match
        // the OS's case-insensitive-but-case-preserving file system.
        if (isWindows()) {
            return p.toString().toLowerCase().startsWith(cwd.toString().toLowerCase());
        }
        return p.startsWith(cwd);
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name");
        return os != null && os.toLowerCase().contains("win");
    }
}
