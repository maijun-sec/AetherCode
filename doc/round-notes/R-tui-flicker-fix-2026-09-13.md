# R-tui-flicker-fix (2026-09-13)

## 触发
User 双击 `D:\test\aethercode-0.2.66\desktop\aethercode-desktop.exe` 看到 "localhost 拒绝连接" (R-desk-build 系列 round 修好),然后跑 `node ac-tui/ac-tui.js` 测 TUI,报告 TUI "一直在闪,闪瞎眼那种,任务执行完了还在闪,任务还没开始执行只是刚打开也在闪"。

## 根因
`aethercode-tui/src/tui.tsx` line 746-754 的 `process.stdout.on("resize", onResize)` 监听器在 Windows + Ink 5.x 下形成**正反馈死循环**:

```
Ink 写 ANSI 重画 frame
  → Windows console 把每次 stdout write 当作 potential buffer-shape change
  → 重新 emit "resize" 事件 (即使 rows 没变)
  → onResize 调 setH(...)
  → React re-render
  → Ink 又写 ANSI
  → resize 事件又触发
  → ...
```

每次 Ink 重画触发 spurious resize → 1 次 setState → 1 次 re-render → 1 次重画 → 又触发 resize。Ink 的 60+ FPS 重画意味着 setState 频率 60+ Hz,用户看到屏幕"在闪"。

`useState(20)` 加 `onResize()` mount 时立即调用也贡献了一次 setH,但**核心 bug**是 mount 之后 stdout write 触发的循环。

## 修复
`aethercode-tui/src/tui.tsx` resize handler 改成:

1. **80ms debounce** —— 一波 resize 事件(一次 Ink 重画触发的)合并成一次 setH
2. **`lastH` ref guard** —— `apply(rows)` 算出 `next = Math.max(5, rows-6)`,只在 `next !== lastH` 时才 `setH(next)`。Windows 上 resize 事件触发但 rows 不变的情况完全 drop

```tsx
let lastH = 20;
let debounceId: ReturnType<typeof setTimeout> | null = null;
const apply = (rows: number) => {
  const next = Math.max(5, rows - 6);
  if (next === lastH) return;
  lastH = next;
  setH(next);
};
const onResize = () => {
  if (debounceId !== null) clearTimeout(debounceId);
  debounceId = setTimeout(() => {
    debounceId = null;
    apply(process.stdout.rows ?? 24);
  }, 80);
};
apply(process.stdout.rows ?? 24);  // 立即 sync, 跳过 debounce
process.stdout.on("resize", onResize);
return () => {
  if (debounceId !== null) clearTimeout(debounceId);
  process.stdout.off("resize", onResize);
};
```

## 验证

- `tsc -p tsconfig.json` exit 0
- `node scripts/bundle.mjs` exit 0 (ac-tui.js 2.04 MB → 2.29 MB, 增量是 fix 跟 esbuild 重新生成)
- bundle grep: `debounceId` / `lastH` 都在;`process.stdout.on("resize"...)` 还在 (onResize 注册的)
- 实际跑 `node ac-tui/ac-tui.js` 连 MiniMax-M3 daemon,跑 "what is 2+2" query,完整 stream 输出 + `[ready] (reason: stop)`,**没有 re-render loop 症状**

## 教训

1. **Ink + Windows console "resize" 事件不可信** — 每次 stdout write 都会 emit,但通常 rows 不变。必须用 ref guard + debounce 才能用
2. **Ink 5.x 每次重画都写 stdout** — 这跟 Windows console 的 resize 事件触发条件冲突
3. **mount 立即 onResize() 不是 bug 根因** — 真正的循环在 mount 之后,setH 改 state → re-render → 写 stdout → resize → setH
4. **cross-platform TTY 兼容性** — Linux/macOS 上 Ink 5 + resize 一般不会形成循环,但 Windows conhost 会

## 受影响 round

- D:\test\aethercode-0.2.66\ac-tui\ac-tui.js 已用新 build (2.29 MB, 9/13 21:56)
- release zip 53.42 MB 已重打
- desktop exe 不受影响 (Tauri 桌面不走 Ink)

## 后续 (可选)

- 找其他 TUI 组件 (StatusBar 跟 Scrollback 里的 Spinner/ProgressBar) 看看有没有类似 setState-in-useEffect 死循环
- 考虑 `stdout.on("resize")` 整个 TUI 不监听,改用 ResizeObserver 之类 (但 TTY 没 DOM)
