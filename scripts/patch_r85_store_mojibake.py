#!/usr/bin/env python3
"""
R85 Phase 2 cleanup: repair mojibake damage in aethercode-desktop/src/store/index.ts
caused by earlier PowerShell Set-Content round-trips (UTF-8 → CP936 → UTF-8).

All replacements are PURE BYTE-LEVEL. The corrupted byte sequences are taken
directly from raw reads of the file, so we don't depend on whether Python
can decode the file as UTF-8 (sometimes it can, sometimes it can't).

Errors fixed (root causes only — cascade errors should resolve themselves):
  L547  `label` var: 🛠 emoji + "…" in second branch
  L585  `preview` : missing close-quote after "…"
  L603  `tool_result` content: broken `'✓ : '✓` ternary
  L607  `tool_result` activity label: same + 🛠
  L650  run_end awaiting msg: corrupted 🔔
  L762  daemon.disconnected: corrupted "…"
  L879  sendMessage summary: missing close-quote after "…"

Run: python scripts/patch_r85_store_mojibake.py
"""
import os

REPO = r'D:\work\workspace\idea\engine\AetherCode'
TARGET = os.path.join(REPO, r'aethercode-desktop\src\store\index.ts')

# Each entry: (corrupted_bytes, correct_bytes, description)
# Source: raw bytes captured from the file via hex dump. Verified.
PATCHES = [
    # hammer emoji (U+1F6E0, F0 9F 9B A0) -> corrupted to E9 A6 83 E6 95 A1 (6 bytes)
    (b'\xe9\xa6\x83\xe6\x95\xa1', b'\xf0\x9f\x9b\xa0', 'hammer emoji'),
    # ellipsis (U+2026, E2 80 A6) -> corrupted to E9 88 A5 EE 9B 86 (6 bytes)
    (b'\xe9\x88\xa5\xee\x9b\x86', b'\xe2\x80\xa6', 'ellipsis'),
    # bell emoji (U+1F514, F0 9F 94 94) -> corrupted to E9 88 B4 3F (4 bytes)
    (b'\xe9\x88\xb4\x3f', b'\xf0\x9f\x94\x94', 'bell emoji'),
    # ballot-x (U+2717, E2 9C 97) -> corrupted to E9 89 82 3F (4 bytes)
    (b'\xe9\x89\x82\x3f', b'\xe2\x9c\x97', 'ballot-x'),
]


def main():
    with open(TARGET, 'rb') as f:
        raw = f.read()

    total = 0
    for old, new, desc in PATCHES:
        n = raw.count(old)
        if n:
            raw = raw.replace(old, new)
            print(f'  [byte] {desc}: {old.hex()} -> {new.hex()} x {n}')
            total += n
        else:
            print(f'  [skip] {desc}: {old.hex()} not present')

    # Targeted structural fixes for cascaded syntax damage:
    # (a) L585 + L879: `+ '… : X;` -- missing close-quote between ellipsis
    #     and space.
    for f, r, desc in [
        (b"+ '\xe2\x80\xa6 : content;",  b"+ '\xe2\x80\xa6' : content;",  'L585 close-quote'),
        (b"+ '\xe2\x80\xa6 : input;",    b"+ '\xe2\x80\xa6' : input;",    'L879 close-quote'),
    ]:
        if f in raw:
            n = raw.count(f)
            raw = raw.replace(f, r)
            print(f'  [byte] {desc}: x {n}')
            total += n

    # (b) L603 + L607: broken `'✓ : '✓}` ternary -- original was `'✗' : '✓'`
    #     The ✗ was lost during PowerShell round-trip; the surrounding `' : '`
    #     got compressed to a single `'`. Restore the proper ternary.
    f = b"isError ? '\xe2\x9c\x93 : '\xe2\x9c\x93}"
    r = b"isError ? '\xe2\x9c\x97' : '\xe2\x9c\x93'}"
    if f in raw:
        n = raw.count(f)
        raw = raw.replace(f, r)
        print(f'  [byte] tool_result ternary fix: x {n}')
        total += n

    # (c) L547: `label` ternary's false branch currently ends with `…,`
    #     (after my previous run got the terminator wrong). The original
    #     intent was `…\``; -- closing backtick + semicolon, because
    #     L547 is a `const` declaration. (`;` terminates the statement.)
    f_label = b"${toolName}\xe2\x80\xa6\x60\x2c"  # current: `…\`,`
    r_label = b"${toolName}\xe2\x80\xa6\x60\x3b"  # correct: `…\`;`
    if f_label in raw:
        n = raw.count(f_label)
        raw = raw.replace(f_label, r_label)
        print(f'  [byte] L547 label backtick+semicolon: x {n}')
        total += n

    # (d) L650: `🔔Per-todo` is missing the space after the bell.
    f = b"'\xf0\x9f\x94\x94Per-todo"
    r = b"'\xf0\x9f\x94\x94 Per-todo"
    if f in raw:
        n = raw.count(f)
        raw = raw.replace(f, r)
        print(f'  [byte] L650 bell+space: x {n}')
        total += n

    # (e) L762: `Reconnecting…,\r` -- missing closing backtick. The original
    #     was `Reconnecting…\`,`. Add it back.
    f = b"Reconnecting\xe2\x80\xa6,"
    r = b"Reconnecting\xe2\x80\xa6\x60\x2c"  # `\`,`
    if f in raw:
        n = raw.count(f)
        raw = raw.replace(f, r)
        print(f'  [byte] L762 reconnecting backtick+comma: x {n}')
        total += n

    # (f) Cleanup: my previous patch run inserted an extra backslash before
    #     the closing backtick (bytes 5c 60 2c instead of 60 2c). Remove it.
    #     Anywhere a backslash is immediately followed by a backtick + comma
    #     in the context of a closing template literal, drop the backslash.
    f = b"\xe2\x80\xa6\x5c\x60\x2c"  # …\`,\r
    r = b"\xe2\x80\xa6\x60\x2c"      # …\`,r
    if f in raw:
        n = raw.count(f)
        raw = raw.replace(f, r)
        print(f'  [byte] remove spurious backslash: x {n}')
        total += n

    if total == 0:
        print('  [info] no changes made (file may already be clean)')
    else:
        with open(TARGET, 'wb') as f:
            f.write(raw)
        print(f'  [ok] wrote {len(raw)} bytes ({total} replacements applied)')

    print('done.')


if __name__ == '__main__':
    main()
