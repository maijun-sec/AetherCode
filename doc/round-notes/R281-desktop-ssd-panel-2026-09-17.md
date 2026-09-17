# R281 — desktop SSD panel + InteractiveRepl JSONL protocol

> 2026-09-17 — desktop home for `aethercode ssd --interactive`
>
> 用户原话："按建议实现，能不能将 ssd 编排成 TODO 列表，等一个阶段完成
> （比如详细需求分析），给个可选项，比如 A 是已完成，继续下一步，B 是
> 需要修改，然后给个框让输入要修改的内容，随后再处理，每轮处理完成后，
> 都要有这个确认信息，每轮都有。这样可能比在输入框中使用 /ssd 要好一点儿"
>
> 落地：daemon 端先做了 InteractiveRepl（JSONL wire protocol），
> desktop 端做一个全屏 SDD panel，挂在 SettingsPage 第 4 个 tab。

---

## 设计

### UX（用户看到的层级）

```
┌─ Spec-Driven Development ────────────────────────────┐
│                                       [Quit]  [×]   │
├──────────────────────────────────────────────────────┤
│ Feature slug:  [demo-feature            ]             │
│ Intent:        [Add a per-user timezone ...]         │
│                [▶ Run demo SSD flow (mock driver) ]  │
├──────────────────────────────────────────────────────┤
│ ● Spec       running…                                │
│ ◐ Design     awaiting your input                     │
│ ○ Tasks      queued                                  │
│ ○ Implement  queued                                  │
├──────────────────────────────────────────────────────┤
│ Design — review                                      │
│ /tmp/.../design.md         full body loaded          │
│ ┌────────────────────────────────────────────────┐   │
│ │ # Design                                       │   │
│ │ Architecture overview …                        │   │
│ │                                                │   │
│ │ ## NFR-3 Audit logging                         │   │
│ └────────────────────────────────────────────────┘   │
│ [✓ Accept & continue]  [↷ Skip phase]                │
│ ───────────────────────────────────────              │
│ Modify — paste revisions, then click Apply:          │
│ ┌────────────────────────────────────────────────┐   │
│ │ add NFR-3 about audit logging                  │   │
│ └────────────────────────────────────────────────┘   │
│ [Apply Revision]                                     │
└──────────────────────────────────────────────────────┘
```

每阶段 daemon 把 draft 写盘 → 发 `phase-draft` event（带 preview 4KB +
path）→ 用户 review → 用户选 ✓ Accept / ✎ Modify → 发回 inbound command
→ daemon 写下一阶段 → 循环。

### Wire Protocol (JSONL newline-delimited)

**Outbound events** (daemon stdout → desktop):
```json
{"event": "phase-list", "feature": "...", "phases": [{"id": "spec", "order": 1, "title": "Spec"}]}
{"event": "phase-start", "phase": "spec", "order": 1, "title": "Spec"}
{"event": "phase-draft", "phase": "spec", "path": ".../spec.md", "bytes": 4321, "preview": "# Spec\n..."}
{"event": "phase-revising", "phase": "spec", "revision": "add NFR-3"}
{"event": "phase-draft", "phase": "spec", "path": ".../spec.md", "bytes": 5120}
{"event": "phase-accepted", "phase": "spec", "revisionCount": 1}
{"event": "complete", "feature": "...", "results": [{"phaseId": "spec", "path": "...", "revisions": 1}]}
{"event": "abort", "reason": "user-quit"}
{"event": "error", "message": "..."}
{"event": "log", "level": "info", "message": "..."}
```

**Inbound commands** (desktop stdin ← daemon):
```json
{"action": "accept"}
{"action": "revise", "text": "add NFR-3 about audit logging"}
{"action": "skip"}
{"action": "quit"}
```

EOF on stdin → treat as `quit`（driver disconnect 优雅退出）。

---

## 实现

### Daemon side (Java)

**新文件**:
- `aethercode-workflows/src/main/java/org/aethercode/workflows/ssd/InteractiveRepl.java`
  (14,225 B; JSONL wire protocol driver)
- `aethercode-workflows/src/test/java/org/aethercode/workflows/ssd/InteractiveReplR281Test.java`
  (8 tests: phase-list emit, accept/revise/skip/quit, EOF on stdin, malformed
  JSON ignored, preview truncated at 4 KB, complete event with results)

**修改**:
- `SsdRunner.java`: 加 `recordRevision(phaseId, text)` helper；`runWithRepl()`
  每个 phase 进入前 emit `phase-start`；recordRevision accept 后写回 SsdRun record
- `SsdCommand.java`: 加 `--interactive` / `-i` flag（与 `--auto` 互斥）；
  使用 `InteractiveRepl` 替换默认 `ConfirmPrompt` REPL

**协议契约**:
- preview 4 KB cap（8 KB char limit）防 event stream 膨胀
- `recordRevision` 必须在 `revise` command 被 daemon accept 后才调
- error event 在 interactive mode 下走 stderr，让 UI 在 subprocess exit code 1 之前收到
- `Optional.empty()` = accept，`Optional.of(text)` = revise，`null` = abort
  复用 SsdRunner 现有 ReplFn 接口

### Desktop side (TypeScript / React)

**新文件**:
- `aethercode-desktop/src/components/ssd/SsdPanel.tsx` (14,726 B;
  main panel with TODO chips, artifact preview pane, accept/modify bar,
  complete/abort/error panes, log details)
- `aethercode-desktop/src/components/ssd/SsdPanel.css` (7,191 B; styling)
- `aethercode-desktop/src/components/ssd/driver.ts` (6,309 B;
  `SsdDriver` interface + `MockSsdDriver` impl)
- `aethercode-desktop/src/components/ssd/__tests__/SsdPanelR281.test.tsx`
  (14 tests: source-pin invariants + jsdom render via MockSsdDriver)
- `aethercode-desktop/src/pages/SettingsPage.css` (6,314 B;
  4-tab layout: permissions/models/workflows/sdd)

**修改**:
- `pages/SettingsPage.tsx`:
  - 加 `sdd` tab（4th tab after workflows）
  - `SddTab` 组件：feature slug input + intent textarea + Run demo button
    + `<SsdPanel>` mount with MockSsdDriver
  - 加 `initialTab?: Tab` prop，让路由可以默认打开某个 tab
  - 复用 MockSsdDriver 跑 canned 4-phase event sequence
- `App.tsx`: 加 `/settings/sdd` route，传 `initialTab="sdd"` 让用户直
  达 SDD panel
- `pages/__tests__/SettingsPage.test.tsx`: 加 3 SDD 相关 test（4 tabs、
  initialTab prop、SDD tab mounts SsdPanel）

### Driver abstraction

`SsdDriver` interface 5 个方法：
```typescript
interface SsdDriver {
  onEvent(handler: (ev: SsdDriverEvent) => void): () => void;
  fetchDraft(path: string): Promise<string>;
  sendCommand(cmd: SsdInboundCommand): void;
  start(): void;
  stop(): void;
}
```

两个实现共享同接口：
- `MockSsdDriver` — 测试 + dev mode demo 用（in-memory queue, setTimeout(0) emit）
- (R282 next) `TauriSsdDriver` — `@tauri-apps/plugin-shell` 真子进程

UI 代码一份，prod/test 两边通吃。

---

## 测试

### Daemon
- `InteractiveReplR281Test`: 8/8 pass
  - emitPhaseList / confirm_accept / confirm_revise / confirm_quit /
    confirm_eofOnStdin / confirm_ignoresMalformedJson /
    previewTruncatedAt4kb / emitComplete
- `SsdRunnerTest`: 17/17 pass（regression clean）
- `SsdConfigTest`: 12/12 pass（regression clean）
- Total: 37/37 workflows pass

### Desktop
- `SsdPanelR281.test.tsx`: 14/14 pass
  - source-pin: SsdPanel.tsx + driver.ts + SsdPanel.css exist
  - driver.ts declares 11 event kinds + 4 inbound commands
  - SsdPanel.tsx imports SsdPanel.css
  - jsdom render: empty state, 4 chips, accept sends command, revise sends
    command with text, Apply Revision disabled when textarea empty,
    Quit sends quit, complete event → completion pane
  - MockSsdDriver: registerDraft / fetchDraft fallback / sendCommand after stop no-op
- `SettingsPage.test.tsx`: 13/13 pass（was 11 + 3 new SDD tests, replaced
  the old "renders the three tabs" test with the four-tab test）
- Total: 1136/1136 desktop pass（含 14 R281 新增 + 3 SettingsPage 加测）

### Jar bytecode verify
`verify_jar_r277_fix.py` 扩展到 16 checks（3 R277 + 8 R280 + 5 R281），全部 pass:
- R281 InteractiveRepl.class: `phase-list` / `phase-draft` / `revise` literals
- R281 SsdRunner.class: `recordRevision` 调用点
- R281 SsdCommand.class: `interactive` flag literal

---

## 部署

### jar
- `aethercode-cli/target/aethercode-cli-0.1.0-SNAPSHOT.jar`
  → 56,606,861 bytes (R281 build)
- 复制到 `release/aethercode-0.2.70/desktop/aethercode.jar`
  与 `release/aethercode-0.2.70/aethercode-0.2.70.jar`

### desktop exe
- Tauri rebuild `cargo build --release` (2m 46s)
- `aethercode-desktop/src-tauri/target/release/aethercode-desktop.exe`
  → 4,161,024 bytes（比 R280 的 5,179,904 小 1 MB —
  之前 size 大概是 NSIS installer wrapper 不是 raw binary，2026-09-11 R268
  之后换了 build flow）
- 复制到 `release/aethercode-0.2.70/desktop/aethercode-desktop.exe`
- 旧 R280 exe 保留为 `.exe.old` 防 rollback

### zip
- `release/aethercode-0.2.70.zip`
- SHA `F382FCCE418777DB6EA10D5EEC890D29AA8D8592BC009B813193C902CBC9E2CA`
- size 110,392,421 bytes
- vs R280 (`60E6ECB9…`, 108,441,908 bytes): +1,950,513 bytes
  - 新 desktop exe (+4,161,024 vs 旧 +5,179,904 实际小 1 MB)
  - 新 jar (R281 +6,861 bytes)
  - .old exe (R280 backup, 5,179,904 bytes)

---

## 用户交互流程 (demo mode)

1. 用户打开 Settings → SDD tab（或直接 `/settings/sdd` 路由）
2. 输入 feature slug (默认 `demo-feature`)
3. 输入 intent (默认 "Add a per-user timezone setting …")
4. 点 ▶ Run demo SSD flow
5. SsdPanel mount, MockSsdDriver 发射 canned 4-phase event sequence:
   - `phase-list` → 4 chips render
   - `phase-start: spec` → spec chip 变 running
   - `phase-draft: spec` → preview pane 可见 + Accept/Skip/Modify bar
   - 用户选 ✓ Accept → driver.sendCommand({action:'accept'})
   - phase chip 变 ✓ done spec
   - 同样的 design / tasks 阶段
   - 最后 `complete` event → completion summary pane
6. 用户可以关 panel（× 按钮）

---

## 关键决定

1. **MockSsdDriver 让 UI 与 daemon 解耦** — driver interface 抽象
   让 mock（test + dev demo）和 Tauri subprocess（prod）共享接口，
   UI 一份代码两边通吃
2. **JSONL newline-delimited 是 SSD wire protocol 的好选型** —
   容错强（单行坏不影响其他），parser trivial（BufferedReader.readLine +
   Jackson.readValue），test 简单（ByteArrayInputStream mock）
3. **preview 4 KB cap 防 event stream 膨胀** — full body 在 disk，
   driver 自己 fetch（`fetchDraft(path)`），desktop 走 Tauri daemon
   static-serve，test 走 in-memory read
4. **SsdRunner ReplFn 契约复用** — 已有 `Optional.empty()` = accept，
   `Optional.of(text)` = revise，`null` = abort，InteractiveRepl 实现即可，
   不用改 SsdRunner 核心 loop
5. **tab 状态 vs URL** — SettingsPage 把 `initialTab?: Tab` 作为 prop，
   路由决定首次打开哪个 tab，但用户可以在 tab 之间切换（component-local state）
6. **abort vs skip 区分** — `quit` action → emit `abort` event（用户中断）；
   `skip` action → emit `phase-skipped`（罕见 case：已 accept 但想跳过）
7. **Apply Revision disabled when empty** — 防止用户点空 revision 把
   daemon state 推到 unclear 状态

---

## 后续 R282 计划

- **TauriSsdDriver 真实 subprocess** — `@tauri-apps/plugin-shell`
  spawn `java -jar aethercode.jar ssd <feature> "<intent>" --interactive
  --cwd <cwd>`，stdin/stdout JSONL stream，capabilities allowlist 配置
- **Save & Resume** — 把 SDD run state 写盘，desktop restart 后能续跑
- **Spec Kit 8 阶段对齐** — R281 还是我们的 4 阶段 spec/design/tasks/dev，
  R282 可以加 constitution/clarify/checklist/analyze 等 Spec Kit 阶段
  （用户原话："有个开源的 ssd，很经典" → GitHub Spec Kit 108k ⭐）

---

## 文件清单

### New
- `D:\work\workspace\idea\engine\AetherCode\aethercode\aethercode-workflows\src\main\java\org\aethercode\workflows\ssd\InteractiveRepl.java` (14,225 B)
- `D:\work\workspace\idea\engine\AetherCode\aethercode\aethercode-workflows\src\test\java\org\aethercode\workflows\ssd\InteractiveReplR281Test.java` (8 tests)
- `D:\work\workspace\idea\engine\AetherCode\aethercode-desktop\src\components\ssd\SsdPanel.tsx` (14,726 B)
- `D:\work\workspace\idea\engine\AetherCode\aethercode-desktop\src\components\ssd\SsdPanel.css` (7,191 B)
- `D:\work\workspace\idea\engine\AetherCode\aethercode-desktop\src\components\ssd\driver.ts` (6,309 B)
- `D:\work\workspace\idea\engine\AetherCode\aethercode-desktop\src\components\ssd\__tests__\SsdPanelR281.test.tsx` (14 tests)
- `D:\work\workspace\idea\engine\AetherCode\aethercode-desktop\src\pages\SettingsPage.css` (6,314 B)
- `D:\work\workspace\idea\engine\AetherCode\doc\round-notes\R281-desktop-ssd-panel-2026-09-17.md`

### Modified
- `D:\work\workspace\idea\engine\AetherCode\aethercode\aethercode-workflows\src\main\java\org\aethercode\workflows\ssd\SsdRunner.java` (recordRevision + emit phase-start)
- `D:\work\workspace\idea\engine\AetherCode\aethercode\aethercode-cli\src\main\java\org\aethercode\cli\SsdCommand.java` (--interactive flag)
- `D:\work\workspace\idea\engine\AetherCode\aethercode-desktop\src\App.tsx` (`/settings/sdd` route)
- `D:\work\workspace\idea\engine\AetherCode\aethercode-desktop\src\pages\SettingsPage.tsx` (sdd tab + SddTab)
- `D:\work\workspace\idea\engine\AetherCode\aethercode-desktop\src\pages\__tests__\SettingsPage.test.tsx` (+3 SDD tests)
- `D:\work\workspace\idea\engine\AetherCode\scripts\verify_jar_r277_fix.py` (+5 R281 markers)

### Deployed (release/aethercode-0.2.70/)
- `desktop/aethercode.jar` — 56,606,861 bytes (R281)
- `desktop/aethercode-desktop.exe` — 4,161,024 bytes (R281)
- `aethercode-0.2.70.jar` — 56,606,861 bytes (R281)
- `desktop/aethercode-desktop.exe.old` — 5,179,904 bytes (R280 backup)

### Zip
- `release/aethercode-0.2.70.zip`
- SHA `F382FCCE418777DB6EA10D5EEC890D29AA8D8592BC009B813193C902CBC9E2CA`
- size 110,392,421 bytes (vs R280 108,441,908)