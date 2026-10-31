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

package org.aethercode.core.middleware;

/**
 * Backward-compatible re-export of {@link FilesystemPermission}.
 *
 * <p>Java-native port of
 * {@code deepagents.middleware.permissions} (3-line re-export
 * module). The original Python code re-exports
 * {@code FilesystemPermission} from {@code deepagents.middleware.filesystem}
 * for callers that imported it from the old path. The Java port keeps
 * the same name on the {@code FilesystemPermission} record directly
 * and exposes a small interface here so legacy imports keep
 * compiling.</p>
 */
public final class FilesystemPermissions {
    private FilesystemPermissions() {}

    /** Type alias for {@link FilesystemPermission}. */
    public static final Class<FilesystemPermission> PERMISSION_CLASS = FilesystemPermission.class;
}
