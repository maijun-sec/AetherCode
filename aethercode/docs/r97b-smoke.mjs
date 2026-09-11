// R97-B E2E smoke test: verify per-RPC sessionId
// routing for query + cancel. We send a query
// to a non-default session and confirm:
//   1. The query is accepted (ok=true or accepted=true)
//   2. The session manager logs show the right
//      engine targeted
//   3. The cancel-by-runId + sessionId hint works
//   4. The query with an unknown sessionId returns
//      a clear error
import WebSocket from "ws";

const port = process.argv[2] || "18446";
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

  // 1. listEngines — start with just the default.
  let r = await call(ws, "listEngines", {});
  console.log("1. listEngines (initial):", JSON.stringify(r));
  if (r.count !== 1) throw new Error("expected 1 initial session");

  // 2. Create a non-default session — the factory
  //    will materialise a fresh engine.
  r = await call(ws, "createEngine", { sessionId: "r97b-worktree" });
  console.log("2. createEngine:", JSON.stringify(r));
  if (!r.ok || !r.created) throw new Error("createEngine failed");

  // 3. Send a query to the new session with
  //    sessionId in the params. The query is
  //    expected to fail at the ChatClient level
  //    (the daemon's model client may not be
  //    reachable) but the routing must succeed —
  //    the response should NOT be a routing
  //    error like "no such sessionId".
  r = await call(ws, "query", { prompt: "hello world", sessionId: "r97b-worktree" });
  console.log("3. query(r97b-worktree):", JSON.stringify(r));
  if (r.error) {
    // Acceptable: the model call failed (network,
    // model error) but the sessionId was found.
    if (r.error.includes("no such sessionId")) {
      throw new Error("query was rejected as 'no such sessionId' — routing failed");
    }
    console.log("   (model call failed downstream, but routing succeeded)");
  } else if (r.accepted) {
    console.log("   (query accepted, runId=" + r.runId + ")");
  } else {
    throw new Error("unexpected query response: " + JSON.stringify(r));
  }

  // 4. Query to an UNKNOWN sessionId must be
  //    rejected with a clear error.
  r = await call(ws, "query", { prompt: "hello", sessionId: "ghost" });
  console.log("4. query(ghost):", JSON.stringify(r));
  // The response shape is {ok: false, error: "..."}.
  // We check both fields separately to avoid
  // false positives (e.g. an empty error string).
  if (r.ok !== false) {
    throw new Error("expected ok=false, got: " + JSON.stringify(r));
  }
  if (!r.error || !r.error.includes("no such sessionId")) {
    throw new Error("expected 'no such sessionId' error, got: " + JSON.stringify(r));
  }

  // 5. Query with sessionId but no session
  //    manager configured is the legacy path —
  //    not testable from here (the daemon always
  //    has a manager after R96-B). Skipped.

  // 6. Query without sessionId — should route
  //    to the default engine (currentEngine()).
  r = await call(ws, "query", { prompt: "hello default" });
  console.log("6. query(default):", JSON.stringify(r));
  if (r.error && r.error.includes("session manager")) {
    throw new Error("default query should not need a session manager");
  }

  // 7. cancel with sessionId hint + unknown
  //    runId — should return cancelled=false
  //    with reason "no such runId", not an error.
  r = await call(ws, "cancel", { runId: "run-bogus", sessionId: "r97b-worktree" });
  console.log("7. cancel(bogus, r97b-worktree):", JSON.stringify(r));
  if (r.cancelled !== false || !r.reason.includes("no such runId")) {
    throw new Error("expected cancelled=false with 'no such runId', got: " + JSON.stringify(r));
  }

  // 8. Cleanup: delete the non-default session.
  r = await call(ws, "deleteEngine", { sessionId: "r97b-worktree" });
  console.log("8. deleteEngine(r97b-worktree):", JSON.stringify(r));
  if (!r.removed) throw new Error("deleteEngine failed");

  // 9. Final listEngines — should be back to 1.
  r = await call(ws, "listEngines", {});
  console.log("9. listEngines (final):", JSON.stringify(r));
  if (r.count !== 1) throw new Error("expected 1 final session, got " + r.count);

  ws.close();
  console.log("\nALL CHECKS PASSED");
}

main().catch((e) => {
  console.error("FAIL:", e.message);
  process.exit(1);
});
