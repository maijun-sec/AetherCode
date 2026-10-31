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

// the renderer's "log" notification handler
// must surface daemon errors as a system message
// in the chat AND un-stick the input box. Before
// this fix the handler swallowed every entry whose
// `level` field was missing (the daemon's query()
// catch block emits an error without a level —
// just {runId, error}) because the fallback was
// "info" + an early return.
//
// This test pins the contract:
//   1. A `log` payload with no `level` but an
//      `error` field surfaces as a system message
//      with isError=true (the legacy-S bug).
//   2. A `log` payload with `level: "info"` and
//      no error is still swallowed (noise).
//   3. A `log` payload with `level: "error"`
//      surfaces as a system message with
//      isError=true.
//   4. A `log` payload with `level: "warn"`
//      surfaces as a non-error system message
//      (so warnings still show in the transcript).
//   5. Any error-level payload un-sticks the
//      input box (isStreaming: true -> false).
//
// We drive the same reducer the production
// `rpc.on('log', ...)` registration uses, via
// the `__test_handleLogNotification` seam.

import { describe, test, expect, beforeEach, vi } from "vitest";

vi.mock("@tauri-apps/api/core", () => ({
  invoke: vi.fn(async () => ({})),
}));
vi.mock("@tauri-apps/api/event", () => ({
  listen: vi.fn(async () => () => {}),
  emit: vi.fn(),
}));

import { useStore, __test_handleLogNotification } from "./index";

beforeEach(() => {
  useStore.setState({
    messages: [],
    isStreaming: false,
  });
});

describe("log notification handler (R188)", () => {
  test("missing level + error field surfaces as system message + isError", () => {
    // The daemon's legacy-S catch block emitted
    // exactly this shape; the renderer's fallback
    // to "info" swallowed it. prior round must surface it.
    useStore.setState({ isStreaming: true });
    __test_handleLogNotification({ runId: "run-1", error: "upstream 503" });
    const s = useStore.getState();
    expect(s.messages).toHaveLength(1);
    expect(s.messages[0].role).toBe("system");
    expect(s.messages[0].isError).toBe(true);
    expect(s.messages[0].content).toContain("upstream 503");
    expect(s.isStreaming).toBe(false);
  });

  test("explicit level=error un-sticks and shows error", () => {
    useStore.setState({ isStreaming: true });
    __test_handleLogNotification({ level: "error", message: "boom" });
    const s = useStore.getState();
    expect(s.messages).toHaveLength(1);
    expect(s.messages[0].isError).toBe(true);
    expect(s.messages[0].content).toContain("[error]");
    expect(s.messages[0].content).toContain("boom");
    expect(s.isStreaming).toBe(false);
  });

  test("level=warn surfaces as non-error message", () => {
    useStore.setState({ isStreaming: true });
    __test_handleLogNotification({ level: "warn", message: "watch out" });
    const s = useStore.getState();
    expect(s.messages).toHaveLength(1);
    expect(s.messages[0].role).toBe("system");
    expect(s.messages[0].isError).toBe(false);
    expect(s.messages[0].content).toContain("[warn]");
    // Warnings shouldn't un-stick the input box.
    expect(s.isStreaming).toBe(true);
  });

  test("level=info with no error is swallowed", () => {
    __test_handleLogNotification({ level: "info", message: "noise" });
    const s = useStore.getState();
    expect(s.messages).toHaveLength(0);
  });

  test("level=err (no second r) treated as error", () => {
    useStore.setState({ isStreaming: true });
    __test_handleLogNotification({ level: "err", error: "short form" });
    const s = useStore.getState();
    expect(s.messages).toHaveLength(1);
    expect(s.messages[0].isError).toBe(true);
    expect(s.isStreaming).toBe(false);
  });

  test("empty message and no error: nothing surfaces", () => {
    __test_handleLogNotification({ level: "info" });
    __test_handleLogNotification({});
    const s = useStore.getState();
    expect(s.messages).toHaveLength(0);
  });
});
