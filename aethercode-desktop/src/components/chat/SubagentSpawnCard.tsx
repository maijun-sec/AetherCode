// Phase 4.2: SubagentSpawnCard (T-4-15).
//
// Inline card surfaced in the chat when an assistant message
// declares a sub-agent. The card is collapsed by default (per
// spec §12.2 — "everything is collapsed by default") and shows
// the subagent's id + role + status. Clicking expands to show
// the assigned task description.

import { useState } from 'react';
import type { SessionEvent } from '../../rpc/types';

export interface SubagentSpawnCardProps {
  event: SessionEvent;
  /** Optional callback to navigate to the subagent's session page. */
  onOpen?: (subagentId: string) => void;
  /** optional dismiss callback. The MessageList
   *  wires this to drop the card from its in-memory
   *  list; the engine doesn't fire a "spawn_dismissed"
   *  event because dismissing is a UI concern, not a
   *  session concern. */
  onDismiss?: (subagentId: string) => void;
}

export function SubagentSpawnCard({ event, onOpen, onDismiss }: SubagentSpawnCardProps) {
  const [open, setOpen] = useState(false);
  const subagentId = event.subagentId ?? readString(event.data, 'subagentId') ?? 'subagent';
  const role = readString(event.data, 'role') ?? 'subagent';
  const description = readString(event.data, 'description') ?? '';
  const status = readString(event.data, 'status') ?? 'spawned';

  return (
    <article
      className={`subagent-spawn-card ${open ? 'open' : 'collapsed'}`}
      data-testid="subagent-spawn-card"
      data-subagent-id={subagentId}
    >
      <header
        className="subagent-spawn-header"
        role="button"
        tabIndex={0}
        aria-expanded={open}
        data-testid="subagent-spawn-toggle"
        onClick={() => setOpen((o) => !o)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault();
            setOpen((o) => !o);
          }
        }}
      >
        <span className="subagent-spawn-icon" aria-hidden>⤵</span>
        <span className="subagent-spawn-role" data-testid="subagent-spawn-role">{role}</span>
        <span className="subagent-spawn-id" data-testid="subagent-spawn-id">{subagentId}</span>
        <span className={`subagent-spawn-status subagent-spawn-status-${status}`} data-testid="subagent-spawn-status">
          {status}
        </span>
        <span className="subagent-spawn-caret" aria-hidden>{open ? '▾' : '▸'}</span>
      </header>
      {open && (
        <div className="subagent-spawn-body" data-testid="subagent-spawn-body">
          {description && <p data-testid="subagent-spawn-description">{description}</p>}
          {onOpen && (
            <button
              type="button"
              className="subagent-spawn-link"
              data-testid="subagent-spawn-link"
              onClick={() => onOpen(subagentId)}
            >
              View subagent →
            </button>
          )}
          {onDismiss && (
            <button
              type="button"
              className="subagent-spawn-dismiss"
              data-testid="subagent-spawn-dismiss"
              onClick={() => onDismiss(subagentId)}
              title="Dismiss this card (the subagent itself keeps running)"
            >
              × Dismiss
            </button>
          )}
        </div>
      )}
    </article>
  );
}

function readString(data: unknown, key: string): string | null {
  if (data && typeof data === 'object' && key in (data as Record<string, unknown>)) {
    const v = (data as Record<string, unknown>)[key];
    if (typeof v === 'string') return v;
  }
  return null;
}
