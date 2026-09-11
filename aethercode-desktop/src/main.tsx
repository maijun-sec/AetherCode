import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';
import './styles/global.css';

// apply the persisted theme attribute SYNCHRONOUSLY
// before React mounts, so the very first paint already uses
// the right CSS variable set. The store re-applies on
// initialize() too, but a useEffect would run after the first
// commit and produce a visible dark → light flash on reload.
// Reading localStorage here is a one-liner with the same
// persistence key the store uses (aethercode.enginePrefs)
// — keep both readers in lockstep or the flash returns.
try {
  const raw = window.localStorage.getItem('aethercode.enginePrefs');
  const parsed = raw ? JSON.parse(raw) as { theme?: string } : null;
  const theme = parsed && parsed.theme === 'light' ? 'light' : 'dark';
  document.documentElement.setAttribute('data-theme', theme);
} catch {
  /* localStorage unavailable (private mode / SSR) — the
   * store's initialize() flow will apply the attribute
   * from in-memory defaults on the first commit. */
}

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
