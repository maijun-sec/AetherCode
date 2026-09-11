package org.aethercode.tools.file;

import org.aethercode.core.permission.PermissionResult;
import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.ToolDef;
import org.aethercode.core.tool.Tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Read a file. Mirrors the TS {@code FileReadTool}.
 *
 * <p>Capabilities:
 * <ul>
 *   <li>Read by exact line range ({@code offset}, {@code limit})</li>
 *   <li>Detect binary files (read first 4 KiB, count NUL bytes) and refuse with a clear error</li>
 *   <li>prior round: image files (PNG, JPG, GIF, WebP) are read and returned
 *       as a base64-encoded {@link Tool.Attachment.ImageAttachment} with the correct
 *       mime type. The TUI / model can render these; the model
 *       itself can be wired to see the image via the engine's
 *       media path (prior round+).</li>
 *   <li>Annotate each line with its 1-indexed line number, formatted as
 *       {@code "<line>: <content>"} so the model can refer to a line by its number</li>
 *   <li>prior round: path sandbox. Refuses to read paths outside the
 *       engine's cwd (the same {@code aethercode.cwd} system
 *       property {@code FileWriteTool} checks), unless
 *       {@code AETHERCODE_ALLOW_ANY_PATH=1} is set. The
 *       sandbox is case-insensitive on Windows to match
 *       {@code FileWriteTool}. Symlink targets are NOT
 *       followed when checking the sandbox — the literal
 *       path is what we compare.</li>
 * </ul>
 */
public class FileReadTool {

    public static final String NAME = "file_read";
    /** max image size we'll base64-encode. Larger images are
     *  refused with a clear error rather than ballooning the tool
     *  result to many MB. 10 MB matches the OpenAI / Anthropic
     *  limits for image inputs. */
    public static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;

    public static Tool build() {
        var props = new LinkedHashMap<String, Map<String, Object>>();
        props.put("file_path", Tools.stringProp("Absolute path to the file to read."));
        props.put("offset",    Tools.intProp("Optional 0-indexed line offset. Omit to read from the start."));
        props.put("limit",     Tools.intProp("Optional max number of lines to return. Omit to read the whole file."));
        Map<String, Object> schema = Tools.objectSchema(props, "file_path");

        return Tools.build(new ToolDef(
                NAME,
                "Read a file from disk. Returns the file's content with 1-indexed line numbers. " +
                        "Use offset/limit to read a slice. Image files (PNG/JPG/GIF/WebP) are " +
                        "returned as a base64 attachment with the correct mime type.",
                schema,
                (input, ctx) -> CompletableFuture.completedFuture(call(input, ctx))
        ));
    }

    public static Tool.ToolResult call(Map<String, Object> input, Tool.CallContext ctx) {
        String pathStr = (String) input.get("file_path");
        if (pathStr == null || pathStr.isBlank()) {
            return Tool.ToolResult.error("file_path is required");
        }
        Path path = Path.of(pathStr).toAbsolutePath().normalize();
        Object offsetRaw = input.get("offset");
        Object limitRaw  = input.get("limit");
        int offset = offsetRaw instanceof Number n ? n.intValue() : -1;
        int limit  = limitRaw  instanceof Number n ? n.intValue() : -1;

        // mirror R88's FileWriteTool sandbox. The model
        // can read freely inside the engine's cwd; reads
        // outside are refused. Set AETHERCODE_ALLOW_ANY_PATH=1
        // to opt out (test/CI use case).
        if (!isPathAllowed(path, ctx)) {
            return Tool.ToolResult.error("read refused: " + path + " is outside the working directory");
        }

        if (!Files.exists(path)) {
            return Tool.ToolResult.error("file does not exist: " + path);
        }
        if (Files.isDirectory(path)) {
            return Tool.ToolResult.error("path is a directory, not a file: " + path);
        }
        try {
            long size = Files.size(path);
            // image detection by magic bytes. We check the
            // first 16 bytes for known image signatures; this
            // catches files saved with the wrong extension
            // (e.g. a PNG renamed to .jpg) which a path-based
            // check would miss.
            String mime = detectImageMime(path);
            if (mime != null) {
                if (size > MAX_IMAGE_BYTES) {
                    return Tool.ToolResult.error(
                            "image too large: " + size + " bytes (max " + MAX_IMAGE_BYTES + ")");
                }
                return readImage(path, mime);
            }
            // Non-image files: existing path. Detect binary by
            // NUL-byte ratio in the first 4 KiB.
            byte[] head = readHead(path, 4096);
            if (looksBinary(head)) {
                return Tool.ToolResult.error(
                        "binary file detected (" + size + " bytes) — " +
                                "use a different tool (e.g. head/tail via bash) for binary data");
            }
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            int from = offset < 0 ? 0 : Math.min(offset, lines.size());
            int to   = limit  < 0 ? lines.size() : Math.min(from + limit, lines.size());
            StringBuilder sb = new StringBuilder();
            int width = String.valueOf(to).length();
            for (int i = from; i < to; i++) {
                sb.append(String.format("%" + width + "d\t%s%n", i + 1, lines.get(i)));
            }
            if (lines.isEmpty()) {
                return Tool.ToolResult.of("(empty file)\n");
            }
            return Tool.ToolResult.of(sb.toString());
        } catch (IOException e) {
            return Tool.ToolResult.error("read failed: " + e.getMessage());
        }
    }

    /** read an image and return it as a {@link Tool.Attachment}
     *  with the base64-encoded bytes. The TUI can render the
     *  attachment; the engine can wire the model to see it via
     *  Media (prior round+). */
    private static Tool.ToolResult readImage(Path path, String mime) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        String b64 = Base64.getEncoder().encodeToString(bytes);
        String body = "(image: " + mime + ", " + bytes.length + " bytes)";
        return new Tool.ToolResult(
                body,
                List.of(new Tool.Attachment.ImageAttachment(mime, b64, bytes.length)),
                false);
    }

    /** detect image mime type by both path extension AND
     *  magic bytes. Returns the mime type or null if the file
     *  isn't a recognised image. */
    public static String detectImageMime(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        // Cheap path-based check first.
        if (name.endsWith(".png"))  return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".gif"))  return "image/gif";
        if (name.endsWith(".webp")) return "image/webp";
        // Fall back to magic-byte inspection.
        try {
            byte[] head = readHead(path, 16);
            if (head.length >= 8
                    && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G'
                    && head[4] == '\r' && head[5] == '\n' && head[6] == 0x1A && head[7] == '\n') {
                return "image/png";
            }
            if (head.length >= 3
                    && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) {
                return "image/jpeg";
            }
            if (head.length >= 6
                    && head[0] == 'G' && head[1] == 'I' && head[2] == 'F' && head[3] == '8'
                    && (head[4] == '7' || head[4] == '9') && head[5] == 'a') {
                return "image/gif";
            }
        } catch (IOException ignored) {}
        return null;
    }

    public static boolean isReadOnly(Map<String, Object> input) { return true; }

    /**
     * mirror of {@code FileWriteTool.isPathAllowed}.
     * Refuses reads outside the engine's cwd unless
     * {@code AETHERCODE_ALLOW_ANY_PATH=1} is set. Case-
     * insensitive on Windows to match the file system.
     */
    private static boolean isPathAllowed(Path p, Tool.CallContext ctx) {
        if ("1".equals(System.getenv("AETHERCODE_ALLOW_ANY_PATH"))) return true;
        // prefer the SESSION's cwd (passed via CallContext
        // extras by StreamingToolExecutor). legacy only checked
        // the JVM-launch cwd which is the daemon's --cwd, not
        // the session's cwd — so a session whose cwd differs
        // from the daemon's --cwd always got "read refused" on
        // legitimate file_read calls. Fall back to the global
        // system property for ad-hoc tool invocations without
        // an engine.
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
            String engineCwd = System.getProperty("aethercode.cwd");
            cwd = (engineCwd != null && !engineCwd.isBlank())
                    ? Path.of(engineCwd).toAbsolutePath().normalize()
                    : Path.of("").toAbsolutePath().normalize();
        }
        if (isWindows()) {
            return p.toString().toLowerCase().startsWith(cwd.toString().toLowerCase());
        }
        return p.startsWith(cwd);
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name");
        return os != null && os.toLowerCase().contains("win");
    }

    private static byte[] readHead(Path p, int n) throws IOException {
        try (var in = Files.newInputStream(p)) {
            return in.readNBytes(n);
        }
    }

    private static boolean looksBinary(byte[] head) {
        if (head.length == 0) return false;
        int nul = 0;
        for (byte b : head) if (b == 0) nul++;
        return nul * 10 > head.length;
    }
}
