package org.aethercode.code;

import java.util.Base64;

/**
 * Media utilities.
 *
 * <p>Java-native port of the Python {@code deepagents_code.media_utils}
 * module. The Java port provides small helpers for encoding/decoding
 * media payloads (image data URLs, base64 transport encoding) the TUI
 * uses when rendering tool output.</p>
 */
public final class MediaUtils {
    private MediaUtils() {}

    /** Build a {@code data:} URL for an image. */
    public static String imageDataUrl(String mimeType, byte[] data) {
        if (data == null || data.length == 0) return null;
        String b64 = Base64.getEncoder().encodeToString(data);
        return "data:" + (mimeType == null ? "application/octet-stream" : mimeType) + ";base64," + b64;
    }

    /** Decode a {@code data:} URL into {@code (mime, bytes)}. */
    public static MediaPayload decodeDataUrl(String dataUrl) {
        if (dataUrl == null) return null;
        int comma = dataUrl.indexOf(',');
        if (comma < 0 || !dataUrl.startsWith("data:")) return null;
        String header = dataUrl.substring(5, comma);
        String body = dataUrl.substring(comma + 1);
        String mime = header;
        boolean base64 = false;
        int semi = header.indexOf(';');
        if (semi >= 0) {
            mime = header.substring(0, semi);
            String params = header.substring(semi + 1);
            base64 = params.contains("base64");
        }
        byte[] bytes = base64
                ? Base64.getDecoder().decode(body)
                : java.net.URLDecoder.decode(body, java.nio.charset.StandardCharsets.UTF_8)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new MediaPayload(mime, bytes);
    }

    /** A decoded media payload. */
    public record MediaPayload(String mimeType, byte[] data) {}
}
