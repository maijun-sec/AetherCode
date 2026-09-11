# R197: session per cwd — cwd switch creates a new session (2026-09-02)

## TL;DR

R197 fixes the CWD bug the user reported in v0.2.38: "I picked
`D:\tmp\abc_2` but the operations still went to `D:\tmp\abc_1`".

The user clarified the design (in a back-and-forth):
- A **session** is a conversation thread (many rounds, persistent).
- A **cwd** is a project folder.
- Different sessions can target the same project (e.g. session 1
  develops module 1, session 2 develops module 2) or different
  projects.
- Project-level memory is shared across sessions on the same cwd.
- Session-level memory is per-session.

Pre-R197, `setCwd` called `bindSessionCwd` on the active session,
which kept the same session id and reused the model's old-cwd
context (file paths, tool history) for the new project. The user
saw operations go to the old project.

R197 makes `cwd switch` ≡ `new session`:
- When the user picks a new cwd via the CWD picker, the renderer
  calls `rpc.createSession({ cwd: newCwd })` instead of
  `bindSessionCwd`. The daemon mints a fresh session bound to
  the new cwd, with a clean transcript and no leaked context.
- The old session is preserved in the session list (you can
  re-open it via the SessionPicker).
- If the user picks the **same** cwd they were already on, the
  call is a no-op (we don't churn a session id for a no-op
  click).

## Files changed

- `src/lib/methods.ts` — `createSession` now accepts an optional
  `{ cwd, worktree }` parameter (the daemon's `createSession` RPC
  already supports this; the JS wrapper was just ignoring it).
- `src/store/index.ts` — `setCwd` rewritten:
  - Compare `curCwd` vs `newCwd` (after normalising trailing
    slashes and case). If equal, return early.
  - Call `rpc.createSession({ cwd: path })` → returns new session id.
  - Update local state: `currentSessionId`, `cwd`, `messages: []`,
    `currentInput: ''`, append new entry to `sessions` list.
  - Refresh sessions / tools / providers + pre-warm sibling.

## Behaviour matrix

| User action | Pre-R197 | R197 |
|---|---|---|
| Pick a different cwd | Engine's cwd changes, session id unchanged, model reuses old context | New session on new cwd, old session preserved |
| Pick the same cwd | Engine's cwd "switches" to the same value (no-op-ish) | No-op (returns early before any RPC) |
| Stay on the current session | Nothing | Nothing |
| Open an old session via SessionPicker | Loads the old session, that session's cwd is restored | Same (no change) |
| Multiple sessions on the same cwd | Possible via SessionPicker | Same (now also possible via the CWD picker if the user re-picks the same cwd — but the no-op short-circuit makes this a no-op; the user can still create a new session via the SessionPicker "New" action) |

## Test count

- Before R197: 904/904
- After R197: **907/907** (76 test files, +3 R197 source-pin tests)

R197 source-pin tests:
1. `setCwd` calls `rpc.createSession({ cwd: path })` (not
   `bindSessionCwd`)
2. `setCwd` short-circuits when the new path equals the current
   cwd (no churn)
3. `lib/methods.createSession` accepts an optional `cwd` parameter

## Build

```
$ npx tauri build
... Finished 2 bundles at:
   - AetherCode_0.2.32_x64_en-US.msi
   - AetherCode_0.2.32_x64-setup.exe
```

(NSIS + MSI both succeeded again — network is fine today.)

- `AetherCode.exe` 3,944,448 bytes (same as v0.2.39; the diff is
  a few lines in `setCwd` and `createSession`, no new components)
- SHA256 [computed at release time]
- `aethercode-0.2.40.jar` 55,310,537 bytes (unchanged)
- `AetherCode_0.2.40-setup.exe` 2,099,161 bytes (NSIS)
- `AetherCode_0.2.40.msi` 2,580,480 bytes (MSI)

## Lessons (2026-09-02)

1. **"CWD" 在用户视角不是 daemon 全局变量, 是 session 属性** ——
   我们架构里把 cwd 跟 session 绑 (R127), 但 setCwd 行为还
   停留在"全局切换" 思路, 所以用户感觉不对. 教训: **架构
   改了, 行为也得跟上**, 不能只改一半
2. **"new cwd = new session" 跟"in-place update" 是两种不同的
   心理模型** —— 用户说"我选 abc_2 但操作 abc_1" 的潜台词
   是"我以为这是新地方, 应该新开始". 跟用户的"session 是
   对话, cwd 是项目" 心理模型一致. 之前 bindSessionCwd 是
   "in-place" 模型, 用户用的是 "new session" 模型, 错位了
3. **cwd 比较要 normalize** —— `D:\tmp\abc_2` 跟 `D:\tmp\abc_2\`
   是同一个目录, 但 `===` 比较会判不等. normalize:
   - 去掉末尾的 `/` 或 `\`
   - 大小写不敏感 (Windows 不区分)
4. **"old session 还在" 是关键** —— R197 没删除老 session, 只是
   切到新 session. 用户可以从 SessionPicker (Ctrl+Shift+P)
   切回老 session. 这是"两个 session 对应同一项目" 模式
   的基础: 同一个项目, 多个 session (模块1, 模块2 分别一个
   session), 通过 session picker 切换
5. **"createSession({ cwd })" 协议早就支持, 只是 JS 没传** ——
   翻代码发现 daemon 的 createSession RPC 早就接受 cwd
   参数 (R97-M), 但 JS wrapper 把它丢掉了. R197 把参数
   接上. 教训: **"API 存在但没人用"** 是常见的技术债
6. **重复点击同个 CWD 不应该 churn session id** —— 用户
   可能在 dialog 里取消重选, 选了同一个目录. R197
   short-circuit 这个 case, 不发 RPC
