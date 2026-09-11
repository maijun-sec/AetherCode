package org.aethercode.core.patch;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * parse a unified diff (or a multi-file diff) into a list of
 * {@link PatchFile} entries. Modelled on the TS {@code parseUnifiedDiff}
 * helper used by the patch/edit tool.
 *
 * <p>Recognises:
 * <ul>
 *   <li>{@code diff --git a/foo b/foo} — optional file boundary</li>
 *   <li>{@code --- a/path} and {@code +++ b/path} — file paths</li>
 *   <li>{@code @@ -oldStart[,oldCount] +newStart[,newCount] @@ heading} — hunk headers</li>
 *   <li>Lines starting with {@code ' '}, {@code '+'}, {@code '-'} — hunk body</li>
 *   <li>{@code \ No newline at end of file} — ignored</li>
 * </ul>
 */
public final class PatchParser {

    private static final Pattern HUNK_HEADER = Pattern.compile(
            "^@@\\s+-(\\d+)(?:,(\\d+))?\\s+\\+(\\d+)(?:,(\\d+))?\\s+@@(.*)$");

    private static final Pattern FILE_OLD = Pattern.compile("^---\\s+(.+?)\\s*$");
    private static final Pattern FILE_NEW = Pattern.compile("^\\+\\+\\+\\s+(.+?)\\s*$");
    private static final Pattern FILE_INDEX = Pattern.compile("^diff --git\\s+.*$");
    private static final Pattern NO_NEWLINE = Pattern.compile("^\\\\ No newline at end of file$");

    private PatchParser() {}

    public static List<PatchFile> parse(String diff) {
        if (diff == null) diff = "";
        List<PatchFile> files = new ArrayList<>();
        String[] lines = diff.split("\\r?\\n");

        String currentOld = null;
        String currentNew = null;
        List<HunkBuilder> currentHunks = new ArrayList<>();
        HunkBuilder currentHunk = null;
        boolean inFile = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int lineNo = i + 1;

            // File boundary marker
            if (FILE_INDEX.matcher(line).matches()) {
                if (inFile) flushFile(files, currentOld, currentNew, currentHunks);
                currentOld = null; currentNew = null; currentHunks = new ArrayList<>(); currentHunk = null; inFile = true;
                continue;
            }

            Matcher oldM = FILE_OLD.matcher(line);
            if (oldM.matches()) {
                String path = stripPrefix(oldM.group(1), "a/");
                if (!inFile || currentOld != null) {
                    if (inFile) flushFile(files, currentOld, currentNew, currentHunks);
                    currentOld = path; currentNew = null; currentHunks = new ArrayList<>(); currentHunk = null; inFile = true;
                } else {
                    currentOld = path;
                }
                continue;
            }
            Matcher newM = FILE_NEW.matcher(line);
            if (newM.matches()) {
                String path = stripPrefix(newM.group(1), "b/");
                if (!inFile || currentNew != null) {
                    if (inFile) flushFile(files, currentOld, currentNew, currentHunks);
                    currentNew = path; currentHunks = new ArrayList<>(); currentHunk = null; inFile = true;
                } else {
                    currentNew = path;
                }
                continue;
            }

            Matcher hm = HUNK_HEADER.matcher(line);
            if (hm.matches()) {
                int oldStart = Integer.parseInt(hm.group(1));
                int oldCount = hm.group(2) == null ? 1 : Integer.parseInt(hm.group(2));
                int newStart = Integer.parseInt(hm.group(3));
                int newCount = hm.group(4) == null ? 1 : Integer.parseInt(hm.group(4));
                String heading = hm.group(5) == null ? "" : hm.group(5).trim();
                if (!inFile) {
                    currentOld = ""; currentNew = ""; inFile = true;
                }
                currentHunk = new HunkBuilder(oldStart, oldCount, newStart, newCount, heading);
                currentHunks.add(currentHunk);
                continue;
            }

            if (NO_NEWLINE.matcher(line).matches()) continue;

            if (currentHunk != null) {
                if (line.isEmpty()) {
                    currentHunk.append(' ', "", lineNo);
                    continue;
                }
                char kind = line.charAt(0);
                if (kind == ' ' || kind == '+' || kind == '-') {
                    String content = line.length() > 1 ? line.substring(1) : "";
                    currentHunk.append(kind, content, lineNo);
                    continue;
                }
                // Tolerate unknown lines inside a hunk (treat as context).
                currentHunk.append(' ', line, lineNo);
            }
        }

        if (inFile) flushFile(files, currentOld, currentNew, currentHunks);
        return files;
    }

    public static PatchFile parseSingle(String diff) {
        List<PatchFile> all = parse(diff);
        if (all.isEmpty()) throw new PatchParseException("empty diff", 0);
        return all.get(0);
    }

    private static void flushFile(List<PatchFile> files, String oldPath, String newPath, List<HunkBuilder> builders) {
        if (oldPath == null) oldPath = "";
        if (newPath == null) newPath = "";
        List<Hunk> hunks = new ArrayList<>();
        for (HunkBuilder b : builders) {
            if (!b.lines.isEmpty()) hunks.add(b.build());
        }
        files.add(new PatchFile(oldPath, newPath, hunks));
    }

    private static String stripPrefix(String path, String prefix) {
        return path.startsWith(prefix) ? path.substring(prefix.length()) : path;
    }

    /** hunk accumulator with line-number tracking. */
    private static final class HunkBuilder {
        final int oldStart;
        final int oldCount;
        final int newStart;
        final int newCount;
        final String heading;
        final List<DiffLine> lines = new ArrayList<>();
        int oldCursor;
        int newCursor;

        HunkBuilder(int oldStart, int oldCount, int newStart, int newCount, String heading) {
            this.oldStart = oldStart;
            this.oldCount = oldCount;
            this.newStart = newStart;
            this.newCount = newCount;
            this.heading = heading == null ? "" : heading;
            this.oldCursor = oldStart;
            this.newCursor = newStart;
        }

        void append(char kind, String content, int lineNo) {
            int oldLn = 0, newLn = 0;
            if (kind == ' ' || kind == '-') oldLn = oldCursor++;
            if (kind == ' ' || kind == '+') newLn = newCursor++;
            lines.add(new DiffLine(kind, content, oldLn, newLn));
        }

        Hunk build() {
            return new Hunk(oldStart, oldCount, newStart, newCount, lines, heading);
        }
    }
}
