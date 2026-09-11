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
