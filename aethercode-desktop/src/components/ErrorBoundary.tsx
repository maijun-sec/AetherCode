import { Component, type ErrorInfo, type ReactNode } from 'react';
import './ErrorBoundary.css';

/**
 * top-level React error boundary.
 *
 * <p>Motivation. R179 was a best-guess fix for the "blank screen on
 * submit" bug, but a real React render error in any sub-component
 * still produces a totally blank center column with no diagnostic
 * for the user or for us. A typical cause is a thrown exception in
 * the message / step / sub-task render path (e.g. malformed
 * tool-call JSON, unexpected null in a markdown prop, etc.) — the
 * React 19 root unmounts the offending subtree and the rest of the
 * column appears empty.
 *
 * <p>legacy the only recourse was `webview2 --remote-debugging-port`
 * + the JS console. R180 wraps the chat surface (and optionally the
 * whole shell) with a class-component error boundary that:
 *
 * <ol>
 *   <li>Catches the error, logs it to the console with full stack
 *       and a marker tag so the user can find it in DevTools.</li>
 *   <li>Renders a compact, non-scary fallback: a single danger-tinted
 *       card with the error message, a "复制错误 (Copy error)" button (writes
 *       the error + stack to the clipboard for reporting), and a
 *       "重试 (Retry)" button that resets the boundary state so the chat
 *       tries to re-render (handy for transient bugs).</li>
 *   <li>Does NOT swallow the error silently — it always re-throws
 *       the original exception into the global error handler so
 *       future crash reporters / Sentry hooks can pick it up.</li>
 * </ol>
 *
 * <p>Source-pin contract: the export name {@code ErrorBoundary} and
 * the literal "重试" (Retry) button label are referenced by
 * {@code errorBoundaryR180.test.ts}. Renaming either one will fail
 * the regression test.
 */
interface ErrorBoundaryProps {
  children: ReactNode;
  /** Optional label shown in the fallback header, e.g. "Chat 列表 (Chat list)".
   *  Defaults to "该区域 (this area)". */
  label?: string;
  /** Optional click handler invoked when the user clicks "重试 (Retry)".
   *  Defaults to an in-component reset that re-renders children. */
  onReset?: () => void;
}

interface ErrorBoundaryState {
  error: Error | null;
  /** Counter that bumps on each reset so React re-mounts the
   *  children even if the error keeps happening on the same props. */
  resetKey: number;
}

export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = { error: null, resetKey: 0 };

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { error, resetKey: 0 };
  }

  override componentDidCatch(error: Error, info: ErrorInfo): void {
    // Tag the log so it's easy to find in DevTools. R180's source
    // contract is "R180 ErrorBoundary" — the regression test
    // greps for the prefix to confirm the boundary fired.
    // eslint-disable-next-line no-console
    console.error('[R180 ErrorBoundary] caught render error', {
      label: this.props.label,
      error,
      componentStack: info.componentStack,
    });
    // Re-throw on the next tick so global error reporters
    // (window.onerror, Sentry, ...) still see the original.
    // We use queueMicrotask to avoid React's "already in render
    // phase" warning, which itself would just re-trigger this
    // boundary.
    queueMicrotask(() => {
      setTimeout(() => {
        throw error;
      }, 0);
    });
  }

  private handleReset = (): void => {
    if (this.props.onReset) {
      this.props.onReset();
      return;
    }
    this.setState((s) => ({ error: null, resetKey: s.resetKey + 1 }));
  };

  private handleCopy = async (): Promise<void> => {
    const { error } = this.state;
    if (!error) return;
    const lines = [
      `R180 ErrorBoundary (${this.props.label ?? '未知区域'})`,
      `time: ${new Date().toISOString()}`,
      `message: ${error.message}`,
      `stack: ${error.stack ?? '(no stack)'}`,
    ];
    const text = lines.join('\n');
    try {
      if (navigator?.clipboard?.writeText) {
        await navigator.clipboard.writeText(text);
      } else {
        // Fallback for non-secure contexts (Tauri webview is secure
        // but be defensive).
        const ta = document.createElement('textarea');
        ta.value = text;
        ta.style.position = 'fixed';
        ta.style.opacity = '0';
        document.body.appendChild(ta);
        ta.focus();
        ta.select();
        document.execCommand('copy');
        document.body.removeChild(ta);
      }
    } catch {
      // Best-effort; ignore clipboard failure.
    }
  };

  override render(): ReactNode {
    const { error, resetKey } = this.state;
    if (!error) {
      // Re-mount children on reset by giving them a key.
      return <div key={resetKey} className="error-boundary-children">{this.props.children}</div>;
    }
    return (
      <div className="error-boundary-fallback" role="alert" aria-live="assertive">
        <div className="error-boundary-card">
          <div className="error-boundary-icon" aria-hidden="true">⚠</div>
          <div className="error-boundary-title">
            {this.props.label ?? '该区域'} 渲染出错
          </div>
          <div className="error-boundary-message">{error.message}</div>
          {error.stack && (
            <pre className="error-boundary-stack" tabIndex={0}>
              {error.stack.split('\n').slice(0, 8).join('\n')}
            </pre>
          )}
          <div className="error-boundary-actions">
            <button
              type="button"
              className="error-boundary-btn error-boundary-btn-primary"
              onClick={this.handleReset}
            >
              重试
            </button>
            <button
              type="button"
              className="error-boundary-btn"
              onClick={() => void this.handleCopy()}
            >
              复制错误
            </button>
          </div>
          <div className="error-boundary-hint">
            已记录到控制台（F12 / DevTools "Console"），可粘贴到 issue 反馈。
          </div>
        </div>
      </div>
    );
  }
}
