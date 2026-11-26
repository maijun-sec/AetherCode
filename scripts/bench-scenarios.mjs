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
 * AetherCode SCENARIO MATRIX bench (R413).
 *
 * ★ WHY THIS EXISTS ★
 * The intent classifier can only say what the agent DECIDED. It cannot say
 * whether the user GOT what they asked for. And a scenario matrix that lives
 * only in a round's scratch directory is a matrix nobody can re-run — so the
 * matrix lives here, next to the code, with its EXPECTED verdicts written
 * down.
 *
 * For every scenario this bench starts its OWN daemon (the daemon's boot cwd
 * and the session's cwd must agree, or the model notices the contradiction and
 * blind-scans the whole tree — see R408's bench v2), drives the real HTTP
 * RPC the desktop talks to, and checks THREE things:
 *
 *   1. the VERDICT on the wire — the intent kind, the posture and the reason,
 *      on the `run_start` frame AND on the polled state, and the two must
 *      agree;
 *   2. the OUTCOME — the file landed / did not land, the code changed / did
 *      not change, the local file was read / was not;
 *   3. the GUARD — a read-only posture really cannot write, and the user's
 *      AGENT REGISTRY is byte-identical before and after the whole run (the
 *      R408 incident: a read-only turn rewrote the user's `end-user` agent).
 *
 * ★ THE EXPECTED VERDICTS ARE A CONTRACT, NOT A SNAPSHOT ★
 * They are the designed ones (pinned by the unit tests), and they are written
 * out here so the bench measures the END TO END path rather than the
 * classifier against itself. A legal change to the taxonomy updates this
 * table in the same commit — that is the point.
 *
 * Usage:
 *   node scripts/bench-scenarios.mjs                    (all scenarios)
 *   SCENARIOS_ONLY=Q1,G1 node scripts/bench-scenarios.mjs
 *   SCENARIOS_JAR=/path/to/other.jar node scripts/bench-scenarios.mjs
 *   SCENARIOS_PORT=22996 node scripts/bench-scenarios.mjs
 */

import { spawn } from "node:child_process";
import { mkdirSync, rmSync, writeFileSync, readFileSync, readdirSync, existsSync } from "node:fs";
import { join, dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { createHash } from "node:crypto";
import { homedir } from "node:os";

const __dirname = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(__dirname, "..");

const JAR = process.env.SCENARIOS_JAR
  ?? join(REPO, "aethercode", "aethercode-cli", "target", "aethercode-cli-0.1.0-SNAPSHOT-shaded.jar");
const PORT = Number(process.env.SCENARIOS_PORT ?? 22996);
const ROOT = process.env.SCENARIOS_ROOT ?? join(REPO, ".aethercode", "bench-scenarios");
const ONLY = (process.env.SCENARIOS_ONLY ?? "").split(",").map((s) => s.trim()).filter(Boolean);
const JAVA = process.env.SCENARIOS_JAVA ?? "java";

// ---------------------------------------------------------------- reporting
let pass = 0;
let fail = 0;
const results = [];
function check(name, ok, detail = "") {
  if (ok) { pass++; console.log(`  PASS  ${name}`); }
  else { fail++; console.log(`  FAIL  ${name}${detail ? ' :: ' + detail : ''}`); }
  results.push({ name, ok, detail });
}
function note(s) { console.log(`  ....  ${s}`); }
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---------------------------------------------------------------- daemon
let daemon = null;
async function startDaemon(dir, sessionsDir) {
  daemon = spawn(JAVA,
    ["-Xms512m", "-Xmx2g", "-jar", JAR, "--http-port", String(PORT),
      "--sessions-dir", sessionsDir, "--cwd", dir],
    { stdio: ["ignore", "pipe", "pipe"], cwd: dir });
  daemon.stdout.on("data", () => { });
  daemon.stderr.on("data", () => { });
  const deadline = Date.now() + 90_000;
  while (Date.now() < deadline) {
    try { const r = await fetch(`http://127.0.0.1:${PORT}/healthz`); if (r.ok) return; } catch { }
    await sleep(500);
  }
  throw new Error("the daemon did not come up");
}
function stopDaemon() { if (daemon) { try { daemon.kill("SIGKILL"); } catch { } daemon = null; } }

// ---------------------------------------------------------------- rpc
let rpcId = 0;
async function rpc(method, params = {}, timeoutMs = 120_000) {
  rpcId += 1;
  const ac = new AbortController();
  const timer = setTimeout(() => ac.abort(), timeoutMs);
  try {
    const res = await fetch(`http://127.0.0.1:${PORT}/rpc`, {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ jsonrpc: "2.0", id: rpcId, method, params }),
      signal: ac.signal,
    });
    const json = await res.json();
    if (json.error) throw new Error(`${method}: ${json.error.message ?? JSON.stringify(json.error)}`);
    return json.result;
  } finally { clearTimeout(timer); }
}

// ---------------------------------------------------------------- ws
// ★ NODE'S BUILT-IN WebSocket ★ no npm dependency: the bench has to be
// runnable by anyone who has the repo and a JDK.
let raw = [];
let ws = null;
async function connectWs() {
  ws = new WebSocket(`ws://127.0.0.1:${PORT}/ws`);
  await new Promise((res, rej) => {
    ws.onopen = res;
    ws.onerror = (e) => rej(new Error(`ws connect failed: ${e?.message ?? e}`));
  });
  ws.onmessage = (ev) => {
    try {
      const msg = JSON.parse(String(ev.data));
      if (msg.method === "stream_event") raw.push(msg.params ?? {});
      if (msg.method === "permission_request") {
        const p = msg.params ?? {};
        rpc("permissionResponse", { requestId: p.requestId, decision: "allow", reason: "scenario bench" })
          .catch(() => { });
      }
    } catch { }
  };
}
const events = () => raw.map((p) => p.event ?? {}).filter((e) => e.type);
async function waitForRunEnd(timeoutMs) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeoutMs) {
    const end = events().find((e) => e.type === "run_end");
    if (end) return end;
    await sleep(200);
  }
  return null;
}
const textOf = () => events().filter((e) => e.type === "text_delta").map((e) => e.text ?? "").join("");
const toolNames = () => events().filter((e) => e.type === "tool_use_start").map((e) => e.name);

// ---------------------------------------------------------------- fixtures
const FIX = {
  order: {
    "src/OrderService.java": `package demo;\n\nimport java.net.http.HttpClient;\nimport java.time.Duration;\n\npublic class OrderService {\n    private final HttpClient client = HttpClient.newBuilder()\n            .connectTimeout(Duration.ofSeconds(2))\n            .build();\n\n    public String query(String orderId) {\n        return client.send(HttpRequest.newBuilder().uri("https://svc/orders/" + orderId).build(),\n                HttpResponse.BodyHandlers.ofString()).body();\n    }\n}\n`,
  },
  sql: {
    "src/OrderDao.java": `package demo;\n\npublic class OrderDao {\n    /** the slow query */\n    public String findOrders(String customerId) {\n        String sql = "SELECT * FROM orders WHERE customer_id = '" + customerId + "'"\n                + " AND status = 'PAID' ORDER BY created_at DESC";\n        return sql;\n    }\n}\n`,
    "schema.sql": `CREATE TABLE orders (\n  id BIGINT PRIMARY KEY,\n  customer_id VARCHAR(64),\n  status VARCHAR(16),\n  created_at TIMESTAMP\n);\n`,
  },
  lru: {
    "src/LruCache.java": `package demo;\n\nimport java.util.LinkedHashMap;\nimport java.util.Map;\n\npublic class LruCache {\n    private final Map<String, String> map = new LinkedHashMap<>();\n\n    public String get(String key) {\n        return map.get(key);\n    }\n}\n`,
  },
  repo: {
    "src/CheckpointStore.java": `package demo;\n\nimport java.nio.file.Path;\n\n/** Stores the checkpoint snapshots of a session. */\npublic class CheckpointStore {\n    private final Path root;\n\n    public CheckpointStore(Path root) {\n        this.root = root;\n    }\n\n    /** Writes a snapshot of the transcript under the session directory. */\n    public Path save(String sessionId, String transcript) {\n        return root.resolve(sessionId + ".jsonl");\n    }\n}\n`,
  },
};

// ---------------------------------------------------------------- scenarios
//
// expect: [intentToken, postureName] -- the DESIGNED verdict.
// mode:
//   "ro"  read-only posture: no file may appear, no write channel may be used
//   "wo"  write posture: the named file must land on disk, non-empty
//   "chg" write posture: the named file must CHANGE
//   "ans" read the local file, answer with file:line evidence
//   "diag" read the local file, cite file:line, and change NOTHING
//   "fix" read the local file, do NOT go to the web, and change it
//   "ops" run a real command
const SCENARIOS = [
  {
    id: "Q1", cat: "a plain question (external concept)", files: [], attempts: 1,
    prompt: "用一句话解释什么是幂等性？",
    expect: ["research", "researcher"], mode: "ro", watch: [],
  },
  {
    id: "Q2", cat: "a question about this repository", files: ["repo"], attempts: 1,
    prompt: "这个仓库的 checkpoint 机制是怎么设计的",
    expect: ["answer", "analyst"], mode: "ans", watch: ["src/CheckpointStore.java"],
  },
  {
    id: "C1", cat: "pure creation (no destination)", files: [], attempts: 1,
    prompt: "写一首关于秋天的打油诗",
    expect: ["uncertain", "navigator"], mode: "ro", watch: [],
  },
  {
    id: "C2", cat: "creation that must land on disk", files: [], attempts: 2,
    prompt: "写一首关于秋天的打油诗，保存到 poem.txt",
    expect: ["uncertain", "producer"], mode: "wo", watch: ["poem.txt"],
  },
  {
    id: "D1", cat: "write a design doc (no destination)", files: [], attempts: 1,
    prompt: "帮我写一份订单状态机的设计稿",
    expect: ["uncertain", "navigator"], mode: "ro", watch: [],
  },
  {
    id: "D2", cat: "write a design doc that must land on disk", files: [], attempts: 2,
    prompt: "帮我写一份订单状态机的设计稿，保存到 design.md",
    expect: ["uncertain", "producer"], mode: "wo", watch: ["design.md"],
  },
  {
    id: "D3", cat: "design (no destination)", files: [], attempts: 1,
    prompt: "帮我设计一个订单状态机",
    expect: ["uncertain", "navigator"], mode: "ro", watch: [],
  },
  {
    id: "D4", cat: "design that must land on disk", files: [], attempts: 2,
    prompt: "帮我设计一个订单状态机，保存到 design.md",
    expect: ["uncertain", "producer"], mode: "wo", watch: ["design.md"],
  },
  {
    id: "V1", cat: "development (change the code)", files: ["lru"], attempts: 2,
    prompt: "给 LruCache 加一个容量上限的字段",
    expect: ["code_change", "builder"], mode: "chg", watch: ["src/LruCache.java"],
  },
  {
    id: "G1", cat: "troubleshooting (locate only)", files: ["order"], attempts: 1,
    prompt: "订单接口偶发超时，帮我定位一下原因",
    // ★ R413: THE EXPECTED POSTURE CHANGED ★ the capability axis is now
    // authoritative in BOTH directions, so a turn that only asked for the
    // CAUSE gets the read-only twin of the diagnosis posture --
    // `investigator` -- instead of the write-capable `debugger`.
    // (Measured: the write-capable posture left a reproduction harness in
    // the user's tree: repro/Repro.java, out/Repro.class.)
    expect: ["diagnose", "investigator"], mode: "diag", watch: ["src/OrderService.java"],
  },
  {
    id: "G2", cat: "troubleshooting + the fix is wanted", files: ["sql"], attempts: 2,
    prompt: "这条 SQL 查询很慢，帮我看看怎么优化",
    expect: ["answer", "producer"], mode: "fix", watch: ["src/OrderDao.java"],
  },
  {
    id: "O1", cat: "build / run / verify", files: [], attempts: 1,
    prompt: "跑一下单元测试",
    expect: ["ops", "operator"], mode: "ops", watch: [],
  },
];

// ---------------------------------------------------------------- helpers
function filesIn(dir) {
  const out = [];
  const walk = (d, rel) => {
    for (const e of readdirSync(d, { withFileTypes: true })) {
      if (e.name === ".aethercode" || e.name === "sessions") continue;
      const r = rel ? rel + "/" + e.name : e.name;
      if (e.isDirectory()) walk(join(d, e.name), r);
      else out.push(r);
    }
  };
  walk(dir, "");
  return out.sort();
}
const sha = (p) => existsSync(p) ? createHash("sha256").update(readFileSync(p)).digest("hex").slice(0, 12) : "MISSING";
const citesLocal = (t) => /\b[\w./-]+\.(java|kt|ts|tsx|js|py|go|rs|sql|md|txt|json|ya?ml)\s*[:：]\s*\d+/.test(t);
const WRITE_TOOLS = ["file_write", "file_edit", "notebook_edit", "bash"];

/**
 * ★ THE AGENT REGISTRY GUARD ★ R408 measured a read-only turn rewriting
 * the user's `end-user` agent. The registry lives OUTSIDE the working tree,
 * so a bench that only watches the sandbox cannot see that damage. Snapshot
 * it before and after the whole run and compare.
 */
function snapshotAgents() {
  const dir = join(homedir(), ".aethercode", "agents");
  const out = {};
  if (!existsSync(dir)) return out;
  const walk = (d, rel) => {
    for (const e of readdirSync(d, { withFileTypes: true })) {
      const r = rel ? rel + "/" + e.name : e.name;
      if (e.isDirectory()) walk(join(d, e.name), r);
      else out[r] = sha(join(d, e.name));
    }
  };
  walk(dir, "");
  return out;
}

// ---------------------------------------------------------------- run
rmSync(ROOT, { recursive: true, force: true });
mkdirSync(ROOT, { recursive: true });
console.log(`== AetherCode scenario matrix ==\n   jar  = ${JAR}\n   port = ${PORT}\n`);

const agentsBefore = snapshotAgents();
const summary = [];
for (const sc of SCENARIOS) {
  if (ONLY.length && !ONLY.includes(sc.id)) continue;
  console.log(`\n--- ${sc.id}  ${sc.cat} ---`);
  const dir = join(ROOT, "sb-" + sc.id);
  mkdirSync(dir, { recursive: true });
  for (const k of sc.files) {
    for (const [rel, body] of Object.entries(FIX[k] ?? {})) {
      const p = join(dir, rel);
      mkdirSync(join(p, ".."), { recursive: true });
      writeFileSync(p, body, "utf8");
    }
  }
  const before = new Map(filesIn(dir).map((f) => [f, sha(join(dir, f))]));
  const row = { id: sc.id, cat: sc.cat, prompt: sc.prompt, checks: [] };

  try {
    // ★ THE SESSIONS DIR LIVES OUTSIDE THE SANDBOX ★ the daemon writes the
    // session transcript (and the daemon's own scratch files) under
    // --sessions-dir. Inside the sandbox, the model's `glob` finds a tree
    // full of transcript files and concludes the repository is a
    // "shell/skeleton repo" -- a MEASUREMENT ARTIFACT, not a product
    // behaviour. The sandbox must contain the fixture and nothing else.
    const sessionsDir = join(ROOT, "sessions-" + sc.id);
    mkdirSync(sessionsDir, { recursive: true });
    await startDaemon(dir, sessionsDir);
    await connectWs();
    await sleep(500);
    const created = await rpc("createSession", { cwd: dir });
    const sid = created?.sessionId;
    check(`${sc.id}: session created`, typeof sid === "string" && sid.length > 0, JSON.stringify(created));

    let end = null;
    let attempts = 0;
    for (let i = 1; i <= sc.attempts; i++) {
      attempts = i;
      raw = [];
      const q = await rpc("query", { sessionId: sid, prompt: sc.prompt });
      check(`${sc.id}: turn ${i} accepted`, q?.accepted === true, `runId=${q?.runId}`);
      end = await waitForRunEnd(600_000);
      check(`${sc.id}: turn ${i} finished`, !!end, end ? `stopReason=${end.stopReason}` : "NO run_end");
      if (!end) break;
      const landed = sc.watch.length > 0 && sc.watch.every((f) => {
        const p = join(dir, f);
        if (!existsSync(p)) return false;
        return sc.mode === "chg" ? sha(p) !== before.get(f) : true;
      });
      if (landed || sc.mode === "ro" || sc.mode === "ans" || sc.mode === "diag"
          || sc.mode === "ops" || i >= sc.attempts) break;
      note(`the deliverable did not land; retrying with the SAME prompt (${i})`);
    }
    row.attempts = attempts;

    const tools = toolNames();
    const text = textOf();
    const after = filesIn(dir);
    const createdFiles = after.filter((f) => !before.has(f));
    const changedFiles = after.filter((f) => before.has(f) && before.get(f) !== sha(join(dir, f)));
    const runStart = events().find((e) => e.type === "run_start");
    const state = await rpc("getState", {});

    note(`tools=[${[...new Set(tools)].join(", ")}]`);
    note(`created=[${createdFiles.join(", ")}] changed=[${changedFiles.join(", ")}]`);
    note(`text(${text.length}): ${text.slice(0, 300).replace(/\s+/g, " ")}`);
    note(`wire: intent=${state?.intent} profile=${state?.profile} reason=${JSON.stringify(state?.intentReason)}`);

    // ---- 1. the VERDICT on the wire ---------------------------------------
    check(`${sc.id}: the run_start frame carries a verdict`,
      typeof runStart?.intent === "string" && typeof runStart?.profile === "string",
      `runStart=${JSON.stringify(runStart)}`);
    check(`${sc.id}: the wire verdict is the designed one`,
      state?.intent === sc.expect[0] && state?.profile === sc.expect[1],
      `expected ${sc.expect[0]}/${sc.expect[1]} got ${state?.intent}/${state?.profile} `
      + `reason=${JSON.stringify(state?.intentReason)}`);
    check(`${sc.id}: the wire carries the reason`,
      typeof state?.intentReason === "string" && state.intentReason.trim().length > 0,
      `intentReason=${JSON.stringify(state?.intentReason)}`);
    check(`${sc.id}: the event and the state agree`,
      runStart?.intent === state?.intent && runStart?.profile === state?.profile,
      `event=${runStart?.intent}/${runStart?.profile} state=${state?.intent}/${state?.profile}`);

    // ---- 2. an answer arrived ----------------------------------------------
    check(`${sc.id}: an answer arrived`, text.trim().length > 0, "no text");

    // ---- 3. the OUTCOME, per mode -----------------------------------------
    if (sc.mode === "ro") {
      check(`${sc.id}: ★ no file was created`, createdFiles.length === 0, `new=[${createdFiles}]`);
      check(`${sc.id}: ★ no write channel was used`,
        !tools.some((t) => WRITE_TOOLS.includes(t)), `tools=[${[...new Set(tools)].join(", ")}]`);
    }
    if (sc.mode === "wo") {
      for (const f of sc.watch) {
        check(`${sc.id}: ★ ${f} EXISTS on disk`, existsSync(join(dir, f)), `dir=[${after}]`);
        check(`${sc.id}: ★ ${f} is not empty`,
          existsSync(join(dir, f)) && readFileSync(join(dir, f), "utf8").trim().length > 0);
      }
      // ★ THE POSTURE'S CONTRACT ★ the producer posture says, in its own
      // words: "Do not add files, scaffolding, samples or documentation that
      // nobody asked for." A turn that delivers the file AND litters the
      // tree with helper scripts has broken it — and the user is the one
      // who has to delete them.
      const extra = createdFiles.filter((f) => !sc.watch.includes(f));
      check(`${sc.id}: ★ no file nobody asked for`, extra.length === 0, `extra=[${extra.join(", ")}]`);
    }
    if (sc.mode === "chg") {
      for (const f of sc.watch) {
        check(`${sc.id}: ★ ${f} actually changed`, changedFiles.includes(f), `changed=[${changedFiles}]`);
      }
    }
    if (sc.mode === "ans") {
      check(`${sc.id}: ★ it read the LOCAL file`,
        tools.some((t) => ["file_read", "grep", "glob"].includes(t)), `tools=[${[...new Set(tools)].join(", ")}]`);
      check(`${sc.id}: ★ the answer cites file:line`, citesLocal(text), text.slice(0, 200));
    }
    if (sc.mode === "diag") {
      // ★ THE POSTURE'S PROMISE ★ the debugger posture KEEPS the write tools
      // (R401: "这个不生效" usually means "fix it"), so "don't change what
      // wasn't asked" is a PROMPT-level guarantee here, not a structural one.
      // The user only asked to LOCATE the cause -- the file must come back
      // untouched.
      check(`${sc.id}: ★ it read the LOCAL file`,
        tools.some((t) => ["file_read", "grep", "glob"].includes(t)), `tools=[${[...new Set(tools)].join(", ")}]`);
      check(`${sc.id}: ★ the answer cites file:line`, citesLocal(text), text.slice(0, 200));
      check(`${sc.id}: ★ ★ the file was NOT changed (the user only asked to locate)`,
        changedFiles.length === 0, `changed=[${changedFiles}] created=[${createdFiles}]`);
    }
    if (sc.mode === "fix") {
      check(`${sc.id}: ★ it read the LOCAL sql`,
        tools.some((t) => ["file_read", "grep", "glob"].includes(t)), `tools=[${[...new Set(tools)].join(", ")}]`);
      check(`${sc.id}: ★ it did NOT go to the web`,
        !tools.some((t) => t.startsWith("web")), `tools=[${[...new Set(tools)].join(", ")}]`);
      check(`${sc.id}: ★ it CHANGED the local file`, changedFiles.length > 0, `changed=[${changedFiles}]`);
    }
    if (sc.mode === "ops") {
      check(`${sc.id}: ★ it ran a REAL command`, tools.includes("bash"), `tools=[${[...new Set(tools)].join(", ")}]`);
    }

    row.verdict = { intent: state?.intent, profile: state?.profile, reason: state?.intentReason };
    row.tools = [...new Set(tools)];
    row.created = createdFiles;
    row.changed = changedFiles;
    row.textLength = text.length;
    row.textHead = text.slice(0, 600);
  } catch (e) {
    fail += 1;
    console.log(`  FAIL  ${sc.id} threw :: ${e?.message ?? e}`);
    row.threw = String(e?.message ?? e);
  } finally {
    try { if (ws) ws.close(); } catch { }
    await sleep(500);
    stopDaemon();
    await sleep(1500);
  }
  summary.push(row);
  writeFileSync(join(ROOT, "summary.json"), JSON.stringify({ pass, fail, rows: summary }, null, 2), "utf8");
}

// ---- the agent registry must be byte-identical --------------------------------
const agentsAfter = snapshotAgents();
const agentKeys = [...new Set([...Object.keys(agentsBefore), ...Object.keys(agentsAfter)])].sort();
const agentDiff = agentKeys.filter((k) => agentsBefore[k] !== agentsAfter[k]);
check("★ the agent registry is UNCHANGED after the whole run", agentDiff.length === 0,
  `changed=[${agentDiff.join(", ")}]`);

console.log(`\n== scenario matrix: ${pass} passed / ${fail} failed ==`);
writeFileSync(join(ROOT, "summary.json"),
  JSON.stringify({ pass, fail, rows: summary, agentRegistryChanged: agentDiff }, null, 2), "utf8");
process.exit(fail === 0 ? 0 : 1);
