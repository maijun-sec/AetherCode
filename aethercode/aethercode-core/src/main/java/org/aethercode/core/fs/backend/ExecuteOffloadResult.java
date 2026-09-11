package org.aethercode.core.fs.backend;

/**
 * Result of {@link BaseSandbox#executeWithOffload}.
 *
 * <p>Mirror of the deepagents <code>ExecuteOffloadResult</code> dataclass.
 * {@code offloaded} describes the capture mechanism and is kept off
 * {@link ExecuteResponse} (which an ordinary {@code execute} never sets).</p>
 */
public record ExecuteOffloadResult(
        boolean offloaded,
        ExecuteResponse response
) {
}
