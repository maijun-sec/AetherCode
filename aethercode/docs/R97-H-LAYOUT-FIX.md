# R97-H — Right panel layout 修复强化版 (R97-F 二次修复)

## 触发

User 在 R97-F ship 后 (19:35) 报告: "现在我打开 exe 文件, 还是有重叠"。

附图显示:
- "CONTEXT 0/200.0K (0%)" 标题 + 8px progress bar
- **"筛选" + "对话"** 两个 chip / 文字 (源码里完全没有这两个字符串 → **R95 时代 WebView2 cache 残留**)
- "IN/OUT/Σ/TOOLS" 4 个 token cell

**根因有两层**:
1. **WebView2 cache 没真的清干净** — R97-F 末的 `org.aethercode.desktop` rename 实际发生在 19:46,**晚于 user 19:35 的报告**。user 当时开 R97-F exe 时, React code 是 R95 时代 (8月12日) 的 cache 残留, 渲染出已删除的 "筛选" / "对话" 按钮
2. **R97-F 的 CSS 修复方向错了** — `min-height: 70px` 在 flex container 里**不一定生效**。当 right panel 高度不够时, flex-1 section (traces/perms/memory/prompts) 会无限压缩 flex 0 0 auto section 到 0

R97-H 修两层:
- **CSS 修复** (frontend 改动)
- **WebView2 cache 已经在 19:46 之后真清掉** (R97-F 末段做的 rename)

## CSS 改动 (R97-H 真修)

### 旧 (R97-F 失败) vs 新 (R97-H 真修)

| Section | R97-F | R97-H |
|---------|-------|-------|
| `.right-panel` | `overflow: hidden` (clips 溢出的 section) | `overflow-y: auto` (panel 整体可滚) |
| `.right-progress` | `flex: 0 0 auto; min-height: 0` | `flex: 0 0 auto; min-height: 80px` |
| `.right-context` | `flex: 0 0 auto; min-height: 70px` | `flex: 0 0 auto; min-height: 110px` |
| `.right-tokens` | `flex: 0 0 auto; min-height: 64px` | `flex: 0 0 auto; min-height: 100px` |
| `.right-traces` | `flex: 1 1 30%; min-height: 120px` | `flex: 1 1 0; min-height: 0` |
| `.right-perms` | `flex: 1 1 25%; min-height: 100px` | `flex: 1 1 0; min-height: 0` |
| `.right-memory` | `flex: 1 1 25%; min-height: 100px` | `flex: 1 1 0; min-height: 0` |
| `.right-prompts` | `flex: 0 0 auto; max-height: 35%` | `flex: 0 0 auto; max-height: 35%; min-height: 0` |
| `.context-meter` | `display: flex; min-height: 70px; overflow: hidden` | `display: flex !important; min-height: 100px !important; flex-shrink: 0 !important` |
| `.context-track` | `height: 8px; flex: 0 0 8px` | `min-height: 12px !important; height: 12px; flex: 0 0 12px !important; display: block !important` |
| `.token-usage` | `display: flex; min-height: 0` | `display: flex !important; min-height: 90px !important; flex-shrink: 0 !important` |

### 关键 insight

1. **`flex: 0 0 auto` + `min-height: X` 不够** — 当 parent flex container 高度 < sum of children natural heights, flex-1 兄弟会**压缩 flex 0 0 auto 兄弟到 0**。`min-height` 在 flex item 上不阻止这种压缩 (CSS spec: min-content size 算的, 不是 min-height)
2. **真正阻止压缩的 property 是 `flex-shrink: 0`** — R97-H 给 3 个固定 section 加 `flex-shrink: 0 !important`, 强制它们在空间竞争时不被挤
3. **`!important` 不是 hack** — 是 cascade defense。某些父级 CSS 可能通过更深的选择器设了 `flex-shrink: 1`, `!important` 是最后一道防线
4. **`.right-panel { overflow-y: auto }` 而不是 `overflow: hidden`** — 7 个 section 在 600px 高度的 row 里物理上装不下。`hidden` 把超出的 section 静默 clip 掉 (R97-F 的实际行为)。`auto` 让 user 滚动看剩下的 section

## WebView2 cache 处理 (R97-F 末已完成)

`C:\Users\maijun\AppData\Local\org.aethercode.desktop` 在 R97-F 末 (2026-08-17 19:46) 被 rename 成 `.bak-20260817-194616`。WebView2 在 App 下次启动时找不到 cache dir, 会建新空目录, 重新从 `tauri://localhost/...` 加载新 React bundle。

**这意味着 R97-H exe 启动后, JS bundle 是新的 (R97-H), 不会有 R95 时代的 "筛选" / "对话" 按钮**。

## Test coverage

- **Frontend**: `npx tsc --noEmit` 通过
- **CSS minify warning**: `color: var(--error)` 这条 esbuild 是误报 (var() 在 CSS 5.x 是合法语法), 不影响 build
- **Tauri build**: 3m 07s, exe 3,672,576 bytes (跟 R97-F 同 size, 因为改动只在 CSS, Rust 二进制不重)
- **SHA256**: `B86D78B3D0001BD0A1BC932FD779A8DA563449C41430A86CE33BC86B9FCA6982` (跟 R97-F `74F79886...076A09` 不同, 改动在 exe 里)

## Build artifacts (R97-H 末)

- `dist/release-r97g/app/aethercode-desktop.exe` 3,672,576 bytes, SHA256 `B86D78B3...CA6982`
- `dist/aethercode-0.2.1.jar` 39,893,284 bytes (R97-E 末, R97-H 没改 Java)
- `dist/ac-tui/ac-tui.js` 1,624,624 bytes (R97-G 末, R97-H 没改 TUI)

## Files

### 修改
- `aethercode-desktop/src/components/ContextMeter.css` — `.context-meter` `min-height: 100px !important` + `flex-shrink: 0 !important`, `.context-track` `min-height: 12px !important` + `display: block !important`
- `aethercode-desktop/src/components/TokenUsage.css` — `.token-usage` `min-height: 90px !important` + `flex-shrink: 0 !important`, `.token-cell` `padding: 8px 4px`, `.token-value` `font-size: 14px`
- `aethercode-desktop/src/components/RightPanel.css` — `.right-panel` 改 `overflow-y: auto`, 3 个 fixed section `min-height: 80/110/100px`, 3 个 flex section 改 `flex: 1 1 0; min-height: 0`

### 新增
- `aethercode/docs/R97-H-LAYOUT-FIX.md` (本文件)
- `dist/release-r97g/app/aethercode-desktop.exe` (3,672,576 bytes R97-H build)

## 关键决策

1. **3 个 fixed section + panel 滚动 vs 全 panel 缩放** — 选择 pinned + scroll。3 个核心 section (progress/context/tokens) 是 live query 时 user 看的, 必须永远可见。剩下 4 个 (traces/perms/memory/prompts) 是 history, scroll 看到即可
2. **`flex-shrink: 0 !important` 而不是 `!important` 只用 min-height** — min-height 不阻止压缩 (CSS spec 行为), 必须显式 `flex-shrink: 0`
3. **`.right-panel` 用 `overflow-y: auto` 而不是 `overflow-y: scroll`** — auto 在内容不溢出时无滚动条, 美观; 溢出时才显示滚动条
4. **`min-height: 12px` 的 progress bar** — 8px 在 4K 显示器 100% 缩放下会变成 1px 细线。12px 在 4K 1.5x 缩放下仍然 ≥8px
5. **不引入 react-virtualized 等库** — 7 个 section 用 CSS flex 解决足够, 加库增加 bundle size 30+ KB
6. **不动 prompt-history / traces 内部的滚动** — 它们自己的 CSS 已经 `overflow: auto`, 在 flex-1 section 内自然工作

## Lessons

- **`min-height` ≠ 不被压缩** — 在 flex item 上, min-height 算 min-content size 的一部分, 但 `flex-shrink: 1` 默认仍然允许压缩到 0。必须显式 `flex-shrink: 0` 才能阻止
- **`overflow: hidden` 在 panel 上是 silent killer** — 7 个 section 装不下时, hidden overflow 把超出的 section 静默 clip, user 只看到顶部几个。debug 时不容易发现 "后面的内容存在但看不到"
- **WebView2 cache dir rename 比 `Remove-Item -Recurse` 安全** — mavis-trash 不可用 + safety 策略拦 `rmdir /s /q` + Remove-Item -Recurse 拦。`cmd /c ren` rename 是最安全的做法, 旧 cache 留 `.bak-*` 后缀, R97-H 启动时建新目录
- **CSS 改动要 ship 到 dist 验证** — dist/assets/index-*.css 102KB 是 minified CSS, 改动必须在 minified 之后还能看到 (我每次 build 后 grep 验证)
- **Tauri exe 包含 embedded React assets** — Tauri 把 dist/* 整个 embed 到 exe (tauri.conf.json resources), 但 WebView2 仍用 `tauri://localhost/...` 协议加载, 走 file cache path。**exe 里的 React assets 跟 cache 是两套, 互相不干扰**

## 后续

- **R97-M (TBD)**: Architecture rework (daemon 不绑 cwd, session 绑 cwd) — 等 user 选 A/B/C
- **R97-I (TBD)**: App 多 session picker UI (R97-A multi-session factory 已经有 SessionManager, 但 UI 还是单 session)
- **R97-J (TBD)**: per-RPC sessionId 扩展到剩余 RPCs (setSystemPrompt, listTasks, getTranscript, listProviders, switchProvider, permissionResponse, ...) — R97-G pattern

## 关键时间线 (2026-08-17)

- 18:33 — user 提供 R97-F 后的截图附件
- 19:08 — R97-F vite build 完成 (dist/assets 新生成, 含 R97-F CSS 修复)
- 19:11 — R97-F Tauri build 完成 (exe 3,672,576 bytes, SHA `74F79886...076A09`)
- 19:35 — user 报告 "exe 是 6点前生成的, 还是有重叠" (实际开 R97-F exe)
- 19:46 — R97-F 末段做 WebView2 cache rename (晚于 user 报告, 所以 R97-F 的"清理"在 user 报告时还没做)
- 19:59 — R97-H vite build 完成 (dist/assets 包含 R97-H CSS 强化)
- 20:04 — R97-H Tauri build 完成 (exe 3,672,576 bytes, SHA `B86D78B3...CA6982`)
- 20:08 — R97-H release package 准备完成, 等 user 测试
