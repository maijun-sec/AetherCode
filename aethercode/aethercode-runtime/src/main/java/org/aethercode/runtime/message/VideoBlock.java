package org.aethercode.runtime.message;

import java.util.Optional;

/** Video content block (URL or inline base64). */
public record VideoBlock(
        String url,
        String base64,
        String mimeType
) implements ContentBlock {

    public VideoBlock {
        url = url == null ? "" : url;
        base64 = base64 == null ? "" : base64;
        mimeType = mimeType == null ? "" : mimeType;
    }

    public Optional<String> urlOpt()        { return url.isEmpty()      ? Optional.empty() : Optional.of(url); }
    public Optional<String> base64Opt()     { return base64.isEmpty()   ? Optional.empty() : Optional.of(base64); }
    public Optional<String> mimeTypeOpt()   { return mimeType.isEmpty() ? Optional.empty() : Optional.of(mimeType); }

    @Override
    public String type() {
        return "video";
    }
}
