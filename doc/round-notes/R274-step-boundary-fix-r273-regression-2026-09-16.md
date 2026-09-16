# R274 — step-boundary fix for R273 regression (2026-09-16)

## 触发

用户在 R273 部署后跑了一个新 prompt ("在当前目录下生成一个 java maven 项目…")，
发现 chat-area 渲染成 5 个独立的"思考·xxx"details 卡片：

```
▼ 思考 · 用户要求在
▼ 思考 · 当前目录
▼ 思考 · (
▼ 思考 · D:\tmp\
▼ 思考 · abc_1)
```

每个只有一两行字，LLM 还在 streaming 时 timeline 已经塞满 (68879 new 警告)，
**完全不是按 model-emit 顺序的 thinking 块 + tool 块交替**，而是一个 chunk 一个
"思考"细节。用户在 IM 里写："现在的这种展示方式是在搞笑吗？"

## 根因

R273 改了 `text_delta` handler 让它**永远** close 当前 step + open 新 step：

```typescript
// R273 (broken)
if (currentStepId) {
  // ALWAYS close previous, open new step carrying THIS text chunk
  ...
}
```

但 LLM streaming 一个 think 块时本来就会拆成 N 个连续 `text_delta` 事件
（每个 chunk 几十字符），结果每个 chunk 都开一个新 step，buildBlocks 渲染
成 N 个独立的 "思考 · {chunk}" details 卡片。

R267 用 `pendingStepBoundary` (true on tool_result, consumed by next text_delta)
只解决了 tool→text 边界，但漏掉了"long think + parallel tool_use_starts"的情况
（多个 parallel tool 之间没 tool_result，text_delta 不会 split）。

R273 想严格 subsume R267 的修复，但**矫枉过正** —— 失去了"连续 text chunks 累积
成一段 thinking"的不变量。

## Fix

R274 引入 `prevEventWasText` 模块 flag，规则**反转 R267**：

```
prevEventWasText = true        ← run_start 后 / 任何 text_delta 之后
prevEventWasText = false       ← tool_use_start / tool_result 之后

text_delta 到来时:
  if (currentStepId && !prevEventWasText): split (close + open new step)
  else (prevEventWasText=true):           accumulate to current step.text
prevEventWasText = true        ← 消费后置 true，下一个 chunk 累积
```

### 例子

LLM stream event 顺序：
```
text_delta "think1 chunk1"   → accumulate  → S0.text += "think1 chunk1"
text_delta "think1 chunk2"   → accumulate  → S0.text += "think1 chunk2"
text_delta "think1 chunk3"   → accumulate  → S0.text += "think1 chunk3"
tool_use_start A             → prevEventWasText=false; S0.tools += [A]
tool_use_start B             → prevEventWasText=false; S0.tools += [A, B]
text_delta "OK"              → split       → close S0, open S1 (text="OK")
text_delta "now let me"      → accumulate  → S1.text = "OK now let me"
tool_use_start C             → prevEventWasText=false; S1.tools += [C]
tool_result A               → prevEventWasText=false
tool_result B               → prevEventWasText=false
tool_result C               → prevEventWasText=false
text_delta "test"            → split       → close S1, open S2 (text="test")
text_delta "passed"          → accumulate  → S2.text = "test passed"
```

buildBlocks 渲染：
```
▼ 思考 · think1 chunk1 chunk2 chunk3
  ▼ bash A
  ▼ bash B
▼ 思考 · OK now let me
  ▼ file_write C
▼ 思考 · test passed
```

完美按 LLM emit 顺序：连续 think 累积成一段 thinking 文字，每个 tool 在
恰当位置穿插，每个新 think phase 拆成新 block。

## 跟 R267 / R273 对比

|                | R267 (旧)                | R273 (错)                | R274 (本 round)         |
|---|---|---|---|
| 规则            | tool_result→text split   | text 永远 split          | tool→text split         |
| 同段 think     | 累积到同一 step           | 每 chunk 一 step (错)     | 累积到同一 step          |
| long think + 多 parallel tools | 不 split (错)           | 全 split (错)              | split (对)              |
| text between parallel tools    | 不 split (错)           | 全 split (错)              | split (对)              |
| run_start 后第一个 text | split (但 run_start 创建空 step，浪费) | split (浪费) | accumulate (对)         |

R274 严格 subsumes R267 (tool_result→text split 仍在) 且**额外**覆盖：
- text between parallel tool_use_starts (R267 漏)
- 连续 stream chunks 累积成一段 (R267 有，但 R273 反而坏了)

## 代码改动

`aethercode-desktop/src/store/index.ts`:
- 加 `let prevEventWasText: boolean = true` 模块 flag (默认 true 意味着
  run_start 后第一个 text_delta accumulate 到 run_start 创建的空 step)
- `case 'run_start':` 开头 `prevEventWasText = true` 重置
- `case 'tool_use_start':` 开头 `prevEventWasText = false`
- `case 'tool_result':` 开头 `prevEventWasText = false`
- `case 'text_delta':` 改逻辑：if `currentStepId && !prevEventWasText` split;
  else if `currentStepId` accumulate; 消费后 `prevEventWasText = true`

`aethercode-desktop/src/store/stepBoundaryR274.test.ts` (6 tests, 取代 R273):
- 验证 `let prevEventWasText: boolean = true` 模块 flag 声明
- 验证 text_delta 用 `if (currentStepId && !prevEventWasText)` 作为 split gate
- 验证 text_delta 累积路径存在 (`st.text + text`)
- 验证 text_delta 消费后 `prevEventWasText = true`
- 验证 tool_use_start / tool_result 设 `prevEventWasText = false`
- 验证 run_start 设 `prevEventWasText = true`
- 验证没有 R273 "always split" 残留 (`if (currentStepId) {` 没匹配 R273 注释)

## 验证

- vitest **1104/1104** pass (1102 → 1104, +2, R273 -4 R274 +6 net +2, 0 regression)
- `npx tsc --noEmit` clean
- `npm run build` clean — JS bundle `DrbBBjkJ` (was `DpsoQjYi`)
- `tauri build --no-bundle` clean — exe SHA
  `404F27E91EC531D8EB6E35B83FF49391E848BAFB51E8EC018BEEA062BA972AD8` (5,179,904 bytes)
- zip SHA `BC7A142D22C78F28BDDF85C1A061EC42841689762961205325EB9A2DE3B47946`
  (108,497,703 bytes)
- jar SHA 沿用 R271/R273 `3A9F786A…` (R274 也是 desktop-only)
- daemon PID 21448 / 12196 / desktop PID 18420 全部清理

## 部署

- 旧 R273 zip → `release/aethercode-0.2.70.zip.prev.bak` (C1989E94…)
- 新 R274 zip → `release/aethercode-0.2.70.zip` (BC7A142D…)
- 用户需要重新启动 desktop.exe 才能看到修复

## Commit

- `81749b0` R273 (parent)
- 即将: `R274: prevEventWasText flag — fix R273 chunked-think regression`
- 即将: `doc: R274 round note`

## 关键调试技巧 (新增 555)

555. **矫枉过正是常见 bug 模式** — 当一个 fix 没把 symptoms 完全消除，
     加更激进规则可能引入新的 symptom。R267 没修干净 → R273 想"永远 split"
     解决 → R273 反而引入"每个 chunk 一个 step"渲染 bug。修复 R273 时要
     想清楚不变量："连续 stream chunks 是同一段 thinking"必须保持。
     规则 "split on tool→text, accumulate on text→text" 是 R267 + R273
     的 intersection，正确。

## 教训 (新增 555-557)

555. **矫枉过正会引入新 symptom** — R267 没修干净就让"永远 split"，看着严格
     subsumes 旧规则实际上丢了"同一段 thinking 累积"的不变量。**严格
     subsumes** 不等于**修对了**，subsumes 只保证"包含旧的所有正确行为"，
     但可能新增破坏性行为。
556. **rule 的 "inverse" 也可能是对的** — R267 是 "split on tool→text"，
     R274 的核心洞察是 split signal 用"上一个 event 是不是 tool"足够，
     并不需要"上一个 event 是不是 tool_result"。"tool_use_start 也算
     tool boundary"这个扩展就是 R274 严格修对的关键。
557. **R267 是对的方向，但实施不完整** — R274 算是 R267 的"补完版"：
     R267 把 split signal 放在 tool_result 上，R274 扩展到所有 tool
     事件 + 翻转累积/分裂的 trigger 条件。