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
    <Box borderStyle="single" borderColor={t.accent} flexDirection="column" paddingX={1}>
      <Text>
        <Text color={t.accent}>{icon.plan} </Text>
        <Text bold>{title ?? "Plan"}</Text>
        <Text dimColor>
          {" "}
          ({items.length} item{items.length === 1 ? "" : "s"})
        </Text>
      </Text>
      {items.map((it, i) => (
        <Text key={i}>
          <Text color={t.accent}> {String(i + 1).padStart(2, " ")}. </Text>
          {it}
        </Text>
      ))}
    </Box>
  );
};
