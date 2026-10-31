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

/**
 * Version information and lightweight constants for the {@code deepagents-code}
 * package.
 *
 * <p>Java-native port of the Python {@code deepagents_code._version} module.
 * The {@link #VERSION} string follows the upstream Python release.</p>
 */
public final class Version {
    private Version() {}

    /** Current release version. Mirrors the Python {@code __version__}. */
    public static final String VERSION = "0.1.60";

    /** URL for the {@code deepagents-code} documentation. */
    public static final String DOCS_URL = "https://docs.langchain.com/oss/python/deepagents/code";

    /** PyPI JSON API endpoint for version checks. */
    public static final String PYPI_URL = "https://pypi.org/pypi/deepagents-code/json";

    /** PyPI JSON API endpoint for reading {@code deepagents} SDK release metadata. */
    public static final String SDK_PYPI_URL = "https://pypi.org/pypi/deepagents/json";

    /** URL for the full changelog. */
    public static final String CHANGELOG_URL =
            "https://github.com/langchain-ai/deepagents/blob/main/libs/code/CHANGELOG.md";

    /** User-Agent header sent with PyPI requests. */
    public static final String USER_AGENT = "deepagents-code/" + VERSION + " update-check";
}
