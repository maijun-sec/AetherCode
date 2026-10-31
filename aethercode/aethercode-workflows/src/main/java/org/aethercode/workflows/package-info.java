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

/**
 * Phase 2.1 of the AetherCode APP spec (design.md §3.6, spec.md §11):
 * the workflow engine. A workflow is a reusable, named prompt template
 * authored in YAML. The engine loads, validates, and runs it; it
 * substitutes {@code {{inputs.X}}}, {@code {{cwd}}}, {@code {{date}}},
 * and {@code {{os}}} placeholders; composes skills into the system
 * prompt; and hands the resolved prompt to the supervisor for spawn.
 *
 * <p>Top-level types:
 * <ul>
 *   <li>{@link org.aethercode.workflows.Workflow} — the parsed YAML
 *       record (schema v1).</li>
 *   <li>{@link org.aethercode.workflows.WorkflowLoader} — SnakeYAML
 *       → {@link org.aethercode.workflows.Workflow}.</li>
 *   <li>{@link org.aethercode.workflows.WorkflowValidator} —
 *       checks inputs, skills existence, prompt count, limits.</li>
 *   <li>{@link org.aethercode.workflows.WorkflowEngine} — runs a
 *       workflow and returns a {@link org.aethercode.workflows.SessionRef}.</li>
 *   <li>{@link org.aethercode.workflows.VariableSubstitution} —
 *       resolves {@code {{...}}} placeholders.</li>
 *   <li>{@link org.aethercode.workflows.SkillComposer} — turns
 *       {@link org.aethercode.workflows.Workflow#skills()} into a
 *       system-prompt-suffix.</li>
 *   <li>{@link org.aethercode.workflows.WorkflowPaths} — resolves
 *       project vs. user workflow locations.</li>
 * </ul>
 */
package org.aethercode.workflows;
