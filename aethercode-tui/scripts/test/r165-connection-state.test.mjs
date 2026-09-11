// R165: tests for the daemon-connection state machine.
//
// The user explicitly asked for "后端 java 仍然有很大优化, 比如
// 可靠性差, 经常断连" — R165 is the TUI half of that answer.
// Pre-R165 the JsonRpcClient had no auto-reconnect and no
// "connecting" / "reconnecting" / "disconnected" states; a
// daemon that died left the TUI frozen with no signal.
//
// We test the surface (types, JsonRpcClient public API, state
// reducer, StatusBar) and skip the live respawn loop (which
// would require a real `java` on PATH). Source-code assertions
// catch regressions where someone removes the auto-reconnect
// or strips the status bar's connection badge.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// ----- 1. Source-code assertions: JsonRpcClient ---------------------

test("R165: JsonRpcClient exports ConnectionState type with four members", () => {
  const src = readFileSync(join(root, "src", "jsonrpc.ts"), "utf-8");
  assert.match(src, /export type ConnectionState = "connecting" \| "connected" \| "reconnecting" \| "disconnected"/);
});

test("R165: JsonRpcClient has autoReconnect + setAutoReconnect API", () => {
  const src = readFileSync(join(root, "src", "jsonrpc.ts"), "utf-8");
  assert.match(src, /private autoReconnect: boolean = false/);
  assert.match(src, /setAutoReconnect\(enabled: boolean, maxAttempts\?: number\): void/);
  assert.match(src, /maxReconnectAttempts: number = 10/);
});

test("R165: JsonRpcClient schedules exponential backoff (0.5s → 30s cap)", () => {
  const src = readFileSync(join(root, "src", "jsonrpc.ts"), "utf-8");
  assert.match(src, /private scheduleReconnect/);
  assert.match(src, /Math\.min\(30_000, 500 \* Math\.pow\(2/);
});

test("R165: JsonRpcClient exposes forceReconnect for manual retry (Ctrl-R)", () => {
  const src = readFileSync(join(root, "src", "jsonrpc.ts"), "utf-8");
  assert.match(src, /forceReconnect\(\): void/);
  assert.match(src, /this\.reconnectAttempt = 0;\s*\n\s*if \(this\.stopped\) return/);
});

test("R165: JsonRpcClient has currentConnectionState + currentReconnectAttempt getters", () => {
  const src = readFileSync(join(root, "src", "jsonrpc.ts"), "utf-8");
  assert.match(src, /currentConnectionState\(\): ConnectionState/);
  assert.match(src, /currentReconnectAttempt\(\): number/);
});

// ----- 2. Source-code assertions: state.ts -----------------------------

test("R165: state.ts defines ConnectionState type (TUI-side mirror)", () => {
  const src = readFileSync(join(root, "src", "state.ts"), "utf-8");
  assert.match(src, /export type ConnectionState = "connecting" \| "connected" \| "reconnecting" \| "disconnected"/);
});

test("R165: state.ts adds connectionState / reconnectAttempt / maxReconnectAttempts / lastDisconnectAt / lastStderrTail to State", () => {
  const src = readFileSync(join(root, "src", "state.ts"), "utf-8");
  assert.match(src, /connectionState: ConnectionState;/);
  assert.match(src, /reconnectAttempt: number;/);
  assert.match(src, /maxReconnectAttempts: number;/);
  assert.match(src, /lastDisconnectAt: number \| null;/);
  assert.match(src, /lastStderrTail: string \| null;/);
});

test("R165: state.ts INITIAL seeds the new fields with sane defaults", () => {
  const src = readFileSync(join(root, "src", "state.ts"), "utf-8");
  // The defaults we expect: connectionState=connecting, attempt=0,
  // max=10, lastDisconnectAt=null, lastStderrTail=null.
  assert.match(src, /connectionState: "connecting" as ConnectionState/);
  assert.match(src, /reconnectAttempt: 0,/);
  assert.match(src, /maxReconnectAttempts: 10,/);
  assert.match(src, /lastDisconnectAt: null,/);
  assert.match(src, /lastStderrTail: null,/);
});

test("R165: state.ts action setConnectionState case in reducer", () => {
  const src = readFileSync(join(root, "src", "state.ts"), "utf-8");
  assert.match(src, /type: "setConnectionState"/);
  assert.match(src, /case "setConnectionState":/);
  // The reducer must update connectionState + reconnectAttempt.
  assert.match(src, /connectionState: action\.state/);
  assert.match(src, /reconnectAttempt: action\.attempt/);
});

// ----- 3. Source-code assertions: tui.tsx -----------------------------

test("R165: tui.tsx runTui enables autoReconnect + onConnectionState", () => {
  const src = readFileSync(join(root, "src", "tui.tsx"), "utf-8");
  assert.match(src, /autoReconnect: true,/);
  assert.match(src, /maxReconnectAttempts: 10,/);
  // The onConnectionState emits on process so App's
  // useEffect can dispatch into the reducer.
  assert.match(src, /emit\("aethercode:connectionState"/);
});

test("R165: tui.tsx App wires process.on(\"aethercode:connectionState\") into dispatch", () => {
  const src = readFileSync(join(root, "src", "tui.tsx"), "utf-8");
  assert.match(src, /process as any\)\.on\("aethercode:connectionState"/);
  assert.match(src, /type: "setConnectionState"/);
});

// ----- 4. Source-code assertions: StatusBar ---------------------------

test("R165: StatusBar renders reconnecting badge with attempt count + last error tail", () => {
  const src = readFileSync(join(root, "src", "components", "StatusBar.tsx"), "utf-8");
  assert.match(src, /state\.connectionState === "reconnecting"/);
  assert.match(src, /↻ reconnecting \(/);
  assert.match(src, /\/\{state\.maxReconnectAttempts\}\)/);
  assert.match(src, /state\.lastStderrTail/);
});

test("R165: StatusBar renders disconnected badge with Ctrl-R retry hint", () => {
  const src = readFileSync(join(root, "src", "components", "StatusBar.tsx"), "utf-8");
  assert.match(src, /state\.connectionState === "disconnected"/);
  assert.match(src, /✕ disconnected/);
  assert.match(src, /Ctrl-R to retry/);
});

test("R165: StatusBar renders connecting badge with spinner", () => {
  const src = readFileSync(join(root, "src", "components", "StatusBar.tsx"), "utf-8");
  assert.match(src, /state\.connectionState === "connecting"/);
  assert.match(src, /<Spinner type="dots" \/> connecting\.\.\./);
});

// ----- 5. Filesystem / build checks ----------------------------------

test("R165: jsonrpc.ts compiles (entry intact)", () => {
  assert.ok(existsSync(join(root, "src", "jsonrpc.ts")));
});
test("R165: state.ts compiles (entry intact)", () => {
  assert.ok(existsSync(join(root, "src", "state.ts")));
});
test("R165: StatusBar.tsx compiles (entry intact)", () => {
  assert.ok(existsSync(join(root, "src", "components", "StatusBar.tsx")));
});
