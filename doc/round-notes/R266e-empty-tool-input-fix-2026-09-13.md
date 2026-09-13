# R266e-empty-tool-input-fix (2026-09-13)

## 触发
User 跑 0.2.66 desktop 跑一个 task 时, ToolCallCard 显示:
- `bash (missing command)` - tool call 失败, 错误 "command is required (string, e.g. 'ls' or 'pwd -L')."
- `glob (no input)` - tool call 失败, 但 **label 是错的** (应该 "(missing pattern)")

任务最后 ✓Done — 意味着 LLM 在收到错误后**自我纠正了**, 但这两条 tool call 失败是 noise.

## 根因

### 问题 1: `glob` 不在 `missingInputHint` 列表

`aethercode-desktop/src/components/MessageList.tsx` line 138-160 的 `missingInputHint(toolName)` 函数维护一个 tool name → 必填参数提示的映射:

```ts
if (n === 'bash' || n === 'shell' || n === 'exec' || n === 'run_command') {
  return '(missing command)';
}
// ... file_read → '(missing file_path)', web_search → '(missing query)', ...
if (n === 'grep' || n === 'search' || n === 'code_search') return '(missing pattern)';
return '(no input)';  // glob 落这里
```

`glob` tool 是 R198 后加进来的, 但 `missingInputHint` 没更新, 所以 glob 走 default `'(no input)'`. 用户看到 `glob (no input)` 不知道是 `pattern` 缺了.

### 问题 2: LLM 偶尔漏 required 参数 (model 端, 不可修)

`BashTool.java` (aethercode-tools) 错误信息很详细:
```
command is required (string, e.g. "ls" or "pwd -L").
accepted parameters:
  - command (string, required): the shell command to run
  - cwd (string, optional): working directory, default = engine cwd
  - timeout_ms (integer, optional): max runtime in ms, default 180000
  - stream (boolean, optional): stream stdout chunks live, default true
  - background (boolean, optional): run in background
```

`SystemPrompt` (`aethercode-prompts/.../SystemPrompt.java` line 358-393) 也很清楚地告诉 LLM:
- "The `input` object is MANDATORY and must contain every required parameter"
- "An `input: {}` is rejected with 'X is required'"
- "If you find yourself writing an empty `input`, stop and re-read the tool's required params"
- 有 worked example + anti-example

但 **MiniMax-M3 model 偶尔仍然漏 required 参数**. 这是 model 端 tool-calling 行为问题, code 端没法修. daemon 已经有 `ProgressLoopDetector` (aethercode-core) 在 3 个连续 empty-input batches 后 hard-stop, 防止无限 retry loop.

## 修复

1. **`MessageList.tsx` line 159 加 `n === 'glob'` 到 `'(missing pattern)'` 分支**
   - 让 glob tool call 失败时显示 "(missing pattern)" 而不是 "(no input)"
   - 用户立刻知道是哪个参数缺了

## 没修的 (model 端问题, 留给 0.2.67)

- **LLM 偶尔漏 required** — MiniMax-M3 行为问题. System prompt 已经尽力, daemon 端 ProgressLoopDetector 兜底.
- 未来选项: 在 Spring AI client 侧加 **server-side required validation** — tool call 进 daemon 时立即 check schema required, 缺就直接 reject + 返回详细 error 强制 retry. 这是 invader pattern (类似 OpenAI strict mode). R266e 范围不动.

## 验证

- `npm run tauri:build` ~5 min SUCCESS (incremental cargo)
- desktop exe 5,191,168 bytes (was 5,161,472, +30 KB for 1 TS line + Vite bundle 增量)
- 视觉验证: 重启 desktop, 跑任意 task, 触发 glob tool call 漏参数时, 显示 "(missing pattern)" 而不是 "(no input)"

## 教训

1. **per-tool hint 列表是手工维护的, 容易漏** — R198 加 glob tool 的时候没在 MessageList.missingInputHint 加 glob. 教训: 加新 tool 时同步更新所有 per-tool 列表 (error hints, missing input hints, fallbacks)
2. **Model 行为问题不能全指望 prompt** — MiniMax-M3 tool calling 不严格, system prompt 写得再清楚也偶尔漏. 客户端侧 schema validation (server-side strict mode) 是更稳的 fix, 留给下一个 round
3. **error message 已经够清楚** — BashTool 的 "command is required ... accepted parameters" 已经把 schema 写全了, LLM 应该能 re-emit 正确 call. 但 LLM 偶尔被自己的 first attempt 困住 (one-shot 反模式)
