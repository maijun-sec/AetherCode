// T-080 ~ T-085 (Phase 1 §1.8, design.md §1): the TUI
// memory panel. Renders the three memory layers
// (Global / Project / Session) side-by-side as tabs.
//
// Visual model:
//
//   ┌─ Memory ──────────────────────────────────┐
//   │  [Global] Project  Session                 │
//   │ ────────────────────────────────────────  │
//   │  fact   user.name  = …                     │
//   │  rule   always run npm test                │
//   │  bread  2026-08-28 — cwd: … → …            │
//   │  …                                        │
//   │ ────────────────────────────────────────  │
//   │  12 entries  ~3.4K tokens  [c] Compact now │
//   └───────────────────────────────────────────┘
//
// Wiring (the App owns the RPC plumbing):
//   - on mount: App calls `memory/list` (T-075) for
//     each scope and pipes the results into the
//     `entries` prop.
//   - on tab switch: App re-fetches the new scope.
//   - on double-click (Enter) of a row: panel calls
//     `onEditFact(entry)` and the App opens the edit
//     dialog (T-083) — see design.md §1.5 "lets the
//     user edit each".
//   - on "c" key or "Compact now" button: App fires
//     `memory/compact` (T-073) with `force: true` and
//     pipes the result back via `onCompactDone`.
//
// Read-only by default (T-082). Edit goes through
// the App's modal dialog, NOT by mutating the list in
// place — this keeps the markdown files the single
// source of truth and avoids dual-write hazards.

import React, { useEffect, useMemo, useState } from "react";
import { Box, Text, useInput } from "ink";
import { t, icon, formatTokens, formatRelative } from "../theme.js";

/* ----------------------------- types --------------------------------- */

/** The three memory scopes (mirrors `MemoryScope` in aethercode-memory). */
export type MemoryScope = "global" | "project" | "session";

/** A flat row the panel renders. The App is responsible
 *  for converting the module's `MemoryEntry` union into
 *  this shape. Keeps the panel testable without a
 *  dependency on the memory module. */
export interface MemoryPanelEntry {
  readonly id: string;
  readonly kind: "fact" | "rule" | "change" | "breadcrumb";
  readonly key?: string;
  readonly value: string;
  readonly ts: number;
  readonly source: string;
  readonly tags: ReadonlyArray<string>;
}

export interface MemoryPanelState {
  readonly entries: ReadonlyArray<MemoryPanelEntry>;
  readonly totalTokens: number;
  readonly source?: "cache" | "sqlite" | "file";
  readonly loading: boolean;
  readonly error?: string | null;
}

export type CompactProgress =
  | { state: "idle" }
  | { state: "running"; startedAt: number }
  | { state: "ok"; beforeTokens: number; afterTokens: number; changesCompressed: number; ms: number }
  | { state: "skipped"; reason: string }
  | { state: "error"; message: string };

export interface MemoryPanelProps {
  /** Per-scope state. The App updates this as the
   *  `memory/list` results come back. */
  global: MemoryPanelState;
  project: MemoryPanelState;
  /** Session id currently bound to the active engine.
   *  The panel uses it as the scope=session query key
   *  and in the tab label. */
  sessionId: string;
  session: MemoryPanelState;
  /** Currently active tab. The App may manage this
   *  itself (e.g. open directly on Project) or let the
   *  panel own it. */
  activeTab?: MemoryScope;
  onTabChange?: (scope: MemoryScope) => void;
  /** Wall-clock for live relative timestamps. */
  now: number;
  /** Panel column width in characters. */
  width: number;
  /** App wiring — fired when the user double-clicks
   *  (Enter) a row. */
  onEditFact?: (scope: MemoryScope, entry: MemoryPanelEntry) => void;
  /** App wiring — fired when the user presses "c" or
   *  the "Compact now" button. The App performs the
   *  `memory/compact` RPC and pipes the outcome back
   *  via `compactProgress` so the panel can render a
   *  spinner / result. */
  onCompact?: () => void;
  /** Live progress of the last `memory/compact` call. */
  compactProgress: CompactProgress;
  /** Esc closes the panel. */
  onClose?: () => void;
}

/* ----------------------------- constants ----------------------------- */

const TAB_LABELS: Record<MemoryScope, string> = {
  global: "Global",
  project: "Project",
  session: "Session",
};

const KIND_ICON: Record<MemoryPanelEntry["kind"], string> = {
  fact: "·",
  rule: "▸",
  change: "✎",
  breadcrumb: "↳",
};

const KIND_COLOR: Record<MemoryPanelEntry["kind"], string> = {
  fact: t.asst,
  rule: t.brand,
  change: t.accent,
  breadcrumb: t.dim,
};

const TAB_ORDER: ReadonlyArray<MemoryScope> = ["global", "project", "session"];

/* ----------------------------- helpers ------------------------------- */

function truncate(s: string, max: number): string {
  if (s == null) return "";
  if (s.length <= max) return s;
  if (max <= 1) return s.slice(0, max);
  return s.slice(0, max - 1) + "…";
}

function pickValue(e: MemoryPanelEntry): string {
  if (e.kind === "fact") {
    return e.key ? `${e.key} = ${e.value}` : e.value;
  }
  if (e.kind === "rule") return e.value;
  if (e.kind === "change") return e.value;
  return e.value;
}

/** Visible row for an entry. Returns the rendered string
 *  truncated to `width` characters. The kind label,
 *  source badge and timestamp are added by the caller. */
function rowText(e: MemoryPanelEntry, width: number): string {
  return truncate(pickValue(e), Math.max(8, width));
}

/* --------------------------- main component -------------------------- */

/**
 * The memory panel. Pure presentational; the App owns
 * the RPC wiring. Tabs are keyboard-switchable
 * (←/→ / Tab), focus is keyboard-driven
 * (↑/↓ / j / k), and Enter (or "e") opens the edit
 * dialog. "c" fires the "Compact now" action.
 */
export function MemoryPanel(props: MemoryPanelProps) {
  const {
    global, project, sessionId, session,
    activeTab, onTabChange,
    now, width,
    onEditFact, onCompact, compactProgress,
    onClose,
  } = props;

  /* The App may drive the active tab; if not, the panel
   * owns it via local state. The local copy is
   * initialised to whatever the App handed us so the
   * first paint matches the desired default. */
  const [localTab, setLocalTab] = useState<MemoryScope>(activeTab ?? "project");
  const effectiveTab: MemoryScope = activeTab ?? localTab;

  /* Local row focus (mirrors the TaskPanel pattern). */
  const [focusId, setFocusId] = useState<string | null>(null);

  /* Pick the right state slice for the active tab. */
  const current: MemoryPanelState = useMemo(() => {
    if (effectiveTab === "global") return global;
    if (effectiveTab === "project") return project;
    return session;
  }, [effectiveTab, global, project, session]);

  /* Reset focus when the tab changes so the first row
   * is selected by default. */
  useEffect(() => {
    setFocusId(null);
  }, [effectiveTab]);

  /* Keyboard wiring. Lives inside the component so the
   * rest of the App's input map is unaffected (the
   * App gates this hook on `memoryPanelOpen`). */
  useInput((input, key) => {
    if (key.escape) {
      if (onClose) onClose();
      return;
    }
    if (key.leftArrow || key.rightArrow || key.tab) {
      const idx = TAB_ORDER.indexOf(effectiveTab);
      const delta = key.leftArrow || (key.tab && key.shift) ? -1 : 1;
      const next = TAB_ORDER[(idx + delta + TAB_ORDER.length) % TAB_ORDER.length];
      if (next !== undefined) {
        if (onTabChange) onTabChange(next);
        else setLocalTab(next);
      }
      return;
    }
    if (input === "1") {
      if (onTabChange) onTabChange("global");
      else setLocalTab("global");
      return;
    }
    if (input === "2") {
      if (onTabChange) onTabChange("project");
      else setLocalTab("project");
      return;
    }
    if (input === "3") {
      if (onTabChange) onTabChange("session");
      else setLocalTab("session");
      return;
    }
    /* Compact shortcut (T-084) — only meaningful on the
     * Project tab; the LLM pass only operates on
     * project memory. The button is still rendered
     * everywhere so the affordance is discoverable, but
     * the keyboard shortcut is gated. */
    if (input === "c" && effectiveTab === "project" && compactProgress.state === "idle" && onCompact) {
      onCompact();
      return;
    }
    /* Edit shortcut (T-083) — Enter or "e" on the
     * focused row. */
    if ((key.return || input === "e") && onEditFact && focusId !== null) {
      const entry = current.entries.find((e) => e.id === focusId);
      if (entry) onEditFact(effectiveTab, entry);
      return;
    }
    /* Row navigation. ↑/↓ and j/k both work. */
    if (key.upArrow || input === "k") {
      moveFocus(current.entries, focusId, -1, setFocusId);
      return;
    }
    if (key.downArrow || input === "j") {
      moveFocus(current.entries, focusId, 1, setFocusId);
      return;
    }
  });

  /* Effective focus falls back to the first row. */
  const effectiveFocus = focusId ?? current.entries[0]?.id ?? null;

  return (
    <Box
      flexDirection="column"
      width={width}
      borderStyle="single"
      borderColor={t.dim}
      paddingX={1}
    >
      {/* Title + tab bar (T-081) */}
      <Box>
        <Text bold color={t.brand}>{"◰ "}Memory</Text>
        <Text dimColor>{`  (${current.entries.length} entries, `}</Text>
        <Text dimColor>{formatTokens(current.totalTokens)}</Text>
        <Text dimColor>{` tokens, `}</Text>
        <Text dimColor>{current.source ?? "—"}</Text>
        <Text dimColor>{` — ↑/↓ select, Enter edit, c compact, Esc close)`}</Text>
      </Box>
      <Box>
        {TAB_ORDER.map((scope, idx) => {
          const active = scope === effectiveTab;
          const label = `${idx + 1}.${TAB_LABELS[scope]}`;
          const count = scope === "global" ? global.entries.length
                      : scope === "project" ? project.entries.length
                      : session.entries.length;
          return (
            <Box key={scope} marginRight={1}>
              <Text
                bold={active}
                underline={active}
                color={active ? t.accent : t.dim}
                inverse={active}
              >
                {active ? ` ${label} (${count}) ` : ` ${label} (${count}) `}
              </Text>
            </Box>
          );
        })}
      </Box>

      {/* Body (T-082) — read-only list view. */}
      {current.loading ? (
        <Text dimColor>{`  (loading ${effectiveTab} memory…)`}</Text>
      ) : null}
      {current.error ? (
        <Text color={t.err}>{`  error: ${current.error}`}</Text>
      ) : null}
      {!current.loading && !current.error && current.entries.length === 0 ? (
        <Text dimColor>{`  (no ${effectiveTab} memory yet — ${
          effectiveTab === "global" ? "edit ~/.aethercode/memory.md"
          : effectiveTab === "project" ? "edit .aethercode/memory.md in cwd"
          : `the session ${sessionId} has no facts yet`
        })`}</Text>
      ) : null}
      {current.entries.map((e) => {
        const focused = e.id === effectiveFocus;
        const kindColor = KIND_COLOR[e.kind];
        const kindIcon = KIND_ICON[e.kind];
        const lineWidth = Math.max(8, width - 12);
        return (
          <Box key={e.id} flexDirection="column">
            <Box>
              <Text inverse={focused} color={focused ? "black" : kindColor}>
                {focused ? "▶" : " "} {kindIcon} {e.kind.padEnd(10, " ")}
              </Text>
              <Text {...(focused ? { inverse: true } : {})}>
                {rowText(e, lineWidth)}
              </Text>
            </Box>
            {focused ? (
              <Text dimColor>{`   ↳ ${e.source} · ${formatRelative(e.ts, now)} · id:${e.id}`}</Text>
            ) : null}
          </Box>
        );
      })}

      {/* Footer with "Compact now" button (T-084). */}
      <Box marginTop={1}>
        <CompactFooter
          scope={effectiveTab}
          progress={compactProgress}
          onCompact={onCompact}
        />
      </Box>
    </Box>
  );
}

/* --------------------------- subcomponents --------------------------- */

interface CompactFooterProps {
  scope: MemoryScope;
  progress: CompactProgress;
  onCompact?: () => void;
}

/** Renders the bottom bar of the panel. The actual
 *  "Compact now" button + progress live here so the
 *  body of the main component stays scannable. */
function CompactFooter({ scope, progress, onCompact }: CompactFooterProps) {
  const canCompact = scope === "project";
  const running = progress.state === "running";
  const label = running ? "Compacting…"
              : progress.state === "ok" ? `Done: ${progress.beforeTokens}→${progress.afterTokens} tokens (${progress.changesCompressed} changes, ${progress.ms}ms)`
              : progress.state === "skipped" ? `Skipped: ${progress.reason}`
              : progress.state === "error" ? `Error: ${progress.message}`
              : "Compact now";

  const color = running ? t.warn
                : progress.state === "ok" ? t.ok
                : progress.state === "error" ? t.err
                : progress.state === "skipped" ? t.dim
                : t.accent;

  if (!canCompact) {
    return (
      <Text dimColor>
        {`Compact is a project-memory action; switch to the Project tab.`}
      </Text>
    );
  }
  return (
    <Box>
      <Text color={color}>{running ? `${icon.running} ` : "[c] "}</Text>
      <Text color={color}>{label}</Text>
      {!running && onCompact && progress.state === "idle" ? (
        <Text dimColor>{`  (press 'c' or click)`}</Text>
      ) : null}
    </Box>
  );
}

function moveFocus(
  entries: ReadonlyArray<MemoryPanelEntry>,
  currentId: string | null,
  delta: number,
  setFocus: (id: string | null) => void,
): void {
  if (entries.length === 0) {
    setFocus(null);
    return;
  }
  const idx = entries.findIndex((e) => e.id === currentId);
  const next = idx < 0
    ? 0
    : Math.max(0, Math.min(entries.length - 1, idx + delta));
  const target = entries[next];
  setFocus(target ? target.id : null);
}

/* ----------------------------- exports ------------------------------- */

/** The default `width` for the panel. The App may
 *  override this when the right column is narrower
 *  (e.g. in a 3-column layout). */
export const MEMORY_PANEL_DEFAULT_WIDTH = 60;

/** Tab key set — used by the App's tab-completion
 *  logic in commands.ts so the new /memory command can
 *  benefit from the existing Tab infrastructure. */
export const MEMORY_TAB_KEYS: ReadonlyArray<MemoryScope> = TAB_ORDER;
