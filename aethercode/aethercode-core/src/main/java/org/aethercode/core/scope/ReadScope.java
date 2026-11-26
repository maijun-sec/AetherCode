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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * R416: the READ SCOPE — the other half of the boundary.
 *
 * <h2>★ WHAT R415 MEASURED ★</h2>
 * <p>R415 drew a hard boundary around WRITES and left reads alone.
 * The survey behind it found what that means in practice:
 *
 * <ul>
 *   <li>{@code grep} and {@code glob} had <b>no boundary check at all</b>
 *       — their {@code base_dir} could point anywhere on the
 *       filesystem, and the model used exactly that to go looking for a
 *       build system outside the tree (R415's K8: "glob upward for
 *       pom.xml", which found the bench harness itself);</li>
 *   <li>all three read tools resolved a relative path against the
 *       JVM's {@code user.dir} — the directory the daemon happened to
 *       start in — so a session pointed at {@code D:/tmp/proj} asking
 *       for {@code src/Foo.java} read
 *       {@code <daemon launch dir>/src/Foo.java}. R415 fixed the
 *       write side of that; the read side kept the defect.</li>
 * </ul>
 *
 * <h2>★ THE THREE RULES ★</h2>
 * <ul>
 *   <li><b>R1 — the tree is the boundary.</b> A read outside the
 *       session's working directory is refused, with the same escape
 *       hatch the write scope honours ({@code
 *       AETHERCODE_ALLOW_ANY_PATH=1}) and the same honest notice when
 *       the hatch is open.</li>
 *   <li><b>R2 — the engine's own offload root stays readable.</b> When
 *       a tool result is too big for the wire, the engine writes it to
 *       a file and <b>tells the model to {@code file_read} that
 *       file</b>. A read scope that refused that path would break a
 *       working feature (R377's lesson: a refusal table that denies a
 *       capability the daemon actually has is worse than no table).</li>
 *   <li><b>R3 — a symlink may not leave the tree.</b> The path says
 *       "inside"; the file it points at does not. A boundary that a
 *       symlink defeats is not a boundary (the same shape as the
 *       one-letter prefix bug R415 found).</li>
 * </ul>
 *
 * <h2>★ WHAT THE READ SCOPE DOES NOT DO ★</h2>
 * <p>It does not deny the tool-owned directories the write scope denies.
 * Reading {@code .git/config} or a build report under {@code target/}
 * is legitimate work; writing into them is not. The two scopes have
 * different rules ON PURPOSE, and they share only the tree boundary,
 * the escape hatch and the verdict type.
 *
 * <p>★ AND IT DOES NOT CONSTRAIN THE SHELL ★ {@code bash} can read
 * anything ({@code cat ../secret}); no path check can constrain a
 * command line. That needs an OS-level sandbox and is not this class's
 * job.
 */
public final class ReadScope {

    /** The key the scope travels under in {@link Tool.CallContext#extras()}. */
    public static final String EXTRAS_KEY = "aethercode.read_scope";

    /** The verb used in the refusal message. */
    public static final String VERB = "read";

    /** The session's working directory — the boundary of R1. */
    private final Path root;

    /** Is the boundary switched off? */
    private final boolean escapeActive;

    public ReadScope(Path root) {
        this(root, WriteScope.allowAnyPath());
    }

    /**
     * A scope with the escape hatch stated explicitly.
     *
     * <p>★ WHY THE FLAG IS A PARAMETER ★ the escape hatch is an
     * environment variable, and this machine has it set at the USER
     * level — so a test that read the variable would silently measure
     * the ESCAPED behaviour and pass while the boundary was doing
     * nothing. A test must be able to pin BOTH states.
     */
    public ReadScope(Path root, boolean escapeActive) {
        this.root = root == null ? null : root.toAbsolutePath().normalize();
        this.escapeActive = escapeActive;
    }

    /**
     * The scope for this call.
     *
     * <p>★ A SCOPE IN THE CONTEXT WINS ★ the engine (or a test) can put
     * a scope in the extras, and then that scope is used verbatim.
     */
    public static ReadScope fromContext(Tool.CallContext ctx) {
        if (ctx != null) {
            Object installed = ctx.extra(EXTRAS_KEY);
            if (installed instanceof ReadScope s) return s;
        }
        return new ReadScope(SessionPaths.cwdOf(ctx));
    }

    /** The session's working directory. */
    public Path root() {
        return root;
    }

    /** Is THIS scope running with the boundary switched off? */
    public boolean isEscapeActive() {
        return escapeActive;
    }

    /**
     * May this read happen?
     *
     * @param target the absolute, normalized path the read would land on
     */
    public ScopeDecision check(Path target) {
        if (target == null) {
            return ScopeDecision.deny(null, VERB, "the path could not be resolved");
        }
        ScopeDecision violation = violationOf(target);
        if (violation == null) return ScopeDecision.allow(target, VERB);
        if (escapeActive) return ScopeDecision.escaped(target, VERB, violation.reason());
        return violation;
    }

    /**
     * The rule this target breaks, or null when it breaks none.
     *
     * <p>★ THE RULES ARE EVALUATED EVEN WHEN THE BOUNDARY IS OFF ★
     * that is the whole point of the escaped state: the read happens,
     * and the caller is told which rule it broke.
     */
    private ScopeDecision violationOf(Path target) {
        // ---- R2: the engine's own offload root stays readable ----------
        Path offload = SessionPaths.offloadRoot();
        if (offload != null && SessionPaths.isInside(target, offload)) {
            return null;
        }
        // ---- R1: the tree is the boundary -------------------------------
        // ★ R1 IS CHECKED BEFORE R3 ★ the ORDER is the diagnosis. R3 is
        // the link rule, and its message says "a link may not lead out
        // of the tree" — which is only true when the LITERAL path says
        // "inside" and the file it points at does not. A plain path that
        // simply IS outside the tree is not a link, and telling the
        // model it is one sends it looking for the wrong thing (R383's
        // lesson: the diagnosis decides what the model does next).
        //
        // ★ MEASURED ★ R416's live matrix produced the wrong message: the
        // model globbed one directory above the sandbox and was told
        // "points at …, which is outside the working directory (…). A
        // link may not lead out of the tree." There was no link.
        if (root != null && !SessionPaths.isInside(target, root)) {
            return ScopeDecision.deny(target, VERB,
                    target + " is outside the working directory (" + root
                            + "). Read inside the tree the session is pointed at; "
                            + "if the user needs a file from elsewhere, ask them to open the scope.");
        }
        // ---- R3: a symlink may not leave the tree -----------------------
        // The literal path is inside (R1 passed) ⇒ the only way this can
        // still be outside is a link.
        Path real = realPathOf(target);
        if (real != null && root != null && !SessionPaths.isInside(real, root)) {
            Path offloadReal = offload == null ? null : realPathOf(offload);
            boolean insideOffload = offloadReal != null && SessionPaths.isInside(real, offloadReal);
            if (!insideOffload) {
                return ScopeDecision.deny(target, VERB,
                        target + " points at " + real + ", which is outside the working directory ("
                                + root + "). A link may not lead out of the tree.");
            }
            return null;
        }
        return null;
    }

    /**
     * The REAL path of a target — symlinks resolved — or null when it
     * cannot be resolved (the file does not exist yet, or the OS
     * refuses).
     *
     * <p>★ WHY TO REAL PATH AND NOT TO NORMALIZE ★ normalize() is a
     * STRING operation: it removes {@code ..} and duplicate
     * separators, and knows nothing about links. A path that says
     * {@code tree/link.txt} and points at {@code C:/Users/me/secret.txt}
     * survives normalize() unchanged — and would sail past a boundary
     * that only compares the literal path.
     */
    private static Path realPathOf(Path target) {
        try {
            if (!Files.exists(target)) return null;
            return target.toRealPath();
        } catch (Exception e) {
            // an unreadable path cannot prove anything; the literal-path
            // rule still applies
            return null;
        }
    }

    /**
     * The directory the engine offloads large tool results into, or
     * null when there is none.
     *
     * <p>★ ONE IMPLEMENTATION ★ the executor resolved this on its own;
     * the read scope needs the same answer, and two answers to one
     * question drift.
     */
    static Path offloadRoot() {
        return SessionPaths.offloadRoot();
    }
}
