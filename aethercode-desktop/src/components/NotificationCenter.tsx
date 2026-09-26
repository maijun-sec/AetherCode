import { useEffect } from 'react';
import { useStore } from '../store';
import type { Notification, NotificationAction } from '../store';
import './NotificationCenter.css';

// NotificationCenter — R359.
//
// Renders the transient notification stack as a vertical
// column of toasts anchored to the bottom-right of the
// viewport (above the StatusBar, mirroring SubagentToast's
// position so the two don't fight each other for the same
// slot — we sit ~88px above the StatusBar so they stack
// with a clean gap).
//
// Each toast exposes:
//   - a tone icon (info/warning/error)
//   - title + optional detail line
//   - optional action buttons (Retry / Cancel / Dismiss …)
//   - an explicit × button (focusable, aria-labelled)
//
// Why this exists:
//   The pre-R359 implementation pushed "[Stream stale]" /
//   "[Reconnect failed]" / "[Delete session failed]" etc.
//   as permanent system messages (store/index.ts:7744-7751
//   prior, MessageList.tsx:953-969). The PM eval
//   (D:\tmp\senior-pm-review\aethercode-stream-stale-banner-review-2026-09-26.md)
//   flagged this as P0:
//     1. Banner sticks to chat forever (no ×)
//     2. No dismiss affordance at all
//     3. Visually identical to "task failed" (message-system-error)
//   This component is the Phase-1 fix: transient, dismissable,
//   visually separate from the conversation history.
//
// Phase 2 (out of scope here) will introduce a state-machine
// + daemon-side heartbeat so the watchdog itself becomes
// smarter (30s yellow → 90s red → 恢复 green). For now we
// only fix the surfacing — Phase 2 is filed as a follow-up.

function toneIcon(level: Notification['level']): string {
  if (level === 'error') return '✕';
  if (level === 'warning') return '⚠';
  return 'ℹ';
}

function variantClass(variant: NotificationAction['variant'] | undefined): string {
  if (variant === 'primary') return 'notification-action-primary';
  return 'notification-action-secondary';
}

export function NotificationCenter() {
  const notifications = useStore((s) => s.notifications);
  const dismiss = useStore((s) => s.dismissNotification);

  // Esc hotkey to dismiss the focused / most-recent toast.
  // Only mounted while at least one notification is showing
  // so we don't intercept Esc when the user is typing in
  // the message box or navigating pickers.
  useEffect(() => {
    if (notifications.length === 0) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      // Don't fight the modal escape handlers — leave
      // modals / pickers alone. We only handle the
      // notification case.
      const target = e.target;
      if (target instanceof HTMLElement) {
        const tag = target.tagName;
        if (tag === 'INPUT' || tag === 'TEXTAREA' || target.isContentEditable) return;
      }
      const last = notifications[notifications.length - 1];
      if (last) {
        e.preventDefault();
        dismiss(last.id);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [notifications, dismiss]);

  if (notifications.length === 0) return null;

  return (
    <div className="notification-center" role="region" aria-label="Notifications">
      {notifications.map((n) => (
        <div
          key={n.id}
          className={`notification notification-${n.level}`}
          role={n.level === 'error' ? 'alert' : 'status'}
          aria-live={n.level === 'error' ? 'assertive' : 'polite'}
        >
          <span className="notification-icon" aria-hidden="true">{toneIcon(n.level)}</span>
          <div className="notification-body">
            <div className="notification-title">{n.title}</div>
            {n.message && <div className="notification-message">{n.message}</div>}
            {n.actions && n.actions.length > 0 && (
              <div className="notification-actions">
                {n.actions.map((a, i) => (
                  <button
                    key={i}
                    className={`notification-action ${variantClass(a.variant)}`}
                    onClick={() => {
                      try { a.onClick(); } catch (e) { console.warn('[NotificationCenter] action failed:', e); }
                    }}
                  >
                    {a.label}
                  </button>
                ))}
              </div>
            )}
          </div>
          <button
            className="notification-close"
            aria-label="Dismiss notification"
            title="Dismiss (Esc)"
            onClick={() => dismiss(n.id)}
          >
            ×
          </button>
        </div>
      ))}
    </div>
  );
}