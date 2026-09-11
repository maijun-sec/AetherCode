/**
 * T-443 (Phase 5 R5): rich welcome banner.
 *
 * Replaces the compact `Welcome` component (prior round) on
 * the first-launch screen. The previous design fit in a
 * single dense line ("model · mode · cwd"); the new
 * banner follows the deepagents-code WelcomeBanner
 * (`deepagents_code.tui.widgets.welcome`) shape with one
 * row per field:
 *
 *   - title (wordmark + version)
 *   - model: <provider:model>  (configurable, hidden by
 *     default for parity with deepagents-code which uses
 *     an env-var)
 *   - directory: ~/cwd  (home-prefixed path)
 *   - tracing: '<project>'        (langsmith-style, future)
 *   - replica: '<project>'        (future)
 *   - thread: <id> (open in langsmith)  (debug-only)
 *   - mcp tools: N loaded
 *   - mcp login: N awaiting login  (yellow)
 *   - mcp errors: N failed         (red)
 *   - mcp reconnect: N awaiting reconnect  (muted)
 *   - debug / experimental badges  (debug env)
 *   - (running from editable install)  (dev)
 *
 * All rows are independently toggleable via the
 * `showModel` / `showCwd` / `showThreadId` / etc. props.
 * Defaults match the deepagents-code Python port:
 *   - model visible only when DEEPAGENTS_CODE_SPLASH_SHOW_MODEL=1
 *   - cwd visible only when DEEPAGENTS_CODE_SPLASH_SHOW_CWD=1
 *   - thread id visible only in debug mode
 *   - all rows render in order; missing values are skipped
 *
 * The previous `Welcome` component is kept as a small
 * alias for callers that still want the one-line
 * summary. `App` (in tui.tsx) routes the new banner on
 * `tutorialOpen` and falls back to `Welcome` on
 * `connected && turns.length === 0` (the per-message
 * one-liner that sits at the top of an empty scrollback).
 */

import React from "react";
import { Box, Text } from "ink";
import { t, wordmark, icon } from "../theme.js";

/** T-443: the rich welcome payload. Mirrors the
 *  `WelcomeBanner` Java port. */
export interface WelcomeBannerInfo {
  modelProvider?: string;
  modelName?: string;
  cwd?: string;
  version?: string;
  threadId?: string;
  projectName?: string;
  replicaProject?: string;
  projectUrls?: Record<string, string>;
  mcpToolCount?: number;
  mcpUnauthenticated?: number;
  mcpErrored?: number;
  mcpAwaitingReconnect?: number;
  showModel?: boolean;
  showCwd?: boolean;
  hideCwd?: boolean;
  hideVersion?: boolean;
  showThreadId?: boolean;
  debugEnabled?: boolean;
  experimentalEnabled?: boolean;
  editableInstall?: boolean;
}

/** T-443: ANSI themes (deepagents-code parity). */
export const ANSI_THEMES: ReadonlySet<string> = new Set(["ansi-dark", "ansi-light"]);

/** T-443: UTM source for langsmith URLs (future). */
export const LANGSMITH_UTM_SOURCE = "aethercode";

/** T-443: format a path with `~/` prefix when it sits
 *  under the user's home directory. Pure helper. */
export function homePrefixed(cwd: string, home: string | null = null): string {
  if (!cwd) return "";
  const h = home ?? process.env.HOME ?? process.env.USERPROFILE ?? "";
  if (!h) return cwd;
  if (cwd === h) return "~";
  if (cwd.startsWith(h + "/") || cwd.startsWith(h + "\\")) {
    return "~/" + cwd.substring(h.length + 1);
  }
  return cwd;
}

/** T-443: a single label/value row, padded to a
 *  consistent width for visual alignment. */
function Row({ label, value, color }: { label: string; value: string; color?: string }): React.ReactElement {
  return (
    <Box>
      <Text dimColor>{label.padEnd(11, " ")}</Text>
      <Text color={color ?? t.asst}>{value}</Text>
    </Box>
  );
}

interface Props extends WelcomeBannerInfo {
  /** Cosmetic session id (used in the title). */
  sessionId?: string;
}

/** T-443: the rich welcome banner. */
export const WelcomeBanner: React.FC<Props> = (info) => {
  const {
    modelProvider = "",
    modelName = "",
    cwd = "",
    version = "0.0.0",
    threadId = "",
    projectName = "",
    replicaProject = "",
    projectUrls = {},
    mcpToolCount = 0,
    mcpUnauthenticated = 0,
    mcpErrored = 0,
    mcpAwaitingReconnect = 0,
    showModel = false,
    showCwd = false,
    hideCwd = false,
    hideVersion = false,
    showThreadId = false,
    debugEnabled = false,
    experimentalEnabled = false,
    editableInstall = false,
  } = info;

  // Title row: "▶ AetherCode v0.2.1 (debug enabled) (experimental) (local)"
  const titleBits: string[] = [`▶ ${wordmark}`];
  if (!hideVersion) titleBits.push(`v${version}`);
  if (debugEnabled) titleBits.push("(debug enabled)");
  if (experimentalEnabled) titleBits.push("(experimental)");
  if (!hideVersion && editableInstall) titleBits.push("(local)");

  return (
    <Box
      flexDirection="column"
      borderStyle="round"
      borderColor={t.brand}
      paddingX={2}
      paddingY={1}
    >
      <Text>
        <Text color={t.brand} bold>{titleBits[0]}</Text>
        {titleBits.slice(1).map((b, i) => (
          <Text key={i} color={t.brand}>  {b}</Text>
        ))}
      </Text>

      {showModel && modelName ? (
        <Row
          label="model:"
          value={modelProvider ? `${modelProvider}:${modelName}` : modelName}
        />
      ) : null}

      {showCwd && cwd && !hideCwd ? (
        <Row
          label="directory:"
          value={homePrefixed(cwd)}
        />
      ) : null}

      {projectName ? (
        <Row
          label="tracing:"
          value={(() => {
            const url = projectUrls[projectName];
            return url ? `'${projectName}' (${url})` : `'${projectName}'`;
          })()}
        />
      ) : null}

      {replicaProject ? (
        <Row
          label="replica:"
          value={(() => {
            const url = projectUrls[replicaProject];
            return url ? `'${replicaProject}' (${url})` : `'${replicaProject}'`;
          })()}
        />
      ) : null}

      {showThreadId && threadId ? (
        <Row
          label="thread:"
          value={`${threadId} (open in langsmith)`}
        />
      ) : null}

      {mcpToolCount > 0 ? (
        <Row label="mcp tools:" value={`${mcpToolCount} loaded`} />
      ) : null}

      {mcpUnauthenticated > 0 ? (
        <Row label="mcp login:" value={`${mcpUnauthenticated} awaiting login`} color={t.warn} />
      ) : null}

      {mcpErrored > 0 ? (
        <Row label="mcp errors:" value={`${mcpErrored} failed to load`} color={t.err} />
      ) : null}

      {mcpAwaitingReconnect > 0 ? (
        <Row label="mcp reconnect:" value={`${mcpAwaitingReconnect} awaiting reconnect`} color={t.dim} />
      ) : null}

      {editableInstall && !hideCwd ? (
        <Text dimColor>  (running from editable install)</Text>
      ) : null}

      <Text dimColor>
        {icon.arrow} type a prompt below · / for commands · @ for files · Ctrl-? for shortcuts
      </Text>
    </Box>
  );
};

export default WelcomeBanner;
