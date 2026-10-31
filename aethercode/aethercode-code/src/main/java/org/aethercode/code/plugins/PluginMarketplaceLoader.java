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

package org.aethercode.code.plugins;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plugin marketplace loader (stub).
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.plugins.marketplace} module. The Java port
 * returns a placeholder; the full implementation lands with the
 * deepagents-code.plugins subdirectory port.</p>
 */
public final class PluginMarketplaceLoader {
    private PluginMarketplaceLoader() {}

    /** Add a marketplace source. */
    public static PluginModels.PluginMarketplace addSource(String source) throws IOException {
        throw new IOException("Plugin marketplace addSource not implemented");
    }

    /** Load a marketplace from a local path. */
    public static PluginModels.PluginMarketplace loadMarketplaceLocation(Path path) {
        return new PluginModels.LocalMarketplace(path.getFileName().toString(), path.toString());
    }

    /** Redact URLs in a text snippet (used for error messages). */
    public static String redactUrlsInText(String text) {
        if (text == null) return null;
        return URL_RE.matcher(text).replaceAll("$1$3");
    }

    /** Redact sensitive parts of a marketplace source descriptor. */
    public static String redactMarketplaceSource(String source) {
        if (source == null) return null;
        return URL_RE.matcher(source).replaceAll("$1$3");
    }

    private static final Pattern URL_RE = Pattern.compile(
            "(https?://)([^@/\\s]+@)([^\\s]+)");
}
