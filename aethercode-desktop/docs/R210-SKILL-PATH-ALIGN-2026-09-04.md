# R210: skill 路径对齐 `~/.aethercode` + light theme .center 修复 + addSkill RPC

**Version**: v0.2.50 (R210 重 build)
**Date**: 2026-09-04
**Type**: bug fix + feature (light theme center panel + skill path rename + `/skill add` RPC)
**Symptom**:
1. 切到 light theme 后,header / leftpanel / status bar 已经是浅色,但 `.center` 区(对话区)还是黑色(R209 修过 `data-theme="light"]` 选择器,但选错了 — 写了 `.center[data-theme="light"]`,而 data-theme 在 `<html>` 上不在 `<div class="center">` 上,根本匹配不上)
2. 全局 skill 走的是 `~/.minimax/skills/`,跟项目目录 `~/.aethercode/mcp.json` 等命名不一致
3. 没有 `/skill add <name> --scope global|project` 的引擎侧 hook (UI / 桌面可以发,但 engine 不知道怎么写文件)

## 修改

### 1. light theme `.center` 修复 (R209 fix)

`aethercode-desktop/src/App.css`: 选择器从 `.center[data-theme="light"]` 改成
**`:root[data-theme="light"] .center`** (后代选择器)。data-theme 在 `<html>` 上,
选择器要 match 它的后代才能 re-skin。R209 原始的 `.center[data-theme="light"]` 形式
永远不会匹配,因为 `<div class="center">` 上没有 data-theme attribute。

`store/sideNoteR208.test.ts` 的 `App.css declares the .center light token override`
test 现在:
- **正向 pin**: 必须有 `:root[data-theme="light"] .center` 块
- **反向 pin**: 显式禁止 `.center[data-theme="light"]` 形式(防止 refactor 回退)

### 2. 全局 skill / agent 路径 `~/.minimax/` → `~/.aethercode/`

**改动文件**:
- `aethercode-cli/.../Main.java`: 加 `resolveAethercodeHome()` helper(跟 `resolveMavisHome()` 平行),优先 `AETHERCODE_HOME` env var,fallback `~/.aethercode`。User-tier skill / agent 路径从 `resolveMavisHome().resolve("skills"/"agents")` 改成 `resolveAethercodeHome().resolve("skills"/"agents")`
- `aethercode-cli/.../DaemonRunner.java`: RegistryReloadService 的 watch path 同样从 `.minimax/` 改成 `.aethercode/`
- `aethercode-core/.../SkillRegistry.java`: 注释更新 `~/.minimax/skills/` → `~/.aethercode/skills/`
- `aethercode-sdk/.../AetherCodeEngine.java`: 注释更新

**保留兼容**: `resolveMavisHome()` 仍然存在(老 env var `MAVIS_HOME` / `MINIMAX_HOME` 还工作),不自动迁移老文件 — user 自己 `mv` 一下。

### 3. `addSkill(name, scope, body)` RPC

**`aethercode-core/.../SkillRegistry.java`**: 加 `addSkill(String name, Scope scope, String body)` 方法:
- `Scope.GLOBAL` → 写到第一个 user-root (`~/.aethercode/skills/<name>/SKILL.md`)
- `Scope.PROJECT` → 写到第一个 project-root (`<cwd>/.aethercode/skills/<name>/SKILL.md`)
- 创建目录 + 写文件 + `reload()`(新 skill 立刻 list() 可见)
- 名字安全 regex: `^[A-Za-z0-9][A-Za-z0-9._-]*$` — 拒绝 `..` / `.` / 任何 leading dot / leading dash / leading underscore / 含路径分隔符的名字
- 没有 root 配置时返回 false(不 throw)

**`aethercode-protocol/.../AetherCodeMethods.java`**: 加 `addSkill` RPC:
- 参数: `{name, scope, body}` — `scope` 默认 `PROJECT` (cwd-local 是 safer tier)
- 返回: `{ok, name, scope, path, count, reloadedAt}` (新 skill 路径 + reload 后的总 count)
- 错误: `{ok: false, error: "..."}` 包含 invalid name / unknown scope / missing body / no roots / no skills
- 注册 `dispatcher.register("addSkill", this::addSkill)` + 加到 methodToMap (`TAG_WRITE, TAG_SKILL`)

### 测试

`aethercode-core/.../SkillRegistryR210Test.java` — 6 个 test:
1. **`addSkill_projectWritesToProjectRootAndReloads`** — PROJECT scope 写到 projectRoot/<name>/SKILL.md + reload 后 list() 看到
2. **`addSkill_globalWritesToUserRootAndReloads`** — GLOBAL scope 写到 userRoot
3. **`addSkill_refusesUnsafeNameWithPathTraversal`** — 拒绝 `..` / `.` / `../escape` / `a/b` / `/abs` / `-foo` / `_foo` / `.foo` / `""`,接受 `ok-name.v2`
4. **`addSkill_rejectsWhenScopeRootIsMissing`** — 没 user root / 没 project root 时返回 false 不 throw
5. **`addSkill_picksFirstRootWhenMultipleConfigured`** — multi-root 时写到第一个,第二个空着
6. **`r210_userTierRenameSourcepin`** — 4 个 source 文件不能出现 `resolveMavisHome().resolve("skills"/"agents")` 或 `.resolve(".minimax").resolve("skills"/"agents")`;Main.java 必须出现 `resolveAethercodeHome()`

## Test 跑分

| 套件 | 之前 | R210 后 | 增量 |
|------|------|---------|------|
| `aethercode-core/.../SkillRegistryR210Test.java` (新) | — | **6** | +6 (R210 source-pin + behavioural) |
| `aethercode-core` skill total | 17 (R128 + base) | **23** | +6 |
| `aethercode-permission` | 314 | **314** | 0 (no change) |
| `aethercode-protocol` AetherCodeMethods tests | 60 | **60** | 0 (no change) |
| TS desktop (1013) | 1013 | **1013** | 0 (no TS change) |

**6/6 R210 + 17/17 R128 + 10/10 base + 314 permission + 60 protocol + 1013 TS = 1420/1420 通过**

(`AgentToolTest` 14 errors 是 pre-existing,从 R181 开始就在,跟 R207/R208/R209/R210 无关)

## Build

- jar: `aethercode-0.2.50.jar` **55,317,002 bytes** (+2,406 vs R209 55,314,596,
  SkillRegistry.addSkill + Scope enum + AetherCodeMethods.addSkill RPC +
  Main/DaemonRunner/SkillRegistry/AetherCodeEngine 注释更新)
  SHA256 `485D4D507AAB0782A9F7A40A12DC79F4D1B062DAEF9A9145CCA1880502A6CD63`
- exe: `AetherCode.exe` 3,965,440 bytes (跟 R209 build 一样 — `.center` light
  theme 修复是 CSS-only 改 App.css 一行 selector,跟 R209 改的 global.css + Header
  + store 一起进了 R209 build。R210 build 重新 build 是因为 jar 改了,exe size
  跟 R209 一样, SHA 不同因为 Rust 重新 link)
  SHA256 `18CA9B53BEB8916AB865260341D4142A292A6FAC52061915D704B280DD4DB24B`
- setup.exe (NSIS): 2,119,755 bytes
- msi: 2,600,960 bytes

Release 目录: `release/aethercode-0.2.50/`

## 后续 (R211 候选)

- 在 desktop 加 `/skill add <name> --scope global|project` slash command(走 `rpc.addSkill`)
- 写 SKILL.md skeleton(frontmatter + body 模板),不需要 user 写 YAML
- 用户已经下载的 skill(从 marketplace)落地到 project / global 路径也走 addSkill
- 老的 `~/.minimax/skills/` 内容检测到后弹一个 "检测到旧路径 skill, 是否迁移到 `~/.aethercode/skills/`?" 提示

## 教训 (2026-09-04)

1. **CSS 后代选择器要 match attribute 的祖先,不是祖先匹配 attribute** —
   R209 写 `.center[data-theme="light"]` 想让 `.center` 在 light theme 下 re-skin,
   但 data-theme 在 `<html>` 上,`.center` 是 `<html>` 的后代,需要的是
   `:root[data-theme="light"] .center` 这种**含祖先 attribute 的后代选择器**。
   教训:CSS attribute 选择器只在**写 attribute 的那个元素**上 match,不会
   "穿透"到后代。

2. **"全局 ~/.aethercode" 是设计意图,老 .minimax 是历史包袱** —
   mcp.json / providers.yaml / sessions 早就在 `~/.aethercode/`,只有 skill /
   agent 还停在 `~/.minimax/`。R210 一刀切到 `~/.aethercode/`。保留
   `resolveMavisHome()` 是为了 `MAVIS_HOME` / `MINIMAX_HOME` 老 env var
   还能工作(向后兼容),但**不再用它** 算 user-tier 默认路径。

3. **加 RPC 之前想清楚 scope / 错误处理 / 安全 regex** —
   `addSkill` 接受任意 `name` 写到 `Path.resolve(name)`,没有 regex 就会被
   `../escape` 路径遍历攻击。R210 加 `^[A-Za-z0-9][A-Za-z0-9._-]*$` 锁死。
   教训:文件操作类 RPC 永远先想 "如果 attacker 控制了 input 会怎样"。
