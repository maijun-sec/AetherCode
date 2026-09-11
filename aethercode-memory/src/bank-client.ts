/**
 * R244.3 (O-10): TypeScript client for the Java
 * {@code BankServer} exposed by
 * {@code aethercode-deepagents} via
 * {@code com.sun.net.httpserver.HttpServer}. Mirrors the
 * Java {@code BankClient} shape so TUI / desktop /
 * IntelliJ surfaces can read and write the same strategy
 * library the daemon runtime uses.
 *
 * <h2>Why a separate client</h2>
 *
 * <p>The Java side ({@code aethercode-talon}'s
 * {@code DeepAgentRuntime}) writes reflections into a
 * file-backed {@code ReasoningBank} and exposes a small
 * HTTP surface for non-JVM clients. This module is the
 * TypeScript half of that round trip. Keeping the client
 * in {@code aethercode-memory} (the place that already
 * ships cross-process memory primitives) avoids pulling
 * TUI- or desktop-specific dependencies into a shared
 * module.</p>
 *
 * <h2>Wire shape</h2>
 *
 * <p>The server returns units as JSON objects whose keys
 * are the same names {@code ReasoningUnit.toMap()} emits
 * in the JVM side:</p>
 *
 * <pre>
 * {
 *   id, taskKind, errorPattern, fixStrategy, example,
 *   utility, uses, okCount, notOkCount, createdAt
 * }
 * </pre>
 *
 * <h2>Failure modes</h2>
 *
 * <p>Network / decode failures throw {@link BankClientError};
 * HTTP 404 is treated as "no such kind" (the
 * {@link BankClient#recallFor} / {@link BankClient#recallAllKinds}
 * methods return an empty list). Other non-2xx codes throw.</p>
 */

export interface BankUnit {
  id: string;
  taskKind: string;
  errorPattern: string;
  fixStrategy: string;
  example: string;
  utility: number;
  uses: number;
  okCount: number;
  notOkCount: number;
  createdAt: string;
}

export interface BankStats {
  size: number;
  kinds: string[];
  perKind: Record<string, number>;
  totalOk: number;
  totalNotOk: number;
}

/** Thrown by every {@link BankClient} method on a
 *  transport, decode, or non-2xx response. The {@link BankClientError.status}
 *  is 0 when the failure happened before HTTP (e.g. fetch threw). */
export class BankClientError extends Error {
  readonly status: number;
  constructor(status: number, message: string) {
    super(`bank client (status=${status}): ${message}`);
    this.name = 'BankClientError';
    this.status = status;
  }
}

/**
 * Thin HTTP client for the {@code BankServer} running on
 * the daemon side. Use {@link BankClient#ping} as a cheap
 * liveness probe before each batch of calls; the server
 * is localhost-only and may be down (opt-in env var).
 */
export class BankClient {
  private readonly baseUrl: string;
  private readonly fetchImpl: typeof fetch;

  constructor(baseUrl: string, fetchImpl: typeof fetch = fetch) {
    // Strip a single trailing slash so we can build
    // `${baseUrl}/bank/...` cleanly.
    this.baseUrl = baseUrl.endsWith('/') ? baseUrl.slice(0, -1) : baseUrl;
    this.fetchImpl = fetchImpl;
  }

  /** Healthz probe. Returns {@code true} on HTTP 200,
   *  {@code false} on any other status or network error. */
  async ping(): Promise<boolean> {
    try {
      const r = await this.fetchImpl(`${this.baseUrl}/healthz`, {
        method: 'GET',
        signal: AbortSignal.timeout(2000),
      });
      return r.status === 200;
    } catch {
      return false;
    }
  }

  /** Top-N units for a given task kind. Empty list when
   *  the server replies 404 (no such kind). */
  async recallFor(kind: string, n: number = 3): Promise<BankUnit[]> {
    if (!kind) throw new Error('kind must be non-empty');
    const q = new URLSearchParams({ kind, n: String(n) });
    const body = await this.getJson(`/bank/recall?${q.toString()}`);
    return this.extractUnits(body);
  }

  /** Cross-kind top-N, matching the server-side ranking
   *  in {@code BankServer#recall-all-kinds}. */
  async recallAllKinds(n: number = 3): Promise<BankUnit[]> {
    const body = await this.postJson(
      `/bank/recall-all-kinds?n=${n}`,
      new Uint8Array(0),
    );
    return this.extractUnits(body);
  }

  /** Bump uses/utility for a unit by id. Returns the
   *  updated wire record. */
  async touch(id: string): Promise<BankUnit> {
    if (!id) throw new Error('id must be non-empty');
    const body = await this.postJson(
      `/bank/touch?id=${encodeURIComponent(id)}`,
      new Uint8Array(0),
    );
    return this.extractUnit(body);
  }

  /** R244.1 feedback: record an ok / notOk outcome for a
   *  unit. Returns the updated wire record. */
  async recordOutcome(id: string, ok: boolean): Promise<BankUnit> {
    if (!id) throw new Error('id must be non-empty');
    const body = await this.postJson(
      `/bank/record-outcome?id=${encodeURIComponent(id)}&ok=${ok}`,
      new Uint8Array(0),
    );
    return this.extractUnit(body);
  }

  /** Snapshot of bank stats: total size, per-kind counts,
   *  total ok / notOk. */
  async stats(): Promise<BankStats> {
    const body = await this.getJson('/bank/stats');
    if (
      typeof body.size !== 'number' ||
      !Array.isArray(body.kinds) ||
      typeof body.perKind !== 'object' ||
      typeof body.totalOk !== 'number' ||
      typeof body.totalNotOk !== 'number'
    ) {
      throw new BankClientError(200, 'malformed stats response');
    }
    return {
      size: body.size,
      kinds: body.kinds,
      perKind: body.perKind,
      totalOk: body.totalOk,
      totalNotOk: body.totalNotOk,
    };
  }

  // -----------------------------------------------------------------
  //  Internals
  // -----------------------------------------------------------------

  private async getJson(path: string): Promise<any> {
    return this.send('GET', path, null);
  }

  private async postJson(path: string, body: Uint8Array): Promise<any> {
    return this.send('POST', path, body);
  }

  private async send(
    method: 'GET' | 'POST',
    path: string,
    body: Uint8Array | null,
  ): Promise<any> {
    const init: RequestInit = {
      method,
      headers: { Accept: 'application/json' },
      signal: AbortSignal.timeout(10_000),
    };
    if (method === 'POST') {
      init.body = body ?? new Uint8Array(0);
    }
    let response: Response;
    try {
      response = await this.fetchImpl(`${this.baseUrl}${path}`, init);
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      throw new BankClientError(0, `transport error: ${msg}`);
    }
    const text = await response.text();
    if (response.status === 404) {
      // 404 is "no such kind" for recall endpoints; let
      // the caller's extractor decide what to return.
      return { __notFound: true, body: this.tryParse(text) };
    }
    if (response.status < 200 || response.status >= 300) {
      throw new BankClientError(
        response.status,
        text.length > 0 ? text : 'empty error body',
      );
    }
    return this.tryParse(text);
  }

  private tryParse(text: string): any {
    if (text.length === 0) return {};
    try {
      return JSON.parse(text);
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      throw new BankClientError(200, `decode error: ${msg}`);
    }
  }

  private extractUnits(body: any): BankUnit[] {
    if (body && body.__notFound === true) return [];
    const list = body && Array.isArray(body.units) ? body.units : [];
    return list.map((u: any) => this.coerceUnit(u));
  }

  private extractUnit(body: any): BankUnit {
    if (body && body.__notFound === true) {
      throw new BankClientError(404, 'unknown id');
    }
    const unit = body && body.unit ? body.unit : body;
    return this.coerceUnit(unit);
  }

  private coerceUnit(u: any): BankUnit {
    return {
      id: String(u.id),
      taskKind: String(u.taskKind),
      errorPattern: String(u.errorPattern),
      fixStrategy: String(u.fixStrategy),
      example: String(u.example ?? ''),
      utility: Number(u.utility ?? 0),
      uses: Number(u.uses ?? 0),
      okCount: Number(u.okCount ?? 0),
      notOkCount: Number(u.notOkCount ?? 0),
      createdAt: String(u.createdAt ?? ''),
    };
  }
}
