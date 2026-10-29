/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.acp.schema;

/**
 * Marker interface for ACP content blocks.
 *
 * <p>Mirrors the {@code acp.schema.*ContentBlock} union. The
 * Java port provides a {@link ContentBlock#type()} discriminant
 * that matches the wire-format {@code "type"} field.</p>
 */
public sealed interface ContentBlock
        permits ContentBlocks.TextContentBlock,
                ContentBlocks.ImageContentBlock,
                ContentBlocks.AudioContentBlock,
                ContentBlocks.ResourceContentBlock,
                ContentBlocks.EmbeddedResourceContentBlock {
    String type();
}
