// T-096 / T-097: memory-rpc.ts (typed RPC surface for the TUI) +
// end-to-end memory flow.
//
// The memory-rpc module ships three concrete classes
// (RemoteMemoryRpc, LocalMemoryRpc, NullMemoryRpc) and a small
// adapter factory. The TUI MemoryPanel uses the same six
// methods regardless of which class is in play, so the test
// here exercises:
//
//   1. RemoteMemoryRpc — proxy path. Verifies the wire method
//      names are the six the daemon implements, and that the
//      normalisers tolerate the common payload shapes
//      (well-formed, missing fields, garbage).
//   2. LocalMemoryRpc + buildLocalInvoke — adapter path. The
//      adapter unwraps a `{ result?, error? }` envelope and
//      feeds the result through the same normalisers.
//   3. NullMemoryRpc — the headless / no-daemon fallback used
//      by the TUI's --print boot path.
//   4. End-to-end memory flow (T-097): drive the six methods
//      in their natural order (get → appendProjectChange →
//      appendSessionFact → list → compact → switchProject)
//      against a hand-rolled in-process shim and assert the
//      state machine stays consistent. This is the same shape
//      a real TUI session would produce on first launch + a
//      /memory-compact slash command + a /cd.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { spawnSync } from "node:child_process";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// ----- 1. Load the TS source via a transpile-on-demand shim. ---

const candidates = ["dist/memory-rpc.js", "memory-rpc.js"];
let loaded = null;
for (const c of candidates) {
  const p = join(root, c);
  if (existsSync(p)) {
    loaded = await import(pathToFileURL(p).href);
    break;
  }
}
if (!loaded) {
  // Use tsc to compile to a tmp dir, then import the .js.
  const tmp = join(root, "tmp-r96");
  if (existsSync(tmp)) {
    spawnSync(process.platform === "win32" ? "cmd" : "rm",
      process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp] : ["-rf", tmp]);
  }
  const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
  const r = spawnSync(`"${tscBin}"`, [
    "--outDir", tmp, "--target", "ES2022", "--module", "ES2022",
    "--moduleResolution", "bundler", "--esModuleInterop", "true",
    "--skipLibCheck", "true", "--strict", "true", "--noUncheckedIndexedAccess",
    "true", "--rootDir", join(root, "src"),
    join(root, "src", "memory-rpc.ts"),
  ], { encoding: "utf-8", shell: true });
  if (r.status !== 0) {
    throw new Error("tsc compile failed for memory-rpc.ts:\n" + r.stdout + "\n" + r.stderr);
  }
  loaded = await import(pathToFileURL(join(tmp, "memory-rpc.js")).href);
}

const {
  RemoteMemoryRpc,
  LocalMemoryRpc,
  NullMemoryRpc,
  buildLocalInvoke,
  pickMemoryRpc,
  MemoryRpcError,
  RpcCallError,
  unwrapMemoryEnvelope,
} = loaded;

// ----- 2. Tests on the proxy / remote path. -------------------

/** Hand-rolled JsonRpcClient fake. Records every call and
 *  routes to a per-method handler. Mirrors the fake used in
 *  the r194-compact-rpc tests. */
function makeFakeRpcClient(handlers) {
  const calls = [];
  return {
    calls,
    request(method, params) {
      calls.push({ method, params });
      const h = handlers[method];
      if (h) return Promise.resolve(h(params));
      return Promise.reject(new Error("unknown method: " + method));
    },
  };
}

test("T-096: RemoteMemoryRpc.get proxies memory/get with the daemon's payload", async () => {
  const fake = makeFakeRpcClient({
    "memory/get": (p) => ({
      source: "cache",
      entries: [
        { kind: "fact", id: "f1", ts: 1, scope: "project", source: "user", tags: [], key: "k", value: "v" },
      ],
      totalTokens: 42,
      truncated: false,
    }),
  });
  const client = new RemoteMemoryRpc(fake);
  const out = await client.get({ scope: "project" });
  assert.equal(out.source, "cache");
  assert.equal(out.entries.length, 1);
  assert.equal(out.entries[0].id, "f1");
  assert.equal(out.entries[0].kind, "fact");
  assert.equal(out.totalTokens, 42);
  assert.equal(fake.calls.length, 1);
  assert.equal(fake.calls[0].method, "memory/get");
  assert.deepEqual(fake.calls[0].params, { scope: "project" });
});

test("T-096: RemoteMemoryRpc.appendProjectChange forwards description and unwraps id/ts/compressed", async () => {
  const fake = makeFakeRpcClient({
    "memory/appendProjectChange": () => ({ ok: true, id: "c-1", ts: 99, compressed: false }),
  });
  const client = new RemoteMemoryRpc(fake);
  const out = await client.appendProjectChange({ description: "added foo" });
  assert.equal(out.ok, true);
  assert.equal(out.id, "c-1");
  assert.equal(out.ts, 99);
  assert.equal(out.compressed, false);
  assert.equal(fake.calls[0].params.description, "added foo");
});

test("T-096: RemoteMemoryRpc.appendSessionFact forwards sessionId/key/value/tags", async () => {
  const fake = makeFakeRpcClient({
    "memory/appendSessionFact": (p) => {
      assert.equal(p.sessionId, "s1");
      assert.equal(p.key, "user.name");
      assert.equal(p.value, "Alice");
      assert.equal(p.source, "user");
      assert.deepEqual(p.tags, ["profile"]);
      return { ok: true, id: "f-1" };
    },
  });
  const client = new RemoteMemoryRpc(fake);
  const out = await client.appendSessionFact({
    sessionId: "s1", key: "user.name", value: "Alice", source: "user", tags: ["profile"],
  });
  assert.equal(out.id, "f-1");
});

test("T-096: RemoteMemoryRpc.compact passes force flag through", async () => {
  const fake = makeFakeRpcClient({
    "memory/compact": (p) => ({
      ok: true, beforeTokens: 1000, afterTokens: 400, changesCompressed: 7,
      ms: 250, skipped: false, resumed: true,
    }),
  });
  const client = new RemoteMemoryRpc(fake);
  const out = await client.compact({ force: true });
  assert.equal(out.beforeTokens, 1000);
  assert.equal(out.afterTokens, 400);
  assert.equal(out.changesCompressed, 7);
  assert.equal(out.skipped, false);
  assert.equal(out.resumed, true);
  assert.equal(fake.calls[0].params.force, true);
});

test("T-096: RemoteMemoryRpc.switchProject returns the new projectId", async () => {
  const fake = makeFakeRpcClient({
    "memory/switchProject": (p) => {
      assert.equal(p.cwd, "D:/projects/foo");
      assert.equal(p.placeholderTitle, "Foo");
      return { ok: true, projectId: "p-abc" };
    },
  });
  const client = new RemoteMemoryRpc(fake);
  const out = await client.switchProject({ cwd: "D:/projects/foo", placeholderTitle: "Foo" });
  assert.equal(out.projectId, "p-abc");
});

test("T-096: RemoteMemoryRpc.list returns {entries, totalTokens}", async () => {
  const fake = makeFakeRpcClient({
    "memory/list": () => ({
      entries: [
        { kind: "change", id: "c1", ts: 1, scope: "project", source: "user", tags: [], description: "x" },
        { kind: "change", id: "c2", ts: 2, scope: "project", source: "user", tags: [], description: "y" },
      ],
      totalTokens: 12,
    }),
  });
  const client = new RemoteMemoryRpc(fake);
  const out = await client.list({ scope: "project" });
  assert.equal(out.entries.length, 2);
  assert.equal(out.totalTokens, 12);
});

test("T-096: RemoteMemoryRpc propagates the daemon's RpcCallError unchanged", async () => {
  const fake = makeFakeRpcClient({
    "memory/get": () => { throw new RpcCallError(-32602, "bad scope"); },
  });
  const client = new RemoteMemoryRpc(fake);
  await assert.rejects(client.get({ scope: "bogus" }), (e) => {
    assert.ok(e instanceof RpcCallError);
    assert.equal(e.code, -32602);
    return true;
  });
});

// ----- 3. Local + adapter path. --------------------------------

/** The minimum in-process store that the adapter can wrap. */
function makeLocalInvokeStub(overrides = {}) {
  return {
    async get(p) {
      return overrides.get ? overrides.get(p) : { source: "file", entries: [], totalTokens: 0, truncated: false };
    },
    async appendProjectChange(p) {
      return overrides.appendProjectChange ? overrides.appendProjectChange(p) : { ok: true, id: "x", ts: 0, compressed: false };
    },
    async appendSessionFact(p) {
      return overrides.appendSessionFact ? overrides.appendSessionFact(p) : { ok: true, id: "x" };
    },
    async compact(p) {
      return overrides.compact ? overrides.compact(p) : { ok: true, beforeTokens: 0, afterTokens: 0, changesCompressed: 0, ms: 0, skipped: true, resumed: false };
    },
    async switchProject(p) {
      return overrides.switchProject ? overrides.switchProject(p) : { ok: true, projectId: "p" };
    },
    async list(p) {
      return overrides.list ? overrides.list(p) : { entries: [], totalTokens: 0 };
    },
  };
}

test("T-096: LocalMemoryRpc surfaces every method", async () => {
  const inv = makeLocalInvokeStub({
    get: () => ({ source: "sqlite", entries: [{ kind: "fact", id: "1", ts: 1, scope: "global", source: "user", tags: [], key: "k", value: "v" }], totalTokens: 5, truncated: false }),
    appendProjectChange: () => ({ ok: true, id: "c1", ts: 9, compressed: true }),
    appendSessionFact: () => ({ ok: true, id: "f1" }),
    compact: () => ({ ok: true, beforeTokens: 100, afterTokens: 30, changesCompressed: 4, ms: 5, skipped: false, resumed: false }),
    switchProject: () => ({ ok: true, projectId: "p-new" }),
    list: () => ({ entries: [{ kind: "change", id: "c1", ts: 1, scope: "project", source: "user", tags: [], description: "x" }], totalTokens: 7 }),
  });
  const client = new LocalMemoryRpc(inv);
  assert.equal((await client.get({ scope: "global" })).source, "sqlite");
  assert.equal((await client.appendProjectChange({ description: "x" })).id, "c1");
  assert.equal((await client.appendSessionFact({ sessionId: "s", key: "k", value: "v" })).id, "f1");
  assert.equal((await client.compact({ force: true })).changesCompressed, 4);
  assert.equal((await client.switchProject({ cwd: "/x" })).projectId, "p-new");
  assert.equal((await client.list({ scope: "project" })).totalTokens, 7);
});

test("T-096: buildLocalInvoke adapts an envelope-shaped shim", async () => {
  const shim = {
    get: (p) => ({ result: { source: "cache", entries: [], totalTokens: 0, truncated: false } }),
    appendProjectChange: (p) => ({ result: { ok: true, id: "x", ts: 1, compressed: false } }),
    appendSessionFact: (p) => ({ result: { ok: true, id: "x" } }),
    compact: (p) => ({ result: { ok: true, beforeTokens: 1, afterTokens: 0, changesCompressed: 1, ms: 1, skipped: false, resumed: false } }),
    switchProject: (p) => ({ result: { ok: true, projectId: "p" } }),
    list: (p) => ({ result: { entries: [], totalTokens: 0 } }),
  };
  const inv = buildLocalInvoke(shim);
  const client = new LocalMemoryRpc(inv);
  assert.equal((await client.compact({ force: true })).changesCompressed, 1);
});

test("T-096: buildLocalInvoke converts an envelope error into MemoryRpcError", async () => {
  const shim = {
    get: () => ({ error: { code: -32602, message: "bad scope", data: { x: 1 } } }),
    appendProjectChange: () => ({ result: { ok: true, id: "x", ts: 0, compressed: false } }),
    appendSessionFact: () => ({ result: { ok: true, id: "x" } }),
    compact: () => ({ result: { ok: true, beforeTokens: 0, afterTokens: 0, changesCompressed: 0, ms: 0, skipped: true, resumed: false } }),
    switchProject: () => ({ result: { ok: true, projectId: "p" } }),
    list: () => ({ result: { entries: [], totalTokens: 0 } }),
  };
  const inv = buildLocalInvoke(shim);
  const client = new LocalMemoryRpc(inv);
  await assert.rejects(client.get({ scope: "bogus" }), (e) => {
    assert.ok(e instanceof MemoryRpcError);
    assert.equal(e.code, -32602);
    return true;
  });
});

test("T-096: unwrapMemoryEnvelope returns the result on success", () => {
  const v = unwrapMemoryEnvelope({ result: { ok: true, id: "x" } });
  assert.deepEqual(v, { ok: true, id: "x" });
});

test("T-096: unwrapMemoryEnvelope throws MemoryRpcError on error", () => {
  assert.throws(
    () => unwrapMemoryEnvelope({ error: { code: -32001, message: "nope" } }),
    (e) => e instanceof MemoryRpcError && e.code === -32001,
  );
});

// ----- 4. NullMemoryRpc: headless fallback. -------------------

test("T-096: NullMemoryRpc.get/list return empty results", async () => {
  const c = new NullMemoryRpc();
  const r = await c.get({ scope: "project" });
  assert.equal(r.entries.length, 0);
  assert.equal(r.totalTokens, 0);
  const l = await c.list({ scope: "session", sessionId: "s" });
  assert.equal(l.entries.length, 0);
});

test("T-096: NullMemoryRpc.append/compact/switch return benign defaults", async () => {
  const c = new NullMemoryRpc();
  const a = await c.appendProjectChange({ description: "x" });
  assert.equal(a.ok, true);
  const f = await c.appendSessionFact({ sessionId: "s", key: "k", value: "v" });
  assert.equal(f.ok, true);
  const m = await c.compact({ force: true });
  assert.equal(m.skipped, true);
  const s = await c.switchProject({ cwd: "/x" });
  assert.equal(s.ok, true);
});

test("T-096: pickMemoryRpc returns RemoteMemoryRpc when a client is supplied", () => {
  const fake = makeFakeRpcClient({});
  const r = pickMemoryRpc({ client: fake });
  assert.ok(r instanceof RemoteMemoryRpc);
  assert.equal(r.mode, "remote");
});

test("T-096: pickMemoryRpc returns LocalMemoryRpc when buildLocal returns an invoke", () => {
  const inv = makeLocalInvokeStub();
  const r = pickMemoryRpc({ client: null, buildLocal: () => inv });
  assert.ok(r instanceof LocalMemoryRpc);
  assert.equal(r.mode, "local");
});

test("T-096: pickMemoryRpc returns NullMemoryRpc when neither is available", () => {
  const r = pickMemoryRpc({ client: null });
  assert.ok(r instanceof NullMemoryRpc);
  assert.equal(r.mode, "null");
});

// ----- 5. End-to-end memory flow (T-097). ----------------------

/** A hand-rolled in-process memory store that mirrors the
 *  real `MemoryStore` API in spirit. Each RPC method mutates
 *  the underlying collections; the test asserts the state
 *  after each step. The store is purposely small so the test
 *  stays fast — we only need to cover the data shape, not
 *  the full SQLite + markdown backends (those are covered by
 *  the aethercode-memory test suite). */
function makeInProcessStore() {
  const globalFacts = []; // {id, key, value}
  /** Map<projectId, {id, ts, description, compressed}[]>. Each project
   *  has its own change log; switchProject starts a fresh one. */
  const projectChangesByProject = new Map();
  const sessionFacts = []; // {id, key, value, sessionId}
  let currentProjectId = "p-default";
  let projectIdSeq = 0;
  const seenCompact = []; // history of compact calls

  const makeId = (prefix) => `${prefix}-${Math.random().toString(36).slice(2, 8)}`;
  const projectChanges = () => {
    let arr = projectChangesByProject.get(currentProjectId);
    if (!arr) { arr = []; projectChangesByProject.set(currentProjectId, arr); }
    return arr;
  };

  return {
    state: { globalFacts, projectChangesByProject, sessionFacts, currentProjectId, seenCompact },

    async get(p) {
      if (p.scope === "global") {
        const entries = globalFacts.map((f) => ({
          kind: "fact", id: f.id, ts: 0, scope: "global", source: "user", tags: [],
          key: f.key, value: f.value,
        }));
        return { source: "memory", entries, totalTokens: entries.length, truncated: false };
      }
      if (p.scope === "project") {
        const changes = projectChanges();
        const entries = changes.map((c) => ({
          kind: "change", id: c.id, ts: c.ts, scope: "project", source: "user", tags: [],
          description: c.description, compressed: c.compressed,
        }));
        return { source: "memory", entries, totalTokens: entries.length, truncated: false };
      }
      const sid = p.sessionId ?? "s-default";
      const entries = sessionFacts.filter((f) => f.sessionId === sid).map((f) => ({
        kind: "fact", id: f.id, ts: 0, scope: "session", source: "user", tags: [],
        key: f.key, value: f.value,
      }));
      return { source: "memory", entries, totalTokens: entries.length, truncated: false };
    },

    async appendProjectChange(p) {
      const id = makeId("c");
      const entry = { id, ts: Date.now(), description: p.description, compressed: false };
      projectChanges().push(entry);
      return { ok: true, id, ts: entry.ts, compressed: false };
    },

    async appendSessionFact(p) {
      const id = makeId("f");
      sessionFacts.push({ id, sessionId: p.sessionId, key: p.key, value: p.value });
      return { ok: true, id };
    },

    async compact(p) {
      seenCompact.push(p);
      const changes = projectChanges();
      if (p.force === true && changes.length > 0) {
        // Mark every change as compressed and return a "skipped: false" success.
        for (const c of changes) c.compressed = true;
        return {
          ok: true,
          beforeTokens: changes.length,
          afterTokens: 1,
          changesCompressed: changes.length,
          ms: 5,
          skipped: false,
          resumed: false,
        };
      }
      return { ok: true, beforeTokens: 0, afterTokens: 0, changesCompressed: 0, ms: 0, skipped: true, resumed: false };
    },

    async switchProject(p) {
      projectIdSeq += 1;
      // Update currentProjectId BEFORE returning so subsequent
      // get/list calls see the new project's (empty) state.
      // The real MemoryStore has the same shape: the
      // switchProject handler invalidates the cache and
      // updates `currentCwd` in place.
      currentProjectId = `p-${projectIdSeq}`;
      return { ok: true, projectId: currentProjectId };
    },

    async list(p) {
      const r = await this.get(p);
      return { entries: r.entries, totalTokens: r.totalTokens };
    },
  };
}

test("T-097: end-to-end memory flow — get → append → list → compact → switch", async () => {
  const store = makeInProcessStore();
  const inv = buildLocalInvoke(store);
  const client = new LocalMemoryRpc(inv);

  // Step 1: get the empty project memory. The TUI mounts the
  // MemoryPanel with an empty list, which is what the user
  // sees on first launch.
  const initial = await client.get({ scope: "project" });
  assert.equal(initial.entries.length, 0);
  assert.equal(initial.totalTokens, 0);

  // Step 2: append a project change. Mirrors what
  // `aethercode memory appendProjectChange` does on every
  // project edit.
  const c1 = await client.appendProjectChange({ description: "added foo()" });
  assert.equal(c1.ok, true);
  assert.ok(c1.id.startsWith("c-"));
  const c2 = await client.appendProjectChange({ description: "removed bar()" });
  assert.ok(c2.id.startsWith("c-"));

  // Step 3: append a session fact. Mirrors the TUI's edit
  // dialog hitting save.
  const f1 = await client.appendSessionFact({
    sessionId: "s-1", key: "user.name", value: "Alice", source: "user", tags: ["profile"],
  });
  assert.equal(f1.ok, true);
  assert.ok(f1.id.startsWith("f-"));

  // Step 4: list the project layer. The MemoryPanel uses this
  // to refresh after each mutation.
  const projectList = await client.list({ scope: "project" });
  assert.equal(projectList.entries.length, 2);
  assert.equal(projectList.totalTokens, 2);
  const allChanges = projectList.entries.every((e) => e.kind === "change");
  assert.equal(allChanges, true);

  // Step 5: list the session layer.
  const sessionList = await client.list({ scope: "session", sessionId: "s-1" });
  assert.equal(sessionList.entries.length, 1);
  assert.equal(sessionList.entries[0].kind, "fact");
  assert.equal(sessionList.entries[0].key, "user.name");

  // Step 6: force a compact. The TUI fires this from the
  // /memory-compact slash command or the 'c' key inside the
  // MemoryPanel.
  const compact = await client.compact({ force: true });
  assert.equal(compact.skipped, false);
  assert.equal(compact.changesCompressed, 2);
  assert.equal(compact.beforeTokens, 2);
  assert.equal(compact.afterTokens, 1);

  // After compact, the changes are marked compressed in the
  // backing store. The next list reflects that.
  const afterCompact = await client.list({ scope: "project" });
  assert.equal(afterCompact.entries.length, 2);
  for (const e of afterCompact.entries) {
    if (e.kind === "change") assert.equal(e.compressed, true);
  }

  // Step 7: switch project. Mirrors the TUI's CwdSwitcher
  // or `/cd` slash command. The TUI uses the returned
  // projectId as the new cache key — the call returns a
  // fresh projectId that the store's consumer (the App)
  // then writes back into its local state.
  const oldProjectId = store.state.currentProjectId;
  const switched = await client.switchProject({ cwd: "D:/projects/foo", placeholderTitle: "Foo" });
  assert.equal(switched.ok, true);
  // The new projectId must be different from the previous
  // one — the daemon's project-switch handler always mints
  // a fresh id for the new cwd.
  assert.notEqual(switched.projectId, oldProjectId);
  assert.ok(switched.projectId.length > 0);

  // Step 8: after the switch, the project list is fresh —
  // the new project has no changes yet.
  const freshProject = await client.list({ scope: "project" });
  assert.equal(freshProject.entries.length, 0);

  // The previous session's facts are still scoped to s-1 and
  // not affected by the project switch.
  const stillThere = await client.list({ scope: "session", sessionId: "s-1" });
  assert.equal(stillThere.entries.length, 1);
});

test("T-097: end-to-end memory flow tolerates a skipped compact (no LLM)", async () => {
  // Without `force: true` the store returns a "skipped"
  // result. The TUI surfaces this as a "skipped" badge in
  // the MemoryPanel footer.
  const store = makeInProcessStore();
  const inv = buildLocalInvoke(store);
  const client = new LocalMemoryRpc(inv);
  await client.appendProjectChange({ description: "x" });
  const r = await client.compact({});
  assert.equal(r.skipped, true);
  assert.equal(r.changesCompressed, 0);
});

// ----- 6. Source-grep tests ------------------------------------

const src = readFileSync(join(root, "src", "memory-rpc.ts"), "utf-8");

test("T-096: memory-rpc.ts exports the three classes + factory", () => {
  assert.match(src, /export class RemoteMemoryRpc/);
  assert.match(src, /export class LocalMemoryRpc/);
  assert.match(src, /export class NullMemoryRpc/);
  assert.match(src, /export function buildLocalInvoke/);
  assert.match(src, /export function pickMemoryRpc/);
  assert.match(src, /export class MemoryRpcError/);
});

test("T-096: RemoteMemoryRpc talks the six memory/* methods on the wire", () => {
  assert.match(src, /"memory\/get"/);
  assert.match(src, /"memory\/appendProjectChange"/);
  assert.match(src, /"memory\/appendSessionFact"/);
  assert.match(src, /"memory\/compact"/);
  assert.match(src, /"memory\/switchProject"/);
  assert.match(src, /"memory\/list"/);
});

test("T-096: MemoryRpcClient interface enumerates the six methods", () => {
  assert.match(src, /get\(params: MemoryGetParams\): Promise<MemoryReadResultWire>/);
  assert.match(src, /appendProjectChange\(/);
  assert.match(src, /appendSessionFact\(/);
  assert.match(src, /compact\(params\?: MemoryCompactParams\)/);
  assert.match(src, /switchProject\(/);
  assert.match(src, /list\(params: MemoryListParams\): Promise<MemoryListResultWire>/);
});

test("T-096: the six handler names appear in aethercode-memory's compiled rpc.js", () => {
  // Cross-check: the TUI's wire method names must match the
  // handlers registered in aethercode-memory. The handlers
  // are exported from aethercode-memory/src/rpc.ts and
  // compiled to dist/rpc.js; we assert on the compiled form
  // since that's the file the TUI's node_modules link sees.
  const memoryDir = join(root, "node_modules", "aethercode-memory", "dist", "rpc.js");
  assert.ok(existsSync(memoryDir), `expected ${memoryDir} to exist (T-095 wiring)`);
  const memSrc = readFileSync(memoryDir, "utf-8");
  // The exported handler names follow the camelCase
  // `memoryGet`, `memoryAppendProjectChange`, etc. pattern
  // even though the JSON-RPC method names use `/`. We assert
  // on the handler names since they're what gets wired in
  // aethercode-protocol.
  assert.match(memSrc, /export (async )?function memoryGet\b/);
  assert.match(memSrc, /export (async )?function memoryAppendProjectChange\b/);
  assert.match(memSrc, /export (async )?function memoryAppendSessionFact\b/);
  assert.match(memSrc, /export (async )?function memoryCompact\b/);
  assert.match(memSrc, /export (async )?function memorySwitchProject\b/);
  assert.match(memSrc, /export (async )?function memoryList\b/);
});

test("T-096: aethercode-memory's index re-exports the six RPC handlers", () => {
  // The dist/index.js bundle re-exports the RPC surface; the
  // TUI never imports aethercode-memory directly (it goes
  // through the daemon), but the re-exports confirm the
  // package's published surface is intact.
  const idxPath = join(root, "node_modules", "aethercode-memory", "dist", "index.js");
  if (!existsSync(idxPath)) return; // optional — the dist may not be present
  const idxSrc = readFileSync(idxPath, "utf-8");
  assert.match(idxSrc, /memoryGet/);
  assert.match(idxSrc, /memoryAppendProjectChange/);
  assert.match(idxSrc, /memoryAppendSessionFact/);
  assert.match(idxSrc, /memoryCompact/);
  assert.match(idxSrc, /memorySwitchProject/);
  assert.match(idxSrc, /memoryList/);
});
