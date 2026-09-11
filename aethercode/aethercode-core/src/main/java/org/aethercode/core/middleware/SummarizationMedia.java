package org.aethercode.core.middleware;

import org.aethercode.core.fs.backend.FileUploadResponse;
import org.aethercode.core.runtime.ContentBlock;
import org.aethercode.core.runtime.Message;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.aethercode.core.runtime.Message.AIMessage;
import org.aethercode.core.runtime.Message.HumanMessage;
import org.aethercode.core.runtime.Message.ToolMessage;
import org.aethercode.core.runtime.Message.SystemMessage;
import org.aethercode.core.runtime.Message.RemoveMessage;

/**
 * Pure-logic helpers for inline media handling used by the summarization
 * middleware.
 *
 * <p>Java-native port of the module-level functions
 * {@code _is_data_url}, {@code _extract_data_url}, {@code _decode_data_url},
 * {@code _media_reference_block}, {@code _rewrite_data_url_blocks}, and
 * {@code _upload_response_error} in
 * {@code deepagents.middleware.summarization}. Each helper is intentionally
 * dependency-free (apart from {@link ContentBlock} / {@link Message} /
 * {@link FileUploadResponse} types) so it can be tested in isolation
 * without a backend, summarizer, or model runtime.</p>
 */
public final class SummarizationMedia {

    private static final Logger log = Logger.getLogger(SummarizationMedia.class.getName());

    /** Text placeholder written when a media block cannot be offloaded. */
    public static final String OFFLOAD_FAILED_PLACEHOLDER =
            "<image error=\"failed_to_offload\" />";

    /** Length of the truncated sha256 hash used as the offload key. */
    public static final int HASH_KEY_LENGTH = 16;

    private SummarizationMedia() {}

    // -----------------------------------------------------------------
    //  Detection: is a URL a data: URL?
    // -----------------------------------------------------------------

    /**
     * Return whether {@code url} is an inline {@code data:} URL.
     *
     * <p>Any {@code data:} URL is treated as inline media, because the
     * XML history renderer drops {@code data:} URL blocks entirely (only
     * {@code http(s)}-style references survive). This covers both
     * base64 ({@code data:<mime>;base64,<payload>}) and percent-encoded /
     * plaintext ({@code data:<mime>,<payload>}, e.g. an inline SVG) forms;
     * whether the payload actually decodes is left to
     * {@link #decodeDataUrl(String)}.</p>
     */
    public static boolean isDataUrl(String url) {
        return url != null && url.startsWith("data:");
    }

    // -----------------------------------------------------------------
    //  Detection: does a content block carry inline data?
    // -----------------------------------------------------------------

    /**
     * Return the embedded {@code data:} URL for an inline-media content
     * block, or {@code null} if the block carries no inline data.
     *
     * <p>Detects the three inline-data content-block shapes that appear
     * across LangChain messages:</p>
     * <ol>
     *   <li>A standard content block with an explicit {@code base64} field.</li>
     *   <li>A {@code data:} URL on the top-level {@code url} field.</li>
     *   <li>An OpenAI-style {@code image_url} block whose {@code url} is
     *       a {@code data:} URL.</li>
     * </ol>
     *
     * <p>Returns {@code null} for non-{@link ContentBlock.GenericContentBlock}
     * inputs &mdash; only generic blocks can carry the three shapes
     * above. This is pure detection and never raises.</p>
     */
    public static String extractDataUrl(ContentBlock block) {
        if (!(block instanceof ContentBlock.GenericContentBlock g)) {
            return null;
        }
        Map<String, Object> fields = g.fields();

        // 1. Standard content block with an explicit base64 field.
        Object rawB64 = fields.get("base64");
        if (rawB64 instanceof String s && !s.isEmpty()) {
            Object mime = fields.get("mime_type");
            String mimeStr = (mime instanceof String ms && !ms.isEmpty())
                    ? ms : "application/octet-stream";
            return "data:" + mimeStr + ";base64," + s;
        }

        // 2. Top-level data: URL.
        Object url = fields.get("url");
        if (url instanceof String s && isDataUrl(s)) {
            return s;
        }

        // 3. OpenAI-style image_url with a data: URL.
        Object imageUrl = fields.get("image_url");
        if (imageUrl instanceof Map<?, ?> m) {
            Object inner = m.get("url");
            if (inner instanceof String s && isDataUrl(s)) {
                return s;
            }
        }
        return null;
    }

    // -----------------------------------------------------------------
    //  Decoding: data:<mime>[;base64],<payload> -> (bytes, ext, mime)
    // -----------------------------------------------------------------

    /**
     * Decode a {@code data:<mime>[;base64],<payload>} URL to raw bytes, a
     * file extension, and a MIME type.
     *
     * <p>Returns {@link Optional#empty()} if decoding fails (including a
     * malformed URL with no {@code ,} payload separator). A failure is
     * logged here and, like an upload failure, surfaces as a
     * failed-offload placeholder that counts toward the caller's
     * aggregate warning &mdash; it is never swallowed silently.</p>
     */
    public static Optional<DecodedDataUrl> decodeDataUrl(String dataUrl) {
        if (dataUrl == null || !dataUrl.startsWith("data:")) {
            return Optional.empty();
        }
        try {
            int comma = dataUrl.indexOf(',');
            if (comma < 0) {
                log.log(Level.WARNING, "Failed to decode data: content block: missing comma");
                return Optional.empty();
            }
            String header = dataUrl.substring(0, comma);
            String payload = dataUrl.substring(comma + 1);

            String mime = "application/octet-stream";
            if (header.contains(":")) {
                mime = header.split(":", 2)[1].split(";")[0];
                if (mime.isEmpty()) {
                    mime = "application/octet-stream";
                }
            }
            String ext = guessExtension(mime);
            boolean isBase64 = header.toLowerCase().contains(";base64");
            byte[] raw = isBase64
                    ? Base64.getDecoder().decode(payload)
                    : URLDecoder.decode(payload, StandardCharsets.UTF_8)
                            .getBytes(StandardCharsets.UTF_8);
            return Optional.of(new DecodedDataUrl(raw, ext, mime));
        } catch (RuntimeException e) {
            log.log(Level.WARNING,
                    "Failed to decode data: content block ({0}): {1}",
                    new Object[]{e.getClass().getSimpleName(), e.getMessage()});
            return Optional.empty();
        }
    }

    /**
     * Best-effort mapping from MIME to a file extension. Falls back to
     * {@code bin} when the platform has no guess.
     */
    private static String guessExtension(String mime) {
        if (mime == null) return "bin";
        String m = mime.toLowerCase();
        return switch (m) {
            case "image/png" -> "png";
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            case "image/svg+xml" -> "svg";
            case "image/bmp" -> "bmp";
            case "audio/mpeg", "audio/mp3" -> "mp3";
            case "audio/wav", "audio/wave" -> "wav";
            case "audio/ogg" -> "ogg";
            case "video/mp4" -> "mp4";
            case "video/webm" -> "webm";
            case "video/ogg" -> "ogv";
            case "application/pdf" -> "pdf";
            case "text/plain" -> "txt";
            case "text/html" -> "html";
            case "application/json" -> "json";
            default -> "bin";
        };
    }

    // -----------------------------------------------------------------
    //  Reference block: build a path-reference block
    // -----------------------------------------------------------------

    /**
     * Build a content block referencing offloaded media by backend path.
     *
     * <p>The block type is chosen so the XML history renderer serializes
     * the reference: {@code image}, {@code audio}, and {@code video} map
     * to their typed blocks, while any other MIME type falls back to a
     * text block (the renderer has no generic file block and would
     * otherwise drop it).</p>
     */
    public static ContentBlock mediaReferenceBlock(String path, String mime) {
        String major = (mime == null ? "" : mime).split("/", 2)[0];
        if (major.equals("image") || major.equals("audio") || major.equals("video")) {
            return ContentBlock.genericOf(major, "url", path);
        }
        String text = "<file url=\"" + path + "\" />";
        return ContentBlock.text(text);
    }

    // -----------------------------------------------------------------
    //  Rewrite messages: data: -> path references (or placeholders)
    // -----------------------------------------------------------------

    /**
     * Rewrite inline {@code data:} URL blocks using uploaded media paths.
     *
     * <p>Each inline-data block whose content hash appears in
     * {@code pathMap} becomes a typed media reference block. Blocks
     * whose upload failed &mdash; or whose payload could not be decoded
     * &mdash; become an {@value #OFFLOAD_FAILED_PLACEHOLDER} text
     * placeholder so the saved history records that media was present
     * rather than silently dropping it. Blocks without inline data pass
     * through unchanged.</p>
     *
     * @param messages  messages whose inline-data blocks should be rewritten
     * @param pathMap   mapping of {@code sha256[:16]} to backend paths for
     *                  successfully uploaded media
     * @return          a {@link Result} containing the rewritten message
     *                  list and the number of blocks rewritten to a
     *                  failed-offload placeholder
     */
    public static Result rewriteDataUrlBlocks(List<Message> messages,
                                              Map<String, String> pathMap) {
        if (messages == null || messages.isEmpty()) {
            return new Result(messages == null ? List.of() : messages, 0);
        }
        List<Message> out = new ArrayList<>(messages.size());
        int failed = 0;
        for (Message m : messages) {
            List<ContentBlock> blocks = m.content();
            if (blocks == null || blocks.isEmpty()) {
                out.add(m);
                continue;
            }
            List<ContentBlock> newBlocks = new ArrayList<>(blocks.size());
            boolean modified = false;
            for (ContentBlock b : blocks) {
                String dataUrl = extractDataUrl(b);
                if (dataUrl == null) {
                    newBlocks.add(b);
                    continue;
                }
                modified = true;
                Optional<DecodedDataUrl> decoded = decodeDataUrl(dataUrl);
                if (decoded.isPresent()) {
                    String key = sha256Prefix(decoded.get().raw());
                    String path = pathMap.get(key);
                    if (path != null) {
                        newBlocks.add(mediaReferenceBlock(path, decoded.get().mime()));
                        continue;
                    }
                }
                failed++;
                newBlocks.add(ContentBlock.text(OFFLOAD_FAILED_PLACEHOLDER));
            }
            if (modified) {
                out.add(replaceContent(m, newBlocks));
            } else {
                out.add(m);
            }
        }
        return new Result(out, failed);
    }

    /**
     * Build a copy of {@code m} with its content blocks replaced by
     * {@code newBlocks}. Each message type has its own canonical ctor
     * shape; we use the narrowest constructor that preserves the
     * identifying fields.
     */
    private static Message replaceContent(Message m, List<ContentBlock> newBlocks) {
        if (m instanceof HumanMessage hm) {
            return new HumanMessage(
                    hm.id(), newBlocks, hm.evictedTo(), hm.additionalKwargs());
        }
        if (m instanceof AIMessage aim) {
            return new AIMessage(aim.id(), newBlocks, aim.toolCallId());
        }
        if (m instanceof SystemMessage sm) {
            return new SystemMessage(sm.id(), newBlocks);
        }
        if (m instanceof ToolMessage tm) {
            return new ToolMessage(
                    tm.id(), tm.toolCallId(), newBlocks,
                    tm.name(), tm.status(), tm.artifact(),
                    tm.additionalKwargs(), tm.responseMetadata());
        }
        // RemoveMessage and any other non-content type: return as-is.
        return m;
    }

    /**
     * Compute the {@value #HASH_KEY_LENGTH}-hex-char sha256 prefix of
     * {@code raw} used as the offload key. Mirrors Python's
     * {@code hashlib.sha256(raw).hexdigest()[:16]}.
     */
    public static String sha256Prefix(byte[] raw) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw);
            StringBuilder sb = new StringBuilder(HASH_KEY_LENGTH);
            for (int i = 0; i < HASH_KEY_LENGTH / 2 && i < digest.length; i++) {
                sb.append(String.format("%02x", digest[i] & 0xff));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // -----------------------------------------------------------------
    //  Upload response classification
    // -----------------------------------------------------------------

    /**
     * Extract an error from a single-file batch upload result.
     *
     * <p>{@code uploadFiles}/{@code auploadFiles} are batch APIs that
     * return one {@link FileUploadResponse} per input file in order.
     * Image offloading passes exactly one file at a time, so the
     * expected length is 1 and {@code responses[0]} maps to that
     * file.</p>
     *
     * @return the upload error, the literal {@code "missing_upload_response"}
     *         if the backend returned no response, or {@code null} when the
     *         upload succeeded.
     */
    public static String uploadResponseError(List<FileUploadResponse> responses) {
        if (responses == null || responses.isEmpty()) {
            return "missing_upload_response";
        }
        FileUploadResponse r = responses.get(0);
        return r.error().orElse(null);
    }

    // -----------------------------------------------------------------
    //  Value types
    // -----------------------------------------------------------------

    /** Decoded result of a {@code data:} URL: bytes + extension + MIME. */
    public record DecodedDataUrl(byte[] raw, String extension, String mime) {
        public DecodedDataUrl {
            raw = raw == null ? new byte[0] : raw.clone();
        }
    }

    /** Pair returned by {@link #rewriteDataUrlBlocks(List, Map)}. */
    public record Result(List<Message> messages, int failedBlockCount) {
        public Result {
            messages = messages == null ? List.of() : List.copyOf(messages);
        }
    }

    // -----------------------------------------------------------------
    //  Tiny utility for tests: build a base64 data: URL string
    // -----------------------------------------------------------------

    /**
     * Convenience helper for tests / other call sites that want to
     * build a {@code data:<mime>;base64,<payload>} URL string. Kept
     * here because the offload tests need it and we don't want a
     * second utility class for one method.
     */
    public static String buildBase64DataUrl(byte[] raw, String mime) {
        String m = (mime == null || mime.isEmpty()) ? "application/octet-stream" : mime;
        return "data:" + m + ";base64," + Base64.getEncoder().encodeToString(raw);
    }

    /**
     * Convenience helper: build a generic {@code file} content block
     * with the given base64 payload and MIME type. Mirrors the
     * existing {@link ContentBlock#file(String, String)} factory but
     * the latter builds with {@code base64} field directly &mdash; this
     * one returns the {@code data:...;base64,...} URL representation
     * for symmetry with the other helpers.
     */
    public static ContentBlock fileBlockWithBase64(String base64, String mimeType) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("type", "file");
        if (base64 != null) fields.put("base64", base64);
        if (mimeType != null) fields.put("mime_type", mimeType);
        return new ContentBlock.GenericContentBlock(fields);
    }
}
