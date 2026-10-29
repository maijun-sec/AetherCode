// Copyright (c) 2026 maijun

// Licensed under the MIT License.
// See the LICENSE file in the project root for the full license text.

// R713 (2026-10-18): React error boundary. Catches a render-
// time exception anywhere in the wrapped tree and shows a
// recoverable fallback instead of letting the exception
// propagate up to React's root, which would unmount the
// entire tree. A single bad message (e.g. a future renderer
// regression on a payload the cap helpers haven't covered
// yet) used to white-screen the chat panel until the user
// closed + reopened the session. The boundary contains the
// blast radius to the offending subtree.
//
// The boundary is *defensive*: a real fix for a render
// error is to also report it (e.g. via Sentry), but the
// desktop doesn't ship a reporter yet. The boundary at
// least guarantees the user can keep working — they see a
// banner pointing at the bad message id, the rest of the
// chat stays usable, and the desktop process doesn't have
// to be restarted.
//
// R180 source contract (kept verbatim so the
// errorBoundaryR180.test.ts source-pin tests still pass):
//   - The fallback must render a button labeled "重试".
//     Renaming the label will break UX expectations and
//     the regression test will catch it.
//   - The componentDidCatch console.error must include
//     the "R180 ErrorBoundary" marker so a real crash in
//     production is easy to find in DevTools.

import { Component, type ErrorInfo, type ReactNode } from 'react';

interface ErrorBoundaryProps {
  children: ReactNode;
  /** Where in the UI the boundary sits. Used in the
   *  fallback banner so the user knows which panel broke. */
  label: string;
  /** Optional callback so the parent can log the
   *  error elsewhere (e.g. into the in-memory event log). */
  onError?: (error: Error, info: ErrorInfo) => void;
}

interface ErrorBoundaryState {
  error: Error | null;
}

export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = { error: null };

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    // R180 source contract (regression test pins this
    // marker). We also log the panel label so the user can
    // tell which boundary fired when several are mounted.
    // eslint-disable-next-line no-console
    console.error('R180 ErrorBoundary', this.props.label, error, info.componentStack);
    this.props.onError?.(error, info);
  }

  /** R180 contract: the retry button is the user's escape
   *  hatch when a renderer crashes. Clicking it resets the
   *  state to { error: null } so React re-renders the
   *  children. If the underlying renderer is still broken
   *  the boundary will fire again immediately, but the user
   *  gets a clear "try again" affordance instead of staring
   *  at a frozen fallback. */
  private handleRetry = () => {
    this.setState({ error: null });
  };

  render(): ReactNode {
    if (this.state.error) {
      // R713: keep the fallback cheap. A real React error
      // message is already long; we don't want to mount a
      // 100 KB stack trace into a fresh tree that will
      // also need to re-render on every keystroke.
      return (
        <div className="error-boundary-fallback" role="alert">
          <div className="error-boundary-icon" aria-hidden>⚠</div>
          <div className="error-boundary-title">{this.props.label} 渲染出错</div>
          <div className="error-boundary-message">
            {this.state.error.message || String(this.state.error)}
          </div>
          <div className="error-boundary-hint">
            其它面板仍可使用。这个错误是单条消息的渲染异常，不影响整桌面。
            点击下面按钮可以重试渲染，如果问题仍然存在，下次会再触发此 banner。
          </div>
          <button
            type="button"
            className="error-boundary-retry"
            onClick={this.handleRetry}
          >重试</button>
        </div>
      );
    }
    return this.props.children;
  }
}
