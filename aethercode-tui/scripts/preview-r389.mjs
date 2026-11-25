// A VISUAL SAMPLE of the R389 chat column.
//
// Not a gate — a way to LOOK at what the new renderer produces, so
// "does it look like the desktop's centre column?" is a question with
// a picture attached instead of a guess.
//
//   node scripts/preview-r389.mjs
//
// It renders the REAL Scrollback with a REALISTIC transcript: a user
// prompt, two sub-tasks, thinks, tool calls, a file change, and the
// model's answer.

import { spawnSync } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(fileURLToPath(new URL("..", import.meta.url)));
const dir = mkdtempSync(join(ROOT, ".r389-preview-"));
const entry = join(dir, "preview.tsx");
const bundle = join(dir, "preview.mjs");

const source = `
import React from "react";
import { render } from "ink";
import { Scrollback } from ${JSON.stringify(join(ROOT, "src", "components", "Scrollback.tsx"))};

const now = Date.now();
const turns = [
  { id: 1, role: "user", text: "帮我把 chat 列改成和 desktop 一样", ts: now - 9000, collapsed: false, previewChars: 240 },
  { id: 2, role: "thinking", text: "**计划**\\n1. 先读 desktop 的 MessageList\\n2. 抽出纯函数\\n3. 用 Ink 渲染", ts: now - 8000, collapsed: true, previewChars: 80, subTaskId: "0:a" },
  { id: 3, role: "tool", text: "file_read", toolName: "file_read", toolArgs: JSON.stringify({ file_path: "aethercode-desktop/src/components/MessageList.tsx" }), toolStatus: "ok", ts: now - 7500, collapsed: true, previewChars: 80, subTaskId: "0:a" },
  { id: 4, role: "tool", text: "bash", toolName: "bash", toolArgs: JSON.stringify({ command: "npm test" }), toolStatus: "ok", ts: now - 7000, collapsed: true, previewChars: 80, subTaskId: "0:a" },
  { id: 5, role: "assistant", text: "读完了。desktop 中间那栏其实是 markdown 优先的：\\n\\n- 一个 \`##\` 标题代表一个子任务\\n- 思考和工具调用都是可折叠块\\n- 最后一条思考永远展开", ts: now - 6000, collapsed: false, previewChars: 240, subTaskId: "0:a" },
  { id: 6, role: "thinking", text: "结论：把纯函数抽出来，两端共用", ts: now - 5000, collapsed: true, previewChars: 80, subTaskId: "0:b" },
  { id: 7, role: "tool", text: "file_edit", toolName: "file_edit", toolArgs: JSON.stringify({ file_path: "src/chat/blocks.ts", old_string: "const a = 1;\\nconst b = 2;", new_string: "const a = 1;\\nconst b = 3;" }), toolStatus: "ok", ts: now - 4000, collapsed: true, previewChars: 80, subTaskId: "0:b" },
  { id: 8, role: "assistant", text: "已经改完了，测试全绿。", ts: now - 3000, collapsed: false, previewChars: 240, subTaskId: "0:b" },
];

const subTasks = [
  { id: "0:a", taskId: 0, subTaskId: "a", content: "读懂 desktop 中间栏的结构", status: "completed", summary: "desktop 是 markdown 优先的：标题 + 可折叠块 + 结果", startedAt: now - 8500 },
  { id: "0:b", taskId: 0, subTaskId: "b", content: "在 TUI 里实现同样的结构", status: "in_progress", startedAt: now - 5500 },
];

const { waitUntilExit } = render(
  <Scrollback
    turns={turns}
    subTasks={subTasks}
    height={40}
    showWelcome={false}
    welcomeCtx={{ model: "MiniMax-M3", cwd: "D:/work/AetherCode", sessionId: "default" }}
    collapseAll={false}
    onToggleTurn={() => {}}
    lastStopReason={null}
    lastStopKind="ok"
    lastError={null}
    showThinking={true}
    permissionAsk={null}
    decisionCardExpanded={true}
    terminalWidth={96}
    isLive={false}
  />,
  { stdout: process.stdout, patchConsole: false },
);
await waitUntilExit();
`;

writeFileSync(entry, source, "utf8");
const esbuildDir = join(ROOT, "node_modules", "esbuild");
const build = spawnSync(
  process.execPath,
  [
    "-e",
    `const {build}=require(${JSON.stringify(esbuildDir)});build({entryPoints:[${JSON.stringify(
      entry,
    )}],bundle:true,outfile:${JSON.stringify(bundle)},platform:'node',format:'esm',nodePaths:[${JSON.stringify(
      join(ROOT, "node_modules"),
    )}],banner:{js:"import { createRequire as __cr } from 'node:module';const require = __cr(import.meta.url);"},loader:{'.ts':'ts','.tsx':'tsx'}}).catch(e=>{console.error(e.message);process.exit(1)});`,
  ],
  { cwd: ROOT, encoding: "utf8" },
);
if (build.status !== 0 || !existsSync(bundle)) {
  console.error(build.stderr || build.stdout);
  process.exit(1);
}
const run = spawnSync(process.execPath, [bundle], { encoding: "utf8", timeout: 60_000 });
process.stdout.write(run.stdout ?? "");
if (run.stderr) process.stderr.write(run.stderr);
spawnSync("cmd", ["/c", "rmdir", "/s", "/q", dir]);
