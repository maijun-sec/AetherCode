#!/usr/bin/env node
// Copyright (c) 2026 maijun
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

/**
 * add-license-headers.mjs — insert an MIT copyright header into every
 * first-party source file.
 *
 * Design notes (these are the reasons it is written this way, not decoration):
 *
 *  1. IDEMPOTENT. A file that already starts with the header is left byte-for-byte
 *     alone. Re-running the script must be a no-op, otherwise the header would
 *     keep growing on every run.
 *  2. LANGUAGE-AWARE. Each file type gets its own comment syntax. A `//` header
 *     inside an XML file is not a comment — it is text, and it would break the
 *     build.
 *  3. SHEBANG / XML DECLARATION FIRST. A file starting with `#!/usr/bin/env node`
 *     or `<?xml ... ?>` must keep that on line 1, so the header goes AFTER it.
 *  3a. PYTHON'S THREE LEADING-DIRECTIVE RULES. Python has three separate
 *     "must be first" rules that are easy to miss:
 *       - the shebang must be line 1;
 *       - a PEP 263 `# -*- coding: ... -*-` declaration must be on line 1 or 2;
 *       - a module docstring must be the FIRST STATEMENT (comments before it are
 *         fine, but a non-comment before it is not).
 *     A header inserted naively violates the second and third rules and silently
 *     breaks the file: Python then either mis-decodes the source (wrong encoding)
 *     or treats the docstring as a plain expression and the module loses its
 *     `__doc__`. So for `.py` we keep shebang + coding-declaration + docstring at
 *     the top and put the header AFTER them.
 *  3b. RUST INNER ATTRIBUTES. `#![...]` must precede every item in a file. A
 *     `//` comment before it is legal, but putting the header between the inner
 *     attribute and `fn main()` is not what we want either — so the header goes
 *     after the inner attributes, still before the first item.
 *  4. EMPTY-FILE SAFE. An empty file has nothing to comment on; we leave it alone
 *     rather than deciding what comment syntax a zero-byte file "is".
 *  5. NO BOM / NO LINE-ENDING CHURN. We detect the existing line ending and BOM
 *     and preserve both. A CRLF file must not silently become LF.
 *  6. EXPLICIT EXCLUSIONS. Vendored submodules, generated files, lockfiles and
 *     binary assets are excluded — putting a copyright header on someone else's
 *     code is both wrong and legally meaningless.
 *
 * Usage:
 *   node scripts/add-license-headers.mjs            # apply
 *   node scripts/add-license-headers.mjs --check    # report only, exit 1 if any file lacks the header
 *   node scripts/add-license-headers.mjs --dry-run  # report what would change
 */

import { readFileSync, writeFileSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { join, relative } from "node:path";
import {
  AUTHOR,
  YEAR,
  MARKER,
  MIT_BODY,
  HEADER_LINES,
  renderBlockHeader,
  renderLineHeader,
  detectEol,
  alreadyHasHeader,
} from "./license-header-common.mjs";

const REPO_ROOT = process.cwd();
const ORGANIZATION = "AetherCode";

/**
 * The canonical header body, WITHOUT comment markers.
 *
 * This is the ONE source of truth for the header text. Every language style
 * renders from these lines, so the wording can never drift between languages.
 */
/**
 * The canonical header body lives in `license-header-common.mjs` — ONE
 * source of truth, shared with the removal script and the LICENSE file.
 *
 * It carries the FULL MIT permission text, not a paraphrase. See MIT_BODY
 * there for why that matters legally.
 */

/**
 * Per-language comment styles.
 *   line:  prefix for each line, no terminator
 *   block: [open, close] wrapping the whole header
 *   styles: which of {line, block} this file type accepts
 */
const STYLE_BY_EXT = {
  // ---- C-family: // line comments, /* */ blocks ----
  java: { block: ["/*", "*/"] },
  kt: { line: "// " },
  kts: { line: "// " },
  ts: { line: "// " },
  tsx: { line: "// " },
  js: { line: "// " },
  jsx: { line: "// " },
  mjs: { line: "// " },
  cjs: { line: "// " },
  rs: { line: "// ", rust: true },
  go: { line: "// " },
  c: { line: "// " },
  h: { line: "// " },
  cpp: { line: "// " },
  hpp: { line: "// " },
  cs: { line: "// " },
  scala: { line: "// " },
  swift: { line: "// " },
  dart: { line: "// " },

  // ---- Shell family: # line comments ----
  sh: { line: "# " },
  bash: { line: "# " },
  zsh: { line: "# " },
  ps1: { line: "# " },
  psm1: { line: "# " },
  cmd: { line: "REM " },
  bat: { line: "REM " },
  yaml: { line: "# " },
  yml: { line: "# " },
  toml: { line: "# " },
  ini: { line: "; " },
  cfg: { line: "# " },
  gitignore: { line: "# " },
  npmrc: { line: "# " },
  editorconfig: { line: "# " },
  prettierignore: { line: "# " },
  gitmodules: { line: "# " },
  sample: { line: "# " },
  env: { line: "# " },
  properties: { line: "# " },
  gitattributes: { line: "# " },

  // ---- Python / Ruby / Perl ----
  py: { line: "# ", python: true },
  rb: { line: "# " },
  pl: { line: "# " },
  pm: { line: "# " },

  // ---- SQL ----
  sql: { line: "-- " },

  // ---- HTML-ish: block comments only ----
  html: { block: ["<!--", "-->"] },
  xml: { block: ["<!--", "-->"] },
  xsd: { block: ["<!--", "-->"] },
  svg: { block: ["<!--", "-->"] },
  vue: { block: ["<!--", "-->"] },

  // ---- CSS: /* */ block, but // is not a comment in plain CSS ----
  css: { block: ["/*", "*/"] },
  scss: { block: ["/*", "*/"] },
  less: { block: ["/*", "*/"] },

  // ---- Markdown / plain text: HTML comment keeps it invisible in rendered MD ----
  md: { block: ["<!--", "-->"] },
  mdx: { block: ["<!--", "-->"] },

  // ---- JSON has NO comment syntax at all ----
  // Handled specially below (JSON5 / "commented JSON" is NOT valid JSON).
  json: { none: true },

  // ---- Make ----
  make: { line: "# " },
  mk: { line: "# " },
};

/** Extensions we deliberately never touch. */
const NEVER_TOUCH_EXT = new Set([
  // Package manager lockfiles: generated, and rewriting them breaks installs.
  "lock",
  "png",
  "jpg",
  "jpeg",
  "gif",
  "ico",
  "svgz",
  "pdf",
  "zip",
  "gz",
  "tar",
  "jar",
  "class",
  "exe",
  "dll",
  "so",
  "dylib",
  "woff",
  "woff2",
  "ttf",
  "otf",
  "webp",
  "mp3",
  "mp4",
  "mov",
  "wasm",
  // Data / fixtures
  "jsonl",
  "ndjson",
  "csv",
  "tsv",
  // Random backup artifacts that are tracked but are not source
  "bak",
  "deleted",
  "deprecated",
  "orig",
  "rej",
  // Backup files whose extension is a compound suffix
  // (e.g. "toolCountBump.test.ts.r97i-bak" -> ext "r97i-bak").
  "r97i-bak",
]);

/** Exact path prefixes (git-relative, POSIX separators) to skip entirely. */
const SKIP_PATH_PREFIXES = [
  "reference/", // third-party reference material, papers, vendored projects
  "node_modules/",
  "dist/",
  "release/",
  "target/",
  "build/",
  ".archive/",
  ".aethercode/",
  ".git/",
];

/** Exact git-relative paths to skip. */
const SKIP_EXACT_PATHS = new Set([
  "LICENSE", // the license itself carries the copyright
  "pnpm-lock.yaml", // generated lockfile
  "package-lock.json",
  "bun.lock",
  ".gitmodules", // git metadata, not project source
  "NUL",
  "aethercode/aethercode-a2a/$logPath", // a literal log filename, not source
]);

/**
 * Extract the "extension" of a path for comment-style lookup.
 *
 * Two shapes matter:
 *   "foo.java"      -> "java"
 *   ".gitignore"    -> "gitignore"   (a dotfile: the WHOLE name is the key)
 *
 * The dotfile case is easy to get wrong: `".gitignore".lastIndexOf(".")` is 0,
 * so a naive `dot <= 0` check treats it as extensionless and the file gets
 * skipped. Dotfiles like .gitignore / .npmrc / .editorconfig ARE source
 * (they carry project conventions), so they need a comment style.
 */
function extensionOf(relPath) {
  const name = relPath.slice(relPath.lastIndexOf("/") + 1);
  const dot = name.lastIndexOf(".");
  // No dot at all -> extensionless.
  if (dot < 0) return "";
  // A leading dot means the whole name is the key (".gitignore" -> "gitignore").
  // But a name like "a.b" (dot > 0) is a normal extension.
  if (dot === 0) return name.slice(1).toLowerCase();
  return name.slice(dot + 1).toLowerCase();
}

function isSkippedPath(relPath) {
  if (SKIP_EXACT_PATHS.has(relPath)) return true;
  for (const p of SKIP_PATH_PREFIXES) {
    if (relPath.startsWith(p)) return true;
  }
  return false;
}

/**
 * File types that must NOT receive a header.
 *
 * Markdown and XML are excluded on purpose: their comment syntax is an HTML
 * comment or a C-style block comment, which is either invisible in rendered
 * Markdown or awkward inside a pom.xml.
 *
 * Shell scripts and CSS are excluded for the same reason — the header ends up
 * as noise in a file whose whole purpose is to be read by a human or a
 * toolchain, and in CSS a block comment above the first rule is pure clutter.
 *
 * The root LICENSE file already states the copyright for the project, and the
 * source files that carry the header do it for the code.
 */
const EXCLUDED_EXT = new Set([
  "md",
  "markdown",
  "xml",
  "sh",
  "bash",
  "zsh",
  "css",
  "cmd",
  "bat",
]);

/**
 * Detect the comment style for a path.
 * Special-cases files whose extension is not in the map but whose content
 * reveals the language (e.g. `.r97i-bak` is a TypeScript backup — we skip those
 * anyway via NEVER_TOUCH_EXT suffixes).
 */
function styleFor(relPath) {
  const ext = extensionOf(relPath);
  if (NEVER_TOUCH_EXT.has(ext)) return null;
  if (EXCLUDED_EXT.has(ext)) return null;

  // Files with no extension: only treat as source if we recognise the name.
  if (ext === "") {
    const name = relPath.slice(relPath.lastIndexOf("/") + 1);
    if (/^(Dockerfile|Makefile|LICENSE|LICENCE|README|NOTICE|COPYING)$/i.test(name)) return null;
    if (/^Dockerfile/i.test(name)) return { line: "# " };
    if (/^Makefile/i.test(name)) return STYLE_BY_EXT.make;
    return null;
  }

  return STYLE_BY_EXT[ext] ?? null;
}

/**
 * The index of the line where the licence header may start.
 *
 * The inserter writes the header as the FIRST thing in the file, after
 * the language DIRECTIVES (a shebang, an encoding declaration, an XML
 * declaration, a Rust inner attribute) and after a Python MODULE
 * DOCSTRING (which must stay the first statement).
 *
 * Anchoring here is what makes the migration safe. Without it, a file
 * whose OWN doc comment merely MENTIONS the marker is mistaken for a
 * header — and the migration then deletes that doc comment. This very
 * script is such a file: its doc comment shows the header shape.
 */
function headerStartIndex(lines) {
  let i = 0;
  for (;;) {
    if (i >= lines.length) return -1;
    const line = lines[i];
    const t = line.trim();
    if (t === "") {
      i++;
      continue;
    }
    if (t.startsWith("#!")) {
      i++;
      continue;
    }
    if (t.startsWith("<?xml")) {
      i++;
      continue;
    }
    if (/^#!\[/.test(t)) {
      i++;
      continue;
    }
    if (/^[ \t\f]*#.*coding[:=][ \t]*[-\w.]+/.test(line)) {
      i++;
      continue;
    }
    // A Python MODULE DOCSTRING comes before the header.
    const doc = t.match(/^[rRbBuUfF]{0,2}("""|\'\'\')/);
    if (doc) {
      const quote = doc[1];
      if (t.length >= quote.length * 2 && t.endsWith(quote)) {
        i++;
        continue;
      }
      let j = i;
      while (j < lines.length) {
        const from = j === i ? t.indexOf(quote) + quote.length : 0;
        const idx = lines[j].indexOf(quote, from);
        if (idx >= 0) {
          i = j + 1;
          break;
        }
        j++;
      }
      if (j >= lines.length) return -1;
      continue;
    }
    return i;
  }
}

/**
 * True when the file already carries OUR header, at the TOP.
 *
 * `alreadyHasHeader` only asks whether the marker appears ANYWHERE, and
 * a file's own doc comment can MENTION it — the licence tooling scripts
 * all show the header shape in their documentation. A marker found in
 * prose is not a header.
 *
 * So the header must be where the inserter puts one: the first content
 * line of a comment block that starts at the top of the file.
 */
/**
 * True when the file already carries EXACTLY the canonical header.
 *
 * This is the IDEMPOTENCY test, and it has to be an exact comparison rather
 * than "does the marker appear?".
 *
 * A weaker test -- "is there a marker at the top?" -- cannot tell a
 * canonical header from an obsolete one, because both carry the marker. So
 * the tool would keep rewriting files whose header was already correct: not
 * wrong, but it means every run reports thousands of changes and the
 * `--check` gate can never be green. A gate that is red on a correct tree
 * is a gate nobody trusts.
 *
 * So the comparison is LINE BY LINE against what `renderHeader(style)` would
 * produce for this file's language. Same lines, in the same order, with the
 * same decoration.
 *
 * The anchoring rule is unchanged and is what makes this safe: the header
 * must be the FIRST content line of a comment block that starts at the top
 * of the file. A file's own doc comment may mention the marker in prose --
 * the licence tooling scripts all do, since they show the header shape in
 * their documentation -- and a marker found in prose is not a header.
 *
 * @param {string[]} lines the file's lines, after the directives are split off
 * @param {object} style the language style, for rendering the canonical form
 * @returns {boolean}
 */
function alreadyHasHeaderAtTop(lines, style) {
  const start = headerStartIndex(lines);
  if (start < 0) return false;

  const canonical = renderHeader(style);
  if (!canonical) return false; // JSON: no header is possible

  const t = (lines[start] ?? "").trim();
  // A LINE-comment run.
  if (/^(\/\/|#|REM |--)/.test(t)) {
    const rendered = renderLineHeader(style.line);
    for (let k = 0; k < rendered.length; k++) {
      if ((lines[start + k] ?? "").trim() !== rendered[k].trim()) return false;
    }
    return true;
  }

  // A BLOCK comment: the slash-star form AND the HTML-comment form.
  //
  // BOTH forms count. The HTML form was missing, and that is exactly why
  // `index.html` was reported as "missing the header" by `--check` even
  // though it carried one -- the detector did not recognise the shape it
  // had written.
  //
  // That is the failure mode to be wary of: the inserter and the detector
  // must agree on the shape, or the tool argues with itself.
  if (t.startsWith("/*") || t.startsWith("<!--")) {
    for (let k = 0; k < canonical.length; k++) {
      const actual = (lines[start + k] ?? "").trim();
      const want = canonical[k].trim();
      if (actual === want) continue;
      // The blank line inside the block may be written bare or with the
      // block's decoration -- both are the same block.
      if (want === "" && (actual === "" || actual === "*" || actual === " *")) {
        continue;
      }
      return false;
    }
    return true;
  }
  return false;
}

/**
 * Detect a licence header of a shape we NO LONGER emit.
 *
 * The R389 header named the licence but carried NO permission text:
 *
 *     Copyright (c) 2026 maijun
 *     <blank>
 *     Licensed under the MIT License.
 *     See the LICENSE file in the project root for the full license text.
 *
 * rendered as a Javadoc block in Java and as a line-comment run everywhere
 * else. The header is now a plain block comment carrying the full MIT text,
 * so a file carrying the old form must be MIGRATED, not skipped:
 * `alreadyHasHeader` would happily report "already has a header" and leave
 * the obsolete form in place.
 *
 * Returns { start, end } line indices of the legacy block, or null.
 */
/**
 * Find an existing licence header block at the top of a file.
 *
 * Returns { start, end } line indices of the block, or null.
 *
 * WHY THIS IS ONE FUNCTION AND NOT TWO
 *
 * There used to be a "does the file have a current header?" test and a
 * separate "does the file have a LEGACY header?" test. Two tests, two
 * shape sets, and they did not agree:
 *
 *  - The "current header" test knew the slash-star block only. An
 *    HTML-comment header was invisible to it.
 *  - The "legacy header" test knew the slash-star block only, for the same
 *    reason.
 *
 * So for an HTML file the first test said "no header here" and the second
 * said "no legacy header here" -- and the inserter took the INSERT path,
 * prepending a fresh header WITHOUT removing the obsolete one. The file
 * ended up carrying two copyright notices and two different licence
 * statements, and `--check` could not make sense of it.
 *
 * One function, one shape set, and the removal is driven by the SAME
 * detection that decides whether to write a header. If they can disagree,
 * they will.
 *
 * WHAT COUNTS AS A HEADER
 *
 * A comment block that starts at the TOP of the file (after the language
 * directives) and whose FIRST content line is exactly the marker.
 *
 * The "first content line" rule is what keeps a file's own doc comment from
 * being mistaken for a header -- the licence tooling scripts all mention the
 * marker in prose, because they show the header shape in their documentation.
 * Mistaking such a comment for a header would DELETE it.
 *
 * Both the current form and the obsolete R389 form are returned, because
 * both must be REMOVED before the fresh header is written. The caller does
 * not care which it was.
 */
function findExistingHeaderBlock(lines) {
  const headStart = headerStartIndex(lines);
  if (headStart < 0) return null;

  const blockForms = [
    { open: "/*", close: "*/" },
    { open: "<!--", close: "-->" },
  ];

  /**
   * If a comment block starts at `from` and its first content line is the
   * marker, return the block's span.
   */
  const blockAt = (from) => {
    if (from < 0 || from >= lines.length) return null;
    const opener = (lines[from] ?? "").trim();
    const form = blockForms.find((f) => opener.startsWith(f.open));
    if (!form) return null;

    let end = -1;
    for (let i = from + 1; i < Math.min(lines.length, from + 60); i++) {
      if (lines[i].trim().endsWith(form.close)) {
        end = i;
        break;
      }
    }
    if (end < 0) return null;

    const firstContent = (lines[from + 1] ?? "")
      .trim()
      .replace(/^\*+/, "")
      .trim();
    if (firstContent !== MARKER) return null;
    return { start: from, end, form: "block" };
  };

  const top = blockAt(headStart);
  if (top) return top;

  // A LINE-comment form at the top: the marker must be AT the header start.
  //
  // THE HEADER'S EXTENT IS DETERMINED BY ITS CONTENT, NOT BY WHERE THE
  // COMMENT RUN ENDS.
  //
  // This is the fix for a bug that destroyed real documentation in hundreds
  // of files, and the reasoning is worth stating.
  //
  // The first version of this scanner asked "how far does the comment run
  // go?" and answered by walking forward while the lines were comments.
  // That is the wrong question. A licence header is a FIXED piece of text:
  //
  //     Copyright (c) 2026 maijun
  //     <blank>
  //     Licensed under the MIT License.
  //     See the LICENSE file in the project root for the full license text.
  //
  // Five lines, known in advance. The file's own documentation often
  // FOLLOWS it as more `//` comment lines -- and a run-based scanner cannot
  // tell "the header's last line" from "the file's first line of
  // documentation", because both are comments.
  //
  // So the scanner swallowed the file's documentation. Concretely:
  // `aethercode-desktop/src/components/ErrorBoundary.tsx` lost its whole
  // R713 rationale and its R180 source contract -- and the diff showed only
  // DELETIONS, which is exactly what a destructive bug looks like.
  //
  // A Rust `//!` inner doc block and a TypeScript `/// <reference>` directive
  // were lost the same way: both start with `//`, so an "is this a comment?"
  // test says yes.
  //
  // THE RULE: match the header's KNOWN LINES. The run ends where the known
  // text ends. A comment line that is not part of the known text ends the
  // header, and the file's own documentation survives.
  //
  // Two forms are recognised:
  //
  //  - The R389 short form: the marker, a blank, and the two sentences.
  //    The blank is UNPREFIXED in that form -- which is why the run used to
  //    be measured by scanning rather than by content.
  //  - The current form: the marker followed by the full MIT text.
  const markerRe = /Copyright \(c\) \d{4} \S/;
  if (markerRe.test(lines[headStart] ?? "")) {
    const strip = (l) => {
      const m = (l ?? "").match(/^\s*(?:\/\/[!/]*|#|REM\b|--)\s?/);
      return m ? l.slice(m[0].length).trim() : (l ?? "").trim();
    };

    const knownForms = [
      // The CURRENT form: marker, blank, full MIT text.
      ["", ...MIT_BODY],
      // The R389 short form: marker, blank, two sentences.
      [
        "",
        "Licensed under the MIT License.",
        "See the LICENSE file in the project root for the full license text.",
      ],
    ];

    for (const form of knownForms) {
      let ok = true;
      for (let k = 0; k < form.length; k++) {
        if (strip(lines[headStart + 1 + k]) !== form[k]) {
          ok = false;
          break;
        }
      }
      if (!ok) continue;
      // The header spans the marker line plus the matched form. A trailing
      // blank line that SEPARATES the header from the body is not part of
      // the header -- the caller already drops exactly one blank after the
      // block it removes.
      return { start: headStart, end: headStart + form.length, form: "line" };
    }

    // NO KNOWN FORM MATCHED.
    //
    // The file has a marker line at the top that is not a header we
    // recognise. That is a file whose own comment merely MENTIONS the
    // marker -- the licence tooling scripts all do, because they show the
    // header shape in their documentation.
    //
    // So we must NOT touch it. Guessing would delete the file's own
    // documentation -- which is precisely the failure this whole rework
    // exists to prevent.
    return null;
  }

  return null;
}

/**
 * Render the header for a given style.
 * Returns an array of lines (no trailing newline).
 */
function renderHeader(style) {
  if (style.none) return null; // JSON: cannot carry a comment

  if (style.block) {
    const [open, close] = style.block;
    // The C-family block form shares ONE renderer with the shared module, so
    // the Java header and any other slash-star language produce
    // byte-identical text.
    if (open === "/*" && close === "*/") return renderBlockHeader();
    return [`${open}`, ...HEADER_LINES.map((l) => (l === "" ? "" : `  ${l}`)), close];
  }

  // Line style.
  return renderLineHeader(style.line);
}

/**
 * Detect leading lines that MUST stay at the top, per language.
 * Returns { prefixLines: string[], rest: string }
 *
 * See the header comment (points 3, 3a, 3b) for why each rule exists.
 */
function splitLeadingDirectives(content, style) {
  // SPLIT ON "\n" ALONE.
  //
  // The carriage return STAYS at the end of each line, so a CRLF file's
  // lines are "...\r" and every later join reproduces the CRLF exactly.
  //
  // The old `split(/\r?\n/)` DISCARDED the carriage return, and the
  // rejoin used a hardcoded "\n" -- so a CRLF file's body was silently
  // rewritten with LF endings. Seven Java files here ended up MIXED: a
  // CRLF header on an LF body, and a diff of thousands of changed lines
  // for a five-line header edit.
  const lines = content.split("\n");
  const prefix = [];

  // --- shebang (any language) ---
  if (lines[0] && lines[0].startsWith("#!")) {
    prefix.push(lines[0]);
    lines.shift();
  }

  // --- Python: PEP 263 encoding declaration must be on line 1 or 2 ---
  // (after a shebang, it is still allowed on line 2)
  if (style.python) {
    let i = 0;
    while (i < lines.length && i < 2) {
      const l = lines[i];
      // A coding declaration is a comment matching `coding[:=]\s*<name>`.
      if (/^[ \t\f]*#.*coding[:=][ \t]*[-\w.]+/.test(l)) {
        prefix.push(l);
        i++;
      } else {
        break;
      }
    }
    lines.splice(0, i);
  }

  // --- XML declaration ---
  if (lines[0] && /^\s*<\?xml[\s\S]*\?\>/.test(lines[0])) {
    prefix.push(lines[0]);
    lines.shift();
  }

  // --- Rust: inner attributes `#![...]` must precede every item ---
  if (style.rust) {
    let i = 0;
    while (i < lines.length && /^\s*#!\[/.test(lines[i])) {
      prefix.push(lines[i]);
      i++;
    }
    lines.splice(0, i);
  }

  // --- Python: module docstring must stay the first statement ---
  // We move it into the prefix so the header lands after it.
  if (style.python) {
    const joined = lines.join("\n");
    const m = joined.match(/^([ \t]*(?:[rRbBuUfF]{0,2})("""|''')[\s\S]*?\2)/);
    if (m && m.index === 0) {
      // The docstring may span multiple lines. Slice it out line-wise.
      const docText = m[1];
      const docLines = docText.split("\n");
      prefix.push(...docLines);
      lines.splice(0, docLines.length);
    }
  }

  return { prefixLines: prefix, rest: lines.join("\n") };
}

function main() {
  const args = new Set(process.argv.slice(2));
  const checkOnly = args.has("--check");
  const dryRun = args.has("--dry-run");

  let files;
  try {
    files = execFileSync("git", ["ls-files"], {
      cwd: REPO_ROOT,
      encoding: "utf8",
      maxBuffer: 64 * 1024 * 1024,
    })
      .split("\n")
      .map((s) => s.trim())
      .filter(Boolean);
  } catch (err) {
    console.error("ERROR: could not run `git ls-files`. Run this from the repo root.");
    process.exit(2);
  }

  const report = {
    changed: [],
    skipped: [],
    untouched: [],
    migrated: [],
    jsonBlocked: [],
    nonUtf8: [],
  };
  let missing = 0;

  for (const rel of files) {
    const relPosix = rel.replace(/\\/g, "/");
    if (isSkippedPath(relPosix)) {
      report.skipped.push(relPosix);
      continue;
    }

    const style = styleFor(relPosix);
    if (!style) {
      report.skipped.push(relPosix);
      continue;
    }

    const abs = join(REPO_ROOT, relPosix);
    let bytes;
    try {
      bytes = readFileSync(abs);
    } catch {
      report.skipped.push(relPosix);
      continue;
    }

    // Binary / NUL guard — never rewrite a binary as text.
    if (bytes.includes(0x00)) {
      report.skipped.push(relPosix);
      continue;
    }

    // ENCODING GUARD — read as bytes first and decode STRICTLY.
    //
    // A file that is not valid UTF-8 (e.g. GBK-encoded Chinese text) would be
    // decoded with replacement characters by a lenient decode, and writing it
    // back would PERMANENTLY destroy the original bytes — the file would then
    // differ from HEAD in a way nobody asked for.
    //
    // This is not hypothetical: one Markdown file in this repo is GBK, and a
    // lenient decode turned its bytes into U+FFFD. The correct behaviour is to
    // SKIP a file we cannot decode losslessly.
    //
    // THE BOM MUST BE DETECTED FROM THE BYTES, NOT FROM THE DECODED STRING.
    // `new TextDecoder("utf-8")` follows the WHATWG spec and SILENTLY STRIPS
    // a leading BOM — so `raw.charCodeAt(0)` is never 0xFEFF, and a check on
    // the decoded string always says "no BOM". The BOM is then dropped on
    // write-back and the file differs from HEAD by exactly one invisible
    // character.
    const hadBom = bytes.length >= 3 && bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf;
    let raw;
    try {
      raw = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
    } catch {
      report.nonUtf8.push(relPosix);
      continue;
    }

    if (raw.trim() === "") {
      report.skipped.push(relPosix);
      continue;
    }

    // Detect the line ending so we preserve it. The decoder already stripped
    // the BOM, so `raw` starts at the first real character.
    const body = raw;
    const eol = detectEol(body);

    if (style.none) {
      // JSON has no comment syntax. A JSON file cannot legally carry a
      // // comment, so adding one would make it unparseable. Report these
      // instead of corrupting them.
      report.jsonBlocked.push(relPosix);
      missing++;
      continue;
    }

    // Split the file into the leading DIRECTIVES (shebang, encoding
    // declaration, XML declaration, Rust inner attributes, module docstring)
    // and the rest.
    //
    // This must happen BEFORE the legacy-header test, because the header is
    // not always at line 0: a Python file opens with a shebang and a PEP 263
    // encoding declaration, and its header sits BELOW them. A test that only
    // looked at line 0 would see "no header here", decide the file was
    // already current, and leave the obsolete form in place.
    const { prefixLines, rest } = splitLeadingDirectives(body, style);

    // Does the file already carry a header -- at the TOP, where the
    // inserter puts one?
    //
    // "alreadyHasHeader" alone is not enough: it only asks whether the
    // marker appears ANYWHERE, and a file's own doc comment can MENTION
    // the marker. The licence tooling scripts all do -- they show the
    // header shape in their documentation -- so a marker-anywhere test
    // reported "already licensed" and left the tooling itself unlicensed.
    //
    // So the test is the SAME rule the remover and the auditor use: the
    // marker must be the first content line of a comment block that starts
    // at the top of the file.
    const restLines = rest.split("\n");
    const alreadyHas = alreadyHasHeaderAtTop(restLines, style);

    // EXISTING HEADER BLOCKS, OF ANY AGE.
    //
    // The header this tool writes is the full MIT text. A file may carry an
    // OLDER one -- the R389 short form -- and it must be REPLACED, not
    // left in place and not merely detected.
    //
    // THE ONE RULE THAT MAKES BOTH PATHS THE SAME PATH: an existing header
    // block is stripped, whatever its age, and exactly one fresh header is
    // written. There is no separate "insert" path and "migrate" path,
    // because two paths are two chances to get it wrong -- and they were:
    //
    //  - `index.html` carried an HTML-comment legacy header. The "already
    //    has a header" test only knew the slash-star shape, so it said "no
    //    header here", took the INSERT path, and PREPENDED a new header
    //    without removing the old one. The file ended up with TWO copyright
    //    notices and TWO different licence statements.
    //
    // So the detection and the removal use the SAME shape set, and both
    // happen on every run.
    const existing = findExistingHeaderBlock(restLines);

    if (alreadyHas) {
      // The file carries EXACTLY the canonical header at the top. It is
      // already correct, so there is nothing to write and nothing to
      // remove -- leave it alone.
      //
      // This is the idempotency guarantee: running the tool twice must not
      // change anything the second time. A tool that rewrites 2800 correct
      // files on every run is not idempotent, and its `--check` gate can
      // never be green -- which means the gate stops being a signal.
      report.untouched.push(relPosix);
      continue;
    }
    if (existing) {
      // An OBSOLETE header: strip it and write the canonical one in its
      // place.
      report.migrated.push(relPosix);
    }

    // Assemble: directives, header, blank line, original content.
    //
    // Two blank-line subtleties, both of which matter for a byte-exact diff:
    //
    //  a) Between the DIRECTIVES and the header. If the original file had a
    //     blank line right after its directive block, we keep one. If it did
    //     not (an XML declaration immediately followed by `<project>`), we do
    //     NOT add one — inserting it would be an unrelated change.
    //
    //  b) Between the HEADER and the body. We always put exactly one blank
    //     line there, EXCEPT when the original body started with a comment
    //     line that was already separated from the directive block by a blank
    //     line — in that case the original's own blank line is reused rather
    //     than adding a second one.
    // THE DIRECTIVES.
    //
    // A shebang, a Rust inner attribute, an XML declaration, a Python
    // encoding declaration and a module docstring must ALL stay at the top
    // of the file, in this order, ahead of everything else.
    //
    // They were dropped once already. The CRLF fix rewrote this whole
    // region and left the directives out of the result -- so
    // `aethercode-desktop/src-tauri/src/main.rs` lost
    // `#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]`
    // (the attribute that stops a release build from opening a console
    // window) and `aethercode-memory/src/cli.ts` lost its
    // `#!/usr/bin/env node`.
    //
    // So the directives are part of the assembly, not a side effect of it.
    const out = [];
    if (prefixLines.length) {
      out.push(...prefixLines);
      // Did the original have a blank line between the directives and the body?
      const hadBlankAfterDirectives = /^[\r\n]/.test(rest);
      if (hadBlankAfterDirectives) out.push("");
    }

    // MIGRATION: an obsolete header must be stripped before the new one is
    // written. Leaving it in place would put TWO licence headers in the file
    // (the old one and the new one), and the file would then no longer match
    // what the removal script expects.
    //
    // The legacy block is always at the very top of the file, so removing it
    // shifts every later line up -- but that is precisely the change we want:
    // the file's own content stays byte-identical, only the obsolete header
    // goes away.
    let effectiveRest = rest;
    if (existing) {
      const restLines = rest.split("\n");
      let kept = [
        ...restLines.slice(0, existing.start),
        ...restLines.slice(existing.end + 1),
      ];
      // The legacy block was followed by a blank line separating it from the
      // rest of the file; drop exactly one so the file does not end up with a
      // stray blank line.
      if (
        kept[existing.start] !== undefined &&
        kept[existing.start].trim() === ""
      ) {
        kept = [
          ...kept.slice(0, existing.start),
          ...kept.slice(existing.start + 1),
        ];
      }
      effectiveRest = kept.join("\n");
    }

    // THE ASSEMBLY, and where the two line-ending conventions meet.
    //
    // The RENDERED header lines carry no carriage return -- they are new
    // text -- so they are joined with the file's detected `eol`.
    //
    // The BODY lines already carry their own carriage return if the file is
    // a CRLF file (because the split kept it), so they are joined with
    // "\n" and the `\r` rides along.
    //
    // Joining EVERYTHING with `eol` would double the carriage return on a
    // CRLF file ("...\r\r\n"); joining everything with "\n" would strip
    // the endings from a CRLF file. So the two halves are joined
    // differently, and that difference IS the point.
    const headerLines = renderHeader(style);
    const bodyText = effectiveRest.replace(/^[\n\r]+/, "");

    // THE JOIN.
    //
    // Three parts, and each is joined with the ending it needs:
    //
    //  - the directives and the header are NEW text, so they are joined
    //    with the file's detected `eol`;
    //  - the BODY already carries its own carriage returns (the split kept
    //    them), so it is joined with "\n" and the "\r" rides along.
    //
    // Joining everything with `eol` would DOUBLE the carriage return on a
    // CRLF file ("...\r\r\n"); joining everything with "\n" would strip
    // the endings from a CRLF file. So the two halves are joined
    // differently, and that difference IS the point.
    const parts = [];
    if (out.length) parts.push(out.join(eol));
    parts.push(headerLines.join(eol));
    if (bodyText !== "") {
      parts.push("");
      parts.push(bodyText);
    }
    let next = parts.join(eol);
    if (hadBom) next = "\uFEFF" + next;

    report.changed.push(relPosix);

    if (!checkOnly && !dryRun) {
      writeFileSync(abs, next, "utf8");
    }
  }

  const rel = (p) => relative(REPO_ROOT, join(REPO_ROOT, p));

  console.log(`files scanned      : ${files.length}`);
  console.log(`skipped (out of scope): ${report.skipped.length}`);
  console.log(`already had header : ${report.untouched.length}`);
  console.log(`migrated (old header -> full MIT text): ${report.migrated.length}`);
  console.log(`${checkOnly || dryRun ? "would change" : "changed"}       : ${report.changed.length}`);
  console.log(`JSON (no comment syntax, exempt): ${report.jsonBlocked.length}`);
  console.log(`not valid UTF-8 (skipped, untouched): ${report.nonUtf8.length}`);


  if (report.nonUtf8.length) {
    console.log("\n--- files that are NOT valid UTF-8 (left untouched on purpose) ---");
    console.log("    Decoding them losslessly is not possible; rewriting them would");
    console.log("    destroy the original bytes.");
    for (const p of report.nonUtf8.slice(0, 40)) console.log("  " + p);
    if (report.nonUtf8.length > 40) console.log(`  ... and ${report.nonUtf8.length - 40} more`);
  }

  if (report.jsonBlocked.length && (checkOnly || dryRun)) {
    console.log("\n--- JSON files exempt from the header (JSON has no comment syntax) ---");
    for (const p of report.jsonBlocked.slice(0, 40)) console.log("  " + p);
    if (report.jsonBlocked.length > 40) console.log(`  ... and ${report.jsonBlocked.length - 40} more`);
  }

  if ((checkOnly || dryRun) && report.changed.length > 0) {
    console.log("\n--- files missing the header ---");
    for (const p of report.changed.slice(0, 40)) console.log("  " + p);
    if (report.changed.length > 40) console.log(`  ... and ${report.changed.length - 40} more`);
  }

  if (checkOnly && report.changed.length > 0) {
    console.log("\n--check FAILED: files missing the license header.");
    process.exit(1);
  }
  if (checkOnly) {
    console.log("\n--check OK: every in-scope file carries the header.");
  }
}

main();
