/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

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
