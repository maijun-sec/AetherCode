/**
 * Pill / chip component.
 *
 * A small, self-contained visual chip with:
 *   - a leading icon
 *   - a label
 *   - a foreground + background color pair (the "pill" look)
 *   - optional dim variant for secondary state
 *
 * Pills are the unit of visual differentiation in R33+. Each
 * status / item in the header (model, mode, session, conn, cwd)
 * becomes its own pill with a distinct color so the user can
 * identify the state at a glance.
 *
 * Why pills instead of plain text:
 *   - A 24x80 terminal has limited horizontal real estate.
 *   - Each piece of information is short (e.g. "DEFAULT", "ok").
 *   - Color + icon + background gives 3 dimensions of differentiation
 *     for the price of 1 row of vertical space.
 *
 *   [⚙ DEFAULT] [⌬ MiniMax-M3] [● live] [# 5e3b9a] [⏱ 14m]
 *
 * The colors come from theme.ts. The bg is the color's "bg" variant
 * (we use ink's hex/bg trick: same color but `bgColor` instead of
 * `color`). For greyscale-friendly output (e.g. piped / no-color),
 * we fall back to a bold + dim styling.
 */

import React from "react";
import { Box, Text } from "ink";

export interface PillProps {
  icon?: string;
  label: string;
  /** Foreground color. */
  color: string;
  /** Background color (use the same color as `color` for "filled"
   *  look, or a dim grey for "outlined" look). */
  bg?: string;
  /** When true, render in dim (outlined) style — for de-emphasised
   *  pills like session-id or cwd. */
  dim?: boolean;
  /** Bold the label (default true). */
  bold?: boolean;
  /** Optional tooltip / hint shown after the label in dim text. */
  hint?: string;
}

export const Pill: React.FC<PillProps> = ({ icon, label, color, bg, dim, bold = true, hint }) => {
  if (dim) {
    return (
      <Box>
        {icon ? <Text dimColor>{icon} </Text> : null}
        <Text dimColor>{label}</Text>
        {hint ? <Text dimColor>  {hint}</Text> : null}
      </Box>
    );
  }
  return (
    <Box>
      {icon ? <Text color={bg ?? color}>{icon} </Text> : null}
      {bg ? (
        // Filled pill: same color foreground + background.
        <Text color={color} backgroundColor={bg} bold={bold}>
          {" "}
          {label}
          {" "}
        </Text>
      ) : (
        // Outlined pill: just bold colored text, no bg.
        <Text color={color} bold={bold}>{label}</Text>
      )}
      {hint ? <Text dimColor>  {hint}</Text> : null}
    </Box>
  );
};

/** A small visual separator (a dim middle dot). Use between pills
 *  when laying them out in a row. */
export const PillSep: React.FC = () => <Text dimColor> · </Text>;
