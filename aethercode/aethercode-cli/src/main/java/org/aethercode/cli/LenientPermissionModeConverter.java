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

package org.aethercode.cli;

import org.aethercode.core.permission.PermissionMode;
import picocli.CommandLine;

/**
 * R747: case- and shape-tolerant parser for {@link PermissionMode}.
 *
 * <p>External schedulers that drive AetherCode over the Claude Code
 * protocol force-inject {@code --permission-mode bypassPermissions} —
 * camelCase, the spelling Claude Code itself uses. Picocli's built-in enum
 * converter is case-sensitive and only accepts the constant names
 * ({@code BYPASS_PERMISSIONS}), so the stock converter rejects the
 * scheduler's spelling and aborts the process before any work happens.
 *
 * <p>That failure mode is invisible until a real run: compilation succeeds,
 * unit tests pass, and the mismatch only appears when a third party hands
 * us an argument string. Since the scheduler cannot be changed, the CLI
 * has to be the tolerant side.
 *
 * <p>Accepted spellings, all mapping onto the same seven modes:
 * <ul>
 *   <li>the constant name — {@code BYPASS_PERMISSIONS}</li>
 *   <li>camelCase — {@code bypassPermissions}, {@code askBeforeTool},
 *       {@code acceptEdits}</li>
 *   <li>lower case with spaces or dashes instead of underscores —
 *       {@code bypass-permissions}, {@code bypass permissions}</li>
 * </ul>
 *
 * <p>Unrecognised input falls back to {@link PermissionMode#BYPASS_PERMISSIONS}
 * with a warning rather than throwing. That default is the safe one for the
 * only caller that matters here: an unattended run that stops to ask a
 * question nobody can answer is worse than one that proceeds under the
 * mode the scheduler already requested.
 */
public final class LenientPermissionModeConverter implements CommandLine.ITypeConverter<PermissionMode> {

    @Override
    public PermissionMode convert(String value) {
        if (value == null) return PermissionMode.DEFAULT;
        String normalised = value.trim()
                .replace("-", "")
                .replace("_", "")
                .replace(" ", "")
                .toLowerCase(java.util.Locale.ROOT);
        for (PermissionMode mode : PermissionMode.values()) {
            if (mode.name().replace("_", "").toLowerCase(java.util.Locale.ROOT).equals(normalised)) {
                return mode;
            }
        }
        System.err.println("aethercode: unrecognised --permission-mode '" + value
                + "', falling back to BYPASS_PERMISSIONS");
        return PermissionMode.BYPASS_PERMISSIONS;
    }
}