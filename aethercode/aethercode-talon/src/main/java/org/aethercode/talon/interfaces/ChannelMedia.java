package org.aethercode.talon.interfaces;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Outbound media delivered through a channel adapter.
 *
 * <p>Java-native port of {@code deepagents_talon.interfaces.ChannelMedia}.</p>
 */
public record ChannelMedia(
        Path path,
        OutboundMediaType mediaType,
        Optional<String> caption) {

    public ChannelMedia {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }
        if (mediaType == null) {
            throw new IllegalArgumentException("mediaType must not be null");
        }
        caption = caption == null ? Optional.empty() : caption;
    }

    public ChannelMedia(Path path, OutboundMediaType mediaType) {
        this(path, mediaType, Optional.empty());
    }

    public ChannelMedia(Path path, OutboundMediaType mediaType, String caption) {
        this(path, mediaType, Optional.ofNullable(caption));
    }
}
