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
