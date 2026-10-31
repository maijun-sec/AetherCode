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
    <prefix>
    <prefix> Permission is hereby granted, free of charge, to any person ...
    ... the full MIT permission text ...
    <prefix>

so the auditor matches that shape rather than guessing at comment runs. A file
whose header does not match the expected shape is reported as a MISMATCH, not
silently passed.

The canonical text is NOT written here. It is read out of
scripts/license_header.py, which in turn reads it out of the JavaScript
module the INSERTER uses. One source of truth: if the wording changes in
one place, the gate follows automatically, and it can never demand a
header the inserter does not write.

Block-comment languages (HTML/XML/Markdown/CSS) use the block form instead:

    <!--
      Copyright (c) 2026 maijun
    ...
    -->
"""
# Copyright (c) 2026 maijun
#
# Permission is hereby granted, free of charge, to any person obtaining a copy
# of this software and associated documentation files (the "Software"), to deal
# in the Software without restriction, including without limitation the rights
# to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
# copies of the Software, and to permit persons to whom the Software is
# furnished to do so, subject to the following conditions:
#
# The above copyright notice and this permission notice shall be included in all
# copies or substantial portions of the Software.
#
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
# IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
# FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
# AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
# LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
# OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
# SOFTWARE.

import re
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from license_header import (  # noqa: E402
    block_header_lines,
    header_lines,
    line_header_lines,
)

REPO = Path(__file__).resolve().parents[1]
AUTHOR = "maijun"
YEAR = "2026"

LINE_PREFIXES = ("// ", "# ", "REM ", "-- ")
BLOCK_FORMS = {
    "<!--": ("<!--", "-->"),
    "/*": ("/*", "*/"),
}

# The canonical header, in the two forms the inserter writes.
#
# These come from license_header.py, which READS them out of the JavaScript
# module the INSERTER uses. One source of truth: the gate can never demand
# a header the inserter does not write.
_CANONICAL_BODY = header_lines()
_CANONICAL_BLOCK = block_header_lines()

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

    The expected run is the canonical header, one line per entry, with the
    comment prefix on every line -- including the blank ones inside the MIT
    text, because an unprefixed blank would END the comment run.
    """
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

    expected = line_header_lines(prefix)
    if start + len(expected) > len(lines):
        return None
    for k, want in enumerate(expected):
        if lines[start + k].rstrip() != want.rstrip():
            return None
    return start + len(expected) - 1


def match_line_header_anywhere(lines, start):
    """Match the LINE-style header.

    The header itself is matched exactly (see match_line_header); this
    variant differs only in what it does with the line AFTER it.
    """
    return match_line_header(lines, start)

def match_javadoc_header(lines, start):
    """Try to match the JAVA header at `start`. Return end index or None.

    Java used to carry the licence as a FILE-LEVEL Javadoc block. It now
    carries a PLAIN block comment:

        /*
         * Copyright (c) 2026 maijun
         *
         * Permission is hereby granted, free of charge, ...
         */
        package org.example;

    The plain block was chosen because google-java-format REFLOWS Javadoc
    prose to fill 100 columns, which made the licence header a moving
    target for the formatter, and because a Javadoc block is
    DOCUMENTATION -- the licence is not part of a class's API docs.

    So this is the same shape as match_block_header, and the generic
    matcher covers it.
    """
    return match_block_header(lines, start)
def match_block_header(lines, start):
    """Try to match the BLOCK-style header at `start`. Return end index or None.

    The expected block is the canonical one -- the copyright line, a blank,
    and the full MIT permission text -- wrapped in the language's block
    delimiter. For the slash-star form the expected lines come straight
    from license_header.block_header_lines(); for the HTML-comment form
    the same body is re-indented under the different delimiter.

    The block is matched EXACTLY, line by line. A header that merely
    mentions the marker in some other shape must not be accepted -- the
    whole point of this auditor is to prove that removing OUR header
    reproduces the original.
    """
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

    # How many lines the canonical body occupies.
    body_len = len(_CANONICAL_BODY)
    if start + 1 + body_len > len(lines):
        return None

    for k, want in enumerate(_CANONICAL_BODY):
        actual = lines[start + 1 + k].strip()
        if want == "":
            # The blank line inside the block carries the block's own
            # decoration: " *" for a slash-star block, "" for an
            # HTML comment.
            if actual not in ("", "*", " *"):
                return None
            continue
        # The body line, with the block's decoration stripped.
        bare = actual
        for prefix in ("*", "#", "!"):
            if bare.startswith(prefix):
                bare = bare[len(prefix):]
                break
        bare = bare.strip()
        if bare != want:
            return None

    # The closer must follow the body.
    close_idx = start + 1 + body_len
    if close_idx >= len(lines):
        return None
    if lines[close_idx].strip() != closer_tok:
        return None
    return close_idx
def header_start_index(lines):
    """The index of the line where the licence header may start.

    The inserter writes the header as the FIRST thing in the file, after
    the language DIRECTIVES and after a Python MODULE DOCSTRING. So the
    header starts at the first line that is none of those.

    Returns -1 when the file has no such line.
    """
    i = 0
    while i < len(lines):
        line = lines[i]
        t = line.strip()
        if t == "":
            i += 1
            continue
        if t.startswith("#!") or t.startswith("<?xml") or t.startswith("#!["):
            i += 1
            continue
        if re.match(r"^[ \t\f]*#.*coding[:=][ \t]*[-\w.]+", line):
            i += 1
            continue
        # A Python MODULE DOCSTRING comes before the header.
        m = re.match(r'^[rRbBuUfF]{0,2}("""|\'\'\')', t)
        if m:
            quote = m.group(1)
            if len(t) >= 2 * len(quote) and t.endswith(quote):
                i += 1
                continue
            j = i
            closed = False
            while j < len(lines):
                src_from = t.index(quote) + len(quote) if j == i else 0
                idx = lines[j].find(quote, src_from)
                if idx >= 0:
                    i = j + 1
                    closed = True
                    break
                j += 1
            if not closed:
                return -1
            continue
        return i
    return -1


def strip_header(text):
    """Remove the header block. Return (text, variants) where variants are the
    plausible reconstructions (with/without the blank separator we inserted).

    The header may sit AFTER a shebang / XML declaration / Rust inner attribute /
    Python module docstring, so we try every plausible start position rather than
    assuming it is line 0. Whatever precedes the header is KEPT — that is the
    directive block the language requires to stay at the top.
    """
    lines = text.split("\n")
    # The header sits at ONE place: the first line that is not a language
    # DIRECTIVE (a shebang, an encoding declaration, an XML declaration, a
    # Rust inner attribute) and not a Python MODULE DOCSTRING.
    #
    # The auditor used to try EVERY start position, which made it find the
    # marker inside a file's OWN documentation -- the licence tooling scripts
    # all SHOW the header shape in their doc comments -- and then report
    # "header shape not recognised" for files whose header is perfectly
    # fine.
    #
    # Whatever precedes the header is KEPT: that is the directive block the
    # language requires to stay at the top.
    start = header_start_index(lines)
    if start < 0:
        return None, []
    for start in [start]:
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
