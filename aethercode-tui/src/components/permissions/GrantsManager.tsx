/**
 * T-6-10 / spec.md §9 / design.md §5.1: the TUI's
 * {@code GrantsManager}.
 *
 * <p>3-column layout:
 * <ul>
 *   <li>Left: list of grants (sorted by createdAt desc).
 *       Highlights the focused row.</li>
 *   <li>Middle: details of the focused grant — full scope /
 *       decision / reason / createdAt / expiresAt.</li>
 *   <li>Right: filter (scope / category) + the
 *       {@link PresetSelector}.</li>
 * </ul>
 *
 * <p>Revoke: pressing {@code r} on a focused row fires
 * {@code onRevoke(grant.id)} (the host wires it to
 * {@code grants/revoke}).
 *
 * <p>The component is presentational. The host owns the data
 * (calls {@code grants/list} and dispatches {@code grants/revoke}
 * / {@code grants/setPreset}). Tests can render the
 * component with a synthetic grant list and assert the layout.
 *
 * <p>Pure-helper exports (testable without React):
 * <ul>
 *   <li>{@link filterGrants} — applies the current
 *       scope / category filter to a grant list.</li>
 *   <li>{@link groupGrantsByScope} — bucketed grouping for
 *       the left list (the UI renders grouped headers).</li>
 *   <li>{@link formatGrantLine} — one-line summary for a grant
 *       (used by the list rows).</li>
 * </ul>
 */

import React, { useState, useMemo } from "react";
import { Box, Text, useInput } from "ink";
import { t, icon } from "../../theme.js";
import {
  PresetSelector,
  PRESETS,
  type PresetId,
} from "./PresetSelector.js";

// --------------------------------------------------------------------
//  Types
// --------------------------------------------------------------------

/** A single consent grant. Shape matches
 *  `permissionRpc.PermissionGrant` so the host can pass the
 *  raw list straight through. The component does NOT reach
 *  into the daemon — the host does. */
export interface Grant {
  id: string;
  scope: "session" | "project" | "user";
  scopeId: string;
  category: string;
  decision: "allow" | "deny";
  reason: string;
  createdAt: number;
  expiresAt?: number | null;
}

export interface GrantsManagerProps {
  grants: Grant[];
  /** Currently active preset (driven by the host — usually
   *  the result of `grants/list`'s `activePreset` field, or
   *  a separately fetched `grants/getActivePreset`). */
  activePreset?: PresetId | null;
  /** Fired when the user presses `r` on a focused row. */
  onRevoke?: (grantId: string) => void;
  /** Fired when the user picks a preset in the
   *  {@link PresetSelector}. */
  onApplyPreset?: (preset: PresetId) => void;
  /** Optional: scope filter ("all" by default). */
  initialScopeFilter?: "all" | "session" | "project" | "user";
  /** Optional: category filter (substring match, empty = all). */
  initialCategoryFilter?: string;
}

// --------------------------------------------------------------------
//  Pure helpers
// --------------------------------------------------------------------

/** Apply the filter to a grant list. Both filters are ANDed. */
export function filterGrants(
  grants: Grant[],
  scope: "all" | "session" | "project" | "user",
  category: string,
): Grant[] {
  const cat = (category ?? "").trim().toLowerCase();
  return grants.filter((g) => {
    if (scope !== "all" && g.scope !== scope) return false;
    if (cat && !g.category.toLowerCase().includes(cat)) return false;
    return true;
  });
}

/** Group a grant list by scope. Used by the UI to render
 *  section headers in the left list. */
export function groupGrantsByScope(
  grants: Grant[],
): Array<{ scope: Grant["scope"]; items: Grant[] }> {
  const order: Grant["scope"][] = ["session", "project", "user"];
  const out: Array<{ scope: Grant["scope"]; items: Grant[] }> = [];
  for (const s of order) {
    const items = grants
      .filter((g) => g.scope === s)
      .sort((a, b) => b.createdAt - a.createdAt);
    if (items.length > 0) out.push({ scope: s, items });
  }
  return out;
}

/** One-line summary for a grant. Used in the list rows. */
export function formatGrantLine(g: Grant): string {
  const cat = g.category || "(uncategorized)";
  const decision = g.decision.toUpperCase();
  return `${g.scope} · ${cat} · ${decision}`;
}

const SCOPE_LABEL: Record<Grant["scope"], string> = {
  session: "session",
  project: "project",
  user: "user",
};

const DECISION_ICON: Record<Grant["decision"], string> = {
  allow: "✓",
  deny: "✗",
};
const DECISION_COLOR: Record<Grant["decision"], string> = {
  allow: t.ok,
  deny: t.err,
};

function fmtTs(ts: number): string {
  if (!Number.isFinite(ts)) return "—";
  return new Date(ts).toLocaleString();
}

function fmtRel(ts: number, now: number = Date.now()): string {
  const diff = now - ts;
  if (diff < 60_000) return `${Math.max(0, Math.floor(diff / 1000))}s ago`;
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m ago`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h ago`;
  return `${Math.floor(diff / 86_400_000)}d ago`;
}

// --------------------------------------------------------------------
//  Component
// --------------------------------------------------------------------

export const GrantsManager: React.FC<GrantsManagerProps> = ({
  grants,
  activePreset = null,
  onRevoke,
  onApplyPreset,
  initialScopeFilter = "all",
  initialCategoryFilter = "",
}) => {
  const [scopeFilter, setScopeFilter] = useState<"all" | "session" | "project" | "user">(initialScopeFilter);
  const [categoryFilter, setCategoryFilter] = useState<string>(initialCategoryFilter);
  const [focusId, setFocusId] = useState<string | null>(null);

  const filtered = useMemo(
    () => filterGrants(grants, scopeFilter, categoryFilter),
    [grants, scopeFilter, categoryFilter],
  );
  const grouped = useMemo(() => groupGrantsByScope(filtered), [filtered]);

  // Auto-focus the first row when the filter changes (or on
  // initial mount).
  React.useEffect(() => {
    if (focusId && filtered.some((g) => g.id === focusId)) return;
    setFocusId(filtered[0]?.id ?? null);
  }, [filtered, focusId]);

  const focused = filtered.find((g) => g.id === focusId) ?? null;

  useInput((input, key) => {
    // r = revoke the focused row
    if (input === "r" && focused && onRevoke) {
      onRevoke(focused.id);
      return;
    }
    // ↓ / j — next grant
    if (key.downArrow || input === "j") {
      const idx = filtered.findIndex((g) => g.id === focusId);
      const next = filtered[Math.min(filtered.length - 1, idx + 1)];
      if (next) setFocusId(next.id);
      return;
    }
    // ↑ / k — previous grant
    if (key.upArrow || input === "k") {
      const idx = filtered.findIndex((g) => g.id === focusId);
      const prev = filtered[Math.max(0, idx - 1)];
      if (prev) setFocusId(prev.id);
      return;
    }
    // 1/2/3 — switch scope filter
    if (input === "1") { setScopeFilter("all"); return; }
    if (input === "2") { setScopeFilter("session"); return; }
    if (input === "3") { setScopeFilter("project"); return; }
    if (input === "4") { setScopeFilter("user"); return; }
  });

  return (
    <Box
      flexDirection="column"
      borderStyle="round"
      borderColor={t.dim}
      paddingX={1}
    >
      <Box>
        <Text color={t.brand} bold>
          {icon.tool} Grants Manager
        </Text>
        <Text dimColor>
          {"  "}· {filtered.length}/{grants.length} shown
        </Text>
      </Box>
      <Text> </Text>
      <Box flexDirection="row">
        {/* LEFT — list */}
        <Box flexDirection="column" width={36} borderStyle="single" borderColor={t.dim} paddingX={1}>
          <Text bold>grants ({filtered.length})</Text>
          {filtered.length === 0 ? (
            <Text dimColor>(no grants — change filter or /grants clear)</Text>
          ) : (
            grouped.map(({ scope, items }) => (
              <Box key={scope} flexDirection="column" marginTop={1}>
                <Text dimColor>▼ {SCOPE_LABEL[scope]}</Text>
                {items.map((g) => {
                  const isFocused = g.id === focusId;
                  return (
                    <Box key={g.id} flexDirection="column">
                      <Text
                        color={isFocused ? "cyan" : undefined}
                        bold={isFocused}
                      >
                        {isFocused ? "▶ " : "  "}
                        {DECISION_ICON[g.decision]} {g.category || "(uncategorized)"}
                      </Text>
                      <Text dimColor>
                        {"    "}{g.scopeId} · {fmtRel(g.createdAt)}
                      </Text>
                    </Box>
                  );
                })}
              </Box>
            ))
          )}
        </Box>

        {/* MIDDLE — details */}
        <Box flexDirection="column" flexGrow={1} marginLeft={1} borderStyle="single" borderColor={t.dim} paddingX={1}>
          <Text bold>details</Text>
          {focused ? (
            <Box flexDirection="column" marginTop={1}>
              <Text>
                <Text dimColor>id:        </Text>
                <Text>{focused.id}</Text>
              </Text>
              <Text>
                <Text dimColor>scope:     </Text>
                <Text bold>{SCOPE_LABEL[focused.scope]}</Text>
                <Text dimColor> ({focused.scopeId})</Text>
              </Text>
              <Text>
                <Text dimColor>category:  </Text>
                <Text>{focused.category || "(uncategorized)"}</Text>
              </Text>
              <Text>
                <Text dimColor>decision:  </Text>
                <Text color={DECISION_COLOR[focused.decision]} bold>
                  {focused.decision.toUpperCase()} {DECISION_ICON[focused.decision]}
                </Text>
              </Text>
              <Text>
                <Text dimColor>reason:    </Text>
                <Text>{focused.reason || "(none)"}</Text>
              </Text>
              <Text>
                <Text dimColor>created:   </Text>
                <Text>{fmtTs(focused.createdAt)}</Text>
              </Text>
              <Text>
                <Text dimColor>expires:   </Text>
                <Text>
                  {focused.expiresAt
                    ? fmtTs(focused.expiresAt)
                    : "(never)"}
                </Text>
              </Text>
              {onRevoke ? (
                <Text dimColor>
                  press <Text color={t.err} bold>r</Text> to revoke
                </Text>
              ) : null}
            </Box>
          ) : (
            <Text dimColor>(no grant focused — use ↑/↓ to pick one)</Text>
          )}
        </Box>

        {/* RIGHT — filter + preset */}
        <Box flexDirection="column" width={32} marginLeft={1} borderStyle="single" borderColor={t.dim} paddingX={1}>
          <Text bold>filter</Text>
          <Text>
            <Text dimColor>scope: </Text>
            <Text color={scopeFilter === "all" ? "cyan" : undefined} bold={scopeFilter === "all"}>
              [1] all
            </Text>
          </Text>
          <Text>
            <Text dimColor>      </Text>
            <Text color={scopeFilter === "session" ? "cyan" : undefined} bold={scopeFilter === "session"}>
              [2] session
            </Text>
          </Text>
          <Text>
            <Text dimColor>      </Text>
            <Text color={scopeFilter === "project" ? "cyan" : undefined} bold={scopeFilter === "project"}>
              [3] project
            </Text>
          </Text>
          <Text>
            <Text dimColor>      </Text>
            <Text color={scopeFilter === "user" ? "cyan" : undefined} bold={scopeFilter === "user"}>
              [4] user
            </Text>
          </Text>
          <Text> </Text>
          <Text>
            <Text dimColor>category: </Text>
            <Text color="cyan">{categoryFilter || "(any)"}</Text>
          </Text>
          <Text> </Text>
          <PresetSelector
            active={activePreset}
            onApply={onApplyPreset}
            compact
          />
        </Box>
      </Box>
      <Text> </Text>
      <Text dimColor>
        ↑/↓ navigate · r revoke · 1-4 scope filter · {" "}
        {PRESETS.length} presets available
      </Text>
    </Box>
  );
};

export default GrantsManager;
