import { useStore } from '../store';
import './StreamingIndicator.css';

/**
 * R269 (2026-09-15) — prominent streaming indicator in the
 * middle of the chat area. Two render modes:
 *
 *   • `variant="empty"` (large, centered) — used when the user
 *      just submitted a prompt but no timeline events have
 *      arrived yet. Sits between the message-list scroll
 *      bounds and the MessageInput, centered.
 *
 *   • `variant="footer"` (compact, left-aligned) — used after
 *      at least one event has streamed in. Renders just below
 *      the last message and above the MessageInput so the user
 *      always sees "the engine is still doing something" while
 *      a long run is in flight.
 *
 * R273 (2026-09-16) — also subscribes to `compactionInProgress`
 * so we keep parity with the now-removed top-of-page
 * ActivityIndicator, which used to surface transcript compaction
 * (⤓ 正在压缩对话历史…) as a separate pill.
 *
 * Both modes share the same color-coded spinner/pulse by
 * `currentActivity.kind` ('thinking' / 'tool' / 'done' / 'error'),
 * matching the legacy ActivityIndicator palette so the user's
 * eye learns the language once.
 */
export type StreamingIndicatorVariant = 'empty' | 'footer';

interface StreamingIndicatorProps {
  variant?: StreamingIndicatorVariant;
  /** when true, render even if no currentActivity is set
   *  (the user just submitted but no events arrived yet) */
  forceWhenEmpty?: boolean;
}

export function StreamingIndicator({
  variant = 'footer',
  forceWhenEmpty = false,
}: StreamingIndicatorProps) {
  const activity = useStore((s) => s.currentActivity);
  const isStreaming = useStore((s) => s.isStreaming);
  // R273: the old top-of-page ActivityIndicator also surfaced
  // compaction ("⤓ 压缩对话历史…"). After R273 removed that
  // banner, the streaming footer takes over the same signal.
  const compacting = useStore((s) => s.compactionInProgress);

  // We render when EITHER:
  //   1) we have a live activity, OR
  //   2) we have an in-progress compaction, OR
  //   3) isStreaming is true and the caller wants the placeholder
  //      (e.g. just-submitted-but-no-events-yet)
  const hasActivity = !!activity;
  if (!hasActivity && !compacting && !(isStreaming && forceWhenEmpty)) return null;

  // Don't show 'done' / 'error' in the empty variant —
  // those mean the run already finished. The top-of-page
  // ActivityIndicator handles the fade-out for those.
  if (variant === 'empty' && activity && (activity.kind === 'done' || activity.kind === 'error') && !compacting) {
    return null;
  }

  // Compaction takes visual priority over the run-end "done" pill
  // because the user is more likely to care about the LLM
  // summarisation work than the prior run's completion. Same
  // ordering the old ActivityIndicator used.
  if (compacting) {
    return (
      <div
        className={`streaming-indicator streaming-${variant} streaming-kind-compaction`}
        role="status"
        aria-live="polite"
        data-testid={`streaming-indicator-${variant}`}
      >
        <span className="streaming-dot" aria-hidden="true" />
        <span className="streaming-label">⤓ 正在压缩对话历史…</span>
      </div>
    );
  }

  const kind = activity?.kind ?? 'thinking';
  const label = activity?.label ?? '⏳ 等待模型响应…';

  return (
    <div
      className={`streaming-indicator streaming-${variant} streaming-kind-${kind}`}
      role="status"
      aria-live="polite"
      data-testid={`streaming-indicator-${variant}`}
    >
      <span className="streaming-dot" aria-hidden="true" />
      <span className="streaming-label">{label}</span>
    </div>
  );
}