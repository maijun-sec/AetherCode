package org.aethercode.core.fs;

/**
 * thrown by {@link PathNormalizer#safeResolve} when a path
 * would resolve outside the project root.
 */
public class PathEscapeException extends RuntimeException {
    public PathEscapeException(String message) { super(message); }
}
