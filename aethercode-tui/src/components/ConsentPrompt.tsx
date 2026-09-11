/**
 * T-250..T-253 / T-255 / spec.md §3.3 / design.md §3.4:
 * the in-TUI consent prompt.
 *
 * <p>Renders the 10-option matrix (8 standard + 2 category-specific)
 * produced by {@code aethercode-permission/prompt/ConsentOptionMatrix.java}
 * and provides:
 *
 * <ul>
 *   <li>T-252: arrow-key navigation (↑/↓/j/k) over the option
 *       list, with Enter to confirm and Esc to cancel.</li>
 *   <li>T-251: the option matrix is built by the Java side and
 *       passed in via {@code options}. The component is a
 *       renderer + input handler — it does not invent options
 *       on its own.</li>
 *   <li>T-253: the {@code ?} key flips the prompt into a help
 *       view showing why this call is risky (the matched
 *       categorisation rules). The same key returns to the
 *       option list. {@code Esc} on the help view returns to
 *       the option list; {@code Esc} on the option list
 *       cancels the prompt.</li>
 * </ul>
 *
 * <p>The component is <em>modal</em> — the rest of the TUI
 * is hidden while the prompt is mounted. The host application
 * is responsible for unmounting it once {@code onSelect} or
 * {@code onCancel} fires.
 */

import React, { useState, useEffect } from "react";
import { Box, Text, useInput } from "ink";

export type RiskLevel = "low" | "medium" | "high";

export type OptionKind = "once" | "standard" | "wildcard";

export type GrantScope = "session" | "project" | "user";

export type GrantDecision = "allow" | "deny";

export interface ConsentOption {
  /** 1..10, matches the spec's numbered layout. */
  index: number;
  kind: OptionKind;
  /** User-facing label (e.g. "Allow for the rest of this session"). */
  label: string;
  /** Single-character hotkey (a / A / p / P / u / U / w / W / d / D). */
  hotkey: string;
  scope: GrantScope;
  decision: GrantDecision;
  /** Set only for options 9-10 (the wildcard pair). */
  wildcardSubCategory?: string;
}

export interface CategoryResult {
  categories: string[];
  risk: RiskLevel;
  matchedRules: string[];
}

/** Reason / explanation rendered under the option list. */
export interface ConsentPromptProps {
  /** The categorisation result. */
  category: CategoryResult;
  /** The 10-option matrix built by ConsentOptionMatrix.java. */
  options: ConsentOption[];
  /** Human-readable call summary, e.g. `bash "rm -rf /"`. */
  callSummary: string;
  /** Fired when the user picks an option (Enter or hotkey). */
  onSelect: (option: ConsentOption) => void;
  /** Fired when the user cancels (Esc on the option list). */
  onCancel: () => void;
  /** Project id (used in the option label) — purely cosmetic. */
  projectId?: string;
}

const RISK_COLOR: Record<RiskLevel, string> = {
  low: "green",
  medium: "yellow",
  high: "red",
};

const RISK_LABEL: Record<RiskLevel, string> = {
  low: "LOW",
  medium: "MEDIUM",
  high: "HIGH",
};

const SCOPE_LABEL: Record<GrantScope, string> = {
  session: "session",
  project: "project",
  user: "user",
};

/**
 * T-251: the consent prompt UI. The matrix comes in from
 * the Java side; this component only renders + handles input.
 *
 * <p>Keyboard map:
 * <ul>
 *   <li>↑ / k   — move highlight up</li>
 *   <li>↓ / j   — move highlight down</li>
 *   <li>1..9    — quick-select option 1..9</li>
 *   <li>0       — quick-select option 10 (matches the
 *       design's 1-0 numeric layout)</li>
 *   <li>a/A/p/P/u/U/w/W/d/D — hotkey-select</li>
 *   <li>?       — toggle the help view (T-253)</li>
 *   <li>Enter   — confirm the highlighted option</li>
 *   <li>Esc     — cancel (option list) / back to options (help)</li>
 * </ul>
 */
export const ConsentPrompt: React.FC<ConsentPromptProps> = ({
  category,
  options,
  callSummary,
  onSelect,
  onCancel,
  projectId,
}) => {
  const [highlight, setHighlight] = useState(0);
  const [showHelp, setShowHelp] = useState(false);

  // Clamp highlight if the matrix shrinks (e.g. the
  // wildcard pair disappears when the categoriser emits
  // no category).
  useEffect(() => {
    if (highlight >= options.length) {
      setHighlight(Math.max(0, options.length - 1));
    }
  }, [options.length, highlight]);

  useInput((input, key) => {
    // T-253: help view is a one-key toggle. ? flips
    // back to the option list; Esc on the help view
    // also returns to options; only Esc on the option
    // list cancels.
    if (input === "?") {
      setShowHelp((s) => !s);
      return;
    }
    if (showHelp) {
      if (key.escape || input === "?") {
        setShowHelp(false);
      }
      return;
    }

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

    // Numeric quick-select: 1..9 and 0 → 10.
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

    // Hotkey select — case-sensitive (per spec, capital
    // letters mean "deny" and lowercase "allow").
    if (input.length === 1) {
      const match = options.find((o) => o.hotkey === input);
      if (match) {
        onSelect(match);
        return;
      }
    }
  });

  if (showHelp) {
    return <HelpView category={category} projectId={projectId} />;
  }

  return (
    <Box
      flexDirection="column"
      borderStyle="double"
      borderColor={RISK_COLOR[category.risk] ?? "yellow"}
      paddingX={2}
      paddingY={1}
    >
      <Text>
        <Text color={RISK_COLOR[category.risk] ?? "yellow"} bold>
          ⚠ CONFIRM REQUIRED{" "}
        </Text>
        <Text dimColor>[{RISK_LABEL[category.risk] ?? category.risk.toUpperCase()} risk]</Text>
      </Text>
      <Text> </Text>
      <Text>
        <Text>Tool: </Text>
        <Text bold>{callSummary}</Text>
      </Text>
      <Text>
        <Text>Categories: </Text>
        <Text color="cyan">
          {category.categories.length > 0 ? category.categories.join(", ") : "(uncategorized)"}
        </Text>
      </Text>
      {projectId ? (
        <Text>
          <Text dimColor>Project: </Text>
          <Text>{projectId}</Text>
        </Text>
      ) : null}
      <Text> </Text>

      {options.map((opt, i) => {
        const isHighlighted = i === highlight;
        const isAllow = opt.decision === "allow";
        const decisionColor = isAllow ? "green" : "red";
        return (
          <Box key={opt.index} flexDirection="row">
            <Text color={isHighlighted ? "cyan" : undefined}>
              {isHighlighted ? "▶" : " "}{" "}
            </Text>
            <Text color={isHighlighted ? "cyan" : undefined} bold={isHighlighted}>
              {String(opt.index).padStart(2, " ")}.{" "}
            </Text>
            <Text color={decisionColor} bold={isHighlighted}>
              [{opt.hotkey}]
            </Text>
            <Text> </Text>
            <Text color={isHighlighted ? "white" : undefined}>{opt.label}</Text>
            {opt.wildcardSubCategory ? (
              <Text dimColor>{" "}({SCOPE_LABEL[opt.scope]})</Text>
            ) : null}
          </Box>
        );
      })}

      <Text> </Text>
      <Text dimColor>
        ↑/↓ navigate · Enter confirm · 1-0 quick · ? help · Esc cancel
      </Text>
    </Box>
  );
};

/**
 * T-253: the help view. Renders the categorisation's
 * matched rules so the user can see "why is this risky?"
 * without leaving the prompt. Pressing any key (or Esc)
 * returns to the option list.
 */
const HelpView: React.FC<{
  category: CategoryResult;
  projectId?: string;
}> = ({ category, projectId }) => {
  return (
    <Box
      flexDirection="column"
      borderStyle="double"
      borderColor="cyan"
      paddingX={2}
      paddingY={1}
    >
      <Text>
        <Text color="cyan" bold>Why is this risky?</Text>
      </Text>
      <Text> </Text>
      <Text>
        <Text>Risk level: </Text>
        <Text color={RISK_COLOR[category.risk] ?? "yellow"} bold>
          {RISK_LABEL[category.risk] ?? category.risk.toUpperCase()}
        </Text>
      </Text>
      <Text>
        <Text>Categories: </Text>
        <Text color="cyan">
          {category.categories.length > 0 ? category.categories.join(", ") : "(uncategorized)"}
        </Text>
      </Text>
      {projectId ? (
        <Text>
          <Text dimColor>Project: </Text>
          <Text>{projectId}</Text>
        </Text>
      ) : null}
      <Text> </Text>
      <Text bold>Matched rules</Text>
      {category.matchedRules.length === 0 ? (
        <Text dimColor>(no specific rule matched — default risk applied)</Text>
      ) : (
        category.matchedRules.map((r, i) => (
          <Text key={i}>
            <Text color="yellow">  • {r}</Text>
          </Text>
        ))
      )}
      <Text> </Text>
      <Text dimColor>Default rule table (excerpt):</Text>
      <Text dimColor>  read_file / glob / grep / ls       — low</Text>
      <Text dimColor>  bash "ls …" / bash "cat …"           — medium</Text>
      <Text dimColor>  bash "rm …" / "mv …"                — high  (shell.destructive)</Text>
      <Text dimColor>  bash "npm install" / "pip install"  — high  (shell.package_install)</Text>
      <Text dimColor>  write_file to existing file        — medium (file.overwrite_existing)</Text>
      <Text dimColor>  delete_file                          — high  (file.delete)</Text>
      <Text dimColor>  git push --force / git reset --hard  — high  (shell.git_mutation)</Text>
      <Text dimColor>  mcp_* tool                           — high  (mcp.tool_invocation)</Text>
      <Text dimColor>  python_run with exec/eval           — high  (code.python_run)</Text>
      <Text> </Text>
      <Text dimColor>Press ? or Esc to return to the options.</Text>
    </Box>
  );
};

export default ConsentPrompt;
