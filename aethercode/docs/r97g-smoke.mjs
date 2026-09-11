// R97-G E2E smoke test: verify per-RPC sessionId
// routing for getState / listTools / setModel /
// setPermissionMode. Sends RPCs without a sessionId
// (default engine), with a known sessionId
// (factory-built engine), and with a ghost
// sessionId (rejected).
import WebSocket from "ws";

const port = process.argv[2] || "18451";
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

  // 1. Setup: create a non-default session.
  let r = await call(ws, "createEngine", { sessionId: "r97g-alpha" });
  console.log("1. createEngine:", JSON.stringify(r));
  if (!r.ok) throw new Error("createEngine failed");

  // 2. getState WITHOUT sessionId — should return
  //    the DEFAULT engine's state.
  r = await call(ws, "getState", {});
  console.log("2. getState (default):", JSON.stringify(r));
  if (!r.model) throw new Error("getState default failed");
  const defaultSessionId = r.sessionId;
  const defaultModel = r.model;

  // 3. getState WITH sessionId="r97g-alpha" — should
  //    return the FACTORY-BUILT engine's state
  //    (different sessionId; probably same model
  //    since the factory mirrors the CLI config).
  r = await call(ws, "getState", { sessionId: "r97g-alpha" });
  console.log("3. getState (r97g-alpha):", JSON.stringify(r));
  if (r.sessionId !== "r97g-alpha") {
    throw new Error("expected sessionId=r97g-alpha, got " + r.sessionId);
  }
  if (r.sessionId === defaultSessionId) {
    throw new Error("factory-built engine has the same sessionId as default — routing failed");
  }

  // 4. getState WITH sessionId="ghost" — should be
  //    rejected with a clear error.
  r = await call(ws, "getState", { sessionId: "ghost" });
  console.log("4. getState (ghost):", JSON.stringify(r));
  if (r.ok !== false || !r.error.includes("no such sessionId")) {
    throw new Error("expected rejection: " + JSON.stringify(r));
  }

  // 5. listTools WITH sessionId="r97g-alpha" — should
  //    return the factory-built engine's tool list
  //    (same shape as the default; the response
  //    also carries the sessionId).
  r = await call(ws, "listTools", { sessionId: "r97g-alpha" });
  console.log("5. listTools (r97g-alpha):", JSON.stringify(r).slice(0, 200));
  if (r.sessionId !== "r97g-alpha") {
    throw new Error("listTools routing failed: " + r.sessionId);
  }
  if (!Array.isArray(r.tools)) {
    throw new Error("listTools shape wrong: " + JSON.stringify(r));
  }

  // 6. setModel WITH sessionId="r97g-alpha" — should
  //    update ONLY the factory-built engine's
  //    mainLoopModel. The default engine keeps its
  //    original model.
  r = await call(ws, "setModel", { model: "M-r97g-test", sessionId: "r97g-alpha" });
  console.log("6. setModel (r97g-alpha):", JSON.stringify(r));
  if (r.model !== "M-r97g-test" || r.sessionId !== "r97g-alpha") {
    throw new Error("setModel routing failed: " + JSON.stringify(r));
  }

  // 7. Verify the default engine's model is
  //    unchanged (read getState without sessionId).
  r = await call(ws, "getState", {});
  console.log("7. getState (default after setModel):", JSON.stringify(r));
  if (r.model === "M-r97g-test") {
    throw new Error("setModel on r97g-alpha leaked into the default engine");
  }
  if (r.model !== defaultModel) {
    throw new Error("default engine's model unexpectedly changed: " + r.model);
  }

  // 8. Verify the factory-built engine's model DID
  //    change.
  r = await call(ws, "getState", { sessionId: "r97g-alpha" });
  console.log("8. getState (r97g-alpha after setModel):", JSON.stringify(r));
  if (r.model !== "M-r97g-test") {
    throw new Error("setModel on r97g-alpha didn't update the factory-built engine: " + r.model);
  }

  // 9. setPermissionMode WITH sessionId="r97g-alpha"
  //    — should update only that engine.
  r = await call(ws, "setPermissionMode", { mode: "BYPASS_PERMISSIONS", sessionId: "r97g-alpha" });
  console.log("9. setPermissionMode (r97g-alpha):", JSON.stringify(r));
  if (r.mode !== "BYPASS_PERMISSIONS" || r.sessionId !== "r97g-alpha") {
    throw new Error("setPermissionMode routing failed: " + JSON.stringify(r));
  }

  // 10. The default engine keeps its original mode.
  r = await call(ws, "getState", {});
  console.log("10. getState (default after setPermissionMode):", JSON.stringify(r));
  if (r.permissionMode === "BYPASS_PERMISSIONS") {
    throw new Error("setPermissionMode on r97g-alpha leaked into the default engine");
  }

  // 11. setModel on a non-existent session — clear error.
  r = await call(ws, "setModel", { model: "M-ghost", sessionId: "ghost" });
  console.log("11. setModel (ghost):", JSON.stringify(r));
  if (r.ok !== false || !r.error.includes("no such sessionId")) {
    throw new Error("setModel on ghost should be rejected: " + JSON.stringify(r));
  }

  // 12. Cleanup.
  r = await call(ws, "deleteEngine", { sessionId: "r97g-alpha" });
  console.log("12. deleteEngine (r97g-alpha):", JSON.stringify(r));
  if (!r.removed) throw new Error("deleteEngine failed");

  ws.close();
  console.log("\nALL CHECKS PASSED");
}

main().catch((e) => {
  console.error("FAIL:", e.message);
  process.exit(1);
});
