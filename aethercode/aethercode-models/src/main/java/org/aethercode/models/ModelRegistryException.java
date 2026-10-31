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

package org.aethercode.models;

/**
 * Phase 2.2 (design.md §3.7): typed exception for the model
 * registry. The CLI / RPC layer translates this into a
 * {@code model/not_found} (-32001) / {@code model/bad_yaml}
 * (-32002) / {@code model/io} (-32003) JSON-RPC error code.
 *
 * <p>Carrying a stable {@link #code()} keeps the wire contract
 * stable across the TUI/Desktop/CLI without forcing every
 * caller to parse the message.
 */
public class ModelRegistryException extends RuntimeException {

    public enum Kind {
        NOT_FOUND,
        BAD_YAML,
        IO_ERROR,
        INVALID_NAME
    }

    private final Kind kind;

    public ModelRegistryException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public ModelRegistryException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind code() {
        return kind;
    }

    public static ModelRegistryException notFound(String name) {
        return new ModelRegistryException(Kind.NOT_FOUND, "model not found: " + name);
    }

    public static ModelRegistryException badYaml(String message) {
        return new ModelRegistryException(Kind.BAD_YAML, message);
    }

    public static ModelRegistryException badYaml(String message, Throwable cause) {
        return new ModelRegistryException(Kind.BAD_YAML, message, cause);
    }

    public static ModelRegistryException ioError(String message, Throwable cause) {
        return new ModelRegistryException(Kind.IO_ERROR, message, cause);
    }

    public static ModelRegistryException invalidName(String name) {
        return new ModelRegistryException(Kind.INVALID_NAME, "invalid model name: " + name);
    }
}
