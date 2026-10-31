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

import { useStore, type DecisionOption } from '../store';
import './AwaitingDecisionBanner.css';

// shown above the message list when the per-todo controller
// has hit its max LLM bumps and is asking the user to decide
// whether to continue, abort, or change direction. R714
// upgrade: the engine now sends a structured menu of choices
// (A/B/C/D etc.) inside AwaitUserDecision.options; the banner
// renders each choice as a clickable card so the user can act
// without typing the letter themselves.
//
// Three ways out:
//   - click an option card  → sends the matching continuation
//                              text ("Pick (A): Mark complete…")
//                              to the LLM and resumes the run.
//   - click "Continue"      → sends a canned "continue with
//                              the current strategy" message.
//   - click "Change direction" → dismisses the banner; user
//                              types their own direction in the
//                              input box.
//   - click "Abort"         → dismisses without sending
//                              anything (engine stays paused).

const CONTINUE = 'continue with the current strategy — the previous bumps were a false alarm';

export function AwaitingDecisionBanner() {
  const awaiting = useStore((s) => s.awaitingUserDecision);
  const dismiss = useStore((s) => s.dismissAwaitingUserDecision);
  const setInput = useStore((s) => s.setCurrentInput);
  const sendMessage = useStore((s) => s.sendMessage);
  if (!awaiting) return null;

  const onContinue = () => {
    setInput(CONTINUE);
    // The next render will use the new input; we send immediately
    // so the user doesn't have to click Send twice.
    setTimeout(() => void sendMessage(), 0);
  };
  const onAbort = () => dismiss();
  const onChange = () => dismiss();
  const onPick = (opt: DecisionOption) => {
    // Match the wording the LLM saw in its earlier ask-llm
    // prompt ("Pick ONE: (A) … (B) …") so the LLM's response
    // path is identical to the user-typed case. The full
    // description is included so the LLM has the rationale
    // available even when the menu changes between builds.
    setInput(`Pick (${opt.letter}): ${opt.description}`);
    setTimeout(() => void sendMessage(), 0);
  };

  return (
    <div className="awaiting-banner" role="alert">
      <div className="awaiting-text">
        <div className="awaiting-title">⏸ Awaiting your decision</div>
        <div className="awaiting-summary">{awaiting.summary}</div>
        <div className="awaiting-meta">
          {awaiting.todoSteps} steps taken · soft threshold {awaiting.softThreshold}
        </div>
      </div>
      <div className="awaiting-options">
        {awaiting.options.map((opt) => (
          <button
            key={opt.letter}
            type="button"
            className={`awaiting-option-card awaiting-option-${opt.letter.toLowerCase()}`}
            onClick={() => onPick(opt)}
            title={opt.description}
          >
            <div className="awaiting-option-letter">{opt.letter}</div>
            <div className="awaiting-option-text">
              <div className="awaiting-option-label">{opt.label}</div>
              <div className="awaiting-option-desc">{opt.description}</div>
            </div>
          </button>
        ))}
      </div>
      <div className="awaiting-actions">
        <button
          className="awaiting-btn primary"
          onClick={onContinue}
          title="Tell the LLM to keep going with the current plan"
        >
          Continue
        </button>
        <button
          className="awaiting-btn"
          onClick={onChange}
          title="Dismiss; type your new direction in the input box and send"
        >
          Change direction
        </button>
        <button
          className="awaiting-btn danger"
          onClick={onAbort}
          title="Dismiss without sending anything"
        >
          Abort
        </button>
      </div>
    </div>
  );
}
