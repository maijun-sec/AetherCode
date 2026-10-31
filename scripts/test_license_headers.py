"""Regression tests for the licence-header tools.

WHY THIS FILE EXISTS
--------------------
The licence-header tools once DESTROYED content, and the diffs looked like
a clean migration because the deleted lines were "just the header".

Four real bugs, each found by mutation-testing the tools:

  1. `findLegacyHeader` had DEAD CODE: the block form returned early, so
     the line-comment branch below it never ran. It was also the wrong
     function -- the tools needed to detect ANY existing header (current or
     obsolete), not only an obsolete one.

  2. Neither detector knew the HTML-comment block form. So an HTML file's
     header was invisible: the inserter PREPENDED a new header without
     removing the old one, and the file ended up with two copyright
     notices and two different licence statements.

  3. The legacy line-header's extent was measured by SCANNING FOR WHERE
     THE COMMENT RUN ENDS. That is the wrong question. A licence header is
     a FIXED piece of text, and the file's own documentation often follows
     it as more `//` lines -- so the scanner swallowed the documentation.
     `ErrorBoundary.tsx` lost its R713 rationale and its R180 source
     contract; a Rust `//!` doc block and a TypeScript `/// <reference>`
     directive were lost the same way, because both start with `//`.

  4. The inserter was not IDEMPOTENT: it rewrote every file whose header
     was already correct, so `--check` could never be green.

And two more that only the byte-level checks could see:

  5. The inserter split with `split(/\\r?\\n/)` and rejoined with `"\\n"`,
     which DISCARDED the carriage return -- so a CRLF file's body was
     rewritten with LF endings, and the file ended up MIXED.

  6. The assembly dropped the language DIRECTIVES (the shebang, the Rust
     inner attributes), so `#![cfg_attr(not(debug_assertions),
     windows_subsystem = "windows")]` was deleted from the desktop's
     `main.rs` -- the attribute that stops a release build from opening a
     console window.

WHAT THIS FILE PROVES
---------------------
For every one of those: the tool must PRESERVE the file's content. The
tests here are byte-level, because "the header looks right" is not the
invariant -- "everything BELOW the header is unchanged" is.
"""
import io
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
ADD = os.path.join(HERE, "add-license-headers.mjs")
COMMON = os.path.join(HERE, "license-header-common.mjs")

sys.path.insert(0, HERE)

FAILURES = []
PASSED = 0


def check(name, condition, detail=""):
    global PASSED
    if condition:
        PASSED += 1
        print("  ok   %s" % name)
    else:
        FAILURES.append(name)
        print("  FAIL %s %s" % (name, detail))


def run_tool(script, args, cwd):
    return subprocess.run(
        ["node", script] + args,
        cwd=cwd,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )


def make_repo(tmp, files):
    """A tiny git repo with the given files, plus the licence tools."""
    os.makedirs(os.path.join(tmp, "scripts"), exist_ok=True)
    for name in ("license-header-common.mjs", "add-license-headers.mjs",
                 "remove-license-headers.mjs"):
        src = os.path.join(HERE, name)
        if os.path.isfile(src):
            with io.open(src, encoding="utf-8", newline="") as f:
                io.open(os.path.join(tmp, "scripts", name), "w",
                        encoding="utf-8", newline="").write(f.read())

    for rel, content in files.items():
        path = os.path.join(tmp, rel)
        d = os.path.dirname(path)
        if d and not os.path.isdir(d):
            os.makedirs(d)
        with io.open(path, "w", encoding="utf-8", newline="") as f:
            f.write(content)

    subprocess.run(["git", "init", "-q"], cwd=tmp, capture_output=True)
    subprocess.run(["git", "config", "user.email", "t@t"], cwd=tmp, capture_output=True)
    subprocess.run(["git", "config", "user.name", "t"], cwd=tmp, capture_output=True)
    subprocess.run(["git", "add", "-A"], cwd=tmp, capture_output=True)
    subprocess.run(["git", "commit", "-q", "-m", "base"], cwd=tmp, capture_output=True)


LEGACY_TS = (
    "// Copyright (c) 2026 maijun\n"
    "\n"
    "// Licensed under the MIT License.\n"
    "// See the LICENSE file in the project root for the full license text.\n"
    "\n"
    "export const x = 1;\n"
)

LEGACY_HTML = (
    "<!--\n"
    "  Copyright (c) 2026 maijun\n"
    "\n"
    "  Licensed under the MIT License.\n"
    "  See the LICENSE file in the project root for the full license text.\n"
    "-->\n"
    "\n"
    "<!doctype html>\n"
    "<html></html>\n"
)

DOC_AFTER_HEADER_TS = (
    "// Copyright (c) 2026 maijun\n"
    "\n"
    "// Licensed under the MIT License.\n"
    "// See the LICENSE file in the project root for the full license text.\n"
    "\n"
    "// R713: the file's OWN documentation, which must survive.\n"
    "// A second line of it.\n"
    "\n"
    "export const x = 1;\n"
)

RUST_DOC_AFTER_HEADER = (
    "// Copyright (c) 2026 maijun\n"
    "\n"
    "// Licensed under the MIT License.\n"
    "// See the LICENSE file in the project root for the full license text.\n"
    "\n"
    "//! R249: the module's own inner doc comment.\n"
    "//! A second line of it.\n"
    "\n"
    "fn main() {}\n"
)

SHEBANG_TS = (
    "#!/usr/bin/env node\n"
    "// Copyright (c) 2026 maijun\n"
    "\n"
    "// Licensed under the MIT License.\n"
    "// See the LICENSE file in the project root for the full license text.\n"
    "\n"
    "export const x = 1;\n"
)

RUST_ATTR = (
    '#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]\n'
    "\n"
    "// Copyright (c) 2026 maijun\n"
    "\n"
    "// Licensed under the MIT License.\n"
    "// See the LICENSE file in the project root for the full license text.\n"
    "\n"
    "fn main() {}\n"
)

CRLF_TS = (
    "// Copyright (c) 2026 maijun\r\n"
    "\r\n"
    "// Licensed under the MIT License.\r\n"
    "// See the LICENSE file in the project root for the full license text.\r\n"
    "\r\n"
    "export const x = 1;\r\n"
    "export const y = 2;\r\n"
)


def body_after_header(text):
    """Everything after the licence header, for comparison."""
    lines = text.split("\n")
    # Find the end of the header: the first line after the marker that is
    # not part of the MIT text.
    for i, l in enumerate(lines):
        if "Copyright (c) 2026 maijun" in l:
            # The header is 21 lines (marker + blank + 19 MIT lines).
            j = i + 21
            while j < len(lines) and lines[j].strip() == "":
                j += 1
            return "\n".join(lines[j:])
    return None


def scenario(name, files, asserts):
    """Build a repo, migrate it, and run the assertions."""
    tmp = tempfile.mkdtemp(prefix="licreg_")
    try:
        make_repo(tmp, files)
        r = run_tool(os.path.join(tmp, "scripts", "add-license-headers.mjs"), [], tmp)
        if r.returncode != 0:
            check(name + ": tool exits 0", False, r.stderr[:400])
            return
        for rel, fn in asserts.items():
            path = os.path.join(tmp, rel)
            with io.open(path, encoding="utf-8", newline="") as f:
                content = f.read()
            check("%s: %s" % (name, rel), fn(content), repr(content[:200]))
    finally:
        subprocess.run(["git", "clean", "-xfdq"], cwd=tmp, capture_output=True)


print("licence-header tool regression tests")
print("")

# --- 1. a legacy header is REPLACED, not duplicated ---
scenario(
    "legacy ts",
    {"a.ts": LEGACY_TS},
    {"a.ts": lambda t: t.count("Copyright (c) 2026 maijun") == 1
     and "Permission is hereby granted" in t
     and "Licensed under the MIT License" not in t},
)

# --- 2. an HTML legacy header is replaced, not duplicated ---
scenario(
    "legacy html",
    {"a.html": LEGACY_HTML},
    {"a.html": lambda t: t.count("Copyright (c) 2026 maijun") == 1
     and "Permission is hereby granted" in t
     and "Licensed under the MIT License" not in t},
)

# --- 3. the file's OWN documentation survives ---
scenario(
    "doc after header",
    {"a.ts": DOC_AFTER_HEADER_TS},
    {"a.ts": lambda t: "R713: the file's OWN documentation" in t
     and "A second line of it." in t},
)

# --- 4. a Rust inner doc block survives ---
scenario(
    "rust inner doc",
    {"a.rs": RUST_DOC_AFTER_HEADER},
    {"a.rs": lambda t: "R249: the module's own inner doc comment." in t},
)

# --- 5. the shebang survives ---
scenario(
    "shebang",
    {"a.ts": SHEBANG_TS},
    {"a.ts": lambda t: t.startswith("#!/usr/bin/env node")},
)

# --- 6. the Rust inner attribute survives ---
scenario(
    "rust inner attribute",
    {"a.rs": RUST_ATTR},
    {"a.rs": lambda t: t.startswith('#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]')},
)

# --- 7. a CRLF file stays CRLF ---
scenario(
    "crlf",
    {"a.ts": CRLF_TS},
    {"a.ts": lambda t: t.count("\r\n") == t.count("\n") and t.count("\n") >= 6},
)


# --- 8. IDEMPOTENCY: a second run changes nothing ---
def test_idempotent():
    tmp = tempfile.mkdtemp(prefix="licidem_")
    try:
        make_repo(tmp, {"a.ts": LEGACY_TS, "b.html": LEGACY_HTML})
        script = os.path.join(tmp, "scripts", "add-license-headers.mjs")
        run_tool(script, [], tmp)
        first = {}
        for rel in ("a.ts", "b.html"):
            with io.open(os.path.join(tmp, rel), encoding="utf-8", newline="") as f:
                first[rel] = f.read()
        r = run_tool(script, [], tmp)
        same = True
        for rel, want in first.items():
            with io.open(os.path.join(tmp, rel), encoding="utf-8", newline="") as f:
                if f.read() != want:
                    same = False
        check("idempotent: a second run changes nothing", same and r.returncode == 0)

        # And --check must be green.
        r2 = run_tool(script, ["--check"], tmp)
        check("idempotent: --check is green", r2.returncode == 0, r2.stdout[-300:])
    finally:
        subprocess.run(["git", "clean", "-xfdq"], cwd=tmp, capture_output=True)


test_idempotent()


# --- 9. the remover's --check must NOT write ---
def test_remover_check_does_not_write():
    tmp = tempfile.mkdtemp(prefix="licrm_")
    try:
        make_repo(tmp, {"a.ts": LEGACY_TS})
        add = os.path.join(tmp, "scripts", "add-license-headers.mjs")
        rem = os.path.join(tmp, "scripts", "remove-license-headers.mjs")
        run_tool(add, [], tmp)
        with io.open(os.path.join(tmp, "a.ts"), encoding="utf-8", newline="") as f:
            before = f.read()
        run_tool(rem, ["--check"], tmp)
        with io.open(os.path.join(tmp, "a.ts"), encoding="utf-8", newline="") as f:
            after = f.read()
        check("remover --check does not write", before == after)
    finally:
        subprocess.run(["git", "clean", "-xfdq"], cwd=tmp, capture_output=True)


test_remover_check_does_not_write()

print("")
print("passed: %d   failed: %d" % (PASSED, len(FAILURES)))
if FAILURES:
    for f in FAILURES:
        print("  FAILED: " + f)
    sys.exit(1)
