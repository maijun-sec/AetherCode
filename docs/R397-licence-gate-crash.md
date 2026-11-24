# R397 — 许可证门禁不报告，它直接崩了

> 2026-11-23。R396 收尾时顺手跑了一次许可证门禁，发现它**崩溃**：
> `node scripts/add-license-headers.mjs --check` 直接抛
> `TypeError: Cannot read properties of undefined (reading 'trimEnd')`，
> 一个文件都没报告。

---

## 0. 结论先说

| | 之前 | 现在 |
| --- | --- | --- |
| `add-license-headers.mjs --check` | **崩溃**（0 个文件被报告） | 正常报告：剩余 6 个缺头的文件，全在 `aethercode-tui/**` |
| 5 个 `.java` 文件的许可证头 | 行注释版（旧形式） | 块注释版（规范形式） |
| 工具自身的回归测试 | 10 例 | **11 例**（新增一条「崩溃」用例） |

---

## 1. 根因

`java` 在工具的风格表里**只有块注释**：

```js
java: { block: ["/*", "*/"] },
```

而检测器在「文件第一行是行注释」时这样写：

```js
if (/^(\/\/|#|REM |--)/.test(t)) {
  const rendered = renderLineHeader(style.line);   // ← style.line 是 undefined
```

`renderLineHeader` 内部直接用 `prefix.trimEnd()` ⇒ **TypeError** ⇒ 整个门禁在报告第一个文件之前就死了。

⇒ 触发文件：**5 个已提交的 `.java`**（R388k / R390 两轮留下的），它们用的是 R389 的**行注释版**短头：

```
// Copyright (c) 2026 maijun
//
// Permission is hereby granted, ...
// SOFTWARE.
```

⇒ 这类文件对 java 来说就是**过时的头形式**，按工具自己的契约应当被**迁移**成块注释形式。

⚠️ **为什么没人发现**：这个工具**不在 CI 上**（`test.yml` 只跑 vitest / node --test / smoke），而**崩溃**和「没有缺头的文件」在终端上长得一模一样——都是「没有输出」。⇒ 一个不会说话的门禁，等于没有门禁。

---

## 2. 修法

### 2.1 工具加保护（1 行 + 注释）

```js
// A BLOCK-ONLY language (Java, CSS, HTML, ...) has no line-comment
// form at all, so `style.line` is undefined for it. ...
// Such a file carries the OBSOLETE line form of the header ... it
// must be MIGRATED to the block form.
if (!style.line) return false;
```

⇒ 语义上也对：块注释语言**没有**行注释形式 ⇒ 这种文件**不是**「已经有规范头」⇒ 走迁移路径。

### 2.2 5 个 `.java` 的头迁移成块注释形式

用**门禁自己的**渲染函数（`renderBlockHeader`）生成，保证字节与规范形式一致；**头以下的每一个字节都逐字节断言未变**。

> ⚠️ 没有直接跑 `add-license-headers.mjs` 的 apply 模式：它扫的是 `git ls-files` **全量**，会连带改到**用户在途的 `aethercode-tui/**` 文件**——那些不是本轮该碰的。⇒ 只对指定的 5 个文件做同样的迁移。

### 2.3 给「崩溃」加一条回归用例

`scripts/test_license_headers.py` 新增第 10 组用例（`java with an obsolete line-form header`）：

- `scenario` 助手**先**断言「工具退出码为 0」⇒ **崩溃本身被钉住**，而不只是终态；
- 断言 `.java` 被迁移成 `/* */` 形式、行注释头消失、**正文原样保留**。

⇒ 变异验证：把守卫去掉 ⇒ 这条用例**精确变红**（报的就是原来那个 `prefix.trimEnd` TypeError），10 passed / 1 failed；加回守卫 ⇒ 11/11 绿，且工具文件**逐字节**还原。

---

## 3. 还剩什么

门禁现在能正常报告了，剩下 **6 个缺头的文件**：

```
aethercode-tui/.prettierignore
aethercode-tui/scripts/test/fixtures/r388r-grouping-probe.ts
aethercode-tui/scripts/test/fixtures/r388r-routing-probe.tsx
aethercode-tui/scripts/test/r388n-auto-mode-mutation.test.mjs
aethercode-tui/src/permissionComment.ts
aethercode-tui/src/sddRun.ts
```

⇒ 全在 `aethercode-tui/**` —— **用户在途的 R389 模块，本轮不碰**。等那摊活干完，这个门禁就能变成绿的。

⇒ 另：`scripts/test_license_headers.py` 自己缺头，也补上了（`# ` 行注释形式，原 docstring 原样保留）。

---

## 4. 教训

**Lesson 1479 — 一个崩掉的门禁和一个永远通过的门禁，在终端上长得一模一样。**
`--check` 崩溃 ⇒ 打印一个 TypeError 栈；「没有缺头的文件」⇒ 打印一行统计。两者都不是「PASS」。
⇒ 这个工具**不在 CI 上**，所以没有人看见它已经死了多久（五个 `.java` 文件从 R388k 起就是那个形状）。
⇒ **Lesson 1480 — 「门禁能跑」和「门禁在跑」是两件事**：把门禁接进 CI，或者至少在**改代码之前**先跑一次它，
否则你不知道它已经哑了多久。
⇒ **Lesson 1481 — 崩溃的修法往往就是契约本身**：块注释语言没有行注释形式 ⇒ 这种文件**不是**「已有规范头」
⇒ 它**必须**迁移。守卫不是「加个 null check 免得炸」，是**把语义说全**。
