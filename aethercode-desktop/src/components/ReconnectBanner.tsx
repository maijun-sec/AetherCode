import { useStore } from '../store';
import './ReconnectBanner.css';

// Persistent banner shown at the top of the center pane whenever the
// daemon connection is not healthy. Covers three cases:
//   - 'connecting'  : first load (Welcome screen already shows, so
//                     we suppress to avoid double-banners)
//   - 'reconnecting': daemon died, auto-retrying with backoff
//   - 'error'       : max reconnect attempts exhausted; user must act
//
// when `cwdSwitchInProgress` is set, the banner shows
// phase-specific messages ("killing old daemon", "starting JVM",
// "loading state") so the user understands the 1-2s lag is
// intentional JVM startup, not a hang.
//
// The banner sits ABOVE the message list (App.tsx renders it as a
// sibling of <Welcome>/<MessageList> inside <main className="center">)
// so it's always visible regardless of which view is active.

const PHASE_LABELS: Record<string, string> = {
  'killing-old':    'killing old daemon…',
  'spawning-jvm':   'starting JVM (this takes ~1-2s)…',
  'health-check':   'waiting for daemon health…',
  'loading-state':  'loading engine state…',
};

function shortenPath(p: string): string {
  if (p.length <= 40) return p;
  return '…' + p.slice(p.length - 39);
}

export function ReconnectBanner() {
  const {
    connectionState, reconnectAttempts, initError, initialize,
    cwdSwitchInProgress, cwdSwitchTarget, transitionPhase,
  } = useStore();

  if (connectionState === 'connected' || connectionState === 'idle') return null;
  // 'connecting' on first load: let <Welcome> handle it.
  if (connectionState === 'connecting' && reconnectAttempts === 0 && !initError) return null;
  // 'awaiting-cwd' is a quiet state — the Welcome
  // component owns the user-facing copy ("打开文件夹" tile)
  // and we don't want a reconnecting banner fighting it
  // for attention. The user already knows the daemon
  // isn't running because there is no project yet.
  if (connectionState === 'awaiting-cwd') return null;

  const isError = connectionState === 'error';
  const isConnecting = connectionState === 'connecting' && reconnectAttempts > 0;
  const isReconnecting = connectionState === 'reconnecting' || isConnecting;

  let label = '';
  let detail = '';
  if (isError) {
    label = '⚠ Disconnected';
    detail = initError
      ? `${reconnectAttempts} reconnect attempts failed. Last error: ${initError}`
      : `${reconnectAttempts} reconnect attempts failed.`;
  } else if (cwdSwitchInProgress) {
    // User-initiated cwd switch: show the target + current phase
    // so the latency is explainable, not mysterious.
    const target = cwdSwitchTarget ? shortenPath(cwdSwitchTarget) : 'new path';
    label = `📂 Switching to ${target}`;
    detail = PHASE_LABELS[transitionPhase] ?? 'working…';
  } else if (isReconnecting) {
    label = reconnectAttempts > 0
      ? `↻ Reconnecting (attempt ${reconnectAttempts})…`
      : '↻ Connecting to daemon…';
    detail = initError ? `Last error: ${initError}` : '';
  } else {
    label = `Connection: ${connectionState}`;
  }

  return (
    <div className={`reconnect-banner ${isError ? 'reconnect-banner-error' : 'reconnect-banner-warn'}`}>
      <div className="reconnect-banner-text">
        <span className="reconnect-banner-label">{label}</span>
        {detail && <span className="reconnect-banner-detail">{detail}</span>}
      </div>
      <div className="reconnect-banner-actions">
        {isError ? (
          <button
            className="reconnect-banner-btn"
            onClick={() => window.location.reload()}
            title="Reload the renderer (Cmd/Ctrl+R equivalent)"
          >
            Reload
          </button>
        ) : !cwdSwitchInProgress ? (
          <button
            className="reconnect-banner-btn"
            onClick={() => { void initialize(); }}
            title="Retry connection now (skip backoff)"
          >
            Retry now
          </button>
        ) : null}
      </div>
    </div>
  );
}
