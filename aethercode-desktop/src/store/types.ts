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

// Shared types for the Zustand store.
//
// child step events surface nested activity
// (tool calls, sub-events) under a running workflow step
// pill. The executor's wrapper produces a structured
// key=value message; the store parses it into a typed
// {@link ChildStepEvent} for the renderer to consume.
//
// Why parse in the store, not the renderer? Because the
// progress bar reads the events via a single `useStore`
// selector, the render path needs the typed shape, and the
// parser benefits from a single canonical implementation
// (so two renderers — the WorkflowProgressBar and a
// future "step detail" panel — agree on the same event
// shape).

/** A single child-session event under a workflow step.
 *  The executor's wrapper produces one of these per
 *  forwarded StreamEvent. The renderer reads the typed
 *  fields directly; the raw `raw` field preserves the
 *  original message for debug hover. */
export interface ChildStepEvent {
  /** Wall-clock ms when the event was received. Used
   *  by the renderer to compute a "running for Xs"
   *  label and to time-order the list. */
  ts: number;
  /** Step id this event is attributed to. Matches the
   *  parent's `stepEvents` key. The store may not set
   *  this if the message's step id cannot be parsed;
   *  the renderer should treat empty as "unattributed"
   *  and skip rendering. */
  stepId: string;
  /** Coarse event category. Drives the icon column in
   *  the nested progress row:
   *    tool_use   → ◐ (running tool, before result)
   *    tool_ok    → ✓ (tool finished ok)
   *    tool_err   → ✗ (tool returned error)
   *    text       → ✎ (text delta; usually suppressed)
   *    run_start  → ▶ (turn began)
   *    run_end    → ■ (turn ended; the stop reason is
   *                  shown on hover)
   *    note       → ℹ (side note; notekind is the
   *                  truncated kind in `name`)
   *    unknown    → ?  (unparseable class name) */
  kind: 'tool_use' | 'tool_ok' | 'tool_err' | 'text'
       | 'run_start' | 'run_end' | 'note' | 'unknown';
  /** Tool name, skill/agent name, or model id,
   *  depending on `kind`. Empty when not applicable. */
  name: string;
  /** Brief, single-line summary. For tool_use this is
   *  the first key=value pair of the input (e.g.
   *  "command=ls -la"); for tool_ok/tool_err this is
   *  the output length; for run_start this is the
   *  model id; for run_end this is the stop reason. */
  summary: string;
  /** True if the arg was truncated when building the
   *  summary. The renderer shows a "…" suffix when
   *  set, and the full arg is preserved in `raw` for
   *  the hover tooltip. */
  truncated?: boolean;
  /** Raw message, preserved for debug. The renderer
   *  uses this as the `title` attribute on the row so
   *  hovering shows the unprocessed payload. */
  raw: string;
}

/** Maximum events kept per step. Older events are
 *  discarded (FIFO). 6 is enough to fill one
 *  nested row at a time without scrolling; longer
 *  histories are not actionable in a real-time
 *  progress bar. */
export const MAX_STEP_EVENTS = 6;
