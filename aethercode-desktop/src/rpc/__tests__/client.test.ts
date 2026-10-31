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

import { describe, it, expect } from 'vitest';
import { readFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * Phase 3 (T-3-01): JsonRpcClient (source-level).
 *
 * The wire-level round-trip is covered by
 * MockRpcServer.test.ts. This file asserts the *shape* of
 * the client + the wire contract it produces.
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
})();
function read(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

describe('Phase 3 / T-3-01: JsonRpcClient (source-level)', () => {
  it('client.ts exists', () => {
    expect(existsSync(join(root, 'src/rpc/client.ts'))).toBe(true);
  });

  it('exports JsonRpcClient class', () => {
    const src = read('src/rpc/client.ts');
    expect(src).toMatch(/export\s+class\s+JsonRpcClient\b/);
  });

  it('exports defaultRpcClient() function', () => {
    const src = read('src/rpc/client.ts');
    expect(src).toMatch(/defaultRpcClient/);
  });

  it('call() takes (method, params, signal?)', () => {
    const src = read('src/rpc/client.ts');
    expect(src).toMatch(/async\s+call\s*<T\s*=/);
    expect(src).toMatch(/method:\s*string/);
    expect(src).toMatch(/params\?:\s*unknown/);
    expect(src).toMatch(/signal\?:\s*AbortSignal/);
  });

  it('call() builds a JSON-RPC 2.0 envelope (jsonrpc + id + method + params)', () => {
    const src = read('src/rpc/client.ts');
    expect(src).toMatch(/jsonrpc:\s*['"]2\.0['"]/);
    expect(src).toMatch(/body:\s*RpcRequest/);
  });

  it('call() throws RpcError on a non-2xx HTTP status', () => {
    const src = read('src/rpc/client.ts');
    expect(src).toMatch(/res\.ok/);
    expect(src).toMatch(/HTTP \$\{res\.status\}/);
  });

  it('call() throws RpcError on a JSON-RPC error envelope', () => {
    const src = read('src/rpc/client.ts');
    expect(src).toMatch(/'error'\s+in\s+parsed/);
    expect(src).toMatch(/parsed\.error\.code/);
  });

  it('call() sets a default 30 s timeout', () => {
    const src = read('src/rpc/client.ts');
    expect(src).toMatch(/30_?000/);
  });

  it('subscribe() takes (method, params, onMessage)', () => {
    const src = read('src/rpc/client.ts');
    expect(src).toMatch(/subscribe\s*\(\s*method/);
    expect(src).toMatch(/onMessage/);
  });

  it('subscribe() returns an unsubscribe handle', () => {
    const src = read('src/rpc/client.ts');
    expect(src).toMatch(/return\s*\{[^}]*unsubscribe/);
  });

  it('subscribe() supports re-attach (lastSeq query param)', () => {
    const src = read('src/rpc/client.ts');
    expect(src).toMatch(/since/);
  });

  it('handles missing EventSource (push-only fallback)', () => {
    const src = read('src/rpc/client.ts');
    expect(src).toMatch(/pickDefaultEventSource/);
    expect(src).toMatch(/push-only mode/);
  });

  it('closeAll() tears down every open stream', () => {
    const src = read('src/rpc/client.ts');
    expect(src).toMatch(/closeAll\s*\(\s*\)/);
    expect(src).toMatch(/es\.close\(\)/);
  });

  it('exports FetchImpl + EventSourceImpl types for tests', () => {
    const src = read('src/rpc/client.ts');
    expect(src).toMatch(/export\s+type\s+FetchImpl\b/);
    expect(src).toMatch(/export\s+type\s+EventSourceImpl\b/);
  });

  it('exports RpcError + ok/err envelope builders', () => {
    const src = read('src/rpc/client.ts');
    expect(src).toMatch(/export\s+\{[^}]*RpcError/);
    expect(src).toMatch(/export\s+\{[^}]*buildOk/);
    expect(src).toMatch(/export\s+\{[^}]*buildErr/);
  });
});

describe('Phase 3 / T-3-01: RpcError type', () => {
  it('types.ts defines RpcError with method, code, data', () => {
    const src = read('src/rpc/types.ts');
    expect(src).toMatch(/export\s+class\s+RpcError\b/);
    expect(src).toMatch(/readonly\s+method:\s*string/);
    expect(src).toMatch(/readonly\s+code:\s*number/);
    expect(src).toMatch(/readonly\s+data:\s*unknown/);
  });

  it('ok() and err() build wire envelopes', () => {
    const src = read('src/rpc/types.ts');
    expect(src).toMatch(/export\s+function\s+ok\b/);
    expect(src).toMatch(/export\s+function\s+err\b/);
  });
});
