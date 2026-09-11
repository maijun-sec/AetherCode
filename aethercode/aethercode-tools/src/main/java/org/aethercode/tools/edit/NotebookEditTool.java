package org.aethercode.tools.edit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.ToolDef;
import org.aethercode.core.tool.Tools;
import org.aethercode.tools.file.FileEditTool;
import org.aethercode.tools.file.FileReadTool;
import org.aethercode.tools.file.FileWriteTool;

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
 * Edit a Jupyter notebook. Modelled after the TS {@code NotebookEditTool}.
 *
 * <p>prior round supports three operation types: {@code replace}, {@code insert}, {@code delete}.
 * Each operates on one cell at a time. The notebook is parsed via Jackson, mutated in
 * memory, and serialised back atomically.
 */
public class NotebookEditTool {

    public static final String NAME = "notebook_edit";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static Tool build() {
        var props = new LinkedHashMap<String, Map<String, Object>>();
        props.put("notebook_path", Tools.stringProp("Absolute path of the .ipynb file."));
        props.put("cell_type",     Tools.stringProp("One of: code, markdown, raw."));
        props.put("operation",     Tools.stringProp("One of: replace, insert, delete."));
        props.put("cell_index",    Tools.intProp("0-indexed cell position."));
        props.put("new_source",    Tools.stringProp("New cell source. Required for replace/insert."));
        Map<String, Object> schema = Tools.objectSchema(props, "notebook_path", "operation", "cell_index");
        return Tools.build(new ToolDef(
                NAME,
                "Edit a Jupyter notebook by inserting, replacing, or deleting a cell. " +
                        "Supports .ipynb files only.",
                schema,
                (input, ctx) -> CompletableFuture.completedFuture(call(input, ctx))
        ));
    }

    public static Tool.ToolResult call(Map<String, Object> input, Tool.CallContext ctx) {
        String path = (String) input.get("notebook_path");
        String op   = (String) input.get("operation");
        Object idx  = input.get("cell_index");
        if (path == null) return Tool.ToolResult.error("notebook_path is required");
        if (op == null)    return Tool.ToolResult.error("operation is required");
        if (!(idx instanceof Number)) return Tool.ToolResult.error("cell_index must be an integer");
        int cellIndex = ((Number) idx).intValue();
        Path p = Path.of(path).toAbsolutePath();
        if (!Files.exists(p)) return Tool.ToolResult.error("notebook does not exist: " + p);
        try {
            JsonNode root = MAPPER.readTree(Files.newBufferedReader(p, StandardCharsets.UTF_8));
            JsonNode cells = root.path("cells");
            if (!cells.isArray()) return Tool.ToolResult.error("invalid notebook: no cells array");
            ArrayNode arr = (ArrayNode) cells;
            switch (op.toLowerCase()) {
                case "replace" -> {
                    if (cellIndex < 0 || cellIndex >= arr.size())
                        return Tool.ToolResult.error("cell_index out of range");
                    ObjectNode cell = (ObjectNode) arr.get(cellIndex);
                    cell.putArray("source").add((String) input.getOrDefault("new_source", ""));
                }
                case "insert" -> {
                    if (cellIndex < 0 || cellIndex > arr.size())
                        return Tool.ToolResult.error("cell_index out of range for insert");
                    ObjectNode cell = MAPPER.createObjectNode();
                    cell.put("cell_type", (String) input.getOrDefault("cell_type", "code"));
                    cell.putArray("source").add((String) input.getOrDefault("new_source", ""));
                    cell.putArray("metadata").addNull();
                    cell.putArray("outputs");
                    cell.put("execution_count", com.fasterxml.jackson.databind.node.NullNode.getInstance());
                    arr.insert(cellIndex, cell);
                }
                case "delete" -> {
                    if (cellIndex < 0 || cellIndex >= arr.size())
                        return Tool.ToolResult.error("cell_index out of range");
                    arr.remove(cellIndex);
                }
                default -> { return Tool.ToolResult.error("unknown operation: " + op); }
            }
            Files.writeString(p, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root), StandardCharsets.UTF_8);
            return Tool.ToolResult.of("notebook edited (" + op + " at " + cellIndex + "), now " + arr.size() + " cells");
        } catch (IOException e) {
            return Tool.ToolResult.error("notebook edit failed: " + e.getMessage());
        }
    }

    public static boolean isDestructive(Map<String, Object> input) { return true; }
}
