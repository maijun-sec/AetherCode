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

/**
 * Standardised error codes for file upload/download operations.
 *
 * <p>Mirror of the deepagents <code>FileOperationError</code> literal
 * type. These are the recoverable, agent-readable categories.</p>
 */
public enum FileOperationError {
    FILE_NOT_FOUND   ("file_not_found"),
    PERMISSION_DENIED("permission_denied"),
    IS_DIRECTORY     ("is_directory"),
    INVALID_PATH     ("invalid_path");

    private final String code;

    FileOperationError(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static final String FILE_NOT_FOUND_CODE   = "file_not_found";
    public static final String PERMISSION_DENIED_CODE = "permission_denied";
    public static final String IS_DIRECTORY_CODE     = "is_directory";
    public static final String INVALID_PATH_CODE     = "invalid_path";
}
