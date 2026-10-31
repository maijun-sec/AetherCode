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
 * End-to-end test that the SqliteHistoryStore can drive the
 * real better-sqlite3 binding (T-193 + final integration).
 *
 * Skipped automatically when better-sqlite3 can't be loaded
 * (e.g. when the platform doesn't have a prebuilt binary).
 */
import { afterAll, describe, expect, it } from "vitest";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { existsSync, unlinkSync } from "node:fs";

import { SqliteHistoryStore, createHistoryStore } from "../history-store.js";
import type { CompactEvent } from "../rpc.js";

function tryRequire(name: string): unknown | null {
  try {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    return require(name);
  } catch {
    return null;
  }
}

const rawBinding = tryRequire("better-sqlite3") as { new (path: string): unknown } | null;

const describeIf = rawBinding ? describe : describe.skip;

/** Build a unique temporary db path for one test. Caller must
 *  remove the file in an `afterAll`/`finally`. */
function uniqueDbPath(prefix: string): string {
  return join(tmpdir(), `aethercode-compact-${prefix}-${Date.now()}-${Math.random().toString(36).slice(2)}.db`);
}

function rmIfExists(path: string): void {
  if (path && existsSync(path)) {
    try { unlinkSync(path); } catch { /* swallow */ }
  }
}

describeIf("SqliteHistoryStore (real better-sqlite3 binding)", () => {
  const paths: string[] = [];
  afterAll(() => {
    for (const p of paths) rmIfExists(p);
  });

  it("opens, migrates, and round-trips events", () => {
    const path = uniqueDbPath("rt");
    paths.push(path);
    const s = new SqliteHistoryStore({ dbPath: path });
    const events: CompactEvent[] = [
      { ts: 100, layer: 3, beforeTokens: 1000, afterTokens: 400, elapsedMs: 50, failed: false },
      { ts: 200, layer: 1, beforeTokens: 600, afterTokens: 200, elapsedMs: 5, failed: false },
      { ts: 300, layer: 3, beforeTokens: 900, afterTokens: 0, elapsedMs: 200, failed: true, failureReason: "llm_error" },
    ];
    for (const e of events) s.append(e);
    expect(s.count()).toBe(3);
    const out = s.recent();
    expect(out.map((e) => e.ts)).toEqual([100, 200, 300]);
    expect(out[2]!.failureReason).toBe("llm_error");
    s.close();
  });

  it("factory returns sqlite backend with a real db file", () => {
    const path = uniqueDbPath("factory");
    paths.push(path);
    const s = createHistoryStore({ dbPath: path });
    expect(s.backend).toBe("sqlite");
    s.append({ ts: 1, layer: 1, beforeTokens: 10, afterTokens: 5, elapsedMs: 1, failed: false });
    expect(s.count()).toBe(1);
    s.close();
  });

  it("survives a process restart (open the same file twice)", () => {
    const path = uniqueDbPath("restart");
    paths.push(path);
    const s1 = new SqliteHistoryStore({ dbPath: path });
    s1.append({ ts: 1, layer: 1, beforeTokens: 10, afterTokens: 5, elapsedMs: 1, failed: false });
    s1.append({ ts: 2, layer: 3, beforeTokens: 20, afterTokens: 6, elapsedMs: 2, failed: false });
    s1.close();
    const s2 = new SqliteHistoryStore({ dbPath: path });
    expect(s2.count()).toBe(2);
    s2.close();
  });

  it("honours a sessionId filter when attached to events", () => {
    const path = uniqueDbPath("session");
    paths.push(path);
    const s = new SqliteHistoryStore({ dbPath: path });
    s.append({ ...{ ts: 1, layer: 1, beforeTokens: 10, afterTokens: 5, elapsedMs: 1, failed: false }, sessionId: "a" } as CompactEvent & { sessionId: string });
    s.append({ ...{ ts: 2, layer: 1, beforeTokens: 10, afterTokens: 5, elapsedMs: 1, failed: false }, sessionId: "b" } as CompactEvent & { sessionId: string });
    s.append({ ...{ ts: 3, layer: 1, beforeTokens: 10, afterTokens: 5, elapsedMs: 1, failed: false }, sessionId: "a" } as CompactEvent & { sessionId: string });
    expect(s.recent({ sessionId: "a" }).map((e) => e.ts)).toEqual([1, 3]);
    expect(s.recent({ sessionId: "b" }).map((e) => e.ts)).toEqual([2]);
    expect(s.recent().map((e) => e.ts)).toEqual([1, 2, 3]);
    s.close();
  });
});
