#!/usr/bin/env python3
"""
check_mixed_eol.py — a file must not contain BOTH CRLF and bare-LF endings.

A file with mixed line endings is broken in a way that looks harmless in a
spot check and is destructive in practice:

  - every diff of it shows the WHOLE file as rewritten, so a reviewer cannot
    see what actually changed;
  - a real change to one of its lines is invisible in the noise;
  - tools that split on newlines and re-join them (which is most of them)
    produce different bytes for the same logical content.

THE DEFECT THIS GATE WAS BORN FROM

The header inserter decided a file's line ending with
`text.includes("\r\n") ? "\r\n" : "\n"`. That asks "does this text contain a
CRLF ANYWHERE" — and the answer was always yes once the file already had a
CRLF header. So:

  run 1: LF file, no header  -> inserts a CRLF header   (mixed now)
  run 2: LF file, CRLF header -> "contains CRLF" -> CRLF  (still mixed)

The body never changed; the header just never stopped being wrong. 108 files
ended up in that state.

The fix is in the inserter (measure the BODY's ending, not the whole text).
This gate is the CHECK that the fix holds — and it also catches a mixed file
that arrived from anywhere else.

Usage: python -B scripts/check_mixed_eol.py [repo-root]
Exit 0 when clean, 1 when at least one file is mixed.
"""
import sys
from pathlib import Path

MARKER = b"Copyright (c) 2026 maijun"

SKIP_DIRS = {"node_modules", "target", ".git", "dist", ".pnpm", "build", "out"}

# The file types that can carry a licence header at all. Restricting the scan
# keeps the gate fast (the repo has tens of thousands of files) and keeps it
# focused on the files the header work actually touches.
EXTENSIONS = {
    ".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs",
    ".java", ".kt", ".kts", ".scala",
    ".py", ".rs", ".go", ".rb", ".php",
    ".c", ".h", ".cpp", ".hpp", ".cc", ".cs", ".swift",
    ".vue", ".svelte",
    ".json", ".yml", ".yaml", ".toml",
    ".sh", ".bash", ".zsh", ".css", ".scss", ".less", ".html",
    ".sql", ".md", ".xml",
}


def main():
    root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[1]
    mixed = []
    checked = 0

    for path in root.rglob("*"):
        if not path.is_file():
            continue
        if any(part in SKIP_DIRS for part in path.parts):
            continue
        if path.suffix.lower() not in EXTENSIONS:
            continue
        try:
            raw = path.read_bytes()
        except OSError:
            continue
        if MARKER not in raw:
            continue
        checked += 1
        crlf = raw.count(b"\r\n")
        lf = raw.count(b"\n") - crlf
        cr = raw.count(b"\r") - crlf
        if crlf and (lf or cr):
            mixed.append((path.relative_to(root).as_posix(), crlf, lf, cr))

    print(f"files with the licence header : {checked}")
    print(f"mixed line endings             : {len(mixed)}")
    for rel, crlf, lf, cr in mixed[:40]:
        print(f"  {rel}: CRLF={crlf} bareLF={lf} bareCR={cr}")
    if len(mixed) > 40:
        print(f"  ... and {len(mixed) - 40} more")

    if mixed:
        print(
            "\nA file with mixed line endings is a broken file: every diff of it\n"
            "shows the whole file as rewritten. The header inserter must measure\n"
            "the BODY's line ending, not the whole text -- see detectEol in\n"
            "scripts/license-header-common.mjs."
        )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
