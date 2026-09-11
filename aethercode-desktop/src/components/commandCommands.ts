/** Slash commands. Mirrors the Claude Code `TUI SlashCommand`
 *  concept: typing `/` in the input box opens a dropdown of
 *  available commands. The user can filter, navigate, and
 *  either:
 *    • execute the command directly (Enter on a no-arg command)
 *    • insert the command + a space into the input (Enter on
 *      a command that takes args) so the user can fill them in
 *
 *  Commands are intentionally a flat array — no plugin system,
 *  no user-defined commands yet. The list is hard-coded so the
 *  affordance stays small and discoverable. (R93 templates +
 *  R95 commands are different features: templates are user-
 *  authored strings; commands are built-in engine controls.)
 *
 *  Each command declares:
 *    id         — short slug, also the slash name
 *    label      — display name in the dropdown
 *    hint       — one-line description (shown in the dropdown)
 *    category   — for grouping / visual cue
 *    takesArgs  — true if Enter should insert "/id " not execute
 *    run        — async action; receives the typed args string
 *                 ("" for no-arg commands) and the AppState
 *                 bridge. Returns { clearInput?: boolean,
 *                 insertText?: string }.
 */

import { useStore } from '../store';
import { rpc } from '../lib/methods';

export interface CommandRunResult {
  /** Clear the input box after the command runs (default true). */
  clearInput?: boolean;
  /** Insert this text into the input box (e.g. to pre-fill
   *  a multi-line template). Mutually exclusive with clearInput. */
  insertText?: string;
  /** Show a transient status in the input. Optional. */
  status?: string;
}

export interface SlashCommand {
  id: string;
  label: string;
  hint: string;
  category: '会话' | '上下文' | '模型' | '权限' | '工具' | '系统';
  takesArgs: boolean;
  run: (args: string) => Promise<CommandRunResult> | CommandRunResult;
}

const store = () => useStore.getState();

export const SLASH_COMMANDS: SlashCommand[] = [
  // ----- Session -----
  {
    id: 'clear',
    label: '/clear',
    hint: '清空当前会话的对话历史',
    category: '会话',
    takesArgs: false,
    run: () => {
      store().clearSubTasks();
      // clearMessages is a future action; for R95 we just clear
      // subTasks + ask the user to start a new session.
      // (The actual chat history lives in the engine; we don't
      // have a clearMessages RPC yet. R95 ships the affordance;
      // a future R96+ wires it to a new session.)
      return { status: '已清空当前子任务 (对话历史需要切到新 session)' };
    },
  },
  {
    id: 'new',
    label: '/new',
    hint: '开始一个新会话 (Ctrl+Shift+N)',
    category: '会话',
    takesArgs: false,
    run: () => {
      // mint a fresh local session id so the
      // LeftPanel shows the new session immediately. Old
      // behaviour was to clear currentSessionId, which
      // relied on the daemon issuing a new id on first
      // sendMessage — the daemon's loadSession is still
      // a stub, so that path was broken.
      store().createNewSession();
      return { status: '新会话已准备。输入内容后发送即可。' };
    },
  },
  {
    id: 'session-delete',
    label: '/session delete',
    hint: '删除指定 session (用法: /session delete <id 尾 8 位>)',
    category: '会话',
    takesArgs: true,
    run: (arg: string) => {
      const tail = arg.trim();
      if (!tail) {
        return { status: '用法: /session delete <session id 尾 8 位>。在 LeftPanel 看到 id。' };
      }
      const s = store();
      // Match by id tail (the LeftPanel shows the last 8 chars).
      const target = s.sessions.find((x) => x.id.endsWith(tail));
      if (!target) {
        return { status: `没找到 id 尾 "${tail}" 的 session。当前 ${s.sessions.length} 个 session。` };
      }
      if (target.id === s.currentSessionId) {
        return { status: '当前会话不能删除。先 /new 切到新会话,再回来删这个。' };
      }
      void s.deleteSession(target.id);
      return { status: `已请求删除 session ...${tail}` };
    },
  },
  {
    id: 'sessions',
    label: '/sessions',
    hint: '打开会话切换器 (Ctrl+K)',
    category: '会话',
    takesArgs: false,
    run: () => {
      // Dispatch a custom event the App component listens for.
      // We avoid pulling App into this module to keep coupling
      // one-way (commands → store, not commands → App).
      window.dispatchEvent(new CustomEvent('aethercode:open-command-palette', { detail: { mode: 'sessions' } }));
      return { status: '已请求打开命令面板 (Ctrl+K)' };
    },
  },
  {
    id: 'cwd',
    label: '/cwd <path>',
    hint: '切换工作目录',
    category: '会话',
    takesArgs: true,
    run: (args) => {
      const path = args.trim();
      if (!path) return { status: '用法: /cwd <path>' };
      void store().setCwd(path);
      return { status: `已请求切换 cwd 到 ${path}` };
    },
  },

  // ----- Context -----
  {
    id: 'compact',
    label: '/compact',
    hint: '手动触发对话历史压缩',
    category: '上下文',
    takesArgs: false,
    run: () => {
      // We don't have a dedicated compact RPC; the engine
      // compacts automatically when context window is full. The
      // "manual compact" UX in Claude Code is similarly a hint.
      return { status: '压缩由引擎在上下文窗口接近上限时自动触发' };
    },
  },
  {
    id: 'memory',
    label: '/memory',
    hint: '在右侧面板聚焦 Memory 浏览器',
    category: '上下文',
    takesArgs: false,
    run: () => {
      window.dispatchEvent(new CustomEvent('aethercode:focus-panel', { detail: { panel: 'memory' } }));
      return { status: '已请求聚焦 Memory 面板' };
    },
  },
  {
    id: 'context',
    label: '/context',
    hint: '显示当前上下文窗口用量',
    category: '上下文',
    takesArgs: false,
    run: () => {
      const m = store().metrics;
      if (!m || !m.totalTokens) return { status: '当前没有活跃查询' };
      const used = m.inputTokens ?? 0;
      const out = m.outputTokens ?? 0;
      const total = m.totalTokens ?? 0;
      return { status: `tokens: in=${used} out=${out} total=${total}` };
    },
  },

  // ----- Model -----
  {
    id: 'model',
    label: '/model <name>',
    hint: '切换模型 (claude-opus-4-1 / sonnet-4-5 / haiku-4-5)',
    category: '模型',
    takesArgs: true,
    run: (args) => {
      const m = args.trim();
      if (!m) return { status: '用法: /model <name>' };
      void store().setModel(m);
      return { status: `已请求切换模型到 ${m}` };
    },
  },

  // ----- Permission -----
  {
    id: 'permission',
    label: '/permission <mode>',
    hint: 'default / acceptEdits / bypass / plan',
    category: '权限',
    takesArgs: true,
    run: (args) => {
      const m = args.trim();
      if (!m) return { status: '用法: /permission <mode>' };
      void store().setPermissionMode(m);
      return { status: `已请求切换 permission mode 到 ${m}` };
    },
  },

  // ----- Tools -----
  {
    id: 'help',
    label: '/help',
    hint: '显示所有命令',
    category: '工具',
    takesArgs: false,
    run: () => {
      const list = SLASH_COMMANDS.map((c) => `${c.label}  —  ${c.hint}`).join('\n');
      window.dispatchEvent(new CustomEvent('aethercode:show-help', { detail: { text: list } }));
      return { status: '帮助已推送到消息流' };
    },
  },
  {
    id: 'status',
    label: '/status',
    hint: '显示连接状态 + 当前 session + 模型',
    category: '工具',
    takesArgs: false,
    run: () => {
      const s = store();
      const lines = [
        `连接: ${s.isConnected ? '✓' : '✗'}`,
        `session: ${s.currentSessionId ?? '(无)'}`,
        `model: ${s.model}`,
        `perm: ${s.permissionMode}`,
        `streaming: ${s.isStreaming ? 'yes' : 'no'}`,
      ].join(' · ');
      return { status: lines };
    },
  },

  // The 4 /workflow commands drive the WorkflowEditorModal.
  // They don't need to take args (the modal collects them),
  // so each is a no-arg command that fires a custom event
  // the modal listens for. After the modal saves, the store
  // refreshes the cached list and the next /workflow / input
  // picker reflects the change without a page reload.
  {
    id: 'workflow-list',
    label: '/workflow list',
    hint: '刷新并展示 cwd 下的所有 workflow',
    category: '工具',
    takesArgs: false,
    run: async () => {
      const s = store();
      try {
        await s.refreshWorkflows();
        const list = (s.availableWorkflows ?? []).map(
          (w) => `${w.name} (${w.stepCount} 步): ${w.description}`,
        );
        const body = list.length
          ? list.join('\n')
          : '(cwd 下没有 workflow。把 yaml 放到 .aethercode/workflows/)';
        window.dispatchEvent(new CustomEvent('aethercode:show-help', { detail: { text: body } }));
        return { status: `找到 ${list.length} 个 workflow` };
      } catch (e: any) {
        return { status: `刷新失败: ${e?.message ?? e}` };
      }
    },
  },
  {
    id: 'workflow-create',
    label: '/workflow create',
    hint: '打开编辑器写新 workflow yaml',
    category: '工具',
    takesArgs: false,
    run: () => {
      // open the editor in create mode with a
      // starter template (the user fills in name /
      // description / steps). The modal is the same
      // surface /workflow modify reuses for edits.
      dispatchWorkflowEditor('create', { raw: workflowCreateStub() });
      return { status: '已打开 workflow 编辑器 (创建模式)' };
    },
  },
  {
    id: 'workflow-modify',
    label: '/workflow modify <name>',
    hint: '打开编辑器改现有 workflow',
    category: '工具',
    takesArgs: true,
    run: async (args) => {
      const name = args.trim();
      if (!name) return { status: '用法: /workflow modify <name>' };
      try {
        const r = await rpc.getWorkflow(name);
        if (!r?.ok || !r.raw) {
          return { status: `找不到 workflow: ${name}` };
        }
        dispatchWorkflowEditor('modify', { name: r.name ?? name, raw: r.raw });
        return { status: `已打开 workflow 编辑器 (修改 ${name})` };
      } catch (e: any) {
        return { status: `打开失败: ${e?.message ?? e}` };
      }
    },
  },
  {
    id: 'workflow-delete',
    label: '/workflow delete <name>',
    hint: '删除一个 workflow yaml (idempotent)',
    category: '工具',
    takesArgs: true,
    run: async (args) => {
      const name = args.trim();
      if (!name) return { status: '用法: /workflow delete <name>' };
      // confirm before delete. The daemon RPC is
      // idempotent but the user still wants a "are you
      // sure" moment — irreversible file delete, no undo.
      if (!window.confirm(`确认删除 workflow "${name}" ?\n该操作不可撤销。`)) {
        return { status: '已取消' };
      }
      try {
        const r = await rpc.deleteWorkflow(name);
        if (!r?.ok) return { status: `删除失败: ${r?.reason ?? 'unknown'}` };
        // Refresh the cached list so the next picker / slash
        // reflects the delete.
        await store().refreshWorkflows();
        return { status: r.removed
          ? `已删除 ${name}`
          : `${name} 本来就不存在 (no-op)` };
      } catch (e: any) {
        return { status: `删除失败: ${e?.message ?? e}` };
      }
    },
  },

  // The desktop can't directly `git clone` from the renderer
  // (no shell exec permission) so /skill add shows a copy-
  // pasteable command for the user to run in their terminal.
  // For local paths the user can also drag a folder into
  // ~/.aethercode/skills/ — we mention that as the "or"
  // alternative. The actual install + lint + visibility check
  // happens after the user pastes the command and restarts
  // the app; the `aethercode:show-help` event surfaces the
  // command in the input bar's status pill so the user can
  // copy it without scrolling the conversation.
  //
  // the legacy path was `~/.minimax/skills/`. R210
  // renamed the user-tier root to `~/.aethercode/skills/`
  // (along with the daemon's `~/.aethercode/mcp.json`); the
  // R210 work landed in the BACKEND (DaemonRunner.java uses
  // `~/.aethercode/skills` correctly) but the DESKTOP's
  // /skill add command was a missed spot — it was still
  // hard-coding `~/.minimax/skills/`. The user pasted a
  // command, ran it, and the new skill went to the old
  // path; the daemon never saw it. R222 syncs the desktop
  // to the backend's path.
  {
    id: 'skill-add',
    label: '/skill add <url-or-path>',
    hint: '在 PowerShell 装一个 skill (git clone 或 file copy)',
    category: '工具',
    takesArgs: true,
    run: (args) => {
      const src = args.trim();
      if (!src) {
        return { status: '用法: /skill add <git-url> | <本地路径>' };
      }
      // derive a default skill name from the source.
      // Git URL: take the last path segment, strip ".git".
      // Local path: take the basename.
      const isUrl = /^(https?|git|ssh):\/\//.test(src) || src.endsWith('.git');
      const seg = src.replace(/\.git$/, '').split(/[\\/]/).filter(Boolean).pop() ?? 'new-skill';
      // path was `~/.minimax/skills/${seg}`; R210
      // renamed the user-tier root to `~/.aethercode/skills/`.
      // Must match the backend's DaemonRunner.java line 959
      // (`Path.of(System.getProperty("user.home")).resolve(".aethercode").resolve("skills")`).
      const target = `~/.aethercode/skills/${seg}`;
      const cmd = isUrl
        ? `git clone ${src} ${target}`
        : // Local path: copy the directory (cp -r on bash,
          // robocopy on PowerShell — we show both for
          // user convenience).
          [
            '# 在 PowerShell:',
            `Copy-Item -Path "${src}" -Destination "${target}" -Recurse -Force`,
            '# 或在 Git Bash:',
            `cp -r "${src}" "${target}"`,
          ].join('\n');
      const body = [
        '把 skill 装到 user-global 目录:',
        '',
        cmd,
        '',
        '装完后重启 AetherCode app,新 skill 会在下一个 session 的 <available_skills> 里出现。',
        '用 /skill-refiner 调优, 用 /skill-creator 写新 skill。',
      ].join('\n');
      window.dispatchEvent(new CustomEvent('aethercode:show-help', { detail: { text: body } }));
      return { status: `已把安装命令推送到消息流 (target: ${target})` };
    },
  },
];

// user-defined slash commands come from R93 templates
// that have a `slashName` field. We read them from localStorage
// at filter time so the dropdown reflects the latest templates
// without a re-render cycle. Templates without a slash name
// are still clickable in the history panel but not exposed
// as commands. Storage key matches PromptHistory.tsx.
const TMPL_KEY = 'aethercode-prompt-templates';

interface StoredTemplate { id: string; label: string; text: string; slashName?: string; }

function loadUserSlashCommands(): SlashCommand[] {
  if (typeof window === 'undefined' || !window.localStorage) return [];
  try {
    const raw = window.localStorage.getItem(TMPL_KEY);
    if (!raw) return [];
    const list: StoredTemplate[] = JSON.parse(raw);
    return list
      .filter((t) => t.slashName && t.slashName.length > 0)
      .map((t) => ({
        id: `tmpl:${t.id}`,
        label: `/${t.slashName}`,
        hint: `模板: ${t.label} — ${t.text.slice(0, 40)}${t.text.length > 40 ? '…' : ''}`,
        category: '系统',
        takesArgs: false,
        run: () => {
          // the user template "inserts into input" — we
          // can't return a callback from the sync run() in
          // the no-arg path, so we instead dispatch a custom
          // event that MessageInput listens for. Cleaner than
          // adding a return-channel here.
          window.dispatchEvent(new CustomEvent('aethercode:insert-template', { detail: { text: t.text } }));
          return { status: `已展开模板: ${t.label}` };
        },
      }));
  } catch { return []; }
}

// These complement the existing /new / /clear etc. with
// workflow-picker-driven flows. They share a pattern:
//   1. read the relevant state from the store (or ask the
//      user via window.prompt / modal)
//   2. call the daemon RPC
//   3. refresh the cached list so the next /workflow / input
//      picker reflects the change
// The custom event `aethercode:open-workflow-editor` carries
// the workflow's current raw content + name so the
// WorkflowEditorModal can mount with the right initial state.
// We use a custom event (vs return-callback) for the same reason
// R100 templates do — the run() return is sync, and the modal
// is an async UI surface.
function dispatchWorkflowEditor(action: 'create' | 'modify', opts: { name?: string; raw?: string } = {}) {
  window.dispatchEvent(new CustomEvent('aethercode:open-workflow-editor', {
    detail: { action, ...opts },
  }));
}

function workflowCreateStub(): string {
  return [
    'name: my-workflow',
    'description: 简要说明这个 workflow 做什么',
    'version: 1',
    '',
    'inputs:',
    '  note:',
    '    type: string',
    '    default: ""',
    '',
    'steps:',
    '  - id: step-1',
    '    type: shell',
    '    cmd: "echo hello"',
    '    timeout: 30000',
  ].join('\n');
}

/** combined command list = built-in + user templates
 *  with slashName. We re-read localStorage on every call so a
 *  freshly-tagged template is visible without a page reload. */
export function allCommands(): SlashCommand[] {
  return [...SLASH_COMMANDS, ...loadUserSlashCommands()];
}

/** parse `/<id> <args...>` and return the matching
 *  command + the args string, or null if the input doesn't
 *  start with `/` or no command matches the prefix. */
export function parseSlash(input: string): { cmd: SlashCommand; args: string } | null {
  if (!input.startsWith('/')) return null;
  // Take the part after `/` up to the first space.
  const space = input.indexOf(' ');
  const id = (space < 0 ? input.slice(1) : input.slice(1, space)).toLowerCase();
  const args = space < 0 ? '' : input.slice(space + 1);
  if (!id) return null;
  // Exact match preferred; else prefix match.
  const all = allCommands();
  const exact = all.find((c) => c.id === id);
  if (exact) return { cmd: exact, args };
  const prefix = all.filter((c) => c.id === id || c.id.startsWith(id));
  if (prefix.length === 1) return { cmd: prefix[0], args };
  return null;
}

/** fuzzy-ish filter for the dropdown. Matches if the
 *  command id, label, or hint contains the query (case
 *  insensitive). The query is everything after the leading
 *  `/`. */
export function filterCommands(query: string): SlashCommand[] {
  // includes user-defined commands from R93 templates.
  // allCommands() re-reads localStorage on every call so a
  // freshly-tagged template is visible without a reload.
  const all = allCommands();
  if (!query) return all;
  const q = query.toLowerCase();
  return all.filter((c) =>
    c.id.toLowerCase().includes(q) ||
    c.label.toLowerCase().includes(q) ||
    c.hint.toLowerCase().includes(q),
  );
}
