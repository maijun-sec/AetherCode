/**
 * scrollback (conversation list).
 *
 * Renders the most recent N "turns" in a single boxed column.
 * Each turn dispatches to the appropriate component:
 *   - user:       blue prompt marker + text
 *   - assistant:  Markdown-rendered text (prior round: collapsed if long)
 *   - thinking:   R86: dim, collapsible sub-section between <think> tags
 *   - tool:       ToolCard (boxed, prior round: collapsible)
 *   - plan:       PlanList (boxed)
 *   - system:     dim single line (prior round: collapsed)
 *   - error:      red single line
 *   - permission: R86: inline "decision pending" card
 */

import React from "react";
import { Box, Text } from "ink";
import type { Turn, PermissionAsk } from "../state.js";
import { t, icon } from "../theme.js";
import { Markdown } from "./Markdown.js";
import { ToolCard } from "./ToolCard.js";
import { PlanList } from "./PlanList.js";
import { Welcome } from "./Welcome.js";
import {
  SummaryFooter,
  splitAssistantMessage,
  type Summary,
} from "./chat/SummaryFooter.js";

interface Props {
  turns: Turn[];
  height: number;
  showWelcome: boolean;
  welcomeCtx: { model: string; cwd: string; sessionId: string; mode?: string };
  /** when true, all turns render in their collapsed form. */
  collapseAll: boolean;
  /** callback to toggle a specific turn's collapsed state. */
  onToggleTurn: (id: number) => void;
  /** most recent run's stop reason — null if the run is
   *  still in flight or hasn't started yet. */
  lastStopReason: string | null;
  /** derived kind of the stop reason. Controls banner colour. */
  lastStopKind: "ok" | "loop" | "max_turns" | "error" | "empty" | null;
  /** most recent error to display as a dedicated card.
   *  Cleared when the user submits a new query. */
  lastError: { message: string; ts: number; stack?: string } | null;
  /** when false, all "thinking" turns are hidden entirely
   *  (Ctrl-I toggle). Default: true. */
  showThinking: boolean;
  /** when non-null and `decisionCardExpanded` is true, render
   *  the inline permission card. The card sits at the top of the
   *  scrollback so the user can see the rest of the conversation
   *  while making a decision. */
  permissionAsk: PermissionAsk | null;
  /** when false, the inline card collapses to a single line
   *  (the user can keep typing in the input box). Default: true. */
  decisionCardExpanded: boolean;
}

function fmtTime(ts: number): string {
  return new Date(ts).toLocaleTimeString();
}

function truncate(s: string, n: number): string {
  if (s.length <= n) return s;
  return s.slice(0, Math.max(0, n - 1)) + "…";
}

/** format the input dict of a permission ask into a single
 *  human-readable line. Mirrors PermissionModal.formatInput but
 *  terser — the inline card has less room than the full-screen
 *  modal. */
function formatAskInput(input: Record<string, unknown>): string {
  if (typeof input.command === "string") return `command: ${input.command}`;
  if (typeof input.file_path === "string") return `file: ${input.file_path}`;
  if (typeof input.path === "string") return `path: ${input.path}`;
  if (typeof input.url === "string") return `url: ${input.url}`;
  const keys = Object.keys(input);
  if (keys.length === 0) return "(no args)";
  return keys.map((k) => `${k}=${JSON.stringify(input[k])}`).join(", ");
}

const RISK_COLOR: Record<string, string> = {
  low:      t.ok,
  medium:   t.warn,
  high:     "yellowBright",
  critical: t.err,
};
const RISK_LABEL: Record<string, string> = {
  low:      "LOW",
  medium:   "MEDIUM",
  high:     "HIGH",
  critical: "CRITICAL",
};

/** inline "decision pending" card. Renders inside the
 *  scrollback (so the user can see the rest of the conversation)
 *  rather than as a full-screen modal. The decision keys
 *  A/T/P/U/D/N are handled in the App component's useInput —
 *  this card is presentation only. */
const PermissionCard: React.FC<{ ask: PermissionAsk; expanded: boolean }> = ({ ask, expanded }) => {
  const riskColor = RISK_COLOR[ask.riskLevel] ?? t.warn;
  const riskLabel = RISK_LABEL[ask.riskLevel] ?? ask.riskLevel.toUpperCase();
  if (!expanded) {
    // Collapsed: one-line pill. The user can keep typing in the
    // input box; the A/T/P/U/D/N keys still fire.
    return (
      <Box marginY={0} paddingX={1} borderStyle="single" borderColor={riskColor}>
        <Text>
          <Text color={riskColor} bold>{icon.warn} decision pending</Text>
          <Text dimColor>  ·  </Text>
          <Text bold>{ask.tool}</Text>
          <Text dimColor>  ·  [{riskLabel}]</Text>
          <Text dimColor>  ·  A=allow  T=task  P=project  U=user  D=deny  N=never</Text>
        </Text>
      </Box>
    );
  }
  return (
    <Box
      flexDirection="column"
      marginY={0}
      paddingX={1}
      borderStyle="round"
      borderColor={riskColor}
    >
      <Text>
        <Text color={riskColor} bold>{icon.warn} PERMISSION REQUIRED</Text>
        <Text dimColor>  ·  [{riskLabel}] risk</Text>
      </Text>
      <Text> </Text>
      <Text>
        <Text>tool:   </Text>
        <Text bold color={t.brand}>{ask.tool}</Text>
      </Text>
      <Text>
        <Text>input:  </Text>
        <Text>{formatAskInput(ask.input)}</Text>
      </Text>
      <Text>
        <Text>reason: </Text>
        <Text dimColor>{ask.reason}</Text>
      </Text>
      <Text> </Text>
      <Text dimColor>  choose an option — these keys work even while the input box is focused:</Text>
      <Text>    <Text color={t.ok} bold>[A]</Text> allow this call once</Text>
      <Text>    <Text color={t.ok} bold>[T]</Text> allow for this task (rest of this sub-task)</Text>
      <Text>    <Text color={t.ok} bold>[P]</Text> allow for this project (saved to .aethercode/permissions.json)</Text>
      <Text>    <Text color={t.ok} bold>[U]</Text> allow for this user (saved to ~/.aethercode/.../permissions.json)</Text>
      <Text>    <Text color={t.err} bold>[D]</Text> deny this call once</Text>
      <Text>    <Text color={t.err} bold>[N]</Text> deny and remember for this tool</Text>
    </Box>
  );
};

const TurnLine: React.FC<{ turn: Turn; effectiveCollapsed: boolean }> = ({ turn, effectiveCollapsed }) => {
  const ts = fmtTime(turn.ts);
  switch (turn.role) {
    case "user":
      return (
        <Box flexDirection="column" marginY={0}>
          <Text>
            <Text dimColor>{ts} </Text>
            <Text color={t.user}>{icon.user} </Text>
            <Text>{turn.text}</Text>
          </Text>
        </Box>
      );
    case "assistant": {
      // T-6-14: extract the `## Summary` block from the
      // assistant message. The lead (everything before
      // `## Summary`) is the body; the summary is rendered
      // separately as the always-visible footer card.
      const { lead, summary } = splitAssistantMessage(turn.text);

      // if collapsed, show a one-line preview instead of full markdown.
      if (effectiveCollapsed) {
        return (
          <Box flexDirection="column" marginY={0}>
            <Text>
              <Text dimColor>{ts} </Text>
              <Text color={t.brand}>💬 </Text>
              <Text dimColor>{truncate(lead.replace(/\n+/g, " "), turn.previewChars)}</Text>
              <Text dimColor>  ·  Tab to expand</Text>
            </Text>
            {/* T-6-14: summary footer is ALWAYS visible,
                even when the rest of the turn is collapsed. */}
            <SummaryFooter summary={summary} />
          </Box>
        );
      }
      return (
        <Box flexDirection="column" marginY={0}>
          <Text>
            <Text dimColor>{ts} </Text>
            <Text color={t.brand} bold>💬 Reply  </Text>
          </Text>
          <Box marginLeft={2}>
            {lead ? <Markdown text={lead} /> : <Text dimColor>…</Text>}
          </Box>
          {/* T-6-14: summary footer rendered as the LAST
              line of the assistant message; never collapsed. */}
          <Box marginTop={1}>
            <SummaryFooter summary={summary} />
          </Box>
        </Box>
      );
    }
    case "thinking": {
      // dim, collapsible sub-section. Renders with a leading
      // "🧠 Thinking" header so it's visually distinct from the
      // actual reply. When collapsed, just a one-line preview.
      if (effectiveCollapsed) {
        return (
          <Box flexDirection="column" marginY={0} marginLeft={2}>
            <Text dimColor>
              🧠 Thinking · {truncate(turn.text.replace(/\n+/g, " "), turn.previewChars)}  · Tab to expand
            </Text>
          </Box>
        );
      }
      return (
        <Box flexDirection="column" marginY={0} marginLeft={2}>
          <Text dimColor>🧠 Thinking</Text>
          <Box marginLeft={2}>
            <Text dimColor>{turn.text}</Text>
          </Box>
        </Box>
      );
    }
    case "tool":
      // T-6-14: tool calls render collapsed by default —
      // matches the existing `turn.collapsed` default
      // (set to `true` in the reducer on creation). The
      // Tab / Ctrl-E handler toggles `turn.collapsed` to
      // expand. The ToolCard itself reads the `collapsed`
      // prop and renders the appropriate form.
      return (
        <ToolCard
          turn={turn}
          showResult={turn.toolStatus !== "running"}
          collapsed={effectiveCollapsed}
        />
      );
    case "plan":
      // T-6-14: plan cards render collapsed by default
      // (the user clicks them to expand). When the
      // `effectiveCollapsed` flag is set (per-turn
      // collapsed OR the global collapseAll), we render
      // a one-line summary instead of the full PlanList.
      if (effectiveCollapsed) {
        const items = turn.planItems ?? [];
        return (
          <Box flexDirection="column" marginY={0} marginLeft={2}>
            <Text dimColor>
              {icon.plan} {turn.text || "Plan"} · {items.length} item{items.length === 1 ? "" : "s"}  · Tab to expand
            </Text>
          </Box>
        );
      }
      return <PlanList items={turn.planItems ?? []} title={turn.text || "Plan"} />;
    case "system":
      return <Text dimColor>  {ts} · {turn.text}</Text>;
    case "error":
      return <Text color={t.err}>  {ts} ✗ {turn.text}</Text>;
    case "permission":
      // not rendered here — the active permission card lives
      // at the top of the scrollback (rendered before the turns
      // list). Older, resolved permission turns could appear here
      // for a historical view, but the current turn type is
      // unused — kept in the union for future expansion.
      return null;
    default:
      return <Text>{turn.text}</Text>;
  }
};

export const Scrollback: React.FC<Props> = ({
  turns, height, showWelcome, welcomeCtx, collapseAll, onToggleTurn,
  lastStopReason, lastStopKind, lastError,
  showThinking, permissionAsk, decisionCardExpanded,
}) => {
  const start = Math.max(0, turns.length - height);
  // filter out "thinking" turns when the toggle is off. The
  // turn is still in state (so re-toggling brings it back) — we
  // just don't render it. Also drop the (unused) "permission" role
  // — the active ask is rendered separately at the top.
  const visibleAll = turns.slice(start);
  const visible = showThinking
    ? visibleAll.filter((t) => t.role !== "permission")
    : visibleAll.filter((t) => t.role !== "permission" && t.role !== "thinking");
  // render a sticky banner at the top when the last run
  // ended abnormally. The banner is a fixed visual element that
  // doesn't scroll away — the user cannot miss it.
  const banner = renderStopBanner(lastStopKind, lastStopReason);
  // render a dedicated error card if there's a lastError.
  const errorCard = lastError ? renderErrorCard(lastError) : null;
  // render the active permission ask as an INLINE card at
  // the top — not a full-screen modal. The user can scroll back
  // and see the rest of the conversation while making a decision.
  const permCard = permissionAsk ? (
    <PermissionCard ask={permissionAsk} expanded={decisionCardExpanded} />
  ) : null;
  return (
    <Box
      flexDirection="column"
      flexGrow={1}
      paddingX={1}
      borderStyle="single"
      borderColor={banner || errorCard || permCard ? t.err : t.dim}
    >
      {errorCard ? (
        <Box marginY={1}>{errorCard}</Box>
      ) : null}
      {banner ? (
        <Box marginY={1}>{banner}</Box>
      ) : null}
      {permCard ? (
        <Box marginY={1}>{permCard}</Box>
      ) : null}
      {showWelcome ? (
        <Box marginY={1}>
          <Welcome model={welcomeCtx.model} cwd={welcomeCtx.cwd} sessionId={welcomeCtx.sessionId} mode={welcomeCtx.mode} />
        </Box>
      ) : null}
      {visible.length === 0 && !showWelcome ? (
        <Text dimColor>  (empty — type a prompt or /help)</Text>
      ) : (
        // wrap the conversation in a clear left gutter
        // (a vertical dim line) so the user can SEE the row
        // boundaries at a glance. Each turn sits inside the
        // gutter; the gutter itself is rendered as a column
        // of "│" characters next to the turn text. This is
        // much more readable than the old "blank line between
        // turns" approach — the eye now has a continuous
        // visual anchor to follow.
        visible.map((turn, idx) => (
          <Box key={turn.id} flexDirection="row" marginTop={idx === 0 ? 0 : 1}>
            <Text dimColor>│ </Text>
            <Box flexDirection="column" flexGrow={1}>
              <TurnLine turn={turn} effectiveCollapsed={collapseAll || turn.collapsed} />
            </Box>
          </Box>
        ))
      )}
    </Box>
  );
};

function renderStopBanner(
  kind: "ok" | "loop" | "max_turns" | "error" | "empty" | null,
  reason: string | null,
): React.ReactNode {
  if (kind == null || kind === "ok" || kind === "empty") return null;
  const color =
    kind === "loop" || kind === "error" ? t.err :
    kind === "max_turns" ? t.warn :
    t.dim;
  const icon = kind === "loop" ? "■" : kind === "error" ? "✗" : "▲";
  const headline =
    kind === "loop"      ? "Run stopped — loop detected" :
    kind === "max_turns" ? "Run stopped — max turns reached" :
    kind === "error"     ? "Run stopped — error" :
                            "Run stopped";
  const hint =
    kind === "loop"
      ? "The model repeated the same call or hit a long-running output; the daemon stopped it. Type a new prompt to continue."
      : kind === "max_turns"
      ? "The run hit the per-query turn cap. Increase with --max-turns, or narrow the prompt."
      : "Check the daemon log (stderr) for the underlying error. Type a new prompt to continue.";
  return (
    <Box
      flexDirection="column"
      borderStyle="round"
      borderColor={color}
      paddingX={2}
      paddingY={0}
    >
      <Text>
        <Text color={color} bold>{icon} {headline}</Text>
        {reason ? <Text dimColor>  (reason: {reason})</Text> : null}
      </Text>
      <Text dimColor>  {hint}</Text>
    </Box>
  );
}

/** render a dedicated error card. This is shown when the
 *  TUI catches an exception (e.g. an RPC failure) and wants to
 *  surface the message + stack to the user. Distinct from the
 *  prior round stop banner because:
 *   - it shows the actual error message (not a stop reason)
 *   - it shows the stack (if available) for debugging
 *   - it persists until the user submits a new query
 */
function renderErrorCard(err: { message: string; ts: number; stack?: string }): React.ReactNode {
  return (
    <Box
      flexDirection="column"
      borderStyle="round"
      borderColor={t.err}
      paddingX={2}
      paddingY={0}
    >
      <Text>
        <Text color={t.err} bold>✗ Error</Text>
        <Text dimColor>  ·  {new Date(err.ts).toLocaleTimeString()}</Text>
      </Text>
      <Text>  {err.message}</Text>
      {err.stack ? (
        <Text dimColor>  {err.stack.split("\n").slice(0, 3).join("\n  ")}</Text>
      ) : null}
      <Text dimColor>  Type a new prompt to clear.</Text>
    </Box>
  );
}
