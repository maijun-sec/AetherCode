package org.aethercode.core.middleware;

/**
 * Thrown when a summarization step is requested but no
 * {@link Summarizer} is registered. Mirrors the Python port's
 * "LLM summarization not configured" path.</p>
 */
public class SummarizerUnavailableError extends RuntimeException {
    public SummarizerUnavailableError(String message) { super(message); }
    public SummarizerUnavailableError(String message, Throwable cause) { super(message, cause); }
}
