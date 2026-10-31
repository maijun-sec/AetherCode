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

package org.aethercode.idea.context

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import org.aethercode.idea.context.OpenFilesContext
import org.aethercode.idea.context.PluginIntegrations
import org.aethercode.idea.context.ProjectContext
import org.aethercode.idea.context.ThemeManager

/**
 * R7: project-open startup activity. Instantiates the four
 * integration services eagerly so their listeners attach
 * before the user has a chance to open a file or switch LaF.
 *
 * <p>The chat panel already calls {@link OpenFilesContext#getInstance}
 * and {@link ThemeManager#getInstance} in its init, so this activity
 * primarily benefits {@link ProjectContext} and {@link PluginIntegrations}
 * — services that the chat panel doesn't directly need but downstream
 * R8+ surfaces do.
 *
 * <p>Services are still lazy-instantiated by IntelliJ; calling
 * {@code getInstance} here just moves the lazy init to project
 * open rather than first UI touch. No global state is allocated
 * until then.
 */
class IntegrationStartupActivity : StartupActivity {

    private val log = logger<IntegrationStartupActivity>()

    override fun runActivity(project: Project) {
        try {
            ProjectContext.getInstance(project)
            OpenFilesContext.getInstance(project)
            ThemeManager.getInstance()
            PluginIntegrations.refresh()
            log.info("R7 integration services online for project ${project.name}")
        } catch (e: Exception) {
            log.warn("failed to start R7 integration services: ${e.message}", e)
        }
    }
}
