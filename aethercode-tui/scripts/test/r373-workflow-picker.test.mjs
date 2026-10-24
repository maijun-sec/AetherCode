// R373 — the `/workflow` Ink panel.
//
// ## What was missing
//
// The registry row `workflow-picker` sat at `ink: 'unavailable'` for
// three rounds. R365 re-derived the truth and found line mode HAS a
// real frame (`line.ts` `panelWorkflow`); R366 found that frame was
// calling `workflow/list` / `workflow/run`, which are registered on
// `SupervisorRpcServer` — a separate CLI the TUI never starts — while
// the daemon the TUI spawns registers the flat `listWorkflows` /
// `runWorkflow`.
//
// So by R373 the backend contract was right and only the Ink surface
// was missing.
//
// ## The two bugs the contract work uncovered
//
// Writing the panel forced two questions the DTOs did not answer:
//
//   1. **What do you send `runWorkflow`?** The daemon resolves a FILE
//      (`WorkflowPaths.workflowFile`), but `listWorkflows` published
//      only the `name:` the YAML declares. A file called
//      `deploy.yaml` that declares `name: ship-it` was listed as
//      "ship-it" and could not be started under that name. R373 made
//      the daemon publish `id` (the file's name) alongside `name`.
//
//   2. **What does an empty list mean?** The daemon dropped workflow
//      files it could not READ, so "there is nothing here" and "you
//      cannot read what is in it" were the same answer. R373 made
//      the daemon publish `skipped`, and the panel renders the two
//      differently.
//
// Both are the "successful-but-empty" class this whole sequence has
// been about: a panel that renders confidently while asserting
// something it does not know.
//
// ## What this gate pins
//
//   * both renderers claim the sentinel;
//   * the Ink panel fetches with the daemon's REAL method name;
//   * the run sends the ADDRESSABLE id, never the display name;
//   * the run reports "accepted", not "finished";
//   * the empty state distinguishes three different facts;
//   * the daemon registers both methods on BOTH transports.
//
// The pure parts are tested BEHAVIOURALLY — `workflowRows.ts` is
// compiled and imported, then run. A source pin can only see that the
// code mentions a field; running it shows that the id survives.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdirSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { codeOf } from "./strip-comments.mjs";
import { javaCode } from "./java-strip.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const repo = join(root, "..");
const tmp = join(root, "tmp-r373-workflow-picker");

const tuiSrc = codeOf(readFileSync(join(root, "src", "tui.tsx"), "utf-8"), "tui.tsx");
const lineSrc = codeOf(readFileSync(join(root, "src", "line.ts"), "utf-8"), "line.ts");
const rowsSrc = codeOf(readFileSync(join(root, "src", "workflowRows.ts"), "utf-8"), "workflowRows.ts");
const pickerSrc = codeOf(
  readFileSync(join(root, "src", "components", "WorkflowPicker.tsx"), "utf-8"),
  "WorkflowPicker.tsx",
);

const methodsPath = join(repo, "aethercode", "aethercode-protocol", "src", "main", "java",
  "org", "aethercode", "protocol", "methods", "AetherCodeMethods.java");
const httpPath = join(repo, "aethercode", "aethercode-protocol", "src", "main", "java",
  "org", "aethercode", "protocol", "http", "HttpJsonRpcServer.java");
const methodsCode = javaCode(readFileSync(methodsPath, "utf-8"));
const httpCode = javaCode(readFileSync(httpPath, "utf-8"));

/** Bounded extraction by BRACE MATCHING — never a character window. */
function block(src, header) {
  const at = src.search(header);
  assert.notEqual(at, -1, `block(): header not found: ${header}`);
  const open = src.indexOf("{", at);
  let depth = 0;
  for (let i = open; i < src.length; i++) {
    if (src[i] === "{") depth++;
    else if (src[i] === "}" && --depth === 0) return src.slice(open, i + 1);
  }
  throw new Error(`block(): unbalanced braces after ${header}`);
}

/**
 * The object that CONTAINS the match.
 *
 * `block()` finds the first `{` AFTER its header, which is right for
 * `case "x": {` and wrong for a field inside an object literal —
 * `id: 'workflow-picker'` sits INSIDE its row, so the next `{` is the
 * NEXT row. Walking BACK to the nearest opening brace is what makes
 * the extraction read the row the header actually names.
 */
function enclosingObject(src, header) {
  const at = src.search(header);
  assert.notEqual(at, -1, `enclosingObject(): header not found: ${header}`);
  const open = src.lastIndexOf("{", at);
  assert.notEqual(open, -1, `enclosingObject(): no opening brace before ${header}`);
  let depth = 0;
  for (let i = open; i < src.length; i++) {
    if (src[i] === "{") depth++;
    else if (src[i] === "}" && --depth === 0) return src.slice(open, i + 1);
  }
  throw new Error(`enclosingObject(): unbalanced braces after ${header}`);
}

// --- compile the pure module so it can be RUN ------------------------------

if (existsSync(tmp)) {
  if (process.platform === "win32") spawnSync("cmd", ["/c", "rmdir", "/s", "/q", tmp]);
  else spawnSync("rm", ["-rf", tmp]);
}
mkdirSync(tmp, { recursive: true });

const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
const compile = spawnSync(`"${tscBin}"`, [
  "--outDir", join(tmp, "out"), "--target", "ES2022", "--module", "ES2022",
  "--moduleResolution", "bundler", "--jsx", "react",
  "--esModuleInterop", "true", "--skipLibCheck", "true",
  "--rootDir", join(root, "src"),
  `"${join(root, "src", "workflowRows.ts")}"`,
  `"${join(root, "src", "state.ts")}"`,
], { encoding: "utf-8", shell: true });
if (compile.status !== 0) {
  console.error("tsc failed:\n" + compile.stdout + "\n" + compile.stderr);
  process.exit(1);
}

const rowsMod = await import(pathToFileURL(join(tmp, "out", "workflowRows.js")).href);
const { normalizeWorkflowRow, parseWorkflowListing, workflowEmptyState } = rowsMod;

const stateMod = await import(pathToFileURL(join(tmp, "out", "state.js")).href);
const { reducer, INITIAL } = stateMod;

// --- the registry -----------------------------------------------------------

test("the workflow-picker row claims BOTH renderers", () => {
  // panelIntents.ts is TypeScript and the r365 gate compiles it for
  // the full derivation. This gate only needs the ONE row, so it
  // reads the source — the row is an object literal, and
  // `enclosingObject` scopes the read to it.
  const src = codeOf(readFileSync(join(root, "src", "panelIntents.ts"), "utf-8"), "panelIntents.ts");
  const row = enclosingObject(src, /id:\s*'workflow-picker'/);
  assert.ok(/ink:\s*'mounted'/.test(row),
    "the workflow-picker row must claim Ink is mounted:\n" + row);
  assert.ok(/line:\s*'rendered'/.test(row),
    "the workflow-picker row must claim line mode is rendered:\n" + row);
  assert.ok(!/blocker:/.test(row),
    "a fully-mounted row must not carry a blocker:\n" + row);
  assert.ok(/component:\s*'components\/WorkflowPicker\.tsx'/.test(row),
    "the row must name the component that implements it:\n" + row);
});

// --- reachability -----------------------------------------------------------

test("both renderers claim the __WORKFLOW_PICKER__ sentinel", () => {
  assert.ok(/case\s+"__WORKFLOW_PICKER__":/.test(lineSrc),
    "line mode has no arm for __WORKFLOW_PICKER__");
  assert.ok(/"workflow-picker":\s*"workflows"/.test(tuiSrc),
    "PANEL_BY_INTENT does not map workflow-picker to a panel");
  assert.ok(/case\s+"workflows":/.test(tuiSrc),
    "tui.tsx has no case for the workflows panel");
});

test("the Ink panel is reachable through the sentinel, not a private path", () => {
  // The sentinel has to travel intent -> PANEL_BY_INTENT -> openPanel.
  // A panel that is only reachable by a hand-written branch would be
  // unreachable in practice.
  //
  // R374: this used to assert the LITERAL
  // `intentForSentinel(bareSentinel(panelLocal))`, and R374's
  // refactor (hoisting `bareSentinel` into a local) broke it while
  // the property was still true. The property is what is asserted
  // now: the claim block consults the REGISTRY and the registry's
  // map, not a hand-written branch.
  const at = tuiSrc.search(/const panelLocal = slash\?\.local;/);
  assert.notEqual(at, -1, "the sentinel claim block moved — re-anchor this test");
  const claim = block(tuiSrc, /const panelLocal = slash\?\.local;/);
  assert.ok(/intentForSentinel\(/.test(claim),
    "the panel claim no longer goes through the registry:\n" + claim);
  assert.ok(/PANEL_BY_INTENT\[/.test(claim),
    "the panel claim no longer goes through PANEL_BY_INTENT:\n" + claim);
  assert.ok(/type:\s*"openPanel"/.test(claim),
    "the panel claim does not open a panel:\n" + claim);
});

// --- the daemon contract ----------------------------------------------------

test("the Ink panel fetches with the daemon's REAL method name", () => {
  const fetchBlock = block(tuiSrc, /if \(panel === "workflows"\)/);
  assert.ok(/request\("listWorkflows"/.test(fetchBlock),
    "the workflows panel does not call listWorkflows:\n" + fetchBlock);
  assert.ok(!/request\("workflow\/list"/.test(fetchBlock),
    "the panel calls `workflow/list`, which lives on SupervisorRpcServer — "
    + "a separate CLI the TUI never starts");
});

test("the run uses the daemon's REAL method name and sends the id", () => {
  const runBlock = block(tuiSrc, /case "workflows":/);
  assert.ok(/request\("runWorkflow"/.test(runBlock),
    "the workflows panel does not call runWorkflow:\n" + runBlock);
  assert.ok(!/request\("workflow\/run"/.test(runBlock),
    "the panel calls `workflow/run`, which lives on SupervisorRpcServer");
  // The DECISIVE part: the `name` param must be the id, not the
  // display name. `runWorkflow` resolves a FILE.
  assert.ok(/request\("runWorkflow",\s*\{\s*name:\s*id\s*\}/.test(runBlock),
    "runWorkflow is not sent the addressable id:\n" + runBlock);
  assert.ok(!/request\("runWorkflow",\s*\{\s*name:\s*name\s*\}/.test(runBlock),
    "runWorkflow is sent the DISPLAY name, which does not resolve a file");
});

test("the picker hands the id to onRun, never the display name", () => {
  assert.ok(/onRun\(w\.id\)/.test(pickerSrc),
    "the picker calls onRun with something other than the id:\n" + pickerSrc);
});

test("the run is reported as ACCEPTED, not finished", () => {
  const runBlock = block(tuiSrc, /case "workflows":/);
  assert.ok(/accepted/.test(runBlock),
    "the toast does not use the daemon's own word for this state");
  assert.ok(!/\bstarted\b|\bfinished\b|\bcompleted\b/.test(runBlock),
    "the toast claims an outcome the RPC did not report:\n" + runBlock);
});

test("both renderers call the daemon's real method names", () => {
  const lineBlock = block(lineSrc, /async function panelWorkflow\(/);
  assert.ok(/probe\(ctx, "listWorkflows"\)/.test(lineBlock),
    "line mode does not probe listWorkflows:\n" + lineBlock);
  assert.ok(/probe\(ctx, "runWorkflow"/.test(lineBlock),
    "line mode does not probe runWorkflow:\n" + lineBlock);
});

test("line mode sends the ADDRESSABLE id, not the display name", () => {
  // R373. Line mode had the same bug the Ink panel would have had:
  // it picked `nameOf(w)` and sent that to `runWorkflow`.
  const lineBlock = block(lineSrc, /async function panelWorkflow\(/);
  assert.ok(/value:\s*idOf\(w\)/.test(lineBlock),
    "line mode sends the display name to runWorkflow:\n" + lineBlock);
  assert.ok(/const idOf = \(w: unknown\): string => fieldStr\(w, \["id", "name"\], ""\)/.test(lineBlock),
    "line mode has no id reader:\n" + lineBlock);
});

// --- the daemon registers both methods on BOTH transports -------------------

test("the daemon serves listWorkflows and runWorkflow on BOTH transports", () => {
  // The TUI speaks stdio (it spawns the jar itself); the desktop
  // speaks HTTP+WS. A stdio-only registration is the R720/R367/R371
  // shape all over again.
  assert.ok(/dispatcher\.register\("listWorkflows"/.test(methodsCode),
    "listWorkflows is not on the stdio dispatcher");
  assert.ok(/dispatcher\.register\("runWorkflow"/.test(methodsCode),
    "runWorkflow is not on the stdio dispatcher");
  assert.ok(/case "listWorkflows"/.test(httpCode),
    "HttpJsonRpcServer does not route listWorkflows");
  assert.ok(/case "runWorkflow"/.test(httpCode),
    "HttpJsonRpcServer does not route runWorkflow");
});

test("every RPC the workflow surfaces call is registered on the daemon", () => {
  // A CROSS-LAYER derivation of "the right method name".
  //
  // The source pins above ask "does the TUI say `listWorkflows`?".
  // This asks "does the daemon HAVE a method by that name?", and it
  // asks about the names the code ACTUALLY calls rather than the
  // names a human wrote down. A wrong name — `workflow/list`,
  // `workflow/run` — is registered on SupervisorRpcServer, a separate
  // CLI the TUI never starts, so it is not on the daemon and this
  // test goes red.
  //
  // Two independent derivations of the same property means a
  // neutered source pin does not make the gate blind.
  const called = new Set();
  const fetchBlock = block(tuiSrc, /if \(panel === "workflows"\)/);
  for (const m of fetchBlock.matchAll(/request\("([^"]+)"/g)) called.add(m[1]);
  const runBlock = block(tuiSrc, /case "workflows":/);
  for (const m of runBlock.matchAll(/request\("([^"]+)"/g)) called.add(m[1]);
  const lineBlock = block(lineSrc, /async function panelWorkflow\(/);
  for (const m of lineBlock.matchAll(/probe\(ctx, "([^"]+)"/g)) called.add(m[1]);

  assert.ok(called.size >= 2,
    `only ${called.size} workflow RPC(s) found — the anchors probably moved`);
  for (const m of called) {
    assert.ok(new RegExp(`dispatcher\\.register\\("${m}"`).test(methodsCode),
      `the TUI calls "${m}" but the daemon does not register it — every ` +
      `call would be -32601 method not found`);
  }
});

test("the daemon publishes the addressable id and the unreadable files", () => {
  // Without `id` the client cannot address what it was shown; without
  // `skipped` an empty list cannot say WHY it is empty.
  const listBlock = block(methodsCode, /public Object listWorkflows\(/);
  assert.ok(/m\.put\("id"/.test(listBlock),
    "listWorkflows does not publish an addressable id:\n" + listBlock);
  assert.ok(/r\.put\("skipped"/.test(listBlock),
    "listWorkflows does not publish the files it could not read:\n" + listBlock);
});

// --- behaviour: the normaliser ---------------------------------------------

test("the normaliser keeps the display name and the addressable id apart", () => {
  const row = normalizeWorkflowRow(
    { name: "ship-it", id: "deploy.yaml", description: "ship it", stepCount: 2, inputs: ["target"] },
    0,
  );
  assert.equal(row.name, "ship-it", "the display name is the declared name");
  assert.equal(row.id, "deploy.yaml", "the id is the file the run addresses");
  assert.equal(row.description, "ship it");
  assert.equal(row.stepCount, 2);
  assert.deepEqual(row.inputs, ["target"]);
});

test("a workflow with no id falls back to its name", () => {
  // An older daemon publishes no `id`. The row must still be
  // addressable rather than becoming `undefined`.
  const row = normalizeWorkflowRow({ name: "solo" }, 0);
  assert.equal(row.id, "solo");
  assert.equal(row.name, "solo");
});

test("the step count comes from the step array when there is one", () => {
  const row = normalizeWorkflowRow(
    { name: "x", id: "x.yaml", stepCount: 99, steps: [{ id: "a" }, { id: "b" }] },
    0,
  );
  assert.equal(row.stepCount, 2, "the array is the thing being counted");
});

test("an unknown step count is 0, never an invented number", () => {
  // R371's lesson: a `0` that means "unknown" must not be dressed up
  // as a measurement, and it must not be a random number either.
  const row = normalizeWorkflowRow({ name: "x", id: "x.yaml" }, 0);
  assert.equal(row.stepCount, 0);
  const bad = normalizeWorkflowRow({ name: "x", id: "x.yaml", stepCount: "many" }, 0);
  assert.equal(bad.stepCount, 0, "a non-numeric count is unknown, not a number");
});

// --- behaviour: the envelope ------------------------------------------------

test("the listing carries the dir and the skipped files", () => {
  const listing = parseWorkflowListing({
    workflows: [{ name: "a", id: "a.yaml" }],
    count: 1,
    dir: "D:/proj/.aethercode/workflows",
    skipped: ["broken.yaml"],
  });
  assert.equal(listing.workflows.length, 1);
  assert.equal(listing.dir, "D:/proj/.aethercode/workflows");
  assert.deepEqual(listing.skipped, ["broken.yaml"]);
});

test("a missing dir is null, not an empty string", () => {
  // `null` is "the daemon has not said"; `""` would read as "the
  // daemon said the directory is the empty string".
  const listing = parseWorkflowListing({ workflows: [] });
  assert.equal(listing.dir, null);
  assert.deepEqual(listing.skipped, []);
});

// --- behaviour: the empty state --------------------------------------------

test("an empty directory says so, and says WHERE", () => {
  const st = workflowEmptyState([], [], "D:/proj/.aethercode/workflows");
  assert.ok(st, "an empty list must have an empty state");
  assert.equal(st.tone, "info", "an empty directory is not a problem");
  assert.ok(st.lines.some((l) => l.includes("no workflows defined")),
    "the empty state does not say the directory is empty:\n" + st.lines.join(" | "));
  assert.ok(st.lines.some((l) => l.includes("D:/proj/.aethercode/workflows")),
    "the empty state does not say where to put one:\n" + st.lines.join(" | "));
});

test("unreadable files are NOT reported as an empty directory", () => {
  // The whole point of `skipped`. "no workflows defined" would blame
  // the user for a permission problem.
  const st = workflowEmptyState([], ["broken.yaml", "locked.yml"], "D:/p/wf");
  assert.ok(st, "an empty list must have an empty state");
  assert.equal(st.tone, "warn", "unreadable files are a problem the user can fix");
  assert.ok(st.lines.some((l) => l.includes("2 workflow files could not be read")),
    "the empty state does not report the unreadable files:\n" + st.lines.join(" | "));
  assert.ok(st.lines.some((l) => l.includes("broken.yaml")),
    "the unreadable file is not named:\n" + st.lines.join(" | "));
  assert.ok(!st.lines.some((l) => l.includes("no workflows defined")),
    "the empty state claims the directory is empty when it is not:\n" + st.lines.join(" | "));
});

test("one unreadable file reads as one file", () => {
  const st = workflowEmptyState([], ["broken.yaml"], null);
  assert.ok(st.lines.some((l) => l.includes("1 workflow file could not be read")),
    "the count is not singularised:\n" + st.lines.join(" | "));
});

test("a daemon that has not said where it scans does not invent a path", () => {
  const st = workflowEmptyState([], [], null);
  assert.ok(st, "an empty list must have an empty state");
  assert.equal(st.tone, "info");
  assert.ok(st.lines.some((l) => l.includes("has not said which directory")),
    "the empty state invents a directory:\n" + st.lines.join(" | "));
});

test("a non-empty list has no empty state", () => {
  const st = workflowEmptyState([{ name: "a", id: "a.yaml" }], [], "D:/p/wf");
  assert.equal(st, null, "a list with rows must not render an empty state");
});

// --- behaviour: the reducer -------------------------------------------------

test("the reducer stores the listing the fetch produced", () => {
  const rows = [{ name: "a", id: "a.yaml", description: "", stepCount: 1, inputs: [] }];
  const after = reducer(INITIAL, {
    type: "setWorkflows",
    workflows: rows,
    dir: "D:/p/wf",
    skipped: ["broken.yaml"],
  });
  assert.equal(after.workflows.length, 1);
  assert.equal(after.workflows[0].id, "a.yaml");
  assert.equal(after.workflowDir, "D:/p/wf");
  assert.deepEqual(after.workflowSkipped, ["broken.yaml"]);
});

test("the reducer clears the skipped list when the fetch says there is none", () => {
  const before = { ...INITIAL, workflowSkipped: ["stale.yaml"], workflowDir: "D:/old" };
  const after = reducer(before, {
    type: "setWorkflows",
    workflows: [],
    dir: "D:/new",
    skipped: [],
  });
  assert.deepEqual(after.workflowSkipped, [], "a stale skipped list survived a re-fetch");
  assert.equal(after.workflowDir, "D:/new");
});

// --- one definition ---------------------------------------------------------

test("the workflow row type is declared ONCE", () => {
  // Two declarations of "a workflow" drift, and the drift shows up as
  // a panel that renders a field the reducer dropped.
  const stateSrc = codeOf(readFileSync(join(root, "src", "state.ts"), "utf-8"), "state.ts");
  assert.ok(!/export interface WorkflowRow\s*\{/.test(stateSrc),
    "state.ts declares its own WorkflowRow — the type must live in workflowRows.ts");
  assert.ok(/export type \{ WorkflowRow \} from "\.\/workflowRows\.js"/.test(stateSrc),
    "state.ts does not re-export the shared WorkflowRow");
  assert.ok(/export interface WorkflowRow\s*\{/.test(rowsSrc),
    "workflowRows.ts does not declare WorkflowRow");
});

test("both renderers share the empty-state copy", () => {
  assert.ok(/export function workflowEmptyCopy\(/.test(rowsSrc),
    "the shared empty-state copy is missing");
  assert.ok(/export function workflowEmptyState\(/.test(rowsSrc),
    "the shared empty-state selector is missing");
  // The CALL, not the import. `import { workflowEmptyState } from …`
  // contains the name, so a plain `/workflowEmptyState/` assertion
  // stays green when a renderer stops calling it and keeps only the
  // unused import — which is the "a source pin cannot see a dead
  // branch" trap. The open paren is what distinguishes a call from an
  // import.
  assert.ok(/workflowEmptyState\(/.test(pickerSrc),
    "the Ink panel does not CALL the shared empty state:\n" + pickerSrc);
  assert.ok(/workflowEmptyCopy\(/.test(lineSrc),
    "line mode does not CALL the shared empty-state copy:\n" + lineSrc);
  // ...and both come from the SAME module, so the two renderers
  // cannot drift apart.
  assert.ok(/from "\.\.\/workflowRows\.js"/.test(pickerSrc),
    "the Ink panel does not take its empty state from workflowRows.ts");
  assert.ok(/from "\.\/workflowRows\.js"/.test(lineSrc),
    "line mode does not take its empty state from workflowRows.ts");
});
