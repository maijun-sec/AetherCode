package org.aethercode.tools.file;

import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.ToolDef;
import org.aethercode.core.tool.Tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Glob over a directory tree. Mirrors the TS {@code GlobTool} but uses Java's
 * {@code Files.find} (a recursive walker that filters by glob pattern) instead of {@code fast-glob}.
 *
 * <p>Returns one path per line, sorted, capped at 200 entries. The cap is a soft safety limit
 * so the model does not blow its context with the contents of {@code node_modules} by accident.
 */
public class GlobTool {

    public static final String NAME = "glob";
    private static final int MAX_RESULTS = 200;

    public static Tool build() {
        var props = new LinkedHashMap<String, Map<String, Object>>();
        props.put("pattern",      Tools.stringProp("Glob pattern, e.g. '**/*.java' or 'src/**/pom.xml'."));
        props.put("base_dir",     Tools.stringProp("Optional base directory. Defaults to the working directory."));
        props.put("max_results",  Tools.intProp("Optional cap. Defaults to 200."));
        Map<String, Object> schema = Tools.objectSchema(props, "pattern");
        return Tools.build(new ToolDef(
                NAME,
                "List files matching a glob. Recursive, sorted, capped at 200 results by default.",
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
        if (!Files.isDirectory(base)) {
            return Tool.ToolResult.error("base_dir is not a directory: " + base);
        }
        // Split pattern into dir-glob and file-glob. fast-glob accepts patterns like
        // "src/**/*.java" — we collapse this to a path matcher over the base.
        java.nio.file.PathMatcher matcher = base.getFileSystem().getPathMatcher("glob:" + pattern);
        List<Path> matches = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(base)) {
            walk.filter(p -> matcher.matches(p) || matcher.matches(base.relativize(p)))
                    .filter(p -> !Files.isDirectory(p))
                    .sorted(Comparator.comparing(Path::toString))
                    .limit(cap)
                    .forEach(matches::add);
        } catch (IOException e) {
            return Tool.ToolResult.error("glob failed: " + e.getMessage());
        }
        if (matches.isEmpty()) return Tool.ToolResult.of("(no matches)\n");
        StringBuilder sb = new StringBuilder();
        for (Path p : matches) sb.append(base.relativize(p)).append('\n');
        if (matches.size() == cap) sb.append("… (cap reached)\n");
        return Tool.ToolResult.of(sb.toString());
    }

    public static boolean isReadOnly(Map<String, Object> input) { return true; }
    public static boolean isConcurrencySafe(Map<String, Object> input) { return true; }
}
