package org.aethercode.tools.file;

import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.ToolDef;
import org.aethercode.core.tool.Tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Write a file. Mirrors the TS {@code FileWriteTool}. Replaces the entire file content.
 *
 * <p>Safety: refuses to write outside {@code cwd} unless an env var
 * {@code AETHERCODE_ALLOW_ANY_PATH=1} is set. This is the minimal sandbox for prior round.
 */
public class FileWriteTool {

    public static final String NAME = "file_write";

    public static Tool build() {
        var props = new LinkedHashMap<String, Map<String, Object>>();
        props.put("file_path", Tools.stringProp("Absolute path of the file to write."));
        props.put("content",   Tools.stringProp("The new content of the file."));
        Map<String, Object> schema = Tools.objectSchema(props, "file_path", "content");
        return Tools.build(new ToolDef(
                NAME,
                "Write a file. Replaces the existing content. Refuses to write outside the " +
                        "current working directory unless AETHERCODE_ALLOW_ANY_PATH=1.",
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
        Path path = Path.of(pathStr).toAbsolutePath().normalize();

        if (!isPathAllowed(path, ctx)) {
            return Tool.ToolResult.error("write refused: " + path + " is outside the working directory");
        }
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
            return Tool.ToolResult.of("wrote " + Files.size(path) + " bytes to " + path);
        } catch (IOException e) {
            return Tool.ToolResult.error("write failed: " + e.getMessage());
        }
    }

    public static boolean isDestructive(Map<String, Object> input) { return true; }

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
