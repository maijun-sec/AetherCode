import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * R177 → the prior round history. The original R177 tests
 * guarded the compact PreparingCard + folded-in stale tier
 * + filtered `[task]` side_note. the prior round replaced
 * the entire PreparingCard / SubTaskCard / StepCard /
 * ToolEventPill chain with a flat-markdown chat
 * (AgentMarkdownMessage) — the agent's output is one
 * markdown document rendered by react-markdown, with no
 * bordered cards. These tests are rewritten to guard the
 * later-followup-3 shape.
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

function read(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

describe('对应历史 round: [task] side_note is filtered (no system message)', () => {
  const storeSrc = read('src/store/index.ts');

  it('side_note handler drops the [task] kind (no system message added)', () => {
    const sideNoteBlock = storeSrc.match(
      /case 'side_note':\s*\{[\s\S]*?break;\s*\}/,
    );
    expect(sideNoteBlock).toBeTruthy();
    expect(sideNoteBlock![0]).toMatch(/kind\s*===\s*['"]task['"]/);
    const taskBranch = sideNoteBlock![0].match(
      /kind === ['"]task['"][\s\S]*?(?=\}\s*else|\}\s*break)/,
    );
    expect(taskBranch).toBeTruthy();
    expect(taskBranch![0]).not.toMatch(/role:\s*['"]system['"]/);
  });
});

describe('对应历史 round: chat is a flat markdown stream', () => {
  const mlSrc = read('src/components/MessageList.tsx');
  const cssSrc = read('src/components/MessageList.css');

  it('MessageList uses AgentMarkdownMessage for agent output (no PreparingCard render)', () => {
    // the prior round: the chat renders the agent's output
    // as one markdown document per sub-task + a preamble
    // run. The legacy PreparingCard is no longer rendered
    // in the main flow (the export is kept for diff
    // readability but always returns null).
    expect(mlSrc).toMatch(/function\s+AgentMarkdownMessage/);
    // The main render must reference AgentMarkdownMessage.
    expect(mlSrc).toMatch(/<AgentMarkdownMessage/);
    // And must NOT render <PreparingCard> in the main
    // render path.
    expect(mlSrc).not.toMatch(/<PreparingCard\s+/);
  });

  it('each sub-task becomes a single markdown document (## heading + ### 步骤 body + ### 结果 footer)', () => {
    // the user said "think has no top-level heading, so folding
    // is unlikely to work. I recommend adding a summary of what needs to be done before,
    // then the execution of thinking, tool calls, etc., and finally a summary".
    // We answer that with a 3-section structure: `## content`
    // (the sub-task name), `### Steps` (wraps think + tool
    // calls, collapsible), `### Results` (wraps the summary).
    expect(mlSrc).toMatch(/stepsToMarkdown\(/);
    expect(mlSrc).toMatch(/## \$\{subTask\.content\}/);
    // explicit sub-headings so markdown viewers (and
    // the user when they paste the doc out) can collapse
    // just the execution body.
    expect(mlSrc).toMatch(/### 步骤/);
    expect(mlSrc).toMatch(/### 结果/);
  });

  it('tool events become inline code blocks (no bordered pill UI)', () => {
    // toolEventToMarkdown turns each tool event into a
    // fenced code block. The user said "no more label / div
    // / pill" — the new shape is a fenced ```bash block
    // with the command + a fenced block with the output.
    expect(mlSrc).toMatch(/function\s+toolEventToMarkdown/);
    expect(mlSrc).toMatch(/```bash/);
    expect(mlSrc).toMatch(/\$\s\$\{summary\}/);
  });

  it('sub-task summary becomes a blockquote under ### 结果 at the bottom', () => {
    // legacy had a <div className="subtask-summary"> footer.
    // R194 puts the blockquote under `### Results` so the
    // summary has its own collapsible section.
    expect(mlSrc).toMatch(/>\s*\*\*\$\{subTask\.status\}\*\*:/);
  });

  it('streaming cursor is a literal ▍ at the end of the live document', () => {
    // the prior round: the legacy "step-live-dot" CSS
    // animation is gone. The streaming cursor is a literal
    // `▍` character at the end of the markdown document.
    // The source file uses the JS escape sequence
    // '\u25cd' (the editor can't reliably save the raw
    // char in Windows PowerShell I/O). We match the
    // `md += ' \u25cd';` line as a literal substring.
    expect(mlSrc).toContain("md += ' \\u25cd';");
  });

  it('PreparingCard / SubTaskCard / StepCard / ToolEventPill are no-ops (legacy kept for diff only)', () => {
    // The prior round / prior round / R98 components are
    // still defined (so the diff against the old code is
    // readable), but they all return null. Some are now
    // `export const X = () => null;` and some are stub
    // `export function X({}) { void ...; return null; }` —
    // we just check that the return value is null and the
    // main render doesn't reference them.
    expect(mlSrc).toMatch(/return null/);
    expect(mlSrc).not.toMatch(/<PreparingCard\s+/);
    expect(mlSrc).not.toMatch(/<SubTaskCard\s+/);
    expect(mlSrc).not.toMatch(/<StepCard\s+/);
    expect(mlSrc).not.toMatch(/<ToolEventPill\s+/);
  });

  it('preparing-card CSS is gone (no bordered card chrome)', () => {
    // prior round + prior round: the .preparing-card
    // rules were either removed or made non-clipping; the
    // new chat has no .preparing-card class anywhere.
    // We pin that the *body* of the CSS file does not
    // define a heavy .preparing-card with overflow:
    // hidden (the prior round fix).
    const card = cssSrc.match(/\.preparing-card\s*\{[\s\S]*?\n\}/);
    if (card) {
      // If the rule still exists, it must not clip.
      const body = card[0].replace(/\/\*[\s\S]*?\*\//g, '');
      expect(body).not.toMatch(/overflow:\s*hidden/);
    }
  });
});

describe('对应历史 round: top-level StaleWarning is removed from App.tsx', () => {
  const appSrc = read('src/App.tsx');

  it('does NOT import StaleWarning', () => {
    expect(appSrc).not.toMatch(/import\s*\{[^}]*\bStaleWarning\b[^}]*\}\s*from/);
  });

  it('does NOT render <StaleWarning /> in the JSX', () => {
    expect(appSrc).not.toMatch(/<StaleWarning\s*\/?>/);
  });
});

describe('R194: pending tool permissions surface in a banner above the chat', () => {
  const appSrc = read('src/App.tsx');

  it('App.tsx imports PermissionPromptBanner', () => {
    // the user reported "tool calls get stuck — is the system
    // waiting for user confirmation? but the frontend never shows the confirm button".
    // Root cause: PermissionList lived inside the right
    // (Telemetry) panel, which is closed by default. We
    // added a new banner that's always visible when
    // pendingPermissions > 0, and pinned the import here.
    expect(appSrc).toMatch(
      /import\s*\{\s*PermissionPromptBanner\s*\}\s*from\s*['"]\.\/components\/PermissionPromptBanner['"]/,
    );
  });

  it('App.tsx renders <PermissionPromptBanner /> in the JSX', () => {
    expect(appSrc).toMatch(/<PermissionPromptBanner\s*\/?>/);
  });
});

describe('R195: in-app folding + file_read suppression + banner-to-bottom', () => {
  const mlSrc = read('src/components/MessageList.tsx');
  const appSrc = read('src/App.tsx');

  it('MessageList uses <details>/<summary> for collapsible sections (R195 folding)', () => {
    // The user said "the folding I mentioned earlier isn't there yet" — the
    // previous turn added `### Steps` / `### Results` markdown
    // headings for paste-out folding, but the user wants
    // in-app folding. R195 wraps each H2/H3 section in a
    // <details> element so clicking the summary folds the
    // body in the TUI itself.
    expect(mlSrc).toMatch(/parseAgentSections\s*\(/);
    expect(mlSrc).toMatch(/AgentSectionView/);
    expect(mlSrc).toMatch(/<details[^>]*open[^>]*>/);
  });

  it('file_read does NOT show file content in the chat (R195: long files overflow)', () => {
    // The user said "file_read content was also dumped to the console,
    // recommend not displaying the content inline". R195: toolEventToMarkdown
    // for file_read returns a one-liner only — the file
    // body is suppressed entirely. We pin that the
    // file_read case in the source does NOT include a
    // fenced ``` block (no ``` followed by trimmed).
    const fileReadBlock = mlSrc.match(
      /case\s+'file_read':[\s\S]*?case\s+'file_write'/,
    );
    expect(fileReadBlock).toBeTruthy();
    // No fenced code block (```\n${trimmed}\n```) in the
    // file_read return statement. The old shape had
    // `+ (trimmed ? \`\\\`\\\`\\\`\n${trimmed}\n\\\`\\\`\\\`\` : '')`.
    expect(fileReadBlock![0]).not.toMatch(/trimmed\s*\?/);
  });

  it('PermissionPromptBanner sits ABOVE MessageInput (R196: between chat and input)', () => {
    // R195 put the banner BELOW MessageInput (at the
    // very bottom of the page), but the user said "the
    // confirm prompt should go below the middle display, not at the very bottom of the page" —
    // they want the banner above the input, so the input
    // stays the bottommost element. R196 moved it: chat
    // list → banner → input. We pin that the JSX renders
    // PermissionPromptBanner BEFORE MessageInput.
    const mlIdx = appSrc.indexOf('<MessageInput');
    const pbIdx = appSrc.indexOf('<PermissionPromptBanner');
    expect(mlIdx).toBeGreaterThan(-1);
    expect(pbIdx).toBeGreaterThan(-1);
    expect(pbIdx).toBeLessThan(mlIdx);
  });

  it('MessageList uses a chronological timeline (R196: events interleaved by time)', () => {
    // previously the renderer grouped events by type
    // (user, agent blocks, then ALL system messages at
    // the bottom). The user said "tool executions and
    // tool outputs are still at the very bottom" — they wanted the system messages
    // and tool events to appear in time order, not
    // collected at the end. R196 builds a unified timeline
    // and renders events in `ts` ascending order.
    expect(mlSrc).toMatch(/timeline/);
    expect(mlSrc).toMatch(/TimelineEvent/);
    // The sort must use ascending `ts`.
    expect(mlSrc).toMatch(/a\.ts\s*-\s*b\.ts/);
  });
});

describe('对应历史 round: setCwd on a different path creates a new session on a freshly-swapped daemon', () => {
  const storeSrc = read('src/store/index.ts');
  const methodsSrc = read('src/lib/methods.ts');
  const libRsSrc = read('src-tauri/src/lib.rs');

  it('store.setCwd delegates to the Rust `set_cwd` Tauri command (R199: swap dance lives in Rust)', () => {
    // R197 made cwd switch = new session. R199 escalated
    // this further: the new session must be minted on a
    // daemon rooted at the new cwd, not the current one
    // (the multi-daemon Layer-2 bug). The renderer now
    // hands the whole job to Rust via the `set_cwd`
    // command, which does pre_warm + swap + createSession.
    // We pin the renderer-side call site to make sure a
    // refactor doesn't fall back to calling
    // `rpc.createSession({ cwd })` on the WS — that would
    // bring back the Layer-2 bug.
    expect(storeSrc).toMatch(/setCwd:[\s\S]*?rpc\.setCwd\(\s*path/);
  });

  it('store.setCwd is a no-op when the new path equals the current cwd', () => {
    // The user might click the same folder in the dialog
    // (or the dialog returns the same path it was opened
    // with). R197 short-circuits the rpc call to avoid
    // churning a session id for a click that didn't change
    // anything.
    expect(storeSrc).toMatch(/setCwd:[\s\S]*?curCwd[\s\S]*?newCwd[\s\S]*?return/);
  });

  it('lib/methods.createSession accepts an optional cwd parameter', () => {
    // the JS-side createSession was previously
    // no-args; the prior round added the `cwd` param to the daemon's
    // createSession RPC, but the JS wrapper ignored it. We
    // wire it up so store.setCwd can pass cwd.
    expect(methodsSrc).toMatch(/createSession\(opts\?:\s*\{\s*cwd\?:\s*string/);
  });

  it('lib/methods.setCwd returns { cwd, sessionId, swapped } (R199: the renderer switches on this)', () => {
    // the Rust `set_cwd` command returns a JSON
    // object with the resolved cwd, the new sessionId
    // (or null when no daemon is up yet), and a `swapped`
    // flag so the renderer can decide whether to refresh
    // sessions / tools / providers. We pin the TS wrapper
    // so a refactor that drops one of these fields breaks
    // the test before runtime.
    expect(methodsSrc).toMatch(/setCwd\(path:\s*string\):\s*Promise<\{\s*cwd:\s*string;\s*sessionId:\s*string\s*\|\s*null;\s*swapped:\s*boolean\s*\}>/);
  });

  it('lib.rs set_cwd delegates to set_cwd_daemon (R199: swap dance is the only path)', () => {
    // the Tauri `set_cwd` command is now a thin
    // wrapper around `set_cwd_daemon`, which does the
    // pre_warm → swap → createSession dance. We pin this
    // wiring so a regression that calls
    // `rpc_call("createSession", ...)` directly (the
    // Layer-2 bug) breaks the test before runtime.
    const setCwdBlock = libRsSrc.match(
      /#\[tauri::command\][\s\S]*?async fn set_cwd\([\s\S]*?\n\}/,
    );
    expect(setCwdBlock).toBeTruthy();
    expect(setCwdBlock![0]).toMatch(/set_cwd_daemon\(path,\s*app,\s*state\)/);
  });

  it('lib.rs set_cwd_daemon calls pre_warm_daemon, swap_to_pre_warm, and createSession in that order (R199: the dance)', () => {
    // switching cwd must switch the daemon. The
    // three calls must be in this exact order — pre-warm
    // the new daemon, swap it to primary, then mint a
    // session on it. If a future refactor re-orders these
    // (e.g. createSession before swap), the session would
    // land on the old daemon and we'd be back to Layer 2.
    const daemonBlock = libRsSrc.match(
      /async fn set_cwd_daemon\([\s\S]*?\n\}/,
    );
    expect(daemonBlock).toBeTruthy();
    const body = daemonBlock![0];
    const pwIdx = body.search(/pre_warm_daemon\(/);
    const swapIdx = body.search(/swap_to_pre_warm\(/);
    const csIdx = body.search(/rpc_call\(\s*"createSession"/);
    expect(pwIdx).toBeGreaterThan(-1);
    expect(swapIdx).toBeGreaterThan(-1);
    expect(csIdx).toBeGreaterThan(-1);
    expect(pwIdx).toBeLessThan(swapIdx);
    expect(swapIdx).toBeLessThan(csIdx);
  });
});

describe('R198: LeftPanel groups sessions by project (cwd) + new-session button', () => {
  const mlSrc = read('src/components/MessageList.tsx');
  const leftPanelSrc = read('src/components/LeftPanel.tsx');
  const methodsSrc = read('src/lib/methods.ts');
  void mlSrc; // keep the var so the test runner doesn't whine

  it('SessionInfo carries an optional cwd field (R198: needed for project grouping)', () => {
    // The daemon's listSessions RPC now returns cwd for
    // each session (looked up in the memory store). We pin
    // the TS type so a refactor that drops cwd breaks the
    // test before runtime.
    expect(methodsSrc).toMatch(/interface SessionInfo[\s\S]*?cwd\?:\s*string/);
  });

  it('LeftPanel uses ProjectGroupList to render sessions grouped by cwd', () => {
    // The user asked: "left side should show all sessions, but
    // sessions should hang under the project (cwd)" and "left
    // side should fold/unfold all sessions by project". R198
    // introduces ProjectGroupList, a tree view that groups
    // sessions by cwd and renders each group as a
    // collapsible <details> element.
    expect(leftPanelSrc).toMatch(/import\s*\{\s*ProjectGroupList/);
    // the top-level "new session" button was
    // removed — the user said the layer between
    // project and session was unnecessary. The
    // per-project "＋" in ProjectGroup is the only
    // entry point. Pin the absence of `left-new`
    // so a future refactor doesn't re-add the
    // redundant layer.
    expect(leftPanelSrc).not.toMatch(/left-new/);
    expect(leftPanelSrc).toMatch(/useNewSessionInCwd/);
  });
});

describe('R199 + R331: ProjectGroup "＋" on the current cwd skips the daemon-swap dance', () => {
  // when the user clicks "+" on the project group for
  // the cwd they're already on, we must NOT go through the
  // full pre_warm + swap + createSession dance. That dance
  // costs ~1-2s (JVM startup) and kills the current daemon,
  // which would discard the user's in-flight draft, mid-task
  // state, etc. For "I want a fresh session on the project
  // I'm already on", we just call createNewSession() — which
  // R331 routes through the mode picker.
  //
  // R331 changed the shape: createNewSession({}) at the end
  // is the entry to the picker, not a direct session mint.
  // The pin checks "sameCwd does NOT trigger setCwd" + "the
  // different-cwd branch still does" + "the picker fires
  // via createNewSession({}) at the tail".
  const projectGroupSrc = read('src/components/ProjectGroup.tsx');

  it('useNewSessionInCwd short-circuits when curCwd === cwd (no setCwd)', () => {
    // The !sameCwd branch is the only place setCwd fires.
    // Same-cwd clicks go straight to the picker.
    expect(projectGroupSrc).toMatch(/!sameCwd/);
    expect(projectGroupSrc).not.toMatch(/if \(sameCwd\)\s*\{/);
    expect(projectGroupSrc).toMatch(/setCwd\(cwd\)/);
    expect(projectGroupSrc).toMatch(/await createNewSession\(\)/);
  });
});

describe('R200: 7 fixes from one user feedback round', () => {
  // The user pasted a single batch of feedback covering
  // seven issues. Each test pins one fix so a regression
  // that brings it back breaks the test, not the user.
  const libRsSrc = read('src-tauri/src/lib.rs');
  // The test root is aethercode-desktop (see `root` above),
  // so the Java sources under the sibling aethercode/
  // module are one level up.
  const javaSrc = read('../aethercode/aethercode-protocol/src/main/java/org/aethercode/protocol/methods/AetherCodeMethods.java');
  const methodsSrc = read('src/lib/methods.ts');
  const queriesSrc = read('src/rpc/queries.ts');
  const mlSrc = read('src/components/MessageList.tsx');
  const bannerSrc = read('src/components/PermissionPromptBanner.tsx');
  const miCss = read('src/components/MessageInput.css');
  const tsCss = read('src/components/TaskSummary.css');
  const storeSrc = read('src/store/index.ts');
  void libRsSrc; // not strictly needed for these tests, but the
                 // invariant lives in Java + Tauri (see R197 block).

  it('#4: TS omits the `target` field when none is provided (no more String.valueOf(null) = "null")', () => {
    // The Java side reads `p.get("target")` and treats null
    // as "match all invocations of this tool". The legacy
    // JS wrapper sent `target: null` (via `opts?.target ?? null`)
    // which JSON-serialised to `"target": null`, and
    // `String.valueOf(null)` yielded the literal "null" —
    // which the rule-matcher regex-quoted to `\Qnull\E` and
    // never matched. The fix: omit the field instead.
    expect(methodsSrc).toMatch(
      /if \(opts\?\.target !== undefined\) \{[\s\S]*?params\.target = opts\.target/,
    );
  });

  it('#4: Java permissionPolicyOverride treats null + "null" as "no target" (defence in depth)', () => {
    // Even if a legacy JS caller still sends `"target": null`,
    // the Java side now collapses it to `""` so the rule's
    // matcher is null (match all invocations of this tool).
    expect(javaSrc).toMatch(
      /targetRaw == null \|\| targetRaw instanceof String s && s\.isBlank/,
    );
    expect(javaSrc).toMatch(/"null"\.equals\(target\)/);
  });

  it('#5: useSessionList calls listSessions (not the unwired "session/list")', () => {
    // The legacy query was named "session/list" (matching
    // a Java class name) but the daemon dispatcher only
    // registers "listSessions". So the WS call returned
    // METHOD_NOT_FOUND, query.data was undefined, and the
    // LeftPanel rendered "0 sessions" even while a task was
    // running. The fix: use the actual wire name.
    expect(queriesSrc).toMatch(/client\.call<SessionListResult>\(\s*['"]listSessions['"]/);
  });

  it('#1/2: toolEventToMarkdown shows tool-aware hint when input is missing', () => {
    // "bash" with no `command` used to render as `> bash`
    // and `(no input)`, leaving the user unable to tell
    // whether the model forgot the command or the call was
    // a no-op. R200 adds a per-tool fallback: bash →
    // "(missing command)", file_* → "(missing file_path)".
    expect(mlSrc).toMatch(/missingInputHint\(ev\.name\)/);
    expect(mlSrc).toMatch(/\(missing command\)/);
    expect(mlSrc).toMatch(/\(missing file_path\)/);
  });

  it('#2: tool event output gets a tighter cap on errors (200 chars vs 4000)', () => {
    // A bash tool that rejects an empty command returns a
    // ~1KB schema dump. The legacy truncateOutput cap
    // was 4000 chars (same as success), so a single bad
    // call filled the chat with the full schema. R200
    // tightens the cap to 200 chars for isError events.
    expect(mlSrc).toMatch(/TOOL_ERROR_PREVIEW = 200/);
    expect(mlSrc).toMatch(/ev\.isError \? TOOL_ERROR_PREVIEW : TOOL_OUTPUT_PREVIEW/);
  });

  it('#1: PermissionPromptBanner surfaces a "missing <param>" hint when input is unusable', () => {
    // The banner used to render the tool name + reason but
    // nothing for the input, because the only preview path
    // returned "" on a missing command. R200 adds an
    // explicit fallback: "missing `command` parameter",
    // "missing `file_path` parameter", etc.
    expect(bannerSrc).toMatch(/missingInputHint\(p\.tool\)/);
    expect(bannerSrc).toMatch(/'missing `command` parameter'/);
  });

  it('#3: loop-warn system messages are truncated to ~100 chars (the verbose engine report is 9 KB)', () => {
    // The engine's loop-warn message can be ~9 KB. legacy
    // the renderer pushed the full text into the chat
    // transcript, drowning the actual conversation. R200
    // keeps the full message in `loopWarn.message` (so
    // the banner is intact) but trims the chat-line copy.
    expect(storeSrc).toMatch(
      /loop-warn-1' \|\| kind === 'loop-warn-2'[\s\S]*?shortMsg\s*=/,
    );
    expect(storeSrc).toMatch(/msg\.length > 100 \? msg\.slice\(0, 97\) \+ '\.\.\.'/);
  });

  it('#7: input-config-bar stays on one row (no wrap, with horizontal scroll fallback)', () => {
    // The user said the cwd chip dropped to a second line
    // at common window widths. The previous CSS had
    // `flex-wrap: wrap` + `cwd-group { max-width: 360px }`,
    // which on a 1280px window pushed cwd to row 2 because
    // the input box itself is wide. R200'sets the strip to
    // `flex-wrap: nowrap` + `overflow-x: auto` so the row
    // stays one line (with horizontal scroll on tiny
    // windows) and shrinks cwd's max-width to 220px.
    expect(miCss).toMatch(/\.input-config-bar\s*\{[\s\S]*?flex-wrap:\s*nowrap/);
    expect(miCss).toMatch(/\.cwd-group\s*\{[\s\S]*?max-width:\s*220px/);
  });

  it('#6: TaskSummary uses a single-row chip layout (CWD + id + model + perm + daemon)', () => {
    // previously the left rail had a vertical list of 7
    // label→value rows under two section headers, eating
    // 168px of vertical space and truncating the session
    // id. R200 collapses everything into one flex row of
    // pills (CWD ellipsises from the right; everything
    // else content-sized) plus an optional second row
    // for name + uptime.
    expect(tsCss).toMatch(/\.task-summary-row\s*\{[\s\S]*?display:\s*flex/);
    expect(tsCss).toMatch(/\.task-pill-cwd\s*\{[\s\S]*?flex:\s*1 1 140px/);
  });
});

describe('R201: 5 fixes from one user feedback round (second batch)', () => {
  // Same shape as R200: the user pasted a screenshot + 5
  // observations; each test pins one fix.
  const libRsSrc = read('src-tauri/src/lib.rs');
  const javaSrc = read('../aethercode/aethercode-protocol/src/main/java/org/aethercode/protocol/methods/AetherCodeMethods.java');
  const mlSrc = read('src/components/MessageList.tsx');
  const drawerSrc = read('src/components/session/SessionDetailsDrawer.tsx');
  const appSrc = read('src/App.tsx');
  void libRsSrc; // referenced below via substring

  it('#1: set_cwd_daemon flips a swap guard so the old daemon\'s WS close is not re-broadcast', () => {
    // The user reported "when switching cwd, the daemon reconnects
    // daemon". The chain: swap_to_pre_warm kills the OLD
    // primary → its WS task emits daemon.disconnected →
    // renderer invokes Tauri `disconnect` (which clears
    // ws_tx + daemon state) → scheduleReconnect calls
    // ensure_daemon → the brand-new primary's ws_tx is
    // wiped. The fix: set_cwd_daemon flips an
    // Arc<AtomicBool> (`swapping`) for the duration of
    // the swap; the WS task consults it before emitting
    // daemon.disconnected and swallows the close.
    expect(libRsSrc).toMatch(/swapping:\s*Arc<AtomicBool>/);
    expect(libRsSrc).toMatch(/ScopingGuard\s*\{/);
    expect(libRsSrc).toMatch(/notify_disconnect/);
    expect(libRsSrc).toMatch(/swapping_task\.load\(Ordering::Acquire\)/);
  });

  it('#4: createSession writes the (sessionId → cwd) binding to the memory store', () => {
    // R198 added the listSessions-side cwd lookup, but
    // never closed the loop: createSession RPC created
    // the engine session + set the engine's cwd, but did
    // NOT call memoryStore.sessionStore().upsertSession.
    // Result: listSessions returned 8 sessions with no
    // cwd field, and the LeftPanel grouped all of them
    // under "unlinked project" (or, in the user's view, "0
    // sessions" because the count widget evaluated
    // visibleCount === totalCount === 0 with a stale
    // query cache). The fix: createSession writes the
    // row after the engine.setCwd call.
    expect(javaSrc).toMatch(
      /createSession[\s\S]*?memoryStore\.sessionStore\(\)\.upsertSession\(\s*newId,\s*resolvedCwd,/,
    );
  });

  it('#5: SessionDetailsDrawer uses the parent\'s open flag (default closed)', () => {
    // The user asked "what is the session detail at the bottom?".
    // The drawer used `const open = sessionId !== null`,
    // which is essentially always true once a session
    // is active, so the Continue / Pause / Stop bar was
    // permanently visible. The fix: accept an explicit
    // `open` prop and fall back to the legacy "open when
    // session is set" only for callers that don't pass
    // the prop (e.g. SessionPage mounts the drawer
    // directly without a parent flag).
    expect(drawerSrc).toMatch(/open\?:\s*boolean/);
    expect(drawerSrc).toMatch(/const open = openProp \?\? \(sessionId !== null\)/);
    expect(appSrc).toMatch(/<SessionDetailsDrawer[\s\S]*?open=\{p\.detailsDrawerOpen\}/);
  });

  it('#3: think blocks render in a foldable <details> with the 80-char summary', () => {
    // R201 (2026-09-15) wrapped any preamble over 500 chars in a
    // default-closed <details>; R202 renamed the variable and
    // moved to per-block rendering. R276 (2026-09-16) replaced
    // the size heuristic with a per-block fold policy (defaultOpenFor)
    // but kept the same affordance: a think block is a <details>
    // that defaults to folded, with the first 80 chars as the
    // summary so the user can see what each think was about
    // without opening every one. The PREAMBLE_AUTO_COLLAPSE_CHARS
    // constant is gone (no more size-based heuristic); what stays
    // is PREAMBLE_SUMMARY_CHARS (still 80) and the `agent-block-think`
    // class.
    expect(mlSrc).not.toMatch(/PREAMBLE_AUTO_COLLAPSE_CHARS/);
    expect(mlSrc).toMatch(/PREAMBLE_SUMMARY_CHARS\s*=\s*80/);
    expect(mlSrc).toMatch(/agent-block-think/);
    // R276 added the defaultOpenFor helper as the single source of
    // truth for which blocks default-open vs default-folded.
    expect(mlSrc).toMatch(/function\s+defaultOpenFor\s*\(/);
  });
});

describe('R202: independent blocks + per-session cwd', () => {
  // The user pasted two more observations:
  //   1. "tool calls are still at the very bottom" — the chat renderer
  //      collapsed think + tool events into a single
  //      markdown document, and react-markdown put
  //      tool events at the document's tail. The fix:
  //      emit one <details> per (think | tool |
  //      result) and interleave them in time order.
  //   2. "0 sessions" — even later + R201 the
  //      LeftPanel still showed 0 sessions. Two root
  //      causes: the renderer read from useSessionList
  //      (TanStack Query) which had stale 30s data AND
  //      a cached "session/list not found" result from
  //      prior; and the daemon's listSessions
  //      queried the user-level memory store (sessions.db)
  //      for cwd, which had no rows for any legacy
  //      session — even the sidecar `<id>.cwd` files
  //      weren't consulted.
  const javaSrc = read('../aethercode/aethercode-protocol/src/main/java/org/aethercode/protocol/methods/AetherCodeMethods.java');
  const mlSrc2 = read('src/components/MessageList.tsx');
  const lpSrc = read('src/components/LeftPanel.tsx');
  const mlCss = read('src/components/MessageList.css');

  it('#1: listSessions reads the per-session .cwd sidecar as the PRIMARY source', () => {
    // R198 read from the user-level SQLite memory
    // store; R201 added the write side; R202 realises
    // that EVERY legacy session (and any session
    // created by a daemon that doesn't speak the
    // R198 protocol) has no memory-store row, so the
    // only reliable cwd source is the per-session
    // sidecar file `<sessions-dir>/<id>.cwd` written
    // by AetherCodeEngine.createSession / setCwd.
    // We pin: sidecar is checked first, memory store
    // is the fallback.
    expect(javaSrc).toMatch(/info\.id\(\)\s*\+\s*"\.cwd"/);
    expect(javaSrc).toMatch(/Files\.exists\(sidecar\)/);
    expect(javaSrc).toMatch(/Files\.readString\(sidecar\)/);
  });

  it('#1: LeftPanel reads sessions from the store, not from useSessionList (R202 single-source-of-truth)', () => {
    // Previously the LeftPanel used useSessionList
    // (TanStack Query) and the store had its own
    // sessions array. Two data sources for the same
    // UI element caused "0 sessions" to persist for
    // up to 30s after a cwd switch (TanStack staleTime)
    // AND a cached `data: { sessions: [] }` from the
    // the prior round `session/list METHOD_NOT_FOUND` error.
    // The store's sessions[] is refreshed by
    // `refreshSessions()` which is called from setCwd,
    // createNewSession, and loadSession.
    expect(lpSrc).toMatch(/const all = useStore\(\(s\) => s\.sessions\)/);
    expect(lpSrc).not.toMatch(/const all = query\.data\?\.sessions \?\? \[\]/);
  });

  it('#1: AgentMarkdownMessage splits output into per-block details (interleaved think / tool / result)', () => {
    // Previously: AgentMarkdownMessage called
    // stepsToMarkdown() once for the whole step array,
    // producing a single markdown document. Tool events
    // landed at the document's tail because the
    // generator walked each step's text, then each
    // step's toolEvents.
    // buildBlocks() emits one block per
    // (think text, tool event) in the model's order,
    // and BlockView renders each as its own <details>.
    expect(mlSrc2).toMatch(/function\s+buildBlocks/);
    expect(mlSrc2).toMatch(/function\s+BlockView/);
    expect(mlSrc2).toMatch(/type\s+Block\s*=[\s\S]*?kind:\s*'header'[\s\S]*?kind:\s*'think'[\s\S]*?kind:\s*'tool'/);
  });

  it('#1: tool blocks have their own CSS class (visible accent border) so they don\'t blend into the think prose', () => {
    // a tool block sits between two think
    // blocks in the stream. Without a visual accent it
    // would blend into the prose and the user would
    // miss the tool call entirely. The CSS pins an
    // accent border-left + tinted background per kind.
    expect(mlCss).toMatch(/\.agent-block-tool\s*\{/);
    expect(mlCss).toMatch(/border-left:\s*2px solid var\(--chat-accent\)/);
    expect(mlCss).toMatch(/\.agent-block-think\s*\{/);
    expect(mlCss).toMatch(/\.agent-block-result\s*\{/);
  });
});