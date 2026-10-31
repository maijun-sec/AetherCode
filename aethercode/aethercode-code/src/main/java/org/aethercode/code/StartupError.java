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

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Stderr marker emission used by the langgraph server graph entry point.
 *
 * <p>Lives in its own class so unit tests can exercise the marker contract
 * without triggering {@code server_graph.make_graph()} at import time.
 * Java-native port of the Python {@code deepagents_code._startup_error}
 * module.</p>
 */
public final class StartupError {
    private StartupError() {}

    private static final Logger LOG = LoggerFactory.getLogger(StartupError.class);

    /** Marker prefix used to identify single-line startup-failure summaries. */
    public static final String STARTUP_ERROR_MARKER = "DEEPAGENTS_STARTUP_ERROR:";

    /**
     * Report a server graph startup failure to the parent app process.
     *
     * <p>Emits two stderr outputs: the full traceback for logs/debugging, then
     * a single-line {@code STARTUP_ERROR_MARKER + type: summary} line that
     * the parent uses to upgrade an opaque "Server process exited with code N"
     * into an actionable summary.</p>
     *
     * @param exc the exception raised during graph initialization
     */
    public static void emitStartupFailure(Throwable exc) {
        LOG.error("Failed to initialize server graph", exc);
        String trace = stackTrace(exc);
        System.err.println("Failed to initialize server graph: " + exc + "\n" + trace);
        String summary = firstLine(String.valueOf(exc));
        if (summary.isEmpty()) {
            summary = "<no message>";
        }
        System.err.println(STARTUP_ERROR_MARKER + exc.getClass().getSimpleName() + ": " + summary);
    }

    private static String stackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            t.printStackTrace(pw);
        }
        return sw.toString();
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }
}
