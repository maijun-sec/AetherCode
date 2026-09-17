"""Verify release jar contains the R277 isAskMode fix.

Use this AFTER `mvn package` but BEFORE copying the jar into the
release/ directory. R277 added two new public methods on
AetherCodeMethods — `isAskMode()` and `currentPermissionModeName()` —
and a guard inside JsonRpcPermissionPrompter that calls
`!methods.isAskMode()`. The bytecode for these is what we
check here.

Why this matters: `mvn package` can use incremental cache and
rebuild a shaded jar that LOOKS fresh (new SHA, new timestamp)
but is actually missing the new class entries — maven reuses
the class files from a previous build. A `mvn clean package`
forces the new class files into the shade. We hit this exact
bug after R277: the jar in release/ had SHA `4BA099C3…` and
was 56,586,422 B, but did NOT contain `isAskMode` (56,586,688 B
with `isAskMode` is the correct shape).
"""
import sys
import zipfile

JAR = r'D:\work\workspace\idea\engine\AetherCode\release\aethercode-0.2.70\desktop\aethercode.jar'

with zipfile.ZipFile(JAR) as z:
    classes = {n: z.read(n) for n in z.namelist() if n.endswith('.class')}

problems = []
checks = [
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
print(f'  contains isAskMode + currentPermissionModeName in AetherCodeMethods.class')
print(f'  JsonRpcPermissionPrompter.class references isAskMode')
print('R277 mode-as-source-of-truth fix is present in the shipped jar.')