package org.aethercode.runtime.message;

import java.util.Optional;

/** Generic file content block, used for arbitrary attachments. */
public record FileBlock(
        String url,
        String base64,
        String mimeType,
        String filename
) implements ContentBlock {

    public FileBlock {
        url = url == null ? "" : url;
        base64 = base64 == null ? "" : base64;
        mimeType = mimeType == null ? "" : mimeType;
        filename = filename == null ? "" : filename;
    }

    public Optional<String> urlOpt()       { return url.isEmpty()      ? Optional.empty() : Optional.of(url); }
    public Optional<String> base64Opt()    { return base64.isEmpty()   ? Optional.empty() : Optional.of(base64); }
    public Optional<String> mimeTypeOpt()  { return mimeType.isEmpty() ? Optional.empty() : Optional.of(mimeType); }
    public Optional<String> filenameOpt()  { return filename.isEmpty() ? Optional.empty() : Optional.of(filename); }

    @Override
    public String type() {
        return "file";
    }
}
