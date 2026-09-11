// @vitest-environment jsdom
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { AppProvider, appReducer, INITIAL_STATE, useApp, useAppSelector } from '../AppContext';
import { autoCleanup } from '../../test/testUtils';

autoCleanup();

describe('Phase 3 / T-3-07: AppContext', () => {
  beforeEach(() => {
    // The provider persists state to localStorage. Wipe between
    // tests so the hydration path doesn't leak across cases.
    if (typeof window !== 'undefined' && window.localStorage) {
      window.localStorage.clear();
    }
  });

  afterEach(() => {
    if (typeof window !== 'undefined' && window.localStorage) {
      window.localStorage.clear();
    }
  });

  it('initial state matches the documented defaults', () => {
    expect(INITIAL_STATE.currentSessionId).toBeNull();
    expect(INITIAL_STATE.theme).toBe('light');
    expect(INITIAL_STATE.permissionPreset).toBe('cautious');
    expect(INITIAL_STATE.autoScroll).toBe(true);
    expect(INITIAL_STATE.detailsDrawerOpen).toBe(false);
    expect(INITIAL_STATE.trashDrawerOpen).toBe(false);
  });

  it('useApp throws when used outside a provider', () => {
    expect(() => renderHook(() => useApp())).toThrow(/AppProvider/);
  });

  it('reducer handles every action type without mutating input', () => {
    const next = appReducer(INITIAL_STATE, { type: 'set-current-session', id: 's-1' });
    expect(next).not.toBe(INITIAL_STATE);
    expect(next.currentSessionId).toBe('s-1');
    expect(INITIAL_STATE.currentSessionId).toBeNull();
  });

  it('useApp returns setters that update the slice and persist', () => {
    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <AppProvider persistKey="app-test">{children}</AppProvider>
    );
    const { result, rerender } = renderHook(() => useApp(), { wrapper });
    act(() => result.current.setTheme('dark'));
    rerender();
    expect(result.current.theme).toBe('dark');
    expect(result.current.setPermissionPreset).toBeDefined();
    act(() => result.current.setPermissionPreset('strict'));
    rerender();
    expect(result.current.permissionPreset).toBe('strict');
  });

  it('hydrates from localStorage on mount when a persisted state exists', () => {
    window.localStorage.setItem(
      'aethercode.appState',
      JSON.stringify({ theme: 'high-contrast', permissionPreset: 'strict' }),
    );
    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <AppProvider persistKey="aethercode.appState">{children}</AppProvider>
    );
    const { result } = renderHook(() => useApp(), { wrapper });
    expect(result.current.theme).toBe('high-contrast');
    expect(result.current.permissionPreset).toBe('strict');
  });

  it('useAppSelector returns only the selected slice', () => {
    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <AppProvider persistKey={false}>{children}</AppProvider>
    );
    const { result } = renderHook(() => useAppSelector((s) => s.theme), { wrapper });
    expect(result.current).toBe('light');
  });

  it('hydrate action merges the partial state without dropping keys', () => {
    const next = appReducer(
      { ...INITIAL_STATE, theme: 'dark' },
      { type: 'hydrate', state: { permissionPreset: 'strict' } },
    );
    expect(next.theme).toBe('dark');
    expect(next.permissionPreset).toBe('strict');
  });
});
