# R180 — ErrorBoundary 兜底 (2026-09-01)

## 1. 目标

R179 是 "白板"（白屏）问题的最佳猜测根因 + 修复（按 id 合并的 `transcript_event` 同步处理器 + `hydrateTranscript`）。
但 R179 没有完全 headless 重现；如果以后某个子组件（MessageList / PreparingCard / ToolCallCard / StepDetailModal 等）出现真正的 React 渲染错误（比如工具调用 JSON 格式异常、markdown 属性为 null 等），React 19 仍然会把整棵子树 unmount 掉，让用户看到和 R179 一样的"什么都没有"。

R180 是兜底：把 `<MessageList />` 包在一个 React `ErrorBoundary` 里，让任何渲染异常都落到一张可读、可操作、可复制的错误卡片上，而不是一片空白。

> 用户层面：现在哪怕渲染崩了，至少能看到错误信息和"重试"按钮，知道发生了什么。

## 2. 改动

| 文件 | 状态 | 描述 |
|------|------|------|
| `src/components/ErrorBoundary.tsx` | 新建 (6.0 KB) | 顶层 React 错误边界 class 组件，捕获渲染异常 |
| `src/components/ErrorBoundary.css` | 新建 (2.2 KB) | 错误卡片样式，红色软背景 + 红色描边 |
| `src/App.tsx` | 修改 | 把 `<MessageList />` 包进 `<ErrorBoundary label="Chat 列表">` |
| `src/components/errorBoundaryR180.test.ts` | 新建 (4.5 KB) | 3 个 source-pin 回归测试 |

## 3. ErrorBoundary 设计

**类型**：class component（React 19 错误边界必须是 class，`getDerivedStateFromError` + `componentDidCatch`）。

**行为**：
1. `getDerivedStateFromError` 把错误存到 state。
2. `componentDidCatch` 打印到控制台，tag 是 `[R180 ErrorBoundary]`，方便 DevTools 检索。
3. 同时 `queueMicrotask + setTimeout + throw` 把原始异常**重新抛**给全局 `window.onerror` / Sentry 类 hook — 不能静默吞错。
4. 渲染 fallback：红色软背景卡片，含：
   - 图标 ⚠ + 标题 "Chat 列表 渲染出错"
   - 错误 message
   - 错误 stack（截前 8 行）
   - "重试" 按钮（默认行为：清 state + 给 children 加新 `key` 强制重挂载）
   - "复制错误" 按钮（写到剪贴板，方便贴 issue）
   - 底部 hint："已记录到控制台（F12 / DevTools "Console"），可粘贴到 issue 反馈。"

**为什么用 class component 而不是 hooks**：React 19 还没有官方的 `useErrorBoundary` 钩子（`<ErrorBoundary>` 包在 react-error-boundary 里，但它要求 children 也是函数组件）。class 是 React 一等公民的官方错误边界实现。

**为什么 message + stack 截断**：完整 stack 可能 100+ 行，撑爆卡片。截前 8 行通常能覆盖到 React 组件树的关键节点，足够定位；用户要看全栈可以 F12 打开控制台。

## 4. 集成策略

只把 `<MessageList />` 包起来，而不是整个 `<Shell />`：

| 选择 | 后果 |
|------|------|
| **只包 MessageList** ✅ | MessageList 崩了 → 显示错误卡片；header / 状态栏 / 输入框照常工作，用户还能切换 CWD、打开 Settings |
| 包整个 Shell | 太激进：providers / 路由崩了也会被吞，反而难定位；header 都看不到 |

兜底原则：**让用户尽可能多地保留逃生通道**。如果 MessageList 渲染挂了，至少输入框、状态栏、Header 还能用；用户可以改 CWD → 新 session 绕过去。

## 5. Source-pin 测试

`src/components/errorBoundaryR180.test.ts` — 3 个 source-pin 测试：

1. `ErrorBoundary.tsx exists and is a real class component` — 验证 `class ErrorBoundary extends Component`、`componentDidCatch`、`getDerivedStateFromError` 三个关键签名都在。
2. `ErrorBoundary fallback includes a 重试 button and an R180 console marker` — 验证 fallback 里出现 `重试` 文字 + 控制台日志 tag `R180 ErrorBoundary`。
3. `App.tsx imports ErrorBoundary and wraps <MessageList /> with it` — 验证 `App.tsx` 真的 import 了 ErrorBoundary 并且把 `<MessageList />` 包在 `<ErrorBoundary label="Chat 列表">` 里。

**剥离注释的 regex 模式**（沿用 R179）：`replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '')` — 让 regex 匹配真实代码，不匹配解释性 JSDoc。

## 6. 测试结果

| 套件 | 改动前 | 改动后 |
|------|--------|--------|
| vitest test files | 73 | **74** (+1) |
| vitest tests | 882 | **885** (+3) |
| tsc --noEmit | clean | clean |
| npm run tauri build | 2m17s | **2m53s** |

## 7. 构建产物

| 路径 | 大小 | SHA256 |
|------|------|--------|
| `release/aethercode-0.2.28/AetherCode.exe` | 3,950,080 (3.95 MB) | `F4CCAF64E1FF358A85A2A3DB38FBFF43DAD4FEF2BE3DA5B079356C50C8D88828` |
| `release/aethercode-0.2.28/aethercode-0.2.28.jar` | 55,301,281 (53 MB) | `56CF4BEF00FD789E915A4B1136940D735B2B89D2776ED30F7035F56F7B1D817B` |
| `aethercode/dist/aethercode-0.2.28.jar` | 55,301,281 (53 MB) | `56CF4BEF00FD789E915A4B1136940D735B2B89D2776ED30F7035F56F7B1D817B` |

**jar 跟 v0.2.27 完全相同**（SHA256 一致）— R180 是纯前端改动，没动后端。

## 8. 验证步骤

1. 关闭当前 v0.2.26 桌面窗口。
2. 启动 `D:\work\workspace\idea\engine\AetherCode\release\aethercode-0.2.28\AetherCode.exe`。
3. 在 chat 输入框随便发一条 prompt。
4. 验证 R179 修复："白板" 不再出现（按 id 合并的 transcript 应该保留用户消息）。
5. **R180 验证**（可选）：在 DevTools Console 里手动 `throw new Error('test R180')` 在 React 渲染路径上（或者在 MessageList.tsx 临时插入 `throw new Error('R180 test')`），验证：
   - 错误卡片显示出来而不是白屏
   - 错误 message 正确
   - "重试" 按钮可以恢复
   - "复制错误" 按钮可以写入剪贴板

## 9. 教训

1. **最佳猜测根因 + 兜底 = 防御性策略**：R179 是 R180 之前对 "白板" 的最佳猜测根因。但即使 R179 修对了，**未来还是可能因为别的 bug 撞上同一个症状**。错误边界是必备的"安全网"，让用户至少能知道发生了什么。
2. **class component 还有用武之地**：React 19 强烈推 hooks，但错误边界是少数 class 仍是唯一干净实现的场景。
3. **source-pin 注释剥离是 R179 → R180 的连贯模式**：未来类似 "防止某段代码被 refactor 改掉" 的测试，剥离注释 + 正则锚关键标识符是个稳的模式。
4. **不要静默吞错**：错误边界捕获后**必须**重新抛出（用 microtask + setTimeout 跨过 React 的"已经在 render 阶段"警告），否则全局错误报告器就丢了信号。
5. **ErrorBoundary 比 console.error 更友好**：让用户在 UI 上看到错误 + 复制 + 重试，比让用户自己打开 DevTools 友好太多。

## 10. 后续

- **R181 候选**：把 R98 deny matrix 里的白名单逻辑（`where` / `java -version` / `mvn -version` / `cmd /c "where ..."`）挪到 Java 端白名单，重建 aethercode-cli jar。
- **R181 候选**：BashTool 默认 timeout 180s 太长，diagnostic 类的命令（`where`, `--version`）应该单独走 30s 短超时。
- **R181 候选**：考虑给整个 `<Shell />` 也加一个兜底 ErrorBoundary（标签 "App"），但只在确认子边界不够用之后。
