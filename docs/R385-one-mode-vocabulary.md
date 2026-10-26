# R385 —— 模式界面必须说 daemon 的词汇

> 裸 `/mode` 在 cmd.exe 里能用,在 Ink 里却回答"这个命令还没有面板"。
> 一个**能用的功能**被报成**不存在**——比没有这个功能更糟。
>
> 顺着这条线查下去,发现权限模式的词表写在**六个文件、三十九个字面量**里,
> 而且它们**已经开始互相矛盾**:目录漏了一个 daemon 支持的模式,
> 两个渲染器给同一个模式显示不同的徽章,Ink 的兜底值还把 `DEFAULT`
> 报成了 `ACCEPT_TASK`——那是在**关于用户自己的安全网撒谎**。

---

## 0. 这一轮做了什么

用户指令是「继续优化」。本轮没有扩功能,而是把**最后一个孤儿 sentinel**
变成真的面板,并把那份散落六处的词表收成一处。

| # | 缺陷 | 症状 | 修法 |
|---|------|------|------|
| 1.1 | `__PICK_MODE__` 只有 cmd.exe 认领 | Ink 上裸 `/mode` 回答"还没有面板" | Ink 端补一个真的 `ModePicker` |
| 1.2 | 词表写在 6 个文件、39 个字面量里 | 两端徽章不一致、目录漏模式、兜底值撒谎 | 收成 `src/permissionModes.ts` 一处 |
| 1.3 | TS 目录缺 `ASK_BEFORE_TOOL` | daemon 支持的模式在 UI 里选不到 | 目录对着 daemon 的枚举核 |
| 1.4 | Ink 兜底写 `ACCEPT_TASK`,daemon 默认是 `DEFAULT` | 头栏把"本任务内全部自动放行"报给其实在逐次询问的用户 | 兜底改成 `MODE.DEFAULT` |
| 1.5 | `/mode default` / `/mode ask-before-tool` 直接失败 | daemon 端 `valueOf` 大小写敏感、不认横线 | 发送前归一化 |
| 1.6 | 词表里的名字**不出现在**任何注册表行里 | 没有任何东西能发现"这个模式没人管" | 门禁:每个 sentinel 两个渲染器都要有归属 |

顺带修掉一处**注册表的谎言**:`__PICK_MODE__` 此前被 r362 门禁的
`INLINE_FAMILIES` 豁免名单登记成"内联动作,不需要归属"——而它其实
**开的是一个面板**。豁免本身就是一个没人看过的类别描述。

---

## 1. 缺陷:最后一个孤儿 sentinel

### 1.1 症状

`src/commands.ts:707` 的裸 `/mode` 返回哨兵 `__PICK_MODE__`:

```ts
case "mode":
  if (rest.length === 0) return { local: "__PICK_MODE__" };
```

两个渲染器对它的处理完全不同:

| 渲染器 | R385 之前 | 用户看到的 |
|--------|-----------|-----------|
| cmd.exe (`line.ts`) | `if (slash?.local === "__PICK_MODE__")` → `pickMode(...)` | 一个 6 行的选择器 |
| Ink (`tui.tsx`) | 落进 R362 的兜底分支 | **"no panel for that command in the TUI yet — /help lists what is supported"** |

Ink 那个分支的源码注释甚至把 `__PICK_MODE__` 举成了例子:

```ts
// `describeSentinel` returns its argument UNCHANGED when the
// token has no registry entry (a sentinel the registry does
// not own, e.g. `__PICK_MODE__`).
```

**注释知道它是孤儿,并且把它写下来了。** 这正是最坏的一种状态:
缺陷被记录、被解释、被当作已知情况接受,而不是被修。

### 1.2 为什么"假失败"比"没有"更糟

Claude Code 那种成熟实现里,`/mode` 是一个**真的面板**。用户从
benchmark 里学到的心智模型是"这是有的"。于是当 TUI 回答"还没有面板":

- 用户以为这个功能不存在,于是去 `/help` 里找,找不到;
- 用户以为这个版本落后了,于是**用 `/mode DEFAULT` 硬打**——
  而这条路径在 Ink 上是**能用的**(它走 `setPermissionMode` RPC),
  于是功能"有时能用有时不能用",取决于你有没有加参数。

**一个半死的功能比一个死功能更难排查。**

### 1.3 修法

给 Ink 一个真的面板,照抄 `EffortPicker` 的形状(它就是 `/effort-pick`
的 Ink 版,是一等公民):

- `src/components/ModePicker.tsx`(新)——`↑/↓` / `j`/`k` 导航、Enter 选定、Esc 取消;
- `src/state.ts`——`PanelId` 加一个 `"mode"`;
- `src/tui.tsx`——`PANEL_BY_INTENT["mode-picker"] = "mode"`、
  `PANEL_META.mode`、面板 switch 里的 `case "mode":`;
- `src/panelIntents.ts`——新增一行 `mode-picker`,声明
  `sentinels: ["__PICK_MODE__"]`、`ink: "mounted"`、`line: "rendered"`。

**还有一处形状上的修正。** cmd.exe 那边原来是在调用方用一个
`if (slash?.local === "__PICK_MODE__")` 单独处理的,而所有别的
sentinel 都走 `renderPanelSentinel` 的 `switch`。注册表的
"line 模式认领了吗"是**按 `case "__X__":` 推导**的,所以一个用
`if` 认领的 sentinel **在推导里等于没被认领**。本轮把它移进
那个 switch:

```ts
case "__PICK_MODE__":
  await pickMode(ctx.lineInput, ctx.state.permissionMode, …);
  return true;
```

> **被认领的形状,必须是注册表看得见的形状。**
> 一个用注册表看不见的方式被认领的 sentinel,就是一个注册表无法背书的 sentinel。

---

## 2. 缺陷:词表写在六个文件里

### 2.1 测量(不是回忆)

R385 的门禁自己会数这件事。测量脚本读**改之前**的树
(`git show HEAD:<file>`),数"整串内容恰好是某个模式名"的字符串字面量:

```
=== HEAD (1499aed) ===
   16  line.ts
   11  components/Header.tsx
    7  tui.tsx
    3  components/Welcome.tsx
    1  commands.ts
    1  state.ts
  TOTAL: 39 literals across 6 files
```

改完之后:**6 个文件里 0 个**。

### 2.2 这三十九个字面量已经在互相矛盾

| 位置 | 矛盾 |
|------|------|
| `line.ts` `modeStyle` | `DEFAULT` → 徽章 `"ASK"` |
| `components/Header.tsx` `modeLabel` | `DEFAULT` **没有分支** → 落到 default → 徽章 `"DEFAULT"` |
| `components/Header.tsx` `modeStyle` | 多出 `AUTO` / `ASK` / `DENY` / `BYPASS` 四个 **daemon 根本产生不了**的名字(那是旧的 `Action` 枚举) |
| `tui.tsx` `getState` 兜底 | `?? "ACCEPT_TASK"` |
| `state.ts` / `line.ts` 兜底 | `?? "DEFAULT"` |

**两个渲染器给同一个模式显示不同的徽章**——Ink 显示 `DEFAULT`,
cmd.exe 显示 `ASK`。而 Ink 的兜底更严重:daemon 的默认值是
`PermissionMode.DEFAULT`(`AppState.permissionMode = PermissionMode.DEFAULT`),
Ink 却报 `ACCEPT_TASK`。用户因此看到"本任务内全部自动放行",
**而实际上 daemon 在逐次询问**。

这不是显示问题。这是**关于用户安全网的谎报**。

### 2.3 目录还漏了一个模式

daemon 的 `org.aethercode.core.permission.PermissionMode` 有**七个**常量:

```
DEFAULT, ASK_BEFORE_TOOL, ACCEPT_EDITS, BYPASS_PERMISSIONS,
PLAN, AUTO_READ_ONLY, ACCEPT_TASK
```

而 TS 那一侧的目录只有**六个**——缺 `ASK_BEFORE_TOOL`。它自己的
javadoc 写着:

> explicit "ask before every tool call" mode. Semantically identical
> to `DEFAULT` … but the name is **more discoverable in the TUI's
> status bar and command palette**.

也就是说,daemon 专门为 TUI 起了一个名字,而 TUI 自己**没有用它**。

### 2.4 修法:一份目录,一个枚举

`src/permissionModes.ts`(新)持有全部:

```ts
export const MODE = {
  DEFAULT: "DEFAULT",
  ASK_BEFORE_TOOL: "ASK_BEFORE_TOOL",
  ACCEPT_TASK: "ACCEPT_TASK",
  ACCEPT_EDITS: "ACCEPT_EDITS",
  BYPASS_PERMISSIONS: "BYPASS_PERMISSIONS",
  PLAN: "PLAN",
  AUTO_READ_ONLY: "AUTO_READ_ONLY",
} as const;
```

词表**只在这里写**。其它文件一律 `MODE.DEFAULT` 这样引用。
每行还带着 label / badge / blurb / tone,渲染器各自映射自己的调色板
(Ink 用色名,cmd.exe 用 ANSI 码)——**语义共享,渲染本地**。

---

## 3. 缺陷:`/mode <name>` 直接失败

daemon 端:

```java
mode = PermissionMode.valueOf(modeStr);
```

`valueOf` **大小写敏感**,也**不认横线**。于是:

| 用户输入 | 改之前 | 改之后 |
|----------|--------|--------|
| `/mode DEFAULT` | ✅ | ✅ |
| `/mode default` | ❌ `unknown permission mode: default` | ✅ |
| `/mode ask-before-tool` | ❌ | ✅ |
| `/mode ACCEPT_TASK` | ✅ | ✅ |

第二条尤其荒唐:**daemon 自己的 javadoc 把 `/mode ask-before-tool`
写成 TUI 的用法**,而 TUI 照抄就会失败。

修法是一条规则,不是一张同义词表:

```ts
export function normalisePermissionMode(arg: string): string {
  return arg.trim().toUpperCase().replace(/-/g, "_");
}
```

**不发明新词汇**——只有用户合理会打出的两种拼写。

---

## 4. 门禁

`scripts/test/r385-mode-surface.test.mjs`,13 条断言,分三节。

### 4.1 §1 目录**就是** daemon 的枚举

读 `aethercode-core/.../permission/PermissionMode.java`,抽出枚举常量,
和 TS 的 `MODE` **双向**比较:

- daemon 有、我们没有 → 该模式在 UI 里选不到;
- 我们有、daemon 没有 → 选了会失败。

### 4.2 §2 词表只写一遍

- **反向**:除 `permissionModes.ts` 外,任何源文件都不得含有
  "整串内容恰好是模式名"的字符串字面量。**散文提到模式名是允许的**
  (`--permission-mode=ACCEPT_TASK or BYPASS_PERMISSIONS` 是帮助文本);
  抄一份词表不允许。
- **正向**:用了 `MODE.` / `PERMISSION_MODES` / `permissionModeBlurb` 等
  名字的文件,**必须真的 import 它**——只 import 模块里的另一个函数不算。

### 4.3 §3 每个 sentinel 在两个渲染器上都有归属

对 `commands.ts` 能产生的**每一个** sentinel:

- 有注册表行的 → Ink 侧的归属**推导**出来:
  `PANEL_BY_INTENT[row.id]` 存在 → 该 PanelId 在面板 switch 里被渲染 →
  该行指名的组件文件在磁盘上 → `tui.tsx` 真的 import 了它;
- 没有注册表行的("动作家族")→ `tui.tsx` 必须**指名**它;
- 两边都必须**指名**它(cmd.exe 侧)。

反向也查:注册表里声明的 sentinel,命令层必须产生得出来。

### 4.4 门禁表

| 断言 | 守住什么 |
|------|---------|
| the permission-mode catalog is exactly the daemon's enum | 目录不漏、不多 |
| the catalog's rows cover exactly the vocabulary | 选择器列出的行 = 词表 |
| every mode row is complete | 每行都有 label / badge / blurb / tone |
| the permission-mode vocabulary is written in exactly one file | 词表不复制 |
| a file that USES the catalog imports it | 用了就得 import |
| every sentinel … has an owner on BOTH renderers | **本轮的头号缺陷** |
| the registry declares no sentinel the command layer cannot emit | 注册表不广告死面板 |
| the /mode picker is a REAL surface on both renderers | 面板不是纸面上的 |
| the picker lists the modes the daemon has … | 两端列同一份 |
| the normalisation is total over the vocabulary | 归一化对每个模式都成立 |
| /mode \<name\> puts the daemon's spelling on the wire | **行为**断言,不是源码 pin |
| bare /mode opens the picker on both renderers | 裸 `/mode` 的路由 |
| the catalog resolves a mode the way the renderers ask | 解析一致 |

---

## 5. 变异电池

`scripts/test/r385-mutate.mjs`,12 个用例,**全部被抓**。

| 变异 | 门禁 fail |
|------|-----------|
| `/mode` 的注册表行不再声明它的哨兵 | 3 |
| `/mode` 指名的面板不再被渲染 | 1 |
| `PANEL_BY_INTENT` 忘了 `/mode` 那一行 | 1 |
| `ModePicker` 不再被 Ink 渲染器 import | 2 |
| cmd.exe 不再认领 `__PICK_MODE__` | 2 |
| 目录漏掉一个 daemon 有的模式 | 1 |
| 目录造一个 daemon 没有的模式 | 4 |
| 某一行模式丢掉描述 | 1 |
| 某个渲染器又开始写字面量 | 1 |
| Ink 渲染器不再读共享目录 | 1 |
| `/mode <name>` 不再归一化参数 | 1 |
| 命令层产生一个没人认领的哨兵 | 1 |

### 5.1 电池抓到的两个洞——都是**门禁自己的**洞

第一版门禁有两个洞,都被电池当场抓出来:

**洞 1:扫描器在原始文件上会瞎。**
§2 最初用 `scan(原始文件)`。`ts-scan` 遇到**没闭合的引号**会停,
而 `line.ts` 的注释里有一个撇号("the daemon's estimate"),
扫描器把它当成字符串开头,于是**后面几千行全被吞掉**——
那段里的 `"DEFAULT"` 字面量根本看不见,门禁报告"词表只写了一遍",
而副本就摆在文件里。

> R381 已经为 `ts-scan` 的同一类盲点写过教训(一个撇号吞掉 47 行),
> 这一次是**同一个盲点的一层之上**:扫描的对象本身选错了。
> 修法:`scan(codeOf(文件))`,永远扫**去注释后**的文本。

**洞 2:"import 了目录"不等于"import 了目录里的那个名字"。**
"用了就得 import"这条最初只检查"这个文件 import 过 permissionModes.js"。
变异把 `MODE` 从 import 列表里删掉、留下 `permissionModeBlurb`,
门禁照样绿——因为**模块**被 import 了。
现在检查的是**名字**:用到的每个名字都必须在 import 列表里。

---

## 6. 数字

| 量 | 值 | 来源 |
|----|----|------|
| daemon 的权限模式 | **7** | `PermissionMode.java` |
| 改之前的 TS 词表 | 6 个模式 | `line.ts:pickMode` |
| 改之前的字面量 | **39**,分布在 **6** 个文件 | 门禁自带的扫描器,读 `HEAD` 的树 |
| 改之后的字面量 | **0**(`permissionModes.ts` 之外) | 同上 |
| 命令层能产生的哨兵 | **47** | `commands.ts` |
| 注册表覆盖的哨兵 | **19**(14 行) | `panelIntents.ts` |
| 动作家族(无注册表行) | **28** | 47 − 19 |
| 门禁断言 | **13** | `r385-mode-surface.test.mjs` |
| 变异用例 | **12**,全部被抓 | `r385-mutate.mjs` |

---

## 7. 顺带修掉的一处注册表谎言

r362 的 `INLINE_FAMILIES` 是一份**豁免名单**:名单里的哨兵被当作
"内联动作,不需要注册表行"。`PICK_MODE` 一直在名单里。

但 `__PICK_MODE__` **开的是一个面板**。它被豁免,所以它没有注册表行,
所以没有渲染器需要认领它——**豁免名单本身制造了这个孤儿**。

本轮把它从名单里移出,数字基线从 29 改成 28(带理由写进代码),
并把 r362 变异电池里那条钉住名单末项的针脚从**值**改成**形状**
(钉 `]` 而不是钉 `"PICK_MODE"`)——

> **一条钉住某个值的针脚,会跟着那个值一起死。**
> 一条钉住形状的针脚,才能活过这一轮。

更强的契约搬到了新门禁:**每一个哨兵,在两个渲染器上都要有归属。**

---

## 8. 教训

**Lesson 1239 —— 豁免名单里的条目,可能是在描述一个不存在的类别。**
`PICK_MODE` 被登记成"内联动作",而它是**一个面板**。豁免本身
制造了孤儿。判据:豁免名单的每一项都要能回答"它为什么**不**需要
一个归属",而"它恰好没人认领"不是答案。

**Lesson 1240 —— 词表散在 N 个文件时,收拢的收益不在"少写几行"。**
收益是**两个渲染器开始说同一件事**:同一个模式在 Ink 和 cmd.exe
里显示同一个徽章、同一段说明、同一种兜底。三十九个字面量的
代价不是三十九行代码,是**六份会各自漂移的真相**。

**Lesson 1241 —— daemon 的枚举是权威,客户端的副本要对着源码核。**
TS 目录缺 `ASK_BEFORE_TOOL`,而那个模式的 javadoc 写着它是**专门
为 TUI 起的名字**。门禁读 `PermissionMode.java` 而不是读注释,
所以它抓得住这种漂移。

**Lesson 1242 —— 一个常量"在另一个渲染器里叫什么"也是契约。**
Ink 显示 `DEFAULT`、cmd.exe 显示 `ASK`,两个渲染器都在"正常工作",
用户看到的是两个不同的世界。徽章要跟词表走,不能各写各的。

**Lesson 1243 —— 兜底值也是断言,而且经常是谎报。**
daemon 的默认是 `DEFAULT`,Ink 的兜底写 `ACCEPT_TASK`。
"daemon 还没回答时我们显示什么"是一个**用户可见的安全承诺**。
它必须对着 daemon 的源码写,不能凭印象。

**Lesson 1244 —— 扫描器要扫去注释后的文本,否则它会藏起副本。**
`ts-scan` 遇到没闭合的引号会停;`line.ts` 注释里的一个撇号就够
让后面几千行从扫描结果里消失。**一个看不见的副本,和一个不存在的
副本,在门禁眼里没有区别。**

**Lesson 1245 —— "import 了模块"不等于"import 了模块里的那个名字"。**
用 `MODE.` 却不 import `MODE`,模块级的检查照样绿。
用到的**名字**必须在 import 列表里。

**Lesson 1246 —— 一个 sentinel 被认领的形状,必须是契约看得见的形状。**
cmd.exe 原来用 `if (local === "__PICK_MODE__")` 认领它,而注册表的
推导找的是 `case "__X__":`。**认领得对,但认领成了一种没人能核对
的形状**,于是它等于没被认领。把形状也统一,比把功能补上更重要。

**Lesson 1247 —— 假失败比缺失更糟,因为它会让人绕路。**
用户看到"还没有面板"会以为功能不存在,于是改用手打参数——
而手打参数**恰好是能用的**,于是功能变成"有时行有时不行"。
半死的功能比死功能更难排查。
