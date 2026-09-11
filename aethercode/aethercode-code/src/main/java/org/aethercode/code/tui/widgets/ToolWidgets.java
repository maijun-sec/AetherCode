package org.aethercode.code.tui.widgets;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool-specific approval widgets for HITL display.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets.tool_widgets}. The
 * Python module defines a {@code ToolApprovalWidget} base class plus
 * three subclasses ({@code GenericApprovalWidget},
 * {@code WriteFileApprovalWidget}, {@code EditFileApprovalWidget}) that
 * render tool-arg data into a stack of static widgets.</p>
 *
 * <p>The Java port preserves the public data shape ({@code data} map) and
 * produces {@link WidgetNode} trees from {@link #render()}. The actual
 * syntax highlighting / diff coloring is the host TUI's job; the Java
 * port emits a structural description.</p>
 */
public final class ToolWidgets {

    private ToolWidgets() {}

    /** Display limits shared by the approval widgets. */
    public static final int MAX_VALUE_LEN = 200;
    public static final int MAX_LINES = 30;
    public static final int MAX_DIFF_LINES = 50;
    public static final int MAX_PREVIEW_LINES = 20;

    public static final String CREDENTIAL_NOTICE = "Contents hidden — file may contain credentials";

    /** Stand-in for a header with no counts to show. */
    public record DiffStats(int additions, int deletions) {
        public static final DiffStats NONE = new DiffStats(0, 0);
    }

    /** Coerce arbitrary tool-arg content into a displayable string. */
    public static String formatDisplayContent(Object content) {
        if (content == null) return "";
        if (content instanceof String s) return s;
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(content);
        } catch (Exception e) {
            return content.toString();
        }
    }

    /**
     * Base class for tool approval widgets.
     *
     * <p>Mirrors the Python {@code ToolApprovalWidget(Vertical)} class.
     * The {@code data} map carries the tool args (file path, content,
     * diff lines, etc.).</p>
     */
    public abstract static class ToolApprovalWidget extends Widget {
        protected final Map<String, Object> data;

        protected ToolApprovalWidget(Map<String, Object> data) {
            super("", "tool-approval-widget");
            this.data = data == null ? new LinkedHashMap<>() : new LinkedHashMap<>(data);
        }

        public Map<String, Object> data() { return data; }

        /** Default render: emit a placeholder row. */
        @Override
        public WidgetNode render() {
            return new WidgetNode.Static("Tool details not available", WidgetNode.Role.MUTED);
        }
    }

    /** Generic approval widget for unknown tools. */
    public static final class GenericApprovalWidget extends ToolApprovalWidget {
        public GenericApprovalWidget(Map<String, Object> data) {
            super(data);
        }

        @Override
        public WidgetNode render() {
            java.util.List<WidgetNode> rows = new java.util.ArrayList<>();
            for (Map.Entry<String, Object> e : data.entrySet()) {
                if (e.getValue() == null) continue;
                String value = e.getValue().toString();
                if (value.length() > MAX_VALUE_LEN) {
                    int hidden = value.length() - MAX_VALUE_LEN;
                    value = value.substring(0, MAX_VALUE_LEN) + "... (" + hidden + " more chars)";
                }
                rows.add(new WidgetNode.Static(
                        e.getKey() + ": " + value, WidgetNode.Role.TEXT));
            }
            return new WidgetNode.Container(WidgetNode.Layout.VERTICAL, rows, "approval-description");
        }
    }

    /** Approval widget for {@code write_file} — shows file content with syntax highlighting. */
    public static final class WriteFileApprovalWidget extends ToolApprovalWidget {
        public WriteFileApprovalWidget(Map<String, Object> data) {
            super(data);
        }

        @Override
        public WidgetNode render() {
            String filePath = (String) data.getOrDefault("file_path", "");
            String content = formatDisplayContent(data.getOrDefault("content", ""));
            String ext = (String) data.getOrDefault("file_extension", "text");

            java.util.List<WidgetNode> rows = new java.util.ArrayList<>();
            rows.add(new WidgetNode.Row("File", new WidgetNode.Static(filePath,
                    WidgetNode.Role.PRIMARY, false, true, false)));
            rows.add(new WidgetNode.Static(""));

            // TODO: integrate with deepagents-core's isSensitiveFilePath helper.
            if (filePath.toLowerCase().endsWith(".env")
                    || filePath.toLowerCase().contains("credential")) {
                rows.add(new WidgetNode.Static(CREDENTIAL_NOTICE, WidgetNode.Role.MUTED, true, false, false));
            } else {
                String[] lines = content.split("\n", -1);
                int totalLines = lines.length;
                DiffStats stats = new DiffStats(totalLines, 0);
                if (totalLines > MAX_LINES) {
                    String shown = String.join("\n", java.util.Arrays.copyOf(lines, MAX_LINES));
                    int remaining = totalLines - MAX_LINES;
                    rows.add(new WidgetNode.Code(ext, shown + "\n... (" + remaining + " more lines)", true));
                } else {
                    rows.add(new WidgetNode.Code(ext, content, false));
                }
            }
            return new WidgetNode.Container(WidgetNode.Layout.VERTICAL, rows,
                    "write-file-approval-widget");
        }
    }

    /** Approval widget for {@code edit_file} — shows clean diff with colors. */
    public static final class EditFileApprovalWidget extends ToolApprovalWidget {
        public EditFileApprovalWidget(Map<String, Object> data) {
            super(data);
        }

        @Override
        public WidgetNode render() {
            String filePath = (String) data.getOrDefault("file_path", "");
            @SuppressWarnings("unchecked")
            List<String> diffLines = (List<String>) data.getOrDefault("diff_lines", List.of());
            String oldString = formatDisplayContent(data.getOrDefault("old_string", ""));
            String newString = formatDisplayContent(data.getOrDefault("new_string", ""));

            DiffStats stats = resolveStats(diffLines, oldString, newString,
                    (DiffStats) data.get("stats"));

            java.util.List<WidgetNode> rows = new java.util.ArrayList<>();
            rows.add(new WidgetNode.Row("File", new WidgetNode.Static(filePath,
                    WidgetNode.Role.PRIMARY, false, true, false)));

            if (filePath.toLowerCase().endsWith(".env")
                    || filePath.toLowerCase().contains("credential")) {
                rows.add(new WidgetNode.Static(CREDENTIAL_NOTICE, WidgetNode.Role.MUTED, true, false, false));
            } else if (diffLines.isEmpty() && oldString.isEmpty() && newString.isEmpty()) {
                rows.add(new WidgetNode.Static("No changes to display", WidgetNode.Role.MUTED));
            } else if (!diffLines.isEmpty()) {
                rows.add(new WidgetNode.Static(
                        String.join("\n", diffLines), WidgetNode.Role.TEXT));
            } else {
                if (!oldString.isEmpty()) {
                    rows.add(new WidgetNode.Static("Removing:",
                            WidgetNode.Role.ERROR, false, true, false));
                    for (String line : oldString.split("\n", -1)) {
                        if (rows.size() > MAX_PREVIEW_LINES) {
                            rows.add(new WidgetNode.Static(
                                    "... (more lines)", WidgetNode.Role.MUTED, true, false, false));
                            break;
                        }
                        rows.add(new WidgetNode.Static("- " + line, WidgetNode.Role.ERROR));
                    }
                    rows.add(new WidgetNode.Static(""));
                }
                if (!newString.isEmpty()) {
                    rows.add(new WidgetNode.Static("Adding:",
                            WidgetNode.Role.SUCCESS, false, true, false));
                    for (String line : newString.split("\n", -1)) {
                        if (rows.size() > MAX_PREVIEW_LINES) {
                            rows.add(new WidgetNode.Static(
                                    "... (more lines)", WidgetNode.Role.MUTED, true, false, false));
                            break;
                        }
                        rows.add(new WidgetNode.Static("+ " + line, WidgetNode.Role.SUCCESS));
                    }
                }
            }
            return new WidgetNode.Container(WidgetNode.Layout.VERTICAL, rows,
                    "edit-file-approval-widget");
        }
    }

    /** Resolve the counts to show above an approval diff. */
    public static DiffStats resolveStats(List<String> diffLines, String oldString,
                                         String newString, DiffStats stats) {
        if (stats != null) return stats;
        if (!diffLines.isEmpty()) {
            return countDiffChangeLines(diffLines);
        }
        int additions = newString.isEmpty() ? 0 : newString.split("\n", -1).length;
        int deletions = oldString.isEmpty() ? 0 : oldString.split("\n", -1).length;
        return new DiffStats(additions, deletions);
    }

    private static DiffStats countDiffChangeLines(List<String> diffLines) {
        int adds = 0, dels = 0;
        for (String line : diffLines) {
            if (line.startsWith("+") && !line.startsWith("+++")) adds++;
            else if (line.startsWith("-") && !line.startsWith("---")) dels++;
        }
        return new DiffStats(adds, dels);
    }
}
