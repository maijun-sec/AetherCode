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
