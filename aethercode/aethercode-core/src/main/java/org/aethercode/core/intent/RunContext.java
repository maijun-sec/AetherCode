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

package org.aethercode.core.intent;

import java.util.List;

import org.aethercode.core.profile.Profile;
import org.aethercode.core.tool.Tool;

/**
 * R401: the posture of ONE user turn — the intent, the profile that
 * posture selected, and the tool pool that profile leaves behind.
 *
 * <h2>Why this is a value, not engine state</h2>
 * <p>The engine re-enters itself: {@code spawn_agent} calls back into
 * {@code AetherCodeEngine.query(...)} on the SAME
 * {@code QueryEngine} instance. If the posture lived in a field, the
 * child's turn would overwrite the parent's mid-run, and the parent's
 * remaining turns would silently run under the child's posture.
 *
 * <p>So the posture is a VALUE that is created once, at the start of a
 * turn, and captured by the turn's Spliterator. The parent and the
 * child each hold their own; neither can disturb the other.
 *
 * <h2>What null means</h2>
 * <ul>
 *   <li>{@code intent == null} — the turn did not come from a user
 *       prompt (a subagent's inner loop, a test). No posture, no
 *       directive, no pruning.</li>
 *   <li>{@code profile == null} — same.</li>
 *   <li>{@code toolPool == null} — "use the session's pool as it is".
 *       A NON-null pool is already pruned; the engine must not prune it
 *       twice (the second prune would be harmless but it would hide
 *       which layer did the work).</li>
 * </ul>
 */
public record RunContext(IntentClassifier.Intent intent, Profile profile, List<Tool> toolPool) {

    /** the "no posture" context — the pre-R401 behaviour. */
    public static RunContext none() {
        return new RunContext(null, null, null);
    }

    public RunContext {
        if (toolPool != null) toolPool = List.copyOf(toolPool);
    }

    /** is this a real, steered turn? */
    public boolean hasPosture() {
        return intent != null && profile != null;
    }
}
