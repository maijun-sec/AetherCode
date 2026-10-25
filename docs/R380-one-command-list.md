# R380 — 命令面只有一份名单

> 轮次：R380 · 日期：2026-10-25 · 分支：`main`
> 上一轮：[R379 — 三条"真的没有"的能力、一次没人看的倒计时](R379-gap-closers-and-countdown.md)

---

## 0. 本轮做了什么

| # | 问题 | 状态 |
|---|------|------|
| 1 | 命令面有**四份**名单（扁平数组 / 目录 / 分组表 / 路由器的 case 分支），而且**已经漂了** | 收敛成**一份**，其余全部派生 |
| 2 | `/demo` **补全不出来**（目录里有、扁平数组里没有） | 已修 |
| 3 | `budget` 在补全列表里**出现两次** | 已修 |
| 4 | `/effort`（R377 刚做出来的功能）**在 `/help` 里找不到** | 已修（现在有独立行） |
| 5 | 权限快捷方式 `/no-confirm` 的注释自称"**discoverable in one word**"，实际哪儿都看不见 | 已修（现在有独立行 + 别名提及） |
| 6 | 注释在**撒谎**："真相来源是 `SLASH_COMMANDS_DETAILED`"（其实是扁平数组）、"mirrors the entries in `SLASH_COMMANDS`"（并不 mirror）、"测试让未分组成为显式决定"（**没有那个测试**） | 全部改成真的 |

---

## 1. 缺陷：四份名单

R380 之前，`aethercode-tui/src/commands.ts` 里有**四份**描述同一批命令的东西：

| # | 名字 | 形态 | 消费者 |
|---|------|------|--------|
| 1 | `SLASH_COMMANDS` | 扁平 `string[]`（77 条） | `completeSlash()`（Tab 补全）、`CommandPalette.tsx`（模糊搜索） |
| 2 | `SLASH_COMMANDS_DETAILED` | 目录（77 条：name / description / args） | `InputBox.tsx` 的下拉框、`/help` 的描述 |
| 3 | `SLASH_COMMAND_GROUPS` | `分组 -> 有序名字`（6 组 73 条） | `/help` 的分组与顺序 |
| 4 | `routeSlash` 的 `case` 分支 | 87 个 case 臂 | 真正派发命令的地方 |

**四份都没有互相校验**，而且**注释在撒谎**：

```ts
// Source of truth for autocomplete is SLASH_COMMANDS_DETAILED;      ← 假的
// Mirrors the entries in `SLASH_COMMANDS` above (the flat string     ← 也是假的
// array is kept for the legacy `completeSlash` consumer).
```

第一句说"补全的真相来源是目录"，可 `completeSlash()` 读的是扁平数组；第二句说目录"镜像"扁平数组，可它们**根本不一样**。

### 1.1 已经漂出来的洞（可执行证据）

```
=== 1. /demo 在 /help 里吗？ ===          →  true（它在分组表里）
=== 2. /demo 能补全吗？ ===
   SLASH_COMMANDS.includes("demo")  ->  false
   completeSlash("/dem")           ->  {"completed":"/dem","alternatives":[],"oneShot":false}
=== 3. 重复 ===
   duplicates in SLASH_COMMANDS: [["budget",2]]   length=77  unique=76  catalog=77
```

- **`/demo`**（R344 加的"走一遍 canned conversation"，文档里说它是"评估 TUI 最快的方式"）**补全不出来**，也**不在命令面板里**。
- **`budget`** 在补全列表里**出现两次**。
- 两个补全面板（Tab 走扁平数组、InputBox 下拉走目录）**顺序还不一样**。

### 1.2 路由器知道 10 个目录不知道的命令

`routeSlash` 有 87 个 `case` 臂，其中 **10 个不在目录里**：

| 命令 | 性质 | 用户能不能找到 |
|------|------|----------------|
| `resume` / `kill` / `todo` / `ctx` / `token` | 隐藏别名 | ❌ 补全不到、面板里没有 |
| `no-confirm` / `noconfirm` / `yes-do-it` | 权限快捷方式 | ❌ 注释自称"discoverable in one word" |
| **`effort`** | **R377 的真功能** | ❌ `/help` 里没有、补全不到 |
| `agent-pick-set-default` | picker 的子动词 | ❌ |

> **也就是说：`/effort` —— R377 整轮做的那个功能 —— 用户在 `/help` 里找不到，Tab 也补不出来。**
> 它只在"用错命令"时的 usage 字符串里露过一次面。

### 1.3 分组表的漂移是**静默**的

`renderSlashHelp()` 遍历分组表，目录里查不到就 `continue` —— 也就是**跳过**。代码注释自己写着：

> "A group row with no catalog entry means the two lists drifted again. **Skip** rather than print a blank row — the test makes that an explicit decision instead of an accident."

**但根本没有那个测试。** 门禁只查了"分组表里的名字都在目录里"（一个方向），反向只硬编码钉了 4 个已知别名。所以**下一个新增命令忘了加分组 → 它会静默地从 `/help` 里消失，没有任何门禁会报。**

---

## 2. 修法：一份名单，其余派生

```
SLASH_COMMANDS_DETAILED   ← 唯一的名单
  { name, description, args?, group, aliasOf? }
        │
        ├─→ SLASH_COMMANDS      = 目录的名字投影（补全 / 面板）
        └─→ renderSlashHelp()   = 按 group 分组渲染（/help）
```

- **`group` 是条目上的字段**（必填）→ 命令不可能"在目录里却不在 `/help` 里"，因为分组跟着它走。
- **`aliasOf` 声明别名指向谁** → 别名**提及**是渲染器**推出来的**，不是手写在描述里的。
- **扁平数组被删掉**，改成目录的投影 → 不可能再有重复，也不可能再缺一个。
- **路由器的 10 个隐藏命令全部登记进目录**：8 个声明成别名（`resume`→`continue` 等），2 个给独立行（`/effort`、`/agent-pick-set-default`）。

### 2.1 `/help` 的用户可见文本**逐字节相同**

除了该长的长（`/effort`、`/agent-pick-set-default` 有了自己的行），重构前后的 `/help` **完全一致**：

```
=== 1. /help: identical? ===
   IDENTICAL — the refactor changed no user-visible help text
```

> 判据不是"看起来没变"，是**拿重构前的文件编译一遍，逐字节 diff**。

### 2.2 别名不得遮住它的主命令

`/token` 是 `/tokens` 的别名。两个都在补全列表里，`/tok` 就从"一键补成 `/tokens `"变成两个候选。

规则很窄也很有据：**当别名指向的主命令也匹配这个前缀时，别名是多余的，丢掉它。**

```ts
const matches = SLASH_COMMANDS.filter((c) => c.startsWith(cmdPart)).filter((c) => {
  const primary = ALIAS_PRIMARY.get(c);
  if (!primary || primary === c) return true;
  return !primary.startsWith(cmdPart);
});
```

- `/tok` → 命中 `{token, tokens}`，而 `tokens` 也命中 → 丢掉 `token` → **`/tokens `**（一键完成，和以前一样 ✓）
- `/q` → 命中 `{q, quit}`，主命令 `exit` 不命中 → **两个都留**（和以前一样 ✓）

---

## 3. 门禁

`scripts/test/r380-command-catalog.test.mjs`（12 断言）钉住的是**两个方向的全称检查**：

| 断言 | 抓的是什么 |
|------|-----------|
| 扁平数组是**派生**的，不是手维护的 | 第二份拷贝回来 |
| 源码里**没有**分组表 | 第二份拷贝回来 |
| 每条都有 group，且**没有分组被拆成两段** | 命令从 `/help` 消失 / 标题重复 |
| **路由器能到的每个命令都在目录里** | `/effort` 那种（路由器知道、目录不知道） |
| **目录里的每个命令都被路由** | `/help` 承诺了却没人实现 |
| 每条非别名都有 `/help` 一行；每个别名都被提及 | 找不到的命令 |
| 别名提及是**推出来的**，不是手写的 | 第二份别名清单 |
| **别名的描述必须指向它的主命令** | `/clear` 冒充 `/help` 的别名 |
| `/effort` / `/no-confirm` 确实可见 | 本轮的具体发现 |
| 别名不遮住主命令 | `/tok` 的一键补全 |
| `/help` 不带开发注记 | 用户可见文本的卫生 |

**6 个旧门禁被重定向**（不是删除）：`r78-traces`、`r80-memory-panel`、`r103-palette`、`r169-agents-effort-cwd`、`r362-help-derived`、`t433-components` —— 它们都在读旧的分组表/扁平数组字面量。性质保留，只是改对新的唯一真相表达。

`r380-mutate.mjs`：**11 个用例，全部被抓**（`ALL MUTATIONS CAUGHT`，exit 0）。

---

## 4. 证据与验证

| 项 | 结果 |
|----|------|
| tsc | `npx tsc --noEmit` → 0 错 |
| 格式 | `npm run format` + `format:check` → clean |
| R380 门禁 | **12/12 绿** |
| R380 电池 | **11/11 ALL MUTATIONS CAUGHT**，exit 0 |
| 全量测试 | `npm test` → **1395 / 1352 通过 / 43 失败** |
| R379 基线 | 1383 / 1341 / 43 → **+12 测试、+11 通过、失败集合逐名相同**（零新增、零静默修复） |
| 全部电池 | **16/16 绿**（格式化之后跑的） |
| `/help` 前后对比 | **逐字节相同** |

---

## 5. 这一轮自己踩的三个坑

1. **`aliases?` 不是 "alias + 可选 es"** —— 正则 `aliases?` 匹配的是 "aliase(+s)"，所以**单数 "alias" 匹配不上**。门禁因此漏掉了"别名提及被手写回去"这条变异。**这是同一处拼写第二次坑人**（第一次在补丁脚本里，把手写的别名提及剥不掉，结果渲染出重复的 `(alias: /?)`）。
2. **针脚打到了注释里**（R379 的老问题，第二次）："倒计时不再承诺 esc"的针脚命中的是 JSDoc 里引用 Claude Code 的那句话。R380 里同样有两个针脚命中了注释 —— no-op 探测器报了出来，改成 `mutateInCode()` 后解决。
3. **prettier 会重排条目**：`effort` 那条描述最长，被折成了多行 → 钉整条文本的针脚失配 → 用例变成 no-op。**针脚要钉"它关心的那个字段"，不是整条文本。**

> **Lesson —— 针脚必须钉住它关心的那个字段，而不是整条文本。**
> 排版一变，整条文本的针脚就失配；而失配的表现是"用例没测到任何东西"，不是"用例失败"。

---

## 6. 遗留（实测）

1. **可达组件里还有 9 个手写按键表**（R379 第 4.3 节的清单，未动）。
2. `/help` 里的**别名现在会带出 3 个近义别名**（`/no-confirm`、`/noconfirm`、`/yes-do-it` 指向 `/mode`），措辞还可以更收敛。
3. `aethercode-cli` 的 `--effort` 仍未映射到 provider 参数（R747）。
4. `TaskPanel.tsx` / `src/rpc/queries.ts` / `src/rpc.ts` 里还有 `task/*` 的死引用。
5. 真实 Ink 交互 + 80 列渲染的视觉验收仍未自动化。

---

## 7. 教训

- **Lesson 1207 —— 一个"清单"可以有好几份，而且每一份都会漂。** 本轮实测：四份名单，**三份已经漂了**（`/demo` 缺失、`budget` 重复、10 个命令只在路由器里）。而且**两处注释在撒谎**——注释说"真相来源是 X"，X 却不是。
- **Lesson 1208 —— "单向校验"留下的正是会漂的那一半。** 分组表方向有门禁、反向没有 → 新命令漏加分组就静默消失；而且代码注释**自称**有那个测试，实际没有。**注释里的"有测试保证"是一句需要被验证的断言。**
- **Lesson 1209 —— 一个别名不得遮住它的主命令。** `/token` 在列表里会让 `/tok` 失去一键补全。判据来自 `aliasOf` 关系：**别名指向的主命令也匹配时，别名是多余的。**
- **Lesson 1210 —— 针脚要钉字段，不是钉整条文本。** prettier 会重排条目，整条文本的针脚就失配，而失配的表现是"没测到任何东西"。
- **Lesson 1211 —— `aliases?` 不是 "alias + 可选 es"。** 正则的量词挂在**最后一个字符**上：`aliases?` = "aliase" + 可选 "s"。同一处拼写坑了两次。
