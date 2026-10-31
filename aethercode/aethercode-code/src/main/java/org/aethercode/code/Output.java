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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Machine-readable JSON output helpers for CLI subcommands.
 *
 * <p>This class deliberately stays stdlib-only so it can be imported from CLI
 * startup paths without pulling in unnecessary dependency trees. Java-native
 * port of the Python {@code deepagents_code.output} module.</p>
 */
public final class Output {
    private Output() {}

    /** Shared mapper used to emit JSON envelopes. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Accepted internal output modes for CLI subcommands. */
    public enum Format {
        TEXT, JSON
    }

    /**
     * Write a JSON envelope to stdout and flush.
     *
     * <p>The envelope is a single-line JSON object with a stable schema:
     * <pre>{"schema_version": 1, "command": "...", "data": ...}</pre>
     * </p>
     *
     * @param command self-documenting command name (e.g. {@code "list"},
     *                {@code "threads list"})
     * @param data    payload — typically a list for listing commands or a
     *                dict for action/info commands
     */
    public static void writeJson(String command, Object data) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schema_version", 1);
        envelope.put("command", command);
        envelope.put("data", data);
        try {
            System.out.println(MAPPER.writeValueAsString(envelope));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize JSON envelope", e);
        }
    }

    /**
     * Convenience helper: write a JSON envelope whose {@code data} is a list.
     */
    public static void writeJsonList(String command, List<?> data) {
        writeJson(command, data);
    }
}
