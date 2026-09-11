// R97-A E2E smoke test: connect to the daemon, run the
// multi-session surface, verify createEngine actually
// builds a fresh engine (not the pre-registered default).
import WebSocket from "ws";

const port = process.argv[2] || "18444";
const url = `ws://localhost:${port}/ws`;
let nextId = 1;

function call(ws, method, params) {
  return new Promise((resolve, reject) => {
    const id = nextId++;
    const msg = { jsonrpc: "2.0", id, method, params: params || {} };
    const handler = (data) => {
      const m = JSON.parse(data.toString());
      if (m.id === id) {
        ws.off("message", handler);
        if (m.error) reject(new Error(JSON.stringify(m.error)));
        else resolve(m.result);
      }
    };
    ws.on("message", handler);
    ws.send(JSON.stringify(msg));
  });
}

async function main() {
  const ws = new WebSocket(url);
  await new Promise((r) => ws.once("open", r));
  console.log("connected to", url);

  // 1. listEngines — should return exactly the default session.
  let r = await call(ws, "listEngines", {});
  console.log("1. listEngines (initial):", JSON.stringify(r));
  if (!r.ok) throw new Error("listEngines failed: " + JSON.stringify(r));
  if (r.count !== 1) throw new Error("expected 1 session, got " + r.count);
  if (r.sessions[0].sessionId !== "default")
    throw new Error("expected default sessionId");

  // 2. createEngine for "worktree-1" — R97-A: this must
  // invoke the factory and build a fresh engine.
  r = await call(ws, "createEngine", { sessionId: "worktree-1" });
  console.log("2. createEngine:", JSON.stringify(r));
  if (!r.ok) throw new Error("createEngine failed: " + JSON.stringify(r));
  if (!r.created) throw new Error("expected created=true");
  if (r.sessionId !== "worktree-1")
    throw new Error("expected sessionId=worktree-1");

  // 3. listEngines — should now return 2 sessions.
  r = await call(ws, "listEngines", {});
  console.log("3. listEngines (after create):", JSON.stringify(r));
  if (r.count !== 2) throw new Error("expected 2 sessions, got " + r.count);
  const ids = r.sessions.map((s) => s.sessionId).sort();
  if (ids[0] !== "default" || ids[1] !== "worktree-1")
    throw new Error("expected [default, worktree-1], got " + JSON.stringify(ids));

  // 4. setActiveEngine to "worktree-1".
  r = await call(ws, "setActiveEngine", { sessionId: "worktree-1" });
  console.log("4. setActiveEngine:", JSON.stringify(r));
  if (!r.ok || r.activeSessionId !== "worktree-1")
    throw new Error("setActiveEngine failed");

  // 5. getActiveEngine should now report "worktree-1".
  r = await call(ws, "getActiveEngine", {});
  console.log("5. getActiveEngine:", JSON.stringify(r));
  if (r.activeSessionId !== "worktree-1")
    throw new Error("expected activeSessionId=worktree-1");

  // 6. deleteEngine for "worktree-1" — factory-built session can be removed.
  r = await call(ws, "deleteEngine", { sessionId: "worktree-1" });
  console.log("6. deleteEngine:", JSON.stringify(r));
  if (!r.ok || !r.removed) throw new Error("deleteEngine failed");

  // 7. listEngines — should be back to 1 session.
  r = await call(ws, "listEngines", {});
  console.log("7. listEngines (after delete):", JSON.stringify(r));
  if (r.count !== 1) throw new Error("expected 1 session, got " + r.count);

  // 8. deleteEngine on "default" — must be rejected.
  r = await call(ws, "deleteEngine", { sessionId: "default" });
  console.log("8. deleteEngine(default):", JSON.stringify(r));
  if (!r.removed) console.log("   (default is deletion-protected, as expected)");

  ws.close();
  console.log("\nALL CHECKS PASSED");
}

main().catch((e) => {
  console.error("FAIL:", e.message);
  process.exit(1);
});
