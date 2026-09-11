// @vitest-environment jsdom
import { describe, it, expect, afterEach } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';

afterEach(() => { cleanup(); });

describe('cleanup test', () => {
  it('first', () => {
    render(<div data-testid="x">a</div>);
    expect(screen.getByTestId('x').textContent).toBe('a');
  });
  it('second', () => {
    render(<div data-testid="x">b</div>);
    expect(screen.getByTestId('x').textContent).toBe('b');
  });
  it('third finds one', () => {
    render(<div data-testid="x">c</div>);
    expect(screen.getAllByTestId('x').length).toBe(1);
  });
});
