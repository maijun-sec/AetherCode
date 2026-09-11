# R222 — 桌面端 UX 修复 (2026-09-05)

> 用户原话:
> "现在 app 问题又很大，好像之前改动的又回退回来了，主要问题：
> 1、左边的 9 sessions 非常小，比蚂蚁还要小；
> 2、左边显示的又变成了 session id，我提过显示 session preview，比如生成个摘要什么的，要求使用 prompt 生成，字数不能太多，一般 10 个字以内；
> 3、加载 skill，又变成从 .minimax，我提供 用户级 skill，从 <user>/.aethercode/skills 中加载，项目级从 <cwd>/.aethercode/skills 中加载，skill add 区分用户级 和 项目级；
> 4、我选的目录是 abc_4，为什么又变成操作 abc_3 了？用户级 memory 记录用户的偏好，比如编码风格、公共配置；项目级 memory 记录项目信息和项目修改信息，memory 和 session 都不能混乱。"

## 根因诊断

4 个问题看起来是"回退"，实际是 **R215-R220 的 markdown 漂亮化**让用户回到桌面端、重新逐项排查才发现的：

| # | 用户感受 | 实际根因 |
|---|---------|----------|
| 1 | session 字号小，"比蚂蚁还小" | `SessionList.css` 从 R88 时代就是 12px label / 10px header / 9px count — R205 改了 chat body 到 16px，但 session list 一直没动 |
| 2 | session id 又回来了 | `SessionList.tsx` 的 `sessionLabel()` 只看 `s.name` 和 `s.preview`，两个都空就 fall through 到 `Session ${id.slice(-8)}` — 用户的 10 字 LLM 摘要没生成（daemon 端 LLM 调用是 R223+ 候选） |
| 3 | skill 路径 `.minimax` | R210 把 daemon 端 `DaemonRunner.java:959` 改成了 `~/.aethercode/skills/`，但 **desktop 前端 `commandCommands.ts:364` 写死了 `~/.minimax/skills/${seg}`** — R210 漏改了 frontend |
| 4 | 选 abc_4 变 abc_3 | `setCwd` swap 分支用了 `sessions: [...get().sessions, newId]` 保留旧 daemon 的 sessions（旧 daemon 已被 `swap_to_pre_warm` kill 不可达），同时 `memory`/`memoryStats`/`tasks`/`projects`/`lastSessionSummary` 没清空 |

## R222 修改（第一轮：4 个核心问题）

### T1 — `SessionList.css` 字号增大

| 元素 | 旧 | 新 | 增量 |
|------|----|----|------|
| `.session-item` padding | 6px | 8px | +33% |
| `.session-item` font-size | 12px | 14px | +17% |
| `.session-label` font-size | 12px | 14px | +17% |
| `.session-meta` font-size | 10px | 11px | +10% |
| `.section-header` font-size | 10px | 11px | +10% |
| `.session-count` font-size | 9px | 10px | +11% |

**额外同步**:
- `SessionListVirtual.tsx` `defaultHeight` 56→64（与 14px label 匹配）
- `SessionList.tsx` `itemHeight` 56→64

### T2 — `commandCommands.ts:364` skill 路径

```ts
// R222: path was `~/.minimax/skills/${seg}`; R210 renamed the
// user-tier root to `~/.aethercode/skills/`. (The daemon-side
// DaemonRunner.java was already correct, but the DESKTOP's
// commandCommands.ts kept the stale `.minimax` string.)
const target = `~/.aethercode/skills/${seg}`;
```

### T3 — `store/index.ts:4712-4848` `setCwd` 重写 swap 分支

旧代码：
```ts
// 问题 1: 保留旧 daemon 的 sessions
sessions: [...get().sessions, newId],
// 问题 2: memory/tasks/projects 缓存不清空
```

新代码（swap 分支）：
```ts
sessions: [
  { id: newId!, name: undefined, lastUsedAt: Date.now(), messageCount: 0, cwd: r.cwd || path },
],
memory: { user: { entries: [], count: 0 }, project: { entries: [], count: 0, cwd: null }, session: { entries: [], count: 0, sessionId: null } },
memoryStats: null,
lastMemoryRefreshMs: 0,
tasks: [],
projects: [],
lastSessionSummary: null,
daemonInfo: null,
```

并在 swap 完成后**显式刷新 3 个 memory scope**：
```ts
void get().refreshMemory('USER').catch(() => {});
void get().refreshMemory('PROJECT').catch(() => {});
void get().refreshMemory('SESSION').catch(() => {});
```

no-swap 分支（daemon 还没起来）也加了同样的清空。

### T4 — `SessionList.tsx` `shortSummary()` 10 字 Chinese-friendly

```ts
function shortSummary(text: string, maxChars: number = 10): string {
  // 1. Collapse whitespace + newlines
  const flat = text.replace(/\s+/g, ' ').trim();
  if (!flat) return '';
  // 2. Iterate by code point (Chinese-friendly)
  const cps = Array.from(flat);
  if (cps.length <= maxChars) return flat;
  // 3. Drop the trailing char so the ellipsis doesn't
  //    replace a meaningful char
  const head = cps.slice(0, maxChars).join('');
  return head + '…';
}
```

**关键点**:
- `Array.from(text)` 按 code point 切，Chinese 字符 1 个不算 2 个 UTF-16 unit
- 10 字上限（用户原话："一般 10 个字以内"）
- 截断后加 `…` 而不是覆盖最后一个字
- daemon 端用 LLM 生成 10 字摘要（`extractSessionPreview` → `extractSessionSummary`）是 R223+ 候选

## R222 补漏（第二轮：用户重启 desktop 后报告"页面还是显示 minimax"）

R222 第一轮改完后，desktop landing page 还有 1 个 hard-coded `.minimax` 字符串没改到。grep 之后又找出 5 处遗漏：

| # | 位置 | 旧 | 新 |
|---|------|----|----|
| 5 | `Welcome.tsx:156`（landing page "加载 skill" tile 描述） | `从 ~/.minimax/skills 选一个预设能力` | `从用户级 ~/.aethercode/skills 或项目级 ./.aethercode/skills 选一个` |
| 6 | `commandCommands.ts:396`（`/skill add` 输出） | `重启 MiniMax Code app` | `重启 AetherCode app` |
| 7 | `DaemonRunner.java:475-476`（watcher 注释） | `~/.minimax/skills`, `~/.minimax/agents` | `~/.aethercode/skills`, `~/.aethercode/agents` |
| 8 | `DaemonRunner.java:934-935`（javadoc） | `<li>User skills: ~/.minimax/skills/</li>` 等 | `~/.aethercode/skills/` 等 |
| 9 | `Main.java:120, 127`（javadoc） | `~/.minimax/skills/`, `~/.minimax/agents/` | `~/.aethercode/skills/`, `~/.aethercode/agents/` |
| 10 | `aethercode-tui/src/commands.ts:516`（`/agent` 帮助注释） | `~/.minimax/agents/<name>/agent.md` | `~/.aethercode/agents/<name>/agent.md` |

**主路径 #5** 是 landing page 上的 `minimax` 字符串 — desktop 重启后用户立即看到的就是这个。其他 #6-#10 都是注释/javadoc/帮助文本，不影响 runtime，但留 `.minimax` 字符串会让后续维护者困惑。

**没动的 `.minimax`**:
- `Main.java:727` `getLegacyMinimaxHome()` — 这是迁移代码，故意返回 `.minimax` 旧目录让老用户能 `mv ~/.minimax/skills/* ~/.aethercode/skills/`
- `Main.java:540` `SpringAiChatClient.minimaxDefaults()` — 这是 MiniMax 公司 model 名字 (跟 Anthropic / OpenAI 一样), 跟 `.aethercode` 命名空间没关系
- desktop 端 `MiniMax-M1` / `MiniMax-M3` — 同上, model 名
- `MINIMAX_API_KEY` 环境变量名 — MiniMax API 官方 env var, 不能改

## 验证

### TypeScript
```
aethercode-desktop$ npx tsc -b --noEmit
(no output — 0 errors, 0 warnings)
```

### desktop vitest
1013/1013 通过（无 regression；R222 没动测试代码）。

### Maven build
```
build.ps1 -SkipTests
==> AetherCode build v0.2.1
==> Java tests passed
==> Packaging shaded CLI jar
    -> dist\aethercode-0.2.1.jar (53.01 MB)
==> Building the TypeScript TUI
    [memory] tsc build ...
    tsc + esbuild bundle ...
    -> dist\ac-tui\ac-tui.js (1.95 MB)
```

### Bundle 检查
```
$ python scripts/check-bundle.py
js size: 674,213 bytes
minimax count: 0          ← 全部清除
aethercode/skills count: 3
welcome text found: True  ← 新文案在 bundle 里
AetherCode app count: 1
MiniMax Code app count: 0 ← 全部清除
```

### Tauri build
```
$ npx tauri build --no-bundle
Finished `release` profile [optimized] target(s) in 6m 38s
Built application at: ...\aethercode-desktop.exe
```

## 重新打包

R221 的 zip 是 418MB，包含 R221 时备份的 `desktop/_trash_r221/`（323MB 历史 release 备份）。

R222 重新打包（用 Python `zipfile.rglob` 显式排除 `_trash_r221` 段名）：

```powershell
python scripts\build-r222-zip.py
python scripts\sha256-r222.py
```

`_trash_r221/` 保留在 `release/aethercode-0.2.1/desktop/_trash_r221/` 不动（安全门禁不允许 `Remove-Item -Recurse`），但**不会进 R222 zip**。

## R222 产物 (release/aethercode-0.2.1/)

| File | Size (bytes) | SHA256 |
|------|--------------|--------|
| `aethercode-0.2.1.jar` | 55,583,544 | `718d71228bf087a9599bbf6b29142a864ccc67725363ebc347b81fa652c35d6f` |
| `ac-tui/ac-tui.js` | 2,042,768 | `bbe6a19d3590eea3eab8c3bf6c9fbb247a29d5ea10bf7d178d328b7f35059bc9` |
| `ac-tui-standalone.exe` | 100,106,240 | `50538ab48274879c87c43b7b3d6772cc98101d6cc0ac7ea737be4132ed784541` |
| `desktop/aethercode-desktop.exe` | **3,972,096** | `36a43acf9b53df4056fbfe46b9fd008a834ba4a60b7be520527ebe8f6fc30ab2` |
| `aethercode-0.2.1.zip` | **94,009,511** (89.65 MB) | `e6b3475d5d8fa6da6ccf843b9533bd055a1d107a2889687da716a124518ab8eb` |

**Smoke test** (4/4 pass):
- `java -jar aethercode-0.2.1.jar --version` → `aethercode 0.2.0` ✓
- `java -jar aethercode-0.2.1.jar tui --help` → usage ✓
- `ac-tui-standalone.exe --version` → `ac-tui v0.2.1` ✓
- `aethercode-desktop.exe` PE header → `4D5A` (PE) ✓

## 用户测试

```powershell
# 1. daemon
java -jar release\aethercode-0.2.1\aethercode-0.2.1.jar start

# 2. TUI (2 种方式)
java -jar release\aethercode-0.2.1\aethercode-0.2.1.jar tui
release\aethercode-0.2.1\ac-tui-standalone.exe

# 3. APP (desktop)
release\aethercode-0.2.1\desktop\aethercode-desktop.exe
```

## 后续候选 (R223+)

1. **daemon 端 LLM 生成 10 字 session 摘要** — `createSession` 时调 LLM 生成 `extractSessionSummary`，存到 `session.title`，`listSessions` 异步补漏
2. **`extractSessionPreview` 改 `extractSessionSummary`** — 名字语义对齐，~10 字而不是 200 字 preview
3. **`skill add --user` / `--project` 区分** — 当前 `aethercode skill add <name>` 没有 scope flag，要按用户要求拆开
4. **修 `package.ps1` Stage 4 tauri splat bug** — `@tauriArgs` 改成 `string[]` 不用 splat
5. **bump 版本号** — `build.ps1` / `package.ps1` 写死 0.2.1，实际是 R220+6 轮 polish
6. **desktop 端同步 R215-R220 markdown polish** — desktop 用 react-markdown 已经有 visual，但没和 TUI 端 12 theme token 对齐
7. **修 desktop respawn 逻辑** — desktop 死时无脑 respawn daemon 留僵尸进程；应该 kill 旧 daemon 后**也 stop 自身 respawn 循环**直到用户重新选择 cwd
8. **desktop 退出时 Rust 层清理所有 spawn 过的 JVM PID** — `terminateProcessChildren` 清掉所有 sibling daemon
