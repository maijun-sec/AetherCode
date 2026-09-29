# R700 — SDD 产品集成 (daemon bundle + TUI CLI mode)

> **基线**: 2026-09-29
> **状态**: planning
> **前置**: R694 (desktop jar + RPC), R312 (R312 daemon SDD 已删)

---

## 这是什么

把 SDD 从"借用 Mavis agent 的 sdd skill"**升级为 AetherCode 自带产品能力**：

1. **Skill bundle 内置到 daemon jar** — 跟产品走，不依赖外部 `.minimax` 路径
2. **新增 `aethercode-sdd` maven 模块** — SddOrchestrator + RPC + 资源加载
3. **删除 desktop 端硬编码** — `store/index.ts:154` 的 `SDD_SKILL_BUNDLE_DIR` 走人
5. **TUI 命令行 SDD mode** — `/sdd <intent>` 启动 + `/sdd-modify` 等子命令

## 范围

### In scope

- 新建 `aethercode/aethercode-sdd/` maven 模块
- 内置 sdd skill bundle (SKILL.md + 8 phase refs + 5 templates) 到 jar 资源
- 新增 SddOrchestrator / SddBundleLoader / SddPhaseState
- 新增 5 个 RPC: `sdd.start` / `sdd.advance` / `sdd.status` / `sdd.abort` / `sdd.listRuns`
- 改 desktop 端 store + SddPhaseBar: 删硬编码 + 改走 RPC
- TUI 端 commands.ts + state.ts + SddMode.tsx + SddReview.tsx

### Out of scope

- 不放 `~/.aethercode/skills/` (用户明确反对)
- 不改现有 `engine.skillRegistry()` (避免影响其他 skill)
- 不动 Mavis 那份 sdd skill (Mavis 仍独立可用)

---

## 架构

```
desktop (React)                    TUI (Ink / CLI)                daemon (Java)
───────────────                    ──────────────                  ───────────
MessageInput                       SddMode.tsx
   │                                  │
   │ startSsdFlow()                   │ /sdd <intent>
   │   ├─ RPC sdd.start() ────────────┼──────────────────────────► SddOrchestrator
   │   │                                                       ┌─ SddBundleLoader
   │   │                                                       │  (jar 内 resources/skills/sdd/)
   │   │                                                       ├─ 读 phase-state.json
   │   │                                                       ├─ <phase-state.json> 写盘
   │   │                                                       └─ ChatClient.stream(...)
   │   │ ◄─────────────────────── chat event stream ──────────────────┐
   │   │                                                            │
   │   │ ◄──── RPC sdd.status ──── phase state 快照 ─────────────────┤
   │                                                                  │
   │   SddPhaseBar 更新                                              │
   │                                                                  │
   │ sendSsdCommand('approve')                                       │
   │   ├─ RPC sdd.advance({action: 'approve'}) ──────────────────────┘
   │   │
   │   └─ 同上的 approve/modify/skip 循环
```

**关键不变**:
1. **LLM 调只在 daemon 端** (ChatClient 在 daemon)
2. **工具调用只在 daemon 端** (write_file / read_file 等 agent tool 都在 daemon)
3. **desktop/TUI 只发 RPC + 渲染状态**

---

## 模块设计: aethercode-sdd

```
aethercode/aethercode-sdd/
├── pom.xml                                    ← parent + dep: aethercode-core
├── src/main/java/org/aethercode/sdd/
│   ├── SddOrchestrator.java                   ← phase state machine
│   ├── SddBundleLoader.java                   ← 启动时读 jar 内 resources/skills/sdd/
│   ├── SddPhaseState.java                     ← record(slug, currentPhase, phases[])
│   ├── SddPhaseSpec.java                      ← const PHASE_IDS / OPTIONAL / OUTPUT_FILES
│   └── SddException.java
├── src/main/resources/skills/sdd/             ← 内置 skill bundle
│   ├── SKILL.md
│   ├── references/
│   │   ├── phase-1-constitution.md
│   │   ├── phase-2-specify.md
│   │   ├── phase-3-clarify.md
│   │   ├── phase-4-plan.md
│   │   ├── phase-5-analyze.md
│   │   ├── phase-6-tasks.md
│   │   ├── phase-7-implement.md
│   │   ├── phase-8-converge.md
│   │   ├── phase-protocol.md
│   │   ├── upstream-credits.md
│   │   └── templates/
│   │       ├── constitution-template.md
│   │       ├── specify-template.md
│   │       ├── plan-template.md
│   │       ├── tasks-template.md
│   │       └── _fallback.md
└── src/test/java/org/aethercode/sdd/
    ├── SddOrchestratorTest.java               ← mock ChatClient, 测 phase 推进
    ├── SddBundleLoaderTest.java               ← 测 jar 资源加载
    └── SddPhaseStateTest.java                 ← 测 JSON 持久化
```

---

## RPC 接口

| Method | Params | Return | 说明 |
|---|---|---|---|
| `sdd.start` | `{ intent, cwd }` | `{ slug, phase:1, status:'running' }` | 写 phase-state.json (phase 1: running, 其他: idle)，返回 slug |
| `sdd.advance` | `{ slug, action, feedback? }` | `{ status, currentPhase, phaseState }` | approve/modify/skip/abort，分发到 SddOrchestrator |
| `sdd.status` | `{ slug }` | `SddPhaseState` JSON | 读 phase-state.json，供 desktop SddPhaseBar 订阅 |
| `sdd.abort` | `{ slug }` | `{ status: 'aborted' }` | 中止 run，写 abort 标记 |
| `sdd.listRuns` | `{ cwd }` | `[{ slug, status, currentPhase, startedAt }]` | 列出当前 cwd 下所有 SDD run |

action 取值:
- `approve` — 当前 phase 标 done，进入下一 phase (REQUIRED phase 不允许 skip)
- `modify` — 当前 phase 标 running + 用 feedback 重跑
- `skip` — 当前 phase 标 skipped (仅 OPTIONAL phase 允许)
- `abort` — 整个 run 标 aborted

---

## Desktop 端改动

### 删除 (store/index.ts)

```ts
// line 154
const SDD_SKILL_BUNDLE_DIR = 'C:/Users/maijun/.minimax/agents/mavis/skills/sdd';
```

### 删除 (buildPhasePrompt)

```ts
// line 211-218
let skillBody = '(unable to read SKILL.md — see path below)';
let phaseRefBody = '(unable to read phase reference — see path below)';
try {
  skillBody = await invoke<string>('read_text_file', { path: sddSkillPath() });
} catch { /* keep fallback */ }
try {
  phaseRefBody = await invoke<string>('read_text_file', { path: sddPhaseRefPath(opts.phase) });
} catch { /* keep fallback */ }
```

### 简化 buildPhasePrompt

```ts
// after
async function buildPhasePrompt(opts: {
  phase: number;
  slug: string;
  intent: string;
  action: SddAction;
  feedback?: string;
}): Promise<{ full: string; visible: string }> {
  const lang = detectIntentLanguage(opts.intent);
  const langLabel = lang === 'zh' ? '中文' : 'en';
  return {
    full: `[sdd-task: ${opts.slug}, phase: ${opts.phase}, action: ${opts.action}]\n\n## User intent\n${opts.intent}${opts.feedback ? `\n\n## User feedback\n${opts.feedback}` : ''}`,
    visible: `📐 **SDD 模式** · slug: \`${opts.slug}\` · phase ${opts.phase}/${SDD_PHASE_IDS.length} · 产物语言: ${langLabel}\n\n## User intent\n${opts.intent}`,
  };
}
```

### startSsdFlow 改 RPC

```ts
startSsdFlow: async (intent: string) => {
  const cwd = get().cwd ?? '';
  const result = await rpc.sddStart({ intent, cwd });
  // result = { slug, currentPhase: 1 }
  set({
    sddActive: true,
    sddSlug: result.slug,
    sddCurrentPhase: 1,
    sddIntent: intent,
    sddPhases: result.phaseState.phases,
  });
  // 订阅 sdd.status 流
  void get().sendMessage();  // 触发 daemon 跑 phase 1
},
```

### sendSsdCommand 改 RPC

```ts
sendSsdCommand: async (cmd, text) => {
  const result = await rpc.sddAdvance({
    slug: get().sddSlug,
    action: cmd,  // 'approve' | 'modify' | 'skip'
    feedback: text,
  });
  set({
    sddCurrentPhase: result.currentPhase,
    sddPhases: result.phaseState.phases,
  });
  void get().sendMessage();
},
```

---

## TUI 端改动

### Slash commands (commands.ts)

```
/sdd <intent>                  启动 SDD run，进入 SDD mode
/sdd-resume <slug>             resume 已存在的 run
/sdd-abort                     中止当前 run
/sdd-modify <feedback...>      modify 当前 phase
/sdd-skip                       skip 当前 phase
/sdd-quit                       退出 SDD mode（回到普通 chat）
```

### SddMode 状态机 (state.ts)

```ts
type SddMode =
  | { kind: 'idle' }
  | { kind: 'running'; slug: string; phase: number; llmStream?: AsyncIterable<...> }
  | { kind: 'awaiting-action'; slug: string; phase: number; artifact: string; summary: string }
  | { kind: 'reviewing-diff'; slug: string; phase: number; oldContent?: string; newContent: string }
  | { kind: 'aborted'; slug: string };
```

### SddMode.tsx (新组件)

```
┌──────────────────────────────────────────────────┐
│ 📐 SDD · slug:sorting-lib · 3/8 [plan]            │
├──────────────────────────────────────────────────┤
│ ▸ Reading inputs: constitution.md, spec.md       │
│ ▸ Calling LLM ...                                │
│ ▸ Writing design.md ...                          │
│ ✓ Generated: /workspace/design.md                 │
├──────────────────────────────────────────────────┤
│ ✅ 第 4 阶段完成 — 详细设计                       │
│ Summary: Maven multi-module + SortAlgorithm<T>   │
│                                                  │
│ [a]pprove  [m]odify  [s]kip  [v]iew  [q]uit      │
└──────────────────────────────────────────────────┘
```

### SddReview.tsx (modify 后内置 diff viewer)

```
┌──────────────────────────────────────────────────┐
│ 📐 SDD · Modify · phase 4/8                      │
├──────────────────────────────────────────────────┤
│ --- design.md (old)                              │
│ +++ design.md (new)                              │
│ @@                                              │
│ - single-module                    │
│ + multi-module                   │
│                                                  │
│ file:///C:/path/to/design.md    [open in editor] │
│                                                  │
│ [a]pprove  [m]odify again  [q]uit                │
└──────────────────────────────────────────────────┘
```

**diff 渲染**: 用简单行级 diff (unified diff 风格)，不支持 sxs（侧边对比），保持简洁。
**打开文件**: TUI 显示 `file://` 链接，windows 下 click 调用 `start ""` 打开；macOS `open`；linux `xdg-open`。由 TUI host 提供。

---

## 不变量

1. **LLM 调只在 daemon 端** — `SddOrchestrator.stream()` 内部调 `ChatClient.stream()`
2. **工具调用只在 daemon 端** — agent 的 `write_file`, `read_file` 等全在 daemon
3. **desktop/TUI 不读 SKILL.md** — 只发 RPC，daemon 端读 jar 资源
4. **filename 严格小写** — constitution.md / spec.md / design.md / tasks.md / dev.log / convergence.json

---

## 测试计划

### Unit
- `SddBundleLoaderTest`: mock jar 资源，测加载 SKILL.md + phase refs + templates
- `SddPhaseStateTest`: JSON 序列化 / 反序列化 / round-trip
- `SddOrchestratorTest`: mock ChatClient，测 8 phase 推进 / modify 重跑 / abort

### Integration (RPC)
- 启动 daemon，调 `sdd.start` → `sdd.status` → `sdd.advance` 8 次 → `sdd.status` 验证 done

### E2E
- daemon + desktop: 跑 8 phase，验证 .aethercode/sdd/<slug>/ 下 8 个产物文件
- daemon + TUI: 同上 + modify 后 verify 文件被重写

### Packaging
- 把 jar 拷到无 `.minimax` 目录的机器上跑 daemon + desktop → SDD 跑通

---

## 工作量

| Round | 内容 | 估算 |
|---|---|---|
| R700a | Task 1 + Task 2 + Task 3 + Task 5 (daemon + desktop + 测试) | 1.5 round |
| R700b | Task 4 (TUI 端) | 1 round |
| R700c | Task 5.3 跨机器打包验证 + 收尾 | 0.5 round |

---

## 风险

1. **ChatClient.stream() 流式输出**: 现有 RPC 是否支持 stream 流？需要确认 `sendMessage` 的事件流机制。
2. **agent tool pool**: LLM 跑 SDD phase 需要哪些 tools (write_file / read_file / bash)? 需要从现有 ChatClient 调用链确认。
3. **desktop 端 R317-R331 测试**: 改 buildPhasePrompt 之后现有 `startSsdFlowRulesR316.test.ts` 等测试是否会破？要顺手修。
4. **TUI Modify 多行输入**: 当前 TUI 是单行 InputBox，modify 需要 multi-line feedback editor。要么用现有 line editor 多次提交拼接，要么临时切换 modal。

## 后续 round 候选

- Cwe369 Loose* checker CSV mapping 修复
- do-while + 多 syscall IR 适配
- Cwe252/253 unchecked return 漏检
- Cwe191 整数下溢 (Z3)