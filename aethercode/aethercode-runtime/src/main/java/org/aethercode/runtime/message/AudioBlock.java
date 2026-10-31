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

package org.aethercode.runtime.message;

import java.util.Optional;

/** Audio content block (URL or inline base64). */
public record AudioBlock(
        String url,
        String base64,
        String mimeType
) implements ContentBlock {

    public AudioBlock {
        url = url == null ? "" : url;
        base64 = base64 == null ? "" : base64;
        mimeType = mimeType == null ? "" : mimeType;
    }

    public Optional<String> urlOpt()        { return url.isEmpty()      ? Optional.empty() : Optional.of(url); }
    public Optional<String> base64Opt()     { return base64.isEmpty()   ? Optional.empty() : Optional.of(base64); }
    public Optional<String> mimeTypeOpt()   { return mimeType.isEmpty() ? Optional.empty() : Optional.of(mimeType); }

    @Override
    public String type() {
        return "audio";
    }
}
