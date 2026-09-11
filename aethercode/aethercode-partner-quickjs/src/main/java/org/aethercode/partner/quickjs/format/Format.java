package org.aethercode.partner.quickjs.format;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aethercode.langchain_compat.langgraph.Command;
import org.aethercode.partner.quickjs.js.JsMarshalException;
import org.aethercode.partner.quickjs.js.JsUndefined;
import org.aethercode.partner.quickjs.repl.EvalOutcome;
import org.aethercode.core.runtime.ContentBlock;
import org.aethercode.core.runtime.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Formatting and output-coercion helpers for the QuickJS REPL.
 *
 * <p>1:1 port of the Python
 * <code>langchain_quickjs._format</code> module. Centralises the
 * JS-value &rarr; Java string coercion, the PTC bridge marshaling
 * shape, and the {@link EvalOutcome} &rarr; tool-output wire format.</p>
 */
public final class Format {

    private static final Logger LOGGER = LoggerFactory.getLogger(Format.class);

    /** Marker used to flag dropped characters during truncation. */
    public static final String TRUNCATE_MARKER_FMT = "\u2026 [truncated %d chars]";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Format() {}

    // -----------------------------------------------------------------
    //  JS-value formatting
    // -----------------------------------------------------------------

    /**
     * Describe a {@code Handle} value in REPL-style shorthand. Mirrors
     * the Python <code>format_handle</code>: <code>[Function]</code>
     * for callables, <code>[Function] arity=N</code> when arity is
     * readable, and <code>[&lt;type&gt;]</code> for everything else.
     */
    public static String formatHandle(String typeOf, Integer arity) {
        if ("function".equals(typeOf)) {
            return arity == null ? "[Function]" : "[Function] arity=" + arity;
        }
        return "[" + typeOf + "]";
    }

    /** Best-effort string form for a console arg or eval result. */
    public static String stringify(Object value) {
        return formatJsValue(value);
    }

    static String formatJsValue(Object value) {
        if (value == null) return "null";
        if (JsUndefined.isUndefined(value)) return "undefined";
        if (value instanceof Boolean b) return b ? "true" : "false";
        if (value instanceof Double d) {
            if (d.isInfinite() == false && d.isNaN() == false && d.doubleValue() == Math.floor(d.doubleValue())
                    && !Double.isInfinite(d) && d.longValue() >= Long.MIN_VALUE && d.longValue() <= Long.MAX_VALUE) {
                return Long.toString(d.longValue());
            }
            return d.toString();
        }
        if (value instanceof Float f) {
            if (f.floatValue() == Math.floor(f.floatValue())
                    && !Float.isInfinite(f) && !Float.isNaN(f)
                    && f.longValue() >= Long.MIN_VALUE && f.longValue() <= Long.MAX_VALUE) {
                return Long.toString(f.longValue());
            }
            return f.toString();
        }
        if (value instanceof Number n) return n.toString();
        if (value instanceof String s) return s;
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(formatNested(list.get(i)));
            }
            return sb.append("]").toString();
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(entry.getKey()).append(": ").append(formatNested(entry.getValue()));
            }
            return sb.append("}").toString();
        }
        return value.toString();
    }

    /** Like {@link #formatJsValue} but quotes nested strings. */
    static String formatNested(Object value) {
        if (value instanceof String s) return "\"" + s + "\"";
        return formatJsValue(value);
    }

    // -----------------------------------------------------------------
    //  Tool output coercion (string flavor)
    // -----------------------------------------------------------------

    /**
     * Coerce arbitrary tool return values to the JS-visible string
     * output. Mirrors the Python <code>coerce_tool_output</code>
     * selection rules: <code>String</code> &rarr; as-is;
     * {@link Command} &rarr; trailing message content;
     * {@link Message.ToolMessage} &rarr; tool message content;
     * <code>List</code> &rarr; scan in reverse for a ToolMessage or
     * Command and unwrap; otherwise &rarr; JSON-encode the value.
     */
    @SuppressWarnings("rawtypes")
    public static String coerceToolOutput(Object value) {
        if (value instanceof String s) return s;
        if (value instanceof Command cmd) return coerceCommandOutput(cmd);
        if (value instanceof Message.ToolMessage tm) return coerceToolMessageOutput(tm);
        if (value instanceof List<?> list) {
            for (int i = list.size() - 1; i >= 0; i--) {
                Object entry = list.get(i);
                if (entry instanceof Message.ToolMessage tm) return coerceToolMessageOutput(tm);
                if (entry instanceof Command cmd) return coerceCommandOutput(cmd);
            }
        }
        return coerceMessageContent(value);
    }

    static String coerceMessageContent(Object content) {
        if (content instanceof String s) return s;
        try {
            return MAPPER.writeValueAsString(content);
        } catch (JsonProcessingException e) {
            LOGGER.debug("Failed to JSON-encode tool output; falling back to toString()", e);
            return String.valueOf(content);
        } catch (RuntimeException e) {
            return String.valueOf(content);
        }
    }

    static String coerceToolMessageOutput(Message.ToolMessage message) {
        // Match the Python port: unwrap the first TextBlock from a
        // content-block list, otherwise fall back to JSON-encoding.
        String text = firstText(message);
        if (text != null) return text;
        return coerceMessageContent(message.content());
    }

    static String coerceCommandOutput(Command command) {
        Object update = command.update();
        if (update instanceof Map<?, ?> m) {
            Object messages = m.get("messages");
            if (messages instanceof List<?> list) {
                for (int i = list.size() - 1; i >= 0; i--) {
                    Object entry = list.get(i);
                    Object content = readContent(entry);
                    if (content != null) return coerceMessageContent(content);
                }
            }
        }
        return String.valueOf(update);
    }

    private static Object readContent(Object entry) {
        if (entry instanceof Message m) {
            // Collect the text content of the first TextBlock we find.
            for (ContentBlock block : m.content()) {
                if (block instanceof ContentBlock.TextBlock tb) {
                    return tb.text();
                }
            }
        }
        return null;
    }

    // -----------------------------------------------------------------
    //  Tool output coercion (typed flavor — PTC bridge)
    // -----------------------------------------------------------------

    /**
     * Scalar types the QuickJS bridge marshals natively. Compound
     * shapes (Map / List) are walked recursively in
     * {@link #coerceForMarshal}; anything else becomes
     * {@code value.toString()} so the JS side can still see a usable
     * value.
     */
    private static final Class<?>[] NATIVE_JS_SCALARS = {
            String.class, Boolean.class, Integer.class, Long.class, Short.class, Byte.class,
            Double.class, Float.class, java.util.concurrent.atomic.AtomicInteger.class,
            java.util.concurrent.atomic.AtomicLong.class
    };

    private static boolean isNativeJsScalar(Object value) {
        for (Class<?> c : NATIVE_JS_SCALARS) {
            if (c.isInstance(value)) return true;
        }
        return value == null;
    }

    /**
     * Coerce a tool result for the PTC bridge, preserving native
     * types. The QuickJS bridge marshals Java primitives, {@code Map},
     * and {@code List} directly to native JS values, so the model can
     * use them without an explicit {@code JSON.parse}. This helper
     * unwraps the {@link Command} / {@link Message.ToolMessage}
     * envelopes (matching {@link #coerceToolOutput}'s selection rules)
     * and returns the underlying value typed.
     */
    public static Object coerceToolOutputForPtc(Object value) {
        if (value instanceof Command cmd) return coerceToolOutputForPtc(extractCommandContent(cmd));
        if (value instanceof Message.ToolMessage tm) return coerceToolOutputForPtc(tm.content());
        if (value instanceof List<?> list) {
            for (int i = list.size() - 1; i >= 0; i--) {
                Object entry = list.get(i);
                if (entry instanceof Message.ToolMessage tm) return coerceToolOutputForPtc(tm.content());
                if (entry instanceof Command cmd) return coerceToolOutputForPtc(extractCommandContent(cmd));
            }
        }
        return coerceForMarshal(value);
    }

    /** Convert {@code value} into a shape the QuickJS bridge can marshal. */
    public static Object coerceForMarshal(Object value) {
        if (isNativeJsScalar(value)) return value;
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), coerceForMarshal(entry.getValue()));
            }
            return out;
        }
        if (value instanceof Iterable<?> iterable) {
            java.util.List<Object> out = new java.util.ArrayList<>();
            for (Object item : iterable) {
                out.add(coerceForMarshal(item));
            }
            return out;
        }
        if (value instanceof JsMarshalException) return value.toString();
        return value.toString();
    }

    /** Return the trailing message content from a {@link Command} update, if any. */
    public static Object extractCommandContent(Command command) {
        Object update = command.update();
        if (update instanceof Map<?, ?> m) {
            Object messages = m.get("messages");
            if (messages instanceof List<?> list) {
                for (int i = list.size() - 1; i >= 0; i--) {
                    Object entry = list.get(i);
                    Object content = readContent(entry);
                    if (content != null) return content;
                }
            }
        }
        return update;
    }

    // -----------------------------------------------------------------
    //  Outcome formatter
    // -----------------------------------------------------------------

    /**
     * Render an {@link EvalOutcome}-like object as the tool's wire
     * format. Mirrors the Python <code>format_outcome</code>:
     * <ul>
     *   <li>{@code <stdout>...</stdout>} block when console output or
     *       truncation occurred;</li>
     *   <li>{@code <error type="...">...</error>} block on JS error;
     *   <li>{@code <result kind="...">...</result>} block on success
     *       (kind omitted when the result came from a plain primitive
     *       conversion).</li>
     * </ul>
     */
    public static String formatOutcome(EvalOutcome outcome, int maxResultChars) {
        StringBuilder out = new StringBuilder();
        if (outcome.stdout() != null && !outcome.stdout().isEmpty()
                || outcome.stdoutTruncatedChars() > 0) {
            String stdout = outcome.stdout() == null ? "" : outcome.stdout();
            if (outcome.stdoutTruncatedChars() > 0) {
                stdout = truncate(stdout, maxResultChars, outcome.stdoutTruncatedChars());
            }
            out.append("<stdout>\n").append(stdout).append("\n</stdout>\n");
        }
        if (outcome.errorType() != null) {
            String inner = outcome.errorMessage() == null ? "" : outcome.errorMessage();
            if (outcome.errorStack() != null && !outcome.errorStack().isEmpty()) {
                inner = inner + "\n" + outcome.errorStack();
            }
            out.append("<error type=\"")
                    .append(xmlEscape(outcome.errorType()))
                    .append("\">")
                    .append(xmlEscape(truncate(inner, maxResultChars)))
                    .append("</error>\n");
        } else {
            String body = outcome.result() == null ? "undefined" : outcome.result();
            String kindAttr = outcome.resultKind() == null || outcome.resultKind().isEmpty()
                    ? "" : " kind=\"" + outcome.resultKind() + "\"";
            out.append("<result").append(kindAttr).append(">")
                    .append(xmlEscape(truncate(body, maxResultChars)))
                    .append("</result>\n");
        }
        String s = out.toString();
        // Match Python's "\n".join(parts) — strip the trailing newline.
        if (s.endsWith("\n")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /**
     * Truncate {@code text} to {@code limit} characters. If
     * {@code dropped} is provided (stdout path) it is folded into the
     * truncate marker; otherwise (result / error path) the marker is
     * built from {@code len(text) - keep}.
     */
    public static String truncate(String text, int limit) {
        return truncate(text, limit, null);
    }

    public static String truncate(String text, int limit, Integer dropped) {
        if (text == null) return "";
        if (dropped != null) {
            String marker = String.format(TRUNCATE_MARKER_FMT, dropped);
            if (marker.length() >= limit) {
                return marker.substring(0, Math.max(0, limit));
            }
            int keep = Math.max(0, limit - marker.length());
            return text.substring(0, Math.min(keep, text.length())) + marker;
        }
        if (text.length() <= limit) return text;
        String marker = String.format(TRUNCATE_MARKER_FMT, 0);
        int keep = Math.max(0, limit - marker.length());
        int droppedCount = text.length() - keep;
        return text.substring(0, keep) + String.format(TRUNCATE_MARKER_FMT, droppedCount);
    }

    /** XML-escape a string for embedding inside an attribute or text node. */
    public static String xmlEscape(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Pull the text content out of the first {@link ContentBlock.TextBlock}
     * of a message, or {@code null} if none. Convenience for PTC + format
     * sites.
     */
    public static String firstText(Message message) {
        for (ContentBlock block : message.content()) {
            if (block instanceof ContentBlock.TextBlock tb) return tb.text();
        }
        return null;
    }
}
