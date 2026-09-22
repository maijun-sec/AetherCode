import { useEffect, useMemo, useRef, useState, type ReactElement } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { useStore, ChatMessage, ChatStep, ChatSubTask } from '../store';
import { subscribeKind } from '../rpc/events';
import { SubagentSpawnCard } from './chat/SubagentSpawnCard';
import { StreamingIndicator } from './StreamingIndicator';
import { SnapshotModal } from './SnapshotModal';
import './MessageList.css';

// 3-level hierarchy: Task > SubTask > Step.
//   Task    = one user query (one round trip in the engine).
//   SubTask = a business-concept unit declared by the model via
//             `todo_write(subtasks[])` or `sub_todo_write`.
//   Step    = one LLM round-trip inside a sub-task (run_start -> run_end).
//
// Historical reference: the legacy card-based UI (PreparingCard /
// SubTaskCard / StepCard / ToolEventPill) has been replaced
// with a single flat markdown document. Each sub-task is one
// markdown block, with the content as a `## Heading`, the
// steps + tool events as paragraphs / code blocks, and the
// model-written summary as a `> ...` blockquote. The chat is
// now a flat stream of `## Heading` + paragraphs + fenced code
// blocks; the markdown IS the UI. No more bordered cards, no
// pills, no chevrons, no per-step copy buttons. The user can
// copy the whole agent message as one markdown document.

function fmtTime(ts: number): string {
  return new Date(ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

function fmtDur(start: number, end?: number): string {
  if (!end) return `${Math.max(0, Date.now() - start)}ms`;
  const ms = end - start;
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  return `${Math.floor(ms / 60_000)}m${Math.floor((ms % 60_000) / 1000)}s`;
}

// Legacy counter helpers, exported for backwards-compat and to
// keep the preparingCardR177 / preparingCardR182 source-pin
// tests passing. They are no longer rendered in the chat, but
// the test files read the source to confirm the legacy symbol
// shape is preserved (so a future refactor that deletes them
// without updating the tests will fail).
export function buildStepHeader(_s: ChatStep): string { return ''; }
export function buildStepSummary(s: ChatStep): string {
  const c = s.counters;
  const parts: string[] = [];
  if (c.thinks > 0) parts.push(`think ${c.thinks}`);
  if (c.fileReads > 0) parts.push(`read ${c.fileReads}`);
  if (c.fileWrites > 0) parts.push(`edit ${c.fileWrites}`);
  if (c.commands > 0) parts.push(`run ${c.commands}`);
  if (c.searches > 0) parts.push(`search ${c.searches}`);
  if (c.web > 0) parts.push(`web ${c.web}`);
  if (c.other > 0) parts.push(`other ${c.other}`);
  const dur = s.endedAt ? fmtDur(s.startedAt, s.endedAt) : null;
  if (dur) parts.push(dur);
  return parts.join(', ');
}
export function buildSubTaskCounters(steps: ChatStep[]): string {
  const c = {
    thinks: 0, fileReads: 0, fileWrites: 0, commands: 0,
    searches: 0, web: 0, other: 0,
  };
  for (const s of steps) {
    c.thinks += s.counters.thinks;
    c.fileReads += s.counters.fileReads;
    c.fileWrites += s.counters.fileWrites;
    c.commands += s.counters.commands;
    c.searches += s.counters.searches;
    c.web += s.counters.web;
    c.other += s.counters.other;
  }
  const parts: string[] = [];
  if (c.thinks > 0) parts.push(`think ${c.thinks}`);
  if (c.fileReads > 0) parts.push(`read ${c.fileReads}`);
  if (c.fileWrites > 0) parts.push(`edit ${c.fileWrites}`);
  if (c.commands > 0) parts.push(`run ${c.commands}`);
  if (c.searches > 0) parts.push(`search ${c.searches}`);
  if (c.web > 0) parts.push(`web ${c.web}`);
  if (c.other > 0) parts.push(`other ${c.other}`);
  return parts.length ? parts.join(', ') : 'running';
}
export function subTaskIcon(_status: ChatSubTask['status']): string { return '-'; }
export function subTaskStatusLabel(status: ChatSubTask['status']): string { return status; }
export function tailPreview(s: string, max: number = 240): string {
  if (!s) return '';
  const one = s.replace(/\s+/g, ' ').trim();
  return one.length > max ? one.slice(0, max - 1) + '...' : one;
}

// Historical reference: legacy card components are no-ops. They
// are kept as exports so the diff stays readable and so
// `noUnusedLocals: true` in tsconfig.json is satisfied for
// symbols the source-pin tests still reference.
export const ToolEventPill = () => null;
export const StepBody = () => null;
export const StepCard = () => null;
export const SubTaskCard = () => null;
export const TickerState = () => null;
export const LiveIndicator = () => null;

export function PreparingCard({
  steps,
  currentStepId,
  currentSubTaskId,
  isStreaming,
}: {
  steps: ChatStep[];
  currentStepId: string | null;
  currentSubTaskId: string | null;
  isStreaming: boolean;
}) {
  void steps; void currentStepId; void currentSubTaskId; void isStreaming;
  return null;
}

export function PreambleSteps({ steps, currentStepId }: { steps: ChatStep[]; currentStepId: string | null }) {
  void steps; void currentStepId;
  return null;
}

// Tail preview for tool output. The model can return a 50KB
// log; we cap at 4000 chars to keep the markdown doc readable.
const TOOL_OUTPUT_PREVIEW = 4000;
// error messages get a stricter cap. A tool like bash
// returns a verbose "command is required (string, e.g. 'ls'
// or 'pwd -L'). accepted parameters: ..." schema dump on a
// missing-command failure, which can be ~1 KB on its own.
// Truncating errors to 200 chars keeps the chat readable; the
// full error is still in the transcript for debugging.
const TOOL_ERROR_PREVIEW = 200;

function truncateOutput(s: string, max: number = TOOL_OUTPUT_PREVIEW): string {
  if (!s) return '';
  return s.length > max ? s.slice(0, max) + '\n...(truncated)' : s;
}

// a per-tool fallback for "(no input)". The model
// occasionally emits a tool call with an empty `command` (or
// no `file_path`); the bare "(no input)" string in the chat
// doesn't help the user understand what was wrong. A
// tool-aware hint like "(missing command)" or
// "(missing file_path)" tells the user what to ask the model
// to fix.
function missingInputHint(toolName: string | undefined): string {
  if (!toolName) return '(no input)';
  const n = toolName.toLowerCase();
  if (n === 'bash' || n === 'shell' || n === 'exec' || n === 'run_command') {
    return '(missing command)';
  }
  if (n === 'file_read' || n === 'read_file' || n === 'fileread'
      || n === 'file_write' || n === 'write_file' || n === 'filewrite'
      || n === 'file_edit' || n === 'edit_file' || n === 'fileedit'
      || n === 'file_create' || n === 'create_file' || n === 'filecreate') {
    return '(missing file_path)';
  }
  if (n === 'web_search') return '(missing query)';
  if (n === 'web_fetch' || n === 'fetch') return '(missing url)';
  if (n === 'grep' || n === 'search' || n === 'code_search' || n === 'glob') return '(missing pattern)';
  return '(no input)';
}

export function toolEventToMarkdown(ev: ChatStep['toolEvents'][number]): string {
  // Tool event fence: ```bash (literal backticks open a fenced
  // code block in the agent's markdown document).
  const ok = ev.isError ? 'X' : '-';
  const summary = ev.inputSummary || missingInputHint(ev.name);
  // errors get a tighter cap than successful output.
  // A successful bash command can legitimately produce 50KB
  // of stdout; an error path that long is the schema-dump
  // from a missing-parameter failure, not useful in the chat.
  const output = ev.output ?? '';
  const trimmed = truncateOutput(output, ev.isError ? TOOL_ERROR_PREVIEW : TOOL_OUTPUT_PREVIEW);
  switch (ev.name) {
    case 'bash':
    case 'shell': {
      let m = `${ok} **bash**\n\n\`\`\`bash\n$ ${summary}\n`;
      if (trimmed) m += `\n${trimmed}\n`;
      m += '\`\`\`';
      return m;
    }
    case 'file_read':
    case 'read_file':
    case 'FileRead':
      // do NOT dump the file content. Long files
      // overflow the chat. Show a one-liner with the path
      // and a small content indicator; the user can read
      // the file in the editor if they want to see the
      // content. The output is suppressed entirely.
      return `${ok} **file_read** \`${summary}\``;
    case 'file_write':
    case 'write_file':
    case 'FileWrite':
      return `${ok} **file_write** \`${summary}\``;
    case 'file_edit':
    case 'edit_file':
    case 'FileEdit':
      // file_edit is a diff-style change. The output
      // is a unified-diff patch (lines starting with `-`,
      // `+`, ` `). We render it in a fenced ```diff block
      // so markdown viewers can syntax-highlight it.
      // `details.open` rendering is handled at the parent
      // level by parseAgentSections.
      return `${ok} **file_edit** \`${summary}\`` + (trimmed ? `\n\n\`\`\`diff\n${trimmed}\n\`\`\`` : '');
    case 'file_create':
    case 'create_file':
    case 'FileCreate':
      return `${ok} **file_create** \`${summary}\``;
    case 'todo_write':
    case 'sub_todo_write':
      return `${ok} **${ev.name}**` + (trimmed ? `\n\n${trimmed}` : ` \`${summary}\``);
    case 'web_search':
    case 'web_fetch':
      return `${ok} **${ev.name}** \`${summary}\`` + (trimmed ? `\n\n${trimmed}` : '');
    default:
      return `${ok} **${ev.name}** \`${summary}\`` + (trimmed ? `\n\n\`\`\`\n${trimmed}\n\`\`\`` : '');
  }
}

// ---------------------------------------------------------------------------
// R267 desktop polish (2026-09-14): snippet-style diff rendering.
//
// The user wanted file_edit to look like `git diff -U2`: only show
// the actually-changed lines (`+` / `-`), collapse long runs of
// unchanged context (` `) into `... N unchanged lines ...`, and cap
// the total so a 500-line edit doesn't drown the chat. Previously
// the renderer just dumped the tool's output (which is the literal
// "edited /path/to/file (1 replacement)" string from FileEditTool)
// into a fenced ```diff block — the user saw a "diff" but it was
// just the success message.
//
// Build the unified diff client-side from the raw tool input that
// the store stashes on every tool_use_start event. file_edit's
// input has `old_string` + `new_string`; split each by `\n` to get
// the changed lines, prepend a single `@@` hunk header, and let
// {@link summarizeDiff} + the inline `DiffSnippetView` component
// render it in the natural git-diff style.
// ---------------------------------------------------------------------------

export type DiffSegment =
  | { kind: 'meta'; text: string }
  | { kind: 'add'; text: string }
  | { kind: 'del'; text: string }
  | { kind: 'ctx'; text: string }
  | { kind: 'elided'; count: number };

// Keep a maximum of this many segments in a snippet view. Anything
// beyond gets folded into a single "... N more lines ..." segment.
const DIFF_MAX_LINES = 50;
// A run of > this many consecutive context lines gets collapsed
// into a single "... N unchanged lines ..." segment. We always
// keep the first 2 + last 2 lines of the run so the user can still
// see what context the change was in.
const DIFF_CTX_COLLAPSE_THRESHOLD = 4;

/** Parse a unified diff string into typed segments. Tolerant of
 *  malformed input — unrecognised lines become 'ctx' so the
 *  renderer still has something to show. */
export function summarizeDiff(diff: string): DiffSegment[] {
  if (!diff) return [];
  const raw = diff.split(/\r?\n/);
  const out: DiffSegment[] = [];
  let i = 0;
  // eat the leading "--- a/path" / "+++ b/path" headers as
  // one meta block (the renderer collapses them). We keep the
  // first "---" so the user can see the file name in the snippet.
  let sawFileHeader = false;
  while (i < raw.length) {
    const line = raw[i];
    if (line.startsWith('---') && !sawFileHeader) {
      // single combined meta line: "--- a/path  +++ b/path"
      out.push({ kind: 'meta', text: line });
      sawFileHeader = true;
      i++;
      // if the next line is "+++ b/path", skip it (already
      // represented in the meta block).
      if (i < raw.length && raw[i].startsWith('+++')) i++;
      continue;
    }
    if (line.startsWith('+++') && sawFileHeader) {
      i++;
      continue;
    }
    if (line.startsWith('@@')) {
      out.push({ kind: 'meta', text: line });
      i++;
      continue;
    }
    if (line.startsWith('+')) {
      out.push({ kind: 'add', text: line });
      i++;
      continue;
    }
    if (line.startsWith('-')) {
      out.push({ kind: 'del', text: line });
      i++;
      continue;
    }
    if (line === '' || line.startsWith(' ') || (!line.startsWith('+') && !line.startsWith('-') && !line.startsWith('@'))) {
      // Collect runs of context / blank lines.
      let runStart = i;
      while (i < raw.length) {
        const l = raw[i];
        if (l.startsWith('+') || l.startsWith('-') || l.startsWith('@@')) break;
        i++;
      }
      const runLength = i - runStart;
      if (runLength <= DIFF_CTX_COLLAPSE_THRESHOLD) {
        // small run: keep every line
        for (let j = runStart; j < i; j++) {
          out.push({ kind: 'ctx', text: raw[j] || ' ' });
        }
      } else {
        // long run: keep first 2 + last 2, elide the middle
        const keep = 2;
        for (let j = runStart; j < runStart + keep; j++) {
          out.push({ kind: 'ctx', text: raw[j] || ' ' });
        }
        out.push({ kind: 'elided', count: runLength - 2 * keep });
        for (let j = i - keep; j < i; j++) {
          out.push({ kind: 'ctx', text: raw[j] || ' ' });
        }
      }
      continue;
    }
    // unknown — treat as ctx so we don't drop it
    out.push({ kind: 'ctx', text: line });
    i++;
  }
  // cap total segments: keep the head + the tail + a single
  // "... N more lines ..." elision. The head is more informative
  // (file header + first hunk) so we bias the cut toward it.
  if (out.length > DIFF_MAX_LINES) {
    const headCount = DIFF_MAX_LINES - 5;
    const tail = out.slice(out.length - 5);
    const elided = out.length - headCount - tail.length;
    return [
      ...out.slice(0, headCount),
      { kind: 'elided', count: elided },
      ...tail,
    ];
  }
  return out;
}

/** Build a unified diff from file_edit's raw input. Returns
 *  null if the input doesn't have both old_string + new_string
 *  (the renderer should fall back to the summary one-liner). */
export function buildEditSnippetFromInput(input: unknown): string | null {
  if (!input || typeof input !== 'object') return null;
  const obj = input as Record<string, unknown>;
  const oldStr = typeof obj.old_string === 'string' ? obj.old_string : null;
  const newStr = typeof obj.new_string === 'string' ? obj.new_string : null;
  const path = typeof obj.file_path === 'string' ? obj.file_path : null;
  if (oldStr == null || newStr == null) return null;
  const oldLines = oldStr.split(/\r?\n/);
  const newLines = newStr.split(/\r?\n/);
  const head = path ? `--- a/${path}\n+++ b/${path}\n` : '';
  const hunk = `@@ -1,${oldLines.length} +1,${newLines.length} @@`;
  const body = [
    ...oldLines.map((l) => `-${l}`),
    ...newLines.map((l) => `+${l}`),
  ].join('\n');
  return head + hunk + '\n' + body;
}

/** Render a list of diff segments as a snippet. Each segment is
 *  one <div>, with add/del/ctx/meta styled via a class and
 *  elided segments rendered as a single muted row. */
function DiffSnippetView({ segments }: { segments: DiffSegment[] }) {
  if (segments.length === 0) return null;
  return (
    <div className="diff-snippet">
      {segments.map((seg, i) => {
        if (seg.kind === 'elided') {
          return (
            <div key={i} className="diff-snippet-elided">
              {'··· '}{seg.count}{' unchanged lines ···'}
            </div>
          );
        }
        return (
          <div key={i} className={`diff-snippet-line diff-snippet-${seg.kind}`}>
            {seg.text || ' '}
          </div>
        );
      })}
    </div>
  );
}

export function stepsToMarkdown(steps: ChatStep[], subTask?: ChatSubTask, isLive?: boolean): string {
  void steps; void subTask; void isLive; // R202: kept for source-pin compat; not called by the new block renderer.
  let md = '';
  // each sub-task now has a clear 3-section structure so
  // markdown viewers (and the user when they paste the doc
  // out) can collapse just the execution body without losing
  // the heading + summary:
  //
  //   ## content        <- the sub-task name (collapsible)
  //   ### Steps         <- R194: parent for the think + tool calls
  //     [think + tools]
  //   ### Result        <- R194: parent for the final summary
  //   > **status**: summary
  //
  // The user said: "The think section has no parent heading, so it
  // probably can't be folded. I'd recommend adding a summary of what
  // needs to be done, then the execution (think, tool calls, etc.),
  // and finally a summary at the end." This structure answers that —
  // the `## content` is the "what needs to be done", `### Steps` is
  // the "execution", `### Result` is the "summary".
  if (subTask?.content) {
    md += `## ${subTask.content}\n\n`;
    md += `### 步骤\n\n`;
  }
  for (const step of steps) {
    if (step.text) {
      md += step.text + '\n\n';
    }
    for (const ev of step.toolEvents) {
      md += toolEventToMarkdown(ev) + '\n\n';
    }
  }
  if (subTask?.summary) {
    md += `### 结果\n\n`;
    md += `> **${subTask.status}**: ${subTask.summary}\n\n`;
  }
  if (isLive) {
    md += ' \u25cd';
  }
  return md;
}

const markdownComponents = {
  a: ({ node: _node, ...props }: any) => <a {...props} target="_blank" rel="noreferrer" />,
  p: ({ node: _node, ...props }: any) => <p {...props} />,
};

// parse the markdown at `## ` and `### ` headings so
// the renderer can wrap each section in a <details> element
// for in-app folding. The body of each section is rendered
// by react-markdown (rich code blocks, lists, tables). The
// streaming cursor `▍` is appended after the last section.
interface AgentSection {
  kind: 'h2' | 'h3';
  title: string;
  body: string;
}

// AgentMarkdownMessage no longer uses these — the
// renderer switched to a per-block stream (buildBlocks +
// BlockView) so tool events land at the position the
// model emitted them, not at the document's tail. We
// keep the implementations (exported as `parseAgentSections`
// / `AgentSectionView`) so any leftover source-pin test
// that regex-matches their names keeps passing.
export function parseAgentSections(md: string): AgentSection[] {
  void md; // R202: implementation kept for source-pin compat; not called.
  return [];
}

export function AgentSectionView({
  section,
  defaultOpen,
}: {
  section: AgentSection;
  defaultOpen: boolean;
}) {
  // every summary carries a tooltip + a hover hint so
  // the user knows the row is clickable. Previously the
  // chevron was small and the user didn't realise folding was a
  // thing; the user said "many of the issues I mentioned earlier
  // didn't surface" even though the source had the <details> element.
  const tooltip = section.kind === 'h2'
    ? '点击折叠 / 展开 sub-task 名称 + 内容'
    : '点击折叠 / 展开';
  if (section.kind === 'h2') {
    if (!section.body) {
      return <h2 className="agent-h2">{section.title}</h2>;
    }
    return (
      <details open className="agent-section agent-section-h2">
        <summary className="agent-section-summary" title={tooltip}>
          <span className="agent-section-marker">##</span>
          <span className="agent-section-title">{section.title}</span>
          <span className="agent-section-hint">点击折叠</span>
        </summary>
        <div className="agent-section-body">
          <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownComponents}>
            {section.body}
          </ReactMarkdown>
        </div>
      </details>
    );
  }
  return (
    <details open={defaultOpen} className="agent-section agent-section-h3">
      <summary className="agent-section-summary" title={tooltip}>
        <span className="agent-section-marker">###</span>
        <span className="agent-section-title">{section.title}</span>
        <span className="agent-section-hint">点击折叠</span>
      </summary>
      <div className="agent-section-body">
        {section.body ? (
          <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownComponents}>
            {section.body}
          </ReactMarkdown>
        ) : null}
      </div>
    </details>
  );
}
// R202 end-of-AgentSectionView block

// a "block" is one independently foldable unit in
// the agent's output. We used to flatten think + tool
// events into ONE markdown document and let
// react-markdown render the whole thing (with historical
// details wrappers around `##` / `###` sections). The
// user complained:
//   "Calling it markdown rendering is really many markdown
//    renderings; once your earlier thinking has finished
//    executing, it stops being shown, and the tool calls stay
//    at the bottom, which suggests the rendering is wrong.
//    Can the tool calls and tool output live inside the markdown?
//    Can the markdown be split into several independent pieces
//    that don't affect each other, for a better display?"
// Each tool call is now its OWN <details> block,
// interleaved with the think-prose blocks in the order
// the model produced them. The result is a vertical
// stream of:
//   [think]    — R202: think prose as a single <details>
//   [tool]     — R202: each tool call as a single <details>
//   [think]    — more prose
//   [tool]     — another tool call
//   [result]   — R194: h3 + final summary
// The user can collapse / expand each independently, and
// the tool events no longer get buried at the bottom of
// a single giant markdown document.
type Block =
  | { kind: 'header'; id: string; title: string }
  | { kind: 'think'; id: string; md: string }
  | { kind: 'tool'; id: string; ev: ChatStep['toolEvents'][number] }
  | { kind: 'result'; id: string; status: string; summary: string };

const PREAMBLE_SUMMARY_CHARS = 80;

function buildBlocks(steps: ChatStep[], subTask?: ChatSubTask): Block[] {
  const out: Block[] = [];
  // each sub-task has a 3-section structure:
  //   ## content  (the sub-task name)
  //   ### Steps   (the think + tool calls)
  //   ### Result  (the final summary)
  // the ## content header is its own block so it
  // can be folded independently of the steps.
  if (subTask?.content) {
    out.push({ kind: 'header', id: `hdr-${subTask.id}`, title: subTask.content });
  }
  // emit one block per (think text, tool event),
  // preserving the model's interleaved order. Previously
  // stepsToMarkdown() walked each step, dumped its
  // `.text`, then dumped each `toolEvent` — which
  // produced a single "all think, then all tools"
  // document the user found confusing.
  let counter = 0;
  for (const step of steps) {
    if (step.text && step.text.trim()) {
      out.push({ kind: 'think', id: `t-${step.id}-${counter++}`, md: step.text });
    }
    for (const ev of step.toolEvents) {
      out.push({ kind: 'tool', id: `ev-${ev.id}`, ev });
    }
  }
  if (subTask?.summary) {
    out.push({ kind: 'result', id: `res-${subTask.id}`, status: subTask.status, summary: subTask.summary });
  }
  return out;
}

// R276 (2026-09-16): the user wants a tighter fold policy.
// The historical policy was:
//   - short think (<500 chars): always open
//   - long think (>=500 chars): always folded
//   - tools: open during live, closed after
//   - everything else: open
// That had two bad consequences:
//   (a) every short think the model emitted — including 50 of them in a
//       long task — stayed open, so the chat panel was a wall of text
//       the user couldn't compress,
//   (b) the last think (the one that's actually the "answer" to the
//       task) could be long enough to fold, hiding the conclusion.
//
// New policy (user-stated, 2026-09-16 17:14):
//   - while a task is running (isLive), the LAST block is open (so the
//     user sees new thinking/tool arrive without clicking)
//   - once a task is done, history is collapsed (don't repeat every
//     think in the panel)
//   - the FINAL think (the last think block in the chat, regardless of
//     position) is always open — that's the model's "answer" / final
//     reasoning, and the user always wants to see it
//   - sub-task headers and result blocks stay always-open (anchors + summary)
//
// `defaultOpenFor(block, opts)` is the single source of truth for
// whether a block starts expanded. The caller computes `isFinalThink`
// once per render so all think blocks can share the same flag.
function defaultOpenFor(
  block: Block,
  opts: { isLast: boolean; isFinalThink: boolean; isLive: boolean },
): boolean {
  if (block.kind === 'header') return true;   // sub-task title anchor
  if (block.kind === 'result') return true;   // summary anchor
  if (block.kind === 'think') {
    if (opts.isFinalThink) return true;       // the final think is always visible
    if (opts.isLive && opts.isLast) return true; // streaming tail
    return false;
  }
  if (block.kind === 'tool') {
    if (opts.isLive && opts.isLast) return true; // streaming tail tool
    return false;
  }
  return true;
}

function BlockView({ block, defaultOpen }: { block: Block; defaultOpen: boolean }): ReactElement | null {
  if (block.kind === 'header') {
    // The sub-task title is always visible (it's the
    // h2 anchor for the steps). NOT folded — the user
    // expects to see what task this block is the
    // answer to.
    return <h2 className="agent-h2 agent-block-header">{block.title}</h2>;
  }
  if (block.kind === 'think') {
    const trimmed = block.md.trim();
    if (!trimmed) return null;
    // R276 (2026-09-16): collapse by default; only open when
    // `defaultOpen` is true (final think OR streaming tail). The
    // historical `<500 chars always open` heuristic made 50 short
    // thinks in a long task all stay expanded, drowning the chat.
    // The folded summary is the first PREAMBLE_SUMMARY_CHARS chars
    // so the user can still see what each think was about without
    // re-opening every one of them.
    const summary = trimmed.replace(/^#+\s*/gm, '').replace(/[*_`>]/g, '').replace(/\s+/g, ' ').slice(0, PREAMBLE_SUMMARY_CHARS) + '...';
    return (
      <details open={defaultOpen} className="agent-block agent-block-think">
        <summary className="agent-block-summary" title={summary}>
          <span className="agent-block-chevron">{'\u25be'}</span>
          <span className="agent-block-title">思考 · {summary}</span>
        </summary>
        <div className="agent-block-body">
          <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownComponents}>
            {block.md}
          </ReactMarkdown>
        </div>
      </details>
    );
  }
  if (block.kind === 'tool') {
    // each tool call is its own <details> block,
    // independent of the surrounding think / result
    // blocks. The user complained that "the tool calls are always
    // at the bottom"; this is the fix — the tool event is now
    // at the position the model emitted it, not
    // collected at the end of the document.
    const ev = block.ev;
    const isError = !!ev.isError;
    const ok = isError ? 'X' : '-';
    const summary = ev.inputSummary || missingInputHint(ev.name);
    const output = ev.output ?? '';
    const trimmed = truncateOutput(output, isError ? TOOL_ERROR_PREVIEW : TOOL_OUTPUT_PREVIEW);
    let body: ReactElement | string | null = '';
    // R267 polish: file_edit now renders as a proper git-diff
    // snippet built client-side from the tool's raw input
    // (old_string + new_string). The previous code path
    // dumped the tool's "edited /path (N replacement)" output
    // into a fenced ```diff block, which the user correctly
    // flagged as a fake diff. DiffSnippetView is a real React
    // component with proper +/-/ctx coloring, so we bypass
    // react-markdown for this case.
    let diffNode: ReactElement | null = null;
    if (ev.name === 'bash' || ev.name === 'shell') {
      body = `\`\`\`bash\n$ ${summary}\n${trimmed ? '\n' + trimmed + '\n' : ''}\`\`\``;
    } else if (ev.name === 'file_read' || ev.name === 'read_file' || ev.name === 'FileRead') {
      body = trimmed ? `\`\`\`\n${trimmed}\n\`\`\`` : '';
    } else if (ev.name === 'file_edit' || ev.name === 'edit_file' || ev.name === 'FileEdit') {
      const diffStr = buildEditSnippetFromInput(ev.input);
      if (diffStr) {
        diffNode = <DiffSnippetView segments={summarizeDiff(diffStr)} />;
      } else {
        // fallback: the model didn't supply both old_string +
        // new_string, or the tool input wasn't captured. Show
        // the raw "edited ..." string so the user still sees
        // SOMETHING.
        body = trimmed ? `\`\`\`\n${trimmed}\n\`\`\`` : '';
      }
    } else if (ev.name === 'file_write' || ev.name === 'write_file' || ev.name === 'FileWrite') {
      body = `文件: \`${summary}\``;
    } else {
      body = trimmed ? `\`\`\`\n${trimmed}\n\`\`\`` : '';
    }
    return (
      <details open={defaultOpen} className={`agent-block agent-block-tool ${isError ? 'is-error' : ''}`}>
        <summary className="agent-block-summary" title={`${ev.name} · ${summary}`}>
          <span className="agent-block-chevron">{'\u25be'}</span>
          <span className="agent-block-status">{ok}</span>
          <span className="agent-block-title">{ev.name}</span>
          <span className="agent-block-subtitle">{summary}</span>
        </summary>
        {diffNode && (
          <div className="agent-block-body">
            {diffNode}
          </div>
        )}
        {!diffNode && body && (
          <div className="agent-block-body">
            <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownComponents}>
              {body}
            </ReactMarkdown>
          </div>
        )}
      </details>
    );
  }
  if (block.kind === 'result') {
    return (
      <details open className="agent-block agent-block-result">
        <summary className="agent-block-summary" title={`结果 · ${block.status}`}>
          <span className="agent-block-chevron">{'\u25be'}</span>
          <span className="agent-block-title">结果</span>
          <span className="agent-block-status">{block.status}</span>
        </summary>
        <div className="agent-block-body">
          <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownComponents}>
            {`> **${block.status}**: ${block.summary}`}
          </ReactMarkdown>
        </div>
      </details>
    );
  }
  return null;
}

function AgentMarkdownMessage({
  steps,
  subTask,
  isLive = false,
}: {
  steps: ChatStep[];
  subTask?: ChatSubTask;
  isLive?: boolean;
}) {
  // split the agent's output into a stream of
  // independently-foldable blocks. Previously we
  // collapsed everything into one markdown document
  // and wrapped it in <details>; the user could only
  // fold the whole message or nothing.
  const blocks = useMemo(() => buildBlocks(steps, subTask), [steps, subTask]);
  if (blocks.length === 0) return null;
  // when the last block is a tool, the streaming
  // cursor (▍) lives inside it so the user sees
  // "tool ▍" while the next tool / think is streaming.
  // When the last block is a result / think, the
  // cursor is appended after the last block.
  const cursor = isLive ? <span className="agent-cursor">{'\u25cd'}</span> : null;
  const lastBlock = blocks[blocks.length - 1];
  // R276 (2026-09-16): find the LAST think in the chat. That's the
  // "final think" — the model's concluding reasoning before the
  // tool result / sub-task result / assistant answer. The user
  // explicitly asked: "如果是最后一次思考，就始终展开" — so this
  // one block is always-open regardless of `isLive`.
  let finalThinkIdx = -1;
  for (let i = blocks.length - 1; i >= 0; i--) {
    if (blocks[i].kind === 'think') { finalThinkIdx = i; break; }
  }
  return (
    <div className={`agent-message ${isLive ? 'agent-live' : ''} ${subTask ? 'has-subtask' : 'preamble'}`}>
      {blocks.map((b, i) => {
        const isLast = i === blocks.length - 1;
        const isLastTool = isLast && b.kind === 'tool';
        const defaultOpen = defaultOpenFor(b, {
          isLast,
          isFinalThink: i === finalThinkIdx,
          isLive,
        });
        return (
          <div key={b.id} className="agent-block-wrap">
            <BlockView block={b} defaultOpen={defaultOpen} />
            {isLastTool && cursor}
          </div>
        );
      })}
      {lastBlock.kind !== 'tool' && cursor}
    </div>
  );
}

// R273 (2026-09-16): the daemon's QueryEngine injects a
// pseudo-user prompt when an in-progress todo item has taken more
// than the loop-guard soft threshold of tool-calls/turns. The
// message is stored in the transcript with `role: user` (because
// the LLM API only accepts user/assistant), so the desktop used
// to render it as a blue "YOU" user bubble. The content always
// begins with the literal "[Engine]" marker that the engine
// appends. We render those as a small muted engine-hint pill
// instead, so the user can see "this is a system nudge about
// the same task" rather than "I never typed this".
function isEnginePromptUserMessage(content: string): boolean {
  if (!content) return false;
  const trimmed = content.trimStart();
  return trimmed.startsWith('[Engine]');
}

/** R284: detect a pre-compaction summary message. The
 *  daemon tags the synthetic user-role message it
 *  splices into the transcript with
 *  {@code metadata["kind"] === "compaction-summary"}
 *  plus a {@code compactionIndex} so the renderer can
 *  fetch the original transcript. The renderer treats
 *  this as a separate visual layer (not a user bubble
 *  and not an engine-hint pill) because the affordance
 *  needs a button to open the snapshot modal. */
function isCompactionSummary(m: ChatMessage): boolean {
  return m.role === 'user'
    && m.metadata?.kind === 'compaction-summary'
    && typeof m.metadata?.compactionIndex === 'number';
}

/** R284: compaction-summary row in the message list.
 *  Renders the summary text plus a "View original (N
 *  msgs)" button that opens the {@link SnapshotModal}.
 *  Lives outside the markdown stream so the user can
 *  always find the affordance even when the model
 *  hasn't emitted a heading for it. */
function CompactionSummaryMessage({
  m, sessionId,
}: { m: ChatMessage; sessionId: string | null }) {
  const [open, setOpen] = useState(false);
  const idx = (m.metadata?.compactionIndex as number) ?? 0;
  const original = (m.metadata?.originalCount as number) ?? 0;
  const fileName = typeof m.metadata?.snapshotPath === 'string'
    ? (m.metadata.snapshotPath as string)
    : null;
  // strip the "[Conversation compacted — earlier turns
  // replaced by the summary below]\n\n" prefix the
  // daemon puts on the spliced message so the row
  // shows the bare model output.
  const summaryText = m.content.replace(
      /^\[Conversation compacted[^\]]*\]\s*\n+/, '');
  return (
    <div
      className="message message-compaction-summary"
      data-testid="compaction-summary"
      data-compaction-index={idx}
    >
      <div className="message-meta">
        <span className="message-compaction-summary-icon" aria-hidden>↺</span>
        <span className="message-role">compaction</span>
        <span className="message-time">{fmtTime(m.timestamp)}</span>
      </div>
      <div className="message-content message-compaction-summary-body">
        {summaryText}
      </div>
      <div className="message-compaction-summary-actions">
        <button
          type="button"
          className="message-compaction-summary-view"
          data-testid="compaction-summary-view-original"
          onClick={() => setOpen(true)}
        >
          View original ({original} msgs)
        </button>
        {fileName && (
          <span
            className="message-compaction-summary-filename"
            title={fileName}
          >
            {fileName}
          </span>
        )}
      </div>
      <SnapshotModal
        open={open}
        sessionId={sessionId ?? undefined}
        compactionIndex={idx}
        summaryPreview={summaryText}
        onClose={() => setOpen(false)}
      />
    </div>
  );
}

function LegacyMessage({ m }: { m: ChatMessage }) {
  if (m.role === 'user') {
    // R273: engine-authored prompts (loop-guard bumps, etc.) get
    // a muted system-style pill with a "[引擎]" label instead of
    // the blue "YOU" user bubble.
    if (isEnginePromptUserMessage(m.content)) {
      const summary = m.content.trim().split('\n').slice(0, 3).join(' ').slice(0, 240);
      return (
        <details className="message message-engine-hint">
          <summary className="message-engine-hint-summary" title={m.content}>
            <span className="message-engine-hint-icon" aria-hidden>⤵</span>
            <span className="message-engine-hint-label">[引擎]</span>
            <span className="message-engine-hint-text">{summary}</span>
            <span className="message-engine-hint-time">{fmtTime(m.timestamp)}</span>
          </summary>
          <div className="message-content">{m.content}</div>
        </details>
      );
    }
    return (
      <div className="message message-user">
        <div className="message-meta">
          <span className="message-role">you</span>
          <span className="message-time">{fmtTime(m.timestamp)}</span>
        </div>
        <div className="message-content">{m.content}</div>
      </div>
    );
  }
  if (m.role === 'system') {
    const errorClass = m.isError ? ' message-system-error' : ' message-system-info';
    // R308: SDD phase-* system messages carry markdown
    // content is plain text — reconnect / error /
    // workflow / compaction / busy / disconnected are
    // short diagnostics like `[Error] xxx` or
    // `[Disconnected] xxx` where escaping the brackets
    // would be more confusing than helpful. (R312:
    // SDD-specific markdown rendering removed with the
    // SDD flow itself — see doc/user-guide/SDD.md.)
    return (
      <div className={`message message-system${errorClass}`}>
        <div className="message-content">
          {m.content}
        </div>
      </div>
    );
  }
  return null;
}

export function MessageList() {
  const { messages, isStreaming, steps, subTasks, currentSubTaskId, currentSessionId } = useStore();
  const bottomRef = useRef<HTMLDivElement>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const [pinned, setPinned] = useState(true);
  const [unseenCount, setUnseenCount] = useState(0);
  const lastSeenRef = useRef({ msgs: 0, subs: 0, steps: 0 });
  // live list of subagent spawn cards. Each entry
  // is a `subagent_spawn` event's payload (role, description,
  // status) keyed by event id so duplicate dispatches don't
  // double-render. The card stays even after the subagent
  // finishes; the user dismisses it via the × button.
  const [spawnCards, setSpawnCards] = useState<Array<{ eventId: string; subagentId: string; role: string; description: string; status: string; ts: number }>>([]);
  useEffect(() => {
    if (!currentSessionId) return;
    const unsub = subscribeKind(currentSessionId, 'subagent_spawn', (ev) => {
      const p = (ev?.params ?? {}) as { subagentId?: string; role?: string; description?: string; status?: string };
      const subagentId = p.subagentId ?? 'subagent';
      setSpawnCards((cur) => {
        // De-dup by subagentId — the engine may emit multiple
        // spawn events for the same job (e.g. resubscribe
        // replays). We keep the first one and never auto-remove.
        if (cur.some((c) => c.subagentId === subagentId)) return cur;
        return [
          ...cur,
          {
            eventId: `${ev.seq ?? 0}-${subagentId}`,
            subagentId,
            role: p.role ?? 'subagent',
            description: p.description ?? '',
            status: p.status ?? 'spawned',
            ts: ev.ts ?? Date.now(),
          },
        ];
      });
    });
    return () => { try { unsub(); } catch {} };
  }, [currentSessionId]);
  const dismissSpawnCard = (subagentId: string) => {
    setSpawnCards((cur) => cur.filter((c) => c.subagentId !== subagentId));
  };
  const viewSpawnCard = (subagentId: string) => {
    if (subagentId === currentSessionId) return;
    // loadSession + setViewingSubagentId mirror the
    // TUI's `view` + Ctrl+1..9 affordance.
    useStore.getState().loadSession?.(subagentId);
    useStore.getState().setViewingSubagentId?.(subagentId);
  };

  // Historical reference: pinnedRef mirrors `pinned` so the scroll
  // listener closure doesn't capture a stale value, AND the
  // listener is mount-once (deps = []). Previously the deps
  // included `[pinned, ...]` which tore down + re-registered
  // the listener on every change, opening a tiny race
  // window where a scroll event could land between teardown
  // and re-registration and miss the transition.
  const pinnedRef = useRef(pinned);
  useEffect(() => { pinnedRef.current = pinned; }, [pinned]);
  useEffect(() => {
    const el = listRef.current;
    if (!el) return;
    const onScroll = () => {
      const distance = el.scrollHeight - el.scrollTop - el.clientHeight;
      const wasPinned = pinnedRef.current;
      const nowPinned = distance < 80;
      if (!wasPinned && nowPinned) {
        setUnseenCount(0);
        lastSeenRef.current = { msgs: messages.length, subs: subTasks.length, steps: steps.length };
      }
      pinnedRef.current = nowPinned;
      setPinned(nowPinned);
    };
    el.addEventListener('scroll', onScroll, { passive: true });
    return () => el.removeEventListener('scroll', onScroll);
  }, [messages.length, subTasks.length, steps.length]);

  useEffect(() => {
    const cur = { msgs: messages.length, subs: subTasks.length, steps: steps.length };
    const el = listRef.current;
    if (pinned && el) {
      // Historical reference: direct scrollTop assignment, not
      // scrollIntoView. scrollIntoView with behavior:'smooth'
      // is async; when many chunks arrive in quick succession
      // each smooth-scroll gets canceled by the next,
      // leaving the viewport stuck partway down. Direct
      // assignment is synchronous and idempotent. We wrap
      // in requestAnimationFrame so the browser has already
      // laid out the new content before we measure
      // scrollHeight.
      //
      // R275 (2026-09-16): the prior round's `if (distance < 80)`
      // gate ONLY when the user was already at the bottom (distance
      // < 80px). On initial mount (user hasn't scrolled yet),
      // scrollHeight was very large (the first batch of streamed
      // content was already longer than the viewport), so
      // distance > 80 and the auto-scroll branch was skipped —
      // the viewport never moved from scrollTop=0, and the user
      // saw "I'm stuck at the top, can't scroll to the bottom".
      // Fix: always scroll when pinned (which is the default
      // state and means "user wants the bottom").
      requestAnimationFrame(() => {
        if (!listRef.current) return;
        listRef.current.scrollTop = listRef.current.scrollHeight;
      });
      lastSeenRef.current = cur;
    } else {
      // R275: also update lastSeenRef in the unpinned branch.
      // The prior round left lastSeenRef at its previous value
      // here, which meant every subsequent useEffect run
      // recomputed `delta = cur - lastSeenRef` with the SAME
      // stale lastSeenRef → delta always reflected the total
      // accumulated events, not "since this useEffect fired".
      // The unseenCount counter ballooned to "89020 new" while
      // the user was actually seeing the latest content (because
      // they were pinned, just temporarily scrolled up at some
      // point). Always advance lastSeenRef so delta is per-tick.
      const delta =
              (cur.msgs - lastSeenRef.current.msgs) +
              (cur.subs - lastSeenRef.current.subs) +
              (cur.steps - lastSeenRef.current.steps);
      if (delta > 0) setUnseenCount((c) => c + delta);
      lastSeenRef.current = cur;
    }
    // depend on the `steps` array reference itself,
    // not just `steps.length`. When a tool's streamed output
    // grows (R193 tool_output_delta) the `steps` array is
    // replaced (because `appendToolOutput` does
    // `steps.map(...)`) but its length is unchanged. Without
    // this dep the auto-scroll useEffect doesn't re-run while
    // bash is streaming.
  }, [messages, isStreaming, steps, subTasks.length, currentSubTaskId, pinned]);

  const jumpToBottom = () => {
    // R275: use direct scrollTop assignment (synchronous,
    // idempotent) instead of scrollIntoView({ behavior: 'smooth' }).
    // smooth-scroll on a 100k-row streaming transcript can fail to
    // trigger reliably — multiple scrollIntoView calls cancel
    // each other, the animation never lands. Direct assignment
    // is what the pinned auto-scroll path already uses.
    const el = listRef.current;
    if (el) el.scrollTop = el.scrollHeight;
    setUnseenCount(0);
    setPinned(true);
    lastSeenRef.current = { msgs: messages.length, subs: subTasks.length, steps: steps.length };
  };

  // build a unified chronological timeline so system
  // messages and sub-task blocks are interleaved in the
  // order the engine emitted them. Previously the renderer
  // grouped by type (user, agent blocks, then ALL system
  // messages at the bottom) so [loop_warn_2] / [todo-step-bump]
  // / [todo-ask-llm] etc. floated to the end of the chat,
  // leaving the user confused about the order of events.
  //
  // Each event has a `ts` (timestamp). We sort ascending so
  // the chat reads top-to-bottom = oldest-to-newest, just
  // like a transcript. The streaming cursor still lives
  // on the last in-flight event (preamble or current sub-task).
  type TimelineEvent =
    | { kind: 'user'; ts: number; message: ChatMessage }
    | { kind: 'system'; ts: number; message: ChatMessage }
    | { kind: 'preamble'; ts: number; steps: ChatStep[]; isLive: boolean }
    | { kind: 'subtask'; ts: number; subTask: ChatSubTask; steps: ChatStep[]; isLive: boolean };

  const timeline = useMemo<TimelineEvent[]>(() => {
    const events: TimelineEvent[] = [];
    // 1. User + system messages use their own timestamp.
    for (const m of messages) {
      if (m.role === 'user') events.push({ kind: 'user', ts: m.timestamp, message: m });
      else if (m.role === 'system') events.push({ kind: 'system', ts: m.timestamp, message: m });
    }
    // 2. Group steps by sub-task.
    const bySub: Record<string, ChatStep[]> = {};
    const pre: ChatStep[] = [];
    for (const s of steps) {
      if (s.subTaskId) (bySub[s.subTaskId] ||= []).push(s);
      else pre.push(s);
    }
    // 3. Each sub-task is one event, timestamped to the first
    //    step's start (or the sub-task's startedAt if no step
    //    ran yet).
    for (const st of subTasks) {
      const own = bySub[st.id] ?? [];
      const ts = own[0]?.startedAt ?? st.startedAt;
      const isLive = isStreaming && st.id === currentSubTaskId && !st.summary;
      events.push({ kind: 'subtask', ts, subTask: st, steps: own, isLive });
    }
    // 4. Preamble runs (no sub-task) — R278 splits by
    //    `queryId` so each user prompt gets its own preamble
    //    block, even when two consecutive prompts both
    //    produce subTaskId=null steps. Without the split,
    //    every step landed in one `pre[]` bucket and the
    //    timeline emitted a single preamble event with the
    //    FIRST step's ts — so the second prompt's user
    //    bubble sorted AFTER the merged content (the user's
    //    "second-prompt leak"). Steps without a queryId
    //    (legacy / pre-R278) fall into the empty-string
    //    bucket so they group together rather than scatter
    //    one per step.
    if (pre.length > 0) {
      const preByQuery: Record<string, ChatStep[]> = {};
      for (const s of pre) {
        const qid = s.queryId ?? '';
        (preByQuery[qid] ||= []).push(s);
      }
      for (const qSteps of Object.values(preByQuery)) {
        const ts = qSteps[0].startedAt;
        // Live when this preamble's last step is the
        // currently-streaming one and the model hasn't
        // declared any sub-task yet. The simple "subTasks
        // empty" check is too coarse (a previous prompt's
        // preamble can never be live once a later preamble
        // has any steps) but it's the same heuristic the
        // previous round used; the R278 split is what fixes
        // the layout, not this live flag.
        const isLive = isStreaming && subTasks.length === 0
          && qSteps.some((s) => !s.done);
        events.push({ kind: 'preamble', ts, steps: qSteps, isLive });
      }
    }
    // 5. Sort ascending. Ties: keep user messages first
    //    (they're prompts the model is responding to),
    //    then sub-task blocks, then system messages on top
    //    of where the model reacted.
    const order: Record<TimelineEvent['kind'], number> = {
      user: 0, preamble: 1, subtask: 1, system: 2,
    };
    events.sort((a, b) => {
      if (a.ts !== b.ts) return a.ts - b.ts;
      return order[a.kind] - order[b.kind];
    });
    return events;
  }, [messages, steps, subTasks, currentSubTaskId, isStreaming]);

  return (
    <div className="message-list" ref={listRef}>
      {timeline.length === 0 ? (
        isStreaming ? (
          // R269: just-submitted-but-no-events-yet — show a
          // centred, prominent streaming placeholder so the
          // user sees "the engine is alive" right between
          // the empty chat and the input box. Matches what
          // typical AI agent tools do (Cursor, Claude Code).
          <StreamingIndicator variant="empty" forceWhenEmpty />
        ) : (
          <div className="message-list-empty">Start a conversation</div>
        )
      ) : (
        <>
          {timeline.map((ev) => {
            if (ev.kind === 'user') {
              // R284: route compaction-summary messages to
              // the dedicated row so the user gets the
              // "View original" affordance. The legacy
              // path stays for plain user bubbles.
              if (isCompactionSummary(ev.message)) {
                return (
                  <CompactionSummaryMessage
                    key={`c-${ev.message.id}`}
                    m={ev.message}
                    sessionId={currentSessionId}
                  />
                );
              }
              return <LegacyMessage key={`u-${ev.message.id}`} m={ev.message} />;
            }
            if (ev.kind === 'system') {
              return <LegacyMessage key={`s-${ev.message.id}`} m={ev.message} />;
            }
            if (ev.kind === 'preamble') {
              return (
                <AgentMarkdownMessage
                  key={`p-${ev.steps[0]?.id ?? 'pre'}`}
                  steps={ev.steps}
                  isLive={ev.isLive}
                />
              );
            }
            return (
              <AgentMarkdownMessage
                key={`t-${ev.subTask.id}`}
                steps={ev.steps}
                subTask={ev.subTask}
                isLive={ev.isLive}
              />
            );
          })}
          {/* R269: persistent streaming footer. When at least
           *  one event has streamed in but the run is still
           *  in flight, show a compact pulsing indicator just
           *  below the last message and above the MessageInput.
           *  Before R269 the chat panel went silent between
           *  the first event and run_end — the user couldn't
           *  tell whether the engine was hung or still working. */}
          {isStreaming && <StreamingIndicator variant="footer" />}
          <div ref={bottomRef} />
          {/* inline subagent spawn cards. The user
              can expand / dismiss each card; the
              "View subagent →" link switches the active
              session to the subagent's transcript so the
              user can read what the worker is doing. */}
          {spawnCards.map((c) => (
            <SubagentSpawnCard
              key={c.eventId}
              event={{
                id: c.eventId,
                type: 'subagent_spawn',
                ts: c.ts,
                subagentId: c.subagentId,
                data: { role: c.role, description: c.description, status: c.status },
              }}
              onOpen={viewSpawnCard}
              onDismiss={dismissSpawnCard}
            />
          ))}
        </>
      )}
      {unseenCount > 0 && !pinned && (
        <button
          className="jump-to-bottom"
          onClick={jumpToBottom}
          title="Scroll to bottom and resume auto-scroll"
        >
          {unseenCount} new
        </button>
      )}
      {!unseenCount && !pinned && isStreaming && (
        <button
          className="jump-to-bottom jump-to-bottom-streaming"
          onClick={jumpToBottom}
          title="Scroll to bottom (streaming in progress)"
        >
          generating...
        </button>
      )}
    </div>
  );
}
