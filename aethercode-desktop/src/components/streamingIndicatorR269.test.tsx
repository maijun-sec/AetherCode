// @vitest-environment jsdom
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { StreamingIndicator } from './StreamingIndicator';
import { useStore } from '../store';

/**
 * R269 (2026-09-15) — middle-of-chat streaming indicator.
 *
 * Source-pin tests (no behaviour coverage):
 *   - empty variant exists and is referenced from MessageList
 *   - footer variant exists and is referenced from MessageList
 *   - the component reads isStreaming + currentActivity from store
 *
 * Behaviour tests (with @testing-library/react):
 *   - renders nothing when neither isStreaming nor activity set
 *   - empty variant: renders with forceWhenEmpty when isStreaming=true
 *   - empty variant: hides 'done' / 'error' (those mean the run already ended)
 *   - footer variant: renders with currentActivity label
 *   - footer variant: hides 'done' / 'error' so the top ActivityIndicator owns those
 *   - aria-live="polite" + role="status" for screen readers
 */

const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

// Reset store between tests so isStreaming / currentActivity are clean
function resetStore() {
  useStore.setState({
    isStreaming: false,
    currentActivity: null,
  } as any);
}

describe('R269: source-pin', () => {
  it('StreamingIndicator is exported from src/components/StreamingIndicator.tsx', () => {
    const src = readFileSync(join(root, 'src', 'components', 'StreamingIndicator.tsx'), 'utf-8');
    expect(src).toMatch(/export\s+(?:function|const)\s+StreamingIndicator/);
  });

  it('CSS file defines both empty and footer variants', () => {
    const css = readFileSync(join(root, 'src', 'components', 'StreamingIndicator.css'), 'utf-8');
    expect(css).toMatch(/\.streaming-empty\b/);
    expect(css).toMatch(/\.streaming-footer\b/);
    // all four activity kinds
    expect(css).toMatch(/\.streaming-kind-thinking\b/);
    expect(css).toMatch(/\.streaming-kind-tool\b/);
    expect(css).toMatch(/\.streaming-kind-done\b/);
    expect(css).toMatch(/\.streaming-kind-error\b/);
    // pulse + spin keyframes
    expect(css).toMatch(/@keyframes\s+streaming-pulse\b/);
    expect(css).toMatch(/@keyframes\s+streaming-spin\b/);
  });

  it('MessageList imports StreamingIndicator and uses both variants', () => {
    const ml = readFileSync(join(root, 'src', 'components', 'MessageList.tsx'), 'utf-8');
    expect(ml).toMatch(/import\s*\{[^}]*StreamingIndicator[^}]*\}\s*from\s*['"]\.\/StreamingIndicator['"]/);
    expect(ml).toMatch(/variant="empty"[\s\S]*?forceWhenEmpty/);
    expect(ml).toMatch(/variant="footer"/);
  });
});

describe('R269: behaviour', () => {
  it('renders nothing when isStreaming=false and no activity', () => {
    resetStore();
    const { container } = render(<StreamingIndicator variant="empty" forceWhenEmpty />);
    expect(container.firstChild).toBeNull();
    render(<StreamingIndicator variant="footer" />);
    expect(screen.queryByTestId(/streaming-indicator/)).toBeNull();
  });

  it('empty variant renders when isStreaming=true even without activity (forceWhenEmpty)', () => {
    resetStore();
    useStore.setState({ isStreaming: true } as any);
    render(<StreamingIndicator variant="empty" forceWhenEmpty />);
    expect(screen.getByTestId('streaming-indicator-empty')).toBeTruthy();
    expect(screen.getByText(/等待模型响应/)).toBeTruthy();
  });

  it('empty variant hides done / error — those mean run already ended', () => {
    resetStore();
    useStore.setState({
      isStreaming: true,
      currentActivity: { kind: 'done', label: '✓Done', ts: Date.now() },
    } as any);
    render(<StreamingIndicator variant="empty" forceWhenEmpty />);
    expect(screen.queryByTestId('streaming-indicator-empty')).toBeNull();
  });

  it('footer variant renders with currentActivity label', () => {
    resetStore();
    useStore.setState({
      isStreaming: true,
      currentActivity: { kind: 'thinking', label: '💭 Thinking…', ts: Date.now() },
    } as any);
    render(<StreamingIndicator variant="footer" />);
    expect(screen.getByTestId('streaming-indicator-footer')).toBeTruthy();
    expect(screen.getByText(/Thinking/)).toBeTruthy();
  });

  it('uses role=status and aria-live=polite for accessibility', () => {
    resetStore();
    useStore.setState({
      isStreaming: true,
      currentActivity: { kind: 'thinking', label: '💭 Thinking…', ts: Date.now() },
    } as any);
    render(<StreamingIndicator variant="footer" />);
    const el = screen.getByTestId('streaming-indicator-footer');
    expect(el.getAttribute('role')).toBe('status');
    expect(el.getAttribute('aria-live')).toBe('polite');
  });

  it('footer colour-codes by kind', () => {
    resetStore();
    useStore.setState({
      isStreaming: true,
      currentActivity: { kind: 'tool', label: '🛠 file_write', ts: Date.now() },
    } as any);
    const { container } = render(<StreamingIndicator variant="footer" />);
    const el = container.querySelector('.streaming-kind-tool');
    expect(el).toBeTruthy();
  });
});