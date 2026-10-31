// Copyright (c) 2026 maijun
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

package org.aethercode.idea.backend

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.logger

/**
 * R7: drop the cached {@link AetherCodeBackend} so the next
 * {@link BackendManager#current} call re-runs selection. This
 * is the user-facing escape hatch for "I started the daemon
 * after AetherCode picked in-process mode" and similar
 * sequencing problems.
 *
 * <p>Bound to {@code Alt+Shift+B} via {@code plugin.xml}. The
 * action is intentionally global (not project-scoped) — the
 * backend is per-project but the re-select is cheap, and a
 * per-project shortcut is harder to discover.
 */
class ResetBackendAction : AnAction() {
    private val log = logger<ResetBackendAction>()
    override fun actionPerformed(e: AnActionEvent) {
        BackendManager.reset()
        log.info("backend cache reset by user")
    }
}
