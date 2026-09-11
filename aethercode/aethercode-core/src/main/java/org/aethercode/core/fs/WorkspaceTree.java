package org.aethercode.core.fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * print a directory tree in the classic {@code tree} style.
 * Bounded by depth and a configurable {@code maxEntries} so a huge
 * {@code node_modules} doesn't lock up the TUI.
 */
public final class WorkspaceTree {

    public record Options(
            int maxDepth,
            int maxEntries,
            boolean showHidden,
            boolean showFiles,
            boolean directoriesOnly
    ) {
        public static Options defaults() {
            return new Options(5, 200, false, true, false);
        }
    }

    public record Node(String name, Path path, boolean directory, List<Node> children) {
        public boolean isFile() { return !directory; }
    }

    public record RenderResult(Node root, List<String> lines, int truncated) {}

    private WorkspaceTree() {}

    /** scan a directory into a tree. */
    public static Node scan(Path root, Options opts) throws IOException {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(opts, "opts");
        if (!Files.exists(root)) throw new IOException("path does not exist: " + root);
        return scanRecursive(root, root.getFileName() == null ? root.toString() : root.getFileName().toString(), 0, opts, new int[]{0});
    }

    private static Node scanRecursive(Path p, String name, int depth, Options opts, int[] counter) throws IOException {
        boolean isDir = Files.isDirectory(p);
        List<Node> children = new ArrayList<>();
        if (isDir && depth < opts.maxDepth() && counter[0] < opts.maxEntries()) {
            try (var stream = Files.list(p)) {
                List<Path> sorted = new ArrayList<>();
                stream.forEach(sorted::add);
                sorted.sort(Comparator.comparing(Path::getFileName));
                for (Path c : sorted) {
                    if (counter[0] >= opts.maxEntries()) break;
                    String fileName = c.getFileName().toString();
                    if (!opts.showHidden() && fileName.startsWith(".")) continue;
                    boolean childIsDir = Files.isDirectory(c);
                    if (!opts.showFiles() && !childIsDir) continue;
                    if (opts.directoriesOnly() && !childIsDir) continue;
                    counter[0]++;
                    children.add(scanRecursive(c, fileName, depth + 1, opts, counter));
                }
            }
        }
        return new Node(name, p, isDir, children);
    }

    /** render a tree to a list of lines. */
    public static RenderResult render(Node root) {
        List<String> lines = new ArrayList<>();
        int[] truncated = {0};
        lines.add(root.name());
        renderChildren(root.children(), "", lines, truncated);
        return new RenderResult(root, lines, truncated[0]);
    }

    private static void renderChildren(List<Node> children, String prefix, List<String> out, int[] truncated) {
        for (int i = 0; i < children.size(); i++) {
            Node c = children.get(i);
            boolean last = i == children.size() - 1;
            String connector = last ? "└── " : "├── ";
            String display = c.name() + (c.directory() ? "/" : "");
            out.add(prefix + connector + display);
            if (!c.children().isEmpty()) {
                String nextPrefix = prefix + (last ? "    " : "│   ");
                renderChildren(c.children(), nextPrefix, out, truncated);
            }
        }
    }

    /** one-shot: scan and render. */
    public static RenderResult scanAndRender(Path root, Options opts) throws IOException {
        Node n = scan(root, opts);
        return render(n);
    }

    /** format a node as a single string. */
    public static String toString(RenderResult r) {
        return String.join("\n", r.lines());
    }
}
