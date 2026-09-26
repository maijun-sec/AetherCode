/**
 * welcome banner — R344 redesign.
 *
 * Claude Code-inspired first impression: a bold ASCII wordmark, a
 * dense single-row "context strip" (provider · model · mode · session
 * · cwd · time), a shortcuts grid (R343), and one "tip of the day".
 *
 * Visual rules:
 *  - the wordmark is the only bold art in the TUI; everything else
 *    is single-line and dim
 *  - pills (provider / model / mode / conn) use a 1-row badge with
 *    an icon + label + optional accent color
 *  - the shortcut grid is dense (2-column flex) so it doesn't eat
 *    the scrollback area
 *  - tips rotate based on context (subagent availability, memory
 *    bank status, daemon mode)
 *
 * Layout:
 *
 *  ╭─────────────────────────────────────────────────────╮
 *  │  ⌬  AETHERCODE  v0.2.71  ·  Type a prompt to start  │
 *  │                                                      │
 *  │  ⌬ model: MiniMax-M3   ⚙ mode: DEFAULT             │
 *  │  ⌃ provider: MiniMax   # session: dcffbb65          │
 *  │  ⏱  uptime: 2m 14s    ↳ cwd: ~/work/proj            │
 *  │                                                      │
 *  │  shortcuts:  Tab accept   Ctrl-B sidebar            │
 *  │              / commands  @ files                    │
 *  │              Ctrl-? help  Ctrl-L logs               │
 *  │                                                      │
 *  │  ⚡ Tip: /demo runs a pre-canned conversation so you │
 *  │  can see the TUI's full visual range in one shot.    │
 *  ╰─────────────────────────────────────────────────────╯
 */

import React from "react";
import { Box, Text } from "ink";
import { t, icon, wordmark, banner } from "../theme.js";

interface Props {
  model: string;
  cwd: string;
  sessionId: string;
  /** current permission mode (e.g. DEFAULT, ACCEPT_TASK). */
  mode?: string;
  /** provider name (e.g. minmax, openai). optional; older
   *  daemons don't report it. */
  provider?: string;
  /** daemon uptime in milliseconds; rendered as 1m 14s etc. */
  uptimeMs?: number;
  /** token counters for the welcome card. `null` until the first
   *  turn finishes. rendered as `—`. */
  inputTokens?: number | null;
  outputTokens?: number | null;
  costUsd?: number | null;
  /** R245.5: one-line bank status (e.g. "12 units, 8 ok / 1 notOk")
   *  or "down". `undefined` = fetch in flight. */
  bankStatus?: string;
  /** subagents supported by the daemon. */
  subagentsAvailable?: boolean;
}

/** static shortcut catalog. The order is deliberate: surface the
 *  ones a new user needs in the first 30 seconds (Tab accept, / ,
 *  @) before the power-user ones (Ctrl-B sidebar, Ctrl-? help). */
const SHORTCUTS: ReadonlyArray<{ key: string; label: string }> = [
  { key: "Tab",     label: "accept autocomplete" },
  { key: "Enter",   label: "send prompt" },
  { key: "↑ / ↓",   label: "input history" },
  { key: "/",       label: "slash commands" },
  { key: "@",       label: "@-mention files" },
  { key: "Ctrl-B",  label: "toggle sidebar" },
  { key: "Ctrl-D",  label: "toggle right panel" },
  { key: "Ctrl-F",  label: "search scrollback" },
  { key: "Ctrl-L",  label: "log viewer" },
  { key: "Ctrl-S",  label: "subagent panel" },
  { key: "Ctrl-T",  label: "theme picker" },
  { key: "Ctrl-?",  label: "full help" },
];

/** context-sensitive tips. The first matching one wins; the
 *  fallback "no-tip" hint is shown when none match. Tips are
 *  short so the welcome doesn't eat vertical space. */
const TIPS: ReadonlyArray<{ when: (p: Props) => boolean; text: string }> = [
  {
    when: (p) => p.subagentsAvailable === true,
    text: "subagents can run in the background — press Ctrl-S to see live jobs, or /spawn <intent> to launch one inline.",
  },
  {
    when: (p) => (p.bankStatus ?? "").startsWith("down"),
    text: "memory bank is unreachable — /memory show --project to inspect the local cache; daemon should self-recover in a few seconds.",
  },
  {
    when: (p) => (p.bankStatus ?? "").includes("0 units"),
    text: "memory bank is empty — every successful tool call seeds a unit automatically; /bank-stats shows the live count.",
  },
  {
    when: (p) => p.mode?.toUpperCase() === "BYPASS_PERMISSIONS",
    text: "you're in BYPASS mode — every tool call runs without confirmation. /mode DEFAULT to require explicit consent.",
  },
  {
    when: (p) => p.mode?.toUpperCase() === "PLAN",
    text: "you're in PLAN mode — the model proposes tool calls but doesn't run them until you accept. type a prompt or /plan to proceed.",
  },
  {
    // fallback
    when: () => true,
    text: "/demo runs a pre-canned conversation so you can see the TUI's full visual range in one shot.",
  },
];

/** render an icon + label pill. Used in the context strip. */
const Pill: React.FC<{ glyph: string; label: string; color?: string; dim?: boolean }> = ({
  glyph, label, color, dim,
}) => (
  <Text>
    <Text color={color ?? t.accent}>{glyph} </Text>
    <Text dimColor={dim}>{label}</Text>
  </Text>
);

/** small connector between pills (a dim `·`). */
const Sep: React.FC = () => <Text dimColor>  ·  </Text>;

/** format a milliseconds duration as `1m 23s` or `12s`. */
function formatUptime(ms: number | undefined): string {
  if (ms == null || !Number.isFinite(ms) || ms < 0) return "—";
  const s = Math.floor(ms / 1000);
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  const rs = s % 60;
  return `${m}m ${rs.toString().padStart(2, "0")}s`;
}

/** format tokens with k/M suffix. */
function fmtTok(n: number | null | undefined): string {
  if (n == null || !Number.isFinite(n)) return "—";
  if (n < 1000) return String(Math.round(n));
  if (n < 1_000_000) return `${(n / 1000).toFixed(1)}k`;
  return `${(n / 1_000_000).toFixed(2)}M`;
}

/** format cost. */
function fmtCost(usd: number | null | undefined): string {
  if (usd == null || !Number.isFinite(usd)) return "—";
  if (usd < 0.01) return "<$0.01";
  return `$${usd.toFixed(2)}`;
}

export const Welcome: React.FC<Props> = ({
  model, cwd, sessionId, mode, provider, uptimeMs,
  inputTokens, outputTokens, costUsd, bankStatus, subagentsAvailable,
}) => {
  const tip = (TIPS.find((t) => t.when({
    model, cwd, sessionId, mode, provider, uptimeMs,
    inputTokens, outputTokens, costUsd, bankStatus, subagentsAvailable,
  })) ?? TIPS[TIPS.length - 1]).text;

  // session short form: first 8 chars of the id. Falls back to
  // em-dash when the daemon hasn't reported a session yet.
  const shortSession = !sessionId || sessionId === "—" ? "—" : sessionId.slice(0, 8);
  // cwd short form: last 2 segments, capped to 28 chars.
  const shortCwd = (() => {
    const parts = cwd.split(/[\\/]/);
    if (parts.length <= 2) return cwd;
    const tail = parts.slice(-2).join("/");
    return tail.length > 28 ? `.../${tail.slice(-26)}` : `.../${tail}`;
  })();

  return (
    <Box
      flexDirection="column"
      borderStyle="round"
      borderColor={t.brand}
      paddingX={2}
      paddingY={1}
    >
      {/* wordmark row. The banner ASCII is shown when stdout is wide
       *  enough (>= 100 cols); otherwise fall back to the bold
       *  wordmark + version. The renderer's flex makes both work
       *  in a constrained viewport. */}
      {banner ? (
        <Text color={t.brand}>{banner}</Text>
      ) : (
        <Text>
          <Text color={t.brand} bold>{icon.model} {wordmark}</Text>
          <Text> v0.2.71</Text>
          <Text dimColor>  ·  type a prompt and press Enter</Text>
        </Text>
      )}

      {/* context strip — single dense row with all the live state.
       *  Order: provider → model → mode → session → uptime → cwd.
       *  Provider is optional (omitted when unknown). */}
      <Box marginTop={1} flexWrap="wrap">
        {provider ? (
          <>
            <Pill glyph={icon.provider} label={provider} color={t.brand} />
            <Sep />
          </>
        ) : null}
        <Pill glyph={icon.model} label={model} color={t.accent} />
        <Sep />
        <Pill glyph={icon.mode} label={mode ?? "DEFAULT"} />
        <Sep />
        <Pill glyph={icon.id} label={shortSession} dim />
        <Sep />
        <Pill glyph={icon.time} label={formatUptime(uptimeMs)} dim />
        <Sep />
        <Pill glyph={icon.path} label={shortCwd} dim />
      </Box>

      {/* live counters — input / output tokens + cost. Compact,
       *  one line. When counters are unknown (no run yet), show
       *  the em-dash placeholder so the row stays balanced. */}
      {(inputTokens != null || outputTokens != null || costUsd != null) ? (
        <Box marginTop={0}>
          <Pill glyph={icon.in}  label={`${fmtTok(inputTokens)} in`}  dim />
          <Sep />
          <Pill glyph={icon.out} label={`${fmtTok(outputTokens)} out`} dim />
          <Sep />
          <Pill glyph={icon.cost} label={fmtCost(costUsd)} dim />
        </Box>
      ) : null}

      {/* bank status — only rendered when reported by the daemon
       *  (R245.5). We don't gate on `undefined` differently because
       *  the fetch is fast; an empty placeholder line would be
       *  more distracting than helpful. */}
      {bankStatus ? (
        <Box marginTop={0}>
          <Pill glyph={icon.bank} label={bankStatus} dim />
        </Box>
      ) : null}

      {/* shortcut grid — 2 columns, dim. The user can scan the
       *  catalog in 2 seconds without opening HelpOverlay. */}
      <Box marginTop={1} flexDirection="column">
        <Text dimColor>shortcuts:</Text>
        <Box flexWrap="wrap" marginTop={0}>
          {SHORTCUTS.map((s, i) => (
            <Box key={s.key} marginRight={3} marginBottom={0}>
              <Text color={t.accent}>{s.key.padEnd(7)}</Text>
              <Text dimColor>{s.label}</Text>
            </Box>
          ))}
        </Box>
      </Box>

      {/* tip of the day — context-sensitive one-liner. Italicised
       *  visually via dim + an icon prefix. The full prompt syntax
       *  (e.g. /demo, /mode) stays in the foreground color so the
       *  user can copy-paste it. */}
      <Box marginTop={1}>
        <Text color={t.warn}>{icon.warn} </Text>
        <Text dimColor>tip: </Text>
        <Text>{tip}</Text>
      </Box>
    </Box>
  );
};