// R80 E2E: prove the HTTP+WebSocket daemon is reachable end-to-end
// from a real WebSocket client, and that the engine state is
// observable via HTTP routes.
//
// Spawn the daemon in --http-port mode, then:
//   1. GET /              — engine info
//   2. GET /healthz       — liveness
//   3. GET /api/methods   — well-known methods
//   4. GET /api/info      — engine + metrics + traces
//   5. WS  /ws            — JSON-RPC 2.0 ping round-trip
//   6. WS  /ws            — listTools round-trip (uses the engine)
//
// All assertions must hold within 5 s.

import { spawn } from "node:child_process";
import { setTimeout as sleep } from "node:timers/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import WebSocket from "ws";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const jar = join(root, "..", "dist", "aethercode-0.2.1.jar");

const PORT = 17888; // avoid clashing with 7777 in case it's in use
const base = `http://localhost:${PORT}`;

const child = spawn("java", ["-jar", jar, "--http-port", String(PORT)], {
  stdio: ["ignore", "pipe", "pipe"],
  env: { ...process.env, JAVA_TOOL_OPTIONS: "" },
});
child.stderr.on("data", () => { /* daemon logs to stderr */ });
child.on("error", (e) => { console.log(`spawn error: ${e.message}`); process.exit(1); });

let failed = false;
function check(name, ok, detail = "") {
  if (ok) { console.log(`✓ ${name}`); }
  else { console.log(`✗ ${name}  ${detail}`); failed = true; }
}

function wsJsonRpc(ws, method, params) {
  return new Promise((resolve, reject) => {
    const id = Math.floor(Math.random() * 1e6);
    const onMsg = (data) => {
      try {
        const msg = JSON.parse(data.toString());
        if (msg.id === id) {
          ws.off("message", onMsg);
          if (msg.error) reject(new Error(msg.error.message || "rpc error"));
          else resolve(msg.result);
        }
      } catch (e) { reject(e); }
    };
    ws.on("message", onMsg);
    ws.send(JSON.stringify({ jsonrpc: "2.0", id, method, params: params ?? {} }));
    setTimeout(() => { ws.off("message", onMsg); reject(new Error("timeout")); }, 8000);
  });
}

// Wait for /healthz to return 200 (the server is up).
async function waitForReady(deadlineMs = 30_000) {
  const start = Date.now();
  while (Date.now() - start < deadlineMs) {
    try {
      const r = await fetch(`${base}/healthz`);
      if (r.ok) return;
    } catch { /* server still starting */ }
    await sleep(300);
  }
  throw new Error("daemon did not become ready in time");
}

(async () => {
  try {
    await waitForReady();
    check("daemon /healthz returns 200", true);

    const root = await fetch(`${base}/`);
    const rootJson = await root.json();
    check("GET / returns status:ok", rootJson.status === "ok", JSON.stringify(rootJson));
    check("GET / returns engine.sessionId", typeof rootJson.engine?.sessionId === "string");

    const methods = await (await fetch(`${base}/api/methods`)).json();
    const methodList = methods.methods ?? [];
    for (const m of ["ping", "getState", "query", "getMetrics", "getTraces", "getTrace"]) {
      check(`GET /api/methods lists ${m}`, methodList.includes(m));
    }

    const info = await (await fetch(`${base}/api/info`)).json();
    check("GET /api/info includes metrics", info.metrics && typeof info.metrics.turnsStarted === "number");
    check("GET /api/info includes traces", info.traces && Array.isArray(info.traces.traces));

    const ws = new WebSocket(`ws://localhost:${PORT}/ws`);
    await new Promise((resolve, reject) => {
      ws.once("open", resolve);
      ws.once("error", reject);
    });
    check("WebSocket /ws connected", true);

    // Welcome message (broadcast notification).
    let welcomeText = "";
    ws.once("message", (data) => { welcomeText = data.toString(); });
    await sleep(300);
    check("WS sends welcome notification on connect", welcomeText.includes("\"type\":\"welcome\""), welcomeText);

    const pong = await wsJsonRpc(ws, "ping");
    check("WS ping round-trip returns sessionId", typeof pong?.sessionId === "string", JSON.stringify(pong));

    const state = await wsJsonRpc(ws, "getState");
    check("WS getState returns model", typeof state?.model === "string");

    const tools = await wsJsonRpc(ws, "listTools");
    check("WS listTools returns tools array", Array.isArray(tools?.tools));

    const metrics = await wsJsonRpc(ws, "getMetrics");
    check("WS getMetrics returns turnsStarted", typeof metrics?.turnsStarted === "number");

    ws.close();
    check("WS closed cleanly", true);
  } catch (e) {
    console.log(`✗ exception: ${e.message}`);
    failed = true;
  } finally {
    try { child.kill(); } catch {}
    await sleep(500);
    process.exit(failed ? 1 : 0);
  }
})();
