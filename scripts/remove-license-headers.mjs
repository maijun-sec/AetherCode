#!/usr/bin/env node
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
import { MARKER, detectEol } from "./license-header-common.mjs";

const REPO_ROOT = process.cwd();

const args = process.argv.slice(2);
const dryRun = args.includes("--dry-run");
const verbose = args.includes("--verbose");

/**
 * Which extensions to operate on.
 *
 * Parsed by walking the argument list and consuming the token after each
 * "--ext". A filter/map over the array would also pick up the "--ext" flags
 * themselves, and an extension called "--ext" matches nothing.
 *
 * Default: md + xml + shell + css + cmd + java.
 */
const exts = new Set();
for (let i = 0; i < args.length; i++) {
  if (args[i] === "--ext" && args[i + 1]) {
    exts.add(args[i + 1].toLowerCase());
    i++;
  }
}
if (exts.size === 0) {
  for (const e of ["md", "xml", "sh", "bash", "zsh", "css", "cmd", "bat", "java"]) {
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

  // The header is marker / blank / sentence / sentence. The blank line is
  // optional (a file whose first line was a shebang and second the marker
  // has none).
  const SENT1 = `${prefix}Licensed under the MIT License.`;
  const SENT2 = `${prefix}See the LICENSE file in the project root for the full license text.`;

  let end = -1;
  // shape A: marker, blank, s1, s2
  if (
    markerIdx + 3 < lines.length &&
    lines[markerIdx + 1].trim() === "" &&
    lines[markerIdx + 2].trim() === SENT1.trim() &&
    lines[markerIdx + 3].trim() === SENT2.trim()
  ) {
    end = markerIdx + 3;
  } else if (
    // shape B: marker, s1, s2
    markerIdx + 2 < lines.length &&
    lines[markerIdx + 1].trim() === SENT1.trim() &&
    lines[markerIdx + 2].trim() === SENT2.trim()
  ) {
    end = markerIdx + 2;
  }
  if (end < 0) return null;

  // The header we INSERT is FIVE lines: marker / blank / s1 / s2 / blank.
  // The trailing blank is the separator between the header and the body.
  //
  // So a complete removal must consume it too — otherwise the file is left
  // with a stray blank line where the header was, and the round trip is not
  // byte-exact.
  //
  // This was a real bug: the candidates were enumerated correctly (the
  // "drop the following blank" variant existed and was a valid target), but
  // the matcher picked the FIRST candidate that matched ANY target — and the
  // first candidate is the one that KEEPS the blank. So the residue blank
  // always won.
  //
  // The fix is to make the removal itself consume the trailing blank, so the
  // "keep the blank" candidate is no longer the natural answer.
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

  return { candidates, start, end };
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
    if (!dryRun) writeFileSync(p, chosen, "utf8");
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
}

main();
