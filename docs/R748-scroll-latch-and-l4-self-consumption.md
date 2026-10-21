# R748 — 滚动永久闩锁 + L4 同 turn 自我蚕食

日期: 2026-10-21
触发: 用户报告两个 desktop 问题（跑一个 45 分钟的研究任务时）
- 1. 对话卡在中间，最开始在最下面，滚动上来后就下不去了
- 2. 执行过程中感觉多次卡住，能自己恢复，但卡顿感严重影响体验

两个问题**完全独立**，根因都在架构层而非表面症状。

---

## Part 1 — 滚动：`userScrolledRef` 是永久闩锁（R748a）

### 症状

「最开始在最下面，然后我滚动上来后就下不去了」

### 根因

R735 引入的 `userScrolledRef` 是一个**单向闩锁**（one-way latch）：

```ts
// 置 true:  listener 看到 distance > 80
// 置 false: 只有 listener 再次看到 distance < 80
```

`distance = scrollHeight - scrollTop - clientHeight`。

致命点在于 **`distance` 是对着一个不断增长的 `scrollHeight` 测量的**。

45 分钟研究会话的实测数据：

| 时刻 | transcript 消息数 | transcript 字符数 |
|------|------------------|-----------------|
| 开始 | 377 | 527,231 |
| turn 17 | 417 | 589,355 |

一个原本距底部 40px 的视口，在 400 张新工具卡片落地后，距离底部变成了 **3000px**——而**用户什么都没做**。

因为清除闩锁的唯一条件是「回到新的底部」，而用户根本到不了那个位置，所以 auto-scroll 在整个会话剩余时间里都是死的。

这正是 R735 试图修的那个 bug 的**更严重版本**：R735 修的是首帧竞态（mount 时），R748 修的是长会话内容增长。

### 两个独立缺陷

必须**同时**存在才会触发症状：

**(A) 误置位** — 任何 `distance > 80` 都会置位闩锁，**包括内容增长导致的那次 scroll 事件**。

浏览器在 `scrollHeight` 增长时会为静止视口派发 scroll 事件。修法：比较 `scrollTop` 是否真的动了。
- 用户拖动 → `scrollTop` 变化
- 内容增长 → `scrollTop` 不变，只有 `scrollHeight` 变

**(B) 永不失效** — 即使正确置位了，也没有过期机制。

修法：加 TTL（`USER_SCROLL_GATE_TTL_MS = 4000`）+ 一个在 TTL 到期时唤醒的定时器。这与主流 chat UI（ChatGPT / Claude.ai / Slack）的意图一致：

> 你滚开了 → 我们停止拽你；你闲下来 → 我们恢复跟随

### 改动

`aethercode-desktop/src/components/MessageList.tsx`：

1. 新增 `lastScrollTopRef` / `lastScrollHeightRef` 记录上次几何
2. listener 计算 `scrollTopMoved`，**只有它为 true 才置位闩锁**
3. 新增 `lastUserScrollAtRef` 记录置位时刻
4. 新增 `isUserScrollGateActive()` — TTL 感知的门判断
5. 新增 `resumeFollowing()` — 同时清 boolean 和 timestamp
6. `shouldFollowTail` 改用 `!isUserScrollGateActive()`（**不是**裸 boolean，否则 TTL 被完全绕过）
7. 新增 TTL 唤醒定时器（否则「门过期」不可观测）
8. `jumpToBottom` 改用 `resumeFollowing()`

### 测试

`MessageListR748ScrollGate.test.tsx` — 8 个 source-pin case。

---

## Part 2 — 卡顿：L4 在同一个 turn 内自我蚕食（R748b）

### 症状

「感觉有多次卡住，然后的确能自己恢复」

### 这不是 UI 假死

先量化。182 个 LLM 轮次 / 45 分钟的日志时间线：

| 轮次区间 | 停顿 | 性质 |
|---------|------|------|
| 1 → 8 | 3.9s ~ 23.2s | 正常工具执行 |
| 20 → 21 | **120.9s** | 丢请求后重试 |
| 21 → 22 | **143.8s** | 丢请求后重试 |
| 157 → 158 | **449.3s** | 丢请求后重试 |

超过 3 秒的停顿共 **150+ 次**。

### 真正的原因：17.6% 的请求被丢弃

日志里 `GoogleScholarClient - scholar search failed: HTTP 429` 频繁出现。

**因果链是反的**——不是「工具慢导致卡顿」，而是：

1. 引擎把 transcript 喂给 MiniMax-M3，上下文太大
2. MiniMax 返回 5xx（`500 unknown error (1000)` / `context window exceeds limit (2013)`）
3. SpringAiChatClient 的 R697 重试阶梯等 5s / 15s / 45s 后重试同一个请求
4. 用户看到的就是「卡住 → 自己恢复」

### L4 自我蚕食的机制

关键日志：

```
turn  1: 377 messages -> 139 survivors (dropped 239)
turn  2: 383 messages -> 138 survivors (dropped 245)
...
turn 17: 417 messages -> 107 survivors (dropped 310)
```

`stripOldestToFit` 在**每一轮**都对**完整的 on-disk transcript** 重新执行。因为每轮会新增若干消息，drop 计数每轮涨约 6 条，于是：

> 工作窗口在**同一个用户请求内**从 139 条缩到 107 条。

模型在同一个任务里逐步忘记自己已经做过的工作，重复读同样的文件，丢失线索——这就是它开始丢请求的原因。

### 修法：shrink-to-fit 先于 head-drop

transcript 超标约 4 倍（589K vs 150K cap），原因是少数几个超大 `TOOL_RESULT`（arxiv / google_scholar payload 各 10-40K 字符）。

**关键洞察**：丢整条消息会破坏 provider 的 adjacency 契约（R736/R741）。丢掉一个 tool_result 会迫使 R736 在下一轮剥掉它对应的 tool_call，整轮消失。**削内容**才能保住「声明 + 回答」相邻。

所以在 head-drop 之前加一层：均匀削超限的 tool result。

### 实现：水位式收缩

第一版有 bug，逐个削最大的，stall 在 504K（cap 是 150K）。原因：每轮重扫**原始** list，重选一个已削过的消息，`target == victimSize`，永不推进。

最终版两个修正：
1. 扫**工作副本** `out` 而非原始 list
2. **均匀削所有超限的** tool result，而非逐个削最大的

```
waterMark = 4000
while total > cap:
    削掉所有 > waterMark 的 tool result
    if total <= cap: break
    overflow = total - cap
    waterMark -= overflow / toolResultCount   # 至少降 256
```

只削 tool result，**绝不削 assistant/user 推理文本**——丢推理比丢一个可重新 fetch 的 payload 更糟。

### 日志 marker

```
R748: shrink-to-fit clipped oversized tool_result(s) 589355 -> 142xxx chars
      (cap=150000) instead of dropping 310 head message(s)
```

部署后 grep 这行即可确认 patch 真的触发了（Lesson 1052）。

---

## 测试

`TranscriptPrunerShrinkToFitR748Test` — 6 个 case：
1. under cap → 同一引用
2. 削完就 fit → **丢 0 条**（pre-R748 丢 14/18）
3. **窗口跨 20 轮稳定**（实际 bug 的直接复现）
4. 削过的 tool result 保留 role + tool_use_id（R736 不当孤儿剥）
5. 削不够 → 仍回落 head-drop
6. 绝不削 assistant 推理文本

**变异验证**：把 `clipFatToolResults` 调用替换为 `transcript`（退回 pre-R748 语义），case 2 和 4 转红 —— 测试确实在守护这个修复。

---

## 附带修复：13 处测试路径依赖 ambient wd

排查过程中发现全项目 source-pin 测试用 `process.cwd()` 定位源码。在 vitest jsdom 环境下多个 suite 同跑时 cwd 不可靠，导致：

- R748 测试单独跑绿，全量跑红
- 报 `ENOENT .../src/src/components/MessageInput.tsx`（路径多一层 `src`）

统一改成从 `import.meta.url` 推导（`ToolsPanel.test.ts` 早就是这个写法）。共修 **13 个文件**。

同时更新了 3 个 pin 住 R748 故意修改之语义的旧断言：
- `MessageListR735AutoScroll`：2 处（`shouldFollowTail` / `jumpToBottom`）
- `scrollBehaviorR275`：2 处（useEffect 定位改用 `shouldFollowTail` 锚点，因为 R748 插入了新的 useEffect）

---

## 部署验证状态

- aethercode-core **1504 tests / 0 failures**
- aethercode-desktop **1365 passed / 1 failed** — 唯一失败是 `R104: Header.tsx has a tools button (🔧)`，**pre-existing**（stash 掉本次全部改动后同样红，与 R748 无关）
- `tsc -b --noEmit` exit 0
- 变异测试确认有效

## Lessons

- **Lesson 1073 (R748a)**：`distance = scrollHeight - scrollTop - clientHeight` 里的 `scrollHeight` 是**移动靶**。任何基于「距底部距离」的 UI 状态判断，都必须先区分「视口移动了」和「内容增长了」——比较 `scrollTop` 的变化，而不是「有没有 scroll 事件」。
- **Lesson 1074 (R748a)**：单向闩锁必须有失效路径。任何「用户意图」状态（滚开了/静音了/折叠了）都要能自动过期，否则内容一增长就永久锁死。
- **Lesson 1075 (R748b)**：**「卡住然后自己恢复」的第一嫌疑不是 UI，是被丢弃的请求**。先量化日志时间线（停顿分布 + 重试阶梯），再猜渲染。120s / 143s / 449s 的停顿是重试阶梯的指纹。
- **Lesson 1076 (R748b)**：**裁剪式上下文压缩优于丢弃式**。丢消息会破坏 provider 的 adjacency 契约并连锁触发 repair pass；削内容只损失可重新获取的字节，不损失结构。
- **Lesson 1077 (R748b)**：**迭代式收缩算法必须扫工作副本，且要单调推进**。「扫原始 + 原地改」的组合会静默 stall——每轮重选同一个已处理对象。收敛判据要显式（`after >= total` 就退出）。
- **Lesson 1078 (R748)**：`git stash` 会静默丢弃后续改动。诊断 pre-existing 失败时，stash 之后**必须立刻 pop 并验证文件内容**，否则会在错误的基础上继续工作（本轮真发生过一次）。
- **Lesson 1079 (R748)**：source-pin 测试用 `process.cwd()` 在 jsdom 多 suite 场景下不可靠。一律用 `import.meta.url` 推导绝对路径。
- **Lesson 1080 (R748)**：往组件里插入新的 `useEffect` 会让「最后一个 useEffect」这类位置启发式失效。定位用**语义锚点**（`shouldFollowTail`）而非位置倒数。