#!/usr/bin/env node
/**
 * aethercode-memory CLI — T-090 ~ T-093.
 *
 * Four subcommands, matching the spec in `spec.md §1.5` and
 * `design.md §1.5`:
 *
 *   aethercode memory show [--global|--project|--session]
 *   aethercode memory edit --project
 *   aethercode memory compact
 *   aethercode memory reset --scope=<scope>
 *
 * The CLI is intentionally tiny and dependency-free (no
 * commander / yargs) so the bin can be installed on a
 * minimal Node setup. The argument grammar is permissive —
 * we only need to recognise the four subcommands + a small
 * set of flags.
 *
 * Design anchors:
 *   - spec.md §1.5 "Functional requirements"
 *   - design.md §1.5 cwd switch / §1.6 RPC surface
 *   - tasks.md T-090 ~ T-094
 *
 * Why a separate file? `aethercode-memory`'s public surface
 * is the `MemoryStore` class; the CLI is a thin consumer of
 * it. Keeping the CLI in its own module means:
 *   - the core library has zero `process.argv` / `console`
 *     dependencies, easier to embed in a daemon / TUI,
 *   - tests can call `runCli(args, env, deps)` directly with
 *     stub IO, file-system overrides, and a fake LLM, so we
 *     don't have to spawn a child process.
 *
 * `main()` is only invoked when the file is run directly
 * (the `ac-mem` bin in package.json). When imported as a
 * module, the consumers see only `runCli` and the option /
 * result types.
 */

import { existsSync, writeFileSync, mkdirSync, readFileSync } from 'node:fs';
import { dirname, join, resolve, sep } from 'node:path';
import { homedir } from 'node:os';
import { spawnSync, type ChildProcess } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { realpathSync } from 'node:fs';

import {
  createMemoryStore,
  ensureProjectFile,
  type MemoryStore,
} from './memory-store.js';
import { parseGlobalMemory } from './markdown.js';
import { parseProjectMemory } from './project-markdown.js';
import {
  adaptMemoryStore,
  runCompressionPass,
  type CompactResult,
  type CompressionLlmClient,
  type CompressionOptions,
} from './compression.js';
import { isMemoryScope, type MemoryEntry, type MemoryScope } from './types.js';
import { withDatabase, queryAll, openAndMigrate } from './sqlite.js';
import { deleteSession } from './session-store.js';

/* ----------------------------- types --------------------------------- */

/** The five subcommands the CLI accepts. `help` is a meta
 *  command — it never appears in the task list but is
 *  reachable from `--help`. */
export type MemorySubcommand = 'show' | 'edit' | 'compact' | 'reset' | 'help';

/** Parsed CLI arguments. */
export interface CliArgs {
  readonly subcommand: MemorySubcommand;
  /** One of `global` / `project` / `session` / `all`. */
  readonly scope: MemoryScope | 'all';
  /** Session id, only meaningful for `scope=session`. */
  readonly sessionId?: string;
  /** `--cwd` override (project memory file lives under here). */
  readonly cwd: string;
  /** `--home` override (global memory file lives under here). */
  readonly home: string;
  /** `--format json` (show only). */
  readonly format: 'text' | 'json';
  /** `--no-color` / `--force` / `--yes` flag carrier. */
  readonly flags: Readonly<{
    force?: boolean;
    noColor?: boolean;
    yes?: boolean;
  }>;
}

/** The result of a CLI invocation. */
export interface CliResult {
  /** Process exit code (0 = success, non-zero = error). */
  readonly exitCode: number;
  /** Captured stdout. */
  readonly stdout: string;
  /** Captured stderr. */
  readonly stderr: string;
  /** When the subcommand is `edit`, this is the resolved file path
   *  the caller should hand to their editor. */
  readonly filePath?: string;
}

/** A writable stream pair the CLI uses for output. Tests inject
 *  in-memory streams so they can assert on the captured output. */
export interface CliStreams {
  readonly stdout: { write(s: string): void };
  readonly stderr: { write(s: string): void };
}

/** Filesystem + side-effecting dependencies. Tests can inject
 *  fakes to avoid touching the real disk. */
export interface CliDeps {
  readonly fs: {
    existsSync(p: string): boolean;
    readFileSync(p: string, encoding: 'utf-8'): string;
    writeFileSync(p: string, data: string, encoding: 'utf-8'): void;
    mkdirSync(p: string, opts: { recursive: boolean }): void;
    rmSync(p: string, opts: { recursive: boolean; force: boolean }): void;
  };
  /** Resolved file path to a project / global memory file, etc. */
  readonly paths: {
    globalMemory(home: string): string;
    projectMemory(cwd: string): string;
    sessionsDir(home: string): string;
    dbPath(home: string): string;
  };
  /** LLM client used by `compact`. Default: `null` (no LLM, force
   *  pass returns "skipped" which is fine for the offline CLI). */
  readonly llm: CompressionLlmClient | null;
  /** Spawn a child process to run `$EDITOR`. Default: real spawn. */
  readonly spawnEditor?: (filePath: string, editor: string) => SpawnResult;
  /** Override the current time, used for deterministic output. */
  readonly now?: () => number;
}

/** A minimal handle for what `spawnEditor` returns. We avoid
 *  exposing `ChildProcess` in the public type so tests don't
 *  have to construct a real one. */
export interface SpawnResult {
  readonly exitCode: number;
  readonly stdout: string;
  readonly stderr: string;
  readonly process: ChildProcess | null;
}

/* ----------------------------- defaults ------------------------------ */

const DEFAULT_CWD = '.';
const DEFAULT_HOME = '~';
const DEFAULT_FORMAT: 'text' | 'json' = 'text';

/** A node-style "real IO" dep — the production wiring. */
const realDeps = (): CliDeps => ({
  fs: {
    existsSync,
    readFileSync: ((p: string) => readFileSync(p, 'utf-8')) as CliDeps['fs']['readFileSync'],
    writeFileSync: ((p: string, data: string) => writeFileSync(p, data, 'utf-8')) as CliDeps['fs']['writeFileSync'],
    mkdirSync: ((p: string, opts: { recursive: boolean }) => mkdirSync(p, opts)) as CliDeps['fs']['mkdirSync'],
    rmSync: (p, opts) => {
      /* Lazily require to keep the module surface tight. */
      // eslint-disable-next-line @typescript-eslint/no-require-imports
      const { rmSync } = require('node:fs') as typeof import('node:fs');
      rmSync(p, opts);
    },
  },
  paths: {
    globalMemory: (home) => join(home, '.aethercode', 'memory.md'),
    projectMemory: (cwd) => join(cwd, '.aethercode', 'memory.md'),
    sessionsDir: (home) => join(home, '.aethercode', 'sessions'),
    dbPath: (home) => join(home, '.aethercode', 'sessions.db'),
  },
  llm: null,
  spawnEditor: (filePath, editor) => {
    const r = spawnSync(editor, [filePath], { stdio: 'inherit' });
    return {
      exitCode: r.status ?? 1,
      stdout: '',
      stderr: r.error?.message ?? '',
      process: null,
    };
  },
});

/* ----------------------------- argument parser ----------------------- */

/**
 * Tokenise + classify the argv. We intentionally keep the parser
 * minimal so the grammar is self-documenting and easy to extend
 * (e.g. T-100+ rounds will likely add `memory import` /
 * `memory export`).
 */
export function parseArgs(argv: ReadonlyArray<string>, env: NodeJS.ProcessEnv = process.env): CliArgs {
  const out: {
    subcommand: MemorySubcommand;
    scope: MemoryScope | 'all';
    sessionId?: string;
    cwd: string;
    home: string;
    format: 'text' | 'json';
    flags: { force?: boolean; noColor?: boolean; yes?: boolean };
  } = {
    subcommand: 'help',
    scope: 'project',
    cwd: env['AETHERCODE_CWD'] ?? DEFAULT_CWD,
    home: env['HOME'] ?? env['USERPROFILE'] ?? DEFAULT_HOME,
    format: DEFAULT_FORMAT,
    flags: {},
  };

  let i = 0;
  /* Flags that may appear anywhere on the line. */
  for (; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === undefined) break;
    /* Normalise `--key=value` to `--key value` for the switch
     * below. We don't need a full tokeniser; just split on the
     * first `=` and remember the rhs. */
    let flag = a;
    let inlineValue: string | undefined;
    const eq = flag.indexOf('=');
    if (eq > 0) {
      inlineValue = flag.slice(eq + 1);
      flag = flag.slice(0, eq);
    }
    if (flag === '--cwd') {
      const v = inlineValue ?? argv[++i];
      if (v === undefined) throw new CliUsageError('--cwd requires a value');
      out.cwd = v;
    } else if (flag === '--home') {
      const v = inlineValue ?? argv[++i];
      if (v === undefined) throw new CliUsageError('--home requires a value');
      out.home = v;
    } else if (flag === '--scope') {
      const v = inlineValue ?? argv[++i];
      if (v === undefined) throw new CliUsageError('--scope requires a value');
      if (!isMemoryScope(v) && v !== 'all') {
        throw new CliUsageError(`unknown --scope "${v}" (try global, project, session, all)`);
      }
      out.scope = v;
    } else if (flag === '--global') {
      out.scope = 'global';
    } else if (flag === '--project') {
      out.scope = 'project';
    } else if (flag === '--session') {
      out.scope = 'session';
    } else if (flag === '--session-id') {
      const v = inlineValue ?? argv[++i];
      if (v === undefined) throw new CliUsageError('--session-id requires a value');
      out.sessionId = v;
    } else if (flag === '--format') {
      const v = inlineValue ?? argv[++i];
      if (v === undefined || (v !== 'text' && v !== 'json')) {
        throw new CliUsageError(`unknown --format "${v ?? ''}" (try text, json)`);
      }
      out.format = v;
    } else if (flag === '--force') {
      out.flags.force = true;
    } else if (flag === '--no-color') {
      out.flags.noColor = true;
    } else if (flag === '--yes' || flag === '-y') {
      out.flags.yes = true;
    } else if (flag === '-h' || flag === '--help' || flag === 'help') {
      out.subcommand = 'help';
      return out as unknown as CliArgs;
    } else if (flag === '-v' || flag === '--version') {
      out.subcommand = 'help';
      return out as unknown as CliArgs;
    } else if (flag.startsWith('-')) {
      throw new CliUsageError(`unknown flag: ${a}`);
    } else {
      /* Positional: the first one is the subcommand. */
      const sub = String(flag).toLowerCase();
      if (sub !== 'show' && sub !== 'edit' && sub !== 'compact' && sub !== 'reset' && sub !== 'help') {
        throw new CliUsageError(`unknown subcommand: ${flag} (try show, edit, compact, reset)`);
      }
      out.subcommand = sub;
      i += 1;
      break;
    }
  }
  /* Trailing flags after the subcommand (e.g. `memory compact --force`).
   * Re-enter the same flag handler so flags like `--scope=...` work
   * regardless of position. The loop terminates when we hit a
   * positional or run out of args. */
  for (; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === undefined) continue;
    let flag = a;
    let inlineValue: string | undefined;
    const eq = flag.indexOf('=');
    if (eq > 0) {
      inlineValue = flag.slice(eq + 1);
      flag = flag.slice(0, eq);
    }
    if (flag === '--scope') {
      const v = inlineValue ?? argv[++i];
      if (v === undefined) throw new CliUsageError('--scope requires a value');
      if (!isMemoryScope(v) && v !== 'all') {
        throw new CliUsageError(`unknown --scope "${v}" (try global, project, session, all)`);
      }
      out.scope = v;
    } else if (flag === '--global') {
      out.scope = 'global';
    } else if (flag === '--project') {
      out.scope = 'project';
    } else if (flag === '--session') {
      out.scope = 'session';
    } else if (flag === '--session-id') {
      const v = inlineValue ?? argv[++i];
      if (v === undefined) throw new CliUsageError('--session-id requires a value');
      out.sessionId = v;
    } else if (flag === '--format') {
      const v = inlineValue ?? argv[++i];
      if (v === undefined || (v !== 'text' && v !== 'json')) {
        throw new CliUsageError(`unknown --format "${v ?? ''}" (try text, json)`);
      }
      out.format = v;
    } else if (flag === '--force') {
      out.flags.force = true;
    } else if (flag === '--no-color') {
      out.flags.noColor = true;
    } else if (flag === '--yes' || flag === '-y') {
      out.flags.yes = true;
    } else if (flag === '--cwd') {
      const v = inlineValue ?? argv[++i];
      if (v === undefined) throw new CliUsageError('--cwd requires a value');
      out.cwd = v;
    } else if (flag === '--home') {
      const v = inlineValue ?? argv[++i];
      if (v === undefined) throw new CliUsageError('--home requires a value');
      out.home = v;
    } else {
      /* Unknown flag → error. */
      throw new CliUsageError(`unknown flag: ${a}`);
    }
  }
  return out as CliArgs;
}

/** Thrown by the parser / dispatcher on bad usage. The CLI
 *  catches this and prints a helpful message + usage to stderr. */
export class CliUsageError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'CliUsageError';
  }
}

/* ----------------------------- help ---------------------------------- */

export const HELP_TEXT = [
  'aethercode memory — view, edit, compact, and reset the 3-layer memory store',
  '',
  'usage: aethercode memory <subcommand> [flags]',
  '',
  'subcommands:',
  '  show          print the entries in one or all layers',
  '  edit          open the project memory file in $EDITOR (T-091)',
  '  compact       force a project-memory compression pass (T-092)',
  '  reset         wipe a layer (T-093)',
  '  help          show this help',
  '',
  'flags:',
  '  --global                    show the global memory (default for `show`: project)',
  '  --project                   show the project memory',
  '  --session                   show the session memory (needs --session-id)',
  '  --scope=<global|project|session|all>  explicit scope selector',
  '  --session-id=<id>           session id for scope=session',
  '  --format=<text|json>        output format for `show` (default text)',
  '  --force                     `compact`: ignore trigger threshold',
  '                             `reset`: skip confirmation prompt',
  '  --yes, -y                   `reset`: assume yes for the confirmation',
  '  --cwd=<path>                project cwd (default: $AETHERCODE_CWD or ".")',
  '  --home=<path>               home dir (default: $HOME or $USERPROFILE)',
  '  --no-color                  disable ANSI colour in output',
  '  -h, --help                  show this help',
  '  -v, --version               print version',
  '',
  'env:',
  '  EDITOR                      editor to launch for `edit` (default: notepad on Windows, vi elsewhere)',
  '  AETHERCODE_CWD              default value for --cwd',
  '',
  'examples:',
  '  aethercode memory show --project',
  '  aethercode memory show --global --format=json',
  '  aethercode memory edit --project',
  '  aethercode memory compact --force',
  '  aethercode memory reset --scope=project --yes',
].join('\n');

/* ----------------------------- dispatch ----------------------------- */

/**
 * Programmatic entry point. Tests pass a stub `streams` + `deps`
 * to assert on captured output without touching the real FS or
 * spawning a process. The production wiring is in `main()`.
 *
 * Most subcommands are synchronous. `compact` is async because
 * the LLM call returns a Promise. We expose the sync version
 * (this function) for the common case and a separate
 * `runCliAsync` for the async path. The test wiring picks the
 * one it needs based on the subcommand.
 */
export function runCli(
  argv: ReadonlyArray<string>,
  streams: CliStreams,
  env: NodeJS.ProcessEnv = process.env,
  depsIn?: Partial<CliDeps>,
): CliResult {
  const deps: CliDeps = { ...realDeps(), ...(depsIn ?? {}) };
  let args: CliArgs;
  try {
    args = parseArgs(argv, env);
  } catch (err) {
    if (err instanceof CliUsageError) {
      const msg = `memory: ${err.message}\n\n${HELP_TEXT}\n`;
      streams.stderr.write(msg);
      return { exitCode: 2, stdout: '', stderr: msg };
    }
    throw err;
  }

  try {
    switch (args.subcommand) {
      case 'help':
        streams.stdout.write(`${HELP_TEXT}\n`);
        return { exitCode: 0, stdout: HELP_TEXT + '\n', stderr: '' };
      case 'show':
        return runShow(args, streams, deps);
      case 'edit':
        return runEdit(args, streams, deps, env);
      case 'reset':
        return runReset(args, streams, deps);
      case 'compact':
        /* The async path goes through `runCliAsync`. The sync
         * entrypoint is a guard for the production bin so it
         * can refuse to call an async function synchronously
         * — see `main()`. */
        streams.stderr.write(
          'memory: `compact` requires the async dispatcher; use `runCliAsync` or the `ac-mem` bin\n',
        );
        return { exitCode: 2, stdout: '', stderr: 'compact requires async' };
    }
  } catch (err) {
    if (err instanceof CliUsageError) {
      const msg = `memory: ${err.message}\n`;
      streams.stderr.write(msg);
      return { exitCode: 2, stdout: '', stderr: msg };
    }
    const message = err instanceof Error ? err.message : String(err);
    streams.stderr.write(`memory: ${message}\n`);
    return { exitCode: 1, stdout: '', stderr: message + '\n' };
  }
  /* Unreachable in practice — the union exhausts every value. */
  return { exitCode: 1, stdout: '', stderr: 'memory: unknown subcommand\n' };
}

/** Async variant — required for `compact`, which can call the
 *  LLM. Tests can await this directly; the production bin
 *  awaits it in `main()`. */
export async function runCliAsync(
  argv: ReadonlyArray<string>,
  streams: CliStreams,
  env: NodeJS.ProcessEnv = process.env,
  depsIn?: Partial<CliDeps>,
): Promise<CliResult> {
  const deps: CliDeps = { ...realDeps(), ...(depsIn ?? {}) };
  let args: CliArgs;
  try {
    args = parseArgs(argv, env);
  } catch (err) {
    if (err instanceof CliUsageError) {
      const msg = `memory: ${err.message}\n\n${HELP_TEXT}\n`;
      streams.stderr.write(msg);
      return { exitCode: 2, stdout: '', stderr: msg };
    }
    throw err;
  }
  try {
    switch (args.subcommand) {
      case 'compact':
        return await runCompact(args, streams, deps);
      default:
        return runCli(argv, streams, env, depsIn);
    }
  } catch (err) {
    if (err instanceof CliUsageError) {
      const msg = `memory: ${err.message}\n`;
      streams.stderr.write(msg);
      return { exitCode: 2, stdout: '', stderr: msg };
    }
    const message = err instanceof Error ? err.message : String(err);
    streams.stderr.write(`memory: ${message}\n`);
    return { exitCode: 1, stdout: '', stderr: message + '\n' };
  }
}

/* ----------------------------- show ---------------------------------- */

function runShow(args: CliArgs, streams: CliStreams, deps: CliDeps): CliResult {
  const home = expandHome(args.home, deps);
  const cwd = resolveCwd(args.cwd, deps);
  const globalPath = deps.paths.globalMemory(home);
  const projectPath = deps.paths.projectMemory(cwd);

  const scopes: ReadonlyArray<MemoryScope> =
    args.scope === 'all' ? ['global', 'project', 'session'] : [args.scope as MemoryScope];

  const blocks: string[] = [];
  for (const scope of scopes) {
    if (scope === 'session') {
      if (!args.sessionId) {
        throw new CliUsageError('scope=session requires --session-id=<id>');
      }
      const sessionEntries = readSessionEntries(home, args.sessionId, deps);
      blocks.push(formatBlock('session', args.sessionId, sessionEntries, deps));
    } else if (scope === 'global') {
      const entries = readGlobalEntries(globalPath, deps);
      blocks.push(formatBlock('global', globalPath, entries, deps));
    } else {
      const entries = readProjectEntries(projectPath, deps);
      blocks.push(formatBlock('project', projectPath, entries, deps));
    }
  }

  let body: string;
  if (args.format === 'json') {
    const payload = { scopes: blocks.map((b) => b) };
    body = JSON.stringify(payload, null, 2) + '\n';
  } else {
    body = blocks.join('\n') + '\n';
  }
  streams.stdout.write(body);
  return { exitCode: 0, stdout: body, stderr: '' };
}

function formatBlock(
  scope: MemoryScope,
  source: string,
  entries: ReadonlyArray<MemoryEntry>,
  _deps: CliDeps,
): string {
  /* Group entries by kind so the printed layout mirrors the
   * underlying markdown file. The CLI's "show" is the
   * human-readable counterpart to `memory/list`. */
  const facts = entries.filter((e) => e.kind === 'fact');
  const rules = entries.filter((e) => e.kind === 'rule');
  const changes = entries.filter((e) => e.kind === 'change');
  const crumbs = entries.filter((e) => e.kind === 'breadcrumb');
  const lines: string[] = [];
  lines.push(`# ${scope} memory — ${source}`);
  lines.push(`# ${entries.length} entries`);
  if (facts.length > 0) {
    lines.push('');
    lines.push('## Facts');
    for (const f of facts) {
      const fact = f as Extract<MemoryEntry, { kind: 'fact' }>;
      lines.push(`  - ${fact.key} = ${fact.value}`);
    }
  }
  if (rules.length > 0) {
    lines.push('');
    lines.push('## Rules');
    for (const r of rules) {
      const rule = r as Extract<MemoryEntry, { kind: 'rule' }>;
      lines.push(`  - ${rule.text}`);
    }
  }
  if (changes.length > 0) {
    lines.push('');
    lines.push('## Changes');
    for (const c of changes) {
      const ch = c as Extract<MemoryEntry, { kind: 'change' }>;
      const tag = ch.compressed ? '[compressed]' : '';
      lines.push(`  - ${new Date(ch.ts).toISOString()} — ${ch.description} ${tag}`.trim());
    }
  }
  if (crumbs.length > 0) {
    lines.push('');
    lines.push('## Breadcrumbs');
    for (const b of crumbs) {
      const cr = b as Extract<MemoryEntry, { kind: 'breadcrumb' }>;
      lines.push(`  - ${new Date(cr.ts).toISOString().slice(0, 10)} — ${cr.message}`);
    }
  }
  return lines.join('\n');
}

/* ----------------------------- edit ---------------------------------- */

function runEdit(args: CliArgs, streams: CliStreams, deps: CliDeps, env: NodeJS.ProcessEnv): CliResult {
  /* T-091: only the project layer is editable from the CLI today.
   * Editing the global layer requires an interactive $EDITOR
   * flow and explicit confirmation; we surface a clear error
   * for any other scope so the user doesn't think we silently
   * edited the wrong file. */
  if (args.scope !== 'project') {
    throw new CliUsageError('`edit` only supports --project today (use `aethercode memory show --global` to inspect)');
  }
  const home = expandHome(args.home, deps);
  const cwd = resolveCwd(args.cwd, deps);
  const projectPath = deps.paths.projectMemory(cwd);

  /* Make sure the file exists before we hand it to the editor,
   * so a brand-new project opens with the canonical template
   * instead of a "no such file" error. */
  ensureProjectFile(projectPath, `project-${cwd.split(sep).pop() ?? 'untitled'}`, '');

  const editor = env['EDITOR'] && env['EDITOR'] !== '' ? env['EDITOR'] : defaultEditor();
  if (editor === '' || editor === null) {
    throw new CliUsageError('no editor configured — set $EDITOR');
  }
  if (deps.spawnEditor === undefined) {
    throw new Error('internal: spawnEditor missing on deps');
  }
  const r = deps.spawnEditor(projectPath, editor);
  streams.stdout.write(`opening ${projectPath} in ${editor}…\n`);
  if (r.exitCode !== 0) {
    streams.stderr.write(`memory: editor exited with code ${r.exitCode}\n`);
  }
  return {
    exitCode: r.exitCode,
    stdout: '',
    stderr: r.exitCode !== 0 ? `editor exit ${r.exitCode}` : '',
    filePath: projectPath,
  };
  /* Use home to satisfy the "noUnusedParameters" rule. */
  void home;
}

function defaultEditor(): string {
  if (process.platform === 'win32') return 'notepad';
  return 'vi';
}

/* ----------------------------- compact ------------------------------- */

async function runCompact(args: CliArgs, streams: CliStreams, deps: CliDeps): Promise<CliResult> {
  const home = expandHome(args.home, deps);
  const cwd = resolveCwd(args.cwd, deps);
  const globalPath = deps.paths.globalMemory(home);
  const projectPath = deps.paths.projectMemory(cwd);
  const sessionsDir = deps.paths.sessionsDir(home);
  const dbPath = deps.paths.dbPath(home);

  /* Open the MemoryStore — this also opens the sqlite db. The
   * store owns the DB handle; we close on every exit path. */
  const store = openStore(deps, {
    globalMemoryPath: globalPath,
    projectMemoryPath: projectPath,
    sessionsDir,
    dbPath,
    cwd,
  });
  try {
    if (deps.llm === null) {
      /* No LLM configured — the compression pipeline returns
       * `skipped: true` and we surface a friendly message. The
       * user can re-run with `--llm` in a future round; for
       * now we just print what would have happened. */
      const beforeTokens = store.getProject().totalTokens;
      const msg =
        `compact: skipped (no LLM client configured). ` +
        `Project memory has ~${beforeTokens} tokens; ` +
        `pass --llm to actually run the 4-shot prompt.\n`;
      streams.stdout.write(msg);
      return { exitCode: 0, stdout: msg, stderr: '' };
    }
    const result = await runCompressionPassWithDeps(store, deps, args.flags.force === true);
    if (result === null) {
      const msg = 'compact: skipped (nothing to compress)\n';
      streams.stdout.write(msg);
      return { exitCode: 0, stdout: msg, stderr: '' };
    }
    const summary =
      `compact: ok — ${result.beforeTokens}→${result.afterTokens} tokens, ` +
      `${result.changesCompressed} changes folded in ${result.ms}ms` +
      (result.resumed ? ' (resumed from WAL)' : '') +
      '\n';
    streams.stdout.write(summary);
    return { exitCode: 0, stdout: summary, stderr: '' };
  } finally {
    store.close();
  }
}

async function runCompressionPassWithDeps(
  store: MemoryStore,
  deps: CliDeps,
  force: boolean,
): Promise<CompactResult | null> {
  if (deps.llm === null) return null;
  const adapter = adaptMemoryStore({
    dbPath: store.dbPath,
    projectMemoryPath: store.projectMemoryPath,
    getProject: () => store.getProject(),
    invalidateProject: () => {
      const projectId = projectIdFromPath(store.projectMemoryPath);
      store.cache.invalidateScope('project', projectId);
    },
  });
  const config: CompressionOptions = {
    triggerHeadroom: force ? Number.POSITIVE_INFINITY : 0,
  };
  return await runCompressionPass({
    store: adapter,
    projectId: projectIdFromPath(store.projectMemoryPath),
    llm: deps.llm,
    config,
  });
}

function projectIdFromPath(p: string): string {
  /* Same FNV-1a 32-bit hash used by memory-store.ts. */
  let h = 0x811c9dc5;
  for (let i = 0; i < p.length; i += 1) {
    h ^= p.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return (h >>> 0).toString(16).padStart(8, '0');
}

/* ----------------------------- reset --------------------------------- */

function runReset(args: CliArgs, streams: CliStreams, deps: CliDeps): CliResult {
  const home = expandHome(args.home, deps);
  const cwd = resolveCwd(args.cwd, deps);

  /* Default to `all` for `reset` since the user has to type
   * `reset` to get here — the intent is destructive. */
  const target = describeResetTarget(args, home, cwd, deps);
  if (target === null) {
    throw new CliUsageError('`reset` requires --scope=<global|project|session|all>');
  }
  /* Confirmation gate: refuse to wipe without --yes unless
   * the user passed --force explicitly. The CLI's contract is
   * "explicit > implicit" — deleting the user's memory should
   * never be silent. */
  if (!args.flags.yes && !args.flags.force) {
    const msg =
      `About to wipe ${target.description}.\n` +
      'This cannot be undone. Re-run with --yes to confirm.\n';
    streams.stdout.write(msg);
    return { exitCode: 0, stdout: msg, stderr: '' };
  }
  const cleared = performReset(target, deps);
  const out =
    `reset: cleared ${cleared.entries} entries from ${target.label} ` +
    `(${cleared.files.length === 0 ? 'no files' : cleared.files.join(', ')})\n`;
  streams.stdout.write(out);
  return { exitCode: 0, stdout: out, stderr: '' };
}

interface ResetTarget {
  readonly label: string;
  readonly description: string;
  readonly globalPath?: string;
  readonly projectPath?: string;
  readonly sessionId?: string;
  readonly dbPath: string;
  readonly wipeAll: boolean;
}

interface ResetResult {
  readonly entries: number;
  readonly files: ReadonlyArray<string>;
}

function describeResetTarget(
  args: CliArgs,
  home: string,
  cwd: string,
  deps: CliDeps,
): ResetTarget | null {
  const dbPath = deps.paths.dbPath(home);
  if (args.scope === 'all') {
    return {
      label: 'all layers',
      description: 'GLOBAL + PROJECT + every SESSION',
      globalPath: deps.paths.globalMemory(home),
      projectPath: deps.paths.projectMemory(cwd),
      dbPath,
      wipeAll: true,
    };
  }
  if (args.scope === 'global') {
    return {
      label: 'global',
      description: 'the global memory file',
      globalPath: deps.paths.globalMemory(home),
      dbPath,
      wipeAll: false,
    };
  }
  if (args.scope === 'project') {
    return {
      label: 'project',
      description: 'the project memory file + its change log',
      projectPath: deps.paths.projectMemory(cwd),
      dbPath,
      wipeAll: false,
    };
  }
  if (args.scope === 'session') {
    if (!args.sessionId) {
      throw new CliUsageError('scope=session requires --session-id=<id>');
    }
    return {
      label: 'session',
      description: `session ${args.sessionId}`,
      sessionId: args.sessionId,
      dbPath,
      wipeAll: false,
    };
  }
  return null;
}

function performReset(target: ResetTarget, deps: CliDeps): ResetResult {
  const files: string[] = [];
  let entries = 0;
  if (target.globalPath !== undefined) {
    const before = readGlobalEntries(target.globalPath, deps).length;
    /* Overwrite the file with the canonical empty template.
     * `ensureGlobalFile` is a no-op when the file already
     * exists, so we have to write the empty form ourselves. */
    deps.fs.writeFileSync(
      target.globalPath,
      '# Global Memory\n\n## Facts\n\n## Rules\n\n## Cross-project breadcrumbs\n\n',
      'utf-8',
    );
    entries += before;
    files.push(target.globalPath);
  }
  if (target.projectPath !== undefined) {
    const before = readProjectEntries(target.projectPath, deps).length;
    /* Overwrite the file with the canonical empty template.
     * `ensureProjectFile` is a no-op when the file already
     * exists, so we have to write the empty form ourselves. */
    deps.fs.writeFileSync(
      target.projectPath,
      '# Untitled Project\n\n## 说明\n\n## 修改记录\n\n## Project facts\n\n',
      'utf-8',
    );
    entries += before;
    files.push(target.projectPath);
  }
  if (target.sessionId !== undefined) {
    const before = readSessionEntries(homedir(), target.sessionId, deps).length;
    /* Wipe just this session's rows. We use the high-level
     * `deleteSession` API; the connection is closed via
     * `withDatabase`. */
    if (existsSync(target.dbPath)) {
      withDatabase(target.dbPath, (db) => {
        deleteSession(db, target.sessionId as string);
      });
    }
    entries += before;
    files.push(`${target.dbPath}#session_messages[${target.sessionId}]`);
  }
  if (target.wipeAll) {
    /* In `wipeAll` mode we also delete every session row in
     * the sqlite db. This is the "nuclear" option — even if
     * the user has no project memory file, we drop every
     * session message. We use the high-level
     * `deleteSession` API from session-store. The db path
     * is a file path (not a connection); open it via
     * `openAndMigrate` so the schema is in place, then
     * enumerate the session ids and call `deleteSession`
     * for each. */
    if (existsSync(target.dbPath)) {
      const db = openAndMigrate(target.dbPath);
      try {
        const sessions = queryAll<{ session_id: string }>(
          db,
          'SELECT DISTINCT session_id FROM session_messages',
          [],
          (r) => ({ session_id: String(r['session_id'] ?? '') }),
        );
        for (const s of sessions) {
          deleteSession(db, s.session_id);
        }
      } finally {
        db.close();
      }
    }
  }
  return { entries, files };
}

/* ----------------------------- helpers ------------------------------- */

function readGlobalEntries(path: string, deps: CliDeps): ReadonlyArray<MemoryEntry> {
  if (!deps.fs.existsSync(path)) return [];
  const text = deps.fs.readFileSync(path, 'utf-8');
  const mem = parseGlobalMemory(text, Date.now());
  return [...mem.facts, ...mem.rules, ...mem.breadcrumbs];
}

function readProjectEntries(path: string, deps: CliDeps): ReadonlyArray<MemoryEntry> {
  if (!deps.fs.existsSync(path)) return [];
  const text = deps.fs.readFileSync(path, 'utf-8');
  const mem = parseProjectMemory(text, Date.now());
  return [...mem.changes, ...mem.facts];
}

function readSessionEntries(
  home: string,
  sessionId: string,
  _deps: CliDeps,
): ReadonlyArray<MemoryEntry> {
  /* Reading the session layer requires the sqlite db. We open
   * it via `withDatabase` so the connection is closed after
   * the read. */
  const dbPath = join(home, '.aethercode', 'sessions.db');
  if (!existsSync(dbPath)) return [];
  const rows = withDatabase(dbPath, (db) =>
    queryAll<{ ts: number; role: string; content: string }>(
      db,
      'SELECT ts, role, content FROM session_messages WHERE session_id = ? ORDER BY ts ASC',
      [sessionId],
      (r) => ({
        ts: Number(r['ts'] ?? 0),
        role: String(r['role'] ?? 'system'),
        content: String(r['content'] ?? ''),
      }),
    ),
  );
  return rows.map((r) => ({
    kind: 'fact',
    id: `session-fact-${sessionId}-${r.ts}`,
    ts: r.ts,
    scope: 'session',
    source: 'tool',
    tags: ['session', r.role],
    key: `msg.${r.ts}`,
    value: r.content,
  }));
}

/* `wipeSessionFromDb` was replaced by the higher-level
 * `deleteSession` API in `performReset`. Kept here as a
 * comment-only reference for callers that want to drop a
 * single session without going through the full
 * `ResetTarget` machinery. The implementation is
 *
 *   withDatabase(dbPath, (db) => deleteSession(db, sessionId));
 *
 * but inlining it in `performReset` avoids one layer of
 * indirection. */

function expandHome(home: string, _deps: CliDeps): string {
  if (home === '~' || home === '') {
    return homedir();
  }
  if (home.startsWith('~/') || home.startsWith('~\\')) {
    return join(homedir(), home.slice(2));
  }
  return home;
}

function resolveCwd(cwd: string, _deps: CliDeps): string {
  /* Allow `.` and absolute paths. The CLI follows the
   * `process.cwd()` contract for the bare `--cwd` case. */
  if (cwd === DEFAULT_CWD) {
    return process.cwd();
  }
  return resolve(cwd);
}

function openStore(deps: CliDeps, opts: {
  globalMemoryPath: string;
  projectMemoryPath: string;
  sessionsDir: string;
  dbPath: string;
  cwd: string;
}): MemoryStore {
  /* Ensure the parent directory exists before we hand the
   * paths to `createMemoryStore` — better-sqlite3 will fail
   * with `SQLITE_CANTOPEN` if the dir is missing. */
  deps.fs.mkdirSync(dirname(opts.globalMemoryPath), { recursive: true });
  deps.fs.mkdirSync(dirname(opts.projectMemoryPath), { recursive: true });
  deps.fs.mkdirSync(dirname(opts.dbPath), { recursive: true });
  deps.fs.mkdirSync(opts.sessionsDir, { recursive: true });
  return createMemoryStore(opts);
}

/* ----------------------------- main ---------------------------------- */

/** The bin entry point. We invoke it via the guard below so
 *  the module can also be imported in tests without side
 *  effects. */
export async function main(argv: ReadonlyArray<string> = process.argv.slice(2)): Promise<number> {
  const streams: CliStreams = {
    stdout: process.stdout,
    stderr: process.stderr,
  };
  /* `compact` is the only async subcommand. We could
   * `await runCliAsync` for every subcommand, but the sync
   * path is faster to debug and matches what `node:test`
   * expects. */
  const sub = argv[0];
  if (sub === 'compact') {
    const r = await runCliAsync(argv, streams, process.env);
    return r.exitCode;
  }
  const r = runCli(argv, streams, process.env);
  return r.exitCode;
}

/** Detect whether this file is being run as a bin. We do
 *  not import the bin's expected path so the test wiring
 *  can override `process.argv[1]`. */
function isDirectInvocation(): boolean {
  try {
    const invoked = fileURLToPath(import.meta.url);
    if (process.argv[1] === undefined) return false;
    if (process.argv[1] === invoked) return true;
    try {
      return realpathSync(process.argv[1]) === realpathSync(invoked);
    } catch {
      return false;
    }
  } catch {
    return false;
  }
}

if (isDirectInvocation()) {
  /* Top-level await is supported on Node 18+, which is our
   * declared engines floor. */
  main()
    .then((code) => {
      process.exit(code);
    })
    .catch((err: unknown) => {
      const message = err instanceof Error ? err.message : String(err);
      process.stderr.write(`memory: ${message}\n`);
      process.exit(1);
    });
}
