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

// Minimal type stub for the `ws` library used by the
// R341 ProviderRegistryContractR341 test. The `ws`
// package ships its own types in newer versions but the
// version pinned in this repo doesn't, so we provide a
// slim declaration to keep TypeScript happy without
// pulling @types/ws (which would add another devDep
// just for one end-to-end test).
declare module 'ws' {
  export class WebSocket {
      constructor(address: string | URL, options?: any);
      on(event: 'open', listener: () => void): this;
      on(event: 'message', listener: (data: any) => void): this;
      on(event: 'error', listener: (err: Error) => void): this;
      on(event: 'close', listener: (code?: number) => void): this;
      once(event: string, listener: (...args: any[]) => void): this;
      send(data: string | Buffer): void;
      close(code?: number): void;
      terminate(): void;
  }
}