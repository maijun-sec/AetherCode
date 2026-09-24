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