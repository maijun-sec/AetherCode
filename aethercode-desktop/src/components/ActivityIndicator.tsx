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

import { useStore } from '../store';
import './ActivityIndicator.css';

// R82+ Issue 1: a small status row pinned above the message list
// that tells the user "where the engine is at" — derived from the
// stream_event handlers in the store. Three visible states:
//   • thinking (blue,  ❍ pulse) — engine is generating
//   • tool      (amber, ⏳ spin)  — a tool call is in flight
//   • done      (green, fade)    — run finished, fades after 2s
//   • error     (red)            — surfaced from `log` events
//   • compaction (purple, ⤓)    — R91: transcript compaction in
//     flight (the engine is calling the LLM to summarise the
//     old messages). Sourced from `compactionInProgress` rather
//     than `currentActivity` so the two indicators don't fight.
//
// We deliberately do NOT subscribe to the full `messages` array —
// just `currentActivity` and `compactionInProgress` — so the
// indicator doesn't re-render on every text_delta.

export function ActivityIndicator() {
  const activity = useStore((s) => s.currentActivity);
  const compacting = useStore((s) => s.compactionInProgress);
  if (!activity && !compacting) return null;

  // Compaction takes visual priority over the run-end "done" pill
  // because the user is more likely to care about the LLM
  // summarisation work than the prior run's completion.
  if (compacting) {
    return (
      <div
        className="activity-indicator activity-compaction"
        role="status"
        aria-live="polite"
        data-testid="activity-indicator"
      >
        <span className="activity-dot" aria-hidden="true" />
        <span className="activity-label">⤓ 正在压缩对话历史…</span>
      </div>
    );
  }

  return (
    <div
      className={`activity-indicator activity-${activity!.kind}`}
      role="status"
      aria-live="polite"
      data-testid="activity-indicator"
    >
      <span className="activity-dot" aria-hidden="true" />
      <span className="activity-label">{activity!.label}</span>
    </div>
  );
}
