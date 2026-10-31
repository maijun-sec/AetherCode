/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.aethercode.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

/**
 * Session persistence (stub for the SQLite-backed module).
 *
 * <p>Java-native port of the Python {@code deepagents_code.sessions}
 * module. The Java port exposes the public surface used by the TUI's
 * {@code /threads} screen; the full SQLite implementation lands with
 * the {@code deepagents-storage} port.</p>
 */
public final class Sessions {
    private Sessions() {}

    private static final Logger LOG = LoggerFactory.getLogger(Sessions.class);

    /** A persisted thread record. */
    public record ThreadInfo(
            String threadId,
            String agentName,
            long createdAtMs,
            long updatedAtMs,
            int messageCount,
            String initialPrompt) {
    }

    /** Return the path to the SQLite database. */
    public static Path databasePath() {
        return ModelConfig.DEFAULT_STATE_DIR.resolve("sessions.db");
    }

    /** Generate a new thread id. */
    public static String generateThreadId() {
        return java.util.UUID.randomUUID().toString();
    }

    /** List the most recent threads. */
    public static List<ThreadInfo> listRecent(int limit) {
        LOG.debug("Sessions.listRecent({}) (stub; full port uses SQLite)", limit);
        return List.of();
    }
}
