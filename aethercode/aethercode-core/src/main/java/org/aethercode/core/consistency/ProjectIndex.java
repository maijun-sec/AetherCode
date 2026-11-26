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

package org.aethercode.core.consistency;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * R415: the project's DECLARATION INDEX — which file declares which constant,
 * and with what value.
 *
 * <h2>★ WHY THE ENGINE NEEDS ITS OWN INDEX ★</h2>
 * <p>The consistency check has to answer "where else is this constant
 * declared, and does it still carry the old value?". That is a question
 * about the whole tree, and the tree is walked ONCE per check — parsing
 * each file once and indexing by symbol.
 *
 * <p>★ THE WALK HAS TO KNOW WHAT IS NOT SOURCE ★ the existing
 * {@code GrepTool} walks everything: {@code .git}, {@code target/},
 * {@code node_modules/}. For a grep that is merely slow; for a consistency
 * check it is <b>wrong</b> — a compiled {@code .class} or a stale copy in
 * {@code target/} would be reported as a "copy that was not updated",
 * which is noise the user cannot act on.
 *
 * <h2>★ THE PROJECT'S OWN {@code .gitignore} IS THE AUTHORITY ★</h2>
 * <p>A project knows what is build output; the engine does not. So the
 * index reads the root {@code .gitignore} and honours it. A file the
 * project ignores is not a copy of anything.
 */
public final class ProjectIndex {

    /**
     * Directories that are never source, whatever the project says.
     *
     * <p>★ TOOL AND VCS OWNED ★ a {@code .git} directory belongs to git, a
     * {@code node_modules} to npm, a {@code target} to the build.
     */
    public static final Set<String> ALWAYS_IGNORED_DIRS = Set.of(
            ".git", ".svn", ".hg", "node_modules", "target", "build", "out",
            "dist", "bin", "obj", ".venv", "venv", "__pycache__", ".mypy_cache",
            ".pytest_cache", ".tox", ".gradle", ".idea", ".vscode", ".next",
            ".nuxt", ".cache", ".mvn", ".gradle-cache", "coverage", ".terraform");

    /** Files bigger than this are not parsed. */
    public static final long MAX_FILE_BYTES = 1_048_576L;

    /** The walk stops after this many files. */
    public static final int MAX_FILES = 20_000;

    /** One declaration, in one file. */
    public record Occurrence(Path file, DeclarationSlots.Slot slot) {
        /** "src/AppConfig.java:12" — the label a report shows. */
        public String label() {
            String rel = file.getFileName() == null ? file.toString() : file.getFileName().toString();
            return rel + ":" + slot.line();
        }
    }

    /** The root that was scanned. */
    private final Path root;

    /** symbol -> every place it is declared. */
    private final Map<String, List<Occurrence>> bySymbol;

    /**
     * file -> the values that file declares.
     *
     * <p>★ WHY THE SECOND INDEX ★ the shell channel needs the OTHER
     * direction: "what did THIS file declare before?". Building it while
     * scanning costs nothing extra, and looking it up by file costs
     * nothing — whereas deriving it from {@link #bySymbol} would be a
     * scan per file.
     */
    private final Map<Path, Map<String, String>> byFile;

    /** The files that were scanned. */
    private final List<Path> files;

    private ProjectIndex(Path root, Map<String, List<Occurrence>> bySymbol,
                         Map<Path, Map<String, String>> byFile, List<Path> files) {
        this.root = root;
        this.bySymbol = bySymbol;
        this.byFile = byFile;
        this.files = files;
    }

    public Path root() {
        return root;
    }

    /** The files that were scanned. */
    public List<Path> files() {
        return files;
    }

    /** How many files were scanned. */
    public int fileCount() {
        return files.size();
    }

    /** Every declaration of {@code symbol}. */
    public List<Occurrence> occurrences(String symbol) {
        List<Occurrence> out = bySymbol.get(symbol);
        return out == null ? List.of() : out;
    }

    /**
     * Every symbol the project declares.
     *
     * <p>★ WHY THE INDEX CAN BE DIFFED ★ the shell channel changes files
     * without the write path ever seeing them, so the "before" of a shell
     * write has to come from somewhere else: from an EARLIER INDEX of the
     * same tree. Diffing two indexes is what turns "the command wrote
     * something" into "the command changed a constant from 3 to 5".
     */
    public Set<String> symbols() {
        return bySymbol.keySet();
    }

    /**
     * The values one file declares, symbol -&gt; value.
     *
     * @param file the file
     * @return the map (empty when the file declares nothing)
     */
    public Map<String, String> valuesOf(Path file) {
        if (file == null) return Map.of();
        Map<String, String> out = byFile.get(file.toAbsolutePath().normalize());
        return out == null ? Map.of() : out;
    }

    /**
     * Every file that declares at least one value.
     *
     * @return file -&gt; symbol -&gt; value
     */
    public Map<Path, Map<String, String>> valuesByFile() {
        return byFile;
    }

    /** Is this symbol declared anywhere in the project? */
    public boolean declares(String symbol) {
        return bySymbol.containsKey(symbol);
    }

    /**
     * Scan the project and index its declarations.
     *
     * <p>★ IT NEVER THROWS ★ a project that cannot be walked yields an
     * EMPTY index, and the check reports "I could not see the tree" —
     * which is a fact the user can act on — rather than an exception that
     * aborts the turn.
     */
    public static ProjectIndex scan(Path root) {
        Map<String, List<Occurrence>> bySymbol = new LinkedHashMap<>();
        Map<Path, Map<String, String>> byFile = new LinkedHashMap<>();
        List<Path> files = new ArrayList<>();
        if (root == null || !Files.isDirectory(root)) {
            return new ProjectIndex(root, bySymbol, byFile, files);
        }
        Path base = root.toAbsolutePath().normalize();
        List<Pattern> ignores = readGitignore(base);
        try (java.util.stream.Stream<Path> walk = Files.walk(base)) {
            java.util.Iterator<Path> it = walk.iterator();
            while (it.hasNext()) {
                Path p = it.next();
                if (files.size() >= MAX_FILES) break;
                if (!Files.isRegularFile(p)) continue;
                Path rel = base.relativize(p);
                if (isIgnored(rel, ignores)) continue;
                if (Files.size(p) > MAX_FILE_BYTES) continue;
                String content = readText(p);
                if (content == null) continue;
                files.add(p);
                Map<String, String> values = new LinkedHashMap<>();
                for (DeclarationSlots.Slot slot : DeclarationSlots.parse(p, content).values()) {
                    bySymbol.computeIfAbsent(slot.symbol(), k -> new ArrayList<>())
                            .add(new Occurrence(p, slot));
                    values.putIfAbsent(slot.symbol(), slot.value());
                }
                if (!values.isEmpty()) {
                    byFile.put(p.toAbsolutePath().normalize(), values);
                }
            }
        } catch (IOException | RuntimeException e) {
            // an unreadable tree is an empty tree, not a crash
        }
        return new ProjectIndex(base, bySymbol, byFile, files);
    }

    /**
     * Read a file as text, or null when it is not text.
     *
     * <p>★ THE NUL BYTE TEST ★ a binary file that happens to be named
     * {@code .java} would otherwise be parsed as source.
     */
    private static String readText(Path p) {
        try {
            byte[] bytes = Files.readAllBytes(p);
            int probe = Math.min(bytes.length, 8192);
            for (int i = 0; i < probe; i++) {
                if (bytes[i] == 0) return null;
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Is this relative path ignored? */
    private static boolean isIgnored(Path rel, List<Pattern> ignores) {
        for (Path part : rel) {
            String n = part.toString();
            if (ALWAYS_IGNORED_DIRS.contains(n)) return true;
        }
        String s = rel.toString().replace('\\', '/');
        for (Pattern p : ignores) {
            if (p.matcher(s).matches()) return true;
        }
        return false;
    }

    /**
     * The project's own ignore rules.
     *
     * <p>★ A SIMPLE SUBSET ★ {@code .gitignore} is a rich format (negation,
     * directory-only rules, escapes). This reads the common part: a plain
     * path or glob, optionally anchored at the root, optionally
     * directory-only. A pattern this cannot express is skipped — and a
     * pattern this cannot express means the file is SCANNED, which is the
     * safe direction (a scan that looks at one file too many reports
     * nothing; a scan that skipped one too many misses a copy).
     */
    private static List<Pattern> readGitignore(Path root) {
        List<Pattern> out = new ArrayList<>();
        Path gi = root.resolve(".gitignore");
        if (!Files.isRegularFile(gi)) return out;
        List<String> lines;
        try {
            lines = Files.readAllLines(gi, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return out;
        }
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("!")) continue;   // negation is not supported
            boolean dirOnly = line.endsWith("/");
            if (dirOnly) line = line.substring(0, line.length() - 1);
            if (line.isEmpty()) continue;
            boolean anchored = line.startsWith("/");
            if (anchored) line = line.substring(1);
            if (line.isEmpty()) continue;
            String regex = globToRegex(line);
            if (regex == null) continue;
            try {
                out.add(Pattern.compile(anchored ? regex : "(.*/)?" + regex));
            } catch (Exception ignored) {
                // an unusable pattern is skipped, and the file is scanned
            }
        }
        return out;
    }

    /** A gitignore-style glob to a regex, or null when it is not usable. */
    private static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> {
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                        sb.append(".*");
                        i++;
                    } else {
                        sb.append("[^/]*");
                    }
                }
                case '?' -> sb.append("[^/]");
                case '.', '(', ')', '+', '|', '^', '$', '@', '%', '{', '}' ->
                        sb.append('\\').append(c);
                case '[' -> sb.append('[');
                case ']' -> sb.append(']');
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** A short description for a report. */
    public String describe() {
        return root + " (" + files.size() + " files, " + bySymbol.size() + " symbols)";
    }

    /** Lower-cased helper for a file name. */
    static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
