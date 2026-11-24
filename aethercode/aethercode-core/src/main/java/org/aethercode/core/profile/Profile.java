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

package org.aethercode.core.profile;

import java.util.Set;

/**
 * R401: a MAIN-loop posture — "what kind of turn is this, and therefore
 * which tools may it touch?"
 *
 * <h2>Why a separate type from {@code SubagentRole.RolePreset}</h2>
 * <p>The shape is deliberately identical to
 * {@code org.aethercode.core.agent.SubagentRole.RolePreset} (a
 * system-prompt preamble + an allow-list + a deny-list), but they are
 * DIFFERENT things and they live in different registries on purpose:
 *
 * <ul>
 *   <li>a <b>Profile</b> is the MAIN agent's posture for ONE turn. It is
 *       chosen by the intent classifier and it decides what the primary
 *       loop may do.</li>
 *   <li>a <b>RolePreset</b> is "who I sent out". It is chosen by
 *       {@code spawn_agent} for a CHILD agent.</li>
 * </ul>
 *
 * <p>Merging the two into one class would create the second copy of a
 * truth — the exact failure mode R378/R380 documented for the TUI's
 * help text and command list. Two registries, two lifecycles, two
 * injection sites.
 *
 * <h2>Why the tool set matters more than the prompt</h2>
 * <p>A prompt instruction ("don't write files") is soft: the model can
 * ignore it, and the user's working tree pays for it. The tool set is
 * STRUCTURAL: {@link ProfileRegistry#prune} removes the tool from the
 * pool BEFORE the model ever sees the tool table, so a pruned tool is
 * not callable at all. The prompt says WHY; the tool set makes it true.
 *
 * @see ProfileRegistry
 * @see org.aethercode.core.intent.IntentClassifier
 */
public record Profile(
        /** stable identifier — the wire token and the override value. */
        String name,
        /** human label (Chinese — the UI is Chinese). */
        String label,
        /** one-line description for the UI's profile picker. */
        String description,
        /** the per-turn directive appended to the system prompt.
         *  This is the ONLY place a posture's prompt text lives — the
         *  classifier names the kind, the profile owns the wording. */
        String systemPrompt,
        /** {@code null} = no allow-list (every tool not denied is
         *  allowed). Otherwise the exact set of tools that survive the
         *  prune. */
        Set<String> allowedTools,
        /** tools removed from the pool even when an allow-list admits
         *  them. Empty = nothing denied. */
        Set<String> deniedTools) {

    public Profile {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("profile name is required");
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("profile label is required: " + name);
        }
        // Defensive copies; null stays null (it means "unrestricted").
        if (allowedTools != null) allowedTools = Set.copyOf(allowedTools);
        if (deniedTools != null) deniedTools = Set.copyOf(deniedTools);
    }

    /** does this profile remove anything from the pool? A profile that
     *  prunes nothing is not worth selecting — it only carries a prompt. */
    public boolean prunes() {
        return allowedTools != null || (deniedTools != null && !deniedTools.isEmpty());
    }
}
