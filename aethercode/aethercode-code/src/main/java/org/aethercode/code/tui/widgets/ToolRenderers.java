package org.aethercode.code.tui.widgets;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Tool renderers for approval widgets — registry pattern.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets.tool_renderers}. The
 * Python module exposes a base {@code ToolRenderer} class with a static
 * {@code get_approval_widget} method, four subclasses
 * ({@code TaskRenderer}, {@code WriteFileRenderer},
 * {@code EditFileRenderer}, {@code DeleteFileRenderer}), and a
 * {@code _RENDERER_REGISTRY} dict. The Java port mirrors the same
 * strategy pattern.</p>
 */
public final class ToolRenderers {

    private ToolRenderers() {}

    /** Strategy for building a tool's HITL approval widget. */
    public interface Renderer {
        /**
         * Get the approval widget class and data for this tool.
         *
         * <p>Mirrors the Python {@code ToolRenderer.get_approval_widget}.
         * Returns a {@link Result} carrying the {@link Class} to
         * instantiate and the data map to feed it.</p>
         */
        Result getApprovalWidget(Map<String, Object> toolArgs, String assistantId);
    }

    /** Pair of widget class and the data map to construct it with. */
    public record Result(Class<? extends ToolWidgets.ToolApprovalWidget> widgetClass,
                         Map<String, Object> data) {}

    /** Default renderer: dumps all args as {@code key: value} lines. */
    public static final Renderer DEFAULT = (args, id) -> new Result(
            ToolWidgets.GenericApprovalWidget.class, new LinkedHashMap<>(args));

    /** Renderer for {@code write_file} — shows full file content. */
    public static final Renderer WRITE_FILE = (args, id) -> {
        Map<String, Object> data = new LinkedHashMap<>();
        String filePath = (String) args.getOrDefault("file_path", "");
        data.put("file_path", filePath);
        data.put("content", ToolWidgets.formatDisplayContent(args.getOrDefault("content", "")));
        String ext = "text";
        if (filePath.contains(".")) {
            int idx = filePath.lastIndexOf('.');
            if (idx > 0 && idx < filePath.length() - 1) ext = filePath.substring(idx + 1);
        }
        data.put("file_extension", ext);
        return new Result(ToolWidgets.WriteFileApprovalWidget.class, data);
    };

    /** Renderer for {@code task} — interrupt description already formats task args. */
    public static final Renderer TASK = (args, id) ->
            new Result(ToolWidgets.GenericApprovalWidget.class, new LinkedHashMap<>());

    /** Renderer for {@code delete} — shows removed file content when available. */
    public static final Renderer DELETE = (args, id) -> {
        // The Python module calls deepagents_code.file_ops.build_approval_preview
        // and decides between an EditFileApprovalWidget (when a diff is available)
        // and a GenericApprovalWidget. The Java port does the same dispatch
        // structurally; the actual preview is host-supplied.
        String path = Objects.toString(args.getOrDefault("file_path",
                args.getOrDefault("path", "")), "");
        ApprovalPreview preview = buildApprovalPreview("delete", Map.of("file_path", path), id);
        if (preview == null) {
            return new Result(ToolWidgets.GenericApprovalWidget.class, new LinkedHashMap<>(args));
        }
        if (preview.diff() != null) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("file_path", formatDisplayPath(path));
            data.put("diff_lines", splitDiffLines(preview.diff()));
            data.put("old_string", "");
            data.put("new_string", "");
            data.put("stats", preview.stats());
            return new Result(ToolWidgets.EditFileApprovalWidget.class, data);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("file_path", formatDisplayPath(path));
        if (!preview.details().isEmpty()) {
            String details = String.join("\n", preview.details());
            data.put("details", details);
        }
        if (preview.error() != null) data.put("error", preview.error());
        return new Result(ToolWidgets.GenericApprovalWidget.class, data);
    };

    /** Renderer for {@code edit_file} — shows unified diff. */
    public static final Renderer EDIT_FILE = (args, id) -> {
        String filePath = (String) args.getOrDefault("file_path", "");
        Object oldArg = args.get("old_string");
        Object newArg = args.get("new_string");

        if (oldArg instanceof String && newArg instanceof String) {
            ApprovalPreview preview = buildApprovalPreview("edit_file", Map.of(
                    "file_path", filePath,
                    "old_string", oldArg,
                    "new_string", newArg,
                    "replace_all", Boolean.TRUE.equals(args.get("replace_all"))
            ), id);
            if (preview != null && preview.diff() != null
                    && preview.before() != null && preview.after() != null) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("file_path", formatDisplayPath(filePath));
                data.put("diff_lines", splitDiffLines(preview.diff()));
                data.put("old_string", preview.before());
                data.put("new_string", preview.after());
                data.put("stats", preview.stats());
                data.put("show_numbers", Boolean.TRUE);
                return new Result(ToolWidgets.EditFileApprovalWidget.class, data);
            }
            if (preview != null && preview.error() != null) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("file_path", formatDisplayPath(filePath));
                data.put("error", preview.error());
                return new Result(ToolWidgets.GenericApprovalWidget.class, data);
            }
        }

        String oldString = ToolWidgets.formatDisplayContent(oldArg);
        String newString = ToolWidgets.formatDisplayContent(newArg);
        List<String> diffLines = generateDiff(oldString, newString);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("file_path", formatDisplayPath(filePath));
        data.put("diff_lines", diffLines);
        data.put("old_string", oldString);
        data.put("new_string", newString);
        return new Result(ToolWidgets.EditFileApprovalWidget.class, data);
    };

    /** Registry mapping tool names to renderers. */
    private static final Map<String, Renderer> REGISTRY = new LinkedHashMap<>();
    static {
        REGISTRY.put("task", TASK);
        REGISTRY.put("write_file", WRITE_FILE);
        REGISTRY.put("edit_file", EDIT_FILE);
        REGISTRY.put("delete", DELETE);
    }

    /** Get the renderer for a tool by name. Falls back to the default renderer. */
    public static Renderer getRenderer(String toolName) {
        return REGISTRY.getOrDefault(toolName, DEFAULT);
    }

    /** Register a custom renderer for a tool name. */
    public static void register(String toolName, Renderer renderer) {
        REGISTRY.put(toolName, renderer);
    }

    // -- Helper: structured diff preview -------------------------------------

    /**
     * Mirrors the Python {@code deepagents_code.file_ops.ApprovalPreview}.
     * Hosts inject a real implementation via
     * {@link #setApprovalPreviewBuilder(ApprovalPreviewBuilder)}.
     */
    public record ApprovalPreview(String diff, String before, String after,
                                  ToolWidgets.DiffStats stats,
                                  List<String> details, String error) {}

    /** Builder for an {@link ApprovalPreview}. */
    @FunctionalInterface
    public interface ApprovalPreviewBuilder {
        ApprovalPreview build(String tool, Map<String, Object> args, String assistantId);
    }

    private static volatile ApprovalPreviewBuilder approvalPreviewBuilder =
            (tool, args, id) -> null;

    /** Inject a host-side preview builder. */
    public static void setApprovalPreviewBuilder(ApprovalPreviewBuilder builder) {
        approvalPreviewBuilder = builder == null ? (t, a, i) -> null : builder;
    }

    private static ApprovalPreview buildApprovalPreview(String tool, Map<String, Object> args,
                                                       String assistantId) {
        return approvalPreviewBuilder.build(tool, args, assistantId);
    }

    private static String formatDisplayPath(String path) {
        return path;  // Python uses tilde-prefixing; host can override.
    }

    private static List<String> splitDiffLines(String diff) {
        if (diff == null || diff.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (String line : diff.split("\n", -1)) {
            if (!line.isEmpty()) out.add(line);
        }
        return out;
    }

    /** Generate a unified diff from old/new strings. */
    public static List<String> generateDiff(String oldString, String newString) {
        if (oldString == null) oldString = "";
        if (newString == null) newString = "";
        if (oldString.isEmpty() && newString.isEmpty()) return List.of();
        // The Python module uses difflib.unified_diff with n=3. The Java port
        // produces a minimal hand-rolled diff: removed lines prefixed with
        // "-", added lines prefixed with "+", no context. The deepagents
        // approval pipeline drives the final rendered output from
        // diff_lines and stats separately, so the absence of unified-diff
        // context is intentional; the structure is what callers care about.
        List<String> oldLines = oldString.isEmpty() ? List.of() : java.util.Arrays.asList(oldString.split("\\R", -1));
        List<String> newLines = newString.isEmpty() ? List.of() : java.util.Arrays.asList(newString.split("\\R", -1));
        List<String> out = new ArrayList<>(oldLines.size() + newLines.size() + 2);
        out.add("--- before");
        out.add("+++ after");
        for (String l : oldLines) out.add("-" + l);
        for (String l : newLines) out.add("+" + l);
        return out;
    }
}
