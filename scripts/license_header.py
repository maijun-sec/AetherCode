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
"""license_header.py — the licence header, in one place, for the PYTHON tools.

WHY A SHARED SOURCE

The header text is defined ONCE, in `scripts/license-header-common.mjs`, and
the JavaScript inserter renders it. The Python auditor has to know the
EXACT same text — otherwise the gate demands a header the inserter never
writes, and the failure looks like "every file is wrong" rather than
"the gate is out of date". That is how a licence check quietly stops
being a licence check.

So the auditor READS the canonical text out of the JavaScript module
rather than keeping its own copy. There is then exactly one place to
change when the wording changes.

The parse is deliberately strict: if the module's shape is not the one
this reader expects, the reader RAISES rather than guessing. A silent
mismatch would report a wrong header shape, which is exactly the failure
mode this whole arrangement exists to prevent.
"""

import io
import os
import re

SCRIPTS_DIR = os.path.dirname(os.path.abspath(__file__))
COMMON_MODULE = os.path.join(SCRIPTS_DIR, "license-header-common.mjs")

AUTHOR = "maijun"
YEAR = "2026"
MARKER = "Copyright (c) %s %s" % (YEAR, AUTHOR)


def _read_common_module():
    with io.open(COMMON_MODULE, encoding="utf-8") as f:
        return f.read()


def _parse_mit_body(src):
    """Pull the MIT_BODY array out of the JavaScript module."""
    m = re.search(r"export const MIT_BODY = \[(.*?)\n\];", src, re.S)
    if not m:
        raise ValueError("MIT_BODY not found in %s" % COMMON_MODULE)
    body = m.group(1)
    lines = []
    for raw in body.split("\n"):
        raw = raw.strip()
        if not raw:
            continue
        # The array's last element carries a trailing comma.
        if raw.endswith(","):
            raw = raw[:-1].rstrip()
        if raw.startswith("'") and raw.endswith("'"):
            lines.append(raw[1:-1].replace('\\"', '"'))
        elif raw.startswith('"') and raw.endswith('"'):
            lines.append(raw[1:-1].replace('\\"', '"'))
        else:
            raise ValueError("unrecognised MIT_BODY line: %r" % raw)
    return lines


def header_lines():
    """The canonical header body: copyright line, blank, MIT text."""
    src = _read_common_module()
    return [MARKER, ""] + _parse_mit_body(src)


def block_header_lines():
    """The header as a plain block comment, one entry per line."""
    return ["/*"] + [
        (" *" if l == "" else " * " + l) for l in header_lines()
    ] + [" */"]


def line_header_lines(prefix):
    """The header as a run of line comments, one entry per line."""
    return [
        (prefix.rstrip() if l == "" else prefix + l) for l in header_lines()
    ]


if __name__ == "__main__":
    for line in block_header_lines():
        print(line)
