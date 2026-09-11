// Phase 3: top-level app state (T-3-07).
//
// The brief specifies four pieces of state:
//
//   - currentSessionId   (string | null) — the active session
//   - theme              ('light' | 'dark' | 'solarized-light' | 'solarized-dark' | 'high-contrast')
//   - permissionPreset   ('permissive' | 'cautious' | 'strict')
//
// We also carry a few derived helpers (`taskControl`, `selectedModelId`,
// `rightPanelTab`) so the rest of the tree doesn't have to thread
// props. The reducer is intentionally narrow — anything that needs
// server state lives in TanStack Query, not here.

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useReducer,
  type ReactNode,
} from 'react';
import type { PermissionPreset } from '../rpc/types';

export type ThemeName =
  | 'light'
  | 'dark'
  | 'solarized-light'
  | 'solarized-dark'
  | 'high-contrast';

export type RightPanelTab =
  | 'telemetry'
  | 'todos'
  | 'tokens'
  | 'details'
  | 'subagents';

export interface AppState {
  currentSessionId: string | null;
  theme: ThemeName;
  permissionPreset: PermissionPreset;
  selectedModelId: string | null;
  rightPanelTab: RightPanelTab;
  /** Tristate: the session is `running` / `paused` / `completed`. The
   *  SessionControl reads this to decide which buttons to enable. */
  currentTaskState: 'running' | 'paused' | 'completed' | 'failed' | 'cancelled' | 'unknown';
  /** Tristate: 'idle' when no model call is in flight; the rest mirror
   *  the ChatView's auto-scroll behaviour. */
  streaming: boolean;
  /** Auto-scroll lock — when the user has scrolled up to read older
   *  messages, we stop yanking the view to the bottom on new chunks. */
  autoScroll: boolean;
  /** Consent modal visibility (10-option dialog). */
  consentRequestId: string | null;
  /** Drawer visibility for the session details panel. */
  detailsDrawerOpen: boolean;
  /** Trash view visibility (full page or side drawer). */
  trashDrawerOpen: boolean;
}

export const INITIAL_STATE: AppState = {
  currentSessionId: null,
  theme: 'light',
  permissionPreset: 'cautious',
  selectedModelId: null,
  rightPanelTab: 'telemetry',
  currentTaskState: 'unknown',
  streaming: false,
  autoScroll: true,
  consentRequestId: null,
  detailsDrawerOpen: false,
  trashDrawerOpen: false,
};

export type AppAction =
  | { type: 'set-current-session'; id: string | null }
  | { type: 'set-theme'; theme: ThemeName }
  | { type: 'set-permission-preset'; preset: PermissionPreset }
  | { type: 'set-selected-model'; id: string | null }
  | { type: 'set-right-panel-tab'; tab: RightPanelTab }
  | { type: 'set-current-task-state'; state: AppState['currentTaskState'] }
  | { type: 'set-streaming'; streaming: boolean }
  | { type: 'set-auto-scroll'; on: boolean }
  | { type: 'set-consent-request'; id: string | null }
  | { type: 'set-details-drawer'; open: boolean }
  | { type: 'set-trash-drawer'; open: boolean }
  | { type: 'hydrate'; state: Partial<AppState> };

/** Pure reducer. The actions are designed to be discriminated unions
 *  so a future "play a transition" action doesn't accidentally flip
 *  the theme. */
export function appReducer(state: AppState, action: AppAction): AppState {
  switch (action.type) {
    case 'set-current-session':
      return { ...state, currentSessionId: action.id };
    case 'set-theme':
      return { ...state, theme: action.theme };
    case 'set-permission-preset':
      return { ...state, permissionPreset: action.preset };
    case 'set-selected-model':
      return { ...state, selectedModelId: action.id };
    case 'set-right-panel-tab':
      return { ...state, rightPanelTab: action.tab };
    case 'set-current-task-state':
      return { ...state, currentTaskState: action.state };
    case 'set-streaming':
      return { ...state, streaming: action.streaming };
    case 'set-auto-scroll':
      return { ...state, autoScroll: action.on };
    case 'set-consent-request':
      return { ...state, consentRequestId: action.id };
    case 'set-details-drawer':
      return { ...state, detailsDrawerOpen: action.open };
    case 'set-trash-drawer':
      return { ...state, trashDrawerOpen: action.open };
    case 'hydrate':
      return { ...state, ...action.state };
    default:
      return state;
  }
}

export interface AppContextValue extends AppState {
  setCurrentSessionId: (id: string | null) => void;
  setTheme: (theme: ThemeName) => void;
  setPermissionPreset: (preset: PermissionPreset) => void;
  setSelectedModelId: (id: string | null) => void;
  setRightPanelTab: (tab: RightPanelTab) => void;
  setCurrentTaskState: (state: AppState['currentTaskState']) => void;
  setStreaming: (streaming: boolean) => void;
  setAutoScroll: (on: boolean) => void;
  setConsentRequestId: (id: string | null) => void;
  setDetailsDrawerOpen: (open: boolean) => void;
  setTrashDrawerOpen: (open: boolean) => void;
  /** Bulk hydration — used by the persistence layer (localStorage
   *  on mount) and by the re-attach handler. */
  hydrate: (state: Partial<AppState>) => void;
}

const AppContext = createContext<AppContextValue | null>(null);

export interface AppProviderProps {
  children: ReactNode;
  /** Optional initial state (e.g. hydrated from localStorage). */
  initial?: Partial<AppState>;
  /** localStorage key for persistence. Set to `false` to disable. */
  persistKey?: string | false;
}

/** Top-level provider. The provider owns the reducer; children read
 *  the bound helpers via {@link useApp}. */
export function AppProvider({ children, initial, persistKey = 'aethercode.appState' }: AppProviderProps) {
  const [state, dispatch] = useReducer(
    appReducer,
    { ...INITIAL_STATE, ...initial },
  );

  // Hydrate from localStorage on mount. Failures (private mode, quota,
  // malformed JSON) are silent — the reducer's defaults are
  // authoritative.
  useEffect(() => {
    if (persistKey === false) return;
    if (typeof window === 'undefined' || !window.localStorage) return;
    try {
      const raw = window.localStorage.getItem(persistKey);
      if (!raw) return;
      const parsed = JSON.parse(raw) as Partial<AppState>;
      if (parsed && typeof parsed === 'object') dispatch({ type: 'hydrate', state: parsed });
    } catch {
      /* ignore */
    }
  }, [persistKey]);

  // Persist on change. Throttled via the reducer's batching — every
  // dispatch re-runs this effect, so a rapid sequence of actions
  // only writes the final state.
  useEffect(() => {
    if (persistKey === false) return;
    if (typeof window === 'undefined' || !window.localStorage) return;
    try {
      window.localStorage.setItem(persistKey, JSON.stringify(state));
    } catch {
      /* quota / private mode — ignore */
    }
  }, [state, persistKey]);

  const value = useMemo<AppContextValue>(() => ({
    ...state,
    setCurrentSessionId: (id) => dispatch({ type: 'set-current-session', id }),
    setTheme: (theme) => dispatch({ type: 'set-theme', theme }),
    setPermissionPreset: (preset) => dispatch({ type: 'set-permission-preset', preset }),
    setSelectedModelId: (id) => dispatch({ type: 'set-selected-model', id }),
    setRightPanelTab: (tab) => dispatch({ type: 'set-right-panel-tab', tab }),
    setCurrentTaskState: (s) => dispatch({ type: 'set-current-task-state', state: s }),
    setStreaming: (s) => dispatch({ type: 'set-streaming', streaming: s }),
    setAutoScroll: (on) => dispatch({ type: 'set-auto-scroll', on }),
    setConsentRequestId: (id) => dispatch({ type: 'set-consent-request', id }),
    setDetailsDrawerOpen: (open) => dispatch({ type: 'set-details-drawer', open }),
    setTrashDrawerOpen: (open) => dispatch({ type: 'set-trash-drawer', open }),
    hydrate: (s) => dispatch({ type: 'hydrate', state: s }),
  }), [state]);

  return <AppContext.Provider value={value}>{children}</AppContext.Provider>;
}

/** Hook. Throws if used outside a provider — the call site has
 *  wired something wrong. */
export function useApp(): AppContextValue {
  const ctx = useContext(AppContext);
  if (!ctx) {
    throw new Error('useApp must be used inside <AppProvider>');
  }
  return ctx;
}

/** Read-only selector hook for components that only need one slice. */
export function useAppSelector<T>(selector: (state: AppState) => T): T {
  const ctx = useApp();
  return selector(ctx);
}

/** Stable dispatchers for actions. Identical to the helpers on
 *  `useApp()` but in their own hook for ergonomics. */
export function useAppDispatch() {
  const ctx = useApp();
  return useCallback((action: AppAction) => {
    // We re-dispatch through the helpers on the context rather
    // than touching the reducer directly so persistence stays in
    // sync. (The helpers map 1:1 to the reducer actions.)
    switch (action.type) {
      case 'set-current-session': return ctx.setCurrentSessionId(action.id);
      case 'set-theme': return ctx.setTheme(action.theme);
      case 'set-permission-preset': return ctx.setPermissionPreset(action.preset);
      case 'set-selected-model': return ctx.setSelectedModelId(action.id);
      case 'set-right-panel-tab': return ctx.setRightPanelTab(action.tab);
      case 'set-current-task-state': return ctx.setCurrentTaskState(action.state);
      case 'set-streaming': return ctx.setStreaming(action.streaming);
      case 'set-auto-scroll': return ctx.setAutoScroll(action.on);
      case 'set-consent-request': return ctx.setConsentRequestId(action.id);
      case 'set-details-drawer': return ctx.setDetailsDrawerOpen(action.open);
      case 'set-trash-drawer': return ctx.setTrashDrawerOpen(action.open);
      case 'hydrate': return ctx.hydrate(action.state);
      default: return;
    }
  }, [ctx]);
}
