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
