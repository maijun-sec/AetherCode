/**
 * T-275 / design.md §3.6: typed wrappers for the five
 * `permission/*` JSON-RPC methods exposed by the daemon.
 *
 * <p>The base JSON-RPC client (jsonrpc.ts) is generic; this
 * module is the typed surface the TUI uses. Each function
 * documents the wire shape (params + result) per the design,
 * so a renderer never has to inline a raw method name.
 *
 * <p>Method bindings (all defined in
 * aethercode-protocol/.../PermissionMethods.java):
 *
 *   permission/check   { tool, args, sessionId? } → { allow, reason?, requiresPrompt, risk, categories[], drivingGrantId?, outcome }
 *   permission/prompt  { tool, args, sessionId?, actor? } → same as check, but writes a PROMPTED audit entry
 *   permission/list    { scope?, sessionId? } → Grant[]
 *   permission/revoke  { id, actor? } → { ok: true, id }
 *   permission/clear   { scope, sessionId?, actor? } → { revoked: number }
 */

import type { JsonRpcClient, RpcValue } from "./jsonrpc.js";

// --------------------------------------------------------------------
//  Types
// --------------------------------------------------------------------

/** A single consent grant as returned by `permission/list`. */
export interface PermissionGrant {
  id: string;
  scope: "session" | "project" | "user";
  scopeId: string;
  category: string;
  decision: "allow" | "deny";
  reason: string;
  createdAt: number;
  expiresAt?: number;
}

/** Result of `permission/check` (and `permission/prompt`). */
export interface PermissionCheckResult {
  /** True iff the engine should run the tool silently. */
  allow: boolean;
  /** True iff the engine should pop a consent prompt. */
  requiresPrompt: boolean;
  /** Human-readable reason (audit + UI). */
  reason: string;
  /** Risk level: low | medium | high. */
  risk: "low" | "medium" | "high";
  /** Categories emitted by the categorizer (may be empty). */
  categories: string[];
  /** id of the grant that drove the decision, when applicable. */
  drivingGrantId?: string;
  /** Terminal outcome: allow | deny | prompt. */
  outcome: "allow" | "deny" | "prompt";
}

/** Params for `permission/check` / `permission/prompt`. */
export interface PermissionCheckParams {
  tool: string;
  args?: Record<string, unknown>;
  sessionId?: string;
  actor?: string;
  [k: string]: unknown;
}

/** Params for `permission/list`. */
export interface PermissionListParams {
  scope?: "session" | "project" | "user";
  sessionId?: string;
  [k: string]: unknown;
}

/** Params for `permission/revoke`. */
export interface PermissionRevokeParams {
  id: string;
  actor?: string;
  [k: string]: unknown;
}

/** Params for `permission/clear`. */
export interface PermissionClearParams {
  scope: "session" | "project" | "user";
  sessionId?: string;
  actor?: string;
  [k: string]: unknown;
}

// --------------------------------------------------------------------
//  Methods
// --------------------------------------------------------------------

/**
 * Run the pre-flight consent check for a tool call. Pure —
 * does not write to the audit log.
 */
export function permissionCheck(
  client: JsonRpcClient,
  params: PermissionCheckParams,
  options: { timeoutMs?: number } = {},
): Promise<PermissionCheckResult> {
  return client.request<PermissionCheckResult>(
    "permission/check",
    stripUndef(params) as RpcValue,
    options,
  );
}

/**
 * Same as `permissionCheck` but also writes a `PROMPTED` audit
 * entry. Call this when the user is actively facing a prompt.
 */
export function permissionPrompt(
  client: JsonRpcClient,
  params: PermissionCheckParams,
  options: { timeoutMs?: number } = {},
): Promise<PermissionCheckResult> {
  return client.request<PermissionCheckResult>(
    "permission/prompt",
    stripUndef(params) as RpcValue,
    options,
  );
}

/**
 * List every active grant at the requested scope (or all
 * three scopes when the param is absent). Expired grants are
 * filtered server-side.
 */
export function permissionList(
  client: JsonRpcClient,
  params: PermissionListParams = {},
  options: { timeoutMs?: number } = {},
): Promise<PermissionGrant[]> {
  return client.request<PermissionGrant[]>(
    "permission/list",
    stripUndef(params) as RpcValue,
    options,
  );
}

/**
 * Revoke a single grant by id. The id is opaque — the daemon
 * scans every layer and removes the match. Throws
 * `RpcCallError` with code -32604 when not found.
 */
export function permissionRevoke(
  client: JsonRpcClient,
  params: PermissionRevokeParams,
  options: { timeoutMs?: number } = {},
): Promise<{ ok: true; id: string }> {
  return client.request<{ ok: true; id: string }>(
    "permission/revoke",
    stripUndef(params) as RpcValue,
    options,
  );
}

/**
 * Bulk-revoke every grant at the requested scope. Returns the
 * number of grants removed. The audit log gets one
 * `REVOKED` entry per grant.
 */
export function permissionClear(
  client: JsonRpcClient,
  params: PermissionClearParams,
  options: { timeoutMs?: number } = {},
): Promise<{ revoked: number }> {
  return client.request<{ revoked: number }>(
    "permission/clear",
    stripUndef(params) as RpcValue,
    options,
  );
}

// --------------------------------------------------------------------
//  helpers
// --------------------------------------------------------------------

/** Strip undefined keys so the wire shape matches the
 *  server-side parser (which is strict about unknown
 *  fields). The daemon tolerates them too, but the
 *  TUI's typed wrapper should be tidy. */
function stripUndef<T extends Record<string, unknown>>(o: T): Partial<T> {
  const out: Partial<T> = {};
  for (const k of Object.keys(o) as (keyof T)[]) {
    if (o[k] !== undefined) out[k] = o[k];
  }
  return out;
}
