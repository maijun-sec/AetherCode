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
        Path home = engineHome();
        if (home == null) return null;
        return home.resolve("sessions").toAbsolutePath().normalize();
    }

    /**
     * R417: the engine's OWN home — the place the engine keeps the
     * things that are not the user's project.
     *
     * <p>★ ONE ANSWER ★ the offload root and the scratch root are both
     * "the engine's own area", and they are siblings under it. Two
     * copies of "where does the engine keep its files" drift — and the
     * drift shows up as a directory the user cannot find.
     *
     * <p>Resolution: {@code AETHERCODE_HOME} if set, else
     * {@code $HOME/.aethercode}.
     *
     * @return the engine home, or null when no home is known
     */
    public static Path engineHome() {
        String home = System.getenv("AETHERCODE_HOME");
        if (home == null || home.isBlank()) {
            String userHome = System.getProperty("user.home");
            if (userHome == null || userHome.isBlank()) return null;
            home = userHome + "/.aethercode";
        }
        try {
            return Path.of(home).toAbsolutePath().normalize();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * R417: the SESSION's scratch directory — the engine-owned place
     * where a turn may put the things that are NOT the user's project.
     *
     * <h2>★ WHY THIS EXISTS ★</h2>
     * <p>R416's live matrix measured it: asked to verify its own work,
     * a turn left nine files in the user's tree — a {@code .verify/}
     * scaffolding directory, a verification script, a transcript of the
     * run. Every one of them was scaffolding the model built for
     * itself, and every one of them is garbage the user has to delete
     * by hand.
     *
     * <p>★ THE SAME SHAPE AS THE INDUSTRY'S SANDBOX ★ the reference
     * sandboxing guidance makes the writable set "the working directory
     * plus one per-user temporary directory", and tells the tools about
     * the temporary directory through an environment variable so that
     * "tools that write temporary files work without extra
     * configuration". ⇒ the scratch area is the temporary directory,
     * the environment variable is how the tools learn about it, and it
     * lives in the ENGINE's area — not in the user's tree, and not in
     * the whole-machine {@code %TEMP%} (which is shared with every
     * other program on the box).
     *
     * <p>★ PER SESSION, NOT PER TURN ★ a turn's leftovers should be
     * readable by the NEXT turn of the SAME session (that is often the
     * point — a script written to verify step 1 is used by step 2) and
     * invisible to every other session.
     *
     * @param sessionId the session the scratch belongs to
     * @return the scratch directory, or null when there is no home or no
     *         session
     */
    public static Path scratchRoot(String sessionId) {
        return scratchRoot(sessionId, engineHome());
    }

    /**
     * R417: the scratch area under a STATED engine home — the seam a
     * test needs.
     *
     * <p>★ WHY THE HOME IS A PARAMETER ★ the engine home comes from the
     * ENVIRONMENT ({@code AETHERCODE_HOME}), and a test cannot set an
     * environment variable. A test that used the ambient home would
     * measure whatever this machine happens to say — and the assertion
     * would be about the machine, not about the code. The production
     * path delegates here, so the two cannot disagree.
     *
     * @param sessionId the session the scratch belongs to
     * @param engineHome the engine's own home (null = no home)
     * @return the scratch directory, or null when there is no home or no
     *         session
     */
    public static Path scratchRoot(String sessionId, Path engineHome) {
        if (engineHome == null) return null;
        if (sessionId == null || sessionId.isBlank()) return null;
        return engineHome.resolve("scratch").resolve(safeDirName(sessionId)).toAbsolutePath().normalize();
    }

    /**
     * R417: the scratch directory of the session this call belongs to.
     *
     * @param ctx the tool call context (carries the session's app state)
     * @return the scratch directory, or null when it cannot be resolved
     */
    public static Path scratchRoot(Tool.CallContext ctx) {
        if (ctx == null) return null;
        Object appState = ctx.extra("app_state");
        if (appState instanceof AppState st) {
            return scratchRoot(st.sessionId());
        }
        return null;
    }

    /**
     * A directory name that is safe to create from an arbitrary id.
     *
     * <p>★ WHY THE SANITISING ★ the id comes from the wire (a session id
     * is chosen by the client in some flows), and a directory name that
     * can contain a separator would let it name a directory OUTSIDE the
     * scratch root — which is exactly the boundary this class exists to
     * hold.
     *
     * @param id the raw id
     * @return a name that is a single, plain path segment
     */
    public static String safeDirName(String id) {
        if (id == null || id.isBlank()) return "_";
        String s = id.trim().replaceAll("[^A-Za-z0-9._-]", "_");
        // "." and ".." are not names; they are the current and the parent
        // directory, and a session id must never be either.
        if (s.equals(".") || s.equals("..")) return "_";
        if (s.length() > 64) s = s.substring(0, 64);
        return s.isEmpty() ? "_" : s;
    }
}
