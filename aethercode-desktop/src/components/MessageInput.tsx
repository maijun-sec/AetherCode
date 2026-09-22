import { useRef, useEffect, useState, useMemo } from 'react';
import { useStore, UI_PERMISSION_MODES } from '../store';
import { CommandDropdown } from './CommandDropdown';
import { parseSlash, filterCommands, SlashCommand } from './commandCommands';
import { rpc } from '../lib/methods';
import type { ProviderInfo } from '../lib/methods';
// bundled starter workflows + the import helper.
// The picker surfaces an "📥 Import examples" button when the
// list is empty, so a brand-new project doesn't have to
// know about `/workflow create` to see something
// selectable. See workflowExamples.ts for the YAMLs.
import { WORKFLOW_EXAMPLES, importExampleWorkflows } from './workflowExamples';
import { computeModelDropdownValue } from './modelDropdownValue';
import './MessageInput.css';

// R295: per-quality tooltip text. Mirrors the
// description strings baked into MockRpcServer.ts
// (and the real daemon's `variantInfo` payload) so
// the user can hover an option to read what the
// preset actually does (temperature / maxTokens /
// extended-thinking toggle). Keeping a local copy
// avoids a synchronous RPC just to label the options.
const QUALITY_PRESET_TIPS: Record<'low' | 'medium' | 'high' | 'xhigh', string> = {
  low:    '0.3 / 16k — 最快，温度最低',
  medium: '0.7 / 32k — 平衡（默认）',
  high:   '1.0 / 48k — 更锐利，输出更长',
  xhigh:  '1.0 / 64k + think — 最锐利，开启扩展思考',
};

// the dropdown is wired to UI_PERMISSION_MODES
// (re-exported from the store so the labels and the
// store-side mapping stay in sync). The 3-tier mental
// model is the user's:
//   Ask first (ask)       → ASK_BEFORE_TOOL   — always ask
//   Smart allow (smart)   → ACCEPT_EDITS      — read-only + bash-readonly auto, mutate asks
//   Always allow (bypass) → BYPASS_PERMISSIONS — never ask
// The "Plan" mode (show-only) was moved into the
// SettingsPage so the input bar stays tight; the engine
// still honours it for users who want a no-mutation
// read-only session.
// (The PERMISSION_MODES list lived here previously as a
// local constant; the source-pin tests in
// permissionModeR203.test.ts pin that the dropdown's
// option count and labels match UI_PERMISSION_MODES.)

function shortPath(p: string | null, maxLen = 50): string {
  if (!p) return '—';
  if (p.length <= maxLen) return p;
  return '…' + p.slice(p.length - maxLen + 1);
}

/** R285: parse `/model provider/model:variant` (or
 *  just `/model provider/model`). Returns
 *  {provider, model, variant?} when the input is a
 *  complete model pick; the caller dispatches
 *  switchProvider with the parsed pieces. Returns
 *  null when the input is something else — the
 *  caller then proceeds with the normal "send
 *  message" path.
 *
 *  <p>The syntax mirrors the claude-code-style
 *  inline model pick: the user types the whole
 *  command on a single line (no args menu), the
 *  variant suffix is optional, and the picking
 *  is a no-op on Enter when the daemon doesn't
 *  recognise the model. A typo in either the
 *  provider or the model fails loudly — the
 *  user sees the daemon's error in the toast,
 *  rather than silently switching to the wrong
 *  model.
 *
 *  <p>Exported so {@link MessageInputR285.test}
 *  can hit it directly without mounting the
 *  whole input bar — the parser is pure
 *  (no React, no DOM, no daemon). */
export function parseModelSwitchCommand(input: string): {
  provider: string;
  model: string;
  variant?: string;
} | null {
  const m = /^model\s+(\S+?)\/(\S+?)(?::(\S+))?$/i.exec(input);
  if (!m) return null;
  const provider = m[1];
  const model = m[2];
  const variant = m[3];
  if (!provider || !model) return null;
  return variant ? { provider, model, variant } : { provider, model };
}

export function MessageInput() {
  const {
    currentInput,
    setCurrentInput,
    sendMessage,
    cancelQuery,
    isStreaming,
    // R267 polish: queued follow-up + cancel action. When
    // isStreaming is true and the user types a prompt +
    // hits Enter, sendMessage parks the text in
    // pendingFollowUp (so the input box unlocks and the
    // user can keep typing). The run_end handler in the
    // store auto-promotes the queued text to the next
    // run. The cancel button next to the queue pill
    // drops it without affecting the current run.
    pendingFollowUp,
    cancelPendingFollowUp,
    isConnected,
    model,
    permissionMode,
    setModel,
    setPermissionMode,
    tools,
    enabledTools,
    toggleTool,
    // manual refresh hook for the tool pool.
    // Legacy A: `tools` was a snapshot taken at
    // initialize() — if the round-trip failed, the user
    // had no way to recover and the Tools button was
    // disabled. Now the popup shows a ↻ button that
    // re-fetches both listTools and listToolActions, and
    // the button is always enabled so the user can open
    // the popup to refresh.
    refreshTools,
    cwd,
    pickCwd,
    setCwd,
    // workflow picker. `availableWorkflows` is
    // populated on app start; the user opens the picker,
    // picks a workflow, and the next message goes through
    // `runWorkflow` instead of `query`. The selected
    // workflow is rendered as a chip below the config bar
    // so the user sees what they're about to send.
    availableWorkflows,
    activeWorkflow,
    setActiveWorkflow,
    refreshWorkflows,
    // LRU of recently-selected workflow names.
    // The picker renders the most-recent 5 in a
    // "Recently used" section above the full list so a
    // user who runs the same workflow repeatedly
    // doesn't have to re-find it in the popup.
    recentWorkflows,
    // list of providers (with their model lists)
    // from the daemon's listProviders RPC. Populated on
    // app start; used to render the model dropdown so
    // the user sees ALL models across ALL providers
    // (minmax, anthropic, openai, glm, qwen, deepseek,
    // gemini) instead of a hard-coded subset.
    availableProviders,
    currentProvider,
    // R285: the active variant + setter. The
    // Quality pills below the model dropdown
    // call switchVariant; the active variant
    // chip shows the current knob bundle.
    currentVariant,
    activeVariant,
    switchVariant,
    refreshProviders,
    // one-time mismatch prompt. Set by
    // initialize() when the localStorage `enginePrefs.model`
    // differs from the daemon's canonical `state.model`.
    // The user clicks "switch" to call setModel(prefsModel)
    // (which writes to BOTH the daemon AND localStorage) or
    // "keep ${daemonModel}" to dismiss. The dismissal
    // records the pair in `modelMismatchDismissed` so the
    // same mismatch doesn't re-prompt on the next launch.
    modelMismatchPrompt,
    dismissModelMismatch,
    acceptModelMismatch,
  } = useStore();
  const ref = useRef<HTMLTextAreaElement>(null);
  const [showTools, setShowTools] = useState(false);
  const [showWorkflows, setShowWorkflows] = useState(false);
  // example-import state. Lives in the component
  // (not the store) because it's a transient UI flag —
  // the imported workflows themselves ARE durable (they
  // get written to .aethercode/workflows/), so a reload
  // should not show "you just imported 3" again. The result
  // pill clears when the user closes+reopens the picker
  // because the state is in the same useState that's
  // re-mounted on each open of the popup's parent —
  // wait, no: the picker popup is rendered by
  // showWorkflows but the useState lives on the parent
  // component which is mounted the whole time. We rely
  // on the user closing+reopening the picker to clear
  // the pill, which is good enough for a "you just
  // imported 3 things" notification.
  const [importingExamples, setImportingExamples] = useState(false);
  const [exampleImportResult, setExampleImportResult] = useState<{
    imported: string[];
    failed: { name: string; reason: string }[];
  } | null>(null);
  const [editingCwd, setEditingCwd] = useState(false);
  const [cwdInput, setCwdInput] = useState(cwd ?? '');
  // Thinking toggle. UI-only local state — the
  // underlying "thinking" capability is model-driven and not
  // part of the request envelope on every model, so we just
  // surface the user's intent in the input bar. The active
  // pill is purely visual; if/when the engine grows a real
  // toggle, it can be lifted into the store without changing
  // any caller of this component.
  const [thinkingOn, setThinkingOn] = useState(true);

  // R315: SDD mode. UI-only state — drives the sdd skill
  // in chat (see agents/mavis/skills/sdd). When on, the next
  // user turn is routed through the SDD skill with an [sdd]
  // trigger prepended so the agent loads the skill bundle.
  const sddEnabled = useStore((s) => s.sddEnabled);
  const setSddEnabled = useStore((s) => s.setSddEnabled);

  // refresh the provider list on mount and whenever
  // the connection comes back. The daemon's listProviders
  // RPC returns every model across every provider
  // (minmax / anthropic / openai / glm / qwen / deepseek
  // / gemini + any custom providers.yaml entries). The
  // dropdown below uses this list — no more hard-coded
  // 3-model subset.
  useEffect(() => {
    if (isConnected) void refreshProviders();
  }, [isConnected, refreshProviders]);

  // flatten the provider list into a (provider,
  // model) pair list, with the current provider's models
  // first. The dropdown shows `provider/model` so the
  // user can see which model belongs to which provider.
  //
  // R282: filter out providers the user hasn't
  // configured (no API key in the daemon's process
  // environment). The user reported "你配置的很多模型
  // 都是我没办法用的" — the dropdown showed every
  // model in the registry, including anthropic /
  // openai whose env vars aren't set. The hasApiKey
  // flag from listProviders solves that — providers
  // the user can't call simply don't appear.
  const modelEntries = useMemo(() => {
    const list: { id: string; label: string; provider: string }[] = [];
    const providers = (availableProviders ?? []) as ProviderInfo[];
    for (const p of providers) {
      // hasApiKey is computed by the daemon at list
      // time (it reads System.getenv each call).
      // Undefined (older daemon) is treated as "show
      // it" for backward compat — fresh daemons
      // always set the field.
      if (p.hasApiKey === false) continue;
      for (const m of p.models ?? []) {
        list.push({
          id: `${p.name}/${m.id}`,
          label: `${p.name} / ${m.id}`,
          provider: p.name,
        });
      }
    }
    // Sort: current provider first, then alphabetical.
    list.sort((a, b) => {
      if (a.provider === currentProvider && b.provider !== currentProvider) return -1;
      if (b.provider === currentProvider && a.provider !== currentProvider) return 1;
      return a.label.localeCompare(b.label);
    });
    return list;
  }, [availableProviders, currentProvider]);

  // compute the dropdown's `value` so it
  // matches the option id (which is
  // `${providerName}/${modelId}`). Pre-fix the
  // dropdown used `value={model ?? ''}` but the
  // options had provider-prefixed ids, so the
  // value never matched an option and the
  // browser fell back to the first option in
  // the list — making it look like the user's
  // "default" model was whatever happened to
  // sort first (e.g. M1), not what the daemon
  // actually had (M3). See
  // ./modelDropdownValue.ts for the pure
  // helper + test surface.
  const dropdownValue = computeModelDropdownValue(model, modelEntries);

  useEffect(() => {
    if (isConnected) ref.current?.focus();
  }, [isConnected]);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    el.style.height = 'auto';
    el.style.height = `${Math.min(el.scrollHeight, 220)}px`;
  }, [currentInput]);

  useEffect(() => { setCwdInput(cwd ?? ''); }, [cwd]);

  // when the user opens the Tools popup and the
  // pool is empty, kick a refresh. Covers the
  // "initialize() failed, user opens the popup for the
  // first time" case without them having to click ↻. The
  // effect re-fires whenever showTools flips; for the
  // already-populated case it's a no-op because of the
  // tools.length === 0 guard. The 30 s periodic poll in
  // the store is the belt-and-braces for the case where
  // the user never opens the popup.
  useEffect(() => {
    if (showTools && tools.length === 0 && isConnected) {
      void refreshTools();
    }
    // We intentionally don't include isConnected in the
    // deps — the popup is only mountable while connected
    // in practice, and re-running the effect on every
    // connection blip would cause a refresh storm.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [showTools, tools.length]);

  // listen for the `aethercode:insert-template` event
  // dispatched by user-defined slash commands (R93 templates
  // with a `slashName` field). The event payload is the
  // template text; we replace the current input with it.
  useEffect(() => {
    const onInsert = (e: Event) => {
      const ce = e as CustomEvent<{ text: string }>;
      setCurrentInput(ce.detail.text);
      setCmdStatus({ kind: 'info', text: '已展开模板', ts: Date.now() });
      setTimeout(() => setCmdStatus(null), 2500);
      // Focus the textarea so the user can immediately edit.
      setTimeout(() => ref.current?.focus(), 0);
    };
    const onHelp = (e: Event) => {
      const ce = e as CustomEvent<{ text: string }>;
      const lines = ce.detail.text.split('\n');
      // a 1-line preview into the status pill (the
      // multi-line text is too long for the pill; the user
      // can call /help again or copy the text from the
      // template). For R101+ we'd want a real help modal.
      setCmdStatus({
        kind: 'info',
        text: `${lines[0]}  (共 ${lines.length} 条; 查看所有命令请输入 / 后看下拉)`,
        ts: Date.now(),
      });
      setTimeout(() => setCmdStatus(null), 5000);
    };
    window.addEventListener('aethercode:insert-template', onInsert);
    window.addEventListener('aethercode:show-help', onHelp);
    return () => {
      window.removeEventListener('aethercode:insert-template', onInsert);
      window.removeEventListener('aethercode:show-help', onHelp);
    };
  }, [setCurrentInput]);

  // slash-command state. The dropdown opens when the
  // input starts with `/` and contains no space yet (i.e. the
  // user is still typing the command name). Once they add a
  // space, the dropdown hides and the input is treated as a
  // normal message until the user presses Enter.
  const slashQuery = currentInput.startsWith('/') && !currentInput.includes(' ')
    ? currentInput.slice(1)
    : null;
  const slashOpen = slashQuery !== null && isConnected;
  // the index of the currently-highlighted row in the
  // filtered list. Reset whenever the query changes.
  const [slashIdx, setSlashIdx] = useState(0);
  useEffect(() => { setSlashIdx(0); }, [currentInput]);

  // status pill shown below the input when a slash
  // command's run() returns a status string.
  const [cmdStatus, setCmdStatus] = useState<{ kind: 'info' | 'error'; text: string; ts: number } | null>(null);

  // run a command either by invoking it (no-arg) or by
  // inserting "/id " into the input (arg-taking).
  const handleCommand = async (cmd: SlashCommand) => {
    if (cmd.takesArgs) {
      setCurrentInput(`${cmd.label} `);
    } else {
      try {
        const result = await cmd.run('');
        if (result.status) {
          setCmdStatus({ kind: 'info', text: result.status, ts: Date.now() });
          setTimeout(() => setCmdStatus(null), 4000);
        }
      } catch (e: any) {
        setCmdStatus({ kind: 'error', text: `${cmd.label} 失败: ${e?.message ?? String(e)}`, ts: Date.now() });
        setTimeout(() => setCmdStatus(null), 5000);
      }
      setCurrentInput('');
    }
  };

  // filtered command list. Mirrors what the dropdown
  // shows. We need the list here too because the textarea's
  // keydown handler drives the active row.
  const slashItems = useMemo(() => {
    if (!slashOpen) return [];
    return filterCommands(slashQuery!);
  }, [slashQuery, slashOpen]);

  // `@`-mention file autocomplete. The textarea
  // listens for an `@<query>` token (where `<query>` is
  // everything from the `@` to the next space / newline /
  // end-of-input). The dropdown opens when the cursor's
  // word starts with `@`, and the items are filtered
  // cwd files whose path contains the substring after
  // the `@`. Selecting an item replaces the `@<query>`
  // token with `@<full-path>` so the model sees the full
  // path in the prompt. The RPC is debounced so a fast
  // typist doesn't fan out a list per keystroke.
  const mentionQuery = (() => {
    if (!isConnected) return null;
    const cursorAt = ref.current?.selectionStart ?? currentInput.length;
    // Look back from the cursor to the start of the
    // current word (the most recent whitespace or @-symbol).
    let start = cursorAt;
    while (start > 0 && !/\s/.test(currentInput[start - 1])) start--;
    const word = currentInput.slice(start, cursorAt);
    if (!word.startsWith('@')) return null;
    // Reject if the @ is not at the start of the word
    // boundary (a substring of another word, e.g.
    // "email@host"). The "start of word" check above
    // already covers this since "email" + "@host" puts
    // the @ mid-word, and the word here would be
    // "@host" only if the @ is right after a whitespace.
    return word.slice(1); // strip the leading @
  })();
  const [mentionOpen, setMentionOpen] = useState(false);
  const [mentionItems, setMentionItems] = useState<string[]>([]);
  const [mentionIdx, setMentionIdx] = useState(0);
  const mentionItemsRef = useRef(mentionItems);
  mentionItemsRef.current = mentionItems;
  useEffect(() => {
    if (mentionQuery == null) {
      // Don't immediately clear — we want the dropdown
      // to fade out gracefully. The next keystroke
      // (without @) keeps it closed.
      if (mentionOpen) setMentionOpen(false);
      setMentionItems([]);
      return;
    }
    setMentionOpen(true);
    setMentionIdx(0);
    // Debounce: 80ms is fast enough to feel reactive
    // and slow enough to avoid hammering the daemon on
    // every keystroke.
    const t = setTimeout(() => {
      rpc.listCwdFiles(mentionQuery, 50)
        .then((r) => {
          // Stale-response guard: only update if the
          // current query is still this one.
          if (mentionItemsRef.current && mentionQuery !== null) {
            setMentionItems(r.files ?? []);
          }
        })
        .catch(() => setMentionItems([]));
    }, 80);
    return () => clearTimeout(t);
    // We intentionally don't depend on `currentInput` —
    // the `mentionQuery` derived value already captures
    // the current state, and the effect re-fires on every
    // keystroke (the @-test runs in render). Including
    // `currentInput` would cause double-fires.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mentionQuery]);

  const onKeyDown = async (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    // `@`-mention dropdown navigation. Same
    // pattern as the slash dropdown (the textarea's
    // keydown handler drives the active row because
    // the dropdown is a position:absolute sibling, not
    // a parent, so key events don't reach it via
    // bubbling). Esc closes the dropdown without
    // clearing the input.
    if (mentionOpen && mentionQuery != null) {
      if (e.key === 'ArrowDown') { e.preventDefault(); setMentionIdx((i) => Math.min(i + 1, mentionItems.length - 1)); return; }
      if (e.key === 'ArrowUp')   { e.preventDefault(); setMentionIdx((i) => Math.max(i - 1, 0)); return; }
      if (e.key === 'Tab') {
        e.preventDefault();
        setMentionIdx((i) => e.shiftKey ? Math.max(0, i - 1) : Math.min(mentionItems.length - 1, i + 1));
        return;
      }
      if (e.key === 'Enter') {
        // Enter picks the active mention. We don't
        // preventDefault on Ctrl+Enter so the user can
        // still send with Ctrl+Enter. Plain Enter is
        // captured here.
        e.preventDefault();
        const pick = mentionItems[mentionIdx];
        if (pick) insertMention(pick);
        return;
      }
      if (e.key === 'Escape') {
        e.preventDefault();
        setMentionOpen(false);
        return;
      }
    }
    // while the slash dropdown is open, the textarea's
    // own key handlers are gated so the dropdown can drive
    // the active row. We dispatch the navigation keys here
    // (in the textarea's handler) because the dropdown is a
    // position:absolute sibling, not a parent, so key events
    // don't reach it via bubbling.
    if (slashOpen) {
      if (e.key === 'ArrowDown') { e.preventDefault(); setSlashIdx((i) => Math.min(i + 1, slashItems.length - 1)); return; }
      if (e.key === 'ArrowUp')   { e.preventDefault(); setSlashIdx((i) => Math.max(i - 1, 0)); return; }
      if (e.key === 'Tab') {
        e.preventDefault();
        setSlashIdx((i) => e.shiftKey ? Math.max(0, i - 1) : Math.min(slashItems.length - 1, i + 1));
        return;
      }
      if (e.key === 'Enter') {
        // Enter (no Ctrl) on the dropdown picks the active
        // row. Ctrl+Enter still sends the message.
        e.preventDefault();
        const it = slashItems[slashIdx];
        if (it) void handleCommand(it);
        return;
      }
      if (e.key === 'Escape') {
        e.preventDefault();
        setCurrentInput('');
        return;
      }
    }
    if (e.key === 'Enter' && !(e.metaKey || e.ctrlKey)) {
      // plain Enter sends the message. Ctrl+Enter
      // is reserved for newline (the textarea's default
      // behaviour, which we don't preventDefault). This
      // matches Claude Code / ChatGPT / Slack conventions
      // — the user types a quick reply, hits Enter, and
      // expects it to go out, not to drop a new line.
      // Shift+Enter also gets a newline (the textarea's
      // default), so power users still have an explicit
      // "newline" binding if they don't want to reach for
      // Ctrl.
      e.preventDefault();
      // if the input is exactly a complete slash command
      // (e.g. "/clear" with no args), execute it instead of
      // sending. This matches Claude Code's behaviour.
      if (!isStreaming) {
        const parsed = parseSlash(currentInput);
        if (parsed && !parsed.cmd.takesArgs) {
          void handleCommand(parsed.cmd);
          return;
        }
      }
      if (isStreaming) cancelQuery();
      else if (isConnected) {
        // R285: `/model y:y` syntax — the user types
        // a complete model pick as a slash-style
        // command, including an optional `:variant`
        // suffix. Matches the opencode / claude
        // code convention of putting the model
        // pick on the input line so the user
        // doesn't have to navigate the dropdown
        // when they want a quick "glm/glm-4-flash
        // at high temperature" pick.
        const modelCmd = parseModelSwitchCommand(currentInput.trim());
        if (modelCmd) {
          setCurrentInput('');
          try {
            await useStore.getState().switchProvider(
                modelCmd.provider, modelCmd.model, modelCmd.variant);
          } catch (e) {
            console.warn('[MessageInput] /model switch failed:', e);
          }
          return;
        }
        // R317: when SDD toggle is on, route the user's intent
        // through startSsdFlow() instead of plain sendMessage().
        // startSsdFlow prepends the per-phase instruction
        // (`[sdd-task: <slug>, phase: 1, action: run]`) so the
        // agent loads the sdd skill and runs phase 1 only,
        // rather than defaulting to its agentic-loop habit of
        // writing pom.xml + src/ straight off.
        const intent = currentInput.trim();
        if (useStore.getState().sddEnabled && intent) {
          setCurrentInput('');
          try {
            await useStore.getState().startSsdFlow(intent);
          } catch (e) {
            console.warn('[MessageInput] startSsdFlow failed:', e);
          }
          return;
        }
        sendMessage();
      }
    } else if (e.key === 'Escape') {
      if (slashOpen) {
        setCurrentInput('');
        e.preventDefault();
        return;
      }
      if (isStreaming) {
        e.preventDefault();
        cancelQuery();
      }
    }
  };

  const activeTools = enabledTools ?? new Set(tools.map((t) => t.name));

  // insert the chosen mention at the cursor. We
  // replace the entire `@<query>` token (from the last
  // whitespace boundary up to the cursor) with
  // `@<path>`, then close the dropdown and re-focus the
  // textarea. The result is "user types @a, picks
  // aethercode/src/.../MemoryList.tsx, sees
  // @aethercode/src/components/MemoryList.tsx in the
  // input".
  const insertMention = (filePath: string) => {
    const cursorAt = ref.current?.selectionStart ?? currentInput.length;
    let start = cursorAt;
    while (start > 0 && !/\s/.test(currentInput[start - 1])) start--;
    const before = currentInput.slice(0, start);
    const after = currentInput.slice(cursorAt);
    // Use forward slashes regardless of OS so the path
    // round-trips through the model cleanly.
    const normalized = filePath.replace(/\\/g, '/');
    const inserted = `${before}@${normalized} ${after}`;
    setCurrentInput(inserted);
    setMentionOpen(false);
    setMentionItems([]);
    setTimeout(() => {
      const el = ref.current;
      if (!el) return;
      const newCursor = (before + '@' + normalized + ' ').length;
      el.focus();
      el.setSelectionRange(newCursor, newCursor);
    }, 0);
  };

  const submitCwd = () => {
    const trimmed = cwdInput.trim();
    if (trimmed && trimmed !== cwd) {
      setCwd(trimmed);
    }
    setEditingCwd(false);
  };

  return (
    <div className="message-input-container">
      {/* R267 desktop polish (2026-09-14): the queued
       *  follow-up pill. Shown ABOVE the config bar so
       *  the user sees "yes, the engine heard your next
       *  prompt — it's waiting for the current run to
       *  end" before they reach for the input box again.
       *
       *  The pill has a ✕ that calls
       *  cancelPendingFollowUp — drops the queued text
       *  without affecting the in-flight run. The user
       *  explicitly asked for this: "我会自己 cancel 前一个,
       *  再提交后一个" — they want control over when the
       *  queued text fires (sometimes "never, I changed
       *  my mind"). Single-slot queue matches that
       *  mental model.
       *
       *  The store auto-promotes the queue to a real run
       *  on run_end, so the user never has to click
       *  anything to make it fire — only to cancel it. */}
      {pendingFollowUp && (
        <div className="followup-pill" title="Runs automatically after the current query finishes. Click ✕ to drop.">
          <span className="followup-pill-icon" aria-hidden>↪</span>
          <span className="followup-pill-label">queued:</span>
          <span className="followup-pill-text">{pendingFollowUp}</span>
          <button
            type="button"
            className="followup-pill-cancel"
            onClick={cancelPendingFollowUp}
            aria-label="Drop queued follow-up"
            title="Drop queued follow-up"
          >
            ✕
          </button>
        </div>
      )}
      {/* one-time model mismatch banner.
       * Rendered above the config bar so the user
       * sees it before they pick a model. The
       * banner disappears the moment they click
       * either "switch" (accepts the localStorage
       * preference) or "keep ${daemonModel}"
       * (dismisses the prompt and records the
       * pair in modelMismatchDismissed).
       *
       * Why a banner (not a toast): the action
       * is a model switch, which affects the
       * next message. A toast is too transient —
       * the user might miss it. The banner
       * stays until they make a choice. */}
      {modelMismatchPrompt && (
        <div
          className="model-mismatch-banner"
          role="alertdialog"
          aria-labelledby="model-mismatch-banner-title"
        >
          <div className="model-mismatch-banner-icon" aria-hidden="true">⚠️</div>
          <div className="model-mismatch-banner-text">
            <div id="model-mismatch-banner-title" className="model-mismatch-banner-title">
              模型不匹配
            </div>
            <div className="model-mismatch-banner-desc">
              你的上次选择是 <code>{modelMismatchPrompt.prefsModel}</code>，
              daemon 现在默认 <code>{modelMismatchPrompt.daemonModel}</code>。
              要切回去吗？
            </div>
          </div>
          <div className="model-mismatch-banner-actions">
            <button
              type="button"
              className="model-mismatch-btn model-mismatch-btn-primary"
              onClick={() => void acceptModelMismatch()}
              title={`切换到 ${modelMismatchPrompt.prefsModel}`}
            >
              切回 {modelMismatchPrompt.prefsModel}
            </button>
            <button
              type="button"
              className="model-mismatch-btn model-mismatch-btn-secondary"
              onClick={() => dismissModelMismatch()}
              title={`保留 ${modelMismatchPrompt.daemonModel}`}
            >
              保留 {modelMismatchPrompt.daemonModel}
            </button>
          </div>
        </div>
      )}
      <div className="input-config-bar">
        {/* Thinking toggle on the very left of
         * the config bar. The pill flips to a soft accent fill
         * when active so the user can see at a glance whether
         * extended thinking is on. UI-only — no store writes. */}
        <div className="config-group">
          <button
            type="button"
            className={`config-toggle config-toggle-thinking ${thinkingOn ? 'config-toggle-active' : ''}`}
            onClick={() => setThinkingOn((v) => !v)}
            title={thinkingOn ? '关闭思考模式' : '开启思考模式'}
            aria-pressed={thinkingOn}
          >
            🧠 思考
          </button>
        </div>
        {/* R315: SDD toggle. When on, the next Enter routes
         * through the sdd skill — the agent runs a strict 8-phase
         * spec-driven development flow with per-phase pause. The
         * SddPhaseBar appears beneath the chat list to show the
         * 8 phase chips + ✅/✏️/⏭️ action bar.
         *
         * Distinct from the Thinking pill: thinking controls the
         * chat model, SDD controls the workflow envelope. Both
         * can be on simultaneously — a SDD run still benefits
         * from extended thinking. */}
        <div className="config-group">
          <button
            type="button"
            className={`config-toggle config-toggle-sdd ${sddEnabled ? 'config-toggle-active' : ''}`}
            onClick={() => setSddEnabled(!sddEnabled)}
            title={sddEnabled ? '关闭 SDD 模式' : '开启 SDD 模式'}
            aria-pressed={sddEnabled}
            data-testid="message-input-sdd-toggle"
          >
            📐 SDD
          </button>
        </div>
        {/* R312: SDD toggle gone. The 4 quality pills
         * (low / medium / high / xhigh) become their own
         * `.config-group` mirroring the Model select's
         * affordance. Each option's `title` carries the
         * daemon-side description (temperature /
         * maxTokens / extended-thinking flags) so users
         * can hover to see what each preset actually does.
         * The active variant name is shown as the
         * select's value. */}
        <div
          className="config-group"
          data-testid="quality-group"
        >
          <select
            className="config-select config-select-quality"
            data-testid="message-input-quality"
            value={(currentVariant ?? '').toLowerCase() || 'medium'}
            onChange={async (e) => {
              try {
                await switchVariant(e.target.value);
              } catch (err) {
                console.warn('[MessageInput] switchVariant failed:', err);
              }
            }}
            disabled={isStreaming || !isConnected}
            title={activeVariant?.description ?? 'Daemon quality preset (temperature / maxTokens / extended thinking)'}
          >
            {(['low', 'medium', 'high', 'xhigh'] as const).map((name) => (
              <option
                key={name}
                value={name}
                title={QUALITY_PRESET_TIPS[name]}
              >
                {name}
              </option>
            ))}
          </select>
          {activeVariant && (
            <span
              className="message-input-quality-detail"
              data-testid="message-input-quality-detail"
              title={activeVariant.description ?? ''}
            >
              {activeVariant.temperature != null
                ? `${activeVariant.temperature.toFixed(1)} / `
                : ''}
              {activeVariant.maxTokens != null
                ? `${Math.round(activeVariant.maxTokens / 1000)}k`
                : ''}
              {activeVariant.extendedThinking ? ' · think' : ''}
            </span>
          )}
        </div>
        <div className="config-group">
          <label className="config-label">Model</label>
          <select
            className="config-select"
            // use the computed value that
            // matches an option id, not the bare
            // model. See the dropdownValue useMemo
            // above for the rationale.
            value={dropdownValue}
            onChange={async (e) => {
              // when the user picks a model, the value
              // is "provider/modelId" (e.g. "anthropic/claude-sonnet-4-5").
              // We split, switch provider if needed, then set
              // the model id. The daemon's setModel RPC only
              // accepts the bare model id; the provider switch
              // is a separate RPC (switchProvider).
              const v = e.target.value;
              const slash = v.indexOf('/');
              if (slash < 0) {
                await setModel(v);
                return;
              }
              const newProvider = v.slice(0, slash);
              const newModel = v.slice(slash + 1);
              if (newProvider && newProvider !== currentProvider) {
                await useStore.getState().switchProvider(newProvider, newModel);
              } else {
                await setModel(newModel);
              }
            }}
            disabled={isStreaming || !isConnected}
          >
            {modelEntries.length === 0 ? (
              <option value={model ?? ''}>{model ?? '—'}</option>
            ) : (
              modelEntries.map((e) => (
                <option key={e.id} value={e.id}>{e.label}</option>
              ))
            )}
          </select>
        </div>
        <div className="config-group">
          <label className="config-label">Perm</label>
          <select className="config-select" value={permissionMode} onChange={(e) => setPermissionMode(e.target.value)} disabled={isStreaming || !isConnected}>
            {UI_PERMISSION_MODES.map((p) => <option key={p.value} value={p.value} title={p.title}>{p.label}</option>)}
          </select>
        </div>
        <div className="config-group">
          <button
            className="config-toggle"
            onClick={() => setShowTools(!showTools)}
            // removed the `disabled={tools.length === 0}`
            // gate. Legacy A: an empty pool locked the
            // user out of the only place that had a ↻ button,
            // so a failed initialize() left the renderer
            // stuck forever. The button is now always
            // enabled; the popup shows an empty state with
            // a refresh button if the pool is empty.
            title="Toggle tools"
          >
            Tools {enabledTools
              ? `(${activeTools.size}/${tools.length})`
              : tools.length > 0 ? `(all ${tools.length})` : '(none)'}
          </button>
          {showTools && (
            <div className="tools-popup">
              {/* refresh button in the popup header.
                  Always visible so the user can re-fetch
                  even when the list is empty. The auto-trigger
                  below (useEffect) handles the "user opened
                  the popup for the first time and there's no
                  data" case; the manual button covers the
                  "data is stale, I want to force a re-pull"
                  case. */}
              <div className="tools-popup-header">
                <span className="tools-popup-title">
                  {tools.length > 0 ? `${tools.length} 个工具` : '无工具数据'}
                </span>
                <button
                  className="tools-popup-refresh"
                  onClick={() => void refreshTools()}
                  title="重新拉取 tool 列表"
                >
                  ↻
                </button>
              </div>
              {tools.length === 0 ? (
                // empty state with an explicit refresh
                // call-to-action. Legacy A: the popup
                // silently didn't open when tools=[] (the
                // button was disabled), so the user had no
                // way to even see the empty state. Now they
                // can open it and click ↻ themselves.
                <div className="tools-popup-empty">
                  <div>暂无工具数据</div>
                  <div className="tools-popup-empty-hint">
                    daemon 启动中或 listTools 失败。点击 ↻ 重试。
                  </div>
                </div>
              ) : (
                tools.map((t) => (
                  <label key={t.name} className="tool-item">
                    <input type="checkbox" checked={activeTools.has(t.name)} onChange={() => toggleTool(t.name)} />
                    <span className="tool-name">{t.name}</span>
                  </label>
                ))
              )}
            </div>
          )}
        </div>
        {/* workflow picker. Same popup pattern as the
            tools toggle — click to expand, click an entry to
            select, click the chip to deselect. We refresh
            the cached list on open so newly-added YAMLs show
            up without an app restart. The picker is also
            the path to "I want this exact pipeline".
            Historical reference: the empty state now also offers a
            one-click "📥 Import examples" button so a fresh project
            sees something selectable without having to
            know about `/workflow create`. */}
        <div className="config-group">
          <button
            className={`config-toggle ${activeWorkflow ? 'config-toggle-active' : ''}`}
            onClick={async () => {
              // Refresh on open so the picker shows the latest
              // list (cheap: single directory scan + parse).
              if (!showWorkflows) void refreshWorkflows();
              setShowWorkflows((v) => !v);
            }}
            // removed the
            // `disabled={availableWorkflows.length === 0 &&
            // !showWorkflows}` gate. The user should always
            // be able to open the picker — that's where the
            // "Import examples" button lives for the empty case.
            title={
              availableWorkflows.length === 0
                ? 'cwd 下没有 workflow — 下方可一键导入示例'
                : '选择 workflow'
            }
          >
            ⚡ {activeWorkflow ? activeWorkflow.name : 'Workflow'}
            {availableWorkflows.length > 0 && ` (${availableWorkflows.length})`}
          </button>
          {showWorkflows && (
            <div className="tools-popup">
              {activeWorkflow && (
                <button
                  key="__clear__"
                  className="workflow-item workflow-item-clear"
                  onClick={() => { setActiveWorkflow(null); setShowWorkflows(false); }}
                >
                  ✕ 清除选择
                </button>
              )}
              {availableWorkflows.length === 0 && (
                <div className="workflow-empty">
                  <div>没有可用 workflow。</div>
                  <div className="workflow-empty-hint">
                    可以把 yaml 放到 <code>.aethercode/workflows/</code>,
                    或点击下方按钮一键导入 {WORKFLOW_EXAMPLES.length} 个示例。
                  </div>
                  {/* one-click example import. The
                      button is disabled while a previous
                      import is still in flight so the user
                      can't fire it twice in a row; the
                      result pill (below) shows the outcome
                      once it returns. */}
                  <button
                    className="workflow-import-btn"
                    onClick={async () => {
                      if (importingExamples) return;
                      setImportingExamples(true);
                      setExampleImportResult(null);
                      try {
                        const r = await importExampleWorkflows((name, content) =>
                          rpc.writeWorkflow(name, content),
                        );
                        setExampleImportResult(r);
                        // Refresh the cached list so the
                        // imported workflows show up without
                        // a manual close-and-reopen.
                        await refreshWorkflows();
                      } finally {
                        setImportingExamples(false);
                      }
                    }}
                    disabled={importingExamples}
                    title={`写入 ${WORKFLOW_EXAMPLES.length} 个示例 yaml 到 .aethercode/workflows/`}
                  >
                    {importingExamples
                      ? '导入中…'
                      : `📥 导入示例 (${WORKFLOW_EXAMPLES.length} 个)`}
                  </button>
                  {/* import result pill. Shows only
                      after an import has run; collapses
                      back to the import button on the next
                      picker open. The pill is intentionally
                      short (3 lines max) so the picker
                      doesn't grow tall. */}
                  {exampleImportResult && (
                    <div className={`workflow-import-result ${
                      exampleImportResult.failed.length === 0
                        ? 'workflow-import-result-ok'
                        : exampleImportResult.imported.length === 0
                          ? 'workflow-import-result-error'
                          : 'workflow-import-result-partial'
                    }`}>
                      {exampleImportResult.failed.length === 0
                        ? `✅ 导入 ${exampleImportResult.imported.length} 个: ${exampleImportResult.imported.join(', ')}`
                        : exampleImportResult.imported.length === 0
                          ? `❌ 全部失败 (${exampleImportResult.failed.length}/${exampleImportResult.failed.length + 0})`
                          : `⚠️ 部分成功: ${exampleImportResult.imported.length} 成功, ${exampleImportResult.failed.length} 失败`}
                      {exampleImportResult.failed.length > 0 && (
                        <ul className="workflow-import-failed">
                          {exampleImportResult.failed.map((f) => (
                            <li key={f.name}>
                              <code>{f.name}</code>: {f.reason}
                            </li>
                          ))}
                        </ul>
                      )}
                    </div>
                  )}
                </div>
              )}
              {/* "Recently used" section. Renders the
                  most-recently-selected workflow names
                  above the full list, intersected with
                  the current availableWorkflows so a
                  stale LRU entry (the yaml was deleted
                  since the LRU was last updated) doesn't
                  show as a ghost button. The cap is 5;
                  the store enforces dedupe + push-to-front
                  on every setActiveWorkflow call. Empty
                  LRU + non-empty full list = no section
                  at all (don't add visual noise). The
                  section header mirrors the empty-state
                  hint typography so the popup reads as
                  one continuous block. */}
              {recentWorkflows.length > 0 && availableWorkflows.length > 0 && (() => {
                // Build a quick lookup so we can pull
                // the full WorkflowDoc out of
                // availableWorkflows by name (the LRU
                // only stores names, not the full doc).
                const byName = new Map(availableWorkflows.map((w) => [w.name, w]));
                const recentPicks = recentWorkflows
                  .map((n) => byName.get(n))
                  .filter((w): w is NonNullable<typeof w> => Boolean(w));
                if (recentPicks.length === 0) return null;
                return (
                  <div className="workflow-recent-section">
                    <div className="workflow-recent-label">最近使用</div>
                    {recentPicks.map((w) => (
                      <button
                        key={`recent-${w.name}`}
                        className={`workflow-item ${activeWorkflow?.name === w.name ? 'workflow-item-active' : ''}`}
                        onClick={() => { setActiveWorkflow(w); setShowWorkflows(false); }}
                        title={w.description}
                      >
                        <span className="workflow-item-name">⚡ {w.name}</span>
                        {w.description && (
                          <span className="workflow-item-desc">{w.description}</span>
                        )}
                        {w.stepCount > 0 && (
                          <span className="workflow-item-steps">{w.stepCount} 步</span>
                        )}
                      </button>
                    ))}
                    <div className="workflow-recent-divider" />
                  </div>
                );
              })()}
              {availableWorkflows.map((w) => (
                <button
                  key={w.name}
                  className={`workflow-item ${activeWorkflow?.name === w.name ? 'workflow-item-active' : ''}`}
                  onClick={() => { setActiveWorkflow(w); setShowWorkflows(false); }}
                  title={w.description}
                >
                  <span className="workflow-item-name">⚡ {w.name}</span>
                  {w.description && (
                    <span className="workflow-item-desc">{w.description}</span>
                  )}
                  {w.stepCount > 0 && (
                    <span className="workflow-item-steps">{w.stepCount} 步</span>
                  )}
                </button>
              ))}
            </div>
          )}
        </div>
        <div className="config-group cwd-group" title={cwd ?? 'No working directory set'}>
          <label className="config-label">📂 cwd</label>
          {editingCwd ? (
            <input
              className="config-cwd-input"
              value={cwdInput}
              onChange={(e) => setCwdInput(e.target.value)}
              onBlur={submitCwd}
              onKeyDown={(e) => {
                if (e.key === 'Enter') { e.preventDefault(); submitCwd(); }
                else if (e.key === 'Escape') { setEditingCwd(false); setCwdInput(cwd ?? ''); }
              }}
              autoFocus
              spellCheck={false}
              placeholder="C:\path\to\project"
            />
          ) : (
            <button
              className="config-cwd-btn"
              onClick={() => setEditingCwd(true)}
              title={cwd ?? 'Click to type a path, or 📁 to browse'}
            >
              {shortPath(cwd)}
            </button>
          )}
          <button className="config-pick-btn" onClick={pickCwd} title="Browse for folder">📁</button>
        </div>
      </div>
      <div className="message-input-wrap">
        {/* slash-command dropdown. Mounted when the user
            has typed `/` and no space yet. We position the
            dropdown above the input box via CSS. The dropdown
            owns its own keyboard handler (↑↓ Enter Esc Tab)
            that we don't duplicate here. */}
        {slashOpen && (
          <CommandDropdown
            query={slashQuery!}
            onSelect={handleCommand}
            activeIdx={slashIdx}
            onActiveIdxChange={setSlashIdx}
          />
        )}
        {/* `@`-mention dropdown. Mirrors the slash
            dropdown (parent-controlled, position:absolute
            above the input, ↑↓ Enter Esc Tab to navigate).
            Empty state is a single line so the user knows
            no files matched. */}
        {mentionOpen && mentionQuery != null && (
          <div
            className="mention-dropdown"
            role="listbox"
            aria-label="Files under cwd"
            tabIndex={-1}
          >
            <div className="mention-dropdown-head">
              <span className="mention-dropdown-tag">📎 @{mentionQuery || ''}</span>
              <span className="mention-dropdown-count">{mentionItems.length} 个</span>
            </div>
            {mentionItems.length === 0 ? (
              <div className="mention-dropdown-empty">cwd 下没有匹配的文件</div>
            ) : (
              <ul className="mention-dropdown-list">
                {mentionItems.map((f, i) => (
                  <li
                    key={f}
                    data-idx={i}
                    className={`mention-dropdown-item ${i === mentionIdx ? 'active' : ''}`}
                    role="option"
                    aria-selected={i === mentionIdx}
                    onMouseEnter={() => setMentionIdx(i)}
                    onMouseDown={(e) => { e.preventDefault(); insertMention(f); }}
                  >
                    <span className="mention-dropdown-icon" aria-hidden="true">📄</span>
                    <span className="mention-dropdown-text">{f}</span>
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}
        <textarea
          ref={ref}
          className="message-input"
          value={currentInput}
          onChange={(e) => setCurrentInput(e.target.value)}
          onKeyDown={onKeyDown}
          placeholder={
            isConnected
              ? 'Type your message…  (Enter to send, Shift+Enter for newline, / for commands)'
              : 'Connecting to daemon…'
          }
          rows={2}
          disabled={!isConnected}
        />
        {/* small send button anchored to the textarea's
         *  bottom-right corner. Previously this was a 72px-wide
         *  "Send" pill sitting to the right of the textarea
         *  (which made the input feel cramped and stole vertical
         *  space from the box itself). The new layout puts a
         *  32x32 button inside the textarea's bottom-right
         *  corner; the input box now spans the full width, the
         *  button doesn't compete for attention, and the user
         *  can also use Enter to send (no need to reach for
         *  Ctrl+Enter). The Cancel button takes the same slot
         *  when a query is in flight. */}
        {isStreaming ? (
          <button
            className="message-input-corner cancel"
            onClick={cancelQuery}
            title="Cancel (Esc)"
          >
            ⏹
          </button>
        ) : (
          <button
            className="message-input-corner primary"
            onClick={sendMessage}
            disabled={!isConnected || !currentInput.trim()}
            title="Send (Enter)"
            aria-label="Send message"
          >
            ➤
          </button>
        )}
      </div>
      <div className="input-hint">
        <kbd>Enter</kbd> send · <kbd>Shift</kbd>+<kbd>Enter</kbd> newline · <kbd>Esc</kbd> cancel · <kbd>/</kbd> commands
        {cmdStatus && (
          <span
            className={`input-hint-cmd input-hint-cmd-${cmdStatus.kind}`}
            title="slash command result"
          >
            {cmdStatus.text}
          </span>
        )}
        {isStreaming && <span className="input-hint-streaming">● live</span>}
        {!isConnected && <span className="input-hint-streaming" style={{ color: 'var(--error)' }}>● disconnected</span>}
      </div>
    </div>
  );
}
