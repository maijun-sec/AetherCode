# R275 — chat scroll always-on when pinned + per-tick unseenCount (2026-09-16)

## 触发

用户在 R274b 部署后跑了一个新 prompt，发现 chat 渲染有两个 UX bug：

1. **"只能看最前面，没办法滚动到最下面"** — 聊天窗口卡在顶部（scrollTop=0），
   用户没法滚到底部去看自己发的 user bubble 在哪、或者新 step 在哪。
2. **"下面的泡泡看不到了"** — 同一根因，看不到 user bubble / 新生成的 step，
   也看不到底部 StreamingIndicator。
3. **"89020 new"** 提示符疯狂累加（截图中数字是 89020），用户实际在 pinned
   状态看最新内容。

## 根因

`MessageList.tsx` 里 auto-scroll + unseen-count bookkeeping 的 useEffect
有三个耦合的 bug：

### Bug 1: `if (distance < 80)` gate 在初始 mount 失败

```typescript
// R274b (broken)
if (pinned && el) {
  requestAnimationFrame(() => {
    const distance = listRef.current.scrollHeight
      - listRef.current.scrollTop
      - listRef.current.clientHeight;
    if (distance < 80) {
      listRef.current.scrollTop = listRef.current.scrollHeight;
    }
  });
  lastSeenRef.current = cur;
}
```

distance 是 `scrollHeight - scrollTop - clientHeight`。**初始 mount 时**：
- `scrollTop = 0`
- `scrollHeight` 已经 > `clientHeight`（第一批 streaming 进来的内容已经
  比 viewport 高，比如 1500px vs viewport 800px）
- 所以 `distance = 1500 - 0 - 800 = 700 >> 80`
- `if (distance < 80)` 失败 → scroll branch 不执行
- scrollTop 永远 = 0 → "卡在顶部，看不到 user bubble / 新 step"

`distance < 80` gate 是给"已经到底 + 来一点小更新"的 sanity check，但
**它不能拿来拒绝 "初始 mount 时 content 已经超出 viewport"** 的情况。

### Bug 2: unpinned 分支不更新 `lastSeenRef`

```typescript
} else {
  // (pinned=false branch)
  const delta =
    (cur.msgs - lastSeenRef.current.msgs) +
    (cur.subs - lastSeenRef.current.subs) +
    (cur.steps - lastSeenRef.current.steps);
  if (delta > 0) setUnseenCount((c) => c + delta);
  // ↑ lastSeenRef.current NOT updated here
}
```

每次 useEffect 跑都重算 `delta = cur - lastSeenRef`，但 `lastSeenRef`
没更新。结果：用户从 pinned → unpinned（短暂往上滑）→ pinned，每跑
一次 unpinned 分支都把"自首次 mount 以来的全部 delta"加到 unseenCount
上。一个 session 跑了 89k events → unseenCount 就堆到 89020。

### Bug 3: `scrollIntoView({ behavior: 'smooth' })` 在大容器失败

```typescript
// R274b jumpToBottom (broken)
bottomRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
```

smooth-scroll 在 100k-row streaming transcript 里：
- 多个 scrollIntoView 调用互相取消
- 动画时间窗口 > content update 间隔 → 永远到不了 bottom

## Fix

`MessageList.tsx` 改 3 处：

```typescript
// Fix 1: 删除 distance gate — always scroll when pinned
if (pinned && el) {
  requestAnimationFrame(() => {
    if (!listRef.current) return;
    listRef.current.scrollTop = listRef.current.scrollHeight;
  });
  lastSeenRef.current = cur;
} else {
  // Fix 2: unpinned 分支也更新 lastSeenRef (per-tick delta)
  const delta =
    (cur.msgs - lastSeenRef.current.msgs) +
    (cur.subs - lastSeenRef.current.subs) +
    (cur.steps - lastSeenRef.current.steps);
  if (delta > 0) setUnseenCount((c) => c + delta);
  lastSeenRef.current = cur;  // ← FIX: per-tick
}

// Fix 3: jumpToBottom 用 direct scrollTop (synchronous, idempotent)
const jumpToBottom = () => {
  const el = listRef.current;
  if (el) el.scrollTop = el.scrollHeight;
  setUnseenCount(0);
  setPinned(true);
  lastSeenRef.current = { msgs: messages.length, subTasks: subTasks.length, steps: steps.length };
};
```

## 代码改动

`aethercode-desktop/src/components/MessageList.tsx`:
- 删除 auto-scroll 分支的 `if (distance < 80)` gate；现在 pinned 时
  requestAnimationFrame 里直接 `scrollTop = scrollHeight`
- 在 useEffect 的 unpinned 分支末尾加 `lastSeenRef.current = cur`
- `jumpToBottom` 把 `scrollIntoView({behavior:'smooth'})` 换成
  `el.scrollTop = el.scrollHeight`

`aethercode-desktop/src/components/scrollBehaviorR275.test.ts` (3 tests):
- auto-scroll 分支不再有 `if (distance < 80)` gate
- unpinned 分支也 `lastSeenRef.current = cur`（出现 ≥ 2 次）
- `jumpToBottom` 用 `.scrollTop = .scrollHeight` 而不是 smooth scrollIntoView

## 验证

- vitest **1110/1110** pass (1107 → 1110, +3, 0 regression)
- `npx tsc --noEmit` clean
- `npm run build` clean — JS bundle `D--ckL9oO`
- `tauri build --no-bundle` clean — exe SHA
  `372E44AF4ACCEA56634855103D6B4D715681C483FA1BB11B1405B206F4F8B1B1`
  (5,179,904 bytes)
- zip SHA `E9281A9CE66C378117644AE04A138185D5FD4F5FD33E3118AFFB67075FC1A01B`
  (108,412,695 bytes, compresslevel=9)
- jar SHA 沿用 R271/R274b `3A9F786A…` (R275 也是 desktop-only)
- 旧 R274b zip → `release/aethercode-0.2.70.zip.prev.bak`
- 进程清理: 旧 aethercode-desktop PID 19580 关闭 (deploy 期间)

## 部署

- 用户需要重新启动 desktop.exe 才能看到修复（修了 scroll 卡顶部 +
  89020 new 累加 + jump-to-bottom 不响应）

## Commit chain

```
ccf8489 chore: drop legacy R267/R273 source-pin tests + aethercode-tui shim stubs
   ↓
即将: R275: chat scroll always-on when pinned + per-tick unseenCount + instant jumpToBottom
   ↓
即将: doc: R275 round note
```

## 关键调试技巧 (新增 561-563)

561. **初始 mount 时 `if (distance < 80)` 会失败** — scrollHeight 已经
     > clientHeight (内容超出一屏)，distance >> 80，scroll branch 跳过
562. **pinned=false 分支不更新 lastSeenRef = 双倍计算** — 每次 useEffect
     跑都加同样的 (cur - lastSeen) delta 累加到 unseenCount
563. **smooth scrollIntoView 在巨大容器里不 work** — 多个 scrollIntoView
     调用互相取消；Direct scrollTop assignment 是 idiomatic

## 教训 (新增 561-563)

561. **distance gate 是 sanity check,不是 gate** — `if (distance < 80)`
     应该解释成 "如果已经在底部了,确保小幅增长也跟上"，不应该 gate
     "根本没滚到底" 的情况。pinned=true 已经表达了 user 意图 ("我
     想要底部"),honour 它就行
562. **ref-vs-ref-update 路径要对称** — 一个 ref 在 branch A 更新,在
     branch B 不更新 → 累积式 bug。写到 useEffect 的两分支要确保
     每个 tick 都更新所有 bookkeeping state
563. **smooth scrollIntoView 不是 silver bullet** — 在长 list +
     frequent update 场景下会失败。Direct scrollTop 是 idiomatic
     替代 (synchronous, idempotent)