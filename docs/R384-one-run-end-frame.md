# R384 —— 一个 run_end 帧,一份契约

> `run_end` 帧的契约写在 `eventToMap` 里,却有**三处**代码在装配这个帧。
> 其中两处手搭的帧**漏了 `stopKind`**——正是 R383 刚放上线的那个字段。
> R383 定了"判决来自 daemon"的规矩,于是客户端读 `isError` 却拿不到 `stopKind`,
> 恰恰发生在**最该说清楚诊断**的两条路径上。

---

## 0. 这一轮做了什么

用户指令是「继续优化」。本轮没有扩功能,而是把 R383 建立的契约**收到一处**。

| # | 缺陷 | 症状 | 修法 |
|---|------|------|------|
| 1.1 | `run_end` 帧有三处装配点 | 两处手搭帧漏 `stopKind` | 唯一装配器 `runEndFrame` |
| 1.2 | 两个合成终态(消费者耗尽 / `catch (Throwable)`)手搭帧 | 精确诊断(`no_run_end`)塌缩成通用 `error` | 全部走同一个装配器 |
| 1.3 | stream-json 那根线上只有装饰过的 reason | 该协议的消费者仍要自己解析 | `result` 帧补 `aethercode_stop_kind` |
| 1.4 | 桌面在"线上有判决但无 kind"时丢弃精度 | R750d–R382 的 daemon 上,`no_run_end` 显示成 `error` | 顺序改成 线上的 kind → 前缀匹配 → 通用 |

顺带修掉两个**工具层**的缺陷(见 §5):一个是会让门禁**静默失效**的 scanner bug,
一个是让门禁**钉死实现位置**的取法。

---

## 1. 缺陷:契约写在一处,在三处被遵守

### 1.1 三处装配点

```
AetherCodeMethods.java:8200   eventToMap     → 引擎自己的 RunEnd
AetherCodeMethods.java:3956   R745 合成终态  → 消费者耗尽,没等到 RunEnd
AetherCodeMethods.java:4042   catch(Throwable)→ 抛出的异常逃出流
```

前两处(R745 合成终态和 `catch` 终态)**手搭**这个帧:

```java
Map<String, Object> endEvent = new LinkedHashMap<>();
endEvent.put("type", "run_end");
endEvent.put("stopReason", "no_run_end: consumer exited without terminal event");
endEvent.put("error", "stream exhausted without a RunEnd event (R745)");
endEvent.put("isError", true);        // ← 没有 stopKind
```

### 1.2 为什么这不是"少个字段"

R383 那一轮刚把规矩定成:**判决来自 daemon**。客户端于是理所当然地假设
`isError` 旁边会有 `stopKind`——因为引擎自己的 `RunEnd` 就是这么发的。

于是:

- 客户端读到 `isError: true`,去读 `stopKind` → **`undefined`**;
- 桌面的"线上优先"路径于是回落到通用 kind,`no_run_end` 变成 `error`;
- 而 `no_run_end` 恰恰是**用户最该看到的**那一种诊断:
  "消费者自己把流耗尽了,引擎根本没机会发终态"。

也就是说,R383 引入的精度,在两条最需要它的路径上被**丢掉了**。这是 R383 自己那条
教训("判决来自 daemon")的下游后果:**判决上了线,但帧的形状没统一**。

### 1.3 探针证据

`D:\tmp\r383-scratch\probe-terminals.mjs` 实测(装配器与两个终态分别提取):

```
### R745 synthetic
  body length: 29290
  hand-built run_end frames in body: 0
### catch(Throwable) terminal
  body length: 29290
  hand-built run_end frames in body: 0
### the assembler
  body length: 444
  hand-built run_end frames in body: 1
```

两个终态所在的方法里**没有**手搭的 `run_end` 帧——这正是修完之后的状态,
门禁现在直接钉这个不变量(见 §3.2)。

---

## 2. 修法:唯一装配器

### 2.1 `runEndFrame`

```java
static Map<String, Object> runEndFrame(String stopReason, String errorDetail) {
    StreamEvent.RunEnd re = new StreamEvent.RunEnd(stopReason, List.of());
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("type", "run_end");
    r.put("stopReason", stopReason);
    r.put("stopKind", re.bareKind());            // 永远在
    if (re.isErrorOutcome()) {                    // 判决来自引擎的分类法
        r.put("isError", true);
        r.put("error", errorDetail != null ? errorDetail : stopReason);
    }
    return r;
}
```

三处全部改成调用它。契约现在**只写一次**:

- `type` = `"run_end"`;
- `stopReason` —— 原始的、可能带装饰的原因;
- `stopKind` —— 引擎的规范裸 kind,**永远在**,所以客户端永远不用重新解析;
- `isError` + `error` —— **恰好**在引擎的分类法说"这是失败"时出现。

两种 kind 和判决都来自引擎自己的 `StreamEvent` 分类法,**从不**手搓 `startsWith`
(R743 的教训)。

### 2.2 另一根线也补上 kind

`ClaudeStreamJsonEmitter` 的 `result` 帧原本只有装饰过的 reason:

```java
frame.put("aethercode_stop_reason", stopReason);
frame.put("aethercode_stop_kind", StreamEvent.bareKindOf(stopReason));  // ← 新增
```

stream-json 是给 Claude Code 兼容层用的,那条线的消费者同样不该重新解析。

---

## 3. 门禁

### 3.1 新的门禁 `scripts/test/r384-run-end-frame.test.mjs`(10 项)

| # | 钉什么 |
|---|--------|
| 1 | 整个 daemon 里**有且只有一个**能装配 `run_end` 帧的地方 |
| 2 | 装配器**总是**带 kind(不在任何条件里) |
| 3 | 判决来自 `isErrorOutcome()`,没有手搓 `startsWith`;`isError` 写在守卫**里面**(干净的运行不带判决) |
| 4 | 两个合成终态**不手搭** `run_end` 帧,而且**仍然发出**终态事件 |
| 5 | stream-json 的 `result` 帧带 kind |
| 6 | TUI 的分类在"线上位到达"前后**不变**(老 daemon 和新 daemon 报告同一件事) |
| 7 | 桌面的顺序是:线上的 kind → 前缀匹配 → 通用 |

变异电池 `scripts/test/r384-mutate.mjs`:**10 个用例,全部捕获**。

### 3.2 两个终态的检查为什么反过来写

原来写的是:

```js
assert.match(methodsCode, /runEndFrame\(\s*"no_run_end: consumer exited without terminal event"/);
```

这钉的是**名字**。改名就红——而改名不是缺陷。

现在写的是**反向的不变量**:

```js
const handBuilt = body.match(/put\(\s*"type"\s*,\s*"run_end"\s*\)/g) ?? [];
assert.deepEqual(handBuilt, [], "合成终态不能手搭 run_end 帧");
```

这比名字钉**更强**:

- 改名 → 绿(代码没问题);
- 新写一个手搭帧 → 红(这才是缺陷)。

配套还加了"两个终态**仍然发出**终态事件"——只钉"不手搭"会放过"干脆不发了"。

### 3.3 R383 门禁的教训:契约钉子必须跟着实现走

R383 的门禁把契约钉在 `eventToMap` 的 `run_end` 分支上。R384 把装配挪进
`runEndFrame` 之后,**那个门禁变红了**——红在一次让代码**变好**的重构上。

于是新增原语 `javaMethodContaining(code, marker)`:按**标记**定位包含它的方法,
而不是按名字或位置。R383 门禁现在读的是"装配 `run_end` 帧的那个方法",
不管它叫什么、在哪个类里。

R384 门禁里剩下的名字钉也一并去掉:

- 装配器的名字**从实现里推导**(`javaMethodInfoContaining`),不写死;
- "唯一装配器"只要求**总数为 1**,不再要求它在某个具体文件里
  (装配器搬家不该看起来像缺陷)。

---

## 4. 桌面的精度修复

R384 之前,桌面这样算失败 kind:

```ts
wireFailed = wireIsError ? (wireKind ?? 'error') : undefined;   // ← 线上有判决但没 kind → 通用
```

R750d–R382 的 daemon 发 `isError` 但**不发** `stopKind`,于是每一条这样的帧
都把 `no_run_end` / `consumer_timeout` 塌缩成 `error`——而那正是 R735b 的
前缀匹配本来能答对的。

R384 之后顺序改成:

```ts
failedKind = wireFailed ?? matchedKind ?? (wireIsError ? 'error' : undefined);
```

**线上的 kind → 本地前缀匹配 → 通用 'error'**。判决没有 kind 时,不再丢弃本地
已经有的精度。

---

## 5. 顺带挖出来的两个工具层缺陷

这两个不属于 `run_end` 契约,但都是这一轮撞见的**真**缺陷,而且都在门禁的地基上。

### 5.1 `javaMethod` 会被字符串字面量里的括号骗到(真 bug)

`javaCode` 把注释清空,但**原样保留字符串字面量**——这是刻意的,门禁要靠它找
`"sessionId"` 这种东西。

后果是:字面量里的 `}` 在剥离后的文本里**仍是一个右括号**,而 `javaMethod`
天真地数括号,于是**提前关掉方法体**。

实测(`D:\tmp\r383-scratch\probe-truncation2.mjs`):`AetherCodeMethods.java` 里有
**978 处**含括号的字符串字面量,全仓库 501 个方法里有 **5 处**提取被截断。

**截断是最贵的一种绿**:方法体少了一半,上面每一条 `assert.match` 都**空洞地通过**。
门禁报绿,而它只读了一半方法。

修法:用**掩码**标出字面量区间,数括号时跳过。比"找字面量边界再跳过去"更简单,
也不会因为边界判断写错而读错区间。爆炸半径实测:**14 个门禁实际用到的提取,0 个变化**。

### 5.2 scanner 没有自测,bug 会被偶然绊倒的 gate 发现

`java-strip.mjs` 原来只有 `javaCode` 有自测(在 r366 门禁里),`javaMethod` 没有。

R381 的教训:**没有自测的 scanner,它的 bug 会被碰巧绊倒它的那个门禁发现,
那不叫计划**。

新增 `scripts/test/java-strip.test.mjs`(**13 项**),三件事各一组用例:

1. `javaCode` 清注释、留字面量、**长度不变**;
2. **括号计数跳过字面量**(用一个真的含 `}` 的字面量做陷阱);
3. `javaMethodContaining` 找的是**方法**——不是最内层的 block;
   lambda、匿名类、静态初始化块都做不了它的对手。

其中"静态初始化块"那条还改了期望值:原语**正确地**报"这里没有方法"。
我一开始的期望是"返回一个块",那会让原语**撒谎**——名字叫 `javaMethod` 却返回
一个不是方法的东西。**宁可报错,不要撒谎。**

---

## 6. 数字

| 指标 | 值 |
|------|-----|
| R384 门禁 | 10 项 / 10 通过 |
| R384 变异电池 | 10 个用例 / **全部捕获** |
| R383 门禁 | 19 项 / 19 通过 |
| R383 变异电池 | 18 个用例 / **全部捕获** |
| scanner 自测 | 13 项 / 13 通过(新增) |
| `aethercode-protocol` | 430 项 / 0 失败 |
| TUI 全量 | 1459 项 / 1417 通过 / 42 失败(42 个已知红,基线未变) |
| desktop vitest | 125 文件通过 / 1 个已知红(R33 `Header.tsx has a tools button`) |

---

## 7. 教训

- **Lesson 1232** —— 契约写在一处、在三处被遵守的,不是契约。
- **Lesson 1233** —— **契约钉子必须跟着实现走**。钉名字的断言会在改名时变红,
  而改名不是缺陷;钉位置的断言会在重构时变红,而重构可能让代码变好。
  R383 的门禁就是这样在 R384 的改进上变红的。钉**不变量**(比如"这里不能手搭帧"),
  而不是钉**写法**。
- **Lesson 1234** —— **被截断的提取是最贵的一种绿**。方法体少一半,上面每一条断言
  都空洞地通过——门禁报绿,而它只读了一半方法。scanner 的括号计数必须跳过字符串字面量。
- **Lesson 1235** —— **没有自测的 scanner,它的 bug 会被碰巧绊倒它的门禁发现,那不叫计划**。
  共享的提取原语要配自己的自测,而不是指望用它的门禁顺带覆盖。
- **Lesson 1236** —— **宁可报错,不要撒谎**。原语找不到方法时应该明确失败,
  而不是悄悄返回一个不是方法的块。
- **Lesson 1237** —— **反向的不变量常常比正向的更强**。"这里不能手搭帧"既容忍改名,
  又抓住真正的新缺陷;而"这里必须调用 X"只钉住了写法。
- **Lesson 1238** —— **判决没有 kind 时,不要丢弃本地已有的精度**。
  通用值要放在**最后**,不是中间。

---

## 8. 相关文件

| 文件 | 作用 |
|------|------|
| `aethercode/aethercode-protocol/.../AetherCodeMethods.java` | 唯一装配器 `runEndFrame` |
| `aethercode/aethercode-core/.../ClaudeStreamJsonEmitter.java` | stream-json 的 `result` 帧补 kind |
| `aethercode-tui/scripts/test/r384-run-end-frame.test.mjs` | R384 门禁(10 项) |
| `aethercode-tui/scripts/test/r384-mutate.mjs` | R384 变异电池(10 用例) |
| `aethercode-tui/scripts/test/r383-stop-kind.test.mjs` | R383 门禁,改为跟着实现走 |
| `aethercode-tui/scripts/test/java-strip.mjs` | 共享 Java scanner(+ 掩码 + `javaMethodContaining`) |
| `aethercode-tui/scripts/test/java-strip.test.mjs` | scanner 自测(13 项,新增) |
| `aethercode/aethercode-protocol/.../RunEndFrameR384Test.java` | Java 侧行为测试(19 项) |
| `aethercode/aethercode-core/.../ClaudeStreamJsonStopKindR384Test.java` | stream-json 行为测试(13 项) |
| `aethercode-desktop/src/store/index.ts` | 桌面:kind 的优先级顺序 |
