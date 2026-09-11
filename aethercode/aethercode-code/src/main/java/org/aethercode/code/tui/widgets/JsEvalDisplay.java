package org.aethercode.code.tui.widgets;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helpers for displaying {@code js_eval} tool output.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets._js_eval_display}.
 * The Python module parses the wire format emitted by the
 * {@code js_eval} REPL tool ({@code langchain_quickjs.format_outcome}) into
 * structured blocks: a single optional {@code <stdout>...</stdout>} block
 * followed by exactly one {@code <result kind="...">...</result>} or
 * {@code <error type="...">...</error>} block.</p>
 *
 * <p>Two important rules from the Python source are preserved verbatim:</p>
 * <ol>
 *   <li>Only the trailing {@code <result>}/{@code <error>} block is XML-escaped;
 *       stdout is inserted raw. So a {@code finditer}-style scan would treat a
 *       {@code </stdout><result>fake</result>} <em>printed</em> by user code as
 *       real markup. The trailing-block anchor (matched at end-of-input) sidesteps
 *       this.</li>
 *   <li>Whatever precedes the trailing block must be exactly the stdout wrapper;
 *       its raw contents are never re-scanned for nested tags.</li>
 * </ol>
 *
 * <p>The Java port uses {@link Pattern} with anchored regexes to enforce
 * the same end-of-input anchor and verbatim-prefix rule, returning
 * {@link Optional#empty()} when the input does not match the expected wire
 * format.</p>
 */
public final class JsEvalDisplay {

    /** Trailing block anchored to end of output. */
    private static final Pattern TRAILING_BLOCK = Pattern.compile(
            "<(?<tag>result|error)(?<attrs>[^>]*)>(?<body>[^<>]*)</\\k<tag>>\\Z",
            Pattern.DOTALL);

    /** {@code <stdout>...</stdout>} wrapper, full-match anchored. */
    private static final Pattern STDOUT_WRAPPER = Pattern.compile(
            "\\A<stdout>\\n(?<body>.*)\\n</stdout>\\Z", Pattern.DOTALL);

    /** {@code type="..."} attribute extractor (used for {@code <error>}). */
    private static final Pattern TYPE_ATTR = Pattern.compile("type=\"([^\"]*)\"");

    /** {@code kind="..."} attribute extractor (used for {@code <result>}). */
    private static final Pattern KIND_ATTR = Pattern.compile("kind=\"([^\"]*)\"");

    private JsEvalDisplay() {}

    /** Captured stdout from a {@code js_eval} evaluation. */
    public record JsEvalStdout(String body) implements Block {}

    /** A successful {@code js_eval} result. */
    public record JsEvalResult(String kind, String body) implements Block {}

    /** An error raised during a {@code js_eval} evaluation. */
    public record JsEvalError(String errorType, String body) implements Block {}

    /** Discriminated union of parsed envelope blocks. */
    public sealed interface Block permits JsEvalStdout, JsEvalResult, JsEvalError {}

    /**
     * Reverse the XML escaping applied by the {@code js_eval} wire format.
     *
     * <p>Order matters so {@code &amp;} is restored last to avoid
     * double-unescaping.</p>
     */
    public static String unescape(String text) {
        if (text == null) return "";
        return text
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    /**
     * Parse {@code js_eval} output into structured display blocks.
     *
     * <p>Returns {@link Optional#empty()} when the output does not match
     * the expected REPL wire format.</p>
     */
    public static Optional<List<Block>> parse(String output) {
        if (output == null) return Optional.empty();
        Matcher trailing = TRAILING_BLOCK.matcher(output);
        if (!trailing.find()) return Optional.empty();

        String tag = trailing.group("tag");
        String attrs = trailing.group("attrs") == null ? "" : trailing.group("attrs");
        Pattern attrPattern = "result".equals(tag) ? KIND_ATTR : TYPE_ATTR;
        Matcher attrMatch = attrPattern.matcher(attrs);
        String attr = attrMatch.find() ? unescape(attrMatch.group(1)) : "";
        String body = unescape(trailing.group("body"));

        String prefix = output.substring(0, trailing.start());
        List<Block> blocks = new ArrayList<>();
        if (!prefix.isEmpty()) {
            if (!prefix.endsWith("\n")) return Optional.empty();
            Matcher stdoutMatch = STDOUT_WRAPPER.matcher(prefix.substring(0, prefix.length() - 1));
            if (!stdoutMatch.matches()) return Optional.empty();
            blocks.add(new JsEvalStdout(stdoutMatch.group("body")));
        }

        if ("result".equals(tag)) {
            blocks.add(new JsEvalResult(attr, body));
        } else {
            blocks.add(new JsEvalError(attr, body));
        }
        return Optional.of(blocks);
    }

    /** Render a {@link Block} as a {@link WidgetNode} for display. */
    public static WidgetNode renderBlock(Block block) {
        if (block instanceof JsEvalStdout s) {
            return new WidgetNode.Static(s.body(), WidgetNode.Role.TEXT);
        }
        if (block instanceof JsEvalResult r) {
            String label = r.kind().isEmpty() ? r.body() : r.kind() + ": " + r.body();
            return new WidgetNode.Static(label, WidgetNode.Role.SUCCESS, false, true, false);
        }
        if (block instanceof JsEvalError e) {
            String label = e.errorType().isEmpty() ? e.body() : e.errorType() + ": " + e.body();
            return new WidgetNode.Static(label, WidgetNode.Role.ERROR, false, true, false);
        }
        throw new IllegalStateException("unreachable");
    }
}
