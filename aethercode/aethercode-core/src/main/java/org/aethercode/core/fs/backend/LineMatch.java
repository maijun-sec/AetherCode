package org.aethercode.core.fs.backend;

import java.util.List;
import java.util.Map;

/**
 * Internal record used by {@link FilesystemBackend} to accumulate
 * {@code (lineNumber, lineText)} pairs from a grep search.
 *
 * <p>Mirror of the {@code list[tuple[int, str]]} value type that ripgrep
 * and the Python-fallback search build up before converting to structured
 * {@link GrepMatch} records.</p>
 */
public record LineMatch(int line, String text) {
}
