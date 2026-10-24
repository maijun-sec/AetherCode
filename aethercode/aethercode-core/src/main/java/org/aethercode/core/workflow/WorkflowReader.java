package org.aethercode.core.workflow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * minimal YAML reader for workflow files.
 *
 * <p>We deliberately avoid pulling in SnakeYAML or jackson-dataformat-yaml —
 * the workflow files are user-authored and small (under 5KB typical), and
 * the desktop only needs four fields for the picker:
 *
 * <ul>
 *   <li>{@code name} (top-level)</li>
 *   <li>{@code description} (top-level)</li>
 *   <li>{@code inputs[]} — declared input keys (so the picker can show
 *       "needs: project_path, notify" hints)</li>
 *   <li>{@code steps[].{id, type}} — for the step-progress bar</li>
 * </ul>
 *
 * <p>The reader is regex-based and intentionally forgiving. It does not
 * validate the full {@code workflow-system} skill schema — that's the
 * executor's job (the agent that loads the skill and walks the YAML). It
 * only extracts the display metadata the desktop needs.
 */
public final class WorkflowReader {

    private WorkflowReader() {}

    /** Strip the {@code .yaml} / {@code .yml} extension from a filename. */
    public static String stripExt(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        if (dot < 0) return filename;
        return filename.substring(0, dot);
    }

    /** Read a single workflow file. Returns the raw text + parsed metadata. */
    public static WorkflowDoc read(Path file) throws IOException {
        String raw = Files.readString(file);
        return parse(raw, stripExt(file.getFileName().toString()));
    }

    /**
     * One workflow file plus its parsed metadata.
     *
     * <p><b>R373:</b> {@code fileName} is the workflow's ADDRESSABLE id
     * — the file name WITH its extension ({@code deploy.yaml}). It is
     * not the same as {@link WorkflowDoc#name()}, which is the
     * {@code name:} the YAML declares (falling back to the file stem
     * when it declares none).
     *
     * <p>The two can differ, and the difference is load-bearing:
     * {@link WorkflowPaths#workflowFile(Path, String)} resolves a FILE,
     * so a file called {@code deploy.yaml} that declares
     * {@code name: ship-it} is LISTED as "ship-it" but cannot be
     * STARTED under that name. Publishing the file name is what lets a
     * client address the thing it was shown.
     *
     * <p>The extension is part of the id on purpose. It is what keeps
     * a directory holding BOTH {@code foo.yaml} and {@code foo.yml}
     * unambiguous — with a bare stem, both rows would publish the id
     * "foo" and picking the second would silently launch the first.
     */
    public record WorkflowEntry(String fileName, WorkflowDoc doc) {}

    /**
     * The result of scanning the workflow directory.
     *
     * <p>{@code skipped} names the YAML files that could not be read.
     * {@link #list(Path)} dropped them silently, which made "there are
     * no workflows here" and "every workflow here is unreadable" the
     * SAME answer. R373 publishes the names so a picker can tell the
     * two apart instead of showing an empty list that reads like a
     * deliberate choice.
     */
    public record WorkflowListing(List<WorkflowEntry> entries, List<String> skipped) {}

    /**
     * Reads one workflow file. Separated out so the scan's error path
     * is testable on every platform.
     *
     * <p>The obvious way to make a file unreadable is to lock it, but
     * file locks are ADVISORY on Linux and MANDATORY on Windows — so a
     * lock-based test would silently skip itself on half the CI
     * matrix and the "we skipped a file" branch would never be
     * exercised anywhere but a developer's Windows box. Injecting the
     * reader keeps the branch reachable from a unit test everywhere.
     */
    @FunctionalInterface
    interface DocReader {
        WorkflowDoc read(Path file) throws IOException;
    }

    /**
     * Scan {@link WorkflowPaths#workflowDir(Path)} for workflow files.
     *
     * <p>Returns the parsed documents in file-name order together with
     * the file stem of each, and the names of the files that could not
     * be read. A missing directory is an EMPTY listing, not an error —
     * most projects have no workflows at all.
     */
    public static WorkflowListing scan(Path cwd) {
        return scan(cwd, WorkflowReader::read);
    }

    /** Package-private seam: see {@link DocReader}. */
    static WorkflowListing scan(Path cwd, DocReader reader) {
        Path dir = WorkflowPaths.workflowDir(cwd);
        List<WorkflowEntry> out = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        if (!Files.isDirectory(dir)) return new WorkflowListing(out, skipped);
        try (var stream = Files.list(dir)) {
            // Sort by file name so the picker is stable across calls.
            stream.filter((p) -> Files.isRegularFile(p))
                  .filter((p) -> {
                      String n = p.getFileName().toString().toLowerCase();
                      return n.endsWith(".yaml") || n.endsWith(".yml");
                  })
                  .sorted((a, b) -> a.getFileName().toString()
                                     .compareTo(b.getFileName().toString()))
                  .forEach((p) -> {
                      try {
                          out.add(new WorkflowEntry(
                                  p.getFileName().toString(),
                                  reader.read(p)));
                      } catch (IOException e) {
                          // Unreadable: bad encoding, a lock, a
                          // half-written file. R373 reports it
                          // instead of dropping it — a directory full
                          // of files the daemon cannot read is NOT the
                          // same as an empty directory, and the picker
                          // has to be able to say so.
                          skipped.add(p.getFileName().toString());
                      }
                  });
        } catch (IOException e) {
            // Directory inaccessible — return whatever we have so far.
        }
        return new WorkflowListing(out, skipped);
    }

    /** List workflows in {@link WorkflowPaths#workflowDir(Path)} as
     *  ordered {@link WorkflowDoc}s (sorted by name). Unreadable files
     *  are dropped — use {@link #scan(Path)} when the caller needs to
     *  know that they existed. */
    public static List<WorkflowDoc> list(Path cwd) {
        List<WorkflowDoc> out = new ArrayList<>();
        for (WorkflowEntry e : scan(cwd).entries()) out.add(e.doc());
        return out;
    }

    // ---- parsing ---------------------------------------------------------

    // Greedy `+` (not non-greedy `+?`) for top-level scalars —
    // the class already excludes the stop chars (`'`, `"`,
    // newline, `#`), so greedy is correct and non-greedy
    // would only match one character. The trailing `\\s*(?:#.*)?$`
    // anchor lets an inline comment terminate the match.
    private static final Pattern TOP_NAME =
            Pattern.compile("(?m)^\\s*name\\s*:\\s*['\"]?([^'\"\\n#]+)['\"]?\\s*(?:#.*)?$");
    private static final Pattern TOP_DESCRIPTION =
            Pattern.compile("(?m)^\\s*description\\s*:\\s*['\"]?([^'\"\\n#]+)['\"]?\\s*(?:#.*)?$");
    private static final Pattern TOP_INPUTS_BLOCK =
            Pattern.compile("(?ms)^\\s*inputs\\s*:\\s*\\n(.*?)(?=^\\S|\\z)");
    // Inputs are nested: the first level is the input name, the
    // second is `type` / `default` / `required`. We only want
    // the first level — exactly two spaces of indent (or a
    // tab, normalised to spaces). Anything deeper is metadata.
    private static final Pattern INPUT_KEY =
            Pattern.compile("(?m)^  ([A-Za-z_][A-Za-z0-9_-]*)\\s*:");
    private static final Pattern STEPS_BLOCK =
            Pattern.compile("(?ms)^\\s*steps\\s*:\\s*\\n(.*?)(?=^\\S|\\z)");
    // top-level step entries are indented by exactly 2
    // spaces (`  - id: name`). Nested entries (under a `parallel`
    // or `gate` step) start at 4+ spaces and are NOT picked up
    // here — the executor parses the nested list itself.
    private static final Pattern STEP_ID_LINE =
            Pattern.compile("(?m)^  -\\s*id\\s*:\\s*['\"]?([^'\"\\n#]+)['\"]?\\s*(?:#.*)?$");
    // Step `type:` may carry a quoted value, a non-quoted
    // scalar, or a single bare token. Match the whole token
    // (greedy, class excludes stop chars) so `shell` /
    // `parallel` / `gate` all parse.
    private static final Pattern STEP_TYPE_LINE =
            Pattern.compile("(?m)^\\s*type\\s*:\\s*['\"]?([^'\"\\n#]+)['\"]?\\s*(?:#.*)?$");
    // per-step `continue_on_error: true` flag. When true, an
    // exception in the step is downgraded to a "skipped" status
    // and the workflow keeps advancing. Default false (any error
    // aborts the remaining steps). Accepts `true` / `false` /
    // `yes` / `no` (case-insensitive); anything else falls back
    // to false. The trailing `\\s*(?:#.*)?$` anchor lets an
    // inline comment terminate the match.
    private static final Pattern STEP_CONTINUE_ON_ERROR =
            Pattern.compile("(?im)^\\s*continue_on_error\\s*:\\s*(true|yes|1|false|no|0)\\b[^\\n#]*(?:#.*)?$");

    /** Parse a workflow YAML body. {@code fallbackName} is used when
     *  the file does not declare {@code name} at the top. */
    public static WorkflowDoc parse(String raw, String fallbackName) {
        String name = match(TOP_NAME, raw, 1, fallbackName).trim();
        String description = match(TOP_DESCRIPTION, raw, 1, "").trim();

        List<String> inputs = new ArrayList<>();
        String inputsBlock = match(TOP_INPUTS_BLOCK, raw, 1, null);
        if (inputsBlock != null) {
            Matcher m = INPUT_KEY.matcher(inputsBlock);
            while (m.find()) inputs.add(m.group(1));
        }

        List<Step> steps = new ArrayList<>();
        String stepsBlock = match(STEPS_BLOCK, raw, 1, null);
        if (stepsBlock != null) {
            // the top-level step list lives directly under
            // `steps:` and is indented by exactly 2 spaces. Nested
            // `- id:` entries (e.g. inside a `parallel` step's
            // `branches:[]` or a `gate` step's `then:` body) are
            // indented by 4+ spaces and must NOT be counted as
            // top-level steps. We split on `^  - id:` (with a
            // literal two-space indent) so the chunks only
            // contain top-level entries. Nested parsing is the
            // executor's job (it re-reads the chunk for the
            // nested step list).
            String[] chunks = stepsBlock.split("(?m)^  -\\s+id\\s*:");
            for (int i = 1; i < chunks.length; i++) {
                String chunk = chunks[i];
                String id = match(STEP_ID_LINE, "  - id:" + chunk, 1, "").trim();
                if (id.isEmpty()) continue;
                String type = match(STEP_TYPE_LINE, chunk, 1, "unknown").trim();
                // per-step `continue_on_error` flag. We pull
                // the FIRST occurrence only — the regex is
                // case-insensitive and matches a single token.
                // Anything not parseable defaults to false.
                boolean continueOnError = parseContinueOnError(chunk);
                steps.add(new Step(id, type, continueOnError));
            }
        }

        return new WorkflowDoc(name, description, inputs, steps, raw);
    }

    private static String match(Pattern p, String body, int group, String fallback) {
        Matcher m = p.matcher(body);
        return m.find() ? m.group(group) : fallback;
    }

    /** Parsed workflow metadata. The raw content is preserved so the
     *  desktop can stream it to the engine for execution. */
    public record WorkflowDoc(
            String name,
            String description,
            List<String> inputs,
            List<Step> steps,
            String raw
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("description", description);
            m.put("inputs", inputs);
            m.put("steps", steps.stream().map(Step::toMap).toList());
            m.put("stepCount", steps.size());
            return m;
        }
    }

    /** a single step declaration. R105 added
     *  {@code continueOnError}: when true, the executor treats a
     *  thrown exception from this step as a "skipped" status
     *  instead of bubbling it up to abort the workflow. */
    public record Step(String id, String type, boolean continueOnError) {
        /** Backwards-compatible factory used by the executor's
         *  branch-parser (parallel/gate sub-steps don't have
         *  per-step metadata in their nested chunks). */
        public Step(String id, String type) { this(id, type, false); }
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("type", type);
            m.put("continueOnError", continueOnError);
            return m;
        }
    }

    /** parse a step chunk's {@code continue_on_error} flag.
     *  Accepts true/yes/1 (case-insensitive) as true; everything
     *  else is false. Returns false when the field is missing. */
    private static boolean parseContinueOnError(String chunk) {
        if (chunk == null) return false;
        Matcher m = STEP_CONTINUE_ON_ERROR.matcher(chunk);
        if (!m.find()) return false;
        String v = m.group(1);
        return "true".equalsIgnoreCase(v) || "yes".equalsIgnoreCase(v) || "1".equals(v);
    }
}
