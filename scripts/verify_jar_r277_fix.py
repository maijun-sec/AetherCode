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