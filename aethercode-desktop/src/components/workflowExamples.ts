/**
 * starter workflow YAMLs.
 *
 * legacy-B, the workflow picker showed an empty state
 * the first time a user opened a fresh project — the
 * repo ships example YAMLs under `.aethercode/workflows/`
 * in the engine checkout, but a brand-new project has
 * nothing in its cwd yet. The user had to know about
 * `/workflow create` to bootstrap.
 *
 * the prior round inlines three ready-to-run example workflows
 * here so the picker can offer an "📥 Import examples (3)"
 * button when the list is empty. Each YAML is a complete
 * pipeline that exercises one or two step types so the
 * user can see the schema without reading the docs.
 *
 * Why inline instead of bundling a Tauri resource? Two
 * reasons:
 *   1. The renderer is the only place that surfaces the
 *      "import" affordance; bundling a separate file just
 *      to read it once on click would add an unnecessary
 *      IPC hop.
 *   2. The picker's empty-state is rendered in a popup
 *      that's mounted long after App start — bundling
 *      inline keeps the import logic self-contained.
 *
 * The YAMLs are written verbatim via {@code writeWorkflow}
 * which lands them in `<cwd>/.aethercode/workflows/<name>.yaml`
 * — the same directory the daemon's `listWorkflows` reads
 * from, so the next `refreshWorkflows()` picks them up.
 */

export interface WorkflowExample {
  /** Filename without the `.yaml` suffix; also the workflow
   *  name in the picker. Matches the YAML's `name:` field. */
  name: string;
  /** One-line tagline shown in the picker button. */
  description: string;
  /** Full YAML body. Must include `name:`, `description:`,
   *  and a non-empty `steps:` list. */
  yaml: string;
}

export const WORKFLOW_EXAMPLES: readonly WorkflowExample[] = [
  {
    name: 'hello-shell',
    description: '一个最小可用的 workflow — 跑一条 shell 命令,看输出',
    yaml: [
      '# hello-shell: 最小可用的 workflow — 跑一条 shell 命令并把输出回显。',
      '# 适合用来确认 picker 真的连上了 executor。',
      'name: hello-shell',
      'description: 跑一条 shell 命令,看输出',
      'version: 1',
      '',
      'inputs:',
      '  cmd:',
      '    type: string',
      '    default: "echo hello from aethercode"',
      '',
      'steps:',
      '  - id: run',
      '    type: shell',
      '    cmd: "{{inputs.cmd}}"',
      '    timeout: 30000',
    ].join('\n'),
  },
  {
    name: 'file-summary',
    description: '读一个文件 → 总结 → 列出易踩的坑(3 步)',
    yaml: [
      '# file-summary: 读一个文件,先总结,再列"易踩的坑"。',
      '# 输入 file_path,跑完输出一段易读的总结。',
      'name: file-summary',
      'description: 读一个文件 → 总结 → 列出易踩的坑',
      'version: 1',
      '',
      'inputs:',
      '  file_path:',
      '    type: string',
      '    required: true',
      '  note:',
      '    type: string',
      '    default: ""',
      '',
      'steps:',
      '  - id: read',
      '    type: shell',
      '    cmd: "cat {{inputs.file_path}} 2>/dev/null | head -200"',
      '    timeout: 30000',
      '',
      '  - id: summary',
      '    type: agent',
      '    agent: coder',
      '    prompt: |',
      '      Read the file at {{inputs.file_path}} and give a 5-line summary:',
      '      - one-line purpose',
      '      - key exports / public surface',
      '      - dependencies it pulls in',
      '      - one thing that surprised you',
      '    background: false',
      '    timeout: 600000',
      '',
      '  - id: gotchas',
      '    type: agent',
      '    agent: aethercode-experienced-user',
      '    prompt: |',
      '      Based on the summary from the previous step, list 3 things that',
      '      would trip up a new reader of {{inputs.file_path}}.',
      '    background: false',
      '    timeout: 600000',
    ].join('\n'),
  },
  {
    name: 'safe-commit',
    description: '提交前的 3 步自检:状态 → 改动 → 仅在有改动时提交',
    yaml: [
      '# safe-commit: 仅在 git 工作区有改动时才走 commit 分支。',
      '# 用 `gate` 步骤实现条件分支 — 演示 workflow 的控制流。',
      'name: safe-commit',
      'description: 提交前的 3 步自检:状态 → 改动 → 仅在有改动时提交',
      'version: 1',
      '',
      'inputs:',
      '  message:',
      '    type: string',
      '    required: true',
      '',
      'steps:',
      '  - id: status',
      '    type: shell',
      '    cmd: "git status --short"',
      '    timeout: 10000',
      '',
      '  - id: diff',
      '    type: shell',
      '    cmd: "git diff --stat"',
      '    timeout: 10000',
      '',
      '  - id: commit',
      '    type: gate',
      '    when: "{{steps.status.output | length > 0}}"',
      '    then:',
      '      - id: do-commit',
      '        type: shell',
      '        cmd: "git add -A && git commit -m \\"{{inputs.message}}\\""',
      '        timeout: 30000',
      '    else_:',
      '      - id: skip',
      '        type: delay',
      '        duration: 0',
    ].join('\n'),
  },
] as const;

/**
 * write the example workflows into the cwd's
 * `.aethercode/workflows/` directory. Calls
 * {@code rpc.writeWorkflow(name, content)} for each
 * example; the daemon auto-creates the parent directory
 * if it doesn't exist (R102b behaviour). Returns a
 * structured result so the UI can show a partial-success
 * status pill (one bad write shouldn't blank the others).
 *
 * The `cwd` parameter is captured in the caller's closure
 * for telemetry only — the actual write goes through the
 * daemon's `writeWorkflow` RPC which uses
 * `System.getProperty("user.dir")` (the daemon's cwd).
 */
export interface ImportResult {
  /** Names that wrote successfully (in order). */
  imported: string[];
  /** Names that failed + the daemon's reason. */
  failed: { name: string; reason: string }[];
}

export async function importExampleWorkflows(
  writeWorkflow: (name: string, content: string) => Promise<{ ok: boolean; reason?: string }>,
): Promise<ImportResult> {
  const result: ImportResult = { imported: [], failed: [] };
  // Sequential rather than parallel: writeWorkflow is
  // idempotent but two simultaneous calls into the same
  // directory have no benefit, and serial makes the order
  // of "imported" deterministic. Each call is one RPC +
  // one tiny file write (< 1 KB), so the round-trip
  // dominates — the total wall time for 3 examples is
  // ~100 ms in the worst case.
  for (const ex of WORKFLOW_EXAMPLES) {
    try {
      const r = await writeWorkflow(ex.name, ex.yaml);
      if (r.ok) {
        result.imported.push(ex.name);
      } else {
        result.failed.push({ name: ex.name, reason: r.reason ?? 'unknown' });
      }
    } catch (e: any) {
      // RPC-level failure (network blip, daemon down,
      // method-not-found on an old daemon). The catch
      // here keeps the loop going so the user sees a
      // per-example breakdown instead of a hard error.
      result.failed.push({ name: ex.name, reason: e?.message ?? String(e) });
    }
  }
  return result;
}
