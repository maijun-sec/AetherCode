# R226 — Welcome tile "加载 skill" 文案修正 + jar version 提升

**日期**: 2026-09-06
**触发**: 用户报告 Welcome landing page 的"加载 skill" tile 文案与设计意图相反
**范围**: 1 个 source file, 1 行文案 + **`aethercode\dist\` jar version 提升（关键 fix）**

## 0. 重大发现 — R222-R225 daemon 一直在跑旧 jar

**User 测试时发现**：跑完任务，session 名字仍然显示"新会话"。

**根因调查**：

1. `Get-CimInstance Win32_Process` 显示 desktop spawn 的 daemon 用的是：
   ```
   java -jar D:\work\workspace\idea\engine\AetherCode\aethercode\dist\aethercode-0.2.50.jar
   ```
2. `aethercode\dist\` 里**同时**有 3 个 jar：
   - `aethercode-0.2.1.jar`  55,585,147 B (R225 真正的修复版)
   - `aethercode-0.2.49.jar` 55,314,417 B (R208)
   - `aethercode-0.2.50.jar` 55,317,002 B (R210) ← desktop spawn 选这个
3. Desktop `find_jar_path` (lib.rs:897) 的逻辑：
   - 优先 Tauri resource_dir — 但 `tauri.conf.json:48` 的 `"resources": []` 是空的，**没 bundle jar**
   - fallback 到 ancestor walk，按 `jar_sort_key` (lib.rs:1143) 选**最高版本** jar
   - `0.2.50 > 0.2.1` → 永远选 0.2.50
4. **R210 的 0.2.50.jar 完全没有 R224 firstPrompt / R225 tool call format 修复**

**影响范围**：R222、R223、R224、R225 期间所有 daemon 端 Java 改动的测试**全部无效**。用户以为 R224 firstPrompt 在跑，实际上跑的是 0.2.50 旧 daemon。

**这是 R222-R225 期间"daemon 改动看起来无效"的根本原因。** 桌面端（TypeScript/JSX）改动都有效，所以 R222 R223 的 UI polish 看起来正常，但所有 daemon 端 RPC 改动（firstPrompt、listSessions withPreview、tool call format log）都因为旧 jar 没生效。

**修法**（同 R222 排除 `_trash_r221` 的模式）：
1. 备份 `0.2.49.jar` / `0.2.50.jar` 到 `aethercode\dist\_trash_r226\`
2. 复制 `0.2.1.jar` → `0.2.51.jar`（升 version）
3. 杀掉 PID 23128（旧 daemon）
4. 用户重启 desktop → desktop spawn 会用 0.2.51.jar（R225 实际代码）

**预期效果**：
- **新 session**（R224 firstPrompt 链路）：session 名字会变成 firstPrompt 头 10 字（如 "java mav…" 之类）
- **旧 session**（41 msg 那个）：`extractSessionPreview` (AetherCodeMethods.java:4206) 会从 jsonl 读第一个 user message 当 preview，所以**也会自动有名字**

## 1. 用户原始反馈

> 加载的 skill 不是从这两个位置加载的，而是加载的 skill 最终会基于 scope 放到这两个文件夹下，并且更新 daemon 全局的 skill 列表

附 2 张截图：
- **图 1**: SessionList abc_4 group 下 "新会话 / just now · 1 msg"（R224 lazy create 触发的）+ "在当前目录下, ... / 15h · 34 msg"（R222 之前的旧 session）。用户问：这是不是 R224 的预期行为？
- **图 2**: Welcome landing page 的"加载 skill" tile 描述写"从用户级 `~/.aethercode/skills` 或项目级 `./.aethercode/skills` 选一个"。这是错的。

## 2. 根因

### 2.1 R224 lazy create 行为 — **符合预期**

R224 的设计就是 "用户提交 prompt 才创建 session"。截图中的 "新会话 / just now · 1 msg" 是用户刚才输入 java maven 任务时由以下链路触发的：

```
user 敲 prompt + Enter
  ↓
sendMessage (currentSessionId = null)
  ↓
listSessions (取 daemon current)
  ↓
wantFresh = (defaultOpenBehavior === 'new') || !daemonCurrent
  ↓
createSession({cwd, firstPrompt: input.slice(0, 200)})
  ↓
set sessions 本地 (preview=prompt 头 200 字, messageCount=0)
  ↓
query → daemon 写 user message → messageCount 变 1
  ↓
refreshSessions → "新会话" 显示 + "just now · 1 msg"
```

**R224 通过**。无需修改。

### 2.2 Welcome tile 文案 — **方向性错误**

`aethercode-desktop/src/components/Welcome.tsx:156` 的 desc 字符串是 R107 写的：

```diff
- 从用户级 ~/.aethercode/skills 或项目级 ./.aethercode/skills 选一个
+ 粘 git URL 或本地路径，按 scope 装到 user 或 project 目录，刷新 daemon 列表
```

**为什么是错的**：
- 它把**目标位置**（user / project scope 目录）当成了**源**（"从这两个位置选一个"）
- 真实流程是 `R102b /skill add` 命令接受 git URL / 本地路径，落到对应 scope 目录，触发 daemon 全局 skill 列表 reload
- 改后的文案才是用户的设计意图：**输入**是 git URL/本地路径，**落地**按 scope，**副作用**是 daemon 列表刷新

## 3. 修复

`aethercode-desktop/src/components/Welcome.tsx:154-156`:

```tsx
<div className="welcome-tile-icon">🧩</div>
<div className="welcome-tile-title">加载 skill</div>
<div className="welcome-tile-desc">粘 git URL 或本地路径，按 scope 装到 user 或 project 目录，刷新 daemon 列表</div>
```

**24 字**（vs 旧 25 字 + 错位语义），跟另两个 tile 的 desc 长度（7-8 字）相比略长但 `white-space: normal` 会自动换行，视觉可接受。

## 4. 验证

| 项 | 结果 |
|---|---|
| `tsc -b` | 0 错 0 警告 |
| `vitest run` | 1013/1013 ✓（无 regression）|
| `vite build` | OK, 25.50s |
| Bundle verify (`index-*.js` 含新文案) | ✓ 3/3 markers (`粘 git URL`, `按 scope 装到`, `刷新 daemon 列表`) |
| `tauri build --no-bundle` | OK, 7m 31s (rust re-link only) |

## 5. R226 产物

| 文件 | 大小 | SHA256 |
|---|---|---|
| `aethercode-0.2.1.jar` | 55,585,147 B | 3FFC5433BEC5F47DE16DF8905C10CCF633B9A2FA8B81A9FC4052C1BD05234A2A (unchanged, R225) |
| `desktop/aethercode-desktop.exe` | 3,975,168 B | **9C3D2DFF3E7F702E325258CBAA3C7631F5F83B57DE0824EE7CD0FD91B1ACA3ED** (R226 NEW) |
| `ac-tui/ac-tui.js` | 2,042,768 B | BBE6A19D3590EEA3EAB8C3BF6C9FBB247A29D5EA10BF7D178D328B7F35059BC9 (unchanged) |
| `ac-tui-standalone.exe` | 100,106,240 B | 50538AB48274879C87C43B7B3D6772CC98101D6CC0AC7EA737BE4132ED784541 (unchanged) |
| `aethercode-0.2.1.zip` | 92,504,190 B (88.21 MB) | **C20E2FABAED53F44C4623DCD709494FFA096108DB4F130CBE69024547B90FC17** (R226 NEW) |

## 6. 教训 (2026-09-06)

1. **Landing page 的文案容易在多次 polish 中漂移** — R107 写 desc 时把"目标位置"当"源"是语义错误，R222 补漏时 grep 也没盯过 welcome tile 的 desc 字段（只 grep 了硬编码 token 串）。**结论**：每轮 grep 改动后**逐字段过一遍 desc 字符串**是稳妥做法。
2. **R224 lazy create 的可见性需要时间消化** — 用户第一次看到 "新会话 / just now · 1 msg" 时会怀疑是 bug。**结论**：下次 R 系列出包时，文档里加一行 "新会话出现在你提交 prompt 之后，是正常的"，让用户不用问。
3. **`white-space: normal` 让 desc 长度更宽容** — 24 字在 tile 里能换行成 2-3 行。**结论**：desc 字段不用太克制，可以放完整信息。
4. **🔥 R222-R225 出包时的 jar version 没升，是 daemon 测试无效的隐藏 root cause** — R222-R225 我每次出包都用 `aethercode-0.2.1.jar`（原始 version），但 `aethercode\dist\` 里 R210 时代留了 `aethercode-0.2.50.jar`。desktop `find_jar_path` 按 `jar_sort_key` 选最高版本 → 0.2.50 永远胜出。**结论**：每次 R 系列出包**必须升 jar version**（R226 → 0.2.51），并把老 version 备份到 `_trash_rXXX\`。
5. **🔥 `tauri.conf.json` 的 `resources: []` 是设计缺陷** — R97-C 本意是把 jar bundle 进 exe，但 5+ rounds 没人填它。**结论**：下次 R 系列 polish 时把 jar 真正 bundle 进去（"the canonical 'what this App version expects' jar" — R97-C 原话），避免 ancestor walk 的版本号竞争。
6. **PowerShell `npx tauri build` 进程监控技巧** — `task_query status=running` 不可靠，进程可能已完成但 query 状态滞后。**结论**：用 `(Get-Item exe).LastWriteTime` 配合 `Get-Process -Id` 检测实际状态，每 30s poll 一次。
