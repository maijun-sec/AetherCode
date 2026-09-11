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
