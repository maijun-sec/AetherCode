# R383 —— 判决来自 daemon,渲染器不自己判

> 引擎会**装饰**它的 stopReason,而 TUI 和桌面各自写了一份解析器,两份都写错了。
> 结果是:最常见的失败(上游 4xx/5xx)在状态条上显示**绿色 "✓ ready"**。
> 这一轮把判决搬到线上(`isError` + `stopKind`),渲染器只负责措辞。

---

## 0. 这一轮做了什么

用户指令是「继续优化」。本轮选中的是**判决链**——它是"看起来一切正常,其实引擎早就死了"
这一类症状的根源,而且它同时影响 TUI 和桌面两个渲染器。

| # | 缺陷 | 症状 | 修法 |
|---|------|------|------|
| 1.1 | TUI 的分类规则匹配 `"error_"` / `"_error"`(下划线),引擎发的是 `"error:"`(冒号) | 上游 500 显示绿色 "✓ ready" | 判决上线,渲染器不自己判 |
| 1.2 | 桌面漏了 `loop_detected`,却带着 `loop` / `max_turns` 两个引擎从不发的 kind | 死亡螺旋显示 "● Idle",没有 ↻ 重试 | 同上 + 修正死条目 |
| 1.3 | `StatusBar` 有**两张**标签表(按原因查 / 按种类查),而且互相矛盾 | "stopped: loop" 被涂成成功色 | 删掉按原因查的那张 |
| 1.4 | 旧门禁把两份拷贝**互相钉住** | 共同犯错时两份完全一致,门禁全绿 | 门禁改成"只能有一份 + 必须与引擎对齐" |

---

## 1. 缺陷:三个渲染器,三份解析器

### 1.1 引擎装饰 stopReason

引擎的失败不是裸字符串:

```
error: WebClientResponseException$InternalServerError: 500 from POST https://api.minimaxi.com/v1/chat/completions
runtime_exception: java.lang.IllegalStateException: boom
loop_detected: empty_tool_input (4 consecutive empty tool calls)
no_run_end: consumer exited without terminal event
```

所以**任何**想知道"这次运行失败了吗"的客户端,都必须自己重新解析这个字符串。
而每一个这么做的客户端,都写出了自己的解析器:

| 消费者 | 它怎么判 | 错在哪 |
|--------|----------|--------|
| `src/state.ts`（TUI 旧副本） | `r === "error" \|\| r.startsWith("error_") \|\| r.endsWith("_error")` | 引擎发的是 `error:` —— **下划线版永远不命中** |
| `src/line.ts`（TUI 第二副本） | 同上 | 同上 |
| `aethercode-desktop/src/store/index.ts` | 前缀匹配一张 kind 表 | 表里有 `loop` / `max_turns`（引擎从不发）,**却漏了 `loop_detected`** |

### 1.2 探针证据

`D:\tmp\r383-scratch\probe-stop-kind.mjs` 把引擎**真实发出**的 13 个 reason 跑过
"daemon 的判决"和"两个渲染器各自的规则":

```
REASON (engine)                        | daemon verdict | TUI shows              | desktop shows
----------------------------------------+----------------+------------------------+-------------------------
error                                  | FAILURE       | stopped: error         | Failed (error)
error: WebClientResponseException$Inte..| FAILURE       | WRONG->✓ ready (GREEN) | Failed (error)
runtime_exception: java.lang.IllegalSt..| FAILURE       | WRONG->✓ ready (GREEN) | Failed (runtime_exception)
loop_detected: empty_tool_input (4 con..| FAILURE       | stopped: loop          | MISS->Idle (no failure pill)
same_error_hard_stop                    | FAILURE       | WRONG->✓ ready (GREEN) | Failed (same_error_hard_stop)
stale_force_end                         | FAILURE       | WRONG->✓ ready (GREEN) | Failed (stale_force_end)
----------------------------------------+----------------+------------------------+-------------------------
TUI misreports 4 of 13 reasons as a green success.
Desktop misses 1 failure(s) the daemon already reported.
```

**13 个里错 4 个**,而且错的全是"引擎已经判定为失败"的那一类。
最要命的是 `error: WebClientResponseException$…` —— 上游 500,最常见的失败,
TUI 把它报成了成功。

### 1.3 旧门禁为什么抓不到

`scripts/test/line-runend.test.mjs` 里有一条:

> "state.ts and line.ts classifyStopReason are equivalent"

它证明两份拷贝**一致**,不能证明它们**正确**。
**两份一起错的拷贝,天然完全一致。** 这类门禁测的是一个事实,不是正确性。

---

## 2. 修法一:判决上线

daemon 其实早就会回答这个问题了 —— R750d 把 `isError` 放上了线。
这一轮补上 `stopKind`（引擎自己的裸 kind）,两个字段**都**来自引擎的分类法:

`aethercode/aethercode-protocol/.../AetherCodeMethods.java` `eventToMap`:

```java
r.put("stopKind", re.bareKind());
...
if (re.isErrorOutcome()) {
    r.put("isError", true);
    r.put("error", re.stopReason());
}
```

* `stopKind` **总是**带上 —— 客户端要能区分"这一轮干净地结束了"和"这一帧没说";
* `isError` **只在失败时**带上 —— 缺失 ≠ false,两者语义不同;
* 字段是**增量**的,老客户端不受影响。

配套的 Java 测试:`aethercode/aethercode-protocol/src/test/java/org/aethercode/protocol/methods/RunEndWireKindR383Test.java`
(18 个用例,含参数化的失败/干净两族)。

---

## 3. 修法二:一份分类法

新模块 `aethercode-tui/src/stopKind.ts` 是 TUI 侧**唯一**知道"一次运行怎么分类"的地方:

```
判决的优先级:
  1. daemon 的 stopKind  —— 权威 kind
  2. daemon 的 isError    —— 权威判决
  3. 本地兜底             —— 仅当 daemon **没有表达判决**时
```

第 3 条的兜底集合 `ENGINE_ERROR_KINDS` 是**给老 daemon 用的**,并且被门禁
逐项钉在 `StreamEvent.isErrorOutcomeOf` 的 `case` 标签上——两侧必须完全相等。

几个刻意的设计:

* **措辞可以细化,判决不行。** `same_error_hard_stop` 是失败,但用户需要听到的是
  "loop"(引擎确实在反复调同一个东西),所以 loop/max/empty 的措辞判定排在判决之前。
* **"没有判决"和"判决是 false"是两回事。** daemon 只在失败时写 `isError`,
  所以 `isError === undefined` 才是"可以兜底"的条件。
  这一条是被门禁抓出来的:最初的写法是"没有 `stopKind` 才兜底",
  于是"有 kind 但没有判决"(字段被中间层丢掉、版本错配)会把失败报成成功。
* **`null` 不是 `"ok"`。** 一次运行都没结束过,和"干净地结束了",必须能区分开。

`state.ts` 和 `line.ts` 各自的那份拷贝删掉了,改成 import。
`state.ts` 仍然 re-export,方便外部消费者 —— 但那不是第二份定义。

---

## 4. 修法三:StatusBar 的第二张标签表

`StatusBar.tsx` 原来有**两张**表:

* `STOP_LABEL` —— 按 stopReason 查;
* `KIND_LABEL` —— 按显示种类查。

渲染时查的是**第一张**。而第一张有三个独立的问题:

1. **永远命中不了。** 引擎发的是装饰过的 reason,按原串查表必然 miss ——
   于是每个失败都掉进 `?? KIND_LABEL[...]`,显示绿色的 "✓ ready"。
2. **和分类器打架。** `same_fingerprint` 在表里写着 "stopped: loop",
   而分类器判它是 "ok" —— 于是状态条**用成功的颜色**写着 "stopped: loop"。
3. **带死条目。** 表里的 `same_fingerprint` / `same_error` / `long_output` /
   `user_interrupt` 是 `ProgressLoopDetector.lastLoopKind()` 的值 ——
   **检测器的 kind,不是 stop reason**。引擎真正的 stop reason 是
   `"loop_detected: <tier> (…)"`,kind 只在装饰里。这四条永远不会被查到。

结论:`STOP_LABEL` 整张删掉。**它每一个存活条目都已经被 `KIND_LABEL` 覆盖**
(`end_turn`→"✓ ready"、`loop_detected`→"stopped: loop"、
`max_iterations`→"stopped: max turns"……),删掉不损失任何措辞,
却少了一张会漂的表、少四个死条目,并且**措辞和颜色从此来自同一个字段**
(`state.lastStopKind`)。

---

## 5. 门禁

新门禁 `aethercode-tui/scripts/test/r383-stop-kind.test.mjs`(19 个用例):

1. daemon 的 `run_end` 帧带着 `stopKind`(总是)和 `isError`(失败时),
   而且都取自引擎的分类法;
2. 桌面的兜底表**覆盖了引擎的每一个失败 kind**,并且**不含**引擎从不发的 kind;
3. 分类法**只存在于一个模块**(`src/stopKind.ts`);
4. TUI 的兜底失败集合与 `StreamEvent.isErrorOutcomeOf` **逐项相等**(双向);
5. 引擎**真实发出**的每一个 reason 分类正确(带来源标注,没有来源的行不许写);
6. 两个渲染器都把 daemon 的判决传下去;
7. `StatusBar` 只有一张标签表、按种类查、且不碰原始 reason。

变异电池 `scripts/test/r383-mutate.mjs`:**18 个用例,全部被捕获**。

| 被变异的行为 | 门禁的反应 |
|--------------|-----------|
| daemon 不再发 `stopKind` | 红 |
| 判决改回手写前缀判断 | 红 |
| 引擎删掉一个失败 kind | 红 |
| TUI 的兜底集合漂移 | 红 |
| 兜底只在"没有 kind"时才查 | 红 |
| 兜底完全不查 | 红 |
| loop 措辞不再优先 | 红 |
| 裸 kind 不再按冒号切 | 红 |
| 渲染器长出自己的拷贝 | 红 |
| 渲染器不再转发 daemon 的判决 | 红 |
| reducer 丢弃 daemon 的判决 | 红 |
| `StatusBar` 长出第二张表 | 红 |
| `StatusBar` 又按原始 reason 查 | 红 |
| `StatusBar` 的表少一个 kind | 红 |
| 桌面兜底表少一个引擎 kind | 红 |
| 桌面兜底表多一个死 kind | 红 |

**门禁自己抓到了两个真缺陷**,都是先红后修:

* Java 的 `case "no_run_end", "consumer_timeout" -> true;` 是**一条 arm 两个标签**。
  提取时只取第一个标签,于是"引擎集合"少了一半 —— 门禁当场红。
* `reducer stops passing the daemon's verdict` 这一条变异**存活了**:
  门禁当时只查 `/isError\s*:/`(键在不在),而 `isError: undefined` 键是在的。
  改成查**值**(`/isError\s*:\s*action\.isError/`)之后才变红。

---

## 6. 顺带修掉的

* **一个早就红的门禁变绿。** `line-runend.test.mjs` 里那条
  `kind === "loop" ? C.red` 的旧针脚(函数早就改成 switch 了)——
  改成 `/case "loop":\s+return C\.red/`,行为没变,针脚不再失效。
  全量 TUI 套件因此从 43 个红降到 **42** 个(42 个是已知的、与本轮无关的)。
* **桌面的 `failedStopKinds` 修正,不只是扩充。** `'loop'` → `'loop_detected'`,
  删掉 `'max_turns'`(那是显示种类,不是引擎 reason;而且撞了 turn 上限不算失败),
  补上 daemon 报为失败的两个消费端终止事件。

---

## 6.1 发布:0.2.73

0.2.72 的包是 R383 **之前**构建的 —— 里面的 TUI 还带着"死引擎显示绿色 ✓"这个 bug,
daemon jar 也没有 `stopKind`。所以本轮顺带出了 **0.2.73**。

构建链(每一步都验过,不是"构建成功"就算):

| 步骤 | 结果 |
|------|------|
| 全量 reactor `mvn install` | **BUILD SUCCESS in 643.9s**;surefire **5905 tests / 0 failures / 0 errors**(含 R383 的 18 个) |
| TUI bundle `npm run build` | `dist/ac-tui.js` = 2,610,779 B(比 0.2.72 多 1,113 B —— 新增 `stopKind.js`) |
| 独立 exe `bun build --compile` | `dist/ac-tui-standalone.exe` = 100,615,680 B |
| 桌面端 `tauri build` | exe 6,677,504 B;MSI 57,126,912 B;NSIS 55,416,896 B |
| 发布目录 | `release/aethercode-0.2.73/` + `release/aethercode-0.2.73.zip`(312,558,646 B) |

三条校验(全部通过):

* **README 16/16** —— 目录树与磁盘**双向**一致(23 个条目),8 个大小全部从文件重新推导;
* **zip 18/18** —— 无反斜杠条目名、单一顶层目录、无 `.aethercode` 泄漏、15 个必需产物齐全、
  9 个 zip 副本与发布目录同尺寸、zip 里的 README 是干净 UTF-8;
* **产物内容 9 + 5 项** —— 把 jar 从 zip 里**取出来**再打开,grep 它的常量池:
  `AetherCodeMethods.class` 带 `stopKind` / `isError` / `bareKind`,
  `StreamEvent.class` 带 7 个失败 kind,`ac-tui.js` 和 `ac-tui-standalone.exe`
  带 `classifyRunEnd` 和 `0.2.73`。
  **"构建成功"不等于"补丁在包里"** —— 这一步是 R735b 的教训。

踩到的两个坑:

* `providers.yaml` 有**两份**。`aethercode-models/src/main/resources/providers.yaml`
  (5,133 B)是 models 注册表用的;**运维要的那份**是 jar 根目录的
  `providers.yaml`(9,844 B,来自 `aethercode-core/src/main/resources/providers.yaml.sample`)。
  第一次拷错了,被 README 校验的**大小表**当场抓到。
* `7z a` 会把**当前目录**的相对路径存进去。在仓库根目录打就多一层 `release/`
  —— 顶层目录不对,zip 校验 6 项红。进 `release/` 目录再打才对。

---

## 7. 遗留与下一轮候选

* **`cancelled` / `awaiting_user_decision` 显示为 "✓ ready"。**
  daemon 的分类法明确说这两个**不是失败**,所以本轮不动它们。但"用户按了 Esc"
  显示成"✓ ready"在措辞上仍然别扭,值得单独一轮加一个 `cancelled` 显示种类。
* **`max_iterations` / `stale_force_end` 现在没有生产者。**
  硬性 turn 上限已经被 `TodoRunController` 取代(`maxTurnsPerQuery = -1`),
  `stale_force_end` 只存在于分类法里。它们留在兜底集合里是对的
  (分类法说有,就得认),但值得记一笔:**分类法比生产者大,不是 bug,是防御**。
* **`EngineContinuationDispatcher` 的两个终止事件**
  (`no_run_end` / `consumer_timeout`)是协议层造的,不经过 `eventToMap` 的
  `run_end` 分支,它们是**另一条**发帧路径(`AetherCodeMethods` 里另外两处
  `endEvent.put("isError", true)`)。这一轮只统一了分类,**没有统一发帧路径**。
  下一轮可以合并。

---

## Lessons

* **Lesson 1225 —— 渲染器不自己判断一次运行有没有失败。**
  判决来自 daemon(`isError`),kind 来自 daemon(`stopKind`),
  渲染器只负责**措辞**。任何"客户端重新解析 stopReason"的设计,都是把一份判决
  复制到了 N 个地方,而 N 个地方会漂。
* **Lesson 1226 —— 把两份拷贝互相钉住的门禁不是门禁。**
  旧门禁证明 `state.ts` 和 `line.ts` 的分类器**等价**。
  它证明不了它们**正确**:两份一起错的拷贝天然完全一致。
  门禁要问的是"**有几份**",不是"它们**一样吗**"。
* **Lesson 1227 —— 引擎会装饰 stopReason,所以每个客户端都会自己写一个解析器,
  而每个解析器都会写错。** 只要协议里的字符串是装饰过的,"谁来分类"就不是
  客户端的事。**把 kind 放到线上**,比在每个客户端修解析器便宜一个数量级。
* **Lesson 1228 —— 一张"按原因查"的表和一张"按种类查"的表可以互相矛盾,
  而且矛盾是看不见的。** `same_fingerprint` 在一张表里写着 "stopped: loop",
  另一张表判它是 "ok" —— 两张表都看着合理,合起来把一句话涂成了成功色。
  标签表**必须只有一张**,且按**判决的产物**查。
* **Lesson 1229 —— 提取一个 Java `switch` 的所有 case 标签时,必须取整条 arm。**
  `case "no_run_end", "consumer_timeout" -> true;` 是**一条 arm 两个标签**。
  只取第一个标签会让"引擎集合"少一半,而**比较会照常进行**——
  两侧不一致,却要等门禁自己红才发现。
  同族的还有 `case` 后面带注释、带 `->` 表达式、跨行的写法。
* **Lesson 1230 —— "这个键在不在"和"这个键的值被用上了"不是一回事。**
  `isError: undefined` 满足 `/isError\s*:/`,但判决已经被丢掉了。
  **存在性断言会通过一个"键在、值没了"的写法。**
  (这是 Lesson 1217 的另一种伪装:1217 是"查表能满足检查",
  这里是"键的**存在**就能满足检查"。)
* **Lesson 1231 —— 循环检测器的 kind 不是 stop reason。**
  `same_fingerprint` / `same_error` / `long_output` / `user_interrupt` /
  `empty_tool_input` 是 `ProgressLoopDetector.lastLoopKind()` 的值,
  它们**待在装饰里**(`loop_detected: <tier> (…)`),从来不是 stop reason 的裸 kind。
  一张列了它们的标签表,列的是**永远不会发生的东西**。
  判据:**这个字符串是"谁产出的"** —— 引擎在 RunEnd 里发的,还是检测器报的。

---

## 相关文件

| 文件 | 作用 |
|------|------|
| `aethercode/aethercode-core/.../stream/StreamEvent.java` | 分类法的**唯一所有者**(`bareKindOf` / `isErrorOutcomeOf`) |
| `aethercode/aethercode-protocol/.../methods/AetherCodeMethods.java` | 把 `stopKind` + `isError` 放上线 |
| `aethercode/aethercode-protocol/src/test/.../RunEndWireKindR383Test.java` | wire 契约(18 用例) |
| `aethercode-tui/src/stopKind.ts` | TUI 侧**唯一**的分类法 |
| `aethercode-tui/src/state.ts` / `src/line.ts` | 消费方(不再自带拷贝) |
| `aethercode-tui/src/components/StatusBar.tsx` | 只剩一张标签表 |
| `aethercode-tui/scripts/test/r383-stop-kind.test.mjs` | 门禁(19 用例) |
| `aethercode-tui/scripts/test/r383-mutate.mjs` | 变异电池(18 用例,全捕获) |
| `aethercode-desktop/src/store/index.ts` | 桌面的兜底表(修正后) |
