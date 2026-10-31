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

package org.aethercode.permission;

import org.aethercode.core.permission.PermissionResult;
import org.aethercode.core.tool.Tool;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Hook the TUI / CLI implements to surface a permission ask to the user. The prompter
 * resolves with either an {@link PermissionResult.Allow} (possibly with rewritten input)
 * or a {@link PermissionResult.Deny}. If the prompter is {@code null}, the policy auto-allows
 * read-only tools and surfaces a deny to the orchestrator for everything else (which the
 * orchestrator surfaces to the model as a tool_result error).
 */
@FunctionalInterface
public interface ToolPermissionPrompter {
    CompletableFuture<PermissionResult> ask(Tool tool, Map<String, Object> input, String question);
}
