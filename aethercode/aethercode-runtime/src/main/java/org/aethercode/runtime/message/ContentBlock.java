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

package org.aethercode.runtime.message;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Map;

/**
 * A single unit of content carried by a message.
 *
 * <p>Mirrors the langchain <code>ContentBlock</code> union: text, images, audio,
 * video, files, tool-use requests, and tool-result payloads. The Java port
 * keeps this as a flat sealed interface so that pattern-matching in
 * middleware is exhaustive.</p>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = TextBlock.class,        name = "text"),
        @JsonSubTypes.Type(value = ImageBlock.class,       name = "image"),
        @JsonSubTypes.Type(value = AudioBlock.class,       name = "audio"),
        @JsonSubTypes.Type(value = VideoBlock.class,       name = "video"),
        @JsonSubTypes.Type(value = FileBlock.class,        name = "file"),
        @JsonSubTypes.Type(value = ToolUseBlock.class,     name = "tool_use"),
        @JsonSubTypes.Type(value = ToolResultBlock.class,  name = "tool_result")
})
public sealed interface ContentBlock
        permits TextBlock, ImageBlock, AudioBlock, VideoBlock,
                FileBlock, ToolUseBlock, ToolResultBlock {

    /** Discriminator used for JSON serialization and Python parity. */
    String type();
}
