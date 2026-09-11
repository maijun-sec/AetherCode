// Test utilities for the Phase 3-7 component suite.
//
// Most component tests render under a fresh `QueryClient` + a
// `JsonRpcClient` whose transport is a `MockRpcServer`. This file
// centralises the harness so each test is one or two lines.

import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, type RenderOptions, type RenderResult } from '@testing-library/react';
import { afterEach as vitestAfterEach } from 'vitest';
import { type ReactNode, type ReactElement } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { MockRpcServer, type MockSeed } from '../rpc/MockRpcServer';
import { JsonRpcClient } from '../rpc/client';
import { RpcProvider } from '../rpc/queries';
import { AppProvider } from '../state/AppContext';
// Re-export so tests that import `MockSeed` from testUtils keep
// working (the type is owned by MockRpcServer but the test
// surface treats it as part of the test helpers).
export type { MockSeed };

/** A fresh in-process test fixture. */
export interface TestFixture {
  server: MockRpcServer;
  client: JsonRpcClient;
  queryClient: QueryClient;
  /** Render a React node under the test provider stack. */
  render: (ui: ReactElement, options?: RenderOptions) => RenderResult;
}

export interface TestFixtureOptions {
  seed?: MockSeed;
  /** Initial route entries for the MemoryRouter. Default ['/']. */
  initialEntries?: string[];
  /** Initial app state (theme / preset / etc.). */
  initialAppState?: Record<string, unknown>;
  /** Persist key — pass `false` to disable localStorage. */
  persistKey?: string | false;
}

export function createFixture(opts: TestFixtureOptions = {}): TestFixture {
  const server = new MockRpcServer(opts.seed ?? {});
  const client = new JsonRpcClient({});
  server.installInto(client);
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0 },
      mutations: { retry: false },
    },
  });
  const harness = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>
      <RpcProvider client={client}>
        <AppProvider initial={opts.initialAppState} persistKey={opts.persistKey ?? false}>
          <MemoryRouter initialEntries={opts.initialEntries ?? ['/']}>
            {children}
          </MemoryRouter>
        </AppProvider>
      </RpcProvider>
    </QueryClientProvider>
  );
  return {
    server,
    client,
    queryClient,
    render: (ui, options) => render(ui, { wrapper: harness, ...options }),
  };
}

/** Auto-cleanup helper for `describe` blocks. Import and call
 *  `autoCleanup()` at the top of a `describe()` to make every
 *  test in the file call `cleanup()` automatically. The
 *  vitest setup file also exposes a `__autoCleanup()` global
 *  with the same effect so test files can call it without an
 *  explicit import. */
export function autoCleanup() {
  vitestAfterEach(() => {
    cleanup();
  });
}

/** Flush microtasks + a couple of ticks. The TanStack Query hooks
 *  schedule their fetches on the next microtask; the mock pushes
 *  events on another. Two `setTimeout(0)` rounds is enough for
 *  the test side to settle without `vi.useFakeTimers`. */
export async function flush(): Promise<void> {
  for (let i = 0; i < 3; i++) {
    await new Promise((r) => setTimeout(r, 0));
  }
}

/** Wrap the user's UI under the standard provider stack WITHOUT
 *  a router — for tests that don't navigate. The mock client
 *  is required so `useRpc()` doesn't fall through to the
 *  production `defaultRpcClient()` (which has no daemon
 *  behind it in tests). */
export function TestProviders({ children, client }: { children: ReactNode; client?: JsonRpcClient }) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  });
  const mockClient = client ?? new JsonRpcClient({});
  return (
    <QueryClientProvider client={queryClient}>
      <RpcProvider client={mockClient}>
        <AppProvider persistKey={false}>{children}</AppProvider>
      </RpcProvider>
    </QueryClientProvider>
  );
}
