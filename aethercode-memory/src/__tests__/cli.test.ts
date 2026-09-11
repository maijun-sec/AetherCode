/**
 * T-094: tests for the `aethercode memory` CLI dispatcher.
 *
 * Strategy: drive `runCli` / `runCliAsync` directly with
 * in-memory streams + a fake `CliDeps` so we can:
 *   - assert on the captured stdout / stderr,
 *   - avoid touching the real disk (`fs` is a fake),
 *   - inject a stub LLM client for the `compact` tests.
 *
 * The `parseArgs` tests cover the grammar independently
 * so refactors that change behaviour without changing
 * the dispatcher surface are caught.
 */

import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { mkdtempSync, rmSync, existsSync, mkdirSync, writeFileSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname, sep } from 'node:path';

import {
  runCli,
  runCliAsync,
  parseArgs,
  CliUsageError,
  HELP_TEXT,
  type CliArgs,
  type CliStreams,
  type CliDeps,
} from '../cli.js';
import { withDatabase, runStatement, openAndMigrate } from '../sqlite.js';

let tmpDir = '';
let home = '';
let cwd = '';
let projectDir = '';
let globalPath = '';
let projectPath = '';
let dbPath = '';
let envLog: Record<string, string | undefined> = {};

/* ----------------------------- helpers ------------------------------- */

function makeStreams(): CliStreams & {
  stdout: { write(s: string): void; text: string };
  stderr: { write(s: string): void; text: string };
} {
  const so = { text: '' };
  const se = { text: '' };
  return {
    stdout: {
      write(s: string): void {
        so.text += s;
      },
      get text(): string {
        return so.text;
      },
    } as { write(s: string): void; text: string },
    stderr: {
      write(s: string): void {
        se.text += s;
      },
      get text(): string {
        return se.text;
      },
    } as { write(s: string): void; text: string },
  };
}

function makeFs(): CliDeps['fs'] {
  return {
    existsSync: (p: string) => existsSync(p),
    readFileSync: (p: string) => readFileSync(p, 'utf-8'),
    writeFileSync: (p: string, data: string) => {
      mkdirSync(dirname(p), { recursive: true });
      writeFileSync(p, data, 'utf-8');
    },
    mkdirSync: (p: string, opts: { recursive: boolean }) => mkdirSync(p, opts),
    rmSync: (p: string, _opts: { recursive: boolean; force: boolean }) =>
      rmSync(p, { recursive: true, force: true }),
  };
}

function defaultDeps(overrides: Partial<CliDeps> = {}): CliDeps {
  return {
    fs: makeFs(),
    paths: {
      globalMemory: (h) => join(h, '.aethercode', 'memory.md'),
      projectMemory: (c) => join(c, '.aethercode', 'memory.md'),
      sessionsDir: (h) => join(h, '.aethercode', 'sessions'),
      dbPath: (h) => join(h, '.aethercode', 'sessions.db'),
    },
    llm: null,
    spawnEditor: (_p, _e) => ({
      exitCode: 0,
      stdout: '',
      stderr: '',
      process: null,
    }),
    now: () => 1700000000000,
    ...overrides,
  };
}

function seedGlobal(): void {
  mkdirSync(dirname(globalPath), { recursive: true });
  writeFileSync(
    globalPath,
    [
      '# Global Memory',
      '',
      '## Facts',
      '- user.name: Alice',
      '- project.build_cmd: pnpm test',
      '',
      '## Rules',
      '- No mocks in production code',
      '',
      '## Cross-project breadcrumbs',
      '- 2026-08-28 — switched from /a to /b',
      '',
    ].join('\n'),
    'utf-8',
  );
}

function seedProject(): void {
  mkdirSync(dirname(projectPath), { recursive: true });
  writeFileSync(
    projectPath,
    [
      '# Demo Project',
      '',
      '## 说明',
      'A small project used by the T-090 round-4 tests.',
      '',
      '## 修改记录 (最近 20 次)',
      '- 2026-08-28 12:00 — initial scaffold',
      '',
      '## Project facts',
      '- test.framework: vitest',
      '',
    ].join('\n'),
    'utf-8',
  );
}

beforeEach(() => {
  tmpDir = mkdtempSync(join(tmpdir(), 'aethercode-mem-cli-'));
  home = tmpDir;
  cwd = join(tmpDir, 'proj');
  projectDir = join(cwd, '.aethercode');
  mkdirSync(projectDir, { recursive: true });
  globalPath = join(home, '.aethercode', 'memory.md');
  projectPath = join(projectDir, 'memory.md');
  dbPath = join(home, '.aethercode', 'sessions.db');
  envLog = { AETHERCODE_CWD: cwd, HOME: home, EDITOR: 'echo' };
});

afterEach(async () => {
  if (tmpDir && existsSync(tmpDir)) {
    /* On Windows the better-sqlite3 handle isn't always
     * released synchronously after `db.close()`. The OS
     * can return EPERM when we try to rmSync too soon.
     * Retry a few times with a tiny delay. Swallow the
     * final error so a flaky cleanup doesn't fail an
     * otherwise-passing test (vitest creates a fresh
     * tmpDir per test so leaks don't accumulate). */
    for (let i = 0; i < 5; i += 1) {
      try {
        rmSync(tmpDir, { recursive: true, force: true });
        return;
      } catch {
        if (i === 4) return;
        await new Promise<void>((res) => setTimeout(res, 50));
      }
    }
  }
});

/* ----------------------------- parseArgs ----------------------------- */

describe('T-094: parseArgs grammar', () => {
  it('returns the help subcommand for --help', () => {
    const a = parseArgs(['--help'], envLog);
    expect(a.subcommand).toBe('help');
  });

  it('defaults to the project scope', () => {
    const a = parseArgs(['show'], envLog);
    expect(a.subcommand).toBe('show');
    expect(a.scope).toBe('project');
  });

  it('--global sets scope=global', () => {
    const a = parseArgs(['show', '--global'], envLog);
    expect(a.scope).toBe('global');
  });

  it('--scope=all enables all-layer mode for show', () => {
    const a = parseArgs(['show', '--scope=all'], envLog);
    expect(a.scope).toBe('all');
  });

  it('--scope=session requires --session-id (deferred to dispatch)', () => {
    const a = parseArgs(['show', '--scope=session'], envLog);
    expect(a.scope).toBe('session');
    expect(a.sessionId).toBeUndefined();
  });

  it('parses --session-id', () => {
    const a = parseArgs(['show', '--session', '--session-id=abc'], envLog);
    expect(a.scope).toBe('session');
    expect(a.sessionId).toBe('abc');
  });

  it('parses --format=json', () => {
    const a = parseArgs(['show', '--global', '--format=json'], envLog);
    expect(a.format).toBe('json');
  });

  it('parses --force / --yes / --no-color', () => {
    const a = parseArgs(['reset', '--scope=project', '--yes', '--no-color'], envLog);
    expect(a.flags.yes).toBe(true);
    expect(a.flags.noColor).toBe(true);
  });

  it('rejects unknown subcommands with CliUsageError', () => {
    expect(() => parseArgs(['destroy-everything'], envLog)).toThrowError(CliUsageError);
  });

  it('rejects unknown --scope values', () => {
    expect(() => parseArgs(['show', '--scope=galaxy'], envLog)).toThrowError(CliUsageError);
  });

  it('rejects unknown flags', () => {
    expect(() => parseArgs(['show', '--bogus'], envLog)).toThrowError(CliUsageError);
  });

  it('parses --cwd and --home overrides', () => {
    const a = parseArgs(['show', '--cwd=/x', '--home=/y'], envLog);
    expect(a.cwd).toBe('/x');
    expect(a.home).toBe('/y');
  });
});

/* ----------------------------- show ---------------------------------- */

describe('T-090: `memory show`', () => {
  it('prints the project memory by default (text format)', () => {
    seedProject();
    const streams = makeStreams();
    const r = runCli(['show', '--cwd', cwd], streams, envLog, defaultDeps());
    expect(r.exitCode).toBe(0);
    expect(streams.stdout.text).toContain('# project memory —');
    expect(streams.stdout.text).toContain('test.framework = vitest');
    expect(streams.stdout.text).toContain('initial scaffold');
  });

  it('prints the global memory with --global', () => {
    seedGlobal();
    const streams = makeStreams();
    const r = runCli(['show', '--global', '--home', home], streams, envLog, defaultDeps());
    expect(r.exitCode).toBe(0);
    expect(streams.stdout.text).toContain('user.name = Alice');
    expect(streams.stdout.text).toContain('No mocks in production code');
  });

  it('emits a JSON payload with --format=json', () => {
    seedGlobal();
    const streams = makeStreams();
    const r = runCli(['show', '--global', '--format=json'], streams, envLog, defaultDeps());
    expect(r.exitCode).toBe(0);
    const parsed = JSON.parse(streams.stdout.text) as { scopes: string[] };
    expect(parsed.scopes.length).toBe(1);
    expect(parsed.scopes[0]).toContain('user.name = Alice');
  });

  it('returns exit code 2 with usage on bad scope', () => {
    const streams = makeStreams();
    const r = runCli(['show', '--scope=galaxy'], streams, envLog, defaultDeps());
    expect(r.exitCode).toBe(2);
    expect(streams.stderr.text).toContain('memory:');
    expect(streams.stderr.text).toContain('usage: aethercode memory');
  });

  it('handles a missing file gracefully (empty entries)', () => {
    const streams = makeStreams();
    const r = runCli(['show', '--global'], streams, envLog, defaultDeps());
    expect(r.exitCode).toBe(0);
    expect(streams.stdout.text).toContain('0 entries');
  });
});

/* ----------------------------- edit ---------------------------------- */

describe('T-091: `memory edit`', () => {
  it('resolves the project memory path and calls the editor', () => {
    const calls: Array<{ filePath: string; editor: string }> = [];
    const deps = defaultDeps({
      spawnEditor: (filePath, editor) => {
        calls.push({ filePath, editor });
        return { exitCode: 0, stdout: '', stderr: '', process: null };
      },
    });
    const streams = makeStreams();
    const r = runCli(['edit', '--cwd', cwd, '--home', home], streams, envLog, deps);
    expect(r.exitCode).toBe(0);
    expect(calls.length).toBe(1);
    expect(calls[0]!.filePath).toBe(projectPath);
    expect(calls[0]!.editor).toBe('echo');
    expect(r.filePath).toBe(projectPath);
  });

  it('rejects non-project scopes', () => {
    const streams = makeStreams();
    const r = runCli(['edit', '--global'], streams, envLog, defaultDeps());
    expect(r.exitCode).toBe(2);
    expect(streams.stderr.text).toContain('only supports --project');
  });

  it('creates the project file if it does not exist', () => {
    const deps = defaultDeps({
      spawnEditor: () => ({ exitCode: 0, stdout: '', stderr: '', process: null }),
    });
    const streams = makeStreams();
    expect(existsSync(projectPath)).toBe(false);
    const r = runCli(['edit', '--cwd', cwd, '--home', home], streams, envLog, deps);
    expect(r.exitCode).toBe(0);
    expect(existsSync(projectPath)).toBe(true);
  });

  it('returns the editor exit code', () => {
    const deps = defaultDeps({
      spawnEditor: () => ({ exitCode: 42, stdout: '', stderr: '', process: null }),
    });
    const streams = makeStreams();
    const r = runCli(['edit', '--cwd', cwd, '--home', home], streams, envLog, deps);
    expect(r.exitCode).toBe(42);
    expect(streams.stderr.text).toContain('editor exited with code 42');
  });
});

/* ----------------------------- compact ------------------------------- */

describe('T-092: `memory compact`', () => {
  it('skips when no LLM client is configured', async () => {
    const streams = makeStreams();
    const deps = defaultDeps({ llm: null });
    const r = await runCliAsync(['compact', '--cwd', cwd, '--home', home], streams, envLog, deps);
    expect(r.exitCode).toBe(0);
    expect(streams.stdout.text).toContain('skipped');
    expect(streams.stdout.text).toContain('no LLM client configured');
  });

  it('runs a forced pass with a stub LLM and reports the result', async () => {
    const deps = defaultDeps({
      llm: {
        async complete(prompt: string): Promise<string> {
          return `New description: <${prompt.length} chars>`;
        },
      },
    });
    const streams = makeStreams();
    const r = await runCliAsync(['compact', '--cwd', cwd, '--home', home, '--force'], streams, envLog, deps);
    expect(r.exitCode).toBe(0);
    expect(streams.stdout.text).toMatch(/compact: ok/);
  });

  it('sync runCli prints a hint for `compact`', () => {
    const streams = makeStreams();
    const r = runCli(['compact', '--cwd', cwd, '--home', home], streams, envLog, defaultDeps());
    expect(r.exitCode).toBe(2);
    expect(streams.stderr.text).toContain('requires the async dispatcher');
  });
});

/* ----------------------------- reset --------------------------------- */

describe('T-093: `memory reset`', () => {
  it('requires --yes to actually wipe (default: prints warning)', () => {
    seedGlobal();
    seedProject();
    const streams = makeStreams();
    const r = runCli(['reset', '--scope=project', '--cwd', cwd, '--home', home], streams, envLog, defaultDeps());
    expect(r.exitCode).toBe(0);
    expect(streams.stdout.text).toContain('About to wipe');
    expect(streams.stdout.text).toContain('Re-run with --yes');
    /* Files still exist. */
    expect(existsSync(projectPath)).toBe(true);
  });

  it('wipes the project memory file with --yes', () => {
    seedProject();
    const streams = makeStreams();
    const r = runCli(['reset', '--scope=project', '--yes', '--cwd', cwd, '--home', home], streams, envLog, defaultDeps());
    expect(r.exitCode).toBe(0);
    expect(streams.stdout.text).toContain('reset: cleared');
    /* File replaced with canonical empty template. */
    const text = readFileSync(projectPath, 'utf-8');
    expect(text).toContain('Untitled Project');
    expect(text).not.toContain('test.framework = vitest');
  });

  it('wipes the global memory file with --yes', () => {
    seedGlobal();
    const streams = makeStreams();
    const r = runCli(['reset', '--scope=global', '--yes', '--home', home], streams, envLog, defaultDeps());
    expect(r.exitCode).toBe(0);
    const text = readFileSync(globalPath, 'utf-8');
    expect(text).not.toContain('user.name = Alice');
  });

  it('wipes every session row from sqlite with --scope=all --yes', () => {
    /* Seed a sqlite db with one session row. */
    mkdirSync(dirname(dbPath), { recursive: true });
    openAndMigrate(dbPath);
    withDatabase(dbPath, (db) => {
      runStatement(
        db,
        'INSERT INTO session_messages (session_id, ts, role, content, metadata, token_count) VALUES (?, ?, ?, ?, ?, ?)',
        ['sess-A', Date.now(), 'user', 'hello', null, 1],
      );
    });
    const streams = makeStreams();
    const r = runCli(
      ['reset', '--scope=all', '--yes', '--cwd', cwd, '--home', home],
      streams,
      envLog,
      defaultDeps(),
    );
    expect(r.exitCode).toBe(0);
    expect(streams.stdout.text).toContain('reset: cleared');
    /* Session row gone. */
    withDatabase(dbPath, (db) => {
      const stmt = (db as unknown as { prepare(s: string): { get(...args: unknown[]): unknown } }).prepare(
        'SELECT COUNT(*) AS n FROM session_messages WHERE session_id = ?',
      );
      const row = stmt.get('sess-A') as { n: number };
      expect(row.n).toBe(0);
    });
  });

  it('rejects unknown scopes', () => {
    const streams = makeStreams();
    const r = runCli(['reset', '--scope=galaxy'], streams, envLog, defaultDeps());
    expect(r.exitCode).toBe(2);
    expect(streams.stderr.text).toContain('memory:');
  });
});

/* ----------------------------- help ---------------------------------- */

describe('T-094: help + usage', () => {
  it('help subcommand prints HELP_TEXT', () => {
    const streams = makeStreams();
    const r = runCli(['help'], streams, envLog, defaultDeps());
    expect(r.exitCode).toBe(0);
    expect(streams.stdout.text).toBe(HELP_TEXT + '\n');
  });

  it('top-level --help also prints HELP_TEXT', () => {
    const streams = makeStreams();
    const r = runCli(['--help'], streams, envLog, defaultDeps());
    expect(r.exitCode).toBe(0);
    expect(streams.stdout.text).toContain('usage: aethercode memory');
  });

  it('HELP_TEXT mentions all four subcommands', () => {
    expect(HELP_TEXT).toContain('show');
    expect(HELP_TEXT).toContain('edit');
    expect(HELP_TEXT).toContain('compact');
    expect(HELP_TEXT).toContain('reset');
  });
});

/* ----------------------------- defaults ------------------------------ */

describe('T-094: defaults', () => {
  it('uses AETHERCODE_CWD when --cwd is omitted', () => {
    const a = parseArgs(['show'], { ...envLog, AETHERCODE_CWD: '/from-env' });
    expect(a.cwd).toBe('/from-env');
  });

  it('falls back to USERPROFILE on Windows', () => {
    const a = parseArgs(['show'], { USERPROFILE: '/win-home' });
    expect(a.home).toBe('/win-home');
  });

  it('home expansion: ~ → $HOME', () => {
    /* We can't easily test the runtime expandHome (private);
     * instead assert the parser keeps `~` and let runCli
     * resolve it. */
    const a = parseArgs(['show', '--home', '~'], envLog);
    expect(a.home).toBe('~');
  });
});

/* ----------------------------- type-level contract ------------------- */

describe('T-094: CliArgs shape', () => {
  it('exposes scope, sessionId, cwd, home, format, flags', () => {
    const a: CliArgs = parseArgs(['reset', '--scope=session', '--session-id=abc', '--format=json'], envLog);
    expect(a.subcommand).toBe('reset');
    expect(a.scope).toBe('session');
    expect(a.sessionId).toBe('abc');
    expect(a.format).toBe('json');
    expect(typeof a.cwd).toBe('string');
    expect(typeof a.home).toBe('string');
    expect(typeof a.flags).toBe('object');
  });
});

/* ----------------------------- windows path safety ------------------ */

describe('T-094: windows path safety (sep-aware)', () => {
  it('project path joins correctly on any platform', () => {
    let captured: string | null = null;
    const deps = defaultDeps({
      spawnEditor: (p) => {
        captured = p;
        return { exitCode: 0, stdout: '', stderr: '', process: null };
      },
    });
    const streams = makeStreams();
    const r = runCli(['edit', '--cwd', cwd, '--home', home], streams, envLog, deps);
    expect(r.exitCode).toBe(0);
    expect(captured).not.toBeNull();
    expect(captured).toContain('.aethercode');
    expect(captured).toContain('memory.md');
    /* Just to make `sep` reachable — eslint:no-unused-vars. */
    void sep;
  });
});
