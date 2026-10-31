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

package org.aethercode.permission.prompt;

import org.aethercode.permission.grants.GrantDecision;
import org.aethercode.permission.grants.GrantScope;

import java.util.Objects;
import java.util.Optional;

/**
 * T-250..T-251 / spec.md §3.3 / design.md §3.4: a single
 * entry in the 10-option consent prompt.
 *
 * <p>The prompt renders one row per option. The TUI uses the
 * Java record's fields directly (label, hotkey, scope). The
 * desktop drop-down does the same. The runtime reacts to a
 * selected option by:
 * <ol>
 *   <li>creating a {@code Grant} with the option's scope +
 *       decision + the prompt's primary category, AND</li>
 *   <li>immediately returning the matching allow/deny without
 *       further prompting (the runtime still consults the
 *       in-memory session memory for ephemeral "once" options).</li>
 * </ol>
 *
 * <p>Options 1-8 are the "standard" set (4 scopes × 2 outcomes);
 * options 9-10 are the "category-specific" wildcard pair. The
 * wildcard pair uses {@code CATEGORY_SCOPE} and the wildcard
 * sub-category the categorizer emits (e.g.
 * {@code shell.package_install} for an {@code npm install} call).
 *
 * <p>The {@code kind} field is the discriminator so the
 * TUI can render the 9-10 pair with a "all {@code <category>}"
 * suffix rather than a fixed label.
 */
public record ConsentOption(
        int index,
        OptionKind kind,
        String label,
        String hotkey,
        GrantScope scope,
        GrantDecision decision,
        String wildcardSubCategory
) {

    public ConsentOption {
        if (index < 1) throw new IllegalArgumentException("index must be >= 1");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(hotkey, "hotkey");
        Objects.requireNonNull(decision, "decision");
    }

    /** Is this a category-wildcard option (9/10)? */
    public boolean isWildcard() {
        return kind == OptionKind.WILDCARD;
    }

    /** The scope the runtime should write to. For
     *  {@link OptionKind#ONCE} the runtime does NOT write any
     *  grant — it consults {@code SessionMemory} instead. The
     *  scope is still populated (as {@code SESSION}) so the
     *  JSON log can record "ephemeral-session" for analytics. */
    public Optional<GrantScope> persistedScope() {
        return kind == OptionKind.ONCE
                ? Optional.of(GrantScope.SESSION)  // for the audit log only
                : Optional.of(scope);
    }
}
