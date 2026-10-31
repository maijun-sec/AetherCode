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

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Phase 2.1 / T-2-12 (design.md §3.6, spec.md §11.3): the
 * {@code aethercode workflow ...} CLI surface. Java-A owns
 * the five core subcommands ({@code list|show|run|new|edit});
 * this class is the parent that the {@code ac workflow lint}
 * subcommand (Java-B / T-2-12) hangs off. When Java-A wires
 * in their subcommands, the @Command(subcommands=...) list
 * below is the integration point.
 */
@Command(
        name = "workflow",
        mixinStandardHelpOptions = true,
        description = "Run, edit, and lint named workflow templates (T-2-08..T-2-12).",
        subcommands = {
                WorkflowListCommand.class,
                WorkflowShowCommand.class,
                WorkflowRunCommand.class,
                WorkflowNewCommand.class,
                WorkflowEditCommand.class,
                WorkflowLintCommand.class,
        }
)
public class WorkflowCommand {
    // no instance state — pure parent
}
