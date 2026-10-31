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
 * T-6 RPC typed surface — phase 6.2 / 6.3 / 7 additions.
 *
 * The `rpc/client.ts` + `rpc/queries.ts` + `rpc/subscriptions.ts`
 * modules are owned by TS-T1 and may not exist yet at the time
 * of writing. The components in this directory therefore expose
 * PRESENTATIONAL props (data + callbacks) and do not import any
 * concrete RPC client. The host (tui.tsx) is the integration
 * point that translates the presentational props into RPC calls.
 *
 * <p>This file documents the JSON-RPC contract that the new
 * components EXPECT. The host is responsible for satisfying
 * that contract. The shapes are 1:1 with the design's §3.1
 * list of new RPC methods.
 *
 * <p>Why a separate file and not just `jsonrpc.ts`? Because
 * the presentational components here need typed shapes they
 * can import without dragging the (heavier, child-process-
 * spawning) JsonRpcClient into the type-checker path. Keeping
 * the contract here makes the components usable from tests
 * that just want a typed shape.
 */

// --------------------------------------------------------------------
//  task/*  (Phase 6 §5 — long-running execution)
// --------------------------------------------------------------------

/** `task/resume` — request params. */
export interface TaskResumeParams {
  id: string;
}

/** `task/pause` — request params. */
export interface TaskPauseParams {
  id: string;
}

/** `task/kill` — request params. */
export interface TaskKillParams {
  id: string;
}

// --------------------------------------------------------------------
//  session/* (Phase 6 §1-§3)
// --------------------------------------------------------------------

/** `session/tokens` — response. */
export interface SessionTokensResult {
  input: number;
  output: number;
  total: number;
  costUsd: number;
}

// --------------------------------------------------------------------
//  grants/*  (Phase 6 §9)
// --------------------------------------------------------------------

/** `grants/setPreset` — request params. */
export interface GrantsSetPresetParams {
  preset: "permissive" | "cautious" | "strict";
}

/** `grants/revoke` — request params. */
export interface GrantsRevokeParams {
  id: string;
}

// --------------------------------------------------------------------
//  model/*  (Phase 6 §10)
// --------------------------------------------------------------------

/** `model/set` — request params. */
export interface ModelSetParams {
  sessionId?: string;
  name: string;
}

/** `model/list` — single entry. Mirrors the
 *  `aethercode-models.ModelProfile` Java record. */
export interface ModelListEntry {
  name: string;
  provider: string;
  contextWindow: number;
  maxOutput: number;
  priceIn: number;
  priceOut: number;
  priceCached: number;
  capabilities: {
    vision?: boolean;
    tools?: boolean;
    jsonMode?: boolean;
    reasoning?: boolean;
    tier?: string;
  };
}

// --------------------------------------------------------------------
//  workflow/*  (Phase 6 §11)
// --------------------------------------------------------------------

/** `workflow/run` — request params. */
export interface WorkflowRunParams {
  name: string;
  inputs?: Record<string, unknown>;
  cwd?: string;
}
