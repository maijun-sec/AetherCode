/**
 * Hooks v2 plumbing for the {@code deepagents-code} Java port.
 *
 * <p>This package is the Java 21 native translation of the Python
 * {@code deepagents_code.hooks} module. It hosts the Hooks v2 client
 * facade, configuration loader, snapshot model, command-handler runner,
 * reducer, permission/permission/HITL bridges, server-side middleware
 * stubs, and the legacy hook dispatcher retained for backward
 * compatibility.</p>
 *
 * <p>Public types are intentionally grouped by responsibility:</p>
 * <ul>
 *   <li><b>Domain model</b> — {@link HookEvent}, {@link HookContext},
 *       {@link HookInvocation}, {@link HookDomainEvents}, and the
 *       supporting records in {@link WireTypes}, {@link HookConfigTypes},
 *       and {@link HookTransportTypes}.</li>
 *   <li><b>Configuration &amp; loading</b> —
 *       {@code load_hooks_config} and the {@code HooksSource} hierarchy.</li>
 *   <li><b>Runtime &amp; engine</b> — {@code HooksRuntime}, the
 *       {@code HookEngine} orchestrator, and the
 *       {@code run_command_handler} command subprocess runner.</li>
 *   <li><b>Lifecycle &amp; lifecycle services</b> — {@code ClientHookService},
 *       {@code HooksManager}, and the server-side middleware facade.</li>
 *   <li><b>Reduction &amp; projection</b> — {@code reduce_hook_results},
 *       {@code project_hook_input}, and the
 *       {@code HookEnvelopeAdapter} boundary.</li>
 *   <li><b>Trust &amp; migration</b> — the {@code WorkspaceTrust} policy
 *       and the legacy-config migration adapter.</li>
 *   <li><b>Transcripts &amp; interrupts</b> — the {@code TranscriptStore}
 *       and the {@code HookFulfillmentLedger} used to deduplicate
 *       server-owned interrupt fulfillment.</li>
 *   <li><b>Legacy &amp; validation</b> — the legacy hook dispatcher and
 *       terminal-sequence validator.</li>
 * </ul>
 *
 * <p>Python pydantic models (e.g. {@code HookEvent} in
 * {@code deepagents_code.hooks.models.domain}) are translated to Java
 * records and sealed interfaces here; the wire types in
 * {@link WireTypes} preserve the exact JSON shape that the
 * Claude-compatible protocol expects.</p>
 */
package org.aethercode.code.hooks;
