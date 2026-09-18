"""Verify release jar contains the R277 isAskMode fix AND the R280
project-memory fix AND the R281 desktop SSD panel InteractiveRepl
+ SsdRunner emitPhaseStart + SsdCommand --interactive flag.

Run this AFTER `mvn clean package` (or `mvn package -DskipTests`)
and BEFORE copying the jar into release/. Each round's bytecode
marker is what we check here — the jar SHA / size is NOT a
reliable signal because maven-shade-plugin can re-package stale
class files via incremental cache (we hit this exact bug in R277;
see also R279).

R277 markers:
    AetherCodeMethods.class   — isAskMode + currentPermissionModeName methods
    JsonRpcPermissionPrompter.class — references isAskMode (guard)

R280 markers:
    LayeredMemoryStore.class — appendSessionChange (3-arg overload),
        writeProjectInfo, readProjectMemoryExcluding
    ProjectMemoryStore.class — the new plain-text store (PROJECT_MEMORY.md);
        the on-disk string PROJECT_MEMORY.md in this class is the
        filename sentinel and must be present
    AetherCodeEngine.class — buildProjectMemorySection (top-of-prompt
        injection). The method name appears as a string in the bytecode.
    MemoryMethods.class — appendSessionChange / setProjectInfo /
        readProjectMemory RPC handlers (the method-name strings appear
        in the dispatcher.register(...) call sites).

R281 markers:
    InteractiveRepl.class — the new JSONL wire protocol driver; the
        InteractiveRepl class itself, the "phase-list" / "phase-start"
        event literals, and the "revise" command key must be present.
    SsdRunner.class — recordRevision helper referenced (call site shows
        up in the bytecode as a method name string)
    SsdCommand.class — the new "interactive" CLI flag literal

R282 markers:
    ProviderSpec.class — the new hasApiKey() helper (the method
        name appears as a string in the bytecode; the apiKey()
        string is also present)
    AetherCodeMethods.class — listAvailableModels RPC handler
        (the method name appears in the dispatcher.register(...)
        call site as a string literal). Also "model/list" alias.
    HttpJsonRpcServer.class — case "listAvailableModels" / case
        "model/list" routing. The literal strings must be present.

R283 markers:
    CompactConfig.class — forContextWindow (the tier-default
        factory), compactAt + preserveTail (record component
        names appear in the bytecode)
    CompactConfig$Strategy.class — wire-stable strategy ids
        (summary8 / summary7 / summarySliding / disabled)
    CompactSpec.class — the YAML record with toConfig() helper
        that bridges YAML fields to the runtime CompactConfig
    ProviderSpec.class — compactFor(modelId) helper (per-model
        lookup that walks model → provider → DEFAULT)
    ModelSpec.class — the compact field on the per-model spec
        (the field name appears in the accessor's bytecode)
    QueryEngine.class — resolveCompactConfig / setCompactRegistry
        (the per-model gate plumbing)
    AetherCodeEngine.class — setCompactRegistry (the SDK
        plumbing that hands the registry over to QueryEngine)
    AetherCodeMethods.class — setProviderRegistry (the RPC
        handler that wires the registry on daemon startup)

R284 markers:
    SnapshotStore.class — saveForSession (the sessionId-keyed
        save path; the method name appears in the bytecode)
    SnapshotStore$Snapshot.class — the record carrying
        sessionId + compactionIndex + the message list
        (sessionId field name appears in the bytecode)
    QueryEngine.class — setSnapshotStore (the R284 wiring
        that lets the engine attribute snapshots to the
        active session)
    AetherCodeEngine.class — setSnapshotStore + snapshotStore
        (the SDK plumbing that hands the store over to
        QueryEngine)
    AetherCodeMethods.class — compactListSnapshots +
        compactGetSnapshot (the two RPC handlers the
        desktop MessageList calls when the user clicks
        "View original")
    HttpJsonRpcServer.class — case "compact/listSnapshots"
        + case "compact/getSnapshot" (the routing literals)

R285 markers:
    Variant.class — the variant record (low/medium/high/xhigh
        presets; the static final fields LOW/MEDIUM/HIGH/XHIGH
        appear in the bytecode, plus byName() alias helper)
    VariantSpec.class — the YAML-shape record with toVariant()
        that bridges YAML fields to runtime Variant
    ProviderSpec.class — variantFor(modelId, name) 3-tier
        fallback (model → provider → BUILTIN)
    ModelSpec.class — variants field on the per-model spec
        (the field name appears in the accessor bytecode)
    AetherCodeEngine.class — setVariant / getActiveVariant
        (the SDK plumbing that hands the active variant
        through to the engine state)
    AetherCodeMethods.class — switchVariant RPC handler
        (the method name appears in the dispatcher.register
        as a string literal)
    HttpJsonRpcServer.class — case "switchVariant" routing
        literal (the routing string)

R286 markers:
    AgentRegistry$AgentMeta.class — the record carries a
        `variant` field (the field name appears in the
        accessor bytecode). The shape is
        (name, description, displayName, model, variant,
        path, lastModifiedMs) — same as R283 with the
        new `variant` slot in the middle.
    AgentRegistry.class — writeAgentMd helper emits the
        `variant:` frontmatter line (the literal
        "variant" appears in the bytecode alongside
        the existing "model" literal)
    AetherCodeEngine.class — resolveVariantName static
        helper + subagentVariant builder field (the
        AETHERCODE_SUBAGENT_VARIANT env override path).
        The static method name appears as a string
        in the bytecode.
    AetherCodeMethods.class — writeAgent + listAgents +
        getAgentBody + createAgent + updateAgent all
        accept the variant field. The "variant" string
        appears in the createAgent / updateAgent
        params map. The new "AETHERCODE_SUBAGENT_VARIANT"
        literal (if env-driven wiring is included) is
        present.
"""
import sys
import zipfile

JAR = r'D:\work\workspace\idea\engine\AetherCode\release\aethercode-0.2.70\desktop\aethercode.jar'

with zipfile.ZipFile(JAR) as z:
    classes = {n: z.read(n) for n in z.namelist() if n.endswith('.class')}

problems = []
checks = [
    # ---- R277 ----
    (
        'org/aethercode/protocol/methods/AetherCodeMethods.class',
        b'isAskMode',
        'AetherCodeMethods must expose the isAskMode() helper (R277)',
    ),
    (
        'org/aethercode/protocol/methods/AetherCodeMethods.class',
        b'currentPermissionModeName',
        'AetherCodeMethods must expose currentPermissionModeName() (R277)',
    ),
    (
        'org/aethercode/protocol/permissions/JsonRpcPermissionPrompter.class',
        b'isAskMode',
        'JsonRpcPermissionPrompter must reference isAskMode (R277 guard)',
    ),
    # ---- R280 ----
    (
        'org/aethercode/memory/LayeredMemoryStore.class',
        b'appendSessionChange',
        'LayeredMemoryStore must expose appendSessionChange(cwd,sessionId,desc) (R280)',
    ),
    (
        'org/aethercode/memory/LayeredMemoryStore.class',
        b'writeProjectInfo',
        'LayeredMemoryStore must expose writeProjectInfo(cwd,info) (R280)',
    ),
    (
        'org/aethercode/memory/LayeredMemoryStore.class',
        b'readProjectMemoryExcluding',
        'LayeredMemoryStore must expose readProjectMemoryExcluding(cwd,excludeSid) (R280)',
    ),
    (
        'org/aethercode/memory/ProjectMemoryStore.class',
        b'PROJECT_MEMORY.md',
        'ProjectMemoryStore owns PROJECT_MEMORY.md; the filename literal must be present (R280)',
    ),
    (
        'org/aethercode/sdk/AetherCodeEngine.class',
        b'buildProjectMemorySection',
        'AetherCodeEngine must inject PROJECT_MEMORY.md at the top of the system prompt (R280)',
    ),
    (
        'org/aethercode/protocol/methods/MemoryMethods.class',
        b'appendSessionChange',
        'MemoryMethods must register appendSessionChange RPC (R280)',
    ),
    (
        'org/aethercode/protocol/methods/MemoryMethods.class',
        b'setProjectInfo',
        'MemoryMethods must register setProjectInfo RPC (R280)',
    ),
    (
        'org/aethercode/protocol/methods/MemoryMethods.class',
        b'readProjectMemory',
        'MemoryMethods must register readProjectMemory RPC (R280)',
    ),
    # ---- R281 ----
    (
        'org/aethercode/workflows/ssd/InteractiveRepl.class',
        b'phase-list',
        'InteractiveRepl must emit phase-list events (R281)',
    ),
    (
        'org/aethercode/workflows/ssd/InteractiveRepl.class',
        b'phase-draft',
        'InteractiveRepl must emit phase-draft events (R281)',
    ),
    (
        'org/aethercode/workflows/ssd/InteractiveRepl.class',
        b'revise',
        'InteractiveRepl must handle revise commands (R281)',
    ),
    (
        'org/aethercode/workflows/ssd/SsdRunner.class',
        b'recordRevision',
        'SsdRunner must call recordRevision after a revise (R281)',
    ),
    (
        'org/aethercode/cli/SsdCommand.class',
        b'interactive',
        'SsdCommand must register the --interactive flag (R281)',
    ),
    # ---- R282 ----
    (
        'org/aethercode/core/providers/ProviderSpec.class',
        b'hasApiKey',
        'ProviderSpec must expose hasApiKey() helper (R282)',
    ),
    (
        'org/aethercode/protocol/methods/AetherCodeMethods.class',
        b'listAvailableModels',
        'AetherCodeMethods must register listAvailableModels RPC (R282)',
    ),
    (
        'org/aethercode/protocol/methods/AetherCodeMethods.class',
        b'model/list',
        'AetherCodeMethods must register model/list alias (R282)',
    ),
    (
        'org/aethercode/protocol/http/HttpJsonRpcServer.class',
        b'listAvailableModels',
        'HttpJsonRpcServer must route listAvailableModels (R282)',
    ),
    # ---- R283 ----
    (
        'org/aethercode/core/compact/CompactConfig.class',
        b'forContextWindow',
        'CompactConfig must expose forContextWindow(ctx) tier-default factory (R283)',
    ),
    (
        'org/aethercode/core/compact/CompactConfig.class',
        b'compactAt',
        'CompactConfig record-component compactAt must be present (R283)',
    ),
    (
        'org/aethercode/core/compact/CompactConfig$Strategy.class',
        b'summary8',
        'Strategy enum must expose the summary8 wire id (R283)',
    ),
    (
        'org/aethercode/core/compact/CompactConfig$Strategy.class',
        b'disabled',
        'Strategy enum must expose the disabled wire id (R283 opt-out)',
    ),
    (
        'org/aethercode/core/providers/CompactSpec.class',
        b'toConfig',
        'CompactSpec must expose toConfig() to bridge YAML to CompactConfig (R283)',
    ),
    (
        'org/aethercode/core/providers/ProviderSpec.class',
        b'compactFor',
        'ProviderSpec must expose compactFor(modelId) lookup (R283)',
    ),
    (
        'org/aethercode/core/providers/ModelSpec.class',
        b'compact',
        'ModelSpec must carry a compact field for the per-model override (R283)',
    ),
    (
        'org/aethercode/core/engine/QueryEngine.class',
        b'resolveCompactConfig',
        'QueryEngine must resolve the per-model config (R283)',
    ),
    (
        'org/aethercode/core/engine/QueryEngine.class',
        b'setCompactRegistry',
        'QueryEngine must accept a compact registry at startup (R283)',
    ),
    (
        'org/aethercode/sdk/AetherCodeEngine.class',
        b'setCompactRegistry',
        'AetherCodeEngine must propagate compact registry to QueryEngine (R283)',
    ),
    (
        'org/aethercode/protocol/methods/AetherCodeMethods.class',
        b'setProviderRegistry',
        'AetherCodeMethods must register setProviderRegistry RPC (R283)',
    ),
    # ---- R284 ----
    (
        'org/aethercode/core/compact/SnapshotStore.class',
        b'saveForSession',
        'SnapshotStore must expose saveForSession (R284)',
    ),
    (
        'org/aethercode/core/compact/SnapshotStore$Snapshot.class',
        b'sessionId',
        'Snapshot record must carry sessionId field (R284)',
    ),
    (
        'org/aethercode/core/engine/QueryEngine.class',
        b'setSnapshotStore',
        'QueryEngine must accept setSnapshotStore (R284)',
    ),
    (
        'org/aethercode/sdk/AetherCodeEngine.class',
        b'setSnapshotStore',
        'AetherCodeEngine must propagate setSnapshotStore (R284)',
    ),
    (
        'org/aethercode/protocol/methods/AetherCodeMethods.class',
        b'compactListSnapshots',
        'AetherCodeMethods must register compactListSnapshots RPC (R284)',
    ),
    (
        'org/aethercode/protocol/methods/AetherCodeMethods.class',
        b'compactGetSnapshot',
        'AetherCodeMethods must register compactGetSnapshot RPC (R284)',
    ),
    (
        'org/aethercode/protocol/http/HttpJsonRpcServer.class',
        b'compact/listSnapshots',
        'HttpJsonRpcServer must route compact/listSnapshots (R284)',
    ),
    (
        'org/aethercode/protocol/http/HttpJsonRpcServer.class',
        b'compact/getSnapshot',
        'HttpJsonRpcServer must route compact/getSnapshot (R284)',
    ),
    # ---- R285 ----
    (
        'org/aethercode/core/providers/Variant.class',
        b'LOW',
        'Variant must expose the LOW preset (R285)',
    ),
    (
        'org/aethercode/core/providers/Variant.class',
        b'XHIGH',
        'Variant must expose the XHIGH preset (R285)',
    ),
    (
        'org/aethercode/core/providers/Variant.class',
        b'byName',
        'Variant must expose byName() alias resolver (R285)',
    ),
    (
        'org/aethercode/core/providers/VariantSpec.class',
        b'toVariant',
        'VariantSpec must expose toVariant() to bridge YAML to Variant (R285)',
    ),
    (
        'org/aethercode/core/providers/ProviderSpec.class',
        b'variantFor',
        'ProviderSpec must expose variantFor(modelId, name) 3-tier lookup (R285)',
    ),
    (
        'org/aethercode/core/providers/ModelSpec.class',
        b'variants',
        'ModelSpec must carry variants field for per-model overrides (R285)',
    ),
    (
        'org/aethercode/sdk/AetherCodeEngine.class',
        b'setVariant',
        'AetherCodeEngine must expose setVariant for active-variant plumbing (R285)',
    ),
    (
        'org/aethercode/sdk/AetherCodeEngine.class',
        b'getActiveVariant',
        'AetherCodeEngine must expose getActiveVariant accessor (R285)',
    ),
    (
        'org/aethercode/protocol/methods/AetherCodeMethods.class',
        b'switchVariant',
        'AetherCodeMethods must register switchVariant RPC (R285)',
    ),
    (
        'org/aethercode/protocol/http/HttpJsonRpcServer.class',
        b'switchVariant',
        'HttpJsonRpcServer must route switchVariant (R285)',
    ),
    # ---- R286 ----
    (
        'org/aethercode/core/agent/AgentRegistry$AgentMeta.class',
        b'variant',
        'AgentMeta record must carry a variant field (R286)',
    ),
    (
        'org/aethercode/core/agent/AgentRegistry.class',
        b'variant',
        'AgentRegistry.writeAgentMd must emit the variant: frontmatter line (R286)',
    ),
    (
        'org/aethercode/sdk/AetherCodeEngine.class',
        b'resolveVariantName',
        'AetherCodeEngine must expose the resolveVariantName static helper (R286)',
    ),
    (
        'org/aethercode/sdk/AetherCodeEngine.class',
        b'subagentVariant',
        'AetherCodeEngine.Builder must declare the subagentVariant field (R286)',
    ),
    (
        'org/aethercode/sdk/AetherCodeEngine.class',
        b'AETHERCODE_SUBAGENT_VARIANT',
        'AetherCodeEngine must consult the AETHERCODE_SUBAGENT_VARIANT env override (R286)',
    ),
]
for path, needle, msg in checks:
    data = classes.get(path)
    if data is None:
        problems.append(f'MISSING CLASS: {path}')
        continue
    if needle not in data:
        problems.append(
            f'{path}: missing {needle!r} — {msg}',
        )

if problems:
    print('JAR VERIFY FAILED:')
    for p in problems:
        print(f'  - {p}')
    sys.exit(1)

print(f'OK: {JAR}')
print(f'  R277: isAskMode + currentPermissionModeName in AetherCodeMethods.class')
print(f'        JsonRpcPermissionPrompter.class references isAskMode')
print(f'  R280: LayeredMemoryStore exposes appendSessionChange / writeProjectInfo / readProjectMemoryExcluding')
print(f'        ProjectMemoryStore owns PROJECT_MEMORY.md (plain-text)')
print(f'        AetherCodeEngine has buildProjectMemorySection (top-of-prompt inject)')
print(f'        MemoryMethods registers 3 new RPCs (appendSessionChange / setProjectInfo / readProjectMemory)')
print(f'  R281: InteractiveRepl (new class) emits phase-list / phase-draft and handles revise')
print(f'        SsdRunner calls recordRevision after a revise')
print(f'        SsdCommand registers the --interactive flag')
print(f'  R282: ProviderSpec exposes hasApiKey() helper (env-var-driven)')
print(f'        AetherCodeMethods registers listAvailableModels + model/list alias')
print(f'        HttpJsonRpcServer routes listAvailableModels')
print(f'  R283: CompactConfig.forContextWindow tier-default factory')
print(f'        Strategy enum exposes summary8 / summary7 / summarySliding / disabled wire ids')
print(f'        CompactSpec.toConfig() bridges YAML to runtime CompactConfig')
print(f'        ProviderSpec.compactFor(modelId) walks model → provider → DEFAULT')
print(f'        ModelSpec carries per-model compact field')
print(f'        QueryEngine resolves per-model config (setCompactRegistry + resolveCompactConfig)')
print(f'        AetherCodeEngine propagates compact registry to QueryEngine')
print(f'        AetherCodeMethods registers setProviderRegistry RPC')
print(f'  R284: SnapshotStore.saveForSession(sessionId, …) writes <sessionId>__<N>.json')
print(f'        Snapshot record carries sessionId + compactionIndex (monotonic per session)')
print(f'        QueryEngine + AetherCodeEngine propagate setSnapshotStore (R284)')
print(f'        AetherCodeMethods registers compactListSnapshots + compactGetSnapshot RPCs')
print(f'        HttpJsonRpcServer routes compact/listSnapshots + compact/getSnapshot')
print(f'  R285: Variant presets LOW/MEDIUM/HIGH/XHIGH + byName() alias resolver')
print(f'        VariantSpec.toVariant() bridges YAML to runtime Variant')
print(f'        ProviderSpec.variantFor(modelId, name) walks model → provider → BUILTIN')
print(f'        ModelSpec carries variants field for per-model overrides')
print(f'        AetherCodeEngine exposes setVariant + getActiveVariant (engine plumbing)')
print(f'        AetherCodeMethods registers switchVariant RPC')
print(f'        HttpJsonRpcServer routes switchVariant')
print(f'  R286: AgentMeta record carries the variant field for per-agent quality preset')
print(f'        AgentRegistry.writeAgentMd emits the variant: frontmatter line')
print(f'        AetherCodeEngine.resolveVariantName + subagentVariant field + AETHERCODE_SUBAGENT_VARIANT env override')