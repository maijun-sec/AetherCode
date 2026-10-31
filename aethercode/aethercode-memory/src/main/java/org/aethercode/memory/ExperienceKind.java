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

package org.aethercode.memory;

/**
 * R230 (G1): kind of experiential memory.
 *
 * <p>Mirrors the arXiv:2512.13564 §4.2 division:
 * <ul>
 *   <li>{@link #CASE} — case-based: a concrete trajectory or solution
 *       from one specific past query (akin to ExpeL, Memento).</li>
 *   <li>{@link #STRATEGY} — strategy-based: an abstracted reasoning
 *       pattern / workflow / heuristic distilled from one or more
 *       trajectories (akin to Reflexion, ReasoningBank, AWM).</li>
 *   <li>{@link #SKILL} — skill-based: a callable procedural unit
 *       (function snippet, MCP, tool template) (akin to Voyager,
 *       SkillWeaver, Memp).</li>
 * </ul>
 *
 * <p>Note: actual "code/MCP" execution is a separate concern handled
 * by the tool runtime. The {@link #SKILL} kind here stores a
 * <i>description</i> of the skill (when to use, signature, examples)
 * rather than the code itself; the actual call goes through the
 * existing tool pool.
 */
public enum ExperienceKind {
    /** Raw trajectory / solution. High fidelity, low abstraction. */
    CASE,
    /** Abstracted strategy / workflow / heuristic. */
    STRATEGY,
    /** Callable skill description (no executable body in R230). */
    SKILL;

    public static ExperienceKind parseOrDefault(String s) {
        if (s == null || s.isBlank()) return CASE;
        try { return ExperienceKind.valueOf(s.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { return CASE; }
    }

    public String wire() { return name().toLowerCase(); }
}
