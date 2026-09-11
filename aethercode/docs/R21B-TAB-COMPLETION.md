# R21-B Tab Completion (2026-08-06)

## 目标

让 Tab 键在 REPL 里**真的能用**。R8 / R10-6 时代写好了
`SimpleSyntaxHighlighter` / `CommandPalette` / `HistorySearch` 三个
独立组件，但 R21 之前 Tab 在 JLine 上是**空的**。R21-B 把补全系统
串起来：先写一个自己的 `Completion` 接口，再用 JLine 的 `Completer`
适配器接进 `LineReaderBuilder`。

## 范围

R21-B 只动 TUI 模块，**不**动 SDK / Tasks。改动：

| 文件 | 角色 |
|---|---|
| `Completion.java` | 抽象：每个 completer 接收 `(buffer, cursor, cwd)` 返回 `List<Candidate>` |
| `Candidate` (record in Completion) | 单个补全候选：display / replacement / start / end |
| `FilePathCompleter.java` | 文件路径补全：bare / `./` / `../` / `~/` / 绝对路径；目录自动加 `/`；隐藏文件默认不显（除非用户打了 `.`） |
| `SlashCommandCompleter.java` | `/` 命令补全：仅在光标**在**当前 token 内（不在末尾）或**当前 token 是 buffer 末尾**时触发 |
| `CompletionEngine.java` | 编排：注册多个 completer；**第一个**返回非空的就赢；同结果按 replacement 去重；附带 `commonPrefix()` |
| `JLineCompletionAdapter.java` | 把 `Completion.Candidate` 翻译成 JLine 3 的 `org.jline.reader.Candidate` |
| `ReplApp.java` | 构造 `buildCompletionEngine()` 并 `.completer(...)` 接到 `LineReaderBuilder` |
| `docs/R21B-TAB-COMPLETION.md` | 本文档 |

## 关键设计

### 1. "First non-empty wins"，不是 merge

JLine 习惯是「所有 completer 的结果合并」。我们反过来 —— **第一个
返回非空列表的 completer 独赢**。原因：用户期待「最相关」那个说话，
不是一个混合菜单。`FilePathCompleter` 排在 `SlashCommandCompleter`
后面，所以 `/foo` 永远是命令补全，不会被当成文件路径。

### 2. SlashCommandCompleter 的 "token 边界" 语义

测试里有一个看似矛盾的用例：

| 输入 | 光标 | 期望 | 原因 |
|---|---|---|---|
| `"/help rest"` | 5 | **空** | cursor=5 在 token `/help` **末尾**且后面还有 ` rest` —— 用户已经敲完命令了，不应打扰 |
| `"/help rest"` | 3 | 1 个候选 | cursor=3 在 token `/help` **中间** —— 还在编辑命令，弹出 |
| `"/h"` | 2 | 1 个候选 | cursor=2 在 buffer 末尾 —— token 自身就是 buffer 末尾，弹出 |

实现就是 `!isLastToken && !isMidToken` 才放弃：

```java
boolean isLastToken = tokenEnd == buffer.length();
boolean isMidToken  = cursor < tokenEnd;
if (!isLastToken && !isMidToken) return List.of();
```

### 3. FilePathCompleter 修过的两个真 bug

第一版有 2 个真实 bug，测试一跑就暴露：

1. **bare 文件名走错目录**：`complete("al", 2, cwd)` 期望匹配 cwd 里的 `alpha.txt`，但代码 `dir = base.getParent()` 算成了 cwd 的**父目录**。修：`dir = cwd` 直接落在 cwd 上。
2. **绝对路径走错**：`Path.of("/tmp/junit/zeb")` 这种绝对路径用 `cwd.resolve(prefix).normalize()` 会绕到错的根。修：检测 `Path.of(prefix).isAbsolute()`，绝对路径用 `Path.of(prefix)` 直接构造。

### 4. 测试数据也要修

`commonPrefix_findsLongestSharedPrefix` 最初用 `/help /history /hello`，实际公前缀是 `/h`（2 字符），不是 `/he`。改用 `/help /her /hello` 后才是 `/he`，算法才有机会返回 3 字符。

`engine_choosesFirstNonEmptyCompleter` 用 `Set.of("help", "tools")` 配 `/h` 只能 match `help` 1 个，期望 2 跑不通。改用 `Set.of("help", "history", "tools")`，2 个 match。

## ReplApp 接入

```java
// 静态工厂：slash 名字从 handleCommand() 的 case 列表抄过来，
// 增删命令时两边要同步 —— 有一个测试 (ReplAppCompletionTest
// .buildCompletionEngine_slashListCoversAllDispatchedCommands) 守住。
static CompletionEngine buildCompletionEngine() {
    Set<String> slashNames = Set.of(
            "exit", "quit", "help", "clear", "tools", "todos", "tasks",
            "search", "agents", "jobs", "job-output", "job-kill",
            "agent", "state", "history", "select", "vim", "plan",
            "approve", "reject", "sessions", "resume", "fork");
    return new CompletionEngine()
            .add(new SlashCommandCompleter(slashNames))
            .add(new FilePathCompleter());
}

this.reader = LineReaderBuilder.builder()
        .terminal(terminal)
        .appName("aethercode")
        .completer(new JLineCompletionAdapter(buildCompletionEngine(),
                Path.of(System.getProperty("user.dir"))))
        .variable(...SECONDARY_PROMPT_PATTERN, "%P")
        .build();
```

`JLineCompletionAdapter` 把 JLine 的 `Completer.complete(LineReader,
ParsedLine, List<Candidate>)` 转给我们自己的
`CompletionEngine.complete(buffer, cursor, cwd)`，再把每个
`Completion.Candidate` 映射到 `new Candidate(replacement, display,
null, null, null, null, false)`。JLine 的 `Candidate` 字段名是
`displ()`（不是 `display()`），第一版测试就栽在这里。

## 关键 pitfall

1. **JLine 3.26.2 实际接口**：
   - `Candidate.value()` / `Candidate.displ()`（不是 `display()`）
   - `ParsedLine` 必须实现 `wordIndex()`
2. **依赖方向**：tui 不能依赖 cli / bridge，但 tui 内的 `screen.*`
   和顶层 `*.java` 可以互引。
3. **Maven 跨模块改动**：改了 `aethercode-tools`（或任何上游
   模块）后 tui 编译可能拿到旧 jar。`mvn -pl aethercode-tui -am`
   强制 reactor build，绕过这个坑。
4. **测试断言要弱一点**：`/agent` 既匹配 `agent` 也匹配 `agents`
   是预期行为（用户打 `/age` 时两个都该出现），别写 `assertEquals(1, ...)`。

## 测试

| 文件 | 测试数 |
|---|---|
| `CompletionTest` | 18 |
| `JLineCompletionAdapterTest` | 4 |
| `ReplAppCompletionTest` | 3 |
| **R21-B 小计** | **+25** |

AetherCode 总数：1435 → **1460**，0 regression。

## 验证

- [x] 单元测试 25 个新 case 全过
- [x] 全量 `mvn test` BUILD SUCCESS
- [x] ReplApp 构造时 `.completer(adapter)` 已挂上（手动看 diff）
- [ ] 实际跑 CLI 跑通 Tab —— 需要交互环境，CI 跑不了（待手动验）

## 下一步

R21-C：把 `SimpleSyntaxHighlighter` 接到 `InputBar.paint()`，让
输入框里**正在敲的内容**也有 markdown 风格的上色。debounced 200ms
重 tokenize 一次，避免每键都全量跑正则。
