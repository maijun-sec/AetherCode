# R395 — daemon 起不来的时候，要说人话

> 2026-11-23。R394 结尾我把三条自己识别出来的待办列给了用户，用户回「继续优化吧，按你自己识别的继续优化」。
>
> 本轮的三条：
> ① daemon 健康窗口 15s 太短 + 失败静默（界面只说 Disconnected）
> ② 「＋」新建的会话落到「未关联项目」
> ③ 每次启动都有 `[store] pre_warm_daemon failed ... Command pre_warm_daemon not found`

---

## 0. 结论先说

① 做了。窗口 15s → 60s；失败时桌面端不再说「never came up」，而是把 daemon 自己日志里的**那一句**连同日志路径一起端给用户。

③ 做了。R361 退休了 pre-warm 设计（单 daemon，切 cwd 是一条 `switchProject` RPC），Rust 侧的 `pre_warm_daemon` / `discard_pre_warm` 早就删了 —— **但 TS 半边一直留着**，每次启动调用一次、失败一次、只打一条 `console.warn`。本轮把 TS 半边整条删干净，并补了一条**跨文件**的门禁：渲染层调用的每个 Tauri command，Rust 侧必须存在。

② **不用修 —— R392 已经修好了**，本轮只是核实（见 §5）。

---

## 1. 症状一：daemon 被桌面端自己杀了

R394 部署时撞到过：桌面端起来了，daemon 没起来，界面只显示 `⚠ Disconnected`，日志里除了 spawn banner 什么都没有。

根因在桌面端（`aethercode-desktop/src-tauri/src/lib.rs`）：

```rust
const DAEMON_HEALTH_TIMEOUT_MS: u64 = 15_000;
...
let deadline = Instant::now() + Duration::from_millis(DAEMON_HEALTH_TIMEOUT_MS);
while Instant::now() < deadline {
    if is_healthy(port).await { return Ok(child); }
    ...
}
let _ = child.kill();                                     // ★ 15 秒还没健康 → 杀掉
Err(format!("port {}: never came up", port))              // ★ 然后只给这一句
```

daemon 的启动是「JVM boot + 引擎装配 + HTTP bind」：空闲机器约 7s，**机器有负载时 20–35s**（当时用户的 Plumb 工程正在跑 `mvn package` + surefire）。15s 的时候它差几秒就起来了 —— 桌面端把它杀了；重试再起一个，再杀。**一个用户完全看不见的杀-重启循环**，界面上只有 `⚠ Disconnected`。

关键点：**15s 这个窗口只覆盖一种失败** ——「JVM 还活着，但还没回答」。其它失败（端口被占、jar 坏了、java 不在 PATH）都是**亚秒级**发现的（JVM 立刻退出，`child.try_wait()` 立刻看得到）。所以把窗口调大，**只影响「慢」这一种情况**，而那正是唯一值得等的。

⇒ 窗口提到 **60s**，并支持 `AETHERCODE_DAEMON_HEALTH_TIMEOUT_MS` 覆盖（越界的值**忽略**而不是钳位 —— 一次手误不应该变成 0ms 窗口，把每个 daemon 都在起跑瞬间杀掉）。

### 1.1 失败要说人话

旧消息 `port 22888: never came up` 报的是一个内部细节，然后就停了。用户拿到这句能做什么？什么都做不了。

新消息分三段：**发生了什么** / **daemon 自己怎么说** / **日志在哪**：

```
daemon did not start on port 22888: it did not answer /healthz within 60s, so it was stopped
Its log (C:\Users\maijun\AppData\Local\Temp\aethercode-daemon-port22888.log) says:
  ERROR java.lang.OutOfMemoryError: Java heap space
```

「daemon 自己怎么说」这一行是从 `%TEMP%\aethercode-daemon-port22888.log` 的**尾部**里挑出来的（`daemon_log_tail`）：

- 取**最后一条像错误的那行**（`exception` / `error` / `caused by` / `fatal` / `failed` / `cannot` / `unable` / `refused`），而不是最后一行 —— 因为最后一行往往只是栈帧（`at com.foo.Bar.baz(Bar.java:42)`），**栈帧不是原因**；
- 整份日志都没有错误行时，退回最后一条非空行；
- **跳过桌面端自己写的 banner**（`===== prior round daemon spawn port=… =====`）—— 那是**我们自己的**记账行，不是 daemon 的话。引用它等于把我们的簿记当成 daemon 的诊断交给用户（**实机第一次跑就撞上了这个**：1 秒窗口下日志里只有 banner，于是用户看到「Its log says: ===== prior round daemon spawn…」，像是在说 daemon 说了什么，其实那是我们自己写的）。跳过之后，日志里只剩 banner ⇒ 诚实地报告「daemon 没有产出任何输出」；
- 只读尾部 16 KB；行太长截到 300 字符。

### 1.2 渲染端

Rust 的错误现在是**多行**的，而旧的 `<p className="welcome-error-msg">` 会把换行折叠成一坨 —— 原因就埋在了中间。所以：

- `Welcome.tsx`：错误框改成 `<pre>`，`Welcome.css` 加 `white-space: pre-wrap`（**真正让换行存活的是 CSS，不是标签**）；
- `ReconnectBanner.tsx`：横幅是**一行**的窄条（CSS 是 `white-space: nowrap` + 省略号），所以它只显示**首行**（headline），完整消息留在 Welcome 错误面板里。

---

## 2. 症状三：一条没人看的警告，和它背后的死调用

每次启动，渲染层 console 里都有：

```
[store] pre_warm_daemon failed for D:\tmp: Command pre_warm_daemon not found
```

根因：**R361 退休了 pre-warm 设计**（单 daemon；切 cwd 是同一条 JVM 里的 `switchProject` RPC），Rust 侧的 `pre_warm_daemon` / `discard_pre_warm` 两条 Tauri command 随之删除 —— **但 TS 半边留了下来**：

| 位置 | 残留 |
|------|------|
| `src/store/index.ts` | `preWarm` 状态槽、`preWarmCwd` / `discardPreWarm` 两个 action、`initialize()` 里那次调用、`samePath` / `suggestSibling` 两个辅助函数 |
| `src/components/StatusBar.tsx` | 「⚡ pre-warm」指示器（读一个**永远是 null** 的槽 ⇒ 永不渲染） |
| `src/lib/methods.ts` | `PreWarmInfo` 接口 |
| `src/components/StatusBar.css` | `.status-item.prewarm` 样式 |

一次交叉核对（`invoke('…')` vs `#[tauri::command] fn …`）把死调用精确地摆了出来：

```
=== invoked but NOT a Rust command ===
discard_pre_warm
pre_warm_daemon
```

**全删**。

### 2.1 本轮新增的跨文件门禁

删完还不够 —— **同一个死调用可以再长回来**。所以补了一条**读两边**的门禁（而不是在任一边 pin 一串字符串）：

> 渲染层（`src/**`，排除测试文件）里每一个 `invoke('X')`，Rust 侧（`src-tauri/src/*.rs`）必须有一个 `#[tauri::command] fn X`。

这条 pin 的形态和 R395 的另外几条不一样，值得说清楚：

| 事实住在几处 | 该用什么形态的 pin |
|---|---|
| **一处**（一个常量、一个函数） | pin **不变量**（值 / 行为），不 pin 拼写 |
| **两处**（渲染层 ↔ Rust 侧） | **交叉核对**：一条检查读两边，单边 pin 挡不住改名 |

---

## 3. 测试

### 3.1 Rust（`cargo test --lib`，26 例全绿，新增 8 例）

新增 `mod daemon_start_tests`：

| 用例 | 断言 |
|------|------|
| `the_health_window_outlasts_a_busy_machine` | `DAEMON_HEALTH_TIMEOUT_MS >= 30_000`（pin **下限**，不是具体值） |
| `the_health_window_override_is_parsed_not_guessed` | `parse_health_timeout`：越界/垃圾值 → `None`（默认值胜出） |
| `the_log_tail_picks_the_reason_not_the_stack_frame` | 尾部取到的是 `OutOfMemoryError`，**不是** `Bar.java` 那一行 |
| `the_log_tail_falls_back_to_the_last_line` | 没有错误行时退回最后一行 |
| `the_log_tail_is_none_for_a_missing_or_empty_log` | 文件不存在/为空 → `None` |
| `the_log_tail_does_not_quote_our_own_banner` | 日志里只有桌面端自己的 banner ⇒ `None`（不把自己的记账行当成 daemon 的话） |
| `the_failure_message_says_what_happened_and_where_to_look` | 端口、headline、日志里的原因、日志路径，四样齐全 |
| `the_failure_message_survives_a_log_with_nothing_in_it` | 日志为空时消息仍然完整 |
| `describe_exit_names_the_code` | `ExitStatus` → `exit code 3` |

### 3.2 TS（新增 `src/__tests__/daemonStartupR395.test.tsx`，8 例）

| 用例 | 断言 |
|------|------|
| `every invoke() in the shipped renderer has a Rust command behind it` | 交叉核对：孤立的 `invoke` 列表为空；且**扫描必须找到 > 5 条 Rust command**（否则这条门禁是空的） |
| `the pre-warm surface is gone from the renderer` | 三个文件里不再有 `preWarm` / `preWarmCwd` / `discardPreWarm` / `PreWarmInfo`；任何渲染层文件都不再 `invoke('pre_warm_daemon')` |
| `the health window outlasts a slow start on a loaded machine` | 从源码里**解析出**这个数并比下限（不是字符串 pin） |
| `the window the spawn actually waits is the configurable one` | `spawn_daemon_on_port` 等的是 `daemon_health_timeout_ms()`，不是裸常量（否则环境变量覆盖是死的） |
| `the failure message names the port, the reason and the log` | 旧的 `never came up` 不在 spawn 里；三个失败分支都走 `daemon_start_failure`；builder 引用 `daemon_log_tail` 与 `log_path` |
| `the error panel shows EVERY line of the message` | 三行消息都在 DOM 里 |
| `the line breaks survive` | `.welcome-error-msg` 规则里有 `white-space: pre-wrap` |
| `the one-line banner shows the HEADLINE only` | 横幅有首行、**没有**日志摘录 |

### 3.3 ⚠ 一条 pin 撞上了它自己的说明

第一次跑，「pre-warm 已删除」和「旧消息已删除」两条**都红了** —— 因为我写的**注释里**提到了被删的名字：

```rust
/// user "port 22888: never came up".   ← 这行注释让 pin 变红
```

这正是 R384 的 Lesson 1233（pin 位置/拼写会在重构上误红）的另一面：**「这个名字不许出现」的 pin，会把解释它为什么消失的那段话也一起禁掉** —— 而那段话是文件里最有用的一行。

修法不是把注释藏起来，而是**让 pin 读的是代码**。于是写了一个带自测的注释剥离器 `src/test-support/strip-comments.ts`：

- 剥 `//` 与 `/* */`；
- **不**被字符串里的 `//` 骗（`'http://127.0.0.1'` 是字面量，不是注释）；
- **区分语言**：TS 里 `'` 一定是字符串；Rust 里 `'` 可能是**生命周期**（`State<'_, AppState>`）或**字符字面量**（`'a'`、`'\''`）—— 把它当字符串处理会把文件后半截吞掉。

它自带 12 例自测（`src/test-support/strip-comments.test.ts`）。Lesson 1212：*没有自测的扫描器，它的 bug 是被哪个门禁碰巧踩到才暴露的 —— 那不叫计划。*

### 3.4 变异验证（7/7 按设计变红）

| 变异 | 预期红 | 实际 |
|------|--------|------|
| M1 健康窗口改回 15s | 窗口下限 | ✔ 1 红 |
| M2 spawn 等裸常量（环境变量覆盖变死代码） | 可配置窗口 | ✔ 1 红 |
| M3 旧消息 `never came up` 回来 | 失败消息 | ✔ 1 红 |
| M4 builder 不再引用 daemon 日志 | 失败消息 | ✔ 1 红 |
| M5 渲染层又去调 `pre_warm_daemon` | 跨文件核对 + pre-warm 已删 | ✔ **2 红** |
| M6 错误框丢掉 `white-space: pre-wrap` | 换行存活 | ✔ 1 红 |
| M7 横幅显示整段多行错误 | 横幅只显示首行 | ✔ 1 红 |

> M5 红了 2 条，是对的：同一个缺陷被两道门禁各抓一次。

---

## 4. 顺带修掉的一个小陷阱

写变异脚本时踩到：`npx` 在 Windows 上是 `.cmd` shim，`execFileSync` 不带 shell 跑不起来，异常被吞掉 ⇒ **红集合是空的**。这正是「红集合为空有两种来源」那条：**量具没读到东西**，和**变异没生效**，长得一模一样。

修法：直接用 `process.execPath` 跑 `node_modules/vitest/vitest.mjs`，并在解析前先剥 ANSI 颜色码（否则 `×` 那一行根本匹配不上）。**第一版脚本 7 个变异全部报「空红集合」——如果当时直接采信，就会把「量具有问题」当成「pin 没问题」。**

---

## 5. 待办 ② 的核实结果：「＋」新建的会话**没有**再落到「未关联项目」

R392 记过「用项目分组的『＋』建出的会话落在『未关联项目』」。本轮先核实它是否还在：

**库里的事实**（`sessions.db`）：

```
6cd6ff77…  cwd=D:\tmp\abc_1  title=多类型数组排序与测试
61bfe997…  cwd=D:\tmp\abc_1  title=…
26ee7d8d…  cwd=D:\tmp\abc_1  title=…
c5703dea…  cwd=D:\tmp\abc_1  title=None      ← 「＋」建的
569048d8…  cwd=D:\tmp\abc_1  title=None      ← 「＋」建的
6a47e762…  cwd=D:\tmp\abc_1  title=None      ← 「＋」建的
9d6785ab…  cwd=D:\tmp\abc_1  title=None      ← 「＋」建的
b772cbb0…  cwd=D:\tmp\abc_1  title=None      ← 「＋」建的
ecce07c1…  cwd=D:\tmp\abc_1  title=新增排序方向与字节数组支持
（另有 3 条 cwd=NULL 的**旧**会话，全部已在回收站）
```

**daemon 的事实**（`listSessions`）：7 条活会话，`cwd` **全部**是 `D:\tmp\abc_1`。

**渲染层的事实**（CDP 只读探针）：左栏**只有一个分组** `▸ abc_1 (count=8)`，**没有**「未关联项目」。

⇒ ② 已经不是缺陷了。R392 把渲染层 cwd 从 daemon 恢复回来之后，「＋」建的会话就带着 cwd 落库、落进正确的分组；当时看到的那条是**旧会话**（cwd=NULL，且在回收站里）。**结论：不修。**

> 顺带说明：左栏里还有一条 `57ca7191`（我 R394 测试用的会话）—— 那是我用裸 RPC 删的，没走 UI，所以渲染层的本地副本没被剪掉。UI 上的删除走 `deleteSession`，会同时剪本地列表。**不是产品缺陷。**

---

## 6. 经验

**Lesson 1461 — 一个「超时」如果不给用户任何话，它和「没启动」在用户眼里是同一件事。**
桌面端给 daemon 15 秒健康窗口，超时就杀掉并只说一句 `never came up`。日志里除了 spawn banner 什么都没有 —— 用户看到的只有 `⚠ Disconnected`。**超时必须有解释，而且这个解释要来自 daemon 自己**（它日志里那一句），不是桌面端猜的。

**Lesson 1462 — 窗口要按「最慢的那个合法情形」来定，不是按「平均」。**
15s 是按空闲机器的 7s 加了点余量定的；可 daemon 的启动时间**不是常量**，它随机器负载在 7s–35s 之间。窗口的唯一作用是「覆盖慢启动」，所以它必须覆盖**最慢的那个合法情形**；而所有**非法**情形（崩溃、端口冲突、jar 损坏）都是亚秒级发现的 —— 把窗口调大只会影响「慢」这一种情况，**这正是唯一值得等的**。

**Lesson 1463 — 「这个命令不存在」不会自己变成 bug，它只是一条没人看的 warning。**
R361 删掉了 Rust 侧的 `pre_warm_daemon`，TS 半边留了下来，于是每次启动调用一次、失败一次、打一条 `console.warn`。**一个死调用可以在树上活几十轮**，因为它的失败形态是「一条日志」，不是「测试红」。⇒ **删掉一个功能时，两边都要删**；而且要有**跨文件核对**（一边调用的每个 command，另一边必须存在），因为单边 pin 挡不住它再长回来。

**Lesson 1464 — 一条「这个名字不许出现」的 pin，会把解释它为什么消失的那段话也一起禁掉。**
本轮两条 pin 第一次就红在自己的说明注释上。修法不是藏注释，而是**让 pin 读代码**（剥注释后再查）—— 于是多了一个带自测的注释剥离器。⇒ **「absence 的 pin」必须先把注释剥掉**；剥注释的工具**必须有自测**，否则它的 bug 是被哪个门禁碰巧踩到才暴露的。

**Lesson 1465 — 事实住在两处时，pin 必须读两处。**
健康窗口是**一处**事实 ⇒ pin 不变量（≥ 30s），不 pin 拼写。「渲染层调用的 command 在 Rust 侧存在」是**两处**事实 ⇒ 交叉核对。**只 pin 一边，等于只检查了一半的契约**：改个名字就绿了。

**Lesson 1466 — 「红集合为空」的第一嫌疑永远是量具，不是被测物。**
变异脚本第一版 7 个变异全部空红集合 —— 原因是 `npx` 在 Windows 上是 `.cmd`，`execFileSync` 跑不起来而异常被吞。**空红集合和「pin 有效」长得一模一样。** ⇒ 变异脚本本身要先跑一次**已知会红的**变异，证明量具读到了东西。

**Lesson 1467 — 引用一份日志时，先分清「谁写的」。**
实机第一次跑新错误文案就撞上了：日志里只有桌面端自己写的 banner（`===== prior round daemon spawn port=… =====`），于是用户看到「Its log says: ===== prior round daemon spawn…」。**那是我们自己的记账行，不是 daemon 的话** —— 引用它等于把簿记包装成诊断。⇒ 引用日志要先过滤掉自己写的那几行；过滤后没有内容，就**诚实地说「没有输出」**，不要拿别的东西凑。

---

## 7. 实机验证

**（a）失败路径 —— 强制让它失败。**
把健康窗口压到 1 秒（`AETHERCODE_DAEMON_HEALTH_TIMEOUT_MS=1000`）启动桌面端，daemon 必然起不来。CDP 只读探针读到的**真机 DOM**：

```
HEADING: ⚠ Connection failed
TAG: PRE
WHITE-SPACE: pre-wrap
--- MESSAGE (raw) ---
daemon did not start on port 22888: it did not answer /healthz within 1s, so it was stopped
Its log (C:\Users\maijun\AppData\Local\Temp\aethercode-daemon-port22888.log) says:
  <日志里 daemon 自己的最后一句>

--- HINT ---
The desktop auto-spawns the daemon from resources/aethercode.jar. If the message above
names a log file, that file has the daemon's own output — the reason is usually the
last thing it printed. …

=== the banner (one line) ===
label: ⚠ Disconnected
detail: 0 reconnect attempts failed. Last error: daemon did not start on port 22888: it did not answer /healthz within 1s, so it was stopped
```

⇒ 端口、原因、日志路径、daemon 自己的话，**四样都在**；横幅只有首行；`<pre>` + `pre-wrap` 让换行活下来了。

（第一次实机跑出来的那行引用的是**我们自己的 banner** —— 那是本轮最后一个改进，见 Lesson 1467。）

**（b）正常路径 —— 机器有负载时的启动耗时。**
用户当时正在跑 Plumb 的 `mvn package` + surefire（正是当初把 15s 窗口打爆的场景）。用探针轮询 `/healthz` 量到：

```
daemon answered /healthz after 12.1s
old window was 15s -> would have SURVIVED
new window is 60s -> survives
```

**12.1s —— 离旧窗口只差 2.9s。** 那次侥幸活下来了；但 R394 部署那一次**确实**超过了 15s。⇒ 15s 这个窗口不是「偏紧」，是**贴着实际值**；60s 把它从悬崖上挪开。

**（c）A/B：那条警告确实是旧版发的，新版没有。**
把 R394 的 exe（`.bak-r395`，bundle `index-OS67Ypz9.js`）放回原名跑一遍，用同一个探针抓「一次真实启动」的 console：

```
=== OLD build (R394) ===
  [warning] [store] pre_warm_daemon failed for D:\tmp: Command pre_warm_daemon not found
=== the retired pre-warm call ===
STILL THERE — the dead call fired
```

同一支探针跑新版：

```
gauge sees an injected marker : YES
window marker after reload : undefined  (fresh JS context => the reload RAN)
=== console output during a real launch ===
  [warning] [r395-probe] capture-marker
=== the retired pre-warm call ===
GONE
```

> **探针本身先证明了自己。** 第一版探针抓到 **0 条** console —— 但「没有输出」和「探针没读到东西」长得一模一样。所以探针加了两件事：① 注入一个 marker，**必须**被抓到，否则结论作废；② 在 reload 前写一个 `window` 标记，reload 后它必须是 `undefined`（证明 reload 真的跑了、console 是新进程的）。**两次实测都过了，结论才成立。**

**（d）新 bundle 里不该有的字符串，确实没有。**

| 字符串 | 新 bundle 里出现次数 |
|--------|------------------|
| `pre_warm_daemon` | **0** |
| `discard_pre_warm` | **0** |
| `preWarmCwd` / `discardPreWarm` | **0** |
| `pre-warm` | **0** |
| `never came up` | **0** |
| `.welcome-error-msg` 的 `white-space: pre-wrap` | **✔ 在 CSS bundle 里** |
