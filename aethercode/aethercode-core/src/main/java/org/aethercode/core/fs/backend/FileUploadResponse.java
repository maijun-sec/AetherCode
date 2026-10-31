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

package org.aethercode.core.fs.backend;

import java.util.Optional;

/**
 * Result of a single file upload operation. Designed to allow partial
 * success in batch operations: the response order matches the input
 * order, callers check the {@code error} field per entry.
 *
 * <p>Mirror of the deepagents <code>FileUploadResponse</code> dataclass.</p>
 */
public record FileUploadResponse(
        String path,
        Optional<String> error
) {
    public static FileUploadResponse success(String path) {
        return new FileUploadResponse(path, Optional.empty());
    }

    public static FileUploadResponse failure(String path, String error) {
        return new FileUploadResponse(path, Optional.ofNullable(error));
    }

    public boolean isSuccess() { return error.isEmpty(); }
}
