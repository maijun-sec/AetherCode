package org.aethercode.tools.file;

import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.ToolDef;
import org.aethercode.core.tool.Tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Targeted edit of an existing file. Mirrors the TS {@code FileEditTool}: accepts an
 * {@code old_string} / {@code new_string} pair, requires uniqueness (the old string must
 * appear exactly once unless {@code replace_all=true}), and refuses to silently no-op.
 *
 * <p>Returns a {@link Tool.Attachment.DiffPreview} so the TUI can render a structured diff
 * alongside the textual result.
 */
public class FileEditTool {

    public static final String NAME = "file_edit";

    /** hint appended to the three common file_edit error
     *  messages so the model stops retrying the same broken
     *  string and re-reads the file first. Mirrors
     *  oh-my-opencode's {@code edit-error-recovery/hook.ts}
     *  reminder, but lives in the tool itself (the bridge
     *  signature can't mutate the result body yet). */
    public static final String HINT_RE_READ =
            "\n[hint] Re-read the file with file_read to see its actual current state, "
          + "then retry with the correct old_string.";

    public static Tool build() {
        var props = new LinkedHashMap<String, Map<String, Object>>();
        props.put("file_path",   Tools.stringProp("Absolute path of the file to edit."));
        props.put("old_string",  Tools.stringProp("The exact text to replace. Must match a unique location unless replace_all is true."));
        props.put("new_string",  Tools.stringProp("The replacement text."));
        props.put("replace_all", Tools.boolProp("If true, replace every occurrence of old_string. Default: false."));
        Map<String, Object> schema = Tools.objectSchema(props, "file_path", "old_string", "new_string");
        return Tools.build(new ToolDef(
                NAME,
                "Edit a file by replacing a unique snippet. Returns a diff for the UI.",
                schema,
                (input, ctx) -> CompletableFuture.completedFuture(call(input, ctx))
        ));
    }

    public static Tool.ToolResult call(Map<String, Object> input, Tool.CallContext ctx) {
        String pathStr = (String) input.get("file_path");
        String oldStr  = (String) input.get("old_string");
        String newStr  = (String) input.get("new_string");
        boolean replaceAll = Boolean.TRUE.equals(input.get("replace_all"));
        if (pathStr == null) return Tool.ToolResult.error("file_path is required");
        if (oldStr  == null) return Tool.ToolResult.error("old_string is required");
        if (newStr  == null) return Tool.ToolResult.error("new_string is required");
        if (oldStr.equals(newStr)) {
            return Tool.ToolResult.error(
                    "old_string and new_string are identical — no-op rejected.\n"
                    + HINT_RE_READ);
        }
        Path path = Path.of(pathStr).toAbsolutePath();
        if (!Files.exists(path)) {
            return Tool.ToolResult.error("file does not exist: " + path);
        }
        try {
            String original = Files.readString(path, StandardCharsets.UTF_8);
            int count = countOccurrences(original, oldStr);
            if (count == 0) {
                return Tool.ToolResult.error(
                        "old_string not found in " + path + ".\n"
                        + HINT_RE_READ);
            }
            if (count > 1 && !replaceAll) {
                return Tool.ToolResult.error(
                        "old_string matches " + count + " places in " + path +
                                " — narrow the snippet or pass replace_all=true.\n"
                                + HINT_RE_READ);
            }
            String updated = replaceAll
                    ? original.replace(oldStr, newStr)
                    : original.replaceFirst(java.util.regex.Pattern.quote(oldStr), java.util.regex.Matcher.quoteReplacement(newStr));
            Files.writeString(path, updated, StandardCharsets.UTF_8);

            return new Tool.ToolResult(
                    "edited " + path + " (" + (replaceAll ? count : 1) + " replacement" +
                            (count == 1 && !replaceAll ? "" : "s") + ")",
                    List.of(new Tool.Attachment.DiffPreview(path.toString(), original, updated))
            );
        } catch (IOException e) {
            return Tool.ToolResult.error("edit failed: " + e.getMessage());
        }
    }

    public static boolean isDestructive(Map<String, Object> input) { return true; }

    private static int countOccurrences(String haystack, String needle) {
        if (needle.isEmpty()) return 0;
        int n = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            n++;
            idx += needle.length();
        }
        return n;
    }
}
