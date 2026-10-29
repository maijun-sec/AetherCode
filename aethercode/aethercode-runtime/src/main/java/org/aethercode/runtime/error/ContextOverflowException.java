/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.runtime.error;

/**
 * Thrown when an LLM call exceeds the model's context window.
 *
 * <p>Mirror of langchain's <code>ContextOverflowError</code>. The
 * summarization middleware catches this and retries with a compacted
 * message list.</p>
 */
public class ContextOverflowException extends DeepAgentsException {
    public ContextOverflowException(String message) {
        super(message);
    }

    public ContextOverflowException(String message, Throwable cause) {
        super(message, cause);
    }
}
