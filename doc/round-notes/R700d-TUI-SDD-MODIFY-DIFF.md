# R700d — TUI SDD modify flow with diff viewer + file:// link

> **基线**: 2026-09-29 (after R700c commit `82aa03b`)
> **前置**: R700/R700c 已把 SDD 全链路跑通（daemon + desktop + TUI RPC）。剩下 TUI 的 modify 反馈 + artefact review 体验。
> **Rounds**: R700d (this) + R700e (cross-machine pack verify).

---

## 这是什么

R700c 实现了 `/sdd-modify <single-line feedback>` 但有两个真实的体验 gap：

1. **看不到改了啥** — 用户提了修改意见，agent 重跑 phase 写完新 artefact，用户在 TUI 里看不到 before/after。
2. **没法直接打开文件** — TUI 没有 file tree，只有 banner 上的路径文字，用户想看全文必须切回外部编辑器。

R700d 加 4 个 slash command 解决这两个：

- `/sdd-view [N]` — 渲染当前 phase 的 artefact 前 60 行 + 一个可点击的 `file://` URI（iTerm2 / WezTerm / Windows Terminal 直接 Ctrl-click 跳转默认编辑器）。
- `/sdd-snapshot [N]` — 把当前 artefact 的内容快照进 `state.sddCache[N]`，作为后续 diff 的 baseline。
- `/sdd-diff [N]` — 渲染 snapshot vs 当前 on-disk artefact 的 line-by-line diff（`-` 红 / `+` 绿 / ` ` 原样）。
- `/sdd-clear-cache` — 清空 cache + 关闭 viewer。

设计决策（来自 R700d 计划）：

- **LCS-free line walker**：O(N+M) 确定性 diff，artefact 都很短（constitution ~50 行 / design.md ~150），LCS 复杂度不划算。
- **没有 multi-line editor**：原本计划加 `\` 行继续符，但 R700d 评估发现 multi-line feedback 在 Ink 里收益不大（用户编辑大段内容会用 `/sdd-modify` 之外的方式）—— 所以保留了 single-line `/sdd-modify <feedback>`，通过 `/sdd-snapshot` + `/sdd-diff` 提供 review workflow。
- **不在 state.ts 加 `sddReview` 字段**：viewer 是 ephemeral 的，本地 `useState` 就够了，避免 reducer 膨胀。

## 范围

### state.ts
- 新增 reducer actions: `sddCache.set` (phase + lines) / `sddCache.clear`
- 新增 State 字段: `sddCache: Record<number, string[]>` (initial `{}`)
- 移除之前重复的 case blocks（lines 1971-1981）

### commands.ts
- 新增 `parsePhase(rest: string[]): number | null | undefined` — 解析 `[phase]` 参数（1-8），无参返回 undefined（fallback 到 run.currentPhase）
- 新增 4 个 slash case: `sdd-view` / `sdd-snapshot` / `sdd-diff` / `sdd-clear-cache` → 返回 `__SDD_VIEW__` / `__SDD_SNAPSHOT__` / `__SDD_DIFF__` / `__SDD_CLEAR_CACHE__` token + rpcParams
- SLASH_HELP / SLASH_COMMANDS / SLASH_COMMANDS_DETAILED 三个表格都加新命令

### components/SddReview.tsx (NEW)
- `SddReview` 组件：cyan header + yellow `file://` link + body 行（diff 模式时 `-/+` 配色）
- `renderDiff` (LCS-free line walker) + `renderPlain` + `padLineNumber`
- 复用 `SddArtefactLink` from SddMode.tsx（already exports）

### tui.tsx
- `import { SddReview } from "./components/SddReview.js"`
- 新增 `useState<{path; lines; previousLines} | null>(null)` for ephemeral viewer
- 新增 4 个 token dispatch handler（`__SDD_VIEW__` / `__SDD_SNAPSHOT__` / `__SDD_DIFF__` 复用一段共用逻辑：`fs.readFileSync(path).split(/\r?\n/)`）
- 新增 `__SDD_CLEAR_CACHE__` handler：dispatch `sddCache.clear` + `setSddReview(null)`
- 渲染分支挂在 `<SddMode>` 之后（`{sddReview ? <SddReview .../> : null}`）

## 不变量

1. **LLM 调只在 daemon 端** ✓ — viewer 纯本地渲染，零 LLM round-trip
2. **Tool 调只在 daemon 端** ✓ — TUI 只 `fs.readFileSync(path)`，文件路径从 `sddRun.phases[N].path` 直接拿（daemon 已经 emit）
3. **Desktop/TUI 从不读 SKILL.md** ✓ — artefact 文件由 phase 处理器写在 `<cwd>/.aethercode/sdd/<slug>/<file>`，TUI 只读产物，不读模板
4. **Use Edit, NOT PowerShell Set-Content** ✓ — 本 round 0 个 PowerShell 文件写入，全部 Edit/Write tool

## 构建 & 测试

```powershell
cd D:/work/workspace/idea/engine/AetherCode/aethercode-tui
npm run build                                                    # tsc + bundle
node --test scripts/test/state.test.mjs                          # 13/13 pass
Get-ChildItem scripts/test -Filter '*.test.mjs' | ForEach-Object { node --test $_.FullName }
```

结果：build 通过（剩 1 个 legacy `budget` case 重复 warning —— 已有 issue 与 R700d 无关）。state.test.mjs 13/13 pass，其余 mjs 测试 fail=0。

## 使用方法

```bash
# 在 TUI 里：
/sdd <intent>                  # 启动 SDD run (e.g. /sdd implement a CLI to merge CSVs)
/sdd-status                    # 看当前 phase
/sdd-modify <feedback>         # 提修改意见（single-line, e.g. /sdd add a --strict flag）
/sdd-approve                   # 接受当前 phase
/sdd-skip                      # 跳过 optional phase
/sdd-abort                     # 整个 run 终止

# R700d 新增：
/sdd-view [N]                  # 渲染 phase N 的 artefact 前 60 行 + file:// link（Ctrl-click 在 iTerm2/WT/WezTerm 里打开）
/sdd-snapshot [N]              # 把当前 artefact 缓存到 sddCache[N]（作为 diff baseline）
/sdd-diff [N]                  # 渲染 snapshot vs on-disk artefact 的 line-by-line diff
/ssdd-clear-cache              # 清空 cache + 关闭 viewer
```

## 验证方法

1. **本地 TUI 端到端**：
   ```bash
   # Terminal A:
   cd D:/work/workspace/idea/engine/AetherCode/aethercode && mvn -pl aethercode-sdd,aethercode-cli -am package -DskipTests
   java -jar aethercode-cli/target/aethercode-cli.jar

   # Terminal B:
   cd D:/work/workspace/idea/engine/AetherCode/aethercode-tui
   node dist/ac-tui.js

   # TUI 输入：
   /sdd a small refactor (e.g. extract helper)
   # 等待 phase 1 跑完
   /sdd-view                  # 应该看到 constitution.md 前 60 行 + file:// 链接
   # 用 iTerm2/Windows Terminal Ctrl-click 跳转
   /sdd-snapshot              # 缓存 phase 1 的当前内容
   /sdd-modify add a max-lines = 1000 guard
   /sdd-approve                # agent 重新生成
   /sdd-diff                   # 应该看到 +max-lines 那一行绿色 + 周围 1-2 行红色
   ```

2. **Desktop 端验证**：相同 RPC 已通，desktop 的 `SendSddCommand('view')` 等按钮同样工作。

3. **回归测试**：57 个 SDD desktop tests + 13 个 tui state tests + 38 个 tui r34-r42 tests 应该全 pass。

## 后续

- R700e — 跨机器 pack 验证：复制 daemon jar + tui bundle 到干净机器（无 `.minimax` 目录），跑 `/sdd-test` smoke，确认 SDD 跑得通。
- 长期 — 当 SDD artefact 超过 200 行（不太可能）时考虑用真正的 LCS / Myers diff 替换现在的 line walker。