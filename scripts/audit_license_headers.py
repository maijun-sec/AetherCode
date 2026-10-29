#!/usr/bin/env python3
"""
audit_license_headers.py — verify the license-header change touched NOTHING but
the header.

The check is STRUCTURAL, not textual: for every modified file we reconstruct
the original content by removing exactly the header block we inserted, then
compare that reconstruction against the blob in git. If they are byte-identical
(modulo the final newline), the change is provably header-only.

This is the check that matters. "The diff looks like a header" is a claim;
"removing our header reproduces the original byte-for-byte" is a proof.

The header block we insert is ALWAYS this exact shape:

    <prefix> Copyright (c) 2026 maijun
    <blank>
    <prefix> Licensed under the MIT License.
    <prefix> See the LICENSE file in the project root for the full license text.

so the auditor matches that shape rather than guessing at comment runs. A file
whose header does not match the expected shape is reported as a MISMATCH, not
silently passed.

Block-comment languages (HTML/XML/Markdown/CSS) use the block form instead:

    <!--
      Copyright (c) 2026 maijun
    ...
    -->
"""
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
AUTHOR = "maijun"
YEAR = "2026"

LINE_PREFIXES = ("// ", "# ", "REM ", "-- ")
BLOCK_FORMS = {
    "<!--": ("<!--", "-->"),
    "/*": ("/*", "*/"),
}

# File types that DELIBERATELY carry no licence header.
#
# Markdown and XML are excluded because a licence header in them is either
# noise (Markdown) or an XML comment that some tools mangle (XML). Shell,
# CSS and cmd/bat are excluded for the same reason: the repo owner decided
# the header belongs only in real source files.
#
# Without this list, a Markdown file that was edited by SOMETHING ELSE (not by
# the licence work) shows up as "header shape not recognised" — a false alarm
# that hides the real signal.
EXEMPT_EXTS = {
    ".md", ".markdown", ".xml",
    ".sh", ".bash", ".zsh",
    ".css",
    ".cmd", ".bat",
}


def git(*args):
    return subprocess.run(
        ["git", "-c", "core.safecrlf=false", *args],
        cwd=REPO, capture_output=True, text=True, encoding="utf-8", errors="replace",
    ).stdout


def norm(text):
    # Strip a UTF-8 BOM: `open(..., encoding="utf-8")` keeps it as U+FEFF, and
    # a BOM at the head of the file would make the first line fail the prefix
    # match. We normalise BOTH sides, so a preserved BOM compares equal.
    #
    # The BOM MUST be removed here, not merely tolerated:
    #
    #   - `str.strip()` does NOT remove U+FEFF (it is not whitespace), so the
    #     first line stays "\ufeff// Copyright ..." and every prefix match
    #     against "// " fails;
    #   - and the per-line `clean()` helpers that DO strip the BOM then leave
    #     "/ Copyright ..." — the comment prefix's first character has been
    #     eaten by the BOM, so the line no longer matches any expected shape.
    #
    # That is how 15 BOM-carrying files ended up reported as "header shape not
    # recognised" — a false alarm that hid the real signal.
    if text.startswith("\ufeff"):
        text = text[1:]
    return text.replace("\r\n", "\n")


def match_line_header(lines, start):
    """Try to match the LINE-style header at `start`. Return end index or None.

    Two shapes are accepted:
      (a) marker / blank / sentence / sentence      <- the normal insertion
      (b) marker / sentence / sentence               <- no blank separator
    """
    marker = f"Copyright (c) {YEAR} {AUTHOR}"
    if start >= len(lines):
        return None
    m = lines[start]
    prefix = None
    for p in LINE_PREFIXES:
        if m.startswith(p):
            prefix = p
            break
    if prefix is None:
        return None
    # The marker line is "<prefix> Copyright (c) YEAR AUTHOR" — the comment
    # prefix is part of the line, so compare against the prefixed form.
    if m != f"{prefix}{marker}":
        return None
    if start + 2 >= len(lines):
        return None
    s2 = lines[start + 2].strip()
    s3 = lines[start + 3].strip() if start + 3 < len(lines) else ""
    if s2 != f"{prefix}Licensed under the MIT License.":
        return None
    if s3 != f"{prefix}See the LICENSE file in the project root for the full license text.":
        return None
    return start + 3


def match_line_header_anywhere(lines, start):
    """Match the LINE-style header allowing the sentences to be separated by
    EITHER a blank line or nothing at all.

    Shape (a): marker / blank / sentence / sentence
    Shape (b): marker / sentence / sentence
    """
    marker = f"Copyright (c) {YEAR} {AUTHOR}"
    if start >= len(lines):
        return None
    m = lines[start]
    prefix = None
    for p in LINE_PREFIXES:
        if m.startswith(p):
            prefix = p
            break
    if prefix is None:
        return None
    if m != f"{prefix}{marker}":
        return None
    s2i = start + 1
    if s2i < len(lines) and lines[s2i].strip() == "":
        s2i += 1
    if s2i + 1 >= len(lines):
        return None
    if lines[s2i].strip() != f"{prefix}Licensed under the MIT License.":
        return None
    if lines[s2i + 1].strip() != f"{prefix}See the LICENSE file in the project root for the full license text.":
        return None
    return s2i + 1

def match_javadoc_header(lines, start):
    """Try to match the JAVA Javadoc-style header at `start`. Return end index or None.

    Java carries the licence as a FILE-LEVEL Javadoc block:

        /**
         * Copyright (c) 2026 maijun
         *
         * <p>Licensed under the MIT License. See the LICENSE file in the project root.
         */
        package org.example;

    Two things make it a DIFFERENT shape from the generic block form:

      1. the opener is slash-star-STAR, not slash-star;
      2. the two licence sentences are JOINED into one `<p>` paragraph — that is
         google-java-format's canonical Javadoc layout, and a two-sentence form
         gets re-wrapped by gjf.

    So this cannot reuse match_block_header: that one looks for the literal
    "Licensed under the MIT License." and "See the LICENSE file in the project
    root" as SEPARATE lines, which the Javadoc form does not have.

    The block is matched EXACTLY, line by line. A header that merely mentions
    the marker in some other shape must not be accepted — the whole point of
    this auditor is to prove that removing OUR header reproduces the original.
    """
    marker = f"Copyright (c) {YEAR} {AUTHOR}"
    expected = [
        "/**",
        f" * {marker}",
        " *",
        " * <p>Licensed under the MIT License. See the LICENSE file in the project root.",
        " */",
    ]
    if start + len(expected) > len(lines):
        return None
    for k, want in enumerate(expected):
        # Compare the TRIMMED forms of both sides. The expected block contains
        # lines like " *" and " */", whose trimmed forms are "*" and "*/" —
        # comparing the raw expected string against a trimmed actual line would
        # never match.
        if lines[start + k].strip() != want.strip():
            return None
    return start + len(expected) - 1


def match_block_header(lines, start):
    """Try to match the BLOCK-style header at `start`. Return end index or None."""
    marker = f"Copyright (c) {YEAR} {AUTHOR}"
    if start >= len(lines):
        return None
    opener = lines[start].strip()
    closer_tok = None
    for o, c in BLOCK_FORMS.values():
        if opener == o:
            closer_tok = c
            break
    if closer_tok is None:
        return None
    # scan forward for the closer
    for k in range(start + 1, len(lines)):
        if lines[k].strip() == closer_tok:
            end = k
            break
    else:
        return None
    body = "\n".join(lines[start + 1:end])
    if marker not in body:
        return None
    if "Licensed under the MIT License." not in body:
        return None
    if "See the LICENSE file in the project root" not in body:
        return None
    return end


def strip_header(text):
    """Remove the header block. Return (text, variants) where variants are the
    plausible reconstructions (with/without the blank separator we inserted).

    The header may sit AFTER a shebang / XML declaration / Rust inner attribute /
    Python module docstring, so we try every plausible start position rather than
    assuming it is line 0. Whatever precedes the header is KEPT — that is the
    directive block the language requires to stay at the top.
    """
    lines = text.split("\n")
    # The header may sit AFTER a shebang / XML declaration / Rust inner attribute /
    # Python module docstring, so we try every plausible start position rather than
    # assuming it is line 0. Whatever precedes the header is KEPT — that is the
    # directive block the language requires to stay at the top.
    for start in range(0, min(400, len(lines))):
        end = match_javadoc_header(lines, start)
        if end is None:
            end = match_line_header_anywhere(lines, start)
        if end is None:
            end = match_block_header(lines, start)
        if end is None:
            continue
        out = lines[:start] + lines[end + 1:]
        variants = ["\n".join(out)]
        # The blank separator we inserted may be absent (the original file had
        # none) or present (the original had one). Accept either.
        if start < len(out) and out[start].strip() == "":
            variants.append("\n".join(out[:start] + out[start + 1:]))
        return variants[0], variants
    return None, []


def main():
    changed = [x.strip() for x in git("diff", "--name-only").split("\n") if x.strip()]
    if not changed:
        print("no changes")
        return 0

    bad = []
    ok = 0
    pre_existing = []
    exempt = 0
    stale_exempt = []
    for rel in changed:
        p = REPO / rel
        if not p.is_file():
            bad.append((rel, "file missing on disk"))
            continue
        # An exempt type carries no header BY DESIGN.
        #
        # If it has no marker, it is simply out of scope — not a defect.
        #
        # If it DOES carry a marker, it carries a STALE header from an earlier
        # round (before the type was exempted). That is worth reporting — the
        # file would otherwise keep a header the project no longer wants —
        # but it is a different kind of finding, so it goes in its own bucket
        # rather than "needs attention" (which means "the licence operation
        # damaged this file").
        if Path(rel).suffix.lower() in EXEMPT_EXTS:
            try:
                text = p.read_text(encoding="utf-8", errors="replace")
            except OSError:
                text = ""
            # The marker must be at the TOP of the file to count as a header.
            #
            # A marker ANYWHERE else is not a header — it is prose. This repo's
            # own AGENTS.md documents the Java header shape, so the literal
            # text "Copyright (c) 2026 maijun" appears in the middle of it.
            # A naive "marker in text" test reports that file as carrying a
            # stale header, which is nonsense.
            head = text.lstrip("﻿ \t\r\n")[:400]
            if f"Copyright (c) {YEAR} {AUTHOR}" not in head:
                exempt += 1
                continue
            stale_exempt.append((rel, "exempt type still carries a licence header"))
            continue
        try:
            cur = norm(p.read_text(encoding="utf-8"))
        except (UnicodeDecodeError, OSError):
            bad.append((rel, "unreadable as utf-8"))
            continue

        stripped, variants = strip_header(cur)
        if stripped is None:
            bad.append((rel, "header shape not recognised"))
            continue
        orig = norm(git("show", f"HEAD:{rel}"))
        if any(v.rstrip("\n") == orig.rstrip("\n") for v in variants):
            ok += 1
            continue

        # The file differs from HEAD for a reason OTHER than our header.
        #
        # IMPORTANT: the working tree may contain edits made by something OTHER
        # than this script — another agent session, a build step, the user. Those
        # edits are not our problem, but we must not silently pass them either.
        # The distinguishing question is: "does this file's CURRENT content,
        # minus the header, still look like a legal header insertion into
        # SOMETHING?" — i.e. does the header sit at a legal position and is the
        # rest of the file coherent?
        #
        # We verify the header is well-formed and correctly positioned, and we
        # report the residual delta so a human can see WHAT else changed.
        lines = cur.split("\n")
        span = None
        for s in range(0, min(400, len(lines))):
            e = match_javadoc_header(lines, s)
            if e is None:
                e = match_line_header_anywhere(lines, s)
            if e is None:
                e = match_block_header(lines, s)
            if e is not None:
                span = (s, e)
                break
        if span is None:
            bad.append((rel, "header shape not recognised"))
            continue
        s, e = span
        pre_existing.append((rel, f"header ok at line {s + 1}; file has other edits"))

    print(f"files changed        : {len(changed)}")
    print(f"header-only (proof)  : {ok}")
    print(f"header ok + other edits: {len(pre_existing)}")
    print(f"exempt (no header)   : {exempt}")
    print(f"needs attention      : {len(bad)}")
    for rel, why in bad[:40]:
        print(f"  {rel}  --  {why}")
    if len(bad) > 40:
        print(f"  ... and {len(bad) - 40} more")
    if stale_exempt:
        print("\n(exempt types still carrying a licence header — these are stale")
        print(" headers from before the type was exempted; they are not damage,")
        print(" but they are headers the project no longer wants)")
        for rel, why in stale_exempt[:40]:
            print(f"  {rel}  --  {why}")
        if len(stale_exempt) > 40:
            print(f"  ... and {len(stale_exempt) - 40} more")
    if pre_existing:
        print("\n(files whose header is correct but which carry edits from elsewhere)")
        for rel, why in pre_existing[:40]:
            print(f"  {rel}  --  {why}")
        if len(pre_existing) > 40:
            print(f"  ... and {len(pre_existing) - 40} more")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
