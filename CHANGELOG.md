# Changelog

All notable changes to AetherCode are recorded here. Versions
follow [semantic versioning](https://semver.org/) starting at
0.2.x (the 0.2 line is the "feature freeze" pre-1.0 line; 0.3.x
is the cross-cutting refactor that introduced the 5-module
split).

## [Unreleased] — Phase 6 cross-cutting round 2

### Added (T-500..T-510)

- **New TS modules** (T-001..T-455): `aethercode-memory`,
  `aethercode-compact`, `aethercode-themes`. Each ships with
  its own `package.json`, `tsconfig.json`, vitest config, and
  `src/__tests__/`. 223 memory tests + 51 compact tests +
  ~40 themes tests all green.
- **New RPC surface** (T-500, design.md §5.4): 32 namespaced
  methods across `memory/*`, `compact/*`, `theme/*`,
  `context/*`, `task/*`. Java handlers live in
  `org.aethercode.protocol.methods.{Memory,Compact,Theme,Context,Task}Methods`;
  TS handlers in each module's `rpc.ts`. Pinned by
  `CrossCuttingMethodsT500Test` (16 tests).
- **TS security helper** (T-507): `aethercode-memory/src/security/permissions.ts`
  — `chmodOwnerReadWriteOnly` / `chmodOwnerReadWriteOnlyAsync` /
  `chmodDirectoryOwnerOnlyAsync`. POSIX-only 0600; best-effort
  no-op on Windows. Wired into `jsonl-writer.ts` (session log,
  tail-recovery) and `memory-store.ts` (`ensureProjectFile`,
  `ensureGlobalFile`). 6 vitest specs cover both platforms.
- **TUI components** (T-432, design.md §5.3): `ThemePicker`,
  `MemoryPanel`, `TaskPanel`, `SubagentPanel`, `PermissionModal`,
  `NotificationCenter`, `NotificationDetail`, `NotificationSettings`,
  `UpdateAvailable`, `UpdateConfirm`, `UpdateProgress`, `EffortPicker`,
  `PlanList`, `TokenChart`, `ContextMeter`, `McpLogin`,
  `McpReconnect`, `McpViewer`, `LogViewer`, `Welcome`, `WelcomeBanner`,
  `AgentSelector`, `CwdSwitcher`, `ThreadSelector`, `HelpOverlay`,
  `ConsentPrompt`. All bundled into `dist/ac-tui.js`.
- **Performance benchmarks** (T-505, T-506):
  - `scripts/bench-tui.mjs` — synthetic StatusBar / MemoryPanel /
    TaskPanel render over 200 ticks. **Budget: p99 < 16 ms.**
  - `scripts/bench-llm.mjs` — MiniMax-class round-trip simulation.
    **Budget: p99 < 4000 ms for a 4K-token reply.**
  - `scripts/bench.mjs` — unified entry; fails on regression.
  - Wired into `package.json` as `pnpm bench` / `pnpm bench:tui` /
    `pnpm bench:llm`.
- **CI workflows** (T-503, T-504):
  - `.github/workflows/build.yml` — Java Maven multi-module
    compile + pnpm TS build/typecheck. Per-language matrix;
    concurrency-cancels on push race.
  - `.github/workflows/test.yml` — full Surefire suite (with
    per-method pinning for R124 contract, T-500 surface,
    Permission + Task registration) + pnpm vitest + TUI
    node-test + Surefire-report artefact.
  - Both workflows cache Maven + pnpm; install with
    `pnpm@9.12.0` (pinned).
- **E2E smoke test** (Phase 6 final):
  - `scripts/smoke-test.mjs` — 30+ checks: workspace modules,
    build artefacts, RPC method registration, TUI components,
    security helper, perf benchmarks, root config files.
  - POSIX wrapper `scripts/smoke-test.sh`, PowerShell wrapper
    `scripts/smoke-test.ps1`.
  - Wired as `pnpm smoke` / `pnpm smoke:bash` / `pnpm smoke:ps1`.
- **`.gitignore`** (T-508): excludes `.aethercode/sessions/*.jsonl`,
  `.aethercode/sessions.db*`, `.aethercode/grants.json*`,
  `.aethercode/grants.log.jsonl`, `.aethercode/theme.json`,
  `.aethercode/font.yaml`, per-module `target/`, `dist/`,
  `tmp-*/`, plus the usual Node / IDE / OS noise.
- **Documentation**:
  - `README.md` — new quick-start commands (`pnpm smoke`,
    `pnpm bench`, `ac-mem` CLI), Java CLI subcommand table,
    updated 0.3.0 section.
  - `CHANGELOG.md` (this file) — round-by-round release notes.

### Changed

- `package.json` (root) — adds `pnpm smoke`, `pnpm smoke:bash`,
  `pnpm smoke:ps1`, `pnpm bench` scripts.
- `aethercode-protocol` — registers the 32 namespaced methods
  (R500+). Per-method tests under
  `src/test/java/org/aethercode/protocol/methods/`.
- `aethercode-permission` — new `grants/` package with
  `GrantsService`, `GrantsStore`, `SecureFilePermissions`.
- `aethercode-tasks` — new `asyncsub/`, `lifecycle/`,
  `limits/`, `streaming/`, `supervisor/` sub-packages plus
  `PersistentTaskRegistry` (T-369).

### Fixed

- `jsonl-writer.ts` — `recoverTruncatedTail` rewrites the file
  without re-applying 0600. Fixed in this round.
- `jsonl-writer.ts` — `appendJsonlLine` did not enforce 0600
  on subsequent appends. Fixed in this round.
- `memory-store.ts` — `ensureProjectFile` / `ensureGlobalFile`
  left newly created files with the process umask. Fixed
  in this round.
- `SecureFilePermissions` (Java) — same fix mirrored in
  `aethercode-permission/grants/SecureFilePermissions.java`.

## [0.2.63] — R250 release (O-10 公网安全化: bearer token + TLS)

R247 (bearer-token 鉴权) + R248 (TLS via HttpsServer + PKCS#12) 完成
**defence-in-depth 公网安全配对**。**2 round 全部 < 1 round, 累计 244 Java + 513 TS
tests pass, 0 回归**。

### R247 Bearer-Token 鉴权

- `BankServer` 构造器接 `String authToken` (R247 加), 4 参 canonical
  constructor 防 Java 歧义
- `authed(HttpHandler)` 装饰器包 5 个 `/bank/*` handler
- `/healthz` 永远开放 (LB probe), Bearer prefix + 裸 token 都接受
- `constantTimeEquals` 防 timing attack
- 10 Java test (unauth/open, missing/wrong/correct/blank token, /healthz skip, etc)

### R248 TLS (HttpsServer + PKCS#12)

- `startTLS(port, keystore, pass)` overload (String + char[])
- `Transport` enum 暴露 HTTP / HTTPS 状态
- `installContexts()` / `defaultExecutor()` 共享给 HTTP/HTTPS 启动路径
- `startBankServer` helper 按 env 自动选 HTTP vs HTTPS
- 4 Java test (transport flag, wiring, env-driven selection)

### env vars 新增

| Env | 默认 | 作用 |
|---|---|---|
| `AETHERCODE_BANK_TOKEN` | (unset) | R247 bearer token, 不设 = unauthenticated |
| `AETHERCODE_BANK_TLS_KEYSTORE` | (unset) | R248 PKCS#12 keystore 路径, 不设 = HTTP |
| `AETHERCODE_BANK_TLS_PASS` | (空) | R248 keystore 密码 |

### O-10 跨 surface bank 公网安全化阶段

- ✅ R244.2 server 暴露 (5 endpoints, JDK HttpServer)
- ✅ R244.3 TS client library
- ✅ R245.1-5 5 round TUI 真正接通
- ✅ R247 bearer-token 鉴权 (env AETHERCODE_BANK_TOKEN)
- ✅ R248 TLS (env AETHERCODE_BANK_TLS_KEYSTORE)
- ⏳ R249 Desktop Rust BankClient (code complete, build deferred)
- 📋 R250+ Tauri command 桥接
- 📋 R250+ Survey 论文 R238.1 第 3 轮

### 报告

- `doc/项目文档/R247-BANK-SERVER-AUTH.md`
- `doc/项目文档/R248-BANK-SERVER-TLS.md`
- `doc/项目文档/R249-DESKTOP-RUST-BANK-CLIENT.md` (code only, build verify deferred)

## [0.2.62] — R245 release (O-3 + O-6 + O-10 接桥 5 round)

R245 在 R244 server 暴露 + TS client 基础上,把"跨 surface bank"做到
**user 真正能感知 + 真正能反馈**。**5 round 全部 < 1 round, 累计 230 Java + 513 TS
tests pass, 0 回归**。

### R245.1 O-10 真正接通 (TUI 端)

- `aethercode-memory/src/bank-recall.ts` (TUI wrapper, 165 行)
- 2 slash commands: `/bank-stats` + `/bank-recall <kind> [n]`
- `vi.hoisted` mock bank-client, 16 vitest

### R245.2 O-6 + R230 接通 (MemoryAudit)

- `aethercode-memory/src/self-eval-audit.ts` (270 行)
- `unitConfidence` 跟 R244.1 Java Laplace K=1 公式严格一致
- `aggregateReport` pure function: per-kind 成功率 + Laplace avg confidence
- `auditSelfEval` 鸭子类型 dispatch, `/memory-audit` slash command
- 19 vitest

### R245.3 R241.3 接桥 (Periodic Decay)

- `DecayScheduler.java` (140 行) — `ScheduledExecutorService` 单线程 daemon
- 失败隔离 (catch `Throwable`), `AutoCloseable` try-with-resources
- env knob `DEEPAGENTS_TALON_DECAY_INTERVAL_MIN` (默认 60 min, 0 = off)
- 10 Java test

### R245.4 R243.3 + R244.1 接桥 (DRIFT 排序对齐)

- `Drift.java` 排序: `utility desc` → `confidence × utility desc`
- 跟 `BankRecallMiddleware` 排序严格一致 (避免 AGENTS.md 跟 recall 撕裂)
- 2 新 test (高 utility 0 obs 输给低 utility 高 obs, tie uses desc)

### R245.5 O-10 启动可见性 (Welcome Banner)

- `Welcome.tsx` + `bankStatus?: string` prop + dimColor + icon.dot
- `tui.tsx` useState + useEffect fire-and-forget `readBankStats()`
- 0 新 test (UI integration 跟 R61 /export 一致)

### env vars 新增

| Env | 默认 | 作用 |
|---|---|---|
| `DEEPAGENTS_TALON_DECAY_INTERVAL_MIN` | 60 | R245.3 decay tick 间隔 (0 = off) |
| `AETHERCODE_BANK_URL` | 127.0.0.1:7777 | R245.1 TUI 端 base URL |

### O-3 / O-6 / O-10 完整闭环

| O | 阶段 | 状态 |
|---|------|------|
| O-3 经验库 | bank / DRIFT / recall 三处排序一致 | ✅ |
| O-3 daemon 周期 decay | R245.3 启 scheduler | ✅ |
| O-6 self-eval metric | R244.1 + R245.2 audit 报告 | ✅ |
| O-10 跨 surface HTTP | R244.2 server | ✅ |
| O-10 TUI 真正接通 | R245.1 + R245.5 default visible | ✅ |

### 报告

- `doc/项目文档/R245-1-TUI-BANK-INTEGRATION.md`
- `doc/项目文档/R245-2-MEMORY-AUDIT-SELF-EVAL.md`
- `doc/项目文档/R245-3-PERIODIC-DECAY-SCHEDULER.md`
- `doc/项目文档/R245-4-CONFIDENCE-AWARE-DRIFT.md`
- `doc/项目文档/R245-5-WELCOME-BANK-STATUS.md`

## [0.2.61] — R245 release (R239 Capability Gap Analysis 闭环)

R239 路线图全部 14 个 round 收口 (R240..R244.3),5+9 round 全部 < 1 round 完成。
**累计 218 Java + 478 TS tests pass, 0 回归。**

### O-3 ExperienceStore → 策略库 (R241.2 / R241.3 / R243.1 / R243.2 / R243.2B / R243.3)

- `ReasoningBank` + `ReasoningUnit` (10-field record) + `SelfReflectMiddleware`
  + `BankRecallMiddleware`: 失败反思 → 提取 errorPattern/fixStrategy/example
  → 入 bank; 成功 task 前 recall top-K → 注入 system prompt。
- `BankStorage` interface + `InMemoryBankStorage` (默认) +
  `JsonFileBankStorage` (per-file JSON 持久化)
- `UtilityDecay` interface + `ExponentialDecay(halfLife)` + `LinearDecay(perDay)`
- `BankGrowthPolicy` interface + 3 实现 (NoGrowthCap / UtilityBasedEviction /
  LruEviction), 默认 LRU cap = 1000
- `TalonSelfReflectWiring` 静态工厂 + `DeepAgentRuntime.start()` 自动接通
- 4 个 middleware (SelfReflect + SuccessReflect + BankRecall + SelfEval)
  真正注入 `CreateDeepAgent.createDeepAgent` 参数 4
- `Drift.runOnce` 静态方法: bank → AGENTS.md, marker block 保护, idempotent
- 修了 5+ talon stale imports (R230-R242 累积)

### O-4 ToT (R240.2) — Tree-of-Thought Middleware

3 strategies: BFS / Beam / Best-First, K 个候选 plan LLM 评分选 best。

### O-5 用户面 (R240.1) — TaskLimits

`TaskLimits` 用户可配置 (maxConcurrent / maxTokens / maxDuration),
engine 强制执行,用户 API 暴露。

### O-6 Self-Eval (R244.1) — Binary Self-Eval + Confidence-Weighted Recall

- `SelfEvalClassifier.heuristic()` 默认 (no exception = ok, 零成本)
- `SelfEvalMiddleware` wrapToolCall 后对 recall 列表 unit 调 `recordOutcome`
- `ReasoningUnit` +2 字段 (okCount/notOkCount) + `confidence()` Laplace-smoothed
- 排序公式升级: `utility → confidence × utility`

### O-7 VLM (R242.1) — image_understand

`image_understand` tool: 接收 base64 image + prompt, 调 MiniMax VLM 拿描述。
3 个 model preset, env opt-in。

### O-9 RoleRegistry (R242.2)

`RoleRegistry` + 6 个内置 role, 每个 role 有 system prompt + 工具白名单 +
token 预算。

### O-10 Cross-Surface Bank (R244.2 / R244.3)

- Java 端: `BankServer` (JDK HttpServer) 5 endpoints + `BankClient` HTTP client
- TS 端: `aethercode-memory/src/bank-client.ts` 镜像 Java client shape
- `DEEPAGENTS_TALON_EXPOSE_BANK=true` opt-in 启 server, 默认 port 7777
- 6 methods + 404 双语义 + `AbortSignal.timeout` + `coerceUnit` 防御

### New Java module

- `aethercode-a2a` (R241.1): Google A2A 协议最小可用实现, agent card +
  JSON-RPC over stdio/HTTP + task lifecycle。Host 应用可选装/卸。

### env vars 新增

| Env | 默认 | 作用 |
|---|---|---|
| `DEEPAGENTS_TALON_SELFREFLECT` | (off) | opt-out 全部 O-3 wiring |
| `DEEPAGENTS_TALON_BANK_DECAY_DAYS` | 7 | exponential half-life |
| `DEEPAGENTS_TALON_BANK_MAX` | 1000 | LRU cap |
| `DEEPAGENTS_TALON_EXPOSE_BANK` | (off) | R244.2 启 BankServer |
| `DEEPAGENTS_TALON_BANK_PORT` | 7777 | R244.2 server 端口 |

### 报告

- `doc/项目文档/R241-3-PERSISTENCE-UTILITY-DECAY.md`
- `doc/项目文档/R243-1-SUCCESS-REFLECTION-AND-GROWTH-CAP.md`
- `doc/项目文档/R243-2-TALON-WIRE-READY.md`
- `doc/项目文档/R243-2B-TALON-WIRE-ACTIVE.md`
- `doc/项目文档/R243-3-DRIFT-BANK-TO-AGENTS.md`
- `doc/项目文档/R244-1-SELF-EVAL-CONFIDENCE-METRIC.md`
- `doc/项目文档/R244-2-CROSS-SURFACE-BANK-HTTP.md`
- `doc/项目文档/R244-3-TS-BANK-CLIENT.md`

## [0.2.1] — Phase 1..5 (R0..R200)

- Initial public release. Java core + TS TUI + desktop app.
- 18 Java sub-modules under `aethercode/`. Single TS package
  (`aethercode-tui`). 27 RPC methods.
- Pre-Phase-6 work; see `git log` for the per-round detail.

---

### Versioning policy

- `0.2.x` — pre-Phase-6 line. Frozen at 0.2.1. Bug-fix only.
- `0.3.x` — Phase 6 cross-cutting refactor. Breaking for
  in-tree module layout; JSON-RPC surface is forward-compatible.
- `0.4.x` and beyond — public API stabilisation; CLI flags
  become part of the contract.

### Release cadence

Phase 6 is shipped as a single 0.3.0 cut. Subsequent rounds
in Phase 6 (T-503..T-510) are aggregated into 0.3.x point
releases on a roughly weekly cadence.
