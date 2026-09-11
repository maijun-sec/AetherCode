package org.aethercode.core.fs.backend;

import java.util.Optional;

/**
 * Result of a backend read operation.
 *
 * <p>Mirror of the deepagents <code>ReadResult</code> dataclass with
 * its <code>__post_init__</code> invariant checks. The window fields
 * are co-dependent: <code>start_line</code>/<code>end_line</code> must
 * be set together or both unset; <code>next_offset</code> requires a
 * window; and the 1-indexed window must be forward and at most as long
 * as the file.</p>
 */
public record ReadResult(
        Optional<String> error,
        Optional<FileData> fileData,
        Optional<Integer> totalLines,
        Optional<Integer> startLine,
        Optional<Integer> endLine,
        Optional<Integer> nextOffset,
        boolean noLinesRequested
) {
    public ReadResult {
        if (startLine.isPresent() != endLine.isPresent()) {
            throw new IllegalArgumentException(
                    "ReadResult.start_line and end_line must be set together or both left unset");
        }
        if (noLinesRequested
                && (error.isPresent() || startLine.isPresent()
                    || nextOffset.isPresent() || totalLines.isPresent())) {
            throw new IllegalArgumentException(
                    "ReadResult.no_lines_requested describes an uninspected window; "
                            + "it cannot be combined with error or pagination fields");
        }
        if (nextOffset.isPresent() && startLine.isEmpty()) {
            throw new IllegalArgumentException(
                    "ReadResult.next_offset requires start_line and end_line to be set");
        }
        if (totalLines.isPresent() && startLine.isEmpty()) {
            throw new IllegalArgumentException(
                    "ReadResult.total_lines requires start_line and end_line to be set");
        }
        if (startLine.isPresent() && endLine.isPresent()) {
            int s = startLine.get(), e = endLine.get();
            if (s < 1 || e < s) {
                throw new IllegalArgumentException(
                        "ReadResult window must satisfy 1 <= start_line <= end_line, "
                                + "got start_line=" + s + ", end_line=" + e);
            }
            if (totalLines.isPresent() && totalLines.get() < e) {
                throw new IllegalArgumentException(
                        "ReadResult.total_lines (" + totalLines.get()
                                + ") cannot be less than end_line (" + e + ")");
            }
            if (nextOffset.isPresent() && nextOffset.get() != e) {
                throw new IllegalArgumentException(
                        "ReadResult.next_offset (" + nextOffset.get()
                                + ") must equal end_line (" + e
                                + "), the 0-indexed line after the last shown");
            }
        }
    }

    public static ReadResult error(String message) {
        return new ReadResult(Optional.ofNullable(message), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false);
    }

    public static ReadResult of(FileData fileData) {
        return new ReadResult(Optional.empty(), Optional.of(fileData),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false);
    }

    public static ReadResult of(FileData fileData, int startLine, int endLine, int totalLines) {
        return new ReadResult(Optional.empty(), Optional.of(fileData),
                Optional.of(totalLines),
                Optional.of(startLine), Optional.of(endLine),
                Optional.of(endLine), false);
    }

    public static ReadResult empty() {
        return new ReadResult(Optional.empty(), Optional.of(FileData.of("")),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), true);
    }
}
