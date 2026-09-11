/**
 * T-6-09 / spec.md §8 / design.md §5.1: the TUI's 10-option
 * consent modal.
 *
 * <p>Renders the full 10-option matrix:
 * <ol>
 *   <li>Allow (this once)</li>
 *   <li>Deny (this once)</li>
 *   <li>Allow for the rest of this session</li>
 *   <li>Deny for the rest of this session</li>
 *   <li>Allow for this project, future sessions</li>
 *   <li>Deny for this project, future sessions</li>
 *   <li>Allow for me, in all projects</li>
 *   <li>Deny for me, in all projects</li>
 *   <li>Allow all {@code <wildcard>} (this project)</li>
 *   <li>Deny all {@code <wildcard>} (this project)</li>
 * </ol>
 *
 * <p>Options 1-8 are ALWAYS present. Options 9-10 only appear
 * when the tool's category matches the {@code shell.command.*}
 * pattern (i.e. the tool is a shell command). The category is
 * exposed via the {@code category} prop and the wildcard
 * sub-category is the leading identifier from the category
 * (e.g. {@code "npm install"} for the {@code shell.command.npm}
 * category). When no wildcard is available, the list is 8
 * items only.
 *
 * <p>Keyboard:
 * <ul>
 *   <li>↑ / k — move highlight up</li>
 *   <li>↓ / j — move highlight down</li>
 *   <li>1-8 (or 9, 0) — quick-select by number</li>
 *   <li>Enter — confirm the highlighted option</li>
 *   <li>Esc — cancel (fires {@code onCancel})</li>
 * </ul>
 *
 * <p>The component is centered in the TUI by its host — it
 * just renders a bordered modal box. The TUI mounts it on top
 * of (replacing) the normal scrollback view.
 *
 * <p>Pure-helper exports (independent of React):
 * <ul>
 *   <li>{@link buildOptions} — given a tool name + category,
 *       compute the 8- or 10-option matrix. The same
 *       function the desktop ConsentModal uses (1:1 port of
 *       aethercode-permission's ConsentOptionMatrix.java).</li>
 *   <li>{@link deriveWildcardSubCategory} — pull the wildcard
 *       sub-category (e.g. "npm install") out of a category
 *       string like {@code "shell.command.npm"}.</li>
 *   <li>{@link shouldShowWildcard} — true if the category is
 *       a {@code shell.command.*} (so options 9-10 are shown).</li>
 * </ul>
 */

import React, { useState, useEffect } from "react";
import { Box, Text, useInput } from "ink";
import { t, icon } from "../../theme.js";

// --------------------------------------------------------------------
//  Types
// --------------------------------------------------------------------

export type GrantScope = "session" | "project" | "user";

export type GrantDecision = "allow" | "deny";

export interface ConsentOption {
  /** 1-10, matches the spec's numbered layout. */
  index: number;
  /** User-facing label. */
  label: string;
  /** Single-character hotkey (the same as the index for
   *  numeric quick-select). */
  hotkey: string;
  scope: GrantScope;
  decision: GrantDecision;
  /** Set only for options 9-10. The wildcard sub-category
   *  (e.g. "npm install" — the "sub" of "shell.command.npm"). */
  wildcardSubCategory?: string;
}

export type RiskLevel = "low" | "medium" | "high";

export interface ConsentModalProps {
  /** Tool name (e.g. "bash", "edit_file"). */
  tool: string;
  /** Tool category (e.g. "shell.command.npm", "fs.write").
   *  Options 9-10 are shown only when this matches
   *  {@code shell.command.*}. */
  category: string;
  /** Human-readable call summary, e.g. `bash "rm -rf /"`. */
  callSummary: string;
  /** Risk level — controls the border colour. */
  risk: RiskLevel;
  /** Fired when the user picks an option (Enter or hotkey). */
  onSelect: (option: ConsentOption) => void;
  /** Fired when the user cancels (Esc). */
  onCancel: () => void;
  /** Optional project id (shown in the header). */
  projectId?: string;
  /** Optional reason / explanation shown under the tool. */
  reason?: string;
}

// --------------------------------------------------------------------
//  Pure helpers (no React / no Ink)
// --------------------------------------------------------------------

/** True if a category triggers the wildcard pair (9-10). */
export function shouldShowWildcard(category: string): boolean {
  if (typeof category !== "string") return false;
  // Exact match (just "shell.command") or a sub-category
  // (e.g. "shell.command.npm"). Anything starting with
  // "shell.command" qualifies.
  return /^shell\.command(\.|$)/i.test(category.trim());
}

/** Derive the wildcard sub-category used in option 9/10's
 *  label. For "shell.command.npm" → "npm"; for
 *  "shell.command.git push --force" → "git push --force".
 *  Returns null if there's no sub-category. */
export function deriveWildcardSubCategory(
  category: string,
): string | null {
  if (!shouldShowWildcard(category)) return null;
  const sub = category.replace(/^shell\.command\.?/i, "").trim();
  if (sub.length === 0) return null;
  return sub;
}

/** Build the 8- or 10-option matrix. Pure — no React, no
 *  side effects. The 8 standard options are always present;
 *  the 9/10 pair is appended only when {@code shouldShowWildcard}
 *  is true. The numeric {@code index} matches the spec's
 *  1-based layout.
 *
 *  Hotkey scheme: each option's hotkey is its index as a
 *  string ("1".."8", "9", "0" for option 10). This matches
 *  the existing {@code ConsentPrompt} UX in this project.
 */
export function buildOptions(category: string): ConsentOption[] {
  const opts: ConsentOption[] = [
    {
      index: 1,
      label: "Allow (this once)",
      hotkey: "1",
      scope: "session",
      decision: "allow",
    },
    {
      index: 2,
      label: "Deny (this once)",
      hotkey: "2",
      scope: "session",
      decision: "deny",
    },
    {
      index: 3,
      label: "Allow for the rest of this session",
      hotkey: "3",
      scope: "session",
      decision: "allow",
    },
    {
      index: 4,
      label: "Deny for the rest of this session",
      hotkey: "4",
      scope: "session",
      decision: "deny",
    },
    {
      index: 5,
      label: "Allow for this project, future sessions",
      hotkey: "5",
      scope: "project",
      decision: "allow",
    },
    {
      index: 6,
      label: "Deny for this project, future sessions",
      hotkey: "6",
      scope: "project",
      decision: "deny",
    },
    {
      index: 7,
      label: "Allow for me, in all projects",
      hotkey: "7",
      scope: "user",
      decision: "allow",
    },
    {
      index: 8,
      label: "Deny for me, in all projects",
      hotkey: "8",
      scope: "user",
      decision: "deny",
    },
  ];
  if (shouldShowWildcard(category)) {
    const sub = deriveWildcardSubCategory(category) ?? "";
    opts.push({
      index: 9,
      label: `Allow all \`${sub}\` (this project)`,
      hotkey: "9",
      scope: "project",
      decision: "allow",
      wildcardSubCategory: sub || undefined,
    });
    opts.push({
      index: 10,
      label: `Deny all \`${sub}\` (this project)`,
      hotkey: "0",
      scope: "project",
      decision: "deny",
      wildcardSubCategory: sub || undefined,
    });
  }
  return opts;
}

const RISK_COLOR: Record<RiskLevel, string> = {
  low: t.ok,
  medium: t.warn,
  high: t.err,
};

const RISK_LABEL: Record<RiskLevel, string> = {
  low: "LOW",
  medium: "MEDIUM",
  high: "HIGH",
};

// --------------------------------------------------------------------
//  Component
// --------------------------------------------------------------------

/**
 * T-6-09: the consent modal UI.
 *
 * <p>Computes its own option matrix via {@link buildOptions}
 * (the existing {@code ConsentPrompt} component receives a
 * pre-built matrix from the daemon — this one is self-
 * contained, which is what the spec asks for in
 * design.md §5.1).
 */
export const ConsentModal: React.FC<ConsentModalProps> = ({
  tool,
  category,
  callSummary,
  risk,
  onSelect,
  onCancel,
  projectId,
  reason,
}) => {
  const options = React.useMemo(() => buildOptions(category), [category]);
  const [highlight, setHighlight] = useState(0);

  // Clamp highlight if the matrix shrinks (e.g. category
  // changed mid-prompt and options 9-10 disappeared).
  useEffect(() => {
    if (highlight >= options.length) {
      setHighlight(Math.max(0, options.length - 1));
    }
  }, [options.length, highlight]);

  useInput((input, key) => {
    if (key.escape) {
      onCancel();
      return;
    }
    if (key.return) {
      const opt = options[highlight];
      if (opt) onSelect(opt);
      return;
    }
    if (key.upArrow || input === "k") {
      setHighlight((h) => (h - 1 + options.length) % options.length);
      return;
    }
    if (key.downArrow || input === "j") {
      setHighlight((h) => (h + 1) % options.length);
      return;
    }
    // Numeric quick-select — same UX as the existing
    // ConsentPrompt.
    if (input === "0") {
      const opt = options[9];
      if (opt) onSelect(opt);
      return;
    }
    if (input >= "1" && input <= "9") {
      const idx = Number(input) - 1;
      const opt = options[idx];
      if (opt) onSelect(opt);
      return;
    }
  });

  const riskColor = RISK_COLOR[risk] ?? t.warn;
  const riskLabel = RISK_LABEL[risk] ?? risk.toUpperCase();
  const hasWildcard = shouldShowWildcard(category);

  return (
    <Box
      flexDirection="column"
      borderStyle="double"
      borderColor={riskColor}
      paddingX={2}
      paddingY={1}
    >
      <Text>
        <Text color={riskColor} bold>
          {icon.warn} CONFIRM REQUIRED
        </Text>
        <Text dimColor>  ·  [{riskLabel} risk]</Text>
      </Text>
      <Text> </Text>
      <Text>
        <Text>Tool: </Text>
        <Text bold color={t.brand}>
          {tool}
        </Text>
        <Text dimColor>  ({callSummary})</Text>
      </Text>
      <Text>
        <Text>Category: </Text>
        <Text color="cyan">{category}</Text>
      </Text>
      {projectId ? (
        <Text>
          <Text dimColor>Project: </Text>
          <Text>{projectId}</Text>
        </Text>
      ) : null}
      {reason ? (
        <Text>
          <Text dimColor>Reason: </Text>
          <Text>{reason}</Text>
        </Text>
      ) : null}
      <Text> </Text>

      {options.map((opt, i) => {
        const isHi = i === highlight;
        const decisionColor = opt.decision === "allow" ? t.ok : t.err;
        return (
          <Box key={opt.index} flexDirection="row">
            <Text color={isHi ? "cyan" : undefined}>
              {isHi ? "▶" : " "}{" "}
            </Text>
            <Text color={isHi ? "cyan" : undefined} bold={isHi}>
              {String(opt.index).padStart(2, " ")}.{" "}
            </Text>
            <Text color={isHi ? "white" : undefined}>{opt.label}</Text>
            <Text color={decisionColor} bold={isHi}>
              {opt.decision === "allow" ? "  ✓" : "  ✗"}
            </Text>
            {opt.wildcardSubCategory ? (
              <Text dimColor>  (wildcard)</Text>
            ) : null}
          </Box>
        );
      })}

      {!hasWildcard ? (
        <Text dimColor>  (options 9-10 hidden — not a shell command)</Text>
      ) : null}

      <Text> </Text>
      <Text dimColor>
        ↑/↓ navigate · Enter confirm · 1-0 quick · Esc cancel
      </Text>
    </Box>
  );
};

export default ConsentModal;
