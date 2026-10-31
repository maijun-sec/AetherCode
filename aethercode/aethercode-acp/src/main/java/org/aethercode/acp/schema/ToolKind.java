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

package org.aethercode.acp.schema;

import java.util.Optional;

/**
 * Tool kind discriminant (matches the {@code kind} field of
 * {@code ToolCallStart} / {@code ToolCallUpdate}). Mirrors
 * {@code acp.schema.ToolKind} string union.
 */
public final class ToolKind {
    private ToolKind() {}

    public static final String READ = "read";
    public static final String EDIT = "edit";
    public static final String DELETE = "delete";
    public static final String MOVE = "move";
    public static final String SEARCH = "search";
    public static final String EXECUTE = "execute";
    public static final String THINK = "think";
    public static final String FETCH = "fetch";
    public static final String SWITCH_MODE = "switch_mode";
    public static final String OTHER = "other";

    private ToolKind(String ignored) {}
}
