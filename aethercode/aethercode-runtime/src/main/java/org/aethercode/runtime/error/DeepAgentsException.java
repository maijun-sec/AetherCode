/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.runtime.error;

/**
 * Root of the deepagents exception hierarchy.
 *
 * <p>Java-native equivalent of langchain's <code>LangChainException</code>.
 * Concrete subclasses describe specific failure modes.</p>
 */
public class DeepAgentsException extends RuntimeException {
    public DeepAgentsException(String message) {
        super(message);
    }

    public DeepAgentsException(String message, Throwable cause) {
        super(message, cause);
    }
}
