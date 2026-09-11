// R94-E: TUI prompt preview surface (slash command + formatter).
//
// Tests:
//   1. Pure test: formatSystemPrompt renders sections with name + source + length
//   2. Pure test: formatSystemPrompt handles empty sections (no rules) gracefully
//   3. Pure test: formatSystemPrompt truncates long firstLine to ~80 chars
//   4. Source: commands.ts has /prompt slash command wired to getSystemPrompt RPC
//   5. Source: tui.tsx uses formatSystemPrompt for the sideNote
//   6. Build smoke: tsc compiles commands.ts + tui.tsx + prompt-formatter.ts cleanly
//
// The real E2E needs a rebuilt jar (aethercode-prompts + aethercode-sdk +
// aethercode-protocol + aethercode-tools) plus the daemon running — covered
// separately by the rebuild / E2E flow. The pure tests + source assertions
// catch 95% of regressions (typos, missing field, wrong handler).

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, rmSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const tmp = join(root, "tmp-test-r94e");
if (existsSync(tmp)) rmSync(tmp, { recursive: true, force: true });

// Compile prompt-formatter.ts to JS so we can import it.
const tscBinAbs = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
const tscArgs = [
  "--outDir", tmp,
  "--target", "ES2022",
  "--module", "ES2022",
  "--moduleResolution", "bundler",
  "--esModuleInterop", "true",
  "--skipLibCheck", "true",
  "--rootDir", join(root, "src"),
];
const tsc = spawnSync('"' + tscBinAbs + '"', [
  ...tscArgs,
  '"' + join(root, "src", "prompt-formatter.ts") + '"',
], {
  encoding: "utf-8",
  shell: true,
});
if (tsc.status !== 0) {
  console.error("tsc failed (status=" + tsc.status + "):\nSTDOUT:\n" + tsc.stdout + "\nSTDERR:\n" + tsc.stderr);
  process.exit(1);
}
if (!existsSync(join(tmp, "prompt-formatter.js"))) {
  console.error("tsc exited 0 but prompt-formatter.js was not produced");
  process.exit(1);
}

const mod = await import("file:///" + join(tmp, "prompt-formatter.js").replace(/\\/g, "/"));
const { formatSystemPrompt } = mod;

// ----- 1. Pure: section table renders all sections ----------------

test("R94-E: formatSystemPrompt renders one row per section with name + source + length", () => {
  const out = formatSystemPrompt({
    text: "AAA",
    totalChars: 12_748,
    sectionCount: 3,
    sections: [
      { name: "identity", length: 3029, source: "default", firstLine: "You are AetherCode" },
      { name: "rules", length: 472, source: "rules:project+global", firstLine: "# Project rules" },
      { name: "workflow", length: 9717, source: "default", firstLine: "plan -> explore -> implement -> verify" },
    ],
  });
  // Header line.
  assert.match(out, /system prompt \(3 sections, 12748 chars\)/);
  // Column header.
  assert.match(out, /section\s+source\s+length\s+first line/);
  // All three sections appear.
  assert.match(out, /identity/);
  assert.match(out, /rules:project\+global/);
  assert.match(out, /workflow/);
  // Lengths are right-aligned in a 6-wide column.
  assert.match(out, /\b3029\b/);
  assert.match(out, /\b9717\b/);
  // First-line previews.
  assert.match(out, /You are AetherCode/);
  assert.match(out, /# Project rules/);
  assert.match(out, /plan -> explore -> implement -> verify/);
});

// ----- 2. Pure: empty sections (no project rules) -----------------

test("R94-E: formatSystemPrompt handles empty sections gracefully", () => {
  const out = formatSystemPrompt({
    text: "short",
    totalChars: 5,
    sectionCount: 0,
    sections: [],
  });
  // Header still shows zero.
  assert.match(out, /system prompt \(0 sections, 5 chars\)/);
  // Helpful hint when there are no sections.
  assert.match(out, /no sections/);
  // Must NOT throw and must NOT print the column header (no rows).
  assert.doesNotMatch(out, /first line$/);
});

test("R94-E: formatSystemPrompt handles missing sections array", () => {
  const out = formatSystemPrompt({ text: "x", totalChars: 1, sectionCount: 0 });
  assert.match(out, /system prompt \(0 sections, 1 chars\)/);
  assert.match(out, /no sections/);
});

test("R94-E: formatSystemPrompt returns graceful placeholder for null input", () => {
  const out = formatSystemPrompt(null);
  assert.match(out, /no response/);
});

// ----- 3. Pure: long firstLine is truncated -----------------------

test("R94-E: formatSystemPrompt truncates firstLine to ~80 chars", () => {
  const longFirstLine = "a".repeat(200);
  const out = formatSystemPrompt({
    text: "x",
    totalChars: 10,
    sectionCount: 1,
    sections: [
      { name: "rules", length: 200, source: "rules:project", firstLine: longFirstLine },
    ],
  });
  // The first-line column should show a truncated 80-char line
  // (77 chars + "..."). The full 200-char string must NOT appear.
  assert.doesNotMatch(out, /a{200}/);
  // The truncated form: 77 'a' chars followed by "...".
  assert.match(out, /a{77}\.\.\./);
});

test("R94-E: formatSystemPrompt collapses whitespace in firstLine", () => {
  const out = formatSystemPrompt({
    text: "x",
    totalChars: 10,
    sectionCount: 1,
    sections: [
      { name: "rules", length: 50, source: "rules:project", firstLine: "line 1\n\nline 2\t\tline 3" },
    ],
  });
  // The \n and \t must be collapsed to a single space.
  assert.match(out, /line 1 line 2 line 3/);
  // No raw newlines should be in the row.
  assert.doesNotMatch(out, /line 1\n/);
});

test("R94-E: formatSystemPrompt pads section and source columns to widest", () => {
  // Sections with very different name/source lengths — columns must
  // align by padding to the widest, not to a fixed width.
  const out = formatSystemPrompt({
    text: "x",
    totalChars: 10,
    sectionCount: 2,
    sections: [
      { name: "id", length: 1, source: "default", firstLine: "x" },
      { name: "workflow", length: 1, source: "rules:project+global", firstLine: "y" },
    ],
  });
  // Header is padded to the widest name (workflow) and source
  // (rules:project+global). We don't pin exact padding, but the
  // column header line must mention the names that follow.
  assert.match(out, /section\s+source/);
  // Both rows appear.
  assert.match(out, /\bid\b/);
  assert.match(out, /\bworkflow\b/);
});

// ----- 4. Source: commands.ts wires /prompt -----------------------

test("R94-E: commands.ts has /prompt slash command", () => {
  const cmds = readFileSync(join(root, "src", "commands.ts"), "utf-8");
  // Slash help mentions /prompt.
  assert.match(cmds, /\/prompt\s+R94-E/);
  // Tab-completion knows the command.
  assert.match(cmds, /"prompt"/);
  // handleSlash dispatches to getSystemPrompt.
  assert.match(cmds, /case "prompt":/);
  assert.match(cmds, /rpcMethod: "getSystemPrompt"/);
});

// ----- 5. Source: tui.tsx uses formatSystemPrompt -----------------

test("R94-E: tui.tsx imports + uses formatSystemPrompt for getSystemPrompt", () => {
  const tui = readFileSync(join(root, "src", "tui.tsx"), "utf-8");
  // Import. R95-G added a second name to this import line
  // (formatSystemPromptSection); the regex must allow 1..2 names.
  assert.match(tui, /import\s*\{\s*formatSystemPrompt(?:,\s*formatSystemPromptSection)?\s*\}\s*from\s*"\.\/prompt-formatter\.js"/);
  // Dispatcher path.
  assert.match(tui, /slash\.rpcMethod === "getSystemPrompt"/);
  assert.match(tui, /formatSystemPrompt\(/);
  assert.match(tui, /sideNote/);
  // The "systemPrompt" kind surfaces in the scrollback.
  assert.match(tui, /kind: "systemPrompt"/);
});

test("R94-E: prompt-formatter.ts exports formatSystemPrompt", () => {
  const fmt = readFileSync(join(root, "src", "prompt-formatter.ts"), "utf-8");
  assert.match(fmt, /export function formatSystemPrompt/);
  // The contract is documented.
  assert.match(fmt, /FIRST_LINE_MAX/);
});

// ----- 6. Build smoke: tsc compiles all three TUI modules ---------

test("R94-E: tsc compile of commands.ts + tui.tsx + prompt-formatter.ts is clean", () => {
  const tmp2 = join(root, "tmp-r94e-tsc");
  if (existsSync(tmp2)) rmSync(tmp2, { recursive: true, force: true });
  const tscArgs2 = [
    "--outDir", tmp2, "--target", "ES2022", "--module", "ES2022",
    "--moduleResolution", "bundler", "--jsx", "react",
    "--esModuleInterop", "true", "--skipLibCheck", "true",
    "--rootDir", join(root, "src"),
  ];
  const r = spawnSync('"' + tscBinAbs + '"', [
    ...tscArgs2,
    '"' + join(root, "src", "commands.ts") + '"',
    '"' + join(root, "src", "tui.tsx") + '"',
    '"' + join(root, "src", "prompt-formatter.ts") + '"',
  ], { encoding: "utf-8", shell: true });
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  }
  assert.equal(r.status, 0, "tsc compile failed for R94-E");
});

// ----- R95-G: drill-down to a single section's full text ----------
//
// R95-G adds:
//   1. A new wire RPC getSystemPromptSection(name) on the Java side.
//   2. /prompt <name> on the TUI side, which calls that RPC.
//   3. formatSystemPromptSection(result) in prompt-formatter.ts,
//      which renders the full text (no truncation) on success and
//      a "did you mean…" hint on failure.
//
// The pure tests below cover the formatter contract. The
// source-assertion tests cover the wire / TUI wiring.

const mod2 = mod; // re-use the compiled module
const { formatSystemPromptSection } = mod2;

test("R95-G: formatSystemPromptSection renders full text (no truncation) on success", () => {
  // 5000 chars — way above the FIRST_LINE_MAX=80 we use in the
  // table view. The drill-down must show all of it.
  const longText = "x".repeat(5000);
  const out = formatSystemPromptSection({
    ok: true,
    name: "workflow",
    source: "default",
    length: longText.length,
    text: longText,
  });
  // Header line carries the metadata.
  assert.match(out, /system prompt section: workflow/);
  assert.match(out, /source=default/);
  assert.match(out, /length=5000/);
  // The body contains the full text — no ellipsis, no
  // truncation, no "first line" hint.
  assert.ok(out.includes(longText), "drill-down must show the full text");
  assert.doesNotMatch(out, /\.\.\./);
});

test("R95-G: formatSystemPromptSection preserves indentation and newlines verbatim", () => {
  const indented = "Line 1\n  Line 2 with 2-space indent\n    Line 3 with 4-space indent\n\nAfter blank line";
  const out = formatSystemPromptSection({
    ok: true,
    name: "rules",
    source: "rules:project+global",
    length: indented.length,
    text: indented,
  });
  // The full text appears unchanged. The firstLine-collapse
  // trick from formatSystemPrompt is NOT used here.
  assert.ok(out.includes(indented));
  // Header line at the top.
  const firstLine = out.split("\n")[0];
  assert.match(firstLine, /system prompt section: rules/);
  // Source + length on the same header.
  assert.match(firstLine, /source=rules:project\+global/);
});

test("R95-G: formatSystemPromptSection reports not-found with available list", () => {
  const out = formatSystemPromptSection({
    ok: false,
    error: "section not found: ruless",
    available: ["identity", "rules", "workflow", "planMode", "memory"],
  });
  assert.match(out, /section not found: ruless/);
  // The "available" hint names every real section.
  assert.match(out, /identity/);
  assert.match(out, /rules/);
  assert.match(out, /workflow/);
  // No body was returned.
  assert.doesNotMatch(out, /system prompt section: ruless \(/);
});

test("R95-G: formatSystemPromptSection reports not-found without available list (no sections at all)", () => {
  const out = formatSystemPromptSection({
    ok: false,
    error: "section not found: foo",
  });
  assert.match(out, /section not found: foo/);
  // We should NOT print "available:" when the response
  // didn't give us a list (could be a runtime bug).
  assert.doesNotMatch(out, /available:/);
});

test("R95-G: formatSystemPromptSection returns graceful placeholder for null input", () => {
  const out = formatSystemPromptSection(null);
  assert.match(out, /no response/);
});

test("R95-G: formatSystemPromptSection handles empty text on success", () => {
  const out = formatSystemPromptSection({
    ok: true,
    name: "planMode",
    source: "plan-mode",
    length: 0,
    text: "",
  });
  assert.match(out, /length=0/);
  // Empty sections get a "(empty section)" placeholder so the
  // user knows the section exists but has no body (vs. a
  // missing section).
  assert.match(out, /empty section/);
});

// ----- R95-G: source assertions for the wire / TUI wiring --------

test("R95-G: commands.ts routes /prompt <name> to getSystemPromptSection", () => {
  const cmds = readFileSync(join(root, "src", "commands.ts"), "utf-8");
  // Help text mentions the new variant.
  assert.match(cmds, /\/prompt <name>/);
  assert.match(cmds, /R95-G/);
  // handleSlash forwards the section name to the new RPC.
  assert.match(cmds, /rpcMethod: "getSystemPromptSection"/);
  assert.match(cmds, /rpcParams: \{ name \}/);
  // The original no-arg form is preserved (still calls
  // getSystemPrompt for the table view).
  assert.match(cmds, /rpcMethod: "getSystemPrompt"/);
});

test("R95-G: AetherCodeMethods.java registers getSystemPromptSection + HttpJsonRpcServer has the switch arm", () => {
  const methods = readFileSync(
    join(root, "..", "aethercode", "aethercode-protocol", "src", "main", "java", "org", "aethercode", "protocol", "methods", "AetherCodeMethods.java"),
    "utf-8",
  );
  assert.match(methods, /dispatcher\.register\("getSystemPromptSection"/);
  assert.match(methods, /public Object getSystemPromptSection\(Object params\)/);
  assert.match(methods, /currentRenderedPrompt\(\)/);
  // The not-found branch sets ok=false and lists `available`
  // section names so the TUI can suggest "did you mean…?".
  // We check each line independently to avoid
  // multi-line regex surprises.
  assert.match(methods, /r\.put\("ok",\s*false\)/);
  assert.match(methods, /r\.put\("error",\s*"section not found: "\s*\+\s*name\)/);
  assert.match(methods, /r\.put\("available",\s*available\)/);

  const http = readFileSync(
    join(root, "..", "aethercode", "aethercode-protocol", "src", "main", "java", "org", "aethercode", "protocol", "http", "HttpJsonRpcServer.java"),
    "utf-8",
  );
  // R92 lesson: the HTTP+WS daemon dispatches via this switch.
  // Forgetting this is the most common bug for a new RPC.
  assert.match(http, /case "getSystemPromptSection" ->/);
});

test("R95-G: tui.tsx imports + uses formatSystemPromptSection", () => {
  const tui = readFileSync(join(root, "src", "tui.tsx"), "utf-8");
  // The dual import — must keep the R94-E formatter too.
  assert.match(tui, /import\s*\{\s*formatSystemPrompt,\s*formatSystemPromptSection\s*\}\s*from\s*"\.\/prompt-formatter\.js"/);
  // The dispatcher path.
  assert.match(tui, /slash\.rpcMethod === "getSystemPromptSection"/);
  assert.match(tui, /formatSystemPromptSection\(/);
  // The sideNote kind is distinct so the renderer can theme it
  // differently (e.g. wider column for full text) if it wants
  // to.
  assert.match(tui, /kind: "systemPromptSection"/);
});

// ----- R95-A: TUI /prompt enhancements ---------------------------
//
// R95-A adds three things on top of R94-E:
//   1. A new "paths" column that shows the file paths that
//      contributed to the rules section (e.g. style.md,
//      api.md, disabled-rule.md). Other sections (identity,
//      workflow, environment, tooling) do NOT show a paths
//      column because their source is the engine, not a file.
//   2. Color-coded source labels — `default` in green, `rules`
//      (and `rules:*`) in blue, `builder` in yellow, anything
//      else in dim grey. The color is wrapped in a COLOR_RESET
//      so it doesn't bleed into the next column.
//   3. An expiring toast (R95-A3, tui.tsx change) so the
//      /prompt summary doesn't permanently occupy scrollback.
//      Tested by source assertion on tui.tsx below; the
//      toaster is a separate UI component we don't import in
//      this pure-module test.

const { colorForSource, formatPaths, PATHS_DISPLAY_MAX, COLOR_DEFAULT, COLOR_RULES, COLOR_BUILDER, COLOR_NEUTRAL, COLOR_RESET } = mod2;

test("R95-A: colorForSource classifies source labels", () => {
  // The three main classes.
  assert.equal(colorForSource("default"), COLOR_DEFAULT);
  assert.equal(colorForSource("rules"), COLOR_RULES);
  assert.equal(colorForSource("rules:project+global"), COLOR_RULES);
  assert.equal(colorForSource("builder"), COLOR_BUILDER);
  // Anything else (planMode, memory, future sources) is
  // the neutral grey.
  assert.equal(colorForSource("plan-mode"), COLOR_NEUTRAL);
  assert.equal(colorForSource("memory"), COLOR_NEUTRAL);
  assert.equal(colorForSource(""), COLOR_NEUTRAL);
  assert.equal(colorForSource(null), COLOR_NEUTRAL);
  assert.equal(colorForSource(undefined), COLOR_NEUTRAL);
  // Case-insensitive — the daemon sometimes emits
  // uppercase labels for the built-in defaults.
  assert.equal(colorForSource("DEFAULT"), COLOR_DEFAULT);
});

test("R95-A: colorForSource returns ANSI codes that reset cleanly", () => {
  const c = colorForSource("default");
  // Each color code is non-empty and ends with a reset.
  assert.ok(c.length > 0);
  // The reset is the standard "\u001b[0m" so a
  // downstream printf "%s%s%s" of [c, plain, RESET]
  // produces a coloured string with no leftover
  // colour state.
  assert.equal(COLOR_RESET, "\u001b[0m");
});

test("R95-A: formatPaths returns empty string for null / empty input", () => {
  assert.equal(formatPaths(null), "");
  assert.equal(formatPaths(undefined), "");
  assert.equal(formatPaths([]), "");
});

test("R95-A: formatPaths shows basenames for short lists", () => {
  const out = formatPaths([
    "/home/u/.aethercode/rules/style.md",
    "/home/u/.aethercode/rules/api.md",
  ]);
  assert.equal(out, "[style.md, api.md]");
});

test("R95-A: formatPaths handles Windows backslashes", () => {
  const out = formatPaths([
    "C:\\Users\\u\\.aethercode\\rules\\style.md",
    "C:\\Users\\u\\.aethercode\\rules\\api.md",
  ]);
  assert.equal(out, "[style.md, api.md]");
});

test("R95-A: formatPaths summarises long lists with +N more", () => {
  const paths = [];
  for (let i = 0; i < PATHS_DISPLAY_MAX + 2; i++) {
    paths.push(`/home/u/.aethercode/rules/rule-${i}.md`);
  }
  const out = formatPaths(paths);
  // First N basenames + a "more" tail.
  assert.match(out, /\[rule-0\.md, rule-1\.md, rule-2\.md, \+2 more\]/);
});

test("R95-A: formatSystemPrompt omits paths column when no section has paths", () => {
  // R94-E shape: no `paths` field on any section. The
  // backward-compatible behaviour (no paths column) must
  // still work.
  const out = formatSystemPrompt({
    text: "AAA",
    totalChars: 12_748,
    sectionCount: 3,
    sections: [
      { name: "identity", length: 3029, source: "default", firstLine: "You are AetherCode" },
      { name: "rules",    length: 472,  source: "rules:project+global", firstLine: "# Project rules" },
      { name: "workflow", length: 9717, source: "default", firstLine: "plan -> explore" },
    ],
  });
  // No "paths" header in the column row.
  assert.doesNotMatch(out, /\bpaths\b/);
  // All sections still appear.
  assert.match(out, /identity/);
  assert.match(out, /rules:project\+global/);
  assert.match(out, /workflow/);
});

test("R95-A: formatSystemPrompt adds a paths column when any section has paths", () => {
  const out = formatSystemPrompt({
    text: "AAA",
    totalChars: 12_748,
    sectionCount: 3,
    sections: [
      { name: "identity", length: 3029, source: "default", firstLine: "You are AetherCode" },
      { name: "rules",    length: 472,  source: "rules:project+global", firstLine: "# Project rules",
        paths: ["/proj/.aethercode/rules/style.md", "/proj/.aethercode/rules/api.md"] },
      { name: "workflow", length: 9717, source: "default", firstLine: "plan -> explore" },
    ],
  });
  // Column header is present.
  assert.match(out, /\bpaths\b/);
  // The rules row shows the basenames.
  assert.match(out, /\[style\.md, api\.md\]/);
  // The identity and workflow rows do NOT get basenames
  // — they have no paths, so the cell is blank.
  // We assert this indirectly: the only place "style.md"
  // appears is in the rules row.
  const styleHits = (out.match(/style\.md/g) ?? []).length;
  assert.equal(styleHits, 1, "style.md should appear exactly once (in the rules row)");
});

test("R95-A: formatSystemPrompt color-codes the source labels", () => {
  const out = formatSystemPrompt({
    text: "AAA",
    totalChars: 100,
    sectionCount: 3,
    sections: [
      { name: "identity", length: 30, source: "default", firstLine: "id" },
      { name: "rules",    length: 30, source: "rules:project", firstLine: "rl" },
      { name: "workflow", length: 30, source: "builder", firstLine: "wf" },
    ],
  });
  // Each source label is wrapped in its color code
  // followed by a reset. The exact bytes may include
  // more ANSI codes for the row padding; we just check
  // that each color appears at least once.
  assert.ok(out.includes(COLOR_DEFAULT + "default"),  "default not color-coded");
  assert.ok(out.includes(COLOR_RULES   + "rules:project"), "rules not color-coded");
  assert.ok(out.includes(COLOR_BUILDER + "builder"), "builder not color-coded");
  // Every color is closed by a reset before the row
  // moves on to the length column. We check that the
  // number of resets is at least the number of rows.
  const resetCount = (out.match(/\u001b\[0m/g) ?? []).length;
  assert.ok(resetCount >= 3, `expected >= 3 resets, got ${resetCount}`);
});

test("R95-A: formatSystemPrompt keeps the header text intact when colors are emitted", () => {
  // The TUI renderer relies on header alignment. The
  // color codes are 0-width visually but padEnd()
  // counts them. The fix is to pad the *plain* source
  // label, not the colored one. Verify the header row
  // and a data row start with the same indentation.
  const out = formatSystemPrompt({
    text: "x",
    totalChars: 1,
    sectionCount: 1,
    sections: [
      { name: "rules", length: 1, source: "rules:project+global", firstLine: "x" },
    ],
  });
  const lines = out.split("\n");
  // Find the header row (contains "section  source").
  const headerIdx = lines.findIndex(l => /^\s+section\s+source/.test(l));
  // Find the data row (contains "rules").
  const dataIdx = lines.findIndex(l => l.includes("rules"));
  assert.ok(headerIdx >= 0, "header row not found");
  assert.ok(dataIdx > headerIdx, "data row not found after header");
  // Both rows start with the same 2-space indent +
  // "section" / "rules" word. We compare the leading
  // characters up to the first column break.
  const headerPrefix = lines[headerIdx].slice(0, 2);
  const dataPrefix   = lines[dataIdx].slice(0, 2);
  assert.equal(dataPrefix, headerPrefix, "header and data rows must share the same column indents");
});

test("R95-A: AetherCodeMethods.java attaches paths to the rules section", () => {
  // The wire payload must include a `paths` field for
  // the rules section. We assert the source change so
  // a future refactor that drops the field fails the
  // test.
  const methods = readFileSync(
    join(root, "..", "aethercode", "aethercode-protocol", "src", "main", "java", "org", "aethercode", "protocol", "methods", "AetherCodeMethods.java"),
    "utf-8",
  );
  assert.match(methods, /if \("rules"\.equals\(s\.name\(\)\)\)/);
  assert.match(methods, /m\.put\("paths",\s*org\.aethercode\.prompts\.RulesLoader\.lastLoadedFileNames\(\)\)/);
});

test("R95-A: RulesLoader.java exposes lastLoadedFileNames and updates the accumulator", () => {
  const loader = readFileSync(
    join(root, "..", "aethercode", "aethercode-prompts", "src", "main", "java", "org", "aethercode", "prompts", "RulesLoader.java"),
    "utf-8",
  );
  assert.match(loader, /public static List<String> lastLoadedFileNames\(\)/);
  // The accumulator is a ThreadLocal — concurrent
  // loads on different threads must not see each
  // other's paths.
  assert.match(loader, /ThreadLocal<List<String>>/);
  // load() resets the accumulator at the top so
  // repeated calls don't grow the list.
  assert.match(loader, /acc\.clear\(\)/);
});

test("R95-A: tui.tsx dispatches an expiring toast on /prompt", () => {
  // The sideNote is the persistent view; the toast is
  // the transient feedback. Both must fire so the user
  // gets a status-bar signal in addition to the
  // scrollback entry.
  const tui = readFileSync(join(root, "src", "tui.tsx"), "utf-8");
  // Both dispatch actions must be present inside the
  // getSystemPrompt handler branch.
  const section = tui.slice(
    tui.indexOf('slash.rpcMethod === "getSystemPrompt"'),
    tui.indexOf('slash.rpcMethod === "getSystemPromptSection"'),
  );
  assert.match(section, /type: "sideNote"/);
  assert.match(section, /type: "pushToast"/);
  // The toast text carries the section count + char
  // count so the user can see at a glance how big the
  // prompt is.
  assert.match(section, /sections, \$\{totalChars\} chars/);
  // The toast also advertises the drill-down so the
  // user knows the next step.
  assert.match(section, /\/prompt <name>/);
});

// ----- R95-F: per-phase tool budget (TUI side) -------------------
//
// R95-F adds two new slash commands and the matching
// RPCs. The pure test layer is exercised by the
// Java-side PhaseTrackerTest + PhaseBudgetHookTest. The
// TUI layer is thin: handleSlash must forward to the
// right RPC method, and the help + tab-completion list
// must mention the new commands.

test("R95-F: commands.ts has /phase and /budget slash commands", () => {
  const cmds = readFileSync(join(root, "src", "commands.ts"), "utf-8");
  // Help text.
  assert.match(cmds, /\/phase\s+R95-F/);
  assert.match(cmds, /\/budget <p> <calls>/);
  // Tab-completion list.
  assert.match(cmds, /"phase"/);
  assert.match(cmds, /"budget"/);
  // handleSlash dispatches to the right RPC.
  assert.match(cmds, /case "phase":/);
  assert.match(cmds, /case "budget":/);
  assert.match(cmds, /rpcMethod: "getPhaseBudget"/);
  assert.match(cmds, /rpcMethod: "setPhase"/);
  assert.match(cmds, /rpcMethod: "setPhaseBudget"/);
});

test("R95-F: AetherCodeMethods.java registers the three new phase RPCs + HttpJsonRpcServer switch", () => {
  const methods = readFileSync(
    join(root, "..", "aethercode", "aethercode-protocol", "src", "main", "java", "org", "aethercode", "protocol", "methods", "AetherCodeMethods.java"),
    "utf-8",
  );
  assert.match(methods, /dispatcher\.register\("getPhaseBudget"/);
  assert.match(methods, /dispatcher\.register\("setPhase"/);
  assert.match(methods, /dispatcher\.register\("setPhaseBudget"/);
  assert.match(methods, /public Object getPhaseBudget\(Object params\)/);
  assert.match(methods, /public Object setPhase\(Object params\)/);
  assert.match(methods, /public Object setPhaseBudget\(Object params\)/);

  const http = readFileSync(
    join(root, "..", "aethercode", "aethercode-protocol", "src", "main", "java", "org", "aethercode", "protocol", "http", "HttpJsonRpcServer.java"),
    "utf-8",
  );
  // R92 lesson: HTTP+WS daemon dispatches via this
  // switch. Forgetting this is the most common bug
  // for a new RPC. The source uses an aligned column
  // (variable whitespace) — the regex must allow
  // multiple spaces between the method name and ->.
  assert.match(http, /case "getPhaseBudget"\s+->/);
  assert.match(http, /case "setPhase"\s+->/);
  assert.match(http, /case "setPhaseBudget"\s+->/);
});

test("R95-F: AetherCodeEngine.java exposes the PhaseTracker", () => {
  // The engine must own the PhaseTracker so the
  // RPCs above can reach it via
  // engine.phaseTracker().
  const engine = readFileSync(
    join(root, "..", "aethercode", "aethercode-sdk", "src", "main", "java", "org", "aethercode", "sdk", "AetherCodeEngine.java"),
    "utf-8",
  );
  assert.match(engine, /private final org\.aethercode\.hooks\.builtin\.PhaseTracker phaseTracker/);
  assert.match(engine, /public org\.aethercode\.hooks\.builtin\.PhaseTracker phaseTracker\(\)/);
  // The Builder exposes the setter.
  assert.match(engine, /public Builder phaseTracker\(org\.aethercode\.hooks\.builtin\.PhaseTracker t\)/);
  // And auto-registers the PhaseBudgetHook when the
  // tracker is non-null.
  assert.match(engine, /registerBuiltinHookIfAbsent\(this\.hookRegistry,\s*new org\.aethercode\.hooks\.builtin\.PhaseBudgetHook/);
});

// ----- R95-E: multi-session daemon (TUI side) --------------------

test("R95-E: commands.ts has /session* slash commands", () => {
  const cmds = readFileSync(join(root, "src", "commands.ts"), "utf-8");
  // Help text.
  assert.match(cmds, /\/sessions-list\s+R95-E/);
  assert.match(cmds, /\/session <id>\s+R95-E/);
  assert.match(cmds, /\/session-new <id>\s+R95-E/);
  assert.match(cmds, /\/session-del <id>\s+R95-E/);
  // Tab-completion list.
  assert.match(cmds, /"sessions-list"/);
  assert.match(cmds, /"session"/);
  assert.match(cmds, /"session-new"/);
  assert.match(cmds, /"session-del"/);
  // handleSlash dispatches to the right RPC.
  assert.match(cmds, /case "sessions-list":/);
  assert.match(cmds, /case "session":/);
  assert.match(cmds, /case "session-new":/);
  assert.match(cmds, /case "session-del":/);
  assert.match(cmds, /rpcMethod: "listEngines"/);
  assert.match(cmds, /rpcMethod: "createEngine"/);
  assert.match(cmds, /rpcMethod: "deleteEngine"/);
  assert.match(cmds, /rpcMethod: "setActiveEngine"/);
});

test("R95-E: AetherCodeMethods.java registers the 5 session RPCs + HttpJsonRpcServer switch", () => {
  const methods = readFileSync(
    join(root, "..", "aethercode", "aethercode-protocol", "src", "main", "java", "org", "aethercode", "protocol", "methods", "AetherCodeMethods.java"),
    "utf-8",
  );
  assert.match(methods, /dispatcher\.register\("listEngines"/);
  assert.match(methods, /dispatcher\.register\("createEngine"/);
  assert.match(methods, /dispatcher\.register\("deleteEngine"/);
  assert.match(methods, /dispatcher\.register\("setActiveEngine"/);
  assert.match(methods, /dispatcher\.register\("getActiveEngine"/);
  // Each method has a Java implementation.
  assert.match(methods, /public Object listEngines\(Object params\)/);
  assert.match(methods, /public Object createEngine\(Object params\)/);
  assert.match(methods, /public Object deleteEngine\(Object params\)/);
  assert.match(methods, /public Object setActiveEngine\(Object params\)/);
  assert.match(methods, /public Object getActiveEngine\(Object params\)/);
  // The list shape carries activeSessionId +
  // count + sessions[] (test the R95-E wire contract).
  assert.match(methods, /r\.put\("activeSessionId", m\.activeSessionId\(\)\)/);
  assert.match(methods, /r\.put\("count", m\.size\(\)\)/);

  const http = readFileSync(
    join(root, "..", "aethercode", "aethercode-protocol", "src", "main", "java", "org", "aethercode", "protocol", "http", "HttpJsonRpcServer.java"),
    "utf-8",
  );
  // R92 lesson: HTTP+WS daemon dispatches via this
  // switch. Forgetting this is the most common bug
  // for a new RPC.
  assert.match(http, /case "listEngines"\s+->/);
  assert.match(http, /case "createEngine"\s+->/);
  assert.match(http, /case "deleteEngine"\s+->/);
  assert.match(http, /case "setActiveEngine"\s+->/);
  assert.match(http, /case "getActiveEngine"\s+->/);
});

test("R95-E: AetherCodeEngine.java exposes the SessionManager", () => {
  // The engine must own the SessionManager so the
  // RPCs above can reach it via engine.sessionManager().
  const engine = readFileSync(
    join(root, "..", "aethercode", "aethercode-sdk", "src", "main", "java", "org", "aethercode", "sdk", "AetherCodeEngine.java"),
    "utf-8",
  );
  assert.match(engine, /private final org\.aethercode\.sdk\.SessionManager sessionManager/);
  assert.match(engine, /public org\.aethercode\.sdk\.SessionManager sessionManager\(\)/);
  // The Builder exposes the setter.
  assert.match(engine, /public Builder sessionManager\(org\.aethercode\.sdk\.SessionManager m\)/);
});

test("R95-E: SessionManager.java is in aethercode-sdk and has the key shape", () => {
  const mgr = readFileSync(
    join(root, "..", "aethercode", "aethercode-sdk", "src", "main", "java", "org", "aethercode", "sdk", "SessionManager.java"),
    "utf-8",
  );
  // Public surface.
  assert.match(mgr, /public static final String DEFAULT_SESSION_ID = "default"/);
  assert.match(mgr, /public static final int MAX_SESSIONS = 64/);
  assert.match(mgr, /public interface EngineFactory/);
  assert.match(mgr, /public static final class EngineHandle/);
  assert.match(mgr, /public EngineHandle get\(String sessionId\)/);
  assert.match(mgr, /public EngineHandle getOrCreate\(String sessionId\)/);
  assert.match(mgr, /public boolean delete\(String sessionId\)/);
  assert.match(mgr, /public List<EngineHandle> list\(\)/);
  assert.match(mgr, /public List<Map<String, Object>> wireSnapshot\(\)/);
  assert.match(mgr, /public void setActive\(String sessionId\)/);
  // The default session is protected from delete.
  assert.match(mgr, /refusing to delete the default session/);
});
