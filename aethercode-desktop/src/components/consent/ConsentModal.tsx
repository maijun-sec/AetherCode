// Phase 5: ConsentModal (T-5-05).
//
// The 10-option tool-call consent dialog. Options 1-8 are always
// present; options 9-10 appear only when the tool's category starts
// with `shell.command.*` (e.g. `npm install`).
//
// Keyboard navigation: ↑/↓ to move the selection, Enter to confirm.
// The modal traps focus and exposes a labelled "deny" escape hatch
// (Esc → deny-once) so the user can dismiss without choosing a
// persistent rule.

import { useEffect, useMemo, useState, type KeyboardEvent } from 'react';
import { ConsentOption, type ConsentOptionDescriptor } from './ConsentOption';
import type { ConsentChoice, ConsentRequest } from '../../rpc/types';

const ALWAYS: ConsentOptionDescriptor[] = [
  { id: 'allow-once', label: 'Allow (this once)', hint: 'Just this one call. Future calls re-prompt.' },
  { id: 'deny-once', label: 'Deny (this once)', hint: 'Skip this call. Future calls re-prompt.' },
  { id: 'allow-session', label: 'Allow for the rest of this session', hint: 'Applies to all calls in the current session.' },
  { id: 'deny-session', label: 'Deny for the rest of this session', hint: 'Blocks all calls of this kind in the current session.' },
  { id: 'allow-project', label: 'Allow for this project, future sessions', hint: 'Persists in the project’s grant file.' },
  { id: 'deny-project', label: 'Deny for this project, future sessions', hint: 'Persists in the project’s grant file.' },
  { id: 'allow-user', label: 'Allow for me, in all projects', hint: 'Persists in the user-level grant file.' },
  { id: 'deny-user', label: 'Deny for me, in all projects', hint: 'Persists in the user-level grant file.' },
];

function categorySpecific(category: string): ConsentOptionDescriptor[] {
  // Per spec §8.1, 9-10 surface for any category that begins with
  // `shell.command.` (e.g. `shell.command.npm`).
  if (!category.startsWith('shell.command.')) return [];
  const verb = category.slice('shell.command.'.length);
  return [
    {
      id: 'allow-category-project',
      label: `Allow all \`${verb}\` (this project)`,
      hint: 'Persists a wildcard grant so every shell command starting with this verb is auto-allowed.',
    },
    {
      id: 'deny-category-project',
      label: `Deny all \`${verb}\` (this project)`,
      hint: 'Persists a wildcard grant that blocks the same prefix.',
    },
  ];
}

export interface ConsentModalProps {
  request: ConsentRequest | null;
  onResolve: (choice: ConsentChoice) => void;
  onCancel?: () => void;
}

export function ConsentModal({ request, onResolve, onCancel }: ConsentModalProps) {
  const options = useMemo<ConsentOptionDescriptor[]>(
    () => (request ? [...ALWAYS, ...categorySpecific(request.category)] : []),
    [request],
  );
  // Default selection: deny-once (safer than the always-first option).
  const [index, setIndex] = useState(1);
  const selected = options[index] ?? options[0];

  // Reset selection when a new prompt arrives.
  useEffect(() => {
    setIndex(1);
  }, [request?.requestId]);

  const handleKey = (e: KeyboardEvent<HTMLDivElement>) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setIndex((i) => Math.min(options.length - 1, i + 1));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setIndex((i) => Math.max(0, i - 1));
    } else if (e.key === 'Enter') {
      e.preventDefault();
      if (selected) onResolve(selected.id);
    } else if (e.key === 'Escape') {
      e.preventDefault();
      // Esc maps to deny-once per the brief — never silently dismiss.
      onResolve('deny-once');
      onCancel?.();
    }
  };

  if (!request) return null;
  return (
    <div
      className="consent-modal-backdrop"
      role="presentation"
      onClick={(e) => {
        if (e.target === e.currentTarget) onCancel?.();
      }}
    >
      <div
        className="consent-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="consent-modal-title"
        data-testid="consent-modal"
        data-request-id={request.requestId}
        onKeyDown={handleKey}
        tabIndex={-1}
      >
        <header className="consent-modal-header">
          <h2 id="consent-modal-title" data-testid="consent-modal-title">
            Allow {request.toolName}?
          </h2>
          <p className="consent-modal-sub" data-testid="consent-modal-sub">
            Category: <code>{request.category}</code> · risk: {request.riskLevel}
          </p>
        </header>
        <ul className="consent-options" data-testid="consent-options" role="listbox">
          {options.map((opt, i) => (
            <ConsentOption
              key={opt.id}
              option={opt}
              selected={i === index}
              index={i}
              onSelect={() => setIndex(i)}
              onActivate={onResolve}
            />
          ))}
        </ul>
        <footer className="consent-modal-footer">
          <span className="consent-modal-hint">↑/↓ to choose · Enter to confirm · Esc = deny once</span>
        </footer>
      </div>
    </div>
  );
}
