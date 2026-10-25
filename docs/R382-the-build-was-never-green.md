# R382 — 这个构建从来没有绿过

> 打包 0.2.72 的时候发现的三个真缺陷：一个让整个 `mvn install` **永久挂死**的
> CLI bug、一个让每次默认构建都要**烧真钱跑 20 分钟**的评测基准、一个让桌面端
> **根本构建不了**的版本漂移。
>
> 三个都修了。前两个补了**会变红的回归契约**，第三个补了**门禁**。

---

## 0. 这一轮做了什么

用户指令是「继续优化，如果已经做完，可以出个包」。出包的过程本身就是一次
**全量构建**，而这次全量构建 —— 从 R747 起 —— 一次都没有跑完过。

三个缺陷叠在一起，把「出包」这条路堵死了：

| # | 缺陷 | 症状 | 修法 |
|---|------|------|------|
| 1.1 | `--print` 读 stdin 读到 EOF | `mvn install` 永久挂死 | plain 路径只读已缓冲的字节 |
| 1.2 | 真实 LLM 基准挂在默认测试里 | 每次构建 20+ 分钟 + 付费调用 | 改成显式 opt-in |
| 1.3 | Tauri 的 npm / Rust 版本漂移 | `tauri build` 直接报错退出 | npm 侧钉到 crate 的 minor + 门禁 |

---

## 1. 三个真问题

### 1.1 `--print` 读 stdin 读到 EOF → 整个 `mvn install` 挂死

**症状.** 19:51 启动的 `mvn install` 跑到 20:31 之后完全静止：surefire 的
fork 进程 **20 秒内 CPU 增量为 0**，maven 进程 CPU 也不动。

**定位.** `jstack` 直接指到了元凶：

```
"main" #3 [2088] prio=5 ... waiting for monitor entry
    java.lang.Thread.State: BLOCKED (on object monitor)
   at java.io.BufferedInputStream.read(BufferedInputStream.java:379)
   at java.io.BufferedReader.readLine(BufferedReader.java:333)
   at org.aethercode.cli.Main.readPromptFromStdin(Main.java:403)
   at org.aethercode.cli.Main.call(Main.java:290)
   at org.aethercode.cli.MainCliTest.call_rejectsPrintWithoutPrompt(MainCliTest.java:31)
```

**根因.** R747（`e4005ca`）让 `--print` 在没有 argv prompt 时从 stdin 读
prompt，而那次读取**读到 EOF**：

```java
while ((line = reader.readLine()) != null) { sb.append(line); }
```

这个读法对 **stream-json 协议是必须的**（对端发完 prompt 会继续持有管道，
后面还有 `control_response` 帧）。但 plain `--print`（默认
`outputFormat=text`）走的是**同一条**路径。

而 surefire fork 的 stdin 是一个**永远不关闭的管道**，`System.console()` 在
fork 里是 `null`，所以那句「在控制台上且没有数据就别等」的守卫
（`System.console() != null && !stdinHasData()`）根本不生效 → 永久阻塞。

**为什么一直没人发现.** helper 日志里最近几次「BUILD SUCCESS」全都是
`-pl` 子集（例如 11:08 那次只到 `aethercode-protocol`）。**全量 reactor
从 R747 起一次都没跑完过**，而子集构建的日志尾巴和全量构建长得一模一样。

**修法.** 两种输出形态需要**不同的读法**，不是同一个：

| 形态 | 读法 | 理由 |
|------|------|------|
| `stream-json` | 读到 EOF（不变） | 协议要求：对端保持管道打开 |
| plain `--print` | 只读**已经缓冲**的字节 | argv 才是文档化的通道；没有数据就报「缺 prompt」退出 2 |

plain 路径不引入任何超时常量 —— 「现在有没有字节」是可以**问出来的事实**，
「该等多久」只能靠猜。

### 1.2 真实 LLM 基准挂在默认测试里

**症状.** `mvn install` 在 `aethercode-evals` 模块停住，fork 进程
`SpringAiChatClient.tryAdvance` 持续 20+ 分钟、CPU 只用了 20 秒 —— 全在等
网络。

**根因.** `BenchmarkReportRealLlmTest` 会发 **~45 次真实模型调用**
（9 个 benchmark × 5 题，约 27 万输入 token）。它自己的 javadoc 写着：

> Run on demand, not on every CI.

但它挂在默认测试路径上。原来的跳过条件是「`MINIMAX_API_KEY` 没设就跳过」——
**这恰恰是最没用的一条守卫**：在**有**密钥的机器上（也就是真正会付钱的那些
机器）它永远不触发。

**修法.** 显式 opt-in：

```
mvn -pl aethercode-evals test -Daethercode.evals.realLlm=true
```

同目录里两个**只调一次**的冒烟测试（`MinimaxM3SmokeTest`、
`EngineQueryRealLlmTest`）保持 env 门 —— 一次调用很便宜，信号值得要。

### 1.3 Tauri 的 npm 包和 Rust crate 版本漂移

**症状.** `npm run tauri:build` 直接失败：

```
Found version mismatched Tauri packages:
tauri (v2.11.5) : @tauri-apps/api (v2.12.4)
tauri-plugin-dialog (v2.7.2) : @tauri-apps/plugin-dialog (v2.8.3)
tauri-plugin-shell (v2.3.6) : @tauri-apps/plugin-shell (v2.5.0)
```

**根因.** 两边都是浮动范围：`Cargo.toml` 写 `tauri = "2"`，
`package.json` 写 `"@tauri-apps/api": "^2.0.0"`。于是各自漂到自己的最新：
`pnpm install` 把 npm 侧刷新到更新的 minor（`node_modules` 时间戳
2026-10-21），而 `Cargo.lock` 还停在 10-20 那次 `cargo` 锁的版本。
**仓库里没有任何东西比较这两边**，所以漂移只在人跑 `tauri build` 时才显形。

**修法.** npm 侧**精确钉**到 crate 的 minor（2.11.1 / 2.7.2 / 2.3.6），并加
门禁。注意规则是 **major.minor** 而不是完全相等：`@tauri-apps/api` 压根没有
2.11.5（它只发了 2.11.0 / 2.11.1），两边的 patch 节奏本来就不一样 ——
**一个生态无法满足的规则，是会被删掉的规则**。

---

## 2. 改了什么

### 2.1 plain `--print` 只读已缓冲的字节

`Main.readPromptFromStdin` 拆成两条路径，新增 `readBufferedPrompt()`：

- 逐字节读（`System.in.read()`），因为 `read(byte[], 0, available)` 会**为
  管道里永远不会来的字节继续阻塞**；
- 没有字节就返回 `null` → 调用方报「缺 prompt」→ 退出 2（回到 R747 之前的行为）。

`readPromptFromStdin` 从 `private` 改成包级可见 —— 这是给测试用的**接缝**，
不是为了让测试能读私有字段。

### 2.2 真实 LLM 基准改成 opt-in

`aethercode-evals/src/test/.../BenchmarkReportRealLlmTest.java` 增加
`-Daethercode.evals.realLlm=true` 门，默认跳过并打印怎么开。

### 2.3 npm 侧钉版 + 门禁

`aethercode-desktop/package.json`：

```json
"@tauri-apps/api": "2.11.1",
"@tauri-apps/plugin-dialog": "2.7.2",
"@tauri-apps/plugin-shell": "2.3.6"
```

新增 `aethercode-desktop/src/__tests__/tauriVersionPairingR382.test.ts`（4 例）：

1. 每个 `@tauri-apps/*` 都是**精确版本**（没有 `^` / `~`）；
2. 每个 npm 包和它的 Rust crate **major.minor 相同**；
3. 比较函数**真的会拒绝**不匹配（合成反例，防止门禁本身是 no-op）；
4. 版本是**从文件读出来的**，不是写死的字面量。

### 2.4 顺手修掉的：package.json 的乱码

`aethercode-desktop/package.json` 的 description 里，破折号是**双重编码**的
`鈥?`（码点 `9225 003F`，本该是 `—`）。和 0.2.71 发布 README 的乱码是同一类
损坏 —— 写文件时被按 GBK 解过一遍。已修。

### 2.5 发布包本身的两处修正

- **0.2.71 的 `desktop/bundle/{msi,nsis}` 是空目录**，而它的 README 明确写着
  里面有安装包。0.2.72 把两个安装包**真的放进去了**。
- **0.2.71 把运行期目录 `.aethercode/sessions/` 打进了包**。0.2.72 不带。

---

## 3. 证据

### 3.1 CLI 挂死

| 观测 | 数值 |
|------|------|
| 挂死时 surefire fork 的 20 秒 CPU 增量 | **0** |
| 挂死时 maven 进程的 CPU 增量 | **0** |
| `jstack` 栈顶 | `Main.readPromptFromStdin(Main.java:403)` → `readLine` → BLOCKED |
| 引入者 | R747 (`e4005ca`) |
| 最后一次**全量** reactor 跑完 | **R747 之前** |

### 3.2 修复后 + 变异验证

```
mvn test -pl aethercode-cli  →  BUILD SUCCESS in 26.53s   (70 tests, 0 failures)
MainCliTest                  →  Tests run: 7, Failures: 0
```

**变异验证**（把修复回退成「plain 也读到 EOF」）：

```
MainCliTest.readPromptFromStdin_plainDoesNotBlockOnSilentStdin -- Time elapsed: 3.014 s <<< FAILURE!
  AssertionFailedError: plain --print must not block on an open-but-silent stdin
                        ==> expected: <false> but was: <true>
```

关键是它**3 秒后失败，而不是把构建挂死** —— 契约本身是有界的。

**补丁确实进了 jar**（`AGENTS.md` 的老规矩：不能只看 BUILD SUCCESS）：

```
readBufferedPrompt     in constant pool: True
readPromptFromStdin    in constant pool: True
```

### 3.3 评测基准

| | 之前 | 之后 |
|---|------|------|
| `BenchmarkReportRealLlmTest` | 20+ 分钟，约 45 次付费调用 | **0.064 秒**（跳过） |
| 显式开启 | 不可能 | `-Daethercode.evals.realLlm=true` |

### 3.4 Tauri 版本配对

变异验证（把 `@tauri-apps/api` 改回漂移前的 `2.12.4`）：

```
FAIL  pairs every @tauri-apps package with its Rust crate on the same major.minor
AssertionError: R382: @tauri-apps/api (2.12.4) must match Rust crate tauri (2.11.5)
                on major.minor: expected 'major.minor mismatch:
                npm=2.12.4 (2.12) vs crate=2.11.5 (2.11)' to be null
```

恢复钉版后 **4/4 绿**。

### 3.5 工具链缓存的旁路（1.3 的余波）

`tauri build` 需要 NSIS 工具链，而它默认从 **github.com** 下载 —— 这台机器上
github.com 会超时（`api.github.com` 和 `objects.githubusercontent.com` 却通）。
契约是从 **tauri-bundler 自己的源码**读的（经 GitHub contents API 取
`crates/tauri-bundler/src/bundle/windows/nsis/mod.rs`，tag `tauri-v2.12.3`）：

| 项 | 值 |
|----|----|
| 缓存目录 | `%LOCALAPPDATA%\tauri\NSIS` |
| 工具链 | `nsis-3.13.zip`，**SHA1** `DB14F8AF2EA346B786C6AE19343F61AAF5E09B8E` |
| 插件 | `nsis_tauri_utils.dll`，**SHA1** `75197FEE3C6A814FE035788D1C34EAD39349B860` |
| 插件落点 | `Plugins/x86-unicode/additional/` |

两个 SHA1 都**实测核对通过**后才写进缓存，然后 `tauri build` 一路跑完。

> 顺带纠一个自己的错：我从二进制字符串里抓到的 40 位十六进制**不是 SHA-256**
> （长度就不对），是 **SHA1**。一开始拿 SHA-256 去比，自然对不上。

### 3.6 发布 README 的数字对账

`release/aethercode-0.2.72/README.md` 里每个数字都对着磁盘**重新推导**过。
校验器当场抓到两个真错（我手算的 MiB 舍入错了两处：`54.25 → 54.26`、
`95.96 → 95.95`），以及目录树和磁盘结构的两处不一致
（`msi/xxx.msi` 写成了单行、`@koromix/` 少了一层）。全部修正后 **16/16 通过**。

---

## 4. 结果

| 项目 | 之前 | 之后 |
|------|------|------|
| `mvn install`（全量 reactor） | **从 R747 起从未跑完** | **BUILD SUCCESS，576.55s** |
| `mvn test -pl aethercode-cli` | 挂死 | 70 例，0 失败，26.53s |
| `MainCliTest` | 挂死 | 7 例，0 失败 |
| evals 真实 LLM 基准 | 20+ 分钟 / 付费 | 0.064s 跳过 |
| `npm run tauri:build` | 版本不匹配报错 | MSI 54.48 MiB + NSIS 52.85 MiB |
| 桌面端 vitest | — | 1390 过 / 2 挂（1 个是发布目录没装完，1 个是既有红） |
| 发布 README 对账 | — | 16/16 |

### 4.1 桌面端那 2 个失败

- `zipLayoutR268b` — **不是回归**：它检查最新 release 目录里
  `desktop/aethercode.jar` 是否存在（正是 R268b 定的发布布局契约）。它在我
  装到一半时红，装完就绿。
- `ToolsPanel` "Header.tsx has a tools button (🔧)" — **既有红**
  （`AGENTS.md` 记的 R33），`Header.tsx` 这次一行没动。

---

## 5. Lessons

**Lesson 1219 —— 一次没有跑完的构建，不算构建。**
日志里的 `BUILD SUCCESS` 说的是**一个子集**。全量 reactor 从 R747 起四天没
跑完过，而子集构建的日志尾巴和全量构建**长得一模一样** —— 没人看出区别。
「构建是绿的」这句话，必须指明**哪个构建**。

**Lesson 1220 —— 一个既要密钥、又要网络、还要 20 分钟的"测试"，是一个任务，
不是一个测试。**
`MINIMAX_API_KEY` 没设就跳过 —— 这条守卫**恰好在会付钱的机器上不生效**。
「有没有密钥」不是「要不要跑」；要跑就得是**显式**的。

**Lesson 1221 —— 两边的浮动范围，迟早会漂。**
同一个概念（tauri 的版本）有两份描述（npm manifest / Cargo.lock），没人比较，
就必然漂。修法不是"这次对齐一下"，是**钉死一边 + 加一条比较**。

**Lesson 1222 —— 工具的契约要从工具自己的源码读，不要从它的报错里猜。**
报错只说了"版本不匹配"并列出三对数字；源码说了**规则是什么**（major.minor）、
**缓存在哪**、**哈希是什么**、**需要哪些文件**。

**Lesson 1223 —— 一个主机不通，不等于一个产物拿不到。**
github.com 超时，但 `api.github.com` 通；API 的资源端点会 302 到
`objects.githubusercontent.com`，那个 CDN 是通的。**换一条路，而不是放弃。**

**Lesson 1224 —— 文档里的数字也是断言。**
发布 README 的两个 MiB 数字是错的，目录树和磁盘结构差了两处。一个
**从文件重新推导每个数字**的校验器，当场把它们全抓了出来。

**推论：发布包本身也要有门禁。** 这次用的是一次性脚本（没进仓库，因为
打包流程本来就是手工的）；但 `zipLayoutR268b` 那条 vitest 已经在管发布布局
了 —— 说明**发布布局是可以有契约的**。

---

## 6. 下一轮的候选

- **发布流程脚本化**。现在出包是纯手工的四步（jar → TUI bundle → bun exe →
  Tauri bundle → 目录 → zip），每一步都可能像这次一样**静默地**产出过期或
  缺失的文件。`doc/tech-docs/packaging.md` 里写的 `package.ps1` **并不存在**。
- **把「全量构建跑得完」变成门禁**。R382 的教训是：子集构建的绿色会冒充
  全量构建的绿色。CI 上应该有一条「全量 reactor 从不挂死」的门。
- **`mvn install` 的网络依赖**。`aethercode-evals` 之外，还有别的模块在
  默认路径上要网络吗？（R382 只查了 evals。）
- **`.aethercode/` 的其它产物**。0.2.71 泄漏的是 `sessions/`，但 daemon
  运行时会不会在别的路径下产出东西？
- **Tauri 缓存的自举**。NSIS 缓存是手工种进去的；下次换机器又会卡住。
  `tauri build` 有没有官方的镜像 / 离线开关，值得查。
