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
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;
import org.aethercode.core.tool.Tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * R415: the WRITE SCOPE — a hard boundary on where a turn may write.
 *
 * <h2>★ WHY A SCOPE AND NOT MORE PROMPT ★</h2>
 * <p>R414 measured the cost of the missing boundary: asked for ONE unit
 * test, the turn scaffolded a whole Maven project — thirteen files nobody
 * asked for. R414's answer was a posture clause ("do not SCAFFOLD"), and
 * it worked (the next live round left exactly the fixture and one test
 * file). But a posture clause is a <b>request</b>, and the thing the user
 * asked for is a <b>boundary</b>.
 *
 * <p>⇒ The user's own design principle settles the shape: deterministic
 * logic takes the deterministic path. A rule that lives in the prompt can
 * be read, forgotten, or overridden by a plausible-sounding instruction in
 * the conversation; a rule that lives in the tool cannot.
 *
 * <h2>★ THE THREE RULES ★</h2>
 * <ul>
 *   <li><b>W1 — the tree is the boundary.</b> A write outside the
 *       session's working directory is refused. This is the rule that was
 *       <b>missing</b> from {@code file_edit} and {@code notebook_edit}
 *       (R415's survey: {@code file_edit} could edit any file on the
 *       whole filesystem, while {@code ToolSafeList}'s comment claimed it
 *       "refuses paths outside the configured cwd" — the claim was false),
 *       and it is the rule that stops the operations turn from hunting the
 *       filesystem for a project (R414's O1 scenario).</li>
 *   <li><b>W2 — tool-owned directories are not the model's.</b> A write
 *       into {@code .git/}, {@code node_modules/}, a virtualenv or a
 *       Python cache is refused: those belong to the tool that manages
 *       them, and a hand-written file there is garbage the user has to
 *       clean up by hand.</li>
 *   <li><b>W3 — do not invent a build system.</b> Creating a build file
 *       (or a {@code .gitignore}) in a project that has none is refused
 *       — unless the request itself names that build system. This is the
 *       structural form of R414's "the repository has no build; write the
 *       code and say there is nothing to run it with".</li>
 * </ul>
 *
 * <h2>★ WHAT THIS IS NOT ★</h2>
 * <p>It is <b>not</b> "you may only edit the files the user named" — that
 * set is not computable from a Chinese sentence, and a rule that guesses
 * wrong blocks work the user asked for. The scope says "inside the tree,
 * not in the tool-owned places, no invented build system", and the
 * <i>read-before-you-overwrite</i> rule (which the write guard already
 * owns) says "and not a file you never looked at". Between them they
 * cover the measured failures without inventing a parser for intent.
 *
 * <p>★ THE SHELL IS NOT IN THE SCOPE ★ {@code bash} can write any file
 * on the filesystem, and no path check can constrain it. The scope covers
 * the tools that name a path; the shell's writes are a separate problem
 * (the shell write detector) and this class does not pretend to cover
 * them.
 */
public final class WriteScope {

    /** The key the scope travels under in {@link Tool.CallContext#extras()}. */
    public static final String EXTRAS_KEY = "aethercode.write_scope";

    /** The verb used in the refusal message ("write refused: …"). */
    public static final String VERB = "write";

    /** ★ THE ESCAPE HATCH ★ the same environment variable the existing
     *  write guard honours: when it is {@code "1"}, the tree boundary and
     *  the tool-owned directories are not enforced. A user who sets it is
     *  saying "I know what I am doing". */
    public static final String ALLOW_ANY_PATH_ENV = "AETHERCODE_ALLOW_ANY_PATH";

    /**
     * Directories the model may never write into, by NAME (any depth).
     *
     * <p>★ WHY BY NAME AND NOT BY PATH ★ a VCS directory or a package
     * directory is identified by its name wherever it appears; a path
     * prefix would have to be enumerated per project.
     */
    public static final Set<String> DENIED_DIRS = Set.of(
            ".git", ".svn", ".hg", "node_modules", "target", "build", "out",
            "dist", "bin", "obj", ".venv", "venv", "__pycache__", ".mypy_cache",
            ".pytest_cache", ".tox", ".gradle", ".idea", ".vscode", ".next",
            ".nuxt", ".cache", ".mvn", "coverage", ".terraform");

    /**
     * The files that constitute a build system, and the words in a request
     * that make creating one legitimate.
     *
     * <p>★ WHY THE MARKERS ARE PER FILE ★ "add a build" is not enough:
     * a project that uses Gradle must not get a {@code pom.xml}, and a
     * project that uses npm must not get a {@code CMakeLists.txt}. The
     * marker list says which build system the user actually asked for.
     */
    public static final Map<String, List<String>> SCAFFOLD_FILES = Map.ofEntries(
            Map.entry("pom.xml", List.of("maven", "pom.xml", "pom ", "mvn")),
            Map.entry("build.gradle", List.of("gradle")),
            Map.entry("build.gradle.kts", List.of("gradle")),
            Map.entry("settings.gradle", List.of("gradle")),
            Map.entry("settings.gradle.kts", List.of("gradle")),
            Map.entry("package.json", List.of("package.json", "npm", "node.js", "node ")),
            Map.entry("Makefile", List.of("makefile")),
            Map.entry("CMakeLists.txt", List.of("cmake")),
            Map.entry("build.xml", List.of("ant ", "build.xml")),
            Map.entry("Cargo.toml", List.of("cargo", "rust")),
            Map.entry("pyproject.toml", List.of("pyproject", "poetry", "pip ")),
            Map.entry(".gitignore", List.of("gitignore", "git ignore")));

    /** The session's working directory — the boundary of W1. */
    private final Path root;

    /** The request that is being served, lower-cased (for W3's markers). */
    private final String request;

    /** Is the boundary switched off? */
    private final boolean escapeActive;

    public WriteScope(Path root, String request) {
        this(root, request, allowAnyPath());
    }

    /**
     * A scope with the escape hatch stated explicitly.
     *
     * <p>★ WHY THE FLAG IS A PARAMETER ★ the escape hatch is an
     * environment variable, and this machine has it set at the USER
     * level — so a test that read the variable would silently measure the
     * ESCAPED behaviour and pass while the boundary was doing nothing.
     * A test must be able to pin BOTH states, and a class that can only
     * read one of them cannot.
     */
    public WriteScope(Path root, String request, boolean escapeActive) {
        this.root = root == null ? null : root.toAbsolutePath().normalize();
        this.request = request == null ? "" : request.toLowerCase(Locale.ROOT);
        this.escapeActive = escapeActive;
    }

    /**
     * The scope for this call.
     *
     * <p>★ THE SCOPE IS BUILT FROM THE SESSION, NOT FROM THE TOOL ★ the
     * tools ask "where may I write", and the answer belongs to the
     * session (its cwd and its current request), not to each tool.
     *
     * <p>★ A SCOPE IN THE CONTEXT WINS ★ the engine (or a test) can put a
     * scope in the extras, and then that scope is used verbatim. Without
     * that seam, a test could only measure the behaviour of the machine
     * it runs on — and this machine has
     * {@code AETHERCODE_ALLOW_ANY_PATH=1} set at the USER level, so every
     * tool-level test would be measuring the ESCAPED behaviour.
     */
    public static WriteScope fromContext(Tool.CallContext ctx) {
        if (ctx != null) {
            Object installed = ctx.extra(EXTRAS_KEY);
            if (installed instanceof WriteScope s) return s;
        }
        Path cwd = SessionPaths.cwdOf(ctx);
        return new WriteScope(cwd, currentRequest(ctx), allowAnyPath());
    }

    /** The session's working directory. */
    public Path root() {
        return root;
    }

    /**
     * The request being served — the last USER message of the session.
     *
     * <p>★ WHY THE LAST ONE ★ the last user message is the request the
     * turn is answering; earlier ones belong to earlier turns.
     */
    public static String currentRequest(Tool.CallContext ctx) {
        if (ctx == null) return "";
        Object appState = ctx.extra("app_state");
        if (!(appState instanceof AppState st)) return "";
        List<Message> transcript = st.transcript();
        if (transcript == null) return "";
        for (int i = transcript.size() - 1; i >= 0; i--) {
            Message m = transcript.get(i);
            if (m == null || m.role() != Role.USER) continue;
            StringBuilder sb = new StringBuilder();
            for (ContentBlock b : m.content()) {
                if (b instanceof ContentBlock.TextBlock t && t.text() != null) sb.append(t.text()).append('\n');
            }
            return sb.toString();
        }
        return "";
    }

    /**
     * May this write happen?
     *
     * @param target the absolute, normalized path the write would land on
     */
    public ScopeDecision check(Path target) {
        if (target == null) {
            return ScopeDecision.deny(null, VERB, "the path could not be resolved");
        }
        // ★ THE RULES ARE EVALUATED EVEN WHEN THE BOUNDARY IS OFF ★
        // that is the whole point of the escaped state: the write
        // happens, and the caller is told which rule it broke. Silently
        // allowing it would make the escape invisible.
        ScopeDecision violation = violationOf(target);
        if (violation == null) return ScopeDecision.allow(target, VERB);
        if (escapeActive) return ScopeDecision.escaped(target, VERB, violation.reason());
        return violation;
    }

    /**
     * The rule this target breaks, or null when it breaks none.
     *
     * <p>★ THE THREE RULES, IN ONE PLACE ★ so the escaped path and the
     * enforced path cannot disagree about what a rule says.
     */
    private ScopeDecision violationOf(Path target) {
        // ---- W1: the tree is the boundary --------------------------------
        if (root != null && !SessionPaths.isInside(target, root)) {
            return ScopeDecision.deny(target, VERB,
                    target + " is outside the working directory (" + root
                            + "). Work inside the tree the session is pointed at.");
        }
        // ---- W2: tool-owned directories ---------------------------------
        String denied = deniedDirectoryOf(target);
        if (denied != null) {
            return ScopeDecision.deny(target, VERB,
                    target + " is inside '" + denied + "/', which the tooling owns. "
                            + "Write the change where it belongs instead.");
        }
        // ---- W3: do not invent a build system ----------------------------
        return checkScaffold(target);
    }

    /**
     * W3: creating a build file (or a {@code .gitignore}) in a project that
     * has none is scaffolding.
     *
     * <p>★ WHY "AT THE ROOT" ★ a multi-module project legitimately has
     * {@code module/pom.xml} under a root that has one too; the failure
     * being prevented is a project that has NO build suddenly acquiring
     * one.
     *
     * @return the refusal, or null when the write is fine
     */
    private ScopeDecision checkScaffold(Path target) {
        Path file = target.getFileName();
        if (file == null) return null;
        String name = file.toString();
        List<String> markers = SCAFFOLD_FILES.get(name);
        if (markers == null) return null;
        // Only at the project root: a nested build file belongs to a
        // module, not to the project.
        Path parent = target.getParent();
        if (root == null || parent == null) return null;
        if (!SessionPaths.key(parent).equals(SessionPaths.key(root))) return null;
        // The project already has a build: adding another file of the same
        // kind is a normal edit, not an invention.
        if (projectHasBuild()) return null;
        // The request names this build system: the user asked for it.
        for (String marker : markers) {
            if (request.contains(marker)) return null;
        }
        return ScopeDecision.deny(target, VERB,
                "this project has no build system, and '" + name + "' would be one. "
                        + "Write the code that was asked for and say that there is "
                        + "nothing here to run it with; do not add a build system nobody asked for.");
    }

    /** Does the project root already contain a build file of any kind? */
    private boolean projectHasBuild() {
        if (root == null) return false;
        for (String name : SCAFFOLD_FILES.keySet()) {
            try {
                if (Files.exists(root.resolve(name))) return true;
            } catch (Exception ignored) {
                // an unreadable root cannot prove a build exists
            }
        }
        return false;
    }

    /** The denied directory the path is inside, or null. */
    private String deniedDirectoryOf(Path target) {
        Path p = target;
        while (p != null) {
            Path name = p.getFileName();
            if (name != null) {
                String n = name.toString();
                if (DENIED_DIRS.contains(n)) return n;
            }
            p = p.getParent();
        }
        return null;
    }

    /** Has the user opted out of the boundary? */
    public static boolean allowAnyPath() {
        return "1".equals(System.getenv(ALLOW_ANY_PATH_ENV));
    }

    /**
     * Is THIS scope running with the boundary switched off?
     *
     * <p>★ WHY THE ENGINE ASKS ★ the escape hatch is an environment
     * variable, and a variable that is set on the user's machine makes the
     * boundary invisible: the writes are unrestricted and nothing says so.
     * The engine asks, and says so out loud.
     */
    public boolean isEscapeActive() {
        return escapeActive;
    }
}
