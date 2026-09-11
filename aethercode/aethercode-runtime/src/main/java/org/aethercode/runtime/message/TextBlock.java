package org.aethercode.runtime.message;

/**
 * Plain text content block.
 *
 * <p>Equivalent to <code>{"type": "text", "text": "..."}</code> in the
 * langchain Python <code>ContentBlock</code> union.</p>
 *
 * @param text the literal text
 */
public record TextBlock(String text) implements ContentBlock {

    @Override
    public String type() {
        return "text";
    }
}
