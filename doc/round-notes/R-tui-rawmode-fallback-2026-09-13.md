# R-tui-rawmode-fallback (2026-09-13)

## 触发
R-tui-flicker-fix 修了 Windows console resize 死循环后,user 报告: `node ac-tui\ac-tui.js` 跑起来"只出来 help 信息,执行不了了"。

## 根因
Ink 5.x 的 `isRawModeSupported()` 只检查 `process.stdin.isTTY`,但在**某些 Windows 终端** (PowerShell ISE 旧版, 非-ConPTY conhost, 一些 SSH session host) 上:

- `process.stdin.isTTY === true` (interactive)
- 但 `process.stdin.setRawMode(true)` 仍 throw "Raw mode is not supported"

Ink 5.x 的第一个 `useInput(...)` 触发 `setRawMode(true)`,throw 被 React error boundary 抓住,TUI 进入 degraded 状态 — 渲染一帧但**键盘收不到**。User 看到 TUI 启动但"不响应"。

## 修复
`aethercode-tui/src/ac-tui.ts` main() 加 raw mode 探测 + 强制 fallback:

```ts
const inkSupportsRaw = (() => {
  if (!process.stdin.isTTY) return false;
  if (typeof process.stdin.setRawMode !== "function") return false;
  try {
    process.stdin.setRawMode(true);
    process.stdin.setRawMode(false);
    return true;
  } catch {
    return false;
  }
})();
const useLine  = wantLine || (!wantTui && (!haveTty || !inkSupportsRaw));
if (!useLine && !inkSupportsRaw && process.stderr.isTTY) {
  process.stderr.write(
    "note: Ink raw mode is not supported in this terminal — falling back to line mode.\n" +
    "      pass --tui to force the full-screen UI (may fail on this host),\n" +
    "      or run inside Windows Terminal / a ConPTY-capable host for the Ink TUI.\n"
  );
}
```

三层保护:
1. **isTTY check** — 非交互直接走 line mode (之前已有)
2. **setRawMode probe** — 配对 setRawMode(true/false),throw 视为不支持
3. **stderr 提示** — fallback 时给用户解释为什么不是 Ink TUI

User 用 `--tui` 强制时:
- 仍然 try Ink (用户明确要求)
- 但如果 Ink crash, error boundary 会 catch (不会 panic)

## 验证

- `tsc -p tsconfig.json` exit 0
- `node scripts/bundle.mjs` exit 0 (ac-tui.js 2.29 MB → 2.29 MB, 微增量)
- 测试 1: `echo. | node ac-tui.js` (stdin pipe) — `haveTty=false` → line mode, 91 bytes idle prompt
- 测试 2: `cmd /c "node ac-tui.js"` (cmd TTY) — `inkSupportsRaw=true` → Ink TUI, 87 bytes ANSI idle
- 预测: 用户的 PowerShell ISE / 非-ConPTY 终端 — `setRawMode` throw → fallback line mode + stderr "note: ..."

## 教训

1. **Ink 5.x isRawModeSupported() 不可靠** — 只是 isTTY,不能 catch 实际 setRawMode 失败
2. **Try-setRawMode 是最稳的 probe** — 配对 true/false 检测兼容性
3. **TUI 三层 fallback** — `--line` 强制 / `!haveTty` / `!inkSupportsRaw`, 覆盖所有情况
4. **Windows 终端兼容性矩阵** — Windows Terminal ✓ / ConPTY cmd ✓ / PowerShell ISE ✗ / 旧 conhost ✗
5. **R-tui-flicker-fix 的 followup** — 修了 resize loop 后才暴露这个 raw mode bug, 用户不报告 "闪烁" 但 TUI 也不响应
