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
