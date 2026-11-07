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
 * scrollback (conversation list).
 *
 * R388h — THE TRANSCRIPT IS THE DOCUMENT.
 *
 * The user asked for the TUI to look like the desktop's CENTRE
 * PANEL. That panel is exactly two things
 * (`aethercode-desktop/src/components/ChatView.tsx`):
 *
 *     <MessageList />   ← flex: 1, scrollable, flat
 *     <MessageInput />  ← pinned to the bottom
 *
 * So the target is not "prettier chrome" — it is a DIFFERENT SHAPE:
 * a conversation that reads as a document, with the input as the
 * only framed element on screen.
 *
 * The rules this component now holds:
 *
 * 1. **THE MARKDOWN IS THE UI.** No frame around the transcript, no
 *    `│` gutter on every turn, no per-line timestamp. The
 *    desktop's `.agent-message` is a plain `<div>` with
 *    typography; the assistant's prose IS the surface. A transcript
 *    drawn with a gutter column reads as a LOG, not a conversation.
 *
 * 2. **THE READING COLUMN IS NARROW.** The desktop caps the
 *    transcript at `max-width: 820px` and centres it. A terminal
 *    is 80–200 columns wide; a full-width transcript at 200
 *    columns is unreadable. We cap at 96 columns and centre.
 *
 * 3. **THE USER TURN IS A SPEECH ACT, NOT A LOG ROW.** The desktop
 *    right-aligns the user's bubble. A terminal cannot right-align
 *    reliably, so the user turn gets a distinct accent bar — the
 *    visual equivalent of "that was you".
 *
 * 4. **THE EMPTY STATE IS ONE LINE.** The desktop shows a single
 *    centred italic "Start a conversation…". The TUI used to show
 *    a boxed ASCII wordmark plus a shortcuts grid — a wall of
 *    chrome before the user had said anything.
 *
 * 5. **BANNERS STAY BOXED.** The stop banner and the error card
 *    keep their borders: a run that died silently is the worst
 *    outcome, and those two must be unmissable. They are EXCEPTIONS
 *    to the flat transcript, which is exactly why they read as
 *    exceptions.
 *
 * Each turn dispatches to the appropriate component:
 *   - user:       accent bar + text
 *   - assistant:  Markdown-rendered text (collapsed if long)
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
import { SummaryFooter, splitAssistantMessage, type Summary } from "./chat/SummaryFooter.js";
// R383: the shared stop-kind type — one definition, not one per component.
import type { StopKind } from "../stopKind.js";
// R388m: the permission deadline contract — one countdown, one wording.
import { countdownLine } from "../permissionDeadline.js";
// R388o: the terminal's width, from ONE source.
import { terminalSize } from "../terminalGeometry.js";

/**
 * The reading column's width.
 *
 * 96 columns is the terminal analogue of the desktop's 820px
 * transcript. It fits an 80-column terminal (the TUI's hard floor)
 * with room for the 2-column left inset, and it stops a 200-column
 * terminal from stretching prose into unreadable line lengths.
 */
export const CHAT_COLUMN_WIDTH = 96;

/** the left inset of the whole transcript, in columns. */
const COLUMN_INSET = 2;

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
  /** derived kind of the stop reason. Controls banner colour.
   *  R383: the shared type — the kind is ONE definition, not one per
   *  component. */
  lastStopKind: StopKind;
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
  /** the live terminal width. Used to keep the reading column
   *  centred and never wider than the viewport. */
  terminalWidth?: number;
}

// R388h: `fmtTime` is GONE. The old transcript prefixed every turn
// with a timestamp ("14:32 › your text"). The desktop's centre panel
// has no per-turn clock, and a transcript with a clock on every line
// reads as a LOG rather than a conversation. The time is still
// available where it is useful — the stop banner and the error card.

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
  low: t.ok,
  medium: t.warn,
  high: "yellowBright",
  critical: t.err,
};
const RISK_LABEL: Record<string, string> = {
  low: "LOW",
  medium: "MEDIUM",
  high: "HIGH",
  critical: "CRITICAL",
};

/**
 * A clock that ticks once a second while `active` is true.
 *
 * <p>R388m: the permission card shows a live countdown, and a countdown
 * that only re-renders when something ELSE happens is not a countdown —
 * the user watches a frozen "1:59" for two minutes.
 *
 * <p>The tick is driven by a real interval (not by the render loop), so
 * the number changes on its own. When `active` is false the interval is
 * not running at all — an always-on 1Hz timer in a TUI is a battery
 * drain for a card that is usually not on screen.
 */
function useTick(active: boolean): number {
  const [now, setNow] = React.useState(() => Date.now());
  React.useEffect(() => {
    if (!active) return;
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, [active]);
  return now;
}

/** inline "decision pending" card. Renders inside the
 *  scrollback (so the user can see the rest of the conversation)
 *  rather than as a full-screen modal. The decision keys
 *  A/T/P/U/D/N are handled in the App component's useInput —
 *  this card is presentation only. */
const PermissionCard: React.FC<{ ask: PermissionAsk; expanded: boolean }> = ({ ask, expanded }) => {
  const riskColor = RISK_COLOR[ask.riskLevel] ?? t.warn;
  const riskLabel = RISK_LABEL[ask.riskLevel] ?? ask.riskLevel.toUpperCase();
  // R388m: the countdown. The DEADLINE comes from the daemon (it
  // publishes `expiresAtMs`), so the client's clock and the daemon's
  // clock agree — a client that guessed could deny a call the user was
  // about to approve.
  const deadline =
    ask.expiresAtMs && ask.expiresAtMs > 0
      ? { expiresAtMs: ask.expiresAtMs, timeoutMs: ask.timeoutMs ?? 300_000 }
      : null;
  const now = useTick(deadline !== null);
  const countdown = deadline ? countdownLine(deadline, now) : null;
  if (!expanded) {
    // Collapsed: one-line pill. The user can keep typing in the
    // input box; the A/T/P/U/D/N keys still fire.
    return (
      <Box marginY={0} paddingX={1} borderStyle="single" borderColor={riskColor}>
        <Text>
          <Text color={riskColor} bold>
            {icon.warn} decision pending
          </Text>
          <Text dimColor> · </Text>
          <Text bold>{ask.tool}</Text>
          <Text dimColor> · [{riskLabel}]</Text>
          {countdown ? (
            <>
              <Text dimColor> · </Text>
              <Text color={t.warn}>{countdown}</Text>
            </>
          ) : null}
          <Text dimColor> · A=allow T=task P=project U=user M=auto D=deny N=never</Text>
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
        <Text color={riskColor} bold>
          {icon.warn} PERMISSION REQUIRED
        </Text>
        <Text dimColor> · [{riskLabel}] risk</Text>
        {countdown ? (
          <>
            <Text dimColor> · </Text>
            <Text color={t.warn} bold>
              {countdown}
            </Text>
          </>
        ) : null}
      </Text>
      <Text> </Text>
      <Text>
        <Text>tool: </Text>
        <Text bold color={t.brand}>
          {ask.tool}
        </Text>
      </Text>
      <Text>
        <Text>input: </Text>
        <Text>{formatAskInput(ask.input)}</Text>
      </Text>
      <Text>
        <Text>reason: </Text>
        <Text dimColor>{ask.reason}</Text>
      </Text>
      <Text> </Text>
      <Text dimColor> choose an option — these keys work even while the input box is focused:</Text>
      <Text>
        {" "}
        <Text color={t.ok} bold>
          [A]
        </Text>{" "}
        allow this call once
      </Text>
      <Text>
        {" "}
        <Text color={t.ok} bold>
          [T]
        </Text>{" "}
        allow for this task (rest of this sub-task)
      </Text>
      <Text>
        {" "}
        <Text color={t.ok} bold>
          [P]
        </Text>{" "}
        allow for this project (saved to .aethercode/permissions.json)
      </Text>
      <Text>
        {" "}
        <Text color={t.ok} bold>
          [U]
        </Text>{" "}
        allow for this user (saved to ~/.aethercode/.../permissions.json)
      </Text>
      {/* R388n: "Yes, and switch to auto mode". Claude Code offers this
          as ONE option: the call is approved AND the session stops
          asking for this kind of call. Two separate presses (approve,
          then /mode) is the same outcome — but the user has to know
          that the second one exists, and has to remember it.

          The label names the MODE it switches to, not just "auto" —
          the mode's own name is the daemon's word, and the user can
          change it afterwards with /mode. */}
      <Text>
        {" "}
        <Text color={t.ok} bold>
          [M]
        </Text>{" "}
        allow and switch to auto mode (ACCEPT_TASK)
      </Text>
      <Text>
        {" "}
        <Text color={t.err} bold>
          [D]
        </Text>{" "}
        deny this call once
      </Text>
      <Text>
        {" "}
        <Text color={t.err} bold>
          [N]
        </Text>{" "}
        deny and remember for this tool
      </Text>
    </Box>
  );
};

const TurnLine: React.FC<{ turn: Turn; effectiveCollapsed: boolean }> = ({
  turn,
  effectiveCollapsed,
}) => {
  switch (turn.role) {
    case "user":
      // R388h: an accent bar, not a timestamp + marker. The bar is
      // the terminal's equivalent of the desktop's right-aligned
      // user bubble: it says "this was you" without needing an
      // alignment a terminal cannot guarantee.
      return (
        <Box flexDirection="row">
          <Text color={t.user} bold>
            {"▌ "}
          </Text>
          <Box flexDirection="column" flexGrow={1}>
            {turn.text.split("\n").map((line, i) => (
              <Text key={i}>{line}</Text>
            ))}
          </Box>
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
          <Box flexDirection="column">
            <Text dimColor>Assistant</Text>
            <Text dimColor>
              {truncate(lead.replace(/\n+/g, " "), turn.previewChars)} · Tab to expand
            </Text>
            {/* T-6-14: summary footer is ALWAYS visible,
                even when the rest of the turn is collapsed. */}
            <SummaryFooter summary={summary} />
          </Box>
        );
      }
      return (
        <Box flexDirection="column">
          <Text dimColor>Assistant</Text>
          {lead ? <Markdown text={lead} /> : <Text dimColor>…</Text>}
          {/* T-6-14: summary footer rendered as the LAST
              line of the assistant message; never collapsed. */}
          <SummaryFooter summary={summary} />
        </Box>
      );
    }
    case "thinking": {
      // dim, collapsible sub-section. Renders with a leading
      // "🧠 Thinking" header so it's visually distinct from the
      // actual reply. When collapsed, just a one-line preview.
      if (effectiveCollapsed) {
        return (
          <Text dimColor>
            Thinking · {truncate(turn.text.replace(/\n+/g, " "), turn.previewChars)} · Tab to expand
          </Text>
        );
      }
      return (
        <Box flexDirection="column">
          <Text dimColor>Thinking</Text>
          <Text dimColor>{turn.text}</Text>
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
          <Text dimColor>
            {icon.plan} {turn.text || "Plan"} · {items.length} item{items.length === 1 ? "" : "s"} ·
            Tab to expand
          </Text>
        );
      }
      return <PlanList items={turn.planItems ?? []} title={turn.text || "Plan"} />;
    case "system":
      return <Text dimColor>{turn.text}</Text>;
    case "error":
      return (
        <Text color={t.err}>
          {icon.err} {turn.text}
        </Text>
      );
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

/**
 * The empty state — ONE centred italic line.
 *
 * R388h: this replaces the boxed ASCII wordmark + shortcuts grid.
 * The desktop's `.message-list-empty` is a single line, and the
 * user's complaint ("我觉得并不好看") was aimed at exactly that
 * wall of chrome. The model's name and the cwd ride along on a
 * second, dimmer line — context, not decoration.
 */
const EmptyState: React.FC<{ model: string; cwd: string; width: number }> = ({
  model,
  cwd,
  width,
}) => {
  const tail = cwd.split(/[\\/]/).filter(Boolean).slice(-2).join("/");
  // R388h: Ink's `alignItems="center"` only centres a child when the
  // parent has a BOUNDED width — and inside a `flexDirection="column"`
  // box it centres each child HORIZONTALLY, which left the two lines
  // ragged against each other. We centre the text ourselves: pad the
  // line so its middle lands in the middle of the reading column.
  // Terminal text cannot be aligned by a flexbox, so the offset is
  // computed from the string length.
  const centre = (line: string): string => {
    const pad = Math.max(0, Math.floor((width - line.length) / 2));
    return " ".repeat(pad) + line;
  };
  return (
    <Box flexDirection="column" justifyContent="center" flexGrow={1}>
      <Text italic dimColor>
        {centre("Start a conversation…")}
      </Text>
      <Text dimColor>{centre(`${model}${tail ? ` · ${tail}` : ""}`)}</Text>
    </Box>
  );
};

export const Scrollback: React.FC<Props> = ({
  turns,
  height,
  showWelcome,
  welcomeCtx,
  collapseAll,
  onToggleTurn,
  lastStopReason,
  lastStopKind,
  lastError,
  showThinking,
  permissionAsk,
  decisionCardExpanded,
  terminalWidth,
}) => {
  // R388o: `height` is a ROW budget now, not a turn count. The
  // slice below keeps the LAST `height` TURNS as a first approximation
  // (a turn is at least one row), and the rendered column is then
  // clipped to the row budget by `overflow="hidden"`.
  //
  // ★ THE SLICE IS A BOUND, NOT A TRUNCATION ★ Rendering a
  // thousand turns and clipping the RESULT is what made the frame
  // enormous: Yoga laid out every turn, and only the visible ones
  // survived. The slice keeps the layout cost proportional to what
  // the user can see.
  //
  // ★ THE SLICE IS AN APPROXIMATION, AND THAT IS FINE ★ A turn is
  // not a row — a turn can be one row or forty. The slice therefore
  // keeps MORE turns than the budget has rows, and the surplus is
  // clipped by `overflow="hidden"` on the column below. What the slice
  // must guarantee is only that the LAYOUT COST is bounded: without it
  // Yoga lays out every turn ever written.
  //
  // The frame's height is not this component's job. It is the flex
  // chain's (see the `flexBasis={0}` note on the column below).
  const maxRows = Math.max(1, height);
  const start = Math.max(0, turns.length - maxRows);
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

  // R388h: the reading column. Never wider than the viewport, never
  // wider than CHAT_COLUMN_WIDTH, and always centred — the desktop
  // centres its 820px transcript the same way.
  // R388o: the width comes from ONE source (`terminalGeometry`).
  // This used to guess 120 when the terminal did not say — and 120 is
  // wider than most terminals, so the "safe" fallback was the one
  // that produced the overflow.
  const cols = terminalWidth ?? terminalSize().cols;
  const columnWidth = Math.max(24, Math.min(CHAT_COLUMN_WIDTH, cols - COLUMN_INSET * 2));
  const leftPad = Math.max(0, Math.floor((cols - columnWidth) / 2));

  // R388o: the transcript is BOUNDED BY ROWS, not by turns.
  //
  // `height` used to be 1000 — a turn COUNT, used as if it were a
  // row count. A single turn can be forty rows of markdown, so a
  // thousand turns is tens of thousands of rows: the frame grew
  // without bound, the frame outgrew the terminal, and Ink switched
  // to its `clearTerminal` path. On a console that ignores `ESC[2J`
  // every render then APPENDED a frame — which is the stack of
  // repeated headers the user saw.
  //
  // The transcript gets the rows the CHROME leaves. The caller
  // computes that from the terminal's height.

  // Nothing to show at all: the centred empty state, and nothing
  // else. No box, no banner row, no gutter.
  if (visible.length === 0 && !banner && !errorCard && !permCard) {
    return (
      <Box flexDirection="column" flexGrow={1} paddingLeft={leftPad} paddingRight={leftPad}>
        <EmptyState model={welcomeCtx.model} cwd={welcomeCtx.cwd} width={columnWidth} />
      </Box>
    );
  }

  // R388o: THE TRANSCRIPT IS BOUNDED BY ROWS.
  //
  // The column is `flexGrow={1}` with `overflow="hidden"`: it takes
  // the rows the chrome leaves and clips whatever does not fit. That
  // is what makes the frame's height a function of the TERMINAL, not
  // of the conversation's length.
  //
  // ★ AND THE BASIS MUST BE ZERO ★
  //
  // `flexGrow={1}` alone is NOT enough. Yoga gives a flex item a BASE
  // SIZE equal to its CONTENT, and it only shrinks when the container
  // overflows — so a transcript whose content is taller than the
  // terminal does not clip itself: it takes the rows and SQUEEZES the
  // chrome.
  //
  // Measured (a Yoga tree dump, 100×30, a 12-turn transcript): the
  // header's outer box computed to ONE row — the brand row and the cwd
  // caption both computed to height 0 — and the input pill showed only
  // its top border. The user saw a bare rule where the header should
  // be, and a half-drawn input box.
  //
  // `flexBasis={0}` makes the base size zero, so the transcript grows
  // INTO the rows the chrome leaves. `minHeight={0}` lets it shrink
  // below its content, which is what makes `overflow="hidden"` clip
  // rather than push.
  //
  // ★ A FIXED `height` ON THE CONTENT COLUMN IS A CONTRADICTION ★
  // The column is a flex child; a fixed height inside a growing
  // column claims rows the sibling chrome also needs, and the chrome
  // is pushed off the bottom of the frame. Measured: with a fixed
  // Scrollback height of 20 in a 30-row terminal the input pill and
  // the status row vanished; without it they sit at the bottom,
  // exactly where they belong.
  //
  // So the height is NOT set here. The clipping comes from
  // `overflow="hidden"` on the column, which is what the flex
  // layout already sized.
  //
  // `overflow="hidden"` keeps the MOST RECENT content visible: the
  // transcript is laid out top-down and the clip takes the bottom.
  // The newest turn is at the bottom, so the newest turn survives.

  return (
    <Box
      flexDirection="column"
      flexGrow={1}
      flexBasis={0}
      minHeight={0}
      overflow="hidden"
      paddingLeft={leftPad}
      paddingRight={leftPad}
    >
      <Box flexDirection="column" width={columnWidth} overflow="hidden">
        {errorCard ? <Box marginBottom={1}>{errorCard}</Box> : null}
        {banner ? <Box marginBottom={1}>{banner}</Box> : null}
        {permCard ? <Box marginBottom={1}>{permCard}</Box> : null}
        {visible.map((turn, idx) => (
          <Box key={turn.id} flexDirection="column" marginTop={idx === 0 ? 0 : 1}>
            <TurnLine turn={turn} effectiveCollapsed={collapseAll || turn.collapsed} />
          </Box>
        ))}
      </Box>
    </Box>
  );
};

function renderStopBanner(kind: StopKind, reason: string | null): React.ReactNode {
  if (kind == null || kind === "ok" || kind === "empty") return null;
  const color = kind === "loop" || kind === "error" ? t.err : kind === "max_turns" ? t.warn : t.dim;
  const icon = kind === "loop" ? "■" : kind === "error" ? "✗" : "▲";
  const headline =
    kind === "loop"
      ? "Run stopped — loop detected"
      : kind === "max_turns"
        ? "Run stopped — max turns reached"
        : kind === "error"
          ? "Run stopped — error"
          : "Run stopped";
  const hint =
    kind === "loop"
      ? "The model repeated the same call or hit a long-running output; the daemon stopped it. Type a new prompt to continue."
      : kind === "max_turns"
        ? "The run hit the per-query turn cap. Increase with --max-turns, or narrow the prompt."
        : "Check the daemon log (stderr) for the underlying error. Type a new prompt to continue.";
  return (
    <Box flexDirection="column" borderStyle="round" borderColor={color} paddingX={2} paddingY={0}>
      <Text>
        <Text color={color} bold>
          {icon} {headline}
        </Text>
        {reason ? <Text dimColor> (reason: {reason})</Text> : null}
      </Text>
      <Text dimColor> {hint}</Text>
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
    <Box flexDirection="column" borderStyle="round" borderColor={t.err} paddingX={2} paddingY={0}>
      <Text>
        <Text color={t.err} bold>
          ✗ Error
        </Text>
        <Text dimColor> · {new Date(err.ts).toLocaleTimeString()}</Text>
      </Text>
      <Text> {err.message}</Text>
      {err.stack ? <Text dimColor> {err.stack.split("\n").slice(0, 3).join("\n  ")}</Text> : null}
      <Text dimColor> Type a new prompt to clear.</Text>
    </Box>
  );
}
