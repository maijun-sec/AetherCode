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

/**
 * R370 — cwd comparison for the session picker.
 *
 * ## Why this file exists
 *
 * `/sessions` is defined as "the sessions of THIS project", so it has
 * to decide `row.cwd === currentCwd`. Writing that inline looks
 * trivial and is wrong in four separate ways, each of which silently
 * empties the list on a real machine:
 *
 *   1. `D:\work\AetherCode` vs `d:\work\aethercode` — the same
 *      directory on a case-insensitive filesystem (all of Windows,
 *      and default macOS). String `===` says different.
 *   2. `D:\work\AetherCode\` vs `D:\work\AetherCode` — one trailing
 *      separator is not a different directory.
 *   3. `D:/work/AetherCode` vs `D:\work\AetherCode` — Java writes
 *      `Path.toString()` with the platform separator, but a cwd typed
 *      by hand into `/cwd-pick`, or one that round-tripped through
 *      JSON written on a POSIX box, can carry the other one.
 *   4. A row with NO cwd at all. `listSessions` OMITS the key when
 *      it cannot attribute a session to a directory (see
 *      AetherCodeMethods.listSessions — `cwd` is only `put` when
 *      non-null, after SQLite -> `.cwd` sidecar -> shared memory
 *      store). `undefined === currentCwd` is false, so those rows
 *      vanish — which is CORRECT (we do not know they belong here)
 *      but must be *counted*, or `/sessions` reporting "0 sessions"
 *      while `/threads` reports 20 looks like a broken panel rather
 *      than an honest answer about attribution.
 *
 * The exported `partitionByCwd` returns both halves so the caller
 * can SAY the second one instead of silently dropping it.
 */

/**
 * Trailing separators are noise; `/` and `\` are interchangeable.
 *
 * The one thing that is NOT noise is the separator on a bare drive
 * letter: `C:\` is the root, `C:` is "whatever directory is current
 * on drive C". Stripping `C:\` down to `C:` turns a root into a
 * drive-relative path, which then compares equal to a literal `C:`
 * from a caller that meant something else entirely.
 */
function canonical(p: string): string {
  const trimmed = (p ?? "").trim();
  if (trimmed.length === 0) return "";
  let s = trimmed;
  while (s.length > 1 && /[\\/]$/.test(s) && !/^[a-zA-Z]:[\\/]$/.test(s)) {
    s = s.slice(0, -1);
  }
  return s.replace(/\//g, "\\");
}

/** Case-insensitive on Windows, where the filesystem is. */
function caseFold(s: string): string {
  return process.platform === "win32" ? s.toLowerCase() : s;
}

/**
 * Do these two strings name the same directory?
 *
 * Returns false for two blanks: "unknown cwd" must not be reported
 * as matching "unknown cwd", or a filter over unattributed rows
 * would pass them all through.
 */
export function sameCwd(a: string | null | undefined, b: string | null | undefined): boolean {
  const ca = canonical(a ?? "");
  const cb = canonical(b ?? "");
  if (ca.length === 0 || cb.length === 0) return false;
  return caseFold(ca) === caseFold(cb);
}

/** Last path segment, for a compact panel heading. */
export function cwdBasename(p: string): string {
  const c = canonical(p ?? "");
  const idx = Math.max(c.lastIndexOf("\\"), c.lastIndexOf("/"));
  if (idx < 0) return c;
  return c.slice(idx + 1) || c;
}

/** Split rows into "recorded under this cwd" and "everything else". */
export function partitionByCwd<T>(
  rows: readonly T[],
  cwdOf: (row: T) => string,
  cwd: string,
): { mine: T[]; other: T[] } {
  const mine: T[] = [];
  const other: T[] = [];
  for (const row of rows) (sameCwd(cwdOf(row), cwd) ? mine : other).push(row);
  return { mine, other };
}
