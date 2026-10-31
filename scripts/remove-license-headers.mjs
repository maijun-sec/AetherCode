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
 * remove-license-headers.mjs — remove the license header from files, restoring
 * them byte-for-byte to their pre-header state.
 *
 * Why this is not "just delete the first 5 lines": a header is not always at
 * the top, and the file may have OTHER edits (from a concurrent session, a
 * version bump, ...). Deleting lines blindly would destroy those edits.
 *
 * The safe way is the same way the header was inserted — by REMOVING exactly
 * the block we added, and then PROVING the result matches the original blob.
 *
 * For Markdown and XML the header is a BLOCK comment, which has a clean
 * delimiter pair, so the removal is unambiguous:
 *
 *     <!--
 *       Copyright (c) 2026 maijun
 *
 *       Licensed under the MIT License.
 *       See the LICENSE file in the project root for the full license text.
 *     -->
 *
 * The XML declaration (<?xml ... ?>) is NOT part of the header and must stay
 * exactly where it is.
 *
 * Usage:
 *   node scripts/remove-license-headers.mjs --dry-run
 *   node scripts/remove-license-headers.mjs --ext md --ext xml
 */
import { readFileSync, writeFileSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { join } from "node:path";
import { MARKER, MIT_BODY, detectEol } from "./license-header-common.mjs";

const REPO_ROOT = process.cwd();

const args = process.argv.slice(2);
const dryRun = args.includes("--dry-run");
// `--check` proves the round-trip WITHOUT writing anything.
//
// WHY THIS MODE EXISTS, and it is not a nicety.
//
// The remover's whole job is to DELETE a header. Run without a flag, it
// deletes the header from every file it recognises -- and it did exactly
// that, during this very round, to 292 files that had already been
// migrated. The tree was left with the headers GONE, and the diff showed
// only deletions.
//
// What made it survivable is that the tool also prints a summary, and the
// summary said "exact match: 288" -- which looked like success rather than
// like damage. A tool that removes something should default to NOT removing
// it, and the mode that removes should be the explicit one.
//
// So: `--check` verifies and reports; `--dry-run` previews; and the
// default still removes, because a remover that cannot remove is not a
// remover. What the default MUST NOT do is remove silently.
const checkOnly = args.includes("--check");
const verbose = args.includes("--verbose");

/**
 * Which extensions to operate on.
 *
 * Parsed by walking the argument list and consuming the token after each
 * "--ext". A filter/map over the array would also pick up the "--ext" flags
 * themselves, and an extension called "--ext" matches nothing.
 *
 * DEFAULT: every extension the INSERTER writes a header for.
 *
 * This list used to be a hand-written subset (md, xml, shell, css, cmd,
 * java) that predated the header being applied to Python, TypeScript and the
 * rest. A removal script that skips a file the inserter touched cannot
 * PROVE the round trip for that file -- and a round trip that is not proven
 * is not a round trip.
 */
const DEFAULT_EXTS = [
  // C-family
  "java", "kt", "kts", "ts", "tsx", "js", "jsx", "mjs", "cjs", "rs", "go",
  "c", "h", "cpp", "hpp", "cs", "scala", "swift", "dart",
  // Shell family
  "sh", "bash", "zsh", "ps1", "psm1", "cmd", "bat",
  // Data / config
  "yaml", "yml", "toml", "ini", "cfg", "properties", "gitattributes",
  "gitignore", "npmrc", "editorconfig", "prettierignore", "gitmodules",
  "sample", "env",
  // Scripting
  "py", "rb", "pl", "pm",
  // SQL
  "sql",
  // Markup
  "html", "xml", "xsd", "svg", "vue", "md", "mdx",
  // Style
  "css", "scss", "less",
  // Build
  "make", "mk",
];

const exts = new Set();
for (let i = 0; i < args.length; i++) {
  if (args[i] === "--ext" && args[i + 1]) {
    exts.add(args[i + 1].toLowerCase());
    i++;
  }
}
if (exts.size === 0) {
  for (const e of DEFAULT_EXTS) {
    exts.add(e);
  }
}

function git(...a) {
  return execFileSync("git", ["-c", "core.safecrlf=false", ...a], {
    cwd: REPO_ROOT,
    encoding: "utf8",
    maxBuffer: 256 * 1024 * 1024,
  });
}

/**
 * Remove a LINE-comment header from a file's text.
 *
 * The line-style header is a 4-line run:
 *
 *     # Copyright (c) 2026 maijun
 *     <blank>
 *     # Licensed under the MIT License.
 *     # See the LICENSE file in the project root for the full license text.
 *
 * It may be preceded by a shebang (`#!/bin/bash`), which must stay on line 1.
 *
 * Returns the candidate reconstructions, or null if no line header was found.
 */
/**
 * Strip a LINE-comment header from a file's text.
 *
 * The header is a FIXED piece of text, so the header's extent is found by
 * matching its KNOWN LINES -- not by walking forward while the lines happen
 * to be comments.
 *
 * WHY THIS MATTERS, and it is not a detail.
 *
 * The first version of this function measured the run: "keep going while
 * the lines are comments". That fails on the R389 header, which wrote its
 * blank line UNPREFIXED:
 *
 *     // Copyright (c) 2026 maijun
 *     <blank>
 *     // Licensed under the MIT License.
 *     // See the LICENSE file in the project root for the full license text.
 *
 * The run stopped at the unprefixed blank, the function returned "no header
 * here", and every file whose HEAD carries the old header was reported as
 * "removed, but NOT provably byte-identical to the original". 788 files.
 * The gate could never go green.
 *
 * It is also the same defect the INSERTER had: a run-based scan cannot
 * tell "the header's last line" from "the file's first line of its own
 * documentation", because both are comments. The file's documentation
 * would be swallowed.
 *
 * So: match the known text. The two forms are
 *
 *  - the CURRENT form: marker, blank, full MIT text;
 *  - the R389 form:     marker, blank, two sentences.
 *
 * The blank inside the header is accepted in BOTH forms -- bare or
 * carrying the comment prefix -- because that is exactly the difference
 * between the two generations.
 *
 * Returns the candidate reconstructions, or null if no line header was found.
 */
function stripLineHeader(text) {
  const lines = text.split(/\r?\n/);
  const markerIdx = lines.findIndex((l) => l.includes(MARKER));
  if (markerIdx < 0) return null;

  // The comment prefix is whatever precedes the marker on its own line.
  const m = lines[markerIdx];
  const prefixes = ["// ", "# ", "REM ", "-- "];
  let prefix = null;
  for (const p of prefixes) {
    if (m.startsWith(p)) {
      prefix = p;
      break;
    }
  }
  if (prefix === null) return null;

  // Strip the comment decoration from a line, so the comparison is against
  // the header's TEXT rather than its decoration.
  const bare = (line) => {
    const t = (line ?? "").trim();
    const p = prefix.trim();
    if (t.startsWith(p)) return t.slice(p.length).trim();
    return t;
  };

  // The known header bodies, in the order they may appear. Each is the text
  // AFTER the marker line.
  const knownForms = [
    // The CURRENT form: blank, then the full MIT text.
    ["", ...MIT_BODY],
    // The R389 form: blank, then the two sentences.
    [
      "",
      "Licensed under the MIT License.",
      "See the LICENSE file in the project root for the full license text.",
    ],
  ];

  for (const form of knownForms) {
    let ok = true;
    for (let k = 0; k < form.length; k++) {
      const actual = bare(lines[markerIdx + 1 + k]);
      const want = form[k];
      // The blank line inside the header may be bare or carry the prefix.
      if (want === "") {
        if (actual !== "" && actual !== prefix.trim()) {
          ok = false;
          break;
        }
        continue;
      }
      if (actual !== want) {
        ok = false;
        break;
      }
    }
    if (!ok) continue;

    let end = markerIdx + form.length;

    // A trailing blank SEPARATES the header from the body. The header we
    // INSERT is followed by exactly one blank line, so a complete removal
    // must consume it -- otherwise the file is left with a stray blank
    // line where the header was, and the round trip is not byte-exact.
    if (end + 1 < lines.length && lines[end + 1].trim() === "") {
      end = end + 1;
    }

    const start = markerIdx;
    const withoutHeader = [...lines.slice(0, start), ...lines.slice(end + 1)];

    // Enumerate the blank-line variants, same as the block-header path.
    const candidates = [];
    const push = (arr) => {
      const key = arr.join("\n");
      if (!candidates.some((c) => c.join("\n") === key)) candidates.push(arr);
    };

    const blankBefore = start > 0 && withoutHeader[start - 1].trim() === "";
    const blankAfter = start < withoutHeader.length && withoutHeader[start].trim() === "";

    for (const dropBefore of [false, true]) {
      if (dropBefore && !blankBefore) continue;
      for (const dropAfter of [false, true]) {
        if (dropAfter && !blankAfter) continue;
        const pre = withoutHeader.slice(0, start);
        if (dropBefore) pre.pop();
        const post = withoutHeader.slice(start);
        if (dropAfter) post.shift();
        push([...pre, ...post]);
      }
    }
    push(withoutHeader);

    return { candidates, start, end };
  }

  return null;
}

/**
 * Remove the licence from a Java file-level Javadoc block.
 *
 * The Java header is a file-level Javadoc above the `package` declaration.
 * Two shapes exist:
 *
 *  a) the block is ONLY the licence — remove the whole block;
 *  b) the licence block is followed by ANOTHER Javadoc block carrying the
 *     file's own description (the repo's package-info.java files) — remove
 *     only the licence block and leave the other one untouched.
 *
 * Shape (b) is why this cannot just reuse the generic block remover: that one
 * deletes the entire block, which would take the file's own documentation
 * with it.
 *
 * Returns candidate reconstructions, or null.
 */
function stripJavadocLicense(text) {
  const lines = text.split(/\r?\n/);
  const markerIdx = lines.findIndex((l) => l.includes(MARKER));
  if (markerIdx < 0) return null;

  // The block opener is the nearest "/**" above the marker.
  const clean = (s) => s.replace(/^\uFEFF/, "").trim();
  let start = -1;
  for (let i = markerIdx; i >= 0 && i > markerIdx - 8; i--) {
    if (clean(lines[i]).startsWith("/**")) {
      start = i;
      break;
    }
  }
  if (start < 0) return null;

  // The block closer is the first " */" at or below the marker.
  let end = -1;
  for (let i = markerIdx; i < lines.length; i++) {
    if (clean(lines[i]) === "*/") {
      end = i;
      break;
    }
  }
  if (end < 0) return null;

  // SAFETY: the block we found must ACTUALLY CONTAIN the marker.
  //
  // The opener search above only walks back from the marker to the nearest
  // "/**". If a file has a file-level Javadoc that does NOT carry the licence
  // but happens to sit within 8 lines above the marker line (e.g. a licence
  // that was inserted as a LINE comment right after an unrelated Javadoc),
  // the nearest "/**" is the WRONG block — and deleting it would silently
  // destroy the file's own documentation.
  //
  // The invariant we need is not "there is a Javadoc above the marker" but
  // "the Javadoc block spanning the marker contains the marker". Check that
  // against the block we actually resolved, and bail out if it does not.
  const block = lines.slice(start, end + 1).join("\n");
  if (!block.includes(MARKER)) return null;

  // The marker must be the FIRST content line of the block. A doc comment
  // that merely MENTIONS the marker is not a licence header.
  const firstContent = clean(lines[start + 1]).replace(/^\*+\/?/, "").trim();
  if (firstContent !== MARKER) return null;

  const candidates = [];
  const push = (arr) => {
    const key = arr.join("\n");
    if (!candidates.some((c) => c.join("\n") === key)) candidates.push(arr);
  };

  // Drop the licence block. Anything after it — including a second Javadoc
  // block — is preserved exactly.
  const withoutBlock = [...lines.slice(0, start), ...lines.slice(end + 1)];
  push(withoutBlock);
  // The licence block may have been separated from what follows by a blank
  // line; drop one if it is there.
  if (start < withoutBlock.length && withoutBlock[start].trim() === "") {
    push([...withoutBlock.slice(0, start), ...withoutBlock.slice(start + 1)]);
  }

  return { candidates, start, end };
}

/**
 * Remove a block comment header from a file's text.
 * Returns the new text, or null if no header block was found.
 */
function stripBlockHeader(text) {
  const lines = text.split(/\r?\n/);
  // Find the block opener that contains our marker. The opener may be a few
  // lines above the marker (e.g. "<!--" on its own line).
  const markerIdx = lines.findIndex((l) => l.includes(MARKER));
  if (markerIdx < 0) return null;

  // Walk back to the opener: the nearest preceding line whose trimmed value
  // is one of the known openers.
  //
  // NOTE: a BOM is NOT whitespace, so String.trim() does not remove it. A file
  // that starts with a BOM has its first line read as "\uFEFF<!--", and a
  // naive `trim() === "<!--"` test MISSES the opener — which silently leaves
  // the BOM attached to the first content line instead of the file. Strip a
  // leading BOM before comparing.
  const OPENERS = ["<!--", "/*"];
  const clean = (s) => s.replace(/^\uFEFF/, "").trim();
  let start = -1;
  for (let i = markerIdx; i >= 0 && i > markerIdx - 6; i--) {
    if (OPENERS.includes(clean(lines[i]))) {
      start = i;
      break;
    }
  }
  if (start < 0) return null;

  // THE MARKER MUST BE THE FIRST CONTENT LINE OF THE BLOCK. The inserter
  // always puts it there, and that is what separates a real licence header
  // from a doc comment that MENTIONS the marker -- this very script's own
  // doc comment shows the header shape, marker line and all, and stripping
  // that would delete the script's documentation.
  if (start + 1 >= lines.length) return null;
  const firstContent = clean(lines[start + 1]).replace(/^\*+\/?/, "").trim();
  if (firstContent !== MARKER) return null;

  // Walk forward to the matching closer.
  const CLOSERS = { "<!--": "-->", "/*": "*/" };
  const opener = clean(lines[start]);
  const closer = CLOSERS[opener];
  let end = -1;
  for (let i = markerIdx; i < lines.length; i++) {
    if (clean(lines[i]) === closer) {
      end = i;
      break;
    }
  }
  if (end < 0) return null;

  // GUARD: the block must be OUR header, not the file's own documentation.
  //
  // A file's own block comment can legitimately contain the word "Copyright"
  // (a package-info.java describing a third-party licence, a fixture that
  // quotes a licence). Removing THAT would delete the file's content -- and
  // the symptom is invisible: the file simply loses a paragraph.
  //
  // So the block must carry our exact header wording. The current header is
  // the full MIT text; the R389 header was the short form. Both are accepted,
  // and nothing else is.
  const blockText = lines.slice(start, end + 1).join("\n");
  const isOurs =
    blockText.includes(MARKER) &&
    (blockText.includes("Permission is hereby granted") ||
      blockText.includes("Licensed under the MIT License"));
  if (!isOurs) return null;

  // Drop the header block, plus the blank lines around it. The header was
  // inserted as "<block> <blank> <original>", but the ORIGINAL may or may not
  // have had a blank line adjacent to where the block went.
  //
  // Rather than guess, we enumerate EVERY combination of:
  //   - drop a blank line immediately BEFORE the block?  (yes/no)
  //   - drop a blank line immediately AFTER the block?   (yes/no)
  // and let the caller pick the combination that reproduces the original blob
  // byte-for-byte. Exhaustive enumeration is what makes the removal PROVABLE
  // instead of guesswork — a missing combination shows up as "unverified",
  // never as a silently wrong file.
  const withoutBlock = [...lines.slice(0, start), ...lines.slice(end + 1)];

  const candidates = [];
  const push = (arr) => {
    const key = arr.join("\n");
    if (!candidates.some((c) => c.join("\n") === key)) candidates.push(arr);
  };

  const blankBefore = start > 0 && withoutBlock[start - 1].trim() === "";
  const blankAfter = start < withoutBlock.length && withoutBlock[start].trim() === "";

  for (const dropBefore of [false, true]) {
    if (dropBefore && !blankBefore) continue;
    for (const dropAfter of [false, true]) {
      if (dropAfter && !blankAfter) continue;
      const pre = withoutBlock.slice(0, start);
      if (dropBefore) pre.pop();
      const post = withoutBlock.slice(start);
      if (dropAfter) post.shift();
      push([...pre, ...post]);
    }
  }

  return { candidates, start, end };
}

function main() {
  const files = git("ls-files")
    .split("\n")
    .map((s) => s.trim())
    .filter(Boolean);

  const changed = [];
  const restored = [];
  const couldNotVerify = [];
  const skipped = [];

  for (const rel of files) {
    const name = rel.split("/").pop();
    const dot = name.lastIndexOf(".");
    if (dot < 0) continue;
    const ext = name.slice(dot + 1).toLowerCase();
    if (!exts.has(ext)) continue;

    const p = join(REPO_ROOT, rel);
    let bytes;
    try {
      bytes = readFileSync(p);
    } catch {
      skipped.push(rel);
      continue;
    }

    // ENCODING GUARD — decode STRICTLY. A file that is not valid UTF-8 (e.g.
    // the GBK-encoded Chinese Markdown in this repo) would be mangled by a
    // lenient decode, and writing it back would destroy the original bytes.
    // We skip such files entirely rather than risk corrupting them.
    //
    // THE BOM MUST BE DETECTED FROM THE BYTES, NOT FROM THE DECODED STRING.
    // `new TextDecoder("utf-8")` follows the WHATWG spec and SILENTLY STRIPS
    // a leading BOM — so `raw.charCodeAt(0)` is never 0xFEFF, and a check
    // on the decoded string always says "no BOM". The BOM then gets dropped
    // on write-back, and the file differs from HEAD by exactly one invisible
    // character. 18 files in this repo carry a BOM.
    const hadBom = bytes.length >= 3 && bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf;
    let raw;
    try {
      raw = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
    } catch {
      skipped.push(rel);
      continue;
    }

    if (!raw.includes(MARKER)) {
      skipped.push(rel);
      continue;
    }

    // The EOL must come from the BODY, not from the whole text: a stale CRLF
    // header on an LF body would make the whole file look CRLF, and the
    // removal would then write the file back with the wrong ending.
    // See detectEol in license-header-common.mjs.
    const eol = detectEol(raw);
    // The BOM was already detected from the raw bytes above; the decoder
    // stripped it, so `raw` starts at the first real character.
    const body = raw;

    // A file's header may be a JAVADOC block (Java), a generic BLOCK comment
    // (CSS, HTML, XML, Markdown) or a LINE-comment run (shell, PowerShell,
    // cmd). Try each; the marker line itself tells us which style it is, so at
    // most one will match.
    //
    // JAVADOC must come first: a Java file-level Javadoc is also a "/*" block,
    // so the generic remover would take the whole block — including the
    // file's own description in the package-info.java files.
    const res =
      stripJavadocLicense(body) ?? stripBlockHeader(body) ?? stripLineHeader(body);
    if (!res) {
      skipped.push(rel);
      continue;
    }

    // Find the candidate that matches the ORIGINAL blob exactly.
    //
    // IMPORTANT: HEAD is not always a clean baseline. A concurrent session may
    // have committed a file that ALREADY carried the header (so HEAD contains
    // the header we are trying to remove). In that case the correct target is
    // "HEAD with the header stripped", not "HEAD as-is".
    //
    // So we build TWO candidate sets:
    //   (a) HEAD as-is                      -> the normal case
    //   (b) HEAD with its header stripped   -> HEAD already had a header
    // and pick whichever the removal reproduces exactly.
    let orig = null;
    try {
      orig = git("cat-file", "-p", `HEAD:${rel}`);
    } catch {
      orig = null;
    }

    // Normalise for comparison: strip a leading BOM and unify line endings.
    //
    // The BOM matters: `git cat-file` returns the blob WITH its BOM, while the
    // work file has it stripped off during the line surgery (and put back
    // afterwards). Comparing the two raw would never match, and every
    // BOM-carrying file would be reported "unverified" — a false alarm that
    // hides the real signal.
    const norm = (s) => s.replace(/^\uFEFF/, "").replace(/\r\n/g, "\n");

    // The targets the removal should reproduce. HEAD as-is is the normal case;
    // "HEAD with its header stripped" covers the case where a concurrent
    // session committed a file that ALREADY carried the header.
    const targets = [];
    if (orig !== null) {
      targets.push(norm(orig));
      const origBody = orig.replace(/^\uFEFF/, "");
      for (const stripper of [stripJavadocLicense, stripBlockHeader, stripLineHeader]) {
        const strippedOrig = stripper(origBody);
        if (strippedOrig) {
          for (const c of strippedOrig.candidates) targets.push(norm(c.join("\n")));
        }
      }
    }

    let chosen = null;
    for (const cand of res.candidates) {
      // Compare on NORMALISED text: the blob in git is LF, but the file on
      // disk may be CRLF. Comparing raw strings would report a false
      // mismatch for every CRLF file, which is exactly the case we care about.
      const text = cand.join("\n");
      if (targets.some((t) => norm(text) === t)) {
        chosen = cand.join(eol);
        break;
      }
    }
    if (chosen === null) {
      // No exact match: fall back to the "drop the block and one following
      // blank line" reconstruction, and record it as unverified.
      chosen = res.candidates[0].join(eol);
      couldNotVerify.push(rel);
    } else {
      restored.push(rel);
    }

    if (hadBom) chosen = "\uFEFF" + chosen;
    changed.push(rel);
    if (!dryRun && !checkOnly) writeFileSync(p, chosen, "utf8");
  }

  console.log(`extensions      : ${[...exts].join(", ")}`);
  console.log(`files examined  : ${files.length}`);
  console.log(`header removed  : ${changed.length}`);
  console.log(`  exact match   : ${restored.length}`);
  console.log(`  unverified    : ${couldNotVerify.length}`);
  console.log(`no header found : ${skipped.length}`);

  if (couldNotVerify.length) {
    console.log("\n--- removed, but NOT provably byte-identical to the original ---");
    for (const r of couldNotVerify.slice(0, 30)) console.log("  " + r);
    if (couldNotVerify.length > 30) console.log(`  ... and ${couldNotVerify.length - 30} more`);
  }
  if (verbose) {
    console.log("\n--- changed ---");
    for (const r of changed.slice(0, 30)) console.log("  " + r);
  }

  if (checkOnly) {
    if (couldNotVerify.length) {
      console.log(
        `\n--check FAILED: ${couldNotVerify.length} file(s) would not round-trip byte-identically.`,
      );
      process.exit(1);
    }
    console.log("\n--check OK: every header round-trips byte-identically.");
  }
}

main();
