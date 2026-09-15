# R269 — middle-of-chat streaming indicator

Date: 2026-09-15
Round: R269
Type: UX (desktop-only)
Status: ✅ Deployed (commit `9fd6517` push 成功)

## Trigger (用户反馈)

用户 17:00 反馈: "建议还是在中间完整展示，因为我看当前典型的所有的 AI Agent 工具，都是在原来中间的位置有加载的"。

观察 Cursor / Claude Code / ChatGPT 等典型 AI Agent 工具 — streaming 时在主 chat 区域**中间位置**显示一个明显的 loading 状态（pulse 圆点 + 状态文字），让用户实时知道 "engine 还在干活"。

## 现状 (修复前)

AetherCode 当时只有两处 streaming 反馈：

1. **顶部 ActivityIndicator** (`src/components/ActivityIndicator.tsx`)  
   放在 `<main className="center">` 顶部 (ReconnectBanner 之后)  
   12px 字号 / 6px padding / 8px 圆角 / 单色 dot  
   **位置不在 chat 中间**，用户眼睛盯在 chat 内容时容易忽略

2. **dead-code 内联 "等待模型响应…"** (`MessageList.tsx`)  
   ```
   {isStreaming && timeline.length === 0 && (
     <div className="message-list-empty-steps">{'等待模型响应…'}</div>
   )}
   ```
   **死代码** — 这个 check 在 `timeline.length > 0` 的分支里，内部的 `timeline.length === 0` 永远 false。永不触发。

3. **完全没有 "between events" 反馈**  
   当 timeline 已经至少有 1 条 event，但 run 还没结束，chat 区域中间**空无一物**，用户看到 "思考 block 显示完 → 一段沉默 → 不知道是 hang 还是还在跑"。

## R269 修复

### 新组件 `<StreamingIndicator />`

`src/components/StreamingIndicator.tsx` (React + CSS module-style) — 两个 variant:

#### `variant="empty"` — 大号居中 hero placeholder

当用户刚发 prompt 但 timeline 还没任何 event 时，在 chat 区域中央显眼显示：

```
┌───────────────────────────────────────┐
│                                       │
│   ●  ⏳ 等待模型响应…                 │   ← 14×14 px 圆点 + 大脉冲
│                                       │   蓝色边框 + 半透蓝填充
│                                       │   font-size: 14px
└───────────────────────────────────────┘
```

替换 dead-code `<div className="message-list-empty-steps">等待模型响应…</div>`。

#### `variant="footer"` — 紧凑左对齐

当 timeline 至少有 1 条 event 时，**在最后一条消息下方、MessageInput 上方**持续显示：

```
   ●  💭 Thinking…          ← 9×9 px 圆点 + 小脉冲
                              蓝色 / 琥珀色 spinner / 绿色 / 红色
                              跟 ActivityIndicator 配色一致
                              font-size: 13px, 圆角 pill
```

颜色按 `currentActivity.kind`:
- `thinking` → 蓝 (#5da9ff) + pulse
- `tool` → 琥珀 (#f0b95a) + spinner  
- `done` → 绿 (#6cc28b) + 静止
- `error` → 红 (#f48771)

用户学一次配色语言就能识别状态。

### MessageList 集成

```tsx
// 修复前 (R196): dead-code path
{timeline.length === 0 ? (
  <div className="message-list-empty">Start a conversation</div>
) : (
  <>
    {timeline.map(...)}
    {isStreaming && timeline.length === 0 && (  // 永远 false
      <div className="message-list-empty-steps">{'等待模型响应…'}</div>
    )}
    <div ref={bottomRef} />
    ...
  </>
)}

// 修复后 (R269): StreamingIndicator
{timeline.length === 0 ? (
  isStreaming ? (
    <StreamingIndicator variant="empty" forceWhenEmpty />
  ) : (
    <div className="message-list-empty">Start a conversation</div>
  )
) : (
  <>
    {timeline.map(...)}
    {/* R269: persistent streaming footer */}
    {isStreaming && <StreamingIndicator variant="footer" />}
    <div ref={bottomRef} />
    ...
  </>
)}
```

### A11y

两个 variant 都有 `role="status"` + `aria-live="polite"`，screen reader 在状态变化时会朗读出来。

## 测试

### 新增 `streamingIndicatorR269.test.tsx` (9 个 test)

**Source-pin (3)**:
- `StreamingIndicator` is exported from `src/components/StreamingIndicator.tsx`
- CSS file defines both empty / footer variants + 4 kinds + keyframes
- `MessageList` imports + uses both variants

**Behaviour (6)**:
- renders nothing when isStreaming=false and no activity
- empty variant renders when isStreaming=true (forceWhenEmpty)
- empty variant hides done / error (run already ended)
- footer variant renders with currentActivity label
- role=status + aria-live=polite for a11y
- footer colour-codes by kind (CSS class `streaming-kind-tool` 等)

### 更新 `messageListEmptyStepsR176.test.ts` (R176 → R269 guard)

R176 的 regression guard 升级为 R269 shape:
- 正 pin: empty-state branch uses `<StreamingIndicator variant="empty">` when `isStreaming`
- 正 pin: populated branch uses `<StreamingIndicator variant="footer">` when `isStreaming`
- **负 pin**: 不再有 inline `message-list-empty-steps` + `等待模型响应…` 块 — 防回归

### 测试结果

```
vitest 1069 → 1079 (+10)
Test Files  88 passed (88)
Tests       1079 passed (1079)
Duration    30.34s
```

**0 回归**。

## 部署

### Build

```bash
npx tsc -b --noEmit       # clean
npm run build              # ✓ built in 7.29s, bundle index-DF9-f_SN.js
cargo build --release --features tauri/custom-protocol  # ✓ 3m 37s
```

### 部署链

| 项 | 值 |
|---|---|
| commit | `9fd6517` (push 成功) |
| jar SHA | `B84855BF...` (跟 R268e 同, daemon 无变化) |
| exe SHA | `1E0F3E62AF4E730D0C8415DC8476BF5DC8D95A5E` (R269 build, JS bundle `DF9-f_SN`) |
| zip SHA | `3D344584ACB05B6B02C0B2821AC1C2C4F216287A` (108,489,380 bytes) |
| 老 zip | `.prev.bak` (R268e backup) |

### Bytecode markers in jar

没变 (R269 是 desktop-only), R268e 的 8/8 markers 都在位。

## 用户下一步验证

1. 双击 `release\aethercode-0.2.70\desktop\aethercode-desktop.exe` 启动
2. 输入 prompt 后, 在 chat 中央 / 底部**应该立即看到**带圆点的 streaming indicator  
3. 当 thinking → 工具调用 → thinking, indicator 的颜色/动画跟着切换  
4. 多个 tool 调用时 (long-running), 用户看 indicator 就能确认 "engine 还在干活"

## 教训 (新增 4 条)

536. **dead-code 是技术债** — `isStreaming && timeline.length === 0` 写在 `>0` 分支里永远 false 的写法, 应该靠 test 抓出来, 而不是等用户反馈"没看到 loading"
537. **典型 UX 是 benchmark** — Cursor / Claude Code / ChatGPT 都把 streaming indicator 放在 chat 中间, 这是行业 de-facto 标准, 用户看到 AetherCode 没有会立刻觉得"少了一块"
538. **a11y 在小 UI 也要考虑** — `role=status` + `aria-live=polite` 让 screen reader 用户也能感知状态变化, 是低成本投入
539. **JS-only round 不需要 rebuild jar** — R269 只改 React/CSS, daemon jar 复用 R268e (`B84855BF...`), 部署链更轻

## 文件清单

### 新增
- `aethercode-desktop/src/components/StreamingIndicator.tsx`
- `aethercode-desktop/src/components/StreamingIndicator.css`
- `aethercode-desktop/src/components/streamingIndicatorR269.test.tsx`
- `doc/round-notes/R269-streaming-indicator-middle-2026-09-15.md` (本文)

### 修改
- `aethercode-desktop/src/components/MessageList.tsx` (empty branch + footer indicator)
- `aethercode-desktop/src/components/messageListEmptyStepsR176.test.ts` (R176 → R269 guard)
- `release/aethercode-0.2.70/RELEASE-NOTES.md` (R269 section + 部署链历史)