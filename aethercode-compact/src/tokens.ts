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
 * Token estimation helpers.
 *
 * We use a simple character-based heuristic (~4 chars per token) that is
 * fast, deterministic, and good enough for budget decisions. A real token
 * counter (e.g. tiktoken) can be plugged in via `PipelineOptions` later.
 */

import type { Message, ToolResult } from "./types.js";

const CHARS_PER_TOKEN = 4;

/** Estimate the token count of an arbitrary string. */
export function estimateTokens(input: string): number {
  if (input.length === 0) {
    return 0;
  }
  return Math.ceil(input.length / CHARS_PER_TOKEN);
}

/** Estimate the token count of a single message. */
export function estimateMessageTokens(message: Message): number {
  if (typeof message.tokens === "number" && message.tokens >= 0) {
    return message.tokens;
  }
  let total = estimateTokens(message.content);
  if (message.toolName) {
    total += estimateTokens(message.toolName);
  }
  return total;
}

/** Estimate the token count of a single tool result. */
export function estimateToolResultTokens(result: ToolResult): number {
  if (typeof result.tokens === "number" && result.tokens >= 0) {
    return result.tokens;
  }
  return estimateTokens(result.body) + estimateTokens(result.toolName);
}

/** Estimate the total prompt token count from history + tool results. */
export function estimateInputTokens(
  history: ReadonlyArray<Message>,
  toolResults: ReadonlyArray<ToolResult>,
): number {
  let total = 0;
  for (const m of history) {
    total += estimateMessageTokens(m);
  }
  for (const r of toolResults) {
    total += estimateToolResultTokens(r);
  }
  return total;
}
