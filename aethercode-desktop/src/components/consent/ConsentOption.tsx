// Phase 5: ConsentOption (T-5-06) — single radio row in the 10-option
// consent modal. Kept dumb so the parent modal can lay it out and
// own the keyboard navigation.

import type { ConsentChoice } from '../../rpc/types';

export interface ConsentOptionDescriptor {
  /** Stable id used for the radio `name` and the DOM `data-choice`. */
  id: ConsentChoice;
  /** User-facing label. */
  label: string;
  /** Optional one-line explanation. Shown in muted text below the
   *  label so the user can decide without expanding anything. */
  hint?: string;
}

export interface ConsentOptionProps {
  option: ConsentOptionDescriptor;
  selected: boolean;
  index: number;
  onSelect: (choice: ConsentChoice) => void;
  onActivate: (choice: ConsentChoice) => void;
}

export function ConsentOption({
  option,
  selected,
  index,
  onSelect,
  onActivate,
}: ConsentOptionProps) {
  return (
    <li
      className={`consent-option ${selected ? 'selected' : ''}`}
      data-testid={`consent-option-${option.id}`}
      data-choice={option.id}
      role="option"
      aria-selected={selected}
    >
      <button
        type="button"
        className="consent-option-button"
        data-testid={`consent-option-button-${option.id}`}
        onClick={() => { onSelect(option.id); onActivate(option.id); }}
        onMouseEnter={() => onSelect(option.id)}
        onFocus={() => onSelect(option.id)}
        onKeyDown={(e) => {
          if (e.key === 'Enter') {
            e.preventDefault();
            onActivate(option.id);
          }
        }}
        autoFocus={index === 0 && selected}
      >
        <span className="consent-option-radio" aria-hidden>
          {selected ? '●' : '○'}
        </span>
        <span className="consent-option-index">{index + 1}.</span>
        <span className="consent-option-label">{option.label}</span>
        {option.hint && (
          <span className="consent-option-hint">{option.hint}</span>
        )}
      </button>
    </li>
  );
}
