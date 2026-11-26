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

import java.nio.file.Path;

/**
 * R416: the ONE verdict shape both scopes produce.
 *
 * <h2>★ WHY IT IS NOT NESTED IN EACH SCOPE ★</h2>
 * <p>R415's {@code WriteScope} carried its own {@code Decision} record.
 * R416 adds a read scope, and the two would then own two copies of the
 * same three states — allow / deny / <b>escaped</b> — with the same
 * "escape notice" behaviour. R380's lesson applies: one "thing" that is
 * several things drifts, and the drift is invisible. So the verdict
 * lives here, once, and both scopes produce it.
 *
 * <h2>★ THE ESCAPED STATE IS NOT OPTIONAL ★</h2>
 * <p>The escape hatch ({@code AETHERCODE_ALLOW_ANY_PATH=1}) is an
 * environment variable, and on this machine it is set at the USER
 * level: the scope never refuses anything, and the reads/writes look
 * exactly like the ones the scope allows. A boundary that silently
 * stops being a boundary is worse than no boundary — because the user
 * believes in it. So a broken rule that was allowed anyway SAYS SO.
 *
 * @param allowed may this happen?
 * @param resolved where it would land
 * @param reason why it was refused (null when allowed); when the scope
 *               is ESCAPED, it is what WOULD have refused it
 * @param verb    what was refused — "write" or "read". It is part of
 *                the message the model sees, and a read refusal that
 *                says "write refused" sends the model looking for the
 *                wrong thing.
 */
public record ScopeDecision(boolean allowed, Path resolved, String reason, String verb) {

    public static ScopeDecision allow(Path p, String verb) {
        return new ScopeDecision(true, p, null, verb);
    }

    public static ScopeDecision deny(Path p, String verb, String reason) {
        return new ScopeDecision(false, p, reason, verb);
    }

    /**
     * A call that broke a rule but was ALLOWED — because the user
     * switched the boundary off.
     *
     * @param p where it would land
     * @param verb what was allowed
     * @param reason what the rules would have said
     */
    public static ScopeDecision escaped(Path p, String verb, String reason) {
        return new ScopeDecision(true, p, reason, verb);
    }

    /**
     * The message the MODEL sees.
     *
     * <p>★ IT MUST SAY WHAT TO DO NEXT ★ a refusal that only says "no"
     * makes the model guess; a refusal that names the rule and the
     * next action lets it comply in one step.
     */
    public String message() {
        if (allowed) return null;
        return verb + " refused: " + reason;
    }

    /**
     * The note that says "the boundary was off, and this call broke
     * it anyway" — or null when there is nothing to say.
     *
     * <p>★ SILENCE WOULD BE A LIE ★ an allowed call that the rules
     * would have refused must say so, because the user is looking at
     * a transcript that says "read 400 lines" and has no way to know
     * the scope was not applied.
     */
    public String escapeNotice() {
        if (!allowed || reason == null) return null;
        return "note: the " + verb + " scope is disabled ("
                + WriteScope.ALLOW_ANY_PATH_ENV + "=1), so this " + verb
                + " was allowed even though " + reason + ".";
    }

    /**
     * The result body, with the escape note appended when there is
     * one.
     *
     * @param body the body the tool produced
     * @return the body to hand back
     */
    public String applyNotice(String body) {
        String notice = escapeNotice();
        if (notice == null) return body;
        String base = body == null ? "" : body;
        return base + "\n\n" + notice;
    }
}
