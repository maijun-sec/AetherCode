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

package org.aethercode.core.scope;

import org.aethercode.core.app.AppState;
import org.aethercode.core.tool.Tool;

import java.nio.file.Path;
import java.util.Locale;

/**
 * R415: ONE place that answers "where does this path point".
 *
 * <h2>★ WHY THIS CLASS EXISTS ★</h2>
 * <p>R415's survey of the write path found the path resolution done THREE
 * times, differently:
 *
 * <ul>
 *   <li>{@code FileWriteTool} — {@code Path.of(pathStr).toAbsolutePath()}.
 *       {@code toAbsolutePath()} resolves a RELATIVE path against the
 *       <b>JVM's</b> {@code user.dir} — the directory the daemon was
 *       launched from — not against the session's working directory. So a
 *       model that wrote {@code src/Foo.java} in a session whose cwd was
 *       {@code D:/tmp/proj} actually aimed at
 *       {@code <daemon launch dir>/src/Foo.java}, and the path guard then
 *       rejected it as "outside the working directory". The model was
 *       right and the resolution was wrong.</li>
 *   <li>{@code FileEditTool} — the same call, <b>without</b>
 *       {@code normalize()}, and with <b>no path guard at all</b>: it can
 *       edit any file on the whole filesystem.</li>
 *   <li>{@code NotebookEditTool} — the same, also unguarded.</li>
 * </ul>
 *
 * <p>⇒ Three copies of a rule that decides where a write lands is three
 * answers to one question (R380's lesson: one "list" is several lists,
 * and every one of them drifts). This class is the single answer; the
 * tools call it.
 *
 * <h2>★ WHY THE SESSION CWD AND NOT {@code user.dir} ★</h2>
 * <p>The session's cwd is the project the user pointed the session at. The
 * daemon's launch directory is an accident of how the daemon was started.
 * A model asked to work on a project must address paths relative to
 * <b>that project</b>.
 */
public final class SessionPaths {

    private SessionPaths() {}

    /**
     * The session's working directory.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>the {@code AppState} in the call context (the executor puts it
     *       there — the session's own cwd);</li>
     *   <li>the {@code aethercode.cwd} system property (the daemon's
     *       {@code --cwd} argument) — for ad-hoc tool invocations that
     *       carry no {@code AppState};</li>
     *   <li>the JVM's {@code user.dir}.</li>
     * </ol>
     *
     * @return never null
     */
    public static Path cwdOf(Tool.CallContext ctx) {
        if (ctx != null) {
            Object appState = ctx.extra("app_state");
            if (appState instanceof AppState st) {
                Path cwd = st.cwd();
                if (cwd != null) return cwd.toAbsolutePath().normalize();
            }
        }
        String engineCwd = System.getProperty("aethercode.cwd");
        if (engineCwd != null && !engineCwd.isBlank()) {
            try {
                return Path.of(engineCwd).toAbsolutePath().normalize();
            } catch (Exception ignored) {
                // fall through to the JVM's directory
            }
        }
        return Path.of("").toAbsolutePath().normalize();
    }

    /**
     * Resolve a model-supplied path against the session's cwd.
     *
     * <p>★ AN ABSOLUTE PATH IS TAKEN AS GIVEN ★ — the scope check, not
     * this method, decides whether an absolute path is allowed to be
     * written. This method only answers "where does it point".
     *
     * @param raw the path the model supplied
     * @param cwd the session's working directory (may be null)
     * @return an absolute, normalized path
     */
    public static Path resolve(String raw, Path cwd) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        Path p;
        try {
            p = Path.of(trimmed);
        } catch (Exception e) {
            return null;
        }
        if (p.isAbsolute()) return p.normalize();
        Path base = cwd != null ? cwd : Path.of("").toAbsolutePath().normalize();
        return base.resolve(p).normalize();
    }

    /**
     * Is {@code child} inside {@code parent} (or the same directory)?
     *
     * <p>★ CASE-INSENSITIVE ON WINDOWS ★ Windows paths are
     * case-insensitive but case-preserving, and {@code Path.startsWith} is
     * case-sensitive — so a model that emitted {@code D:\Tmp\abc\pom.xml}
     * against a session rooted at {@code d:\tmp\abc} would be judged
     * "outside" by a naive comparison.
     *
     * <p>★ THE SEPARATOR MATTERS ★ a plain string prefix would treat
     * {@code D:\tmp\abc} as the parent of {@code D:\tmp\abcde} ⇒ the
     * guard would let a write escape by one letter. The parent is compared
     * with a trailing separator.
     */
    public static boolean isInside(Path child, Path parent) {
        if (child == null || parent == null) return false;
        String c = normalizeForCompare(child);
        String p = normalizeForCompare(parent);
        if (p.isEmpty()) return false;
        if (c.equals(p)) return true;
        String prefix = p.endsWith("/") || p.endsWith("\\") ? p : p + "/";
        return c.startsWith(prefix);
    }

    /**
     * Is this path inside the session's working directory?
     *
     * <p>★ ONE IMPLEMENTATION ★ R415 found this rule written twice — once
     * in {@code FileWriteTool} and once in {@code FileReadTool} — and the
     * two copies were not the same: the read copy compared with a plain
     * string {@code startsWith}, so {@code D:\tmp\abcde} counted as
     * inside {@code D:\tmp\abc}. A boundary that a one-letter suffix
     * defeats is not a boundary.
     *
     * <p>★ THE ESCAPE HATCH IS HONOURED ★ {@code AETHERCODE_ALLOW_ANY_PATH=1}
     * means the user has opted out of the boundary.
     */
    public static boolean isInsideSession(Path p, Tool.CallContext ctx) {
        if (WriteScope.allowAnyPath()) return true;
        Path cwd = cwdOf(ctx);
        if (cwd == null) return true;
        return isInside(p, cwd);
    }

    /** The comparison key for a path: normalized, and lower-cased on Windows. */
    public static String key(Path p) {
        if (p == null) return "";
        String s = normalizeForCompare(p);
        return isWindows() ? s.toLowerCase(Locale.ROOT) : s;
    }

    /** Absolute, normalized, with the separators collapsed to '/'. */
    private static String normalizeForCompare(Path p) {
        String s = p.toAbsolutePath().normalize().toString().replace('\\', '/');
        while (s.length() > 1 && s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** Is this a Windows host? */
    public static boolean isWindows() {
        String os = System.getProperty("os.name");
        return os != null && os.toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * R416: the directory the engine offloads large tool results into.
     *
     * <p>★ WHY IT LIVES HERE ★ two places need the same answer: the
     * executor that writes the offload file, and the read scope that
     * must let the model read it back (the engine's own stub tells
     * the model to {@code file_read} that path — a read scope that
     * refused it would break a working feature). Two answers to one
     * question drift.
     *
     * <p>Resolution: {@code AETHERCODE_HOME} if set, else
     * {@code $HOME/.aethercode}; the offload root is the {@code
     * sessions} directory under it.
     *
     * @return the offload root, or null when no home is known
     */
    public static Path offloadRoot() {
        String home = System.getenv("AETHERCODE_HOME");
        if (home == null || home.isBlank()) {
            String userHome = System.getProperty("user.home");
            if (userHome == null || userHome.isBlank()) return null;
            home = userHome + "/.aethercode";
        }
        return Path.of(home, "sessions").toAbsolutePath().normalize();
    }
}
