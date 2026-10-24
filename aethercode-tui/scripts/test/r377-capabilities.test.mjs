// R377 — a refusal must be GROUNDED, and a naming gap must not be
// shipped as a missing feature.
//
// ## What R377 found
//
// The refusal table (`src/rpcCapabilities.ts`) had four entries. One of
// them — `effort/set` — was a NAME MISMATCH, not a missing capability:
// the daemon calls the thing a VARIANT and serves it through
// `switchVariant`, registered on both transports since R285. The TUI
// had been telling the user the feature was impossible while the
// desktop drove it the whole time.
//
// The Ink panel even carried the "verification" that produced the lie:
//
//     NOTE: the daemon exposes no effort RPC (verified against
//     `AetherCodeMethods`' method registry — there is neither a read
//     nor a write path for it)
//
// The verification was real. The registry does not contain the word
// "effort" — it contains a SYNONYM.
//
// ## What this gate makes executable
//
// 1. **A capability the daemon serves is never refused.** The effort
//    capability's method must be registered on the stdio dispatcher AND
//    routed by the HTTP switch.
// 2. **The refusal table claims no gap the daemon does not have.** A
//    key that the daemon registers is a refusal that is lying.
// 3. **Every refusal reason is GROUNDED.** It must quote at least one
//    identifier, and every identifier it quotes must exist in the
//    daemon's CODE — comments stripped, because a claim that rests on
//    a word in a Javadoc is a claim about prose, not about behaviour.
// 4. **The TUI sends the daemon's PARAM name.** `switchVariant` reads
//    `p.get("variant")`; sending `effort` would be silently ignored.
// 5. **The phantom name is gone.** No TUI source may still say
//    `effort/set`.
// 6. **The refusal guards EVERY path that would call the method** —
//    not just the slash dispatcher. Ctrl-Z used to call `rewind`
//    directly, so the same capability answered in words from the
//    command and in a raw -32601 from the key press.
//
// ## What this gate cannot do
//
// It cannot find a SYNONYM. No static check can tell that "variant" is
// "effort". What it CAN do is force the author of a refusal to go and
// quote the daemon's own identifiers — and a quote is a thing a
// reviewer can check, while "the daemon has no effort concept" is not.
//
// Lesson 1193 — a refusal is a claim about ANOTHER system's
// vocabulary, and such a claim cannot verify itself.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync, readdirSync, statSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { javaCode, javaMethod } from "./java-strip.mjs";
import { codeOf } from "./strip-comments.mjs";
import { loadTsModule } from "./load-ts-module.mjs";
import { readEntries, selfTest } from "./caps-table.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const repo = join(root, "..");
const java = join(repo, "aethercode");

// --- the daemon's RPC surface ---------------------------------------------

const methodsPath = join(
  java,
  "aethercode-protocol",
  "src",
  "main",
  "java",
  "org",
  "aethercode",
  "protocol",
  "methods",
  "AetherCodeMethods.java",
);
const httpPath = join(
  java,
  "aethercode-protocol",
  "src",
  "main",
  "java",
  "org",
  "aethercode",
  "protocol",
  "http",
  "HttpJsonRpcServer.java",
);
const hasDaemon = existsSync(methodsPath) && existsSync(httpPath);

const methodsSrc = hasDaemon ? readFileSync(methodsPath, "utf-8") : "";
const httpSrc = hasDaemon ? readFileSync(httpPath, "utf-8") : "";

/** The names the stdio dispatcher registers. */
const stdioMethods = new Set();
for (const m of methodsSrc.matchAll(/dispatcher\.register\(\s*"([^"]+)"/g)) stdioMethods.add(m[1]);

/** The names the HTTP switch routes. */
const httpMethods = new Set();
for (const m of httpSrc.matchAll(/case\s+"([^"]+)"\s*->/g)) httpMethods.add(m[1]);

// --- the daemon's CODE (comments stripped) ---------------------------------

/**
 * Every identifier in the daemon's main sources.
 *
 * <p>Comments are stripped on purpose. A refusal reason that quotes a
 * word which only appears in a Javadoc is quoting PROSE, and prose is
 * where the R369 effort reason went wrong ("no effort concept on the
 * daemon at all" — the word "effort" is all over the comments and
 * nowhere in the code).
 */
const DAEMON_MODULES = [
  join("aethercode-protocol", "src", "main", "java"),
  join("aethercode-core", "src", "main", "java"),
  join("aethercode-sdk", "src", "main", "java"),
  join("aethercode-cli", "src", "main", "java"),
];

function daemonIdentifiers() {
  const words = new Set();
  for (const mod of DAEMON_MODULES) {
    const dir = join(java, mod);
    if (!existsSync(dir)) continue;
    (function walk(d) {
      for (const e of readdirSync(d, { withFileTypes: true })) {
        const p = join(d, e.name);
        if (e.isDirectory()) walk(p);
        else if (e.name.endsWith(".java")) {
          const code = javaCode(readFileSync(p, "utf-8"));
          for (const w of code.match(/[A-Za-z_][A-Za-z0-9_]*/g) || []) words.add(w);
        }
      }
    })(dir);
  }
  return words;
}

// --- the TUI's own table ---------------------------------------------------

const capsMod = await loadTsModule(root, "src/rpcCapabilities.ts");
const BACKEND_MISSING = capsMod.BACKEND_MISSING;
const EFFORT_METHOD = capsMod.EFFORT_METHOD;

const effortMod = await loadTsModule(root, "src/effort.ts");
const EFFORT_LEVELS = effortMod.EFFORT_LEVELS;
const EFFORT_EFFECT = effortMod.EFFORT_EFFECT;

const tuiSrc = readFileSync(join(root, "src", "tui.tsx"), "utf-8");
const lineSrc = readFileSync(join(root, "src", "line.ts"), "utf-8");
const commandsSrc = readFileSync(join(root, "src", "commands.ts"), "utf-8");
const effortSrc = readFileSync(join(root, "src", "effort.ts"), "utf-8");

// --- the daemon's handler, extracted by BRACE MATCHING ---------------------

/**
 * The body of the daemon's effort handler.
 *
 * <p>Scoped to the handler, deliberately. `AetherCodeMethods` has MORE
 * THAN ONE method that reads a `variant` param — `switchProvider`
 * takes one too — so an assertion over the whole file is satisfied by
 * the wrong method. That is not a theoretical concern: the R377
 * mutation battery's "the daemon renames the param" case renamed the
 * FIRST `p.get("variant")` in the file (which belongs to
 * `switchProvider`) and the gate stayed green. The gate has to say
 * "the handler I mean", not "somewhere in the file".
 *
 * @returns {string} the handler's body, or "" when it cannot be found
 */
function effortHandler() {
  try {
    return javaMethod(javaCode(methodsSrc), `public Object ${EFFORT_METHOD}(Object params)`);
  } catch {
    return "";
  }
}

// --- the daemon's own vocabulary, DERIVED ----------------------------------

const variantJava = join(
  java,
  "aethercode-core",
  "src",
  "main",
  "java",
  "org",
  "aethercode",
  "core",
  "providers",
  "Variant.java",
);

/**
 * The canonical preset names, derived from `Variant.BUILTIN`.
 *
 * <p>Derived, not hard-coded: `Variant.java` says the renderer's
 * dropdown shows the bundled presets "in the same order", so the TUI's
 * catalog must BE that list. If the daemon adds a preset, this moves
 * and the gate says so — instead of the TUI quietly offering a subset
 * while the daemon knows more.
 *
 * @returns {string[]}
 */
function daemonLevels() {
  if (!existsSync(variantJava)) return [];
  const vSrc = javaCode(readFileSync(variantJava, "utf-8"));
  const list = /BUILTIN\s*=\s*java\.util\.List\.of\(([^)]*)\)/.exec(vSrc);
  if (!list) return [];
  const consts = [...list[1].matchAll(/([A-Z][A-Z0-9_]*)/g)].map((m) => m[1]);
  return consts.map((c) => {
    const decl = new RegExp(`Variant ${c} = new Variant\\(\\s*"([^"]+)"`).exec(vSrc);
    return decl ? decl[1] : null;
  });
}

// =========================================================================

test("the effort capability is served by a method the daemon registers", () => {
  assert.ok(hasDaemon, "the daemon's Java sources are missing — the gate cannot check anything");
  assert.equal(typeof EFFORT_METHOD, "string", "rpcCapabilities.ts must export EFFORT_METHOD");

  assert.ok(
    stdioMethods.has(EFFORT_METHOD),
    `the daemon does not register "${EFFORT_METHOD}" on the stdio dispatcher — the TUI would ` +
      `get -32601 for a capability the engine really has`,
  );
  assert.ok(
    httpMethods.has(EFFORT_METHOD),
    `HttpJsonRpcServer does not route "${EFFORT_METHOD}" — the desktop would get -32601 for a ` +
      `capability the engine really has`,
  );
});

test("the daemon's method really SWITCHES the variant, not just answers to the name", () => {
  // The second derivation of "the capability is served". The first is
  // the registration (the NAME). This one is the BEHAVIOUR: the method
  // has to hand the name to the engine. A rename that keeps the
  // registration intact would still fail here, and a stub that
  // registers the name without switching anything would fail here too.
  assert.match(
    methodsSrc,
    new RegExp(`public Object ${EFFORT_METHOD}\\(Object params\\)`),
    `the daemon has no \`${EFFORT_METHOD}\` handler — the TUI's constant points at nothing`,
  );
  // Brace matching, NOT a guessed window. The first version of this
  // test looked for the next `\n  }` after the signature — and the
  // handler's closing brace is indented by FOUR spaces, so the window
  // never opened and the assertion failed for a reason that had
  // nothing to do with the daemon.
  //
  // That failure was worse than a red test: a mutation battery that
  // runs against an ALREADY-RED gate reports every case as "caught",
  // because the gate is red whether or not the mutation landed. The
  // whole battery was measuring nothing. Scope the extraction to the
  // intended block instead (the R366 rule), and see
  // scripts/test/mutation-baseline.mjs for the guard that keeps a
  // battery from ever being that blind again.
  const body = effortHandler();
  assert.ok(body, "the daemon's handler could not be extracted — re-anchor this test");
  assert.match(
    body,
    /engine\.setVariant\(/,
    `the daemon's ${EFFORT_METHOD} does not hand the name to the engine — the TUI would ` +
      "report a change that never happened",
  );
});

test("the refusal table claims no gap the daemon does not have", () => {
  for (const name of BACKEND_MISSING.keys()) {
    assert.ok(
      !stdioMethods.has(name),
      `the refusal table claims "${name}" is missing, but the daemon REGISTERS it — the ` +
        "refusal is a lie. Wire the real method instead.",
    );
    assert.ok(
      !httpMethods.has(name),
      `the refusal table claims "${name}" is missing, but the HTTP transport ROUTES it — the ` +
        "refusal is a lie. Wire the real method instead.",
    );
  }
});

test("every refusal reason is grounded in the daemon's own code", () => {
  const words = daemonIdentifiers();
  assert.ok(words.size > 1000, `the daemon scan found only ${words.size} identifiers — re-anchor`);

  for (const [name, why] of BACKEND_MISSING) {
    // The quoted identifiers: the ones in backticks.
    const quoted = [...why.matchAll(/`([^`]+)`/g)].map((m) => m[1]);
    assert.ok(
      quoted.length > 0,
      `${name}'s reason quotes nothing from the daemon: "${why}". A reason that cannot be ` +
        "checked against the daemon is a rumour — quote the daemon's own identifiers.",
    );
    for (const q of quoted) {
      // A quote may be a plain identifier or a `Class.method` pair.
      const parts = q.split(".");
      for (const part of parts) {
        assert.ok(
          words.has(part),
          `${name}'s reason quotes "${q}", and "${part}" does not exist in the daemon's code ` +
            "(comments do not count). The reason is describing something that is not there.",
        );
      }
    }
  }
});

test("the TUI sends the daemon's PARAM name", () => {
  // `switchVariant` reads `p.get("variant")`. A `variant` key is the
  // contract; `effort` would be silently ignored and the daemon would
  // fall back to its default.
  //
  // SCOPED to the handler. `AetherCodeMethods` has more than one
  // method that reads a `variant` param — `switchProvider` takes one
  // too — so "somewhere in the file" is satisfied by the wrong
  // method. The R377 battery proved it: renaming the param in
  // `switchProvider` left the gate green.
  const handler = effortHandler();
  assert.ok(handler, "the daemon's handler could not be extracted — re-anchor this test");
  assert.match(
    handler,
    /p\.get\("variant"\)/,
    `the daemon's ${EFFORT_METHOD} must read the \`variant\` param — if this changed, re-anchor ` +
      "the TUI's payload",
  );
  assert.match(commandsSrc, /rpcParams:\s*\{\s*variant:/, "/effort must send `{ variant }`");
  assert.match(
    lineSrc,
    /probe\(ctx, EFFORT_METHOD, \{ variant: picked \}/,
    "line mode must send `{ variant }`",
  );
  assert.match(
    tuiSrc,
    /client\.request\(EFFORT_METHOD, \{ variant \}\)/,
    "the Ink panel must send `{ variant }`",
  );
});

test("the phantom name is gone", () => {
  // `effort/set` never existed. If a source still NAMES it in CODE,
  // somebody is about to wire a call to a method that answers -32601.
  //
  // Comments do not count: the history of this mistake is written
  // down in prose, and prose is allowed to mention a name it does not
  // call. Same discipline as the Java scanner below.
  for (const [label, src] of [
    ["tui.tsx", tuiSrc],
    ["line.ts", lineSrc],
    ["commands.ts", commandsSrc],
    ["effort.ts", effortSrc],
  ]) {
    const code = codeOf(src, label);
    assert.ok(
      !code.includes("effort/set"),
      `${label} still names "effort/set" IN CODE — that method does not exist. The daemon's ` +
        `method is switchVariant.`,
    );
  }
});

test("the refusal guards EVERY path that would call the method", () => {
  // R377: Ctrl-Z called `rewind` DIRECTLY, so the slash command
  // answered in plain words and the same key press answered with a
  // raw -32601. One capability, two answers.
  const ctrlZ = tuiSrc.slice(tuiSrc.indexOf('key.ctrl && (input === "z"'));
  assert.ok(ctrlZ.length > 0, "the Ctrl-Z handler moved — re-anchor this test");
  assert.match(
    ctrlZ,
    /unsupportedReason\("rewind"\)/,
    "the Ctrl-Z path must consult the refusal table before calling `rewind`",
  );
  assert.ok(
    ctrlZ.indexOf('unsupportedReason("rewind")') < ctrlZ.indexOf('client.request("rewind"'),
    "the refusal check must come BEFORE the request",
  );
});

test("both renderers report what the daemon RESOLVED, not what they asked for", () => {
  // `Variant.byName` falls back to the bundled default for a name it
  // does not know. A toast that echoed the request would be lying.
  assert.match(
    lineSrc,
    /active \? variantLine\(active\) : picked/,
    "line mode must report the daemon's resolved preset",
  );
  assert.match(
    tuiSrc,
    /r\.activeVariant \? variantLine\(r\.activeVariant\)/,
    "the Ink panel must report the daemon's resolved preset",
  );
  // And the one-line description lives in ONE place.
  assert.match(
    effortSrc,
    /export function variantLine\(/,
    "the shared description helper must exist",
  );
  assert.ok(
    !lineSrc.includes("function variantLine("),
    "line mode must not carry its own copy of the description helper",
  );
  assert.ok(
    !tuiSrc.includes("function variantLine("),
    "the Ink renderer must not carry its own copy of the description helper",
  );
});

test("the daemon's own variant vocabulary is the one the TUI offers", () => {
  // The four canonical levels are `Variant`'s BUNDLED presets, and
  // `Variant.java` says the renderer's dropdown shows them "in the
  // same order". So the TUI's catalog must BE that list — derived from
  // the daemon's own source, not a hand-written copy of it.
  //
  // The first version of this test asked "does the string `"xhigh"`
  // appear somewhere in effort.ts?". It does — in the file's own doc
  // comment — so the R377 battery could rename the catalog entry and
  // the gate stayed green. A check that a STRING appears in a FILE is
  // not a check of the value.
  const daemon = daemonLevels();
  assert.ok(
    daemon.length >= 4,
    `only ${daemon.length} presets derived from Variant.BUILTIN — re-anchor this test`,
  );
  assert.deepEqual(
    [...EFFORT_LEVELS],
    daemon,
    "the TUI's catalog must be the daemon's BUILTIN presets, in the daemon's order",
  );
  for (const level of daemon) {
    assert.ok(
      EFFORT_EFFECT[level],
      `the TUI has no one-line description for the "${level}" preset — the picker would ` +
        "render an empty line for it",
    );
  }
});

// The scan above is only meaningful when the daemon's sources are
// present. Say so out loud rather than letting a missing checkout make
// a green gate meaningless.
test("the gate is not vacuous", () => {
  assert.ok(hasDaemon, "the daemon's Java sources are missing — this gate checked nothing");
  assert.ok(
    statSync(methodsPath).size > 0 && statSync(httpPath).size > 0,
    "the daemon's RPC sources are empty — this gate checked nothing",
  );
});

// --- the shared table editor -----------------------------------------------

test("the shared table editor still edits the table", () => {
  // The editor is the NEEDLE of three mutation batteries (r366, r369,
  // r375). A needle that silently stops matching reports EXACTLY like
  // a needle that was caught — "ok" either way — so a broken editor
  // blinds three batteries at once without a single red line.
  //
  // R377 wrote all three needles against the table's TEXT, and the day
  // the reasons were rewritten they went NO-OP. The editor is the
  // structural replacement, and this is what keeps it alive.
  const st = selfTest();
  assert.deepEqual(st.failures, [], "the shared table editor broke: " + st.failures.join(", "));
});

test("the editor sees the table the product actually ships", () => {
  // The editor and the compiled table must agree about how many
  // entries there are. If the table grows a fourth entry and the
  // editor stops seeing it, the batteries are aiming at a table that
  // no longer exists.
  const seen = readEntries(readFileSync(join(root, "src", "rpcCapabilities.ts"), "utf-8"));
  assert.equal(
    seen.length,
    BACKEND_MISSING.size,
    `the editor sees ${seen.length} entries but the compiled table has ${BACKEND_MISSING.size} — ` +
      "one of them is reading a table the other does not",
  );
  for (const [name, why] of BACKEND_MISSING) {
    const hit = seen.find((e) => e.key === name);
    assert.ok(hit, `the editor cannot find the "${name}" entry`);
    assert.equal(hit.reason, why, `the editor reads a different reason for "${name}"`);
  }
});
