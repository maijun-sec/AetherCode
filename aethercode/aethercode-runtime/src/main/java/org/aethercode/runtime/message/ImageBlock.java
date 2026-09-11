package org.aethercode.runtime.message;

import java.util.Optional;

/**
 * Image content block. Mirrors the langchain
 * <code>{"type": "image", "url": ..., "base64": ..., "mime_type": ...}</code>
 * shape.
 *
 * <p>Exactly one of {@link #url()} (URL reference) or {@link #base64()}
 * (inline data URI / raw base64) is normally populated; both are exposed
 * as optionals so a producer can pick.</p>
 */
public record ImageBlock(
        String url,
        String base64,
        String mimeType
) implements ContentBlock {

    public ImageBlock {
        url = url == null ? "" : url;
        base64 = base64 == null ? "" : base64;
        mimeType = mimeType == null ? "" : mimeType;
    }

    public Optional<String> urlOpt()        { return url.isEmpty()       ? Optional.empty() : Optional.of(url); }
    public Optional<String> base64Opt()     { return base64.isEmpty()    ? Optional.empty() : Optional.of(base64); }
    public Optional<String> mimeTypeOpt()   { return mimeType.isEmpty()  ? Optional.empty() : Optional.of(mimeType); }

    @Override
    public String type() {
        return "image";
    }
}
