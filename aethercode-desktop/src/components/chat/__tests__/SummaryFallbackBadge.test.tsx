// @vitest-environment jsdom
import { describe, it, expect } from 'vitest';
import { screen, render } from '@testing-library/react';
import { SummaryFallbackBadge } from '../SummaryFallbackBadge';
import { autoCleanup } from '../../../test/testUtils';

autoCleanup();

describe('Phase 7 / T-7-04: SummaryFallbackBadge', () => {
  it('renders nothing when the summary is null', () => {
    render(<SummaryFallbackBadge summary={null} />);
    expect(screen.queryByTestId('summary-fallback-badge')).toBeNull();
  });

  it('renders nothing when the summary is not a fallback', () => {
    render(<SummaryFallbackBadge summary={{ text: 'ok', fallback: false }} />);
    expect(screen.queryByTestId('summary-fallback-badge')).toBeNull();
  });

  it('renders the badge when the summary is a fallback', () => {
    render(<SummaryFallbackBadge summary={{ text: 'synthesised', fallback: true }} />);
    const badge = screen.getByTestId('summary-fallback-badge');
    expect(badge.textContent).toContain('auto-summary failed');
  });

  it('sets the title attribute so a hover explains the badge', () => {
    render(<SummaryFallbackBadge summary={{ text: 'synthesised', fallback: true }} />);
    const badge = screen.getByTestId('summary-fallback-badge');
    expect(badge.getAttribute('title')).toContain('## Summary');
  });
});
