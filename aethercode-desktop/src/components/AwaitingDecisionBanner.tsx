import { useStore } from '../store';
import './AwaitingDecisionBanner.css';

// shown above the message list when the per-todo controller
// has hit its max LLM bumps and is asking the user to decide
// whether to continue, abort, or change direction. The user can:
//   - type a message in the input box and send — that becomes
//     the continuation decision and the engine resumes.
//   - click "Abort" to dismiss the prompt without sending
//     anything (the engine stays paused).
//   - click "Continue" to send a canned "continue" message
//     that tells the LLM to keep going with the current
//     strategy.

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

  return (
    <div className="awaiting-banner" role="alert">
      <div className="awaiting-text">
        <div className="awaiting-title">⏸ Awaiting your decision</div>
        <div className="awaiting-summary">{awaiting.summary}</div>
        <div className="awaiting-meta">
          {awaiting.todoSteps} steps taken · soft threshold {awaiting.softThreshold}
        </div>
      </div>
      <div className="awaiting-actions">
        <button className="awaiting-btn primary" onClick={onContinue} title="Tell the LLM to keep going with the current plan">
          Continue
        </button>
        <button className="awaiting-btn" onClick={onChange} title="Dismiss; type your new direction in the input box and send">
          Change direction
        </button>
        <button className="awaiting-btn danger" onClick={onAbort} title="Dismiss without sending anything">
          Abort
        </button>
      </div>
    </div>
  );
}
