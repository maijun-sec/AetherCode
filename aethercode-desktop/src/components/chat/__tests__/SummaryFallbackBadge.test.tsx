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
