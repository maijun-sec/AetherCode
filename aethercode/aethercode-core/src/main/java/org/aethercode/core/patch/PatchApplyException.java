/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.core.patch;

/**
 * thrown by {@link PatchApplier} when a hunk cannot be
 * applied. The message includes the line number and the expected vs
 * actual content.
 */
public class PatchApplyException extends RuntimeException {
    public PatchApplyException(String message) { super(message); }
    public PatchApplyException(String message, Throwable cause) { super(message, cause); }
}
