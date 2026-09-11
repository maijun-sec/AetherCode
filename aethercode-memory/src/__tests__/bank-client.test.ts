/**
 * R244.3 (O-10): tests for the TypeScript {@link BankClient}.
 *
 * <p>These tests use a hand-rolled {@link FakeFetch} rather
 * than {@code vi.fn()} so the suite stays decoupled from
 * vitest's specific mock helpers. The fake is good enough
 * to assert the URL path, method, query string, and JSON
 * response shape, which is everything the JVM server's
 * contract guarantees.</p>
 */

import { describe, it, expect } from 'vitest';
import { BankClient, BankClientError, type BankUnit } from '../bank-client.js';

/** Minimal fetch shape that {@link BankClient} uses. */
type FetchArgs = {
  method: 'GET' | 'POST';
  url: string;
  body: Uint8Array | null;
};

class FakeFetch {
  readonly calls: FetchArgs[] = [];
  /** Each entry is consulted in order; a missing entry
   *  throws "no scripted response", which fails the test
   *  loudly if the test set up fewer responses than the
   *  client made calls. */
  private readonly responses: Response[] = [];
  private cursor = 0;

  constructor(responses: Array<{ status: number; body: string }>) {
    for (const r of responses) {
      this.responses.push(
        new Response(r.body, {
          status: r.status,
          headers: { 'Content-Type': 'application/json' },
        }),
      );
    }
  }

  fn = async (url: string, init: RequestInit): Promise<Response> => {
    this.calls.push({
      method: (init.method as 'GET' | 'POST') ?? 'GET',
      url,
      body:
        init.body == null
          ? null
          : init.body instanceof Uint8Array
            ? init.body
            : new TextEncoder().encode(String(init.body)),
    });
    if (this.cursor >= this.responses.length) {
      throw new Error('FakeFetch: no scripted response for call ' + this.cursor);
    }
    return this.responses[this.cursor++];
  };
}

describe('BankClient — ping', () => {
  it('returns true on HTTP 200', async () => {
    const fake = new FakeFetch([{ status: 200, body: '{"ok":true}' }]);
    const c = new BankClient('http://127.0.0.1:7777', fake.fn as typeof fetch);
    expect(await c.ping()).toBe(true);
    expect(fake.calls[0].url).toBe('http://127.0.0.1:7777/healthz');
    expect(fake.calls[0].method).toBe('GET');
  });

  it('returns false on non-200', async () => {
    const fake = new FakeFetch([{ status: 500, body: '' }]);
    const c = new BankClient('http://127.0.0.1:7777', fake.fn as typeof fetch);
    expect(await c.ping()).toBe(false);
  });
});

describe('BankClient — recallFor', () => {
  const unit: BankUnit = {
    id: 'u1',
    taskKind: 'file_edit',
    errorPattern: 'permission denied',
    fixStrategy: 'ensure dir exists first',
    example: 'mkdir -p /x',
    utility: 0.85,
    uses: 3,
    okCount: 2,
    notOkCount: 0,
    createdAt: '2026-09-10T00:00:00Z',
  };

  it('decodes the unit list on 200', async () => {
    const fake = new FakeFetch([
      { status: 200, body: JSON.stringify({ units: [unit] }) },
    ]);
    const c = new BankClient('http://127.0.0.1:7777', fake.fn as typeof fetch);
    const out = await c.recallFor('file_edit', 5);
    expect(out).toEqual([unit]);
    const url = fake.calls[0].url;
    expect(url).toContain('/bank/recall?');
    expect(url).toContain('kind=file_edit');
    expect(url).toContain('n=5');
  });

  it('returns empty list on 404', async () => {
    const fake = new FakeFetch([{ status: 404, body: '{"error":"no such kind"}' }]);
    const c = new BankClient('http://127.0.0.1:7777', fake.fn as typeof fetch);
    expect(await c.recallFor('nonexistent', 3)).toEqual([]);
  });

  it('throws on transport failure (status 0)', async () => {
    const throwing = async () => {
      throw new Error('ECONNREFUSED');
    };
    const c = new BankClient('http://127.0.0.1:7777', throwing as typeof fetch);
    await expect(c.recallFor('file_edit', 1)).rejects.toBeInstanceOf(BankClientError);
  });
});

describe('BankClient — recallAllKinds', () => {
  const u1: BankUnit = {
    id: 'u1',
    taskKind: 'file_edit',
    errorPattern: 'a',
    fixStrategy: 'A',
    example: '',
    utility: 0.9,
    uses: 1,
    okCount: 0,
    notOkCount: 0,
    createdAt: '2026-09-10T00:00:00Z',
  };
  const u2: BankUnit = { ...u1, id: 'u2', taskKind: 'build', fixStrategy: 'B' };

  it('decodes the cross-kind list', async () => {
    const fake = new FakeFetch([
      { status: 200, body: JSON.stringify({ units: [u1, u2] }) },
    ]);
    const c = new BankClient('http://127.0.0.1:7777', fake.fn as typeof fetch);
    const out = await c.recallAllKinds(5);
    expect(out).toHaveLength(2);
    expect(fake.calls[0].url).toContain('/bank/recall-all-kinds?n=5');
    expect(fake.calls[0].method).toBe('POST');
    expect(fake.calls[0].body).toEqual(new Uint8Array(0));
  });
});

describe('BankClient — touch', () => {
  it('returns the updated unit on 200', async () => {
    const updated = {
      id: 'u1',
      taskKind: 'k',
      errorPattern: 'e',
      fixStrategy: 'f',
      example: '',
      utility: 0.55,
      uses: 1,
      okCount: 0,
      notOkCount: 0,
      createdAt: '2026-09-10T00:00:00Z',
    };
    const fake = new FakeFetch([
      { status: 200, body: JSON.stringify({ unit: updated }) },
    ]);
    const c = new BankClient('http://127.0.0.1:7777', fake.fn as typeof fetch);
    const out = await c.touch('u1');
    expect(out.utility).toBeCloseTo(0.55, 5);
    expect(out.uses).toBe(1);
    const url = fake.calls[0].url;
    expect(url).toContain('/bank/touch?id=u1');
    expect(fake.calls[0].method).toBe('POST');
  });

  it('throws BankClientError on 404', async () => {
    const fake = new FakeFetch([{ status: 404, body: '{"error":"unknown id"}' }]);
    const c = new BankClient('http://127.0.0.1:7777', fake.fn as typeof fetch);
    await expect(c.touch('nope')).rejects.toMatchObject({
      status: 404,
    });
  });
});

describe('BankClient — recordOutcome', () => {
  it('encodes ok=true correctly', async () => {
    const fake = new FakeFetch([
      {
        status: 200,
        body: JSON.stringify({
          unit: {
            id: 'u1',
            taskKind: 'k',
            errorPattern: 'e',
            fixStrategy: 'f',
            example: '',
            utility: 0.5,
            uses: 0,
            okCount: 1,
            notOkCount: 0,
            createdAt: '2026-09-10T00:00:00Z',
          },
        }),
      },
    ]);
    const c = new BankClient('http://127.0.0.1:7777', fake.fn as typeof fetch);
    const out = await c.recordOutcome('u1', true);
    expect(out.okCount).toBe(1);
    const url = fake.calls[0].url;
    expect(url).toContain('/bank/record-outcome');
    expect(url).toContain('id=u1');
    expect(url).toContain('ok=true');
  });

  it('encodes ok=false correctly', async () => {
    const fake = new FakeFetch([
      {
        status: 200,
        body: JSON.stringify({
          unit: {
            id: 'u1',
            taskKind: 'k',
            errorPattern: 'e',
            fixStrategy: 'f',
            example: '',
            utility: 0.5,
            uses: 0,
            okCount: 0,
            notOkCount: 1,
            createdAt: '2026-09-10T00:00:00Z',
          },
        }),
      },
    ]);
    const c = new BankClient('http://127.0.0.1:7777', fake.fn as typeof fetch);
    const out = await c.recordOutcome('u1', false);
    expect(out.notOkCount).toBe(1);
    expect(fake.calls[0].url).toContain('ok=false');
  });
});

describe('BankClient — stats', () => {
  it('decodes the stats snapshot', async () => {
    const body = JSON.stringify({
      size: 5,
      kinds: ['k1', 'k2'],
      perKind: { k1: 3, k2: 2 },
      totalOk: 4,
      totalNotOk: 1,
    });
    const fake = new FakeFetch([{ status: 200, body }]);
    const c = new BankClient('http://127.0.0.1:7777', fake.fn as typeof fetch);
    const out = await c.stats();
    expect(out.size).toBe(5);
    expect(out.kinds).toEqual(['k1', 'k2']);
    expect(out.perKind).toEqual({ k1: 3, k2: 2 });
    expect(out.totalOk).toBe(4);
    expect(out.totalNotOk).toBe(1);
  });
});

describe('BankClient — construction', () => {
  it('strips a trailing slash from the base URL', async () => {
    const fake = new FakeFetch([{ status: 200, body: '{"ok":true}' }]);
    const c = new BankClient('http://127.0.0.1:7777/', fake.fn as typeof fetch);
    await c.ping();
    expect(fake.calls[0].url).toBe('http://127.0.0.1:7777/healthz');
  });
});
