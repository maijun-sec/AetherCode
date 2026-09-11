package org.aethercode.core.runtime;

import java.util.List;
import java.util.Map;

/**
 * A single block of content within a {@link Message}.
 *
 * <p>Java-native port of LangChain's {@code ContentBlock} union. The
 * shape is intentionally permissive: blocks are sealed types and the
 * model adapter picks the concrete subtype it knows how to render.</p>
 */
public sealed interface ContentBlock
        permits ContentBlock.TextBlock,
                ContentBlock.ToolUseBlock,
                ContentBlock.ToolResultBlock,
                ContentBlock.ImageBlock,
                ContentBlock.GenericContentBlock {

    /** Plain text content. */
    record TextBlock(String text) implements ContentBlock {
        public TextBlock {
            if (text == null) text = "";
        }
    }

    /**
     * Model-requested tool invocation. {@code id} is what the model uses
     * to correlate the eventual {@link ToolResultBlock} back to this call.
     */
    record ToolUseBlock(String id, String name, Map<String, Object> input) implements ContentBlock {
        public ToolUseBlock {
            if (id == null) id = "";
            if (name == null) name = "";
            input = input == null ? Map.of() : Map.copyOf(input);
        }
    }

    /**
     * Result returned to the model for a {@link ToolUseBlock}. The
     * {@code isError} flag distinguishes successful results from error
     * payloads (mirrors Anthropic's content-block convention).
     */
    record ToolResultBlock(String toolUseId, Object content, boolean isError) implements ContentBlock {
        public ToolResultBlock {
            if (toolUseId == null) toolUseId = "";
        }
    }

    /** Inline image; the model adapter serializes bytes per its provider's convention. */
    record ImageBlock(byte[] data, String mimeType) implements ContentBlock {
        public ImageBlock {
            if (mimeType == null) mimeType = "image/png";
            data = data == null ? new byte[0] : data.clone();
        }
    }

    /**
     * Generic, weakly-typed content block. Backed by a map mirroring
     * LangChain's {@code ContentBlock} dict-union so blocks that don't
     * map cleanly to a Java record &mdash; {@code file}, {@code audio},
     * {@code video}, {@code plain_text}, etc. &mdash; can still flow
     * through the message pipeline without a model-specific
     * subtype.
     *
     * <p>The {@code type} field (when present) is the discriminator
     * (e.g. {@code "file"}, {@code "audio"}, {@code "video"}); the
     * remainder is opaque payload. Mirrors the Python port's
     * {@code ContentBlock} TypedDict.
     */
    record GenericContentBlock(java.util.Map<String, Object> fields) implements ContentBlock {
        public GenericContentBlock {
            if (fields == null) {
                throw new NullPointerException("GenericContentBlock.fields must not be null");
            }
            fields = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(fields));
        }
        /** The block type discriminator, or {@code null} when absent. */
        public String type() {
            Object t = fields.get("type");
            return t instanceof String s ? s : null;
        }
        /** Convenience: get a field by key. */
        public Object get(String key) { return fields.get(key); }
        /** Convenience: build a generic block with one or more key/value pairs. */
        public static GenericContentBlock of(String type, Object... kv) {
            java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("type", type);
            for (int i = 0; i + 1 < kv.length; i += 2) {
                m.put(String.valueOf(kv[i]), kv[i + 1]);
            }
            return new GenericContentBlock(m);
        }
    }

    /** Convenience: build a text block. */
    static ContentBlock text(String text) {
        return new TextBlock(text);
    }

    /** Convenience: build a tool-use block. */
    static ContentBlock toolUse(String id, String name, Map<String, Object> input) {
        return new ToolUseBlock(id, name, input);
    }

    /** Convenience: build a tool-result block (success). */
    static ContentBlock toolResult(String toolUseId, Object content) {
        return new ToolResultBlock(toolUseId, content, false);
    }

    /** Convenience: build a tool-result block (error). */
    static ContentBlock toolError(String toolUseId, String errorMessage) {
        return new ToolResultBlock(toolUseId, errorMessage, true);
    }

    /** Convenience: build an image block. */
    static ContentBlock image(byte[] data, String mimeType) {
        return new ImageBlock(data, mimeType);
    }

    /**
     * Convenience: build a generic content block from a field map. The
     * map must contain a {@code "type"} discriminator. Mirrors the
     * Python port's {@code ContentBlock} dict-union.
     */
    static ContentBlock generic(java.util.Map<String, Object> fields) {
        return new GenericContentBlock(fields);
    }

    /**
     * Convenience: build a generic {@code file} content block (the
     * block type {@code read_file} emits for PDF/DOCX/etc).
     */
    static ContentBlock file(String base64, String mimeType) {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("type", "file");
        if (base64 != null) m.put("base64", base64);
        if (mimeType != null) m.put("mime_type", mimeType);
        return new GenericContentBlock(m);
    }

    /**
     * Convenience: build a generic content block with a custom
     * {@code type} and free-form key/value payload.
     */
    static ContentBlock genericOf(String type, Object... kv) {
        return GenericContentBlock.of(type, kv);
    }

    /** Flatten the text content of a message to a single string. */
    static String flattenText(List<ContentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : blocks) {
            if (b instanceof TextBlock t) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(t.text());
            }
        }
        return sb.toString();
    }
}
