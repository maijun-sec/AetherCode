package org.aethercode.talon.media;

import org.aethercode.talon.interfaces.ChannelMedia;
import org.aethercode.talon.interfaces.OutboundMediaType;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Media helpers shared by channel adapters and the Talon host.
 *
 * <p>Java-native port of {@code deepagents_talon.media}. Exposes the
 * markdown media extractor, bounded path resolver, MIME sniffers, and
 * content-block builders used to assemble multimodal prompts.</p>
 */
public final class MediaUtils {

    /** Maximum bytes for inbound images that the model will receive inline. */
    public static final long MAX_INBOUND_IMAGE_BYTES = 5L * 1024L * 1024L;
    /** Maximum bytes for a text document to be inlined. */
    public static final long MAX_TEXT_DOCUMENT_BYTES = 100L * 1024L;

    private static final Pattern MARKDOWN_MEDIA_PATTERN =
            Pattern.compile("!\\[([^\\]]*)]\\(([^)\\s]+)\\)");
    private static final Pattern FENCE_PATTERN =
            Pattern.compile("```[\\s\\S]*?```");
    private static final Pattern INLINE_CODE_PATTERN =
            Pattern.compile("`[^`\\n]+`");
    private static final String FENCE_PLACEHOLDER = "\u0000TALONFENCE";
    private static final String CODE_PLACEHOLDER = "\u0000TALONCODE";

    private MediaUtils() {}

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Append bounded user-visible fallback text for inbound media.
     */
    public static String buildInboundText(String text, java.util.Map<String, Object> metadata) {
        List<Path> mediaPaths = mediaPaths(metadata);
        if (mediaPaths.isEmpty()) {
            return text == null ? "" : text;
        }
        String mediaType = normalizedMediaType(metadata);
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (text != null && !text.strip().isEmpty()) {
            parts.add(text);
        }
        switch (mediaType) {
            case "document" -> parts.addAll(documentParts(mediaPaths));
            case "image" -> parts.addAll(imageFallbackParts(mediaPaths));
            case "video" -> parts.addAll(binaryMediaParts(mediaPaths, "video"));
            case "voice", "audio" -> {
                return text == null ? "" : text;
            }
            default -> parts.addAll(binaryMediaParts(mediaPaths, "media"));
        }
        String joined = parts.stream()
                .filter(p -> p != null && !p.strip().isEmpty())
                .reduce((a, b) -> a + "\n\n" + b)
                .orElse(text == null ? "" : text);
        return joined;
    }

    /**
     * Build provider-agnostic multimodal content for inbound photos.
     *
     * @return plain text for non-photo messages, or a list of content
     *         blocks (a text block followed by image data URLs).
     */
    public static Object buildModelContent(String text, java.util.Map<String, Object> metadata) {
        if (!"image".equals(normalizedMediaType(metadata))) {
            return text == null ? "" : text;
        }
        List<Path> mediaPaths = mediaPaths(metadata);
        List<String> mimeTypes = mediaMimeTypes(metadata);
        java.util.List<java.util.Map<String, Object>> blocks = new java.util.ArrayList<>();
        for (int i = 0; i < mediaPaths.size(); i++) {
            Path path = mediaPaths.get(i);
            String reported = (i < mimeTypes.size()) ? mimeTypes.get(i) : null;
            String mime = imageMime(path, reported);
            if (mime == null) {
                continue;
            }
            try {
                long size = Files.size(path);
                if (size > MAX_INBOUND_IMAGE_BYTES) {
                    continue;
                }
                byte[] raw = Files.readAllBytes(path);
                String data = Base64.getEncoder().encodeToString(raw);
                java.util.Map<String, Object> url = new java.util.LinkedHashMap<>();
                url.put("url", "data:" + mime + ";base64," + data);
                java.util.Map<String, Object> block = new java.util.LinkedHashMap<>();
                block.put("type", "image_url");
                block.put("image_url", url);
                blocks.add(block);
            } catch (IOException e) {
                continue;
            }
        }
        if (blocks.isEmpty()) {
            return text == null ? "" : text;
        }
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        java.util.Map<String, Object> textBlock = new java.util.LinkedHashMap<>();
        textBlock.put("type", "text");
        textBlock.put("text", (text == null || text.strip().isEmpty()) ? "(image)" : text.strip());
        out.add(textBlock);
        out.addAll(blocks);
        return out;
    }

    /**
     * Strip markdown media references from text and return local path refs.
     *
     * @return cleaned response text and extracted media references in
     *         source order.
     */
    public static MarkdownResult extractMarkdownMedia(String text) {
        if (text == null || text.isEmpty()) {
            return new MarkdownResult(text == null ? "" : text, List.of());
        }
        String[] mask = maskCode(text);
        String masked = mask[0];
        java.util.List<String> fences = splitTokens(mask[1]);
        java.util.List<String> codes = splitTokens(mask[2]);
        java.util.List<MarkdownMediaRef> refs = new java.util.ArrayList<>();
        Matcher m = MARKDOWN_MEDIA_PATTERN.matcher(masked);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            refs.add(new MarkdownMediaRef(m.group(1), Paths.get(m.group(2))));
            m.appendReplacement(out, "");
        }
        m.appendTail(out);
        String collapsed = out.toString().replaceAll("\n{3,}", "\n\n");
        String restored = restoreCode(collapsed, fences, codes);
        return new MarkdownResult(restored.strip(), refs);
    }

    /**
     * Build an outbound channel media payload for a markdown media ref.
     *
     * @throws IllegalArgumentException if the referenced path is unsafe or
     *                                  is not an image or video.
     */
    public static ChannelMedia outboundChannelMedia(MarkdownMediaRef ref,
                                                   String caption,
                                                   Path root) {
        Path path = (root != null)
                ? resolveBoundedMediaPath(ref.path(), root, true)
                : ref.path().toAbsolutePath();
        OutboundMediaType type = outboundMediaType(path);
        if (type == null) {
            throw new IllegalArgumentException("unsupported outbound media file type: " + path);
        }
        return new ChannelMedia(path, type, caption);
    }

    /**
     * Resolve a local media path and enforce containment under a trusted root.
     *
     * @throws IllegalArgumentException if the path is unsafe, unavailable,
     *                                  or escapes {@code root}.
     */
    public static Path resolveBoundedMediaPath(Path path, Path root, boolean requireRelative) {
        String raw = path.toString();
        URI uri;
        try {
            uri = new URI(raw);
        } catch (URISyntaxException ignored) {
            uri = null;
        }
        // Reject absolute URLs (scheme://...) but allow Windows drive paths
        // like C:\foo which URI parses as scheme="c".
        boolean isWindowsDrive = false;
        if (raw.length() >= 2 && Character.isLetter(raw.charAt(0))
                && raw.charAt(1) == ':') {
            isWindowsDrive = true;
        }
        if (uri != null && uri.getScheme() != null && !isWindowsDrive) {
            throw new IllegalArgumentException(
                    "media path must be a local filesystem path: " + path);
        }
        if (raw.startsWith("~") || (requireRelative
                && (path.isAbsolute() || isWindowsDrive))) {
            throw new IllegalArgumentException(
                    "media path must be a local relative path under the outbound root: "
                            + path);
        }
        for (Path part : path) {
            if (part.toString().equals("..")) {
                throw new IllegalArgumentException(
                        "media path must not contain parent-directory traversal: " + path);
            }
        }

        Path rootResolved = root.toAbsolutePath();
        Path candidate = path.isAbsolute() ? path : rootResolved.resolve(path);
        candidate = candidate.toAbsolutePath().normalize();
        if (!candidate.startsWith(rootResolved)) {
            throw new IllegalArgumentException("media path escapes outbound root: " + path);
        }
        if (!Files.isRegularFile(candidate)) {
            throw new IllegalArgumentException("media path is not a regular file: " + path);
        }
        return candidate;
    }

    // -----------------------------------------------------------------------
    // Public records returned by the helpers
    // -----------------------------------------------------------------------

    /** Markdown media reference extracted from an agent response. */
    public record MarkdownMediaRef(String alt, Path path) {}

    /** Result of {@link #extractMarkdownMedia(String)}. */
    public record MarkdownResult(String text, List<MarkdownMediaRef> refs) {}

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    private static List<Path> mediaPaths(java.util.Map<String, Object> metadata) {
        if (metadata == null) {
            return List.of();
        }
        java.util.List<Object> values = new java.util.ArrayList<>();
        Object rawMany = metadata.get("media_paths");
        if (rawMany == null) {
            rawMany = metadata.get("media_urls");
        }
        if (rawMany instanceof List<?> list) {
            values.addAll(list);
        }
        Object rawOne = metadata.get("media_path");
        if (rawOne == null) {
            rawOne = metadata.get("voice_path");
        }
        if (rawOne != null) {
            values.add(rawOne);
        }
        java.util.Set<Path> seen = new java.util.LinkedHashSet<>();
        java.util.List<Path> paths = new java.util.ArrayList<>();
        for (Object value : values) {
            Path p = pathFromValue(value);
            if (p != null && seen.add(p)) {
                paths.add(p);
            }
        }
        return paths;
    }

    private static Path pathFromValue(Object value) {
        if (value instanceof Path p) {
            return p.toAbsolutePath();
        }
        if (value instanceof String s && !s.isEmpty()) {
            return Paths.get(s).toAbsolutePath();
        }
        return null;
    }

    private static List<String> mediaMimeTypes(java.util.Map<String, Object> metadata) {
        if (metadata == null) {
            return List.of();
        }
        Object raw = metadata.get("media_mime_types");
        if (raw == null) {
            raw = metadata.get("mime_types");
        }
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Object item : list) {
            if (item instanceof String s && s.contains("/")) {
                out.add(s);
            }
        }
        return out;
    }

    private static String normalizedMediaType(java.util.Map<String, Object> metadata) {
        if (metadata == null) {
            return "unknown";
        }
        Object raw = metadata.get("media_type");
        if (raw == null) {
            raw = metadata.get("message_type");
        }
        String value = raw instanceof String s ? s.toLowerCase() : "";
        if (value.contains("image") || "photo".equals(value) || "sticker".equals(value)) {
            return "image";
        }
        if (value.contains("audio") || "voice".equals(value) || "ptt".equals(value)) {
            return "voice";
        }
        if (value.contains("video")) {
            return "video";
        }
        if ("document".equals(value) || "file".equals(value)) {
            return "document";
        }
        return value.isEmpty() ? "unknown" : value;
    }

    private static String imageMime(Path path, String reported) {
        if (reported != null && reported.startsWith("image/")) {
            return reported;
        }
        String guessed = guessMime(path);
        if (guessed != null && guessed.startsWith("image/")) {
            return guessed;
        }
        return sniffImageMime(path);
    }

    private static String guessMime(Path path) {
        try {
            return java.nio.file.Files.probeContentType(path);
        } catch (IOException e) {
            return null;
        }
    }

    private static String sniffImageMime(Path path) {
        try {
            byte[] header = new byte[12];
            try (java.io.InputStream in = Files.newInputStream(path)) {
                int read = in.read(header);
                if (read < 0) {
                    return null;
                }
            }
            if (header[0] == (byte) 0x89 && header[1] == 'P' && header[2] == 'N'
                    && header[3] == 'G' && header[4] == '\r' && header[5] == '\n'
                    && header[6] == 0x1A && header[7] == '\n') {
                return "image/png";
            }
            if ((header[0] & 0xff) == 0xff && (header[1] & 0xff) == 0xd8
                    && (header[2] & 0xff) == 0xff) {
                return "image/jpeg";
            }
            if (header[0] == 'G' && header[1] == 'I' && header[2] == 'F' && header[3] == '8'
                    && (header[4] == '7' || header[4] == '9') && header[5] == 'a') {
                return "image/gif";
            }
            if (header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                    && header[8] == 'W' && header[9] == 'E' && header[10] == 'B'
                    && header[11] == 'P') {
                return "image/webp";
            }
        } catch (IOException ignored) {
            // fall through
        }
        return null;
    }

    private static OutboundMediaType outboundMediaType(Path path) {
        String guessed = guessMime(path);
        if (guessed != null) {
            if (guessed.startsWith("image/")) {
                return OutboundMediaType.IMAGE;
            }
            if (guessed.startsWith("video/")) {
                return OutboundMediaType.VIDEO;
            }
        }
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return null;
        }
        String ext = name.substring(dot + 1).toLowerCase();
        if (List.of("mp4", "mov", "webm", "3gp", "m4v").contains(ext)) {
            return OutboundMediaType.VIDEO;
        }
        return null;
    }

    private static List<String> documentParts(List<Path> paths) {
        java.util.Set<String> textExts = Set.of(
                ".txt", ".md", ".csv", ".json", ".xml", ".yaml", ".yml",
                ".log", ".py", ".js", ".ts", ".html", ".css");
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (Path path : paths) {
            String name = path.getFileName().toString();
            int dot = name.lastIndexOf('.');
            String ext = (dot >= 0) ? name.substring(dot).toLowerCase() : "";
            if (!textExts.contains(ext)) {
                parts.add("_(Received unsupported document attachment: " + name + ".)_");
                continue;
            }
            try {
                long size = Files.size(path);
                if (size > MAX_TEXT_DOCUMENT_BYTES) {
                    parts.add("_(Document attachment is too large to read inline: "
                            + name + ".)_");
                    continue;
                }
                String content = Files.readString(path, java.nio.charset.StandardCharsets.UTF_8);
                parts.add("[Content of " + name + "]:\n" + content);
            } catch (IOException e) {
                parts.add("_(Document attachment was unavailable: " + name + ".)_");
            }
        }
        return parts;
    }

    private static List<String> imageFallbackParts(List<Path> paths) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (Path path : paths) {
            String name = path.getFileName().toString();
            try {
                long size = Files.size(path);
                if (size > MAX_INBOUND_IMAGE_BYTES) {
                    parts.add("_(Image attachment is too large to inspect: " + name + ".)_");
                }
            } catch (IOException e) {
                parts.add("_(Image attachment was unavailable: " + name + ".)_");
            }
        }
        return parts;
    }

    private static List<String> binaryMediaParts(List<Path> paths, String mediaType) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (Path path : paths) {
            parts.add("_(Received " + mediaType + " attachment: " + path + ".)_");
        }
        return parts;
    }

    // -----------------------------------------------------------------------
    // Code-fence masking
    // -----------------------------------------------------------------------

    private static String[] maskCode(String text) {
        java.util.List<String> fences = new java.util.ArrayList<>();
        java.util.List<String> codes = new java.util.ArrayList<>();
        String masked = FENCE_PATTERN.matcher(text).replaceAll(m -> {
            int idx = fences.size();
            fences.add(m.group(0));
            return FENCE_PLACEHOLDER + idx + "\u0000";
        });
        masked = INLINE_CODE_PATTERN.matcher(masked).replaceAll(m -> {
            int idx = codes.size();
            codes.add(m.group(0));
            return CODE_PLACEHOLDER + idx + "\u0000";
        });
        return new String[]{masked, joinTokens(fences), joinTokens(codes)};
    }

    private static String restoreCode(String text, List<String> fences, List<String> codes) {
        String restored = text;
        for (int i = 0; i < fences.size(); i++) {
            restored = restored.replace(FENCE_PLACEHOLDER + i + "\u0000", fences.get(i));
        }
        for (int i = 0; i < codes.size(); i++) {
            restored = restored.replace(CODE_PLACEHOLDER + i + "\u0000", codes.get(i));
        }
        return restored;
    }

    private static String joinTokens(java.util.List<String> tokens) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0) {
                sb.append('\u0001');
            }
            sb.append(tokens.get(i));
        }
        return sb.toString();
    }

    private static java.util.List<String> splitTokens(String joined) {
        if (joined.isEmpty()) {
            return new java.util.ArrayList<>();
        }
        String[] parts = joined.split("\u0001", -1);
        java.util.List<String> out = new java.util.ArrayList<>(parts.length);
        for (String p : parts) {
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        return out;
    }
}
