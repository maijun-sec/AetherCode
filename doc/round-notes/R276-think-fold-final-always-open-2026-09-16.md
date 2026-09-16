# R276 — think fold policy: final think always open, history collapsed (2026-09-16)

## 触发

用户 17:14 在 IM 反馈：

> "推荐在任务执行时，最近的一次的 思考 始终展开，当结束后，后面如果有新的 思考 或者 tool 执行，就折叠，如果这是最后一次思考，就始终展开。"

含义：
1. 任务还在 streaming (`isLive=true`)：最近一次的 think 始终展开，让用户能看到新 thinking 流进来
2. 任务结束后：历史 think / tool 默认折叠，不要让 chat 满屏都是短 think
3. **例外**：chat 末尾的"最后一次思考"始终展开 — 这是 model 的结论性 reasoning，user 永远想看到

## 旧逻辑（坏的）

`BlockView` 里 think 分支：

```typescript
// R275 (broken)
if (block.kind === 'think') {
  const trimmed = block.md.trim();
  const isLong = trimmed.length > PREAMBLE_AUTO_COLLAPSE_CHARS; // 500
  if (!isLong) {
    return <details open ...>...</details>; // 短 think 永远展开
  }
  return <details ...>...</details>; // 长 think 永远折叠
}
```

两个坏后果：
- 长 task 跑下来 model 经常 emit 几十个短 think，每个都 `<details open>` → chat 满屏文字，用户没法 collapse
- 最终的 think 可能恰好很长 (>500)，结果被默认折叠 → "model 的结论藏起来了"

`AgentMarkdownMessage` 的 defaultOpen 也过度宽松：

```typescript
const defaultOpen = isLive ? isLast : true; // 历史 block 全部展开
```

历史 think 也全部展开 — chat 没法 collapse。

## Fix (R276)

`defaultOpenFor(block, opts)` helper — single source of truth：

```typescript
function defaultOpenFor(
  block: Block,
  opts: { isLast: boolean; isFinalThink: boolean; isLive: boolean },
): boolean {
  if (block.kind === 'header') return true;   // sub-task title anchor
  if (block.kind === 'result') return true;   // summary anchor
  if (block.kind === 'think') {
    if (opts.isFinalThink) return true;       // final think 永远展开
    if (opts.isLive && opts.isLast) return true; // streaming tail
    return false;                              // 其他都折叠
  }
  if (block.kind === 'tool') {
    if (opts.isLive && opts.isLast) return true; // streaming tail tool
    return false;
  }
  return true;
}
```

`AgentMarkdownMessage`：

```typescript
let finalThinkIdx = -1;
for (let i = blocks.length - 1; i >= 0; i--) {
  if (blocks[i].kind === 'think') { finalThinkIdx = i; break; }
}
const defaultOpen = defaultOpenFor(b, {
  isLast, isFinalThink: i === finalThinkIdx, isLive,
});
```

`BlockView` think 分支：

```typescript
if (block.kind === 'think') {
  const trimmed = block.md.trim();
  if (!trimmed) return null;
  const summary = trimmed.replace(/^#+\s*/gm, '')
    .replace(/[*_`>]/g, '').replace(/\s+/g, ' ')
    .slice(0, PREAMBLE_SUMMARY_CHARS) + '...';
  return (
    <details open={defaultOpen} className="agent-block agent-block-think">
      <summary className="agent-block-summary" title={summary}>
        <span className="agent-block-chevron">{'\u25be'}</span>
        <span className="agent-block-title">思考 · {summary}</span>
      </summary>
      ...
    </details>
  );
}
```

## 文件改动

`aethercode-desktop/src/components/MessageList.tsx`:
- 新增 `defaultOpenFor(block, opts)` helper — 单一策略源
- `AgentMarkdownMessage` 用 `finalThinkIdx` 算 final think index，然后 `defaultOpenFor(b, { isLast, isFinalThink, isLive })`
- `BlockView think branch` 删掉 `if (!isLong) { <details open> }` short-circuit + hardcoded `<details open>`，统一 `<details open={defaultOpen}>`
- 删 unused `PREAMBLE_AUTO_COLLAPSE_CHARS = 500` (size heuristic 不再用了)

`aethercode-desktop/src/components/thinkFoldR276.test.ts` (4 新 source-pin 测试):
- `AgentMarkdownMessage` 算 `finalThinkIdx` (blocks backward walk)
- `BlockView think branch` 用 `<details open={defaultOpen}>` 不再 size heuristic
- `defaultOpenFor` helper 存在，special-case header/result + think isFinalThink
- `AgentMarkdownMessage` 调用 `defaultOpenFor(b, { isLast, isFinalThink, isLive })` 替代 `isLive ? isLast : true`

## 验证

- vitest **1114/1114** pass (1110 → 1114, +4, 0 regression)
- `npx tsc --noEmit` clean (删 `PREAMBLE_AUTO_COLLAPSE_CHARS` 后)
- `npm run build` clean — JS bundle `index-DQNkC44a.js`
- `tauri build --no-bundle` clean — exe SHA
  `8681853BB604194C7064A231E19DF2FE1BB03D1CC99E094A2CFDB9E4EEB633E7`
  (5,179,904 bytes)
- jar SHA 沿用 R271/R275 `3A9F786A…` (R276 也是 desktop-only)

## 部署

- 用户需要重新启动 desktop.exe 才能看到折叠策略
- 旧 R275 zip → `release/aethercode-0.2.70.zip.prev.bak`
- 新 R276 zip 还没 repack（build 完没 deploy），用户重启 desktop 之前还需要 repack + commit

## 关于 second-prompt leak

用户还提到 "之前提过好几次" 的 second-prompt leak（第二个 prompt 的输出跑到第一个 prompt 里面）。

这个 bug 在 R272 (commit `e4be6c5` — sendMessage 重置 sub-task tracking) + R274b (commit `25944d1` — `run_end` 关 step + `run_start` defensive guard) 都修了。

R275 部署的 desktop PID 3432 跑的 exe (`372E44AF...`) 已经包含 R272 + R274b 修复。本 R276 commit `ce7e6fd` 是在 R275 基础上加折叠策略。

如果用户重启 desktop 后 second-prompt leak 还在，说明：
- (a) 用户实际跑的 desktop 还是 R275 之前的版本（要确认 exe SHA）
- (b) R274b 修复有 race / 没覆盖的 path（需要看具体现象 — 是 live session 还是 transcript replay）

让我看 transcript hydration 路径——desktop 重启后从 daemon 拉 transcript（不含 stream events），`steps=[]`，
但是 `messages` 里有 user + assistant role。`LegacyMessage` 只渲染 user + system，
assistant role 全部丢。这可能是用户看到的"输出跑了"的另一个原因：transcript 重放后 chat 失去所有 assistant 内容。

下一步可能需要 (R277):
- 让 `timeline` 把 `assistant role` messages 也 push 进去
- 让 `AgentMarkdownMessage` 在 `steps=[]` + 有 plain text content 时也渲染（fallback 单 block think = full text）

但需要先让用户验证 R276 折叠策略 + 确认 desktop 是不是已经 R275+ 才能确认 leak 还在。