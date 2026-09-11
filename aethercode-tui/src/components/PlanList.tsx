/**
 * plan list (a.k.a. todo list).
 *
 * Shown when the agent emits a plan via the `plan` event. The
 * plan is rendered as a vertical list of indented items, each
 * with a numbered marker.
 */

import React from "react";
import { Box, Text } from "ink";
import { t, icon } from "../theme.js";

interface Props {
  items: string[];
  title?: string;
}

export const PlanList: React.FC<Props> = ({ items, title }) => {
  return (
    <Box
      borderStyle="single"
      borderColor={t.accent}
      flexDirection="column"
      paddingX={1}
    >
      <Text>
        <Text color={t.accent}>{icon.plan} </Text>
        <Text bold>{title ?? "Plan"}</Text>
        <Text dimColor>  ({items.length} item{items.length === 1 ? "" : "s"})</Text>
      </Text>
      {items.map((it, i) => (
        <Text key={i}>
          <Text color={t.accent}>  {String(i + 1).padStart(2, " ")}. </Text>
          {it}
        </Text>
      ))}
    </Box>
  );
};
