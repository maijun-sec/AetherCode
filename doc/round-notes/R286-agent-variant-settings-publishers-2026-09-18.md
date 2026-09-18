# R286 — agent.md `variant:` field + `AETHERCODE_SUBAGENT_VARIANT` env override + Settings "Show all providers" + per-model pricing

**触发**: 用户原话 "**请认真评估 claude code、opencode 等 AI Agent 的模型选择方法**" + R285 lesson 607 留下的三个 deferred 项。

R285 只让用户在 MessageInput 手动设 quality preset,作用在所有 sub-agent。R286 让每个 agent 可以独立 quality pick (类似 claude-code `agent.md` 顶层 frontmatter),以及支持 env-driven default (类似 opencode `OPENCODE_VARIANT`)。加上 Settings panel 的 "Show all providers" toggle (R282 follow-up,complaint: "你配置的很多模型都是我没办法用的") + per-model pricing summary (用户不知道 `0.0007/1k` 是什么意思)。

**核心改动 (R286)**:
- **`AgentRegistry.AgentMeta`** 加 `String variant` field;`reload()` 解析 `variant:` frontmatter;`writeAgentMd` 写 `variant:` line (blank/null 时 omit 整个 line,保留 legacy "missing field = default" 契约)
- **`AgentRegistry.create/update`** 加 `variant` 参数 (5-arg → 6-arg);round-trip 测试覆盖 create→reload→update→reload 路径
- **`AetherCodeMethods.writeAgent`** 加 variant 参数;empty string → null → 不写 frontmatter line
- **`AetherCodeMethods.listAgents` + `getAgentBody`** response 加 `variant` field (空字符串表示 "inherit from env")
- **`AetherCodeEngine.resolveVariantName(String)`** 静态 helper (Variant.byName 走 case-insensitive lookup,返回 canonical name 或 null)
- **`AetherCodeEngine.Builder.subagentVariant(String)`** 显式 setter,优先于 env override
- **AETHERCODE_SUBAGENT_VARIANT env override**: constructor 在 boot 时读 env var → resolveVariantName → 设 currentVariant;unknown name log warning + fallback to default
- **desktop `SettingsPanel`**:
  - "Show all providers" toggle (默认关,filter hasApiKey=false 镜像 R282 MessageInput)
  - localStorage 持久化 toggle 状态 (`aethercode.settings.showAllProviders`)
  - provider 下拉加 `(no API key)` badge 当 toggle 开 + hasApiKey=false
  - count hint 行 "showing X configured · Y hidden (no API key)"
  - 每个 model option 加 `· $X/M in, $Y/M out` annotation (inputPer1k × 1000 = per-million)
  - provider 级别 min..max cost band hint
- **desktop `AgentEditor`**:
  - 加 Quality preset dropdown (low / medium / high / xhigh + inherit 空选项)
  - variant state 加 `useState(initial?.variant ?? '')`
  - onSave 把 variant.trim() 传给 createAgent/updateAgent
  - 加 `data-testid="agent-editor-variant"`
- **desktop `AgentsPanel.AgentEditorLazy`** prefill 加 `variant: r.variant ?? ''`
- **desktop `lib/methods.ts`**: `createAgent` / `updateAgent` opts 加 `variant?: string`;`getAgentBody` response shape 加 `variant?: string`;通过 `as unknown as Promise<...>` cast 避免 caller TS error
- **desktop `store/index.ts`**: `getAgentBody` interface 加 variant 字段

**Tests**:
- daemon: `AgentRegistryR286Test` 5 tests (variantFieldParsedFromFrontmatter / missingVariantFieldStaysEmpty / variantFieldRoundTripsThroughCreateAndUpdate / blankVariantStringOmitsTheFrontmatterLine / listSurfacesVariantField)
- daemon: `AetherCodeEngineR286Test` 13 tests (resolveVariantName null/blank/bundled/aliases/unknown/trim/case-insensitive + Builder.subagentVariant seeds/aliases/unknown/blank/no-override/trims)
- daemon: `AetherCodeMethodsR286Test` 5 tests (listAgentsSurfacesVariantField / getAgentBodySurfacesVariantField / createAgentWritesVariantToFrontmatter / updateAgentChangesVariantField / blankVariantOmittedFromFrontmatter)
- daemon: `AgentRegistryTest` 14 tests (R286 update — 7 个 create/update call site 改 6-arg)
- daemon full: 24 modules pass (11:11 min)
- desktop: `SettingsPanelR286.test.ts` 7 tests (regex contract — show-all toggle + pricing 持久化 + cost band)
- desktop: `AgentEditorR286.test.ts` 10 tests (regex contract — variant dropdown + initial props + AgentsPanel prefill + methods.ts shape)
- desktop full: 1170/1170 (+17 R286: 7 SettingsPanel + 10 AgentEditor)

**Deploy**:
- jar: 56,630,295 B (R286, +852 vs R285)
- exe: 4,161,024 B (unchanged — no desktop shell changes)
- zip: SHA `9931EA5DA92E080D0ED77D6B3AD6A82D6A8008129EE48D409EC7D84FF890C459`, 112,374,761 B (+1,744 vs R285)
- 54/54 bytecode markers (3 R277 + 8 R280 + 5 R281 + 4 R282 + 11 R283 + 8 R284 + 10 R285 + 5 R286)

**Commit chain (累积 71 R-round)**:
```
14d0df7 R285 (model variants + /model y:y + Quality pills) ← pushed
   ↓
TBD   R286 (agent variant + AETHERCODE_SUBAGENT_VARIANT + Show all providers + pricing) — daemon + RPC + desktop done
```

**关键技术决定 (8 条)**:
1. **variant: blank string omits frontmatter line** — writeAgentMd 只在 variant 非 blank 时 append `variant: <name>` 行;legacy agent.md (无 variant line) 用 "missing field = inherit" 路径
2. **resolveVariantName 是 static helper** — 不需要 engine 实例,测试不用 reflection-mutate env (Java 21 ProcessEnvironment.theUnmodifiableEnvironment 没法 mutate)
3. **AETHERCODE_SUBAGENT_VARIANT env + Builder.subagentVariant(String) 优先级** — explicit builder setter wins;env var 是 fallback
4. **unknown variant 不抛异常** — log warning + currentVariant = null → getActiveVariant() 返回 Variant.DEFAULT;typo 不 break daemon
5. **Show all providers toggle 默认关 + localStorage 持久化** — 镜像 R282 MessageInput filter,fix 用户 reload 后 toggle 重置的 bug
7. **per-model pricing 用 inputPer1k × 1000** — 行业标准 $/M (per-million tokens);`0.0007/1k` = `$0.70/M`
8. **provider-level cost band 显示 min..max** — multi-model provider 让用户知道 range 而不只单点

**关键调试技巧 (新增 608-609)**:
608. **Java 21 ProcessEnvironment.theUnmodifiableEnvironment** — System.getenv() 读的是 unmodifiable wrapper,不是 theEnvironment;reflection mutate theEnvironment 不影响后续读。需要 `--add-opens java.base/java.lang=ALL-UNNAMED` 才能 mutate (但 still 不 work 因为 theUnmodifiableEnvironment 是独立 wrapper);最简洁解决方案:把 env-var resolution 抽成 static helper,测试不依赖 env mutation
609. **tsc `as unknown as Promise<...>` for new field on existing RPC return type** — `this.call('getAgentBody', ...)` 是 generic in T,加上 variant 字段后必须 cast `as unknown as Promise<{...extended shape...}>` 才能让 callers 看到 variant

**教训 (新增 608-609)**:
608. **ProcessEnvironment 在 Java 21 是不可 reflection-mutate 的** — 不要在 test 里 mutate env var;抽成 static helper 让测试覆盖逻辑;env-var integration 留给 daemon-level 测试 (jar 启动时设 env)
609. **store 接口的 getAgentBody 也要更新** — AetherCodeMethods 改了 wire shape,但 store AppState 的 getAgentBody 签名没同步,导致 AgentsPanel 引用 `r.variant` 失败;每个 RPC 都要 store interface + methods.ts + daemon 三方同步更新

**后续 R287+ 计划**:
- **R287**: Tauri shell plugin driver for SsdPanel — R281 MockSsdDriver 替代物;真实 subprocess wiring
- **R288**: providers.yaml in-app editor (Settings panel 加 YAML edit tab,save → reloadProviders) + live env-var refresh button (`refreshEnvVars` → 重新读 System.getenv 然后 refreshProviders)
- **R289**: Settings "Show all providers" toggle 反向 — 让 "Show only configured" 也能关掉 (current default)