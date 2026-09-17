# R282 — env-driven model picker (GLM / DeepSeek / hasApiKey filter)

> 2026-09-17 — Settings page model picker shows only providers the user
> configured via env vars. Submodels of configured providers all show,
> even ones the user hasn't purchased yet.

**触发**: 用户原话 "我在环境变量里面，配置了 GLM 和 deepseek 的 api key
环境变量，请添加对这两个 provider 的兼容，这两个都有少量免费模型
可用，请完成适配。在 app model 选择那里，你配置的很多模型都是我
没办法用的，可以只显示有配置 provider 的部分，配置了 provider 的
下面的子模型都可以显示，虽然不一定有购买"

## 设计

### 核心：hasApiKey 字段

`ProviderSpec.hasApiKey()` 检查 `System.getenv(apiKeyEnv)` 是否非空。
RPC 把这个 boolean 暴露给桌面，桌面按它过滤模型列表。

**为什么 boolean 而不是字符串**：
- 用户配置好 `GLM_API_KEY=xxx` 后 daemon 进程看到的就是这个值
- boolean 比 string 更省 wire bytes 且不容易出错
- 客户端不需要知道 key 实际值（也不应该知道）

### RPC surface

| RPC | 用途 |
|---|---|
| `listProviders` (扩展) | 老 RPC + 加 `hasApiKey` 字段 per provider |
| `listAvailableModels` (新) | 注册到 daemon + HTTP server，flat catalog + per-provider block + `model/list` alias |
| `model/list` (alias) | 兼容旧的 `useModelList()` 调用路径 |

### UI 行为

```
┌─ Available models ──────────────────────────────────────────────┐
│ Showing 3 models from providers with an API key configured      │
│ in your environment.                                            │
│                                                                 │
│ [deepseek]  deepseek-chat       64k ctx   [Use]                 │
│ [glm]       glm-4-flash        128k ctx   [Use]                 │
│ [glm]       glm-4-plus         128k ctx   [Use]                 │
└─────────────────────────────────────────────────────────────────┘
```

未配置 provider 整段消失（包括 anthropic、openai — 用户没 key）。
已配置 provider（glm、deepseek）下所有子模型都列出来（即使
用户账户只买了 glm-4-flash 但没买 glm-4-plus；用户可以看到
glm-4-plus，将来想用也能切）。

## 实现

### Daemon side (Java)

**修改**:
- `ProviderSpec.java`: 加 `hasApiKey()` helper（动态查 env）
- `AetherCodeMethods.listProviders`: 每个 provider entry 加 `hasApiKey`
- `AetherCodeMethods.listAvailableModels`: 新方法，flat catalog + 兼容 `model/list` 调用
- `AetherCodeMethods.METHOD_TAGS`: 注册 `listAvailableModels`
- `AetherCodeMethods` dispatcher: 注册 `listAvailableModels` 和 `model/list` alias
- `HttpJsonRpcServer`: 加 `case "listAvailableModels" ->` 和 `case "model/list" ->`

### Desktop side (TypeScript / React)

**修改**:
- `lib/methods.ts`: `ProviderInfo` 加 `hasApiKey?: boolean`；新增
  `AvailableModelInfo` interface；`AetherCodeRpc.listAvailableModels()`
- `components/MessageInput.tsx`: dropdown 过滤 `p.hasApiKey === false`
- `pages/SettingsPage.tsx`: `ModelsTab` 改用 `useStore().availableProviders`
  + `refreshProviders()`（不再用 `useModelList`，因为那个 RPC 在
  production 的 daemon HTTP server 上没注册）。新 test IDs:
  `model-row-{provider}-{modelName}`、`model-pick-{provider}-{modelName}`
- `rpc/MockRpcServer.ts`: 加 `providers: MockProviderInfo[]` 到 MockSeed；
  加 `listProviders` + `listAvailableModels` handler

### Tests

**新文件**:
- `aethercode-protocol/src/test/java/org/aethercode/protocol/methods/AetherCodeMethodsR282Test.java`
  (4 tests: listProviders hasApiKey flag / listAvailableModels flat catalog
  with hasApiKey / listAvailableModels empty when no registry /
  listProviders empty when no registry)
- `aethercode-desktop/src/components/ssd/__tests__/SsdPanelR281.test.tsx`
  already done in R281, doesn't need updates here

**修改**:
- `aethercode-core/src/test/java/org/aethercode/core/providers/ProviderRegistryTest.java`
  +2 tests (hasApiKey unset/blank returns false)
- `aethercode-desktop/src/pages/__tests__/SettingsPage.test.tsx`
  把 `modelsFixture` 换成 `providersFixture` (3 providers, 1
  unconfigured)，验证 hasApiKey filter 行为

**总计**:
- Daemon: 12 ProviderRegistryTest + 4 R282 + (其他 module 未跑)
- Desktop: 1137/1137 pass (96 files; +1 net from R281's 1136 — R281
  had 2 model tests, R282 has 3)

### Jar bytecode verify

`verify_jar_r277_fix.py` 扩展到 20 checks (3 R277 + 8 R280 + 5 R281 + 4 R282):

```
R277: AetherCodeMethods.isAskMode + currentPermissionModeName
      JsonRpcPermissionPrompter references isAskMode
R280: LayeredMemoryStore.appendSessionChange / writeProjectInfo /
      readProjectMemoryExcluding
      ProjectMemoryStore owns PROJECT_MEMORY.md
      AetherCodeEngine.buildProjectMemorySection (top-of-prompt inject)
      MemoryMethods 3 RPCs (appendSessionChange / setProjectInfo / readProjectMemory)
R281: InteractiveRepl phase-list / phase-draft / revise
      SsdRunner recordRevision
      SsdCommand --interactive
R282: ProviderSpec.hasApiKey helper
      AetherCodeMethods.listAvailableModels + model/list alias
      HttpJsonRpcServer routes listAvailableModels
```

全部 20/20 pass。

## 部署

| Artifact | Size | SHA (前 8 chars) |
|---|---|---|
| `release/aethercode-0.2.70.zip` | 112,330,849 B | `E02F4E62…` |
| `release/aethercode-0.2.70/desktop/aethercode.jar` | 56,607,546 B | R282 build |
| `release/aethercode-0.2.70/desktop/aethercode-desktop.exe` | 4,161,024 B | R282 build |
| `release/aethercode-0.2.70/desktop/aethercode-desktop.exe.r281` | 4,161,024 B | R281 backup |
| `release/aethercode-0.2.70/desktop/aethercode-desktop.exe.old` | 5,179,904 B | R280 backup |
| `release/aethercode-0.2.70/aethercode-0.2.70.jar` | 56,607,546 B | R282 build (CLI) |

zip 比 R281 (`F382FCCE…`, 110 MB) 大 +1.94 MB：
- R282 jar (56,607,546 vs 56,606,861, +685 B)
- R282 .r281 backup exe (+4,161,024)

git commit `TBD`（commit 待执行）。
github push 又不通（443 timeout），cron `push-r282` 待设。

## 关键技术决定 (5 条)

1. **`hasApiKey()` 是 `ProviderSpec` 上的 method，不是 constructor field**
   — env vars 在 process 生命周期里能改（用户可能 export GLM_API_KEY
   后重启 daemon；或不同 session 之间改）。每次 RPC 调用都重新查，
   不会 stale。如果做成 constructor field，重启 daemon 才能刷新。

2. **`listAvailableModels` 是独立 RPC，不只是 `listProviders` 的扩展**
   — Settings picker 需要 flat 数组 + per-provider metadata；MessageInput
   dropdown 也需要。两个 RPC 各司其职。`listProviders` 继续支持老的
   consumer（switchProvider / agent frontmatter 解析）

3. **`model/list` 是 alias 指向 `listAvailableModels`**
   — desktop 老的 `useModelList()` 调的是 `model/list`；直接改 RPC
   比改一堆 hook 简单。HTTP server 也加 case。

4. **过滤在 renderer 做，不在 daemon 做**
   — daemon 返回所有 providers + hasApiKey flag；renderer 按 flag
   过滤。这样 daemon RPC 是干净的 "全量"，renderer 有 UI 决定权
   （比如以后可以加一个 "show all providers" 开关，daemon 不用动）。

5. **submodels 全部显示，不只看 `default: true`**
   — 用户原话 "下面的子模型都可以显示，虽然不一定有购买"。
   模型列表的 `default` 是 provider 推荐的 default，不是用户能
   调的子集。所以 renderer 一律展开所有 submodel。

## 教训 (新增 591-595)

**591-595 (R282)**:

591. **`refreshProviders` 在 store 里使用 Tauri singleton，不能在 jsdom test 跑**
   — 测试 fixture 用 `useStore.setState({ availableProviders: fixture })` 直接 seed
   store，绕过 refreshProviders 的网络调用。这条适用于任何使用
   store 直接调 RPC 的 UI 代码——测试时别用 production 的 store，
   改用 store.setState seed。

592. **R282 daemon side changes 需要在 desktop 测 setState seed (not via useRpc)**
   — `useStore` 是用 production RPC (Tauri) 而不是 jest mockRpcServer。
   一个 React 组件可以走 useRpc（mock client）或者 useStore（production
   client）。测试时要知道走哪条。

593. **`mvn clean package -DskipTests -pl aethercode-core,aethercode-protocol,aethercode-cli -am`**
   — R282 改 ProviderSpec (aethercode-core) + AetherCodeMethods (aethercode-protocol)
   + HttpJsonRpcServer (aethercode-protocol)，jar 是 aethercode-cli shade 出来。
   这 3 个 module + -am 一起 rebuild 才能拿到正确 bytecode。

594. **`AetherCodeEngine$Builder.apiKey(null)` 在 b.apiKey == null 时报 "provider requires env var X (unset)"**
   — ProviderSpec.apiKey() 返回 null 时会抛 IllegalStateException。
   不是抛 "key not set"，而是 "env var unset"。错误消息应该清晰指向
   "你需要在 shell 里 export GLM_API_KEY=..."。R282 改动了 ProviderSpec
   但没动 AetherCodeEngine 这条路径，留给下一轮 (R283+) 改进。

595. **`listProviders` 加 `hasApiKey` 字段不影响 backward compat**
   — TypeScript 的 `hasApiKey?: boolean` 是 optional，老 client 不会破。
   桌面 if (p.hasApiKey === false) 是 strict 比较，undefined 不会被
   filter 掉（保持老 behaviour）。新桌面会 filter。

## 后续 R283+ 计划

- **R283**: Tauri shell plugin driver for SsdPanel (R282 的 SSD 真实 subprocess)
- **R284**: Settings panel "Show all providers (even unconfigured)" toggle
  — 高级用户想看 anthropic/openai 列表但用不了
- **R285**: providers.yaml UI editor — 直接在 Settings 改 provider
  catalog，不用手写 YAML
- **R286**: per-model pricing display in picker (input/output $/M tokens)
- **R287**: live env var check — Settings 顶部一个 "Refresh from env"
  按钮，不用重启 daemon 也能看到 GLM_API_KEY 设上后的 picker 更新

## 文件清单

### New
- `D:\work\workspace\idea\engine\AetherCode\aethercode\aethercode-protocol\src\test\java\org\aethercode\protocol\methods\AetherCodeMethodsR282Test.java` (4 tests)
- `D:\work\workspace\idea\engine\AetherCode\doc\round-notes\R282-provider-hasapikey-filter-2026-09-17.md`

### Modified
- `D:\work\workspace\idea\engine\AetherCode\aethercode\aethercode-core\src\main\java\org\aethercode\core\providers\ProviderSpec.java` (+ hasApiKey method)
- `D:\work\workspace\idea\engine\AetherCode\aethercode\aethercode-protocol\src\main\java\org\aethercode\protocol\methods\AetherCodeMethods.java` (+ listAvailableModels, model/list alias, hasApiKey field)
- `D:\work\workspace\idea\engine\AetherCode\aethercode\aethercode-protocol\src\main\java\org\aethercode\protocol\http\HttpJsonRpcServer.java` (+ 2 case routing)
- `D:\work\workspace\idea\engine\AetherCode\aethercode\aethercode-core\src\test\java\org\aethercode\core\providers\ProviderRegistryTest.java` (+ 2 tests)
- `D:\work\workspace\idea\engine\AetherCode\aethercode-desktop\src\lib\methods.ts` (+ ProviderInfo.hasApiKey, + AvailableModelInfo, + listAvailableModels)
- `D:\work\workspace\idea\engine\AetherCode\aethercode-desktop\src\components\MessageInput.tsx` (filter dropdown by hasApiKey)
- `D:\work\workspace\idea\engine\AetherCode\aethercode-desktop\src\pages\SettingsPage.tsx` (ModelsTab uses store.availableProviders + refreshProviders, new test IDs)
- `D:\work\workspace\idea\engine\AetherCode\aethercode-desktop\src\pages\__tests__\SettingsPage.test.tsx` (providersFixture, useStore.setState seed)
- `D:\work\workspace\idea\engine\AetherCode\aethercode-desktop\src\rpc\MockRpcServer.ts` (+ MockProviderInfo type, + listProviders handler, + listAvailableModels handler)
- `D:\work\workspace\idea\engine\AetherCode\scripts\verify_jar_r277_fix.py` (+ 4 R282 markers)

### Deployed (release/aethercode-0.2.70/)
- `desktop/aethercode.jar` — 56,607,546 bytes (R282 build, +685 vs R281)
- `desktop/aethercode-desktop.exe` — 4,161,024 bytes (R282 build)
- `desktop/aethercode-desktop.exe.r281` — 4,161,024 bytes (R281 backup)
- `desktop/aethercode-desktop.exe.old` — 5,179,904 bytes (R280 backup, was .r280 before)
- `aethercode-0.2.70.jar` — 56,607,546 bytes (R282 CLI build)

### Zip
- `release/aethercode-0.2.70.zip`
- SHA `E02F4E62C3AE278EB7834C6C9C286DF301427AE8BBEC23B9DF62B26C591CE030`
- size 112,330,849 bytes (vs R281 110,392,421 = +1,938,428)