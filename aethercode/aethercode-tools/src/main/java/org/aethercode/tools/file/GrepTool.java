package org.aethercode.tools.file;

import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.ToolDef;
import org.aethercode.core.tool.Tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Recursive text search. Mirrors the TS {@code GrepTool} but with a Java regex instead of
 * ripgrep. Returns matches as {@code path:line:content} lines, sorted, capped at 200.
 */
public class GrepTool {

    public static final String NAME = "grep";
    private static final int MAX_RESULTS = 200;

    public static Tool build() {
        var props = new LinkedHashMap<String, Map<String, Object>>();
        props.put("pattern",     Tools.stringProp("Java regular expression."));
        props.put("base_dir",    Tools.stringProp("Optional base directory. Defaults to the working directory."));
        props.put("include",     Tools.stringProp("Optional file glob to include, e.g. '*.java'."));
        props.put("max_results", Tools.intProp("Optional cap. Defaults to 200."));
        Map<String, Object> schema = Tools.objectSchema(props, "pattern");
        return Tools.build(new ToolDef(
                NAME,
                "Recursively grep a directory for a regex. Returns path:line:content. " +
                        "Cap 200 by default.",
                schema,
                (input, ctx) -> CompletableFuture.completedFuture(call(input, ctx))
        ));
    }

    public static Tool.ToolResult call(Map<String, Object> input, Tool.CallContext ctx) {
        String pattern = (String) input.get("pattern");
        if (pattern == null || pattern.isBlank()) {
            return Tool.ToolResult.error("pattern is required");
        }
        Path base = input.containsKey("base_dir") && input.get("base_dir") != null
                ? Path.of((String) input.get("base_dir")).toAbsolutePath()
                : Path.of("").toAbsolutePath();
        int cap = input.get("max_results") instanceof Number n ? n.intValue() : MAX_RESULTS;
        String include = (String) input.get("include");
        java.nio.file.PathMatcher includeMatcher = include == null
                ? null
                : base.getFileSystem().getPathMatcher("glob:" + include);

        Pattern compiled;
        try {
            compiled = Pattern.compile(pattern);
        } catch (Exception e) {
            return Tool.ToolResult.error("invalid regex: " + e.getMessage());
        }

        if (!Files.isDirectory(base)) {
            return Tool.ToolResult.error("base_dir is not a directory: " + base);
        }
        StringBuilder sb = new StringBuilder();
        int count = 0;
        try (Stream<Path> walk = Files.walk(base)) {
            Iterable<Path> it = walk::iterator;
            outer:
            for (Path p : (Iterable<Path>) () -> walk.iterator()) {
                if (!Files.isRegularFile(p)) continue;
                if (includeMatcher != null) {
                    String name = p.getFileName().toString();
                    if (!includeMatcher.matches(Path.of(name))) continue;
                }
                List<String> lines;
                try {
                    lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                } catch (Exception e) {
                    continue; // skip unreadable
                }
                for (int i = 0; i < lines.size(); i++) {
                    if (compiled.matcher(lines.get(i)).find()) {
                        if (count >= cap) {
                            sb.append("… (cap reached)\n");
                            break outer;
                        }
                        sb.append(base.relativize(p)).append(":").append(i + 1).append(":").append(lines.get(i)).append('\n');
                        count++;
                    }
                }
            }
        } catch (IOException e) {
            return Tool.ToolResult.error("grep failed: " + e.getMessage());
        }
        if (count == 0) return Tool.ToolResult.of("(no matches)\n");
        return Tool.ToolResult.of(sb.toString());
    }

    public static boolean isReadOnly(Map<String, Object> input) { return true; }
    public static boolean isConcurrencySafe(Map<String, Object> input) { return true; }
}
