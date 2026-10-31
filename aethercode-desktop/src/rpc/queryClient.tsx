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

// Phase 3: TanStack Query client + Provider wrapper.
//
// The brief mandates TanStack Query v5 for server state. We
// centralise the QueryClient here so every component gets the
// same cache (a mutation in `useResumeSession` invalidates the
// same `['session', 'list']` key the sidebar reads from).

import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useMemo, type ReactNode } from 'react';

export interface QueryProviderProps {
  children: ReactNode;
  /** Optional pre-built client (useful for tests so each one gets
   *  a fresh cache). */
  client?: QueryClient;
}

/** Build a `QueryClient` with sane defaults for a real-time app:
 *  no global refetch on focus (the chat UI controls refresh
 *  manually), 30s stale time, no retry on 4xx (the typed RPC
 *  client already surfaces the error). */
export function buildQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        gcTime: 5 * 60_000,
        retry: (failureCount, error: any) => {
          if (error && typeof error === 'object' && 'code' in error) {
            const code = (error as { code: number }).code;
            if (code >= -32099 && code <= -32000) return failureCount < 2;
          }
          return failureCount < 2;
        },
        refetchOnWindowFocus: false,
      },
      mutations: { retry: 0 },
    },
  });
}

export function QueryProvider({ children, client }: QueryProviderProps) {
  const qc = useMemo(() => client ?? buildQueryClient(), [client]);
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
}
