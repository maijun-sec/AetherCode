package org.aethercode.core.middleware;

import org.aethercode.core.runtime.ContentBlock;
import org.aethercode.core.runtime.Message;
import org.aethercode.core.runtime.llm.ModelProfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.aethercode.core.runtime.Message.AIMessage;
import org.aethercode.core.runtime.Message.HumanMessage;
import org.aethercode.core.runtime.Message.ToolMessage;
import org.aethercode.core.runtime.Message.SystemMessage;
import org.aethercode.core.runtime.Message.RemoveMessage;

/**
 * Java-native port of {@code deepagents.middleware.filesystem._scrub_unsupported_multimodal_content}.
 *
 * <p>Replaces multimodal {@link ContentBlock.GenericContentBlock} entries
 * (file / image / audio / video blocks) the resolved
 * {@link ModelProfile} marks as unsupported with a text placeholder,
 * avoiding non-retryable provider errors (e.g. Anthropic 400 on
 * non-PDF {@code file} blocks for providers without DOCX support).</p>
 *
 * <p>Behavior parity with the Python port:</p>
 * <ul>
 *   <li>A {@code null} {@code modelProfile} or one without a
 *       {@code fields} map is treated as an empty profile; every
 *       block type defaults to supported.</li>
 *   <li>Missing profile fields default to "supported" (only an
 *       explicit {@code Boolean.FALSE} rejects a block type).</li>
 *   <li>URL-/file-ID-backed {@code file} references &mdash; those
 *       without a {@code "base64"} field &mdash; are passed
 *       through untouched (provider-managed references often
 *       don't carry a {@code mime_type}).</li>
 *   <li>Non-PDF base64 {@code file} blocks (DOCX, PPTX, ...)
 *       are passed only when
 *       {@link #toleratesNonPdfFiles} returns {@code true}.</li>
 *   <li>{@code ToolMessage}-only block types (image, file) honor
 *       the additional {@code *_tool_message} profile field.</li>
 *   <li>Only {@link HumanMessage} and
 *       {@link ToolMessage} messages are scrubbed;
 *       {@link AIMessage},
 *       {@link SystemMessage},
 *       {@link RemoveMessage} pass through unchanged.</li>
 * </ul>
 */
public final class MultimodalContentScrubber {

    /** Block type discriminator for image content blocks. */
    public static final String BLOCK_TYPE_IMAGE = "image";
    /** Block type discriminator for audio content blocks. */
    public static final String BLOCK_TYPE_AUDIO = "audio";
    /** Block type discriminator for video content blocks. */
    public static final String BLOCK_TYPE_VIDEO = "video";
    /** Block type discriminator for file content blocks. */
    public static final String BLOCK_TYPE_FILE = "file";

    /**
     * Block types {@code read_file} may emit that require multimodal
     * model support. Mirrors the Python port's
     * {@code _MULTIMODAL_BLOCK_TYPES} (derived from
     * {@code _EXTENSION_TO_FILE_TYPE.values()}).
     */
    public static final Set<String> MULTIMODAL_BLOCK_TYPES = Set.of(
            BLOCK_TYPE_IMAGE, BLOCK_TYPE_AUDIO, BLOCK_TYPE_VIDEO, BLOCK_TYPE_FILE);

    /** Mime type the {@code file} block must carry to be treated as PDF. */
    public static final String PDF_MIME_TYPE = "application/pdf";

    /**
     * Whether the model is a known-tolerant provider (OpenAI /
     * Azure / Google) for non-PDF {@code file} blocks. Mirrors
     * the Python port's
     * {@code _model_tolerates_non_pdf_files} check on
     * {@code _OPENAI_FILE_MODEL_TYPES + _GOOGLE_FILE_MODEL_TYPES}.
     *
     * <p>Java port uses a string-based match on the model's
     * provider tag (e.g. {@code "openai"}, {@code "azure-openai"},
     * {@code "google"}), which is the same effective check when
     * the {@code ModelProfile} is unknown. When the caller already
     * has a profile or class check, supply an explicit result via
     * the {@code toleratesNonPdfFiles} parameter.</p>
     */
    public static final Set<String> NON_PDF_FILE_TOLERANT_PROVIDERS = Set.of(
            "openai", "azure-openai", "azure", "google", "google-ai", "google-gla");

    private MultimodalContentScrubber() {}

    /**
     * Whether {@code modelProvider} (a lower-cased provider tag,
     * e.g. {@code "openai"}, {@code "anthropic"}) is a known
     * tolerant provider for non-PDF base64 {@code file} blocks.
     * Returns {@code false} for {@code null} or unknown providers.
     */
    public static boolean toleratesNonPdfFiles(String modelProvider) {
        if (modelProvider == null) return false;
        return NON_PDF_FILE_TOLERANT_PROVIDERS.contains(modelProvider.toLowerCase());
    }

    /**
     * Replace unsupported multimodal content blocks across {@code messages}.
     *
     * <p>Mirrors the Python port's
     * {@code _scrub_unsupported_multimodal_content}. Messages that
     * don't need scrubbing are returned unchanged (identity, not a
     * copy).</p>
     *
     * @param messages the messages to scrub
     * @param profile the model profile (may be {@code null} for empty)
     * @param toleratesNonPdfFiles whether the model is a known tolerant
     *                              provider for non-PDF {@code file} blocks
     * @return a new list of scrubbed messages, or the input list when
     *         nothing changed
     */
    public static List<Message> scrub(
            List<Message> messages,
            ModelProfile profile,
            boolean toleratesNonPdfFiles) {
        if (messages == null || messages.isEmpty()) return messages;
        ModelProfile effectiveProfile = profile == null ? ModelProfile.empty() : profile;
        List<Message> result = new ArrayList<>(messages.size());
        boolean changed = false;
        for (Message m : messages) {
            Message scrubbed = scrubMessage(m, effectiveProfile, toleratesNonPdfFiles);
            result.add(scrubbed);
            if (scrubbed != m) changed = true;
        }
        return changed ? Collections.unmodifiableList(result) : messages;
    }

    /**
     * Variant of {@link #scrub(List, ModelProfile, boolean)} that
     * consults {@link #toleratesNonPdfFiles(String)} with the
     * provided model provider tag.
     */
    public static List<Message> scrub(
            List<Message> messages,
            ModelProfile profile,
            String modelProvider) {
        return scrub(messages, profile, toleratesNonPdfFiles(modelProvider));
    }

    /**
     * Replace unsupported multimodal blocks in a single message.
     * Returns the input when nothing changed.
     */
    static Message scrubMessage(
            Message message,
            ModelProfile profile,
            boolean toleratesNonPdfFiles) {
        if (!(message instanceof HumanMessage
                || message instanceof ToolMessage)) {
            return message;
        }
        List<ContentBlock> blocks = message.content();
        if (blocks == null || blocks.isEmpty()) return message;
        List<ContentBlock> newBlocks = new ArrayList<>(blocks.size());
        boolean changed = false;
        boolean inToolMessage = message instanceof ToolMessage;
        for (ContentBlock b : blocks) {
            ContentBlock next = scrubBlock(b, profile, toleratesNonPdfFiles, inToolMessage, message);
            newBlocks.add(next);
            if (next != b) changed = true;
        }
        if (!changed) return message;
        return withContent(message, Collections.unmodifiableList(newBlocks));
    }

    /**
     * Per-block scrub. Returns the input when supported; otherwise a
     * text-block placeholder.
     */
    static ContentBlock scrubBlock(
            ContentBlock block,
            ModelProfile profile,
            boolean toleratesNonPdfFiles,
            boolean inToolMessage,
            Message owningMessage) {
        if (!(block instanceof ContentBlock.GenericContentBlock g)) {
            return block;
        }
        String blockType = g.type();
        if (blockType == null || !MULTIMODAL_BLOCK_TYPES.contains(blockType)) {
            return block;
        }
        if (supported(g, blockType, profile, toleratesNonPdfFiles, inToolMessage)) {
            return block;
        }
        return placeholder(g, owningMessage);
    }

    /**
     * Check whether the block is supported by the profile plus the
     * hard-coded non-PDF provider exception. Mirrors the Python
     * port's {@code _multimodal_block_supported}.
     */
    static boolean supported(
            ContentBlock.GenericContentBlock block,
            String blockType,
            ModelProfile profile,
            boolean toleratesNonPdfFiles,
            boolean inToolMessage) {
        // URL/file-ID-backed file references don't carry base64; pass
        // them through so provider-managed references are not replaced
        // with text placeholders.
        if (BLOCK_TYPE_FILE.equals(blockType) && !block.fields().containsKey("base64")) {
            return true;
        }
        // Non-PDF base64 file blocks (DOCX, PPTX) have no ModelProfile
        // field today; only the hard-coded tolerant providers pass.
        if (BLOCK_TYPE_FILE.equals(blockType)) {
            Object mime = block.fields().get("mime_type");
            if (mime == null || !PDF_MIME_TYPE.equals(mime.toString())) {
                return toleratesNonPdfFiles;
            }
        }
        String field = profileFieldFor(blockType);
        if (field == null) {
            return true;
        }
        if (inToolMessage) {
            String toolField = toolMessageFieldFor(blockType);
            if (toolField != null && profile.isFalse(toolField)) {
                return false;
            }
        }
        return !profile.isFalse(field);
    }

    /** Profile field name gating a block type (or {@code null} if unknown). */
    static String profileFieldFor(String blockType) {
        return switch (blockType) {
            case BLOCK_TYPE_IMAGE -> "image_inputs";
            case BLOCK_TYPE_AUDIO -> "audio_inputs";
            case BLOCK_TYPE_VIDEO -> "video_inputs";
            case BLOCK_TYPE_FILE -> "pdf_inputs";
            default -> null;
        };
    }

    /** Extra profile field gating a block type inside a {@code ToolMessage}. */
    static String toolMessageFieldFor(String blockType) {
        return switch (blockType) {
            case BLOCK_TYPE_IMAGE -> "image_tool_message";
            case BLOCK_TYPE_FILE -> "pdf_tool_message";
            default -> null;
        };
    }

    /**
     * Build the text-block placeholder replacing a scrubbed block.
     * Mirrors the Python port's
     * {@code _unsupported_multimodal_placeholder}. The path comes
     * from {@code additional_kwargs["read_file_path"]} when the
     * message carries it, otherwise a generic label.
     */
    static ContentBlock placeholder(
            ContentBlock.GenericContentBlock block,
            Message owningMessage) {
        Object mime = block.fields().get("mime_type");
        String mimeType = mime == null ? "unknown" : mime.toString();
        String path = "the requested file";
        if (owningMessage instanceof HumanMessage hm) {
            Object p = hm.additionalKwargs().get("read_file_path");
            if (p instanceof String s && !s.isEmpty()) path = s;
        } else if (owningMessage instanceof ToolMessage tm) {
            Object p = tm.additionalKwargs().get("read_file_path");
            if (p instanceof String s && !s.isEmpty()) path = s;
        }
        String text = "[read_file: " + path + " was not attached because this model does not support "
                + block.type() + " content (" + mimeType + ").]";
        return new ContentBlock.TextBlock(text);
    }

    /** Copy a message with new content blocks. */
    static Message withContent(Message original, List<ContentBlock> content) {
        if (original instanceof HumanMessage hm) {
            return new HumanMessage(hm.id(), content, hm.evictedTo(), hm.additionalKwargs());
        }
        if (original instanceof ToolMessage tm) {
            return new ToolMessage(
                    tm.id(), tm.toolCallId(), content, tm.name(), tm.status(),
                    tm.artifact(), tm.additionalKwargs(), tm.responseMetadata());
        }
        return original;
    }

    /**
     * Build a {@code GenericContentBlock} with a fresh map (helper
     * for tests / callers building model-emitted content).
     */
    public static ContentBlock.GenericContentBlock blockOf(String type, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return new ContentBlock.GenericContentBlock(m);
    }
}
