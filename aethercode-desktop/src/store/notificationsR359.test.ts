import { describe, it, expect } from 'vitest';
import { readFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * R359 desktop polish (2026-09-26): move the
 * "transient system health" notifications (most
 * importantly `[Stream stale]`) out of the `messages`
 * array and into a separate `notifications: Notification[]`
 * stack rendered by <NotificationCenter />.
 *
 * <p>Why source-pin tests instead of full
 * unit tests:
 * <ol>
 *   <li>The pre-R359 behaviour was a permanent
 *       `messages.push({ role: 'system', isError: true })`
 *       so a regression in the watchdog path is
 *       <em>silent</em> — no smoke test catches a
 *       missing set() in the setInterval callback.</li>
 *   <li>Source-pin tests verify the contract
 *       directly: the watchdog calls `pushNotification`,
 *       the AppState declares the new field, and the
 *       App.tsx renders the component. If any of those
 *       change, these tests fail.</li>
 * </ol>
 *
 * <p>Refs: PM eval at
 * {@code D:\tmp\senior-pm-review\aethercode-stream-stale-banner-review-2026-09-26.md}
 * — flagged `[Stream stale]` as P0 because it stuck
 * forever, had no × button, and visually conflated
 * "transient health warning" with "task failure".
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

function readSrc(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

describe('R359: transient notification stack (replaces [Stream stale] permanent system message)', () => {
  it('declares Notification types', () => {
    const src = readSrc('src/store/index.ts');
    // The Notification interface + NotificationLevel union
    // + NotificationAction interface. Without these the
    // AppState declarations and the watchdog impl have
    // nowhere to reference.
    expect(src).toMatch(/export\s+type\s+NotificationLevel\s*=\s*'info'\s*\|\s*'warning'\s*\|\s*'error'/);
    expect(src).toMatch(/export\s+interface\s+NotificationAction\b/);
    expect(src).toMatch(/export\s+interface\s+Notification\b/);
  });

  it('AppState declares notifications + 3 actions', () => {
    const src = readSrc('src/store/index.ts');
    // notifications array
    expect(src).toMatch(/notifications:\s*Notification\[\]/);
    // push / dismiss / clearBySource
    expect(src).toMatch(/pushNotification:\s*\(n:\s*Omit<Notification,\s*'id'\s*\|\s*'createdAt'>\)\s*=>\s*string/);
    expect(src).toMatch(/dismissNotification:\s*\(id:\s*string\)\s*=>\s*void/);
    expect(src).toMatch(/clearNotificationsBySource:\s*\(source:\s*string\)\s*=>\s*void/);
  });

  it('initial state seeds notifications: []', () => {
    const src = readSrc('src/store/index.ts');
    // Without this the very first render of <NotificationCenter />
    // would `notifications.length` on `undefined` and crash.
    expect(src).toMatch(/notifications:\s*\[\]/);
  });

  it('watchdog uses pushNotification instead of messages.push', () => {
    const src = readSrc('src/store/index.ts');
    // The watchdog fires inside the streaming-stale setInterval.
    // The old code pushed a permanent system message:
    //     messages: [...cur.messages, { role: 'system', content: '[Stream stale] ...' }]
    // The new code calls `pushNotification({ source: 'stream-stale', ... })`.
    // Both paths live in the watchdog — verify the new path
    // is present and the old path is gone.
    const watchdogBlock = src.match(/Streaming-stale watchdog[\s\S]*?STREAM_CHECK_INTERVAL_MS\s*\)/);
    expect(watchdogBlock, 'watchdog block must exist').toBeTruthy();
    const block = watchdogBlock![0];
    expect(block, 'watchdog must call pushNotification').toMatch(/pushNotification\s*\(\s*\{/);
    expect(block, 'watchdog must NOT push [Stream stale] to messages').not.toMatch(/role:\s*'system'[\s\S]{0,200}Stream stale/);
    expect(block, 'watchdog source tag must be stream-stale').toMatch(/source:\s*'stream-stale'/);
    expect(block, 'watchdog must keep force-ending the stream').toMatch(/isStreaming:\s*false/);
  });

  it('sendMessage clears stream-stale notifications at the top', () => {
    const src = readSrc('src/store/index.ts');
    // The user reported the banner stayed forever even
    // after they moved on. The fix: when the user sends
    // a new prompt, drop any stale stream-stale
    // notification. This is the natural "I'm sending
    // again, the old warning is moot" affordance.
    expect(
      src,
      'sendMessage must call clearNotificationsBySource(stream-stale) before the isStreaming guard',
    ).toMatch(/sendMessage:\s*async\s*\(\)\s*=>\s*\{[\s\S]{0,800}clearNotificationsBySource\(\s*'stream-stale'\s*\)/);
  });

  it('cancelQuery clears stream-stale notifications', () => {
    const src = readSrc('src/store/index.ts');
    // Cancel = "stop everything". Same UX rationale as
    // sendMessage: a stream-stale notification from the
    // run that was just cancelled is no longer relevant.
    expect(
      src,
      'cancelQuery must call clearNotificationsBySource(stream-stale)',
    ).toMatch(/cancelQuery:\s*async\s*\(\)\s*=>\s*\{[\s\S]{0,800}clearNotificationsBySource\(\s*'stream-stale'\s*\)/);
  });

  it('switchSession clears stream-stale notifications', () => {
    const src = readSrc('src/store/index.ts');
    // Switching to a different session = fresh start.
    // The previous session's stream-stale notification
    // would otherwise attach to the new chat history.
    expect(
      src,
      'switchSession must call clearNotificationsBySource(stream-stale)',
    ).toMatch(/switchSession:\s*async\s*\(\s*sessionId:\s*string\s*\)\s*=>\s*\{[\s\S]{0,800}clearNotificationsBySource\(\s*'stream-stale'\s*\)/);
  });

  it('NotificationCenter component exists and is mounted in App.tsx', () => {
    const componentPath = join(root, 'src/components/NotificationCenter.tsx');
    const cssPath = join(root, 'src/components/NotificationCenter.css');
    expect(existsSync(componentPath), 'NotificationCenter.tsx must exist').toBe(true);
    expect(existsSync(cssPath), 'NotificationCenter.css must exist').toBe(true);

    const componentSrc = readFileSync(componentPath, 'utf-8');
    expect(componentSrc).toMatch(/export\s+function\s+NotificationCenter\b/);
    // Must read notifications + dismiss from the store
    expect(componentSrc).toMatch(/notifications\s*=\s*useStore\(\(s\)\s*=>\s*s\.notifications\)/);
    expect(componentSrc).toMatch(/dismissNotification/);
    // × button must exist (P0 #2 fix)
    expect(componentSrc).toMatch(/notification-close|aria-label="Dismiss/);
    // Action buttons must exist (PM plan)
    expect(componentSrc).toMatch(/notification-actions/);

    const appSrc = readSrc('src/App.tsx');
    expect(appSrc, 'App.tsx must import NotificationCenter').toMatch(/import\s+\{\s*NotificationCenter\s*\}\s+from\s+'\.\/components\/NotificationCenter'/);
    expect(appSrc, 'App.tsx must render <NotificationCenter />').toMatch(/<NotificationCenter\s*\/>/);
  });

  it('MessageList no longer treats stream-stale as a permanent system message', () => {
    // The MessageList.tsx system-message branch (line ~953-969)
    // is still used for OTHER system messages (e.g. log_handler
    // log messages). What we DON'T want is for it to render the
    // legacy "[Stream stale] Daemon stopped responding for 90s"
    // content, because the watchdog no longer pushes that into
    // `messages`. Source-pin: that string literal must be gone
    // from MessageList.tsx (it's now in NotificationCenter.tsx
    // and the watchdog's pushNotification call).
    const mlSrc = readSrc('src/components/MessageList.tsx');
    expect(
      mlSrc,
      'MessageList must not embed the literal [Stream stale] Daemon stopped responding copy',
    ).not.toMatch(/\[Stream stale\]\s*Daemon stopped responding/);
  });
});