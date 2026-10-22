# R367 — 让 permission/* 和 grants/* 真正可达

日期：2026-10-22
范围：`aethercode-protocol` / `aethercode-cli`（Java）、`aethercode-tui`（门禁 + `line.ts`）

## 一句话

`/consents` 面板渲染了很久，但它的 RPC 一直是 `-32601`；
把 daemon 接线修好之后，如果不接着修 `asRows()`，面板会从
**诚实的"端点缺失"提示** 变成 **自信的空列表** —— 修复会让谎言变大。

## 缺陷本体

三件事叠在一起，每一件单独看都像完成了：

| 层 | 状态 | 后果 |
|---|---|---|
| `PermissionMethods` | 完整实现 + 5 个 `METHOD_*` 常量 + `registerAll` | 无 |
| `GrantMethods` | 完整实现 + 4 个 `METHOD_*` 常量 + `registerAll` | 无 |
| `DaemonRunner` | **从未调用这两个 `registerAll`** | TUI 每个调用都是 `-32601` |
| `line.ts` `asRows()` | **key 列表里没有 `"grants"`** | 接通后 `/consents` 显示 "0 stored" |
| `HttpJsonRpcServer` | **没有 `permission/*` / `grants/*` 的 case** | desktop 仍然 `-32601` |
| `GrantMethods` 公开 API | **只有 `registerAll`** | HTTP 路径**根本无法**路由 `grants/*` |

这是 R720 `memory/*`（Lesson 1022，two-layer fix）和 R366 `model/set`
（DTO 齐了、handler 没接）的第三次同型。**同一个根因第三次出现，
所以这一轮的产出不只是接线，还有让"接线缺失"以后无法再静默通过的门禁。**

## 改了什么

### Java

`DaemonRunner.runInternal`（stdio）—— `permRpcs.registerAll` / `grantRpcs.registerAll`，
根目录显式写成 `Path.of(System.getProperty("user.home")).resolve(".aethercode")`。

`DaemonRunner.runHttp`（HTTP+WS）—— 同一套面在 HTTP 路径重建一次并
`http.setPermissionMethods` / `http.setGrantMethods`。
`runInternal` 和 `runHttp` 是两个入口方法，一个 daemon 只跑其中一个，
所以两边各自构造；**但必须用同一个 root 和同一个 cwd**，
否则 stdio 写进去的 grant 在 HTTP 上看不见。

`HttpJsonRpcServer` —— 新增两个字段、两个 setter、两个 `route*` helper，
以及 9 个 `case`。注意它和 `routeMemory` 的**故意差异**：

> `routeMemory` 未配置时返回 `{ok:false, reason:"not configured"}`。
> 但 `permission/list` 如果也这么返回，那是一个**形状撒谎** ——
> `asRows()` 看到对象里没有数组 key，返回 `[]`，
> `/consents` 渲染 "0 stored grants" 且无任何报错。
> 所以 `routePermission` / `routeGrant` **抛错**，不返回信封。

`GrantMethods` —— 新增 `list` / `revoke` / `clear` / `setPreset` 四个直通方法。
在此之前它**只有一个公开成员 `registerAll`**，所以 `grants/*`
在结构上就只可能通过 `JsonRpcDispatcher` 到达；
**只能被一种传输访问的方法类，是一个单传输功能。**

### TypeScript

`line.ts` `asRows()` —— key 列表加入 `"grants"`。
`grants/list` 返回 `{grants:[...]}`（`GrantHandlers.list`），
而 `permission/list` 返回裸数组 —— 两个方法的信封形状不一样，
而 `panelConsents` 先试 `grants/list`。

### 门禁

`r366-rpc-names.test.mjs`（9 → 14 个用例）：

1. **注册 ≠ 可达。** 新增 `EXTRA_METHOD_CLASSES`，
   对每个"自己带 `registerAll` 的方法类"要求 `DaemonRunner`
   真的调了它的 `registerAll`。原来的门禁只问
   "这个名字在不在某个 Java 文件里"—— 对 R367 之前的代码**恰好是绿的**。
2. **两个传输都要路由。** 9 个名字必须同时出现在
   `HttpJsonRpcServer` 的 `case` 里，且两个 setter 必须被 `DaemonRunner` 调用。
   TUI 只走 stdio，所以纯 stdio 的修法在 TUI 侧全绿，只有 desktop 坏掉。
3. **信封 key 对账扩展到 `GrantHandlers`。**
   `grants/list` 的信封不在 `AetherCodeMethods` 里，在另一个模块。
4. **`javaCode()` 自测**（见下）。
5. **移除 `BACKEND_MISSING` 里的 `permission/list` / `permission/revoke` / `grants/revoke`**，10 → 7。

## 三条值得单独记住的教训

### 1. "类能用"和"类被调用"是两个命题，而门禁只测了第一个

R366 的门禁会问：`permission/list` 在 `PermissionMethods.java` 里吗？在。绿。
它不会问：`DaemonRunner` 调了 `PermissionMethods.registerAll` 吗？**不问**。

这跟 R366 记录的 `model/set` 是同一个洞 —— DTO 存在、handler 没接。
区别只是这次洞在 Java 侧、在一个已经有门禁的仓库里，
而门禁的模型（"名字在文件里 = 方法可用"）刚好绕过了它。

> **Lesson：可达性问题的门禁必须同时问「谁注册的」和「谁调用的」。**
> 只问前者，等于给一整类"实现完整但从未接线"的功能发免罪符。

### 2. 注释剥离器的第二个实例：撇号陷阱

这一轮的门禁要读 `DaemonRunner.java`，所以需要一个 Java 注释剥离器。
我写了它，变异测试立刻指出它坏 —— 而且坏法和 R364 一模一样：

```java
// MemoryMethods.registerAll(); the HTTP path needs the
// LayeredMemoryStore attached (the stdio daemon's
```

`daemon's` 里的撇号。如果扫描器把它当成字符字面量的开头，
它会一直吞到下一个撇号为止 —— **实测吞掉 2.4 KB 真实代码**，
其中就包括门禁自己要读的 `http.setPermissionMethods(...)` 那行。

R364 已经在 TypeScript 上栽过一次（`line.ts` 里 `"memory/* ..."` 的
`/*` 被当成注释开头，吞掉 916 行 / 24 KB）。
**同一个 bug，换一种语言，换一个文件，又写了一遍。**

> **Lesson 1122：写第二个"去注释扫描器"之前，先读第一个为什么坏。**
> 两次的失效字符不同（`/*` vs `'`），机理完全相同：
> **字面量/注释的定界符出现在另一种状态里。**
> 一旦两种状态共用一个扫描器，交叉污染就是默认行为，不是意外。

### 3. 让修复本身接受变异测试，因为它可能让撒谎变大

把 daemon 接线修好之后，`/consents` 的行为变化是：

- 修之前：`grants/list` → `-32601` → 面板显示"端点缺失"。
  **诚实的坏。**
- 修之后（不修 `asRows`）：`grants/list` → `{grants:[...]}` →
  `asRows` 返回 `[]` → 面板显示 **"0 stored grants"**，
  空状态文案是 "nothing has been auto-allowed yet"。**不诚实的坏。**

第二种比第一种糟，因为用户会相信它。

> **Lesson 1123：端点从不「不可用」变成「可用」时，
> 必须重新审计它在"成功"路径上的每一处假设。**
> `asRows` 在"端点缺失"时是**没被调用**的 —— 它一直是坏的，只是坏得没人看见。

## 变异验证

`PermissionGrantWiringR367Test`（11 个用例，4 个变异）：

| 变异 | 结果 |
|---|---|
| 注释掉 `permRpcs.registerAll` | 红 ✓ |
| 去掉 `.resolve(".aethercode")` | 红 ✓ —— **第一版门禁在这里是绿的** |
| `GrantMethods.from` 的 permissions 文件漂到 `projectDir` | 红 ✓ |
| `PermissionMethods.defaults` 拿到裸 `~`（两跳脱钩） | 红 ✓ |

第一次跑"去掉 `.resolve(".aethercode")`"时门禁**没有变红**。
原因：`src.contains(".resolve(\".aethercode\")")` 在 `DaemonRunner.java` 里
匹配到了**另外两处无关的 `.resolve(".aethercode")`**。
和 r364-mutate 里 `listProjects` 匹配到 `listProjectsDISABLED` 是同一个坑。
改成结构化两跳检查（提取 root 变量的赋值 → 断言它解析 `.aethercode` →
断言 `PermissionMethods.defaults` 的第一个实参就是这个变量）。

`r366-mutate.mjs`（10 → 25 个用例，全捕获）：

- 新增 5 条：注释掉两个 `registerAll`、从 `EXTRA_METHOD_CLASSES` 删条目、
  删 HTTP case arm、删两个 `http.set*`、删 setter。
- 新增 3 条 **"必须变绿"** 的判别力用例（通过标准反转）：
  - `isWired := true` + 取消接线 → 门禁应当**变绿**。
    变红说明除了 `isWired` 还有别的东西在报，判别器是死的。
  - 删掉 R367 回归用例 → 门禁应当**变绿**。变红说明那条用例是摆设。
- `EXTRA_METHOD_CLASSES` 的两条删除变异**最初是绿的**：
  `declaredButUnwired` 是**遍历这个列表**生成的，
  列表里删掉一个类，恰恰就是那个类不再被检查。
  加了 `the extra-method-class list is itself complete` 才抓住。
  → **Lesson 1120 的第四次：allowlist 必须自己被测。**

变异电池顺带改进：失败时打印**失败用例名 + 断言原文**。
加了之后立刻定位了 `javaCode` 的撇号问题 ——
在那之前我基于 `gate fail=1` 推理了三轮，方向全错。
计数不是诊断。

## 诚实失败（顺带钉住）

`permission/revoke` 对不存在的 id 抛 `-32604`；
`grants/revoke` 经 `GrantHandlers.revoke` 的 `Consumer<Map>` 适配器转发，
**只能靠 `JsonRpcProtocolException` 是 `RuntimeException` 才传得出去**。
否则 `GrantHandlers.revoke` 会无条件返回 `{ok:true, id}`，
面板打出 "✓ revoked"，而什么都没删。两个测试把 `-32604` 钉死。

## 未完成 / 有意不做

- `HttpJsonRpcServer` 的 9 个 case 只有源码级门禁覆盖，
  没有 Java 单测（起一个真 server 太贵，R720 的 memory 也没有）。
- `/context` 仍是产品决策：加命令 + 挂 `ContextMeter`，还是删掉占位 intent。
- 视觉验收（真 Ink 交互、80 列）仍未做。
- R362–R367 全部未提交。