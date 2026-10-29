#!/usr/bin/env node
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

import { readFileSync, writeFileSync, existsSync, readdirSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { join, relative } from "node:path";
import {
  AUTHOR,
  YEAR,
  MARKER,
  JAVADOC_LINES,
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
const HEADER_LINES = [
  `Copyright (c) ${YEAR} ${AUTHOR}`,
  "",
  "Licensed under the MIT License.",
  "See the LICENSE file in the project root for the full license text.",
];

/**
 * The Javadoc form, for Java.
 *
 * The text and its google-java-format rationale live in
 * `license-header-common.mjs` — ONE source of truth, shared with the removal
 * script. See JAVADOC_LINES there.
 */

/**
 * Per-language comment styles.
 *   line:  prefix for each line, no terminator
 *   block: [open, close] wrapping the whole header
 *   styles: which of {line, block} this file type accepts
 */
const STYLE_BY_EXT = {
  // ---- C-family: // line comments, /* */ blocks ----
  java: { javadoc: true },
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
 * Detect the LEGACY line-comment licence header in a Java file.
 *
 * The very first version of this script wrote the Java header as a `//` run:
 *
 *     // Copyright (c) 2026 maijun
 *     //
 *     // Licensed under the MIT License.
 *     // See the LICENSE file in the project root for the full license text.
 *
 * The header is now a Javadoc block (see JAVADOC_LINES), so a file carrying the
 * legacy form must be MIGRATED, not skipped. `alreadyHasHeader` would happily
 * report "already has a header" for such a file and leave the old form in
 * place — so the two forms have to be told apart explicitly.
 *
 * Returns { start, end } line indices of the legacy run, or null.
 */
function findLegacyJavaLineHeader(lines) {
  // The legacy run is a contiguous `//` comment block starting at the very
  // top of the file (line 0), interrupted by at most blank `//` lines.
  let i = 0;
  // Allow a leading blank line? No — the legacy header was always first.
  if (i >= lines.length) return null;
  if (!/^\s*\/\/ Copyright \(c\) \d{4} \S/.test(lines[i])) return null;

  let end = i;
  while (end + 1 < lines.length) {
    const next = lines[end + 1];
    if (/^\s*\/\/ /.test(next) || /^\s*\/\/$/.test(next)) {
      end++;
      continue;
    }
    break;
  }
  return { start: i, end };
}

/**
 * Render the header for a given style.
 * Returns an array of lines (no trailing newline).
 */
function renderHeader(style) {
  if (style.none) return null; // JSON: cannot carry a comment

  if (style.block) {
    const [open, close] = style.block;
    return [`${open}`, ...HEADER_LINES.map((l) => (l === "" ? "" : `  ${l}`)), close];
  }

  // Line style: a stable, greppable 4-line shape.
  const prefix = style.line;
  return HEADER_LINES.map((l) => (l === "" ? "" : `${prefix}${l}`));
}

/**
 * The google-java-format executable, if one is available.
 *
 * Resolution order:
 *   1. $GJF_JAR                     — an explicit jar path
 *   2. $GJF_HOME / google-java-format-all-deps.jar
 *   3. google-java-format on PATH
 *   4. the Maven local repository (com/google/googlejavaformat)
 *
 * If none of those yield a working tool, the header is still inserted (in the
 * hand-rolled canonical form) and the script says so — a missing formatter
 * must not block the licence work.
 */
function resolveGjf() {
  const envJar = process.env.GJF_JAR;
  if (envJar && existsSync(envJar)) return { kind: "jar", cmd: envJar };

  // The jar checked into the repo, so every developer and CI run has the
  // same formatter version.
  const local = join(REPO_ROOT, "scripts", "tools", "google-java-format-all-deps.jar");
  if (existsSync(local)) return { kind: "jar", cmd: local };

  const home = process.env.GJF_HOME;
  if (home) {
    const p = join(home, "google-java-format-all-deps.jar");
    if (existsSync(p)) return { kind: "jar", cmd: p };
  }

  // Maven local repository
  const repo = process.env.MAVEN_REPO_LOCAL ?? join(process.env.USERPROFILE ?? "", ".m2", "repository");
  const gjfDir = join(repo, "com", "google", "googlejavaformat", "google-java-format");
  if (existsSync(gjfDir)) {
    const versions = readdirSync(gjfDir).sort().reverse();
    for (const v of versions) {
      const p = join(gjfDir, v, `google-java-format-${v}-all-deps.jar`);
      if (existsSync(p)) return { kind: "jar", cmd: p };
    }
  }

  return null;
}

let GJF = null;
let gjfAvailable = false;
let gjfError = null;
try {
  GJF = resolveGjf();
  gjfAvailable = GJF !== null;
} catch (e) {
  gjfAvailable = false;
  gjfError = e instanceof Error ? e.message : String(e);
}

/**
 * Format a Java file with google-java-format and return ONLY the header
 * block (the Javadoc above the package declaration), as an array of lines.
 *
 * Returns null when gjf cannot be run or produces nothing usable — the caller
 * then keeps the hand-rolled header.
 *
 * CACHE: gjf is a JVM launch, and this function used to run once per Java
 * file — 2012 JVM launches for a 2012-file repo, which took ~30 minutes.
 * But the licence header is IDENTICAL for every file: the package name does
 * not appear in it, and gjf's Javadoc layout does not depend on the package
 * declaration. So one run tells us the canonical shape for ALL files.
 *
 * The cache key is the source text of the probe. If two files really did need
 * different shapes, the key would differ and gjf would run again — the cache
 * is an optimisation, never a shortcut that changes the output.
 */
const gjfHeaderCache = new Map();

function formatJavadocWithGjf(source, eol) {
  if (!GJF) return null;
  const cached = gjfHeaderCache.get(source);
  if (cached) return cached.map((l) => l);
  try {
    const stdout = execFileSync(
      "java",
      GJF.kind === "jar" ? ["-jar", GJF.cmd, "-"] : [GJF.cmd, "-"],
      { input: source, encoding: "utf8", maxBuffer: 64 * 1024 * 1024, cwd: REPO_ROOT },
    );
    const lines = stdout.split(/\r?\n/);
    // The header block is everything from the opening delimiter up to and
    // including the closing one, at the very top of the file.
    let start = -1;
    for (let i = 0; i < Math.min(10, lines.length); i++) {
      if (lines[i].trim().startsWith("/**")) {
        start = i;
        break;
      }
    }
    if (start < 0) return null;
    let end = -1;
    for (let i = start + 1; i < lines.length; i++) {
      if (lines[i].trim() === "*/") {
        end = i;
        break;
      }
    }
    if (end < 0) return null;
    const result = lines.slice(start, end + 1);
    gjfHeaderCache.set(source, result);
    return result.map((l) => l);
  } catch {
    return null;
  }
}

/**
 * Build the file-level Javadoc header for a Java source file.
 *
 * Returns the lines to emit immediately above the `package` declaration.
 *
 * If the file ALREADY has a file-level Javadoc (this repo's package-info.java
 * files do), the license block is placed ABOVE it as a SEPARATE block rather
 * than merged into it.
 *
 * Why separate and not merged:
 *
 *  - Merging makes the operation IRREVERSIBLE. google-java-format re-wraps the
 *    old prose to fill 100 columns, so removing the merged licence afterwards
 *    cannot restore the original wrapping — the text differs from HEAD for a
 *    reason that has nothing to do with the licence. Keeping the two blocks
 *    separate means the old prose is never touched, so adding and removing the
 *    licence is a perfect round trip.
 *
 *  - Two blocks read fine here: a file-level licence comment followed by the
 *    file's own documentation comment, both above `package`. This is a
 *    common shape in Apache-licensed Java codebases.
 *
 * When there is no existing Javadoc, the licence block stands alone.
 */
function renderJavaHeader(existingDocLines) {
  const lic = JAVADOC_LINES.map((l) => (l === "*" ? " *" : ` * ${l}`));
  return ["/**", ...lic, " */"];
}

/** Index of the `package` declaration in an array of lines, or -1. */
function pkgIndexOf(lines) {
  return lines.findIndex((l) => /^\s*package\s+[\w.]+\s*;/.test(l));
}

/**
 * Find the file-level comment block immediately above the `package` declaration.
 * Returns its lines (including the open and close delimiters), or null.
 *
 * TWO forms are recognised, and both must be:
 *
 *  a) A real Javadoc block — opener is slash-star-star.
 *  b) A plain block comment — opener is slash-star. This repo has files whose
 *     "file-level doc" is a plain block comment rather than a Javadoc (e.g.
 *     `BashToolR326MkdirPTest.java` opens with a plain block and an
 *     "R326 — ..." note).
 *
 * Why (b) matters: the caller slices the body from this block down to the
 * `package` declaration. If a plain block comment is not recognised, the
 * lines between it and `package` — including that block — are silently
 * DROPPED. The file then loses its own documentation, and no amount of
 * licence-header logic is involved; it is a plain "we cut the wrong lines" bug.
 *
 * The opener must be the LAST opener before the closer — scanning upwards
 * from the closer and stopping at the first opener found is what makes this
 * pick the INNERMOST block, which is the one that actually ends at that
 * closer.
 */
function findFileJavadoc(lines) {
  const pkgIdx = pkgIndexOf(lines);
  if (pkgIdx < 0) return null;
  if (pkgIdx === 0 || lines[pkgIdx - 1].trim() !== "*/") return null;

  let start = -1;
  for (let i = pkgIdx - 1; i >= 0; i--) {
    const t = lines[i].trim();
    if (t.startsWith("/**") || t === "/*") {
      start = i;
      break;
    }
    // Any other content between the opener and the closer means the closer
    // does not belong to a file-level comment block.
    if (t !== "" && !t.startsWith("*")) break;
  }
  if (start < 0) return null;
  return { doc: lines.slice(start, pkgIdx), start, pkgIdx };
}

/**
 * Detect leading lines that MUST stay at the top, per language.
 * Returns { prefixLines: string[], rest: string }
 *
 * See the header comment (points 3, 3a, 3b) for why each rule exists.
 */
function splitLeadingDirectives(content, style) {
  const lines = content.split(/\r?\n/);
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
      const docLines = docText.split(/\r?\n/);
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

    let migratedJava = null;
    if (alreadyHasHeader(raw)) {
      // A file may carry the LEGACY `//` licence header. For Java that form
      // is now obsolete — the header is a Javadoc block — so such a file is
      // MIGRATED rather than skipped. For every other language the legacy
      // form IS the current form, so it is left alone.
      const legacy = style.javadoc
        ? findLegacyJavaLineHeader(body.split(/\r?\n/))
        : null;
      if (!legacy) {
        report.untouched.push(relPosix);
        continue;
      }
      report.migrated.push(relPosix);
      migratedJava = legacy;
    }

    if (style.none) {
      // JSON has no comment syntax. A JSON file cannot legally carry a
      // // comment, so adding one would make it unparseable. Report these
      // instead of corrupting them.
      report.jsonBlocked.push(relPosix);
      missing++;
      continue;
    }

    // Detect BOM and line ending so we preserve both.
    const { prefixLines, rest } = splitLeadingDirectives(body, style);

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
    const out = [];
    if (prefixLines.length) {
      out.push(...prefixLines);
      // Did the original have a blank line between the directives and the body?
      const hadBlankAfterDirectives = /^\n/.test(rest);
      if (hadBlankAfterDirectives) out.push("");
    }

    if (style.javadoc) {
      // Java: the file-level Javadoc sits IMMEDIATELY above the package
      // declaration, with NO blank line between them. That is what
      // google-java-format produces, and what this repo's existing
      // package-info.java files already look like:
      //
      //     /**
      //      * ... text ...
      //      */
      //     package org.example;
      //
      // A blank line there would make gjf re-flow the comment away from the
      // package declaration, so it must not be there.
      //
      // If the file already has a file-level Javadoc, the licence block goes
      // ABOVE it as a separate block (see renderJavaHeader) — the file's own
      // documentation is never touched, so adding and removing the licence is
      // a perfect round trip.
      const restLines = rest.split(/\r?\n/);

      // MIGRATION: drop the legacy `//` licence run before doing anything
      // else. Leaving it in place would put TWO licence headers in the file
      // (the old `//` run and the new Javadoc block), and the file would then
      // no longer match what the removal script expects.
      //
      // The legacy run is always at the very top of the file, so removing it
      // shifts every later line up — but that is precisely the change we want:
      // the file's own content stays byte-identical, only the obsolete header
      // goes away.
      let effectiveLines = restLines;
      if (migratedJava) {
        effectiveLines = [
          ...restLines.slice(0, migratedJava.start),
          ...restLines.slice(migratedJava.end + 1),
        ];
        // The legacy block was followed by a blank line separating it from
        // the package declaration; drop exactly one so the file does not end
        // up with a stray blank line.
        if (
          effectiveLines.length &&
          effectiveLines[migratedJava.start] !== undefined &&
          effectiveLines[migratedJava.start].trim() === ""
        ) {
          effectiveLines = [
            ...effectiveLines.slice(0, migratedJava.start),
            ...effectiveLines.slice(migratedJava.start + 1),
          ];
        }
      }

      const existing = findFileJavadoc(effectiveLines);
      const doc = renderJavaHeader(existing ? existing.doc : null);

      // The body is everything from the existing Javadoc (if any) down to the
      // package declaration.
      const pkgIdx = pkgIndexOf(effectiveLines);
      const body = existing
        ? effectiveLines.slice(existing.start)
        : effectiveLines.slice(pkgIdx >= 0 ? pkgIdx : 0);

      // Let google-java-format decide the FINAL shape of the licence block.
      //
      // gjf has opinions about Javadoc layout (the empty line inside a block
      // is " *", not "  "; the paragraph needs a "<p>" tag). Rather than
      // re-implement those rules, we run gjf on the assembled file and take
      // ITS version of the licence block.
      //
      // Only the LICENCE BLOCK is taken from gjf's output — the rest of the
      // file is left exactly as it was, because this repo is not
      // gjf-formatted (running gjf over a whole file would rewrite 2000
      // files).
      //
      // gjf needs a PARSEABLE file, so it gets the header PLUS the package
      // declaration — a header on its own is not valid Java and gjf would
      // reject it.
      out.push(...doc);
      if (gjfAvailable) {
        // The probe must be PARSEABLE Java: the licence block plus a package
        // declaration. A licence block on its own is not valid Java.
        //
        // The package name is deliberately a FIXED placeholder, not the file's
        // real package: the licence header does not mention the package, and
        // gjf's Javadoc layout does not depend on it. Using a constant keeps
        // the probe identical for every file, which lets formatJavadocWithGjf
        // hit its cache — one gjf run for the whole repo instead of 2012.
        const probe = [...out, "package probe;"].join("\n");
        const formatted = formatJavadocWithGjf(probe, eol);
        if (formatted) {
          out.length = 0;
          out.push(...formatted);
        }
      }
      out.push(...body);
    } else {
      out.push(...renderHeader(style));
      out.push("");
      out.push(rest.replace(/^\n+/, ""));
    }

    let next = out.join(eol);
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
  console.log(`migrated (legacy // -> Javadoc): ${report.migrated.length}`);
  console.log(`${checkOnly || dryRun ? "would change" : "changed"}       : ${report.changed.length}`);
  console.log(`JSON (no comment syntax, exempt): ${report.jsonBlocked.length}`);
  console.log(`not valid UTF-8 (skipped, untouched): ${report.nonUtf8.length}`);
  console.log(
    `google-java-format: ${gjfAvailable ? "available (Java headers are gjf-canonical)" : "NOT FOUND" + (gjfError ? ` (${gjfError})` : "")}`,
  );

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
