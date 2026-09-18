# R285 — model variants (low / medium / high / xhigh) + `/model y:y` slash

**触发**: 用户原话 "**每个模型支持的 输入 输出 上下文长度不同，那么压缩的时候，对应的压缩阈值也不同，需要给出不同的配置**" + "**请明确当前是如果进行上下文压缩的，如何存储压缩前后的上下文**" + "**请认真评估 claude code、opencode 等 AI Agent 的模型选择方法**"。

R283 给 CompactConfig 加 per-model `compactAt` / `preserveTail` / `strategy`;R284 给 compact 加 per-session SnapshotStore。但模型选择本身还是单维度 (provider + model)。Claude Code 和 opencode 都有 quality preset (low / medium / high / xhigh) 让用户在一个 model 内调温度 + maxTokens + thinking token。

**核心改动 (R285)**:
- `Variant` record (aethercode-core): 6 字段 (`name` / `description` / `temperature` / `maxTokens` / `reasoningBudget` / `extendedThinking`) + 4 bundled presets (`LOW` 0.3/16K, `MEDIUM` 0.7/32K, `HIGH` 1.0/48K, `XHIGH` 1.0/64K + 8K reasoning + extended thinking) + `DEFAULT` fallback + `byName()` alias resolver (opencode-style: `default/medium/med → MEDIUM`, `low/fast → LOW`, `high/deep → HIGH`, `xhigh → XHIGH`, unknown → null 让 caller 走 fallback chain)
- `VariantSpec` record (aethercode-core): YAML-shape (`nullable Integer compactAt` / `nullable Integer preserveTail` / `nullable String strategy`);`toVariant()` 用 tier default 填 null
- `ModelSpec` 加 8-arg constructor `List<Variant> variants`;null/empty 时 fill bundled default;`variantByName(name)` method
- `ProviderSpec` 加 8-arg constructor `List<Variant> variants`;`variantFor(modelId, name)` 三路 fallback (model → provider → BUILTIN)
- `ProviderRegistry.YAML` 加 `variants` schema:`ProviderYaml.variants` + `ModelYaml.variants` + `VariantYaml.toSpec()` + `VariantYaml.toVariantList()` + `mergeList()` (model wins when non-empty)
- `AetherCodeEngine.setVariant(variant)` (normalize empty→null, trim) + `currentVariant()` accessor + `getActiveVariant()` (engine fields → Variant.DEFAULT fallback);`mainLoopModelName()` 在 model change 时 reset `currentVariant = null` (避免旧 variant 不存在于新 model)
- `AetherCodeMethods`:
  - `currentVariant` volatile field 镜像 engine
  - `switchProvider(Object params)` 接受 optional `variant` 参数,不传保留 engine.currentVariant();listAvailableModels response 加 `currentVariant` + `activeVariant` row;telemetry log 加 `(variant={})`
  - 新 `switchVariant(Object params)` RPC;返回 `{ok, variant, activeVariant}` 让 renderer 立即 echo new state
  - `setProviderRegistry()` reflection-based 读 engine fields,避免 clobber engine 已 set 的 `(provider, model)` (R285 lesson 603)
- `HttpJsonRpcServer` 加 `case "switchVariant"` routing
- desktop `lib/methods.ts`:`VariantInfo` interface + `switchProvider(opts)` 加 variant 参数 + 新 `switchVariant(opts)` method
- desktop `store/index.ts`:
  - `currentVariant: string | null` + `activeVariant: VariantInfo | null` state
  - `switchVariant(variant)` action (persist 到 localStorage + set state)
  - `switchProvider(provider, model, variant)` 加 variant 参数 (持久化 + optimistic set)
  - `refreshProviders` 优先用 `listAvailableModels` 拿 `currentVariant` + `activeVariant`,fallback 到 `listProviders`
  - `EnginePrefs` 加 `variant?: string` 字段让重启恢复 quality pick
- desktop `MessageInput.tsx`:
  - `parseModelSwitchCommand(input)` 导出 — regex `/^model\s+(\S+?)\/(\S+?)(?::(\S+))?$/i` lazy quantifier + optional variant suffix + case-insensitive `/i` flag
  - `onSubmit` 时 detect `/model y:y` 调 `switchProvider(provider, model, variant)`
  - Quality pills row (`low` / `medium` / `high` / `xhigh`) 在 model dropdown sibling;click → `switchVariant(name)`;active row 高亮 via `currentVariant.toLowerCase() === name`
  - Active variant detail chip: `temp / maxTokensK · think` (only `temperature != null`)
- desktop `MessageInput.css`:`.message-input-quality-row` + `.message-input-quality-pill*` styling
- desktop `MockRpcServer`:
  - 新增 `switchProvider` + `switchVariant` handlers
  - `listAvailableModels` 加 `currentVariant` + `activeVariant` fields
  - 新增 `resolveVariant(name)` helper (mirrors daemon's `Variant.byName()`)
  - MockStore 加 `currentProvider` / `currentModel` / `currentVariant` / `activeVariant` fields

**Tests**:
- daemon: AetherCodeMethodsR285Test 7 tests pass (switchProvider_acceptsVariantArgumentAndPersistsIt / switchProvider_withoutVariantKeepsCurrentOne / switchVariant_changingVariantOnlyDoesNotTouchModel / switchVariant_nullOrBlankClearsToDefault / switchVariant_unknownVariantFallsBackToDefault / switchProvider_changingModelResetsVariant / listAvailableModels_includesActiveVariant)
- daemon full: protocol + core + sdk + others 全部 pass (14:19 min)
- desktop: MessageInputR285 6 tests pass (parser unit tests + opencode alias) — UI tests skipped 因 store 通过 production singleton rpc 调 Tauri invoke,fixture harness 的 mock client 不在 path 上 (R285 lesson 607)

**Deploy**:
- jar: 56,629,443 B (R285, +8,629 vs R284)
- exe: 4,161,024 B (unchanged — R282 build, no desktop shell changes)
- zip: SHA `99695BA8CE818E74EFE4030DC9F5EFCC6412433A636AFCA85BCC5C45CD980419`, 112,373,017 B (+18,610 vs R284)
- 49/49 bytecode markers (3 R277 + 8 R280 + 5 R281 + 4 R282 + 11 R283 + 8 R284 + 10 R285)

**Commit chain (累积 70 R-round)**:
```
4287f72 R284 (pre-compact snapshot + View original) ← pushed
   ↓
TBD   R285 (model variants + /model y:y + Quality pills) — commit pending
```

**关键技术决定 (8 条)**:
1. **Variant 4 bundled presets + 1 DEFAULT fallback** — LOW 0.3/16K, MEDIUM 0.7/32K, HIGH 1.0/48K, XHIGH 1.0/64K + 8K reasoning + extended thinking。Opencode-style alias (`default/medium/med → MEDIUM`)
2. **ModelSpec.variants 默认 BUILTIN** — null/empty 时 fill bundled;user list 完全替换默认
3. **mainLoopModelName reset currentVariant** — model change 时 old variant 可能不在新 model;reset null 让 fallback 找新 model bundled default
4. **switchProvider variant argument 让 caller supply** — 不传保留 engine current variant;传了覆盖
5. **switchVariant RPC 独立** — provider/model 不动,只换 variant,renderer Quality pills 用
6. **setProviderRegistry reflection-based engine field peek** — 避免 clobber engine 已 set 的 (provider, model);`readEngineProviderName()` + `readEngineModelName()` helpers
7. **mainLoopModelName 移到 if (newClient != null) 之外** — model change reset variant 是 engine 状态,不依赖 chat client swap
8. **Quality pills 在 MessageInput 不在 dropdown 里** — model picker 是 dropdown,variant picker 是 pills row 紧邻 dropdown;active row 高亮 via currentVariant

**关键调试技巧 (新增 607)**:
607. **TS1308 await 在 non-async function** — `const onKeyDown = (e) => {` 需要加 `async`;浏览器 keydown handler 不会 await fire-and-forget 但 React 不报错

**教训 (新增 603-607)**:
603. **AetherCodeMethods.setProviderRegistry clobber engine fields** — `methods.setProviderRegistry(reg)` 后调 `engine.setCompactRegistry(reg, null, "MiniMax-M3")` 覆盖 `engine.setCompactRegistry(reg, "glm", "glm-4-flash")`;fixed by reflection 读 engine fields,优先 engine field,只有两者都空才 fallback
604. **mainLoopModelName reset currentVariant 不依赖 chat client swap** — 旧逻辑只在 `if (newClient != null)` 内调 mainLoopModelName;test 5 model change 没 newClient 所以没 reset;fixed by `if (!modelId.equals(...)) mainLoopModelName(modelId)` 在 if/else 之外
605. **JSX `<label>` self-close 必须 fix** — `<label ...>...</label>` 是 self-close (没有 child),但如果外层 `<div>` 想用它做行内 label 元素,JSX 必须是 `<label ... />`;line 738 `</label>` 是 dangling (label 早就 close 了),改成 `</div>` 关闭 config-group
606. **JavaScript regex lazy quantifier 避免贪婪匹配** — `/^model\s+(\S+?)\/(\S+?)(?::(\S+))?$/i` `(\S+?)` lazy,`(?::(\S+))?` optional variant suffix;`/i` flag 让 `/MODEL y:y` 也 work
607. **Tests 必须走 UI click,不能直接调 store action** — store action 用 production singleton `rpc` (lib/methods),不走 RpcProvider context 的 mock client;直接 `useStore.getState().switchVariant('high')` 在 jsdom 失败 (Tauri invoke 没 `__TAURI_INTERNALS__`)。R284 lesson 598 同样限制:store 必须 refactor 用 useRpc() 才能让 UI tests 走 mock;R285 直接放弃完整 UI tests,只保留 parser 单元测试

**后续 R286+ 计划**:
- **R286**: agent.md frontmatter `variant:` 字段 + env `AETHERCODE_SUBAGENT_VARIANT` (R285 deferred) + Settings "Show all providers" toggle (R282 follow-up) + per-model pricing display + providers.yaml in-app editor + live env-var refresh button
- **R287**: Tauri shell plugin driver for SsdPanel — R281 MockSsdDriver 替代物;真实 subprocess wiring