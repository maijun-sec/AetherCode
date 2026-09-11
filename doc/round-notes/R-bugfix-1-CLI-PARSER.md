# R-bugfix-1 Cli Parser 2 Bug 修复 (2026-09-12)

## 触发

R-radar-2 锁定 2 个 Cli parser bug (regression test 形式):

- **Bug #1: remainder mode 早开** — `run` / `trials` / `radar` 声明
  `addRemainder("pytest_extra")` 后, parser 立刻进 remainder mode, 所有后续
  options 被吞进 pytestExtra. 实际导致 `main(["run", "--model", "X"])` 把
  `--model X` 吞掉, validateModel 失败.
- **Bug #2: 嵌套 subcommand options 不下钻** — `list.addSub("models", ...)` 后
  `listModels.addValue("--group", ...)`. parser 找到 `list` 顶层 sub 后不
  下钻到 `listModels`, `--group` 被当作 unknown option 拒收.

这 2 个 bug 在 R-radar-2 时被锁为"actual behavior" (regression test),
R-bugfix-1 把它们真正修掉.

## 实际产出 (1 main 改 + 1 test 改, 1 round)

- `evals/Cli.java` (改 49 insertions, 11 deletions):
  - **Bug #1 修法**: 删除 parser 顶部的 `parsed.remainder = true` 提前
    设置 (旧 line 733-739). 改为在主循环里遇到独立 `--` token 才进
    remainder mode (Python argparse 标准语义).
  - **Bug #2 修法**: 引入 `active` 变量代替直接用 `sub`. 当 positional
    token 匹配 active sub 的 nested sub 名时 (`active.findSub(arg) != null`),
    切换 `active` 到 nested sub. `findOption` 跟着 active 走, 自动
    看到 nested sub 的 options.
  - 新增 `Sub.findSub(String token)` method.
  - 删除 `Cli.main()` 里的 `--` strip (line 110-113) — 现在 parser 直接
    处理 `--` 自身, 不再保留进 pytestExtra.
- `evals/CliTest.java` (改 7 个 test + 增 2 个 test, 8 个新/改):
  - 修 `runDryRunDoesNotInvokeShellRunner`: 现在 --model + --dry-run
    正常解析, dry-run 路径走完 EXIT_OK, shell 0 次
  - 修 `parserHonorsNegativeNumbersAsRemainder` → `parserRejectsBareShortFlags`:
    `run -1` 现在是 unknown option, parser 拒收
  - 修 `parserParsesListModelsWithFilters`: `list models --group set0 --provider
    anthropic --json` 正确解析, group/provider/json 都进 Parsed
  - 修 `parserParsesListEvalsWithCategory`: `list evals --category memory`
    正确解析, category 进 Parsed
  - 修 `parserStripsLeadingDoubleDashRemainder` → `parserGatesRemainderOnDoubleDashSentinel`:
    `run -- -k memory` → pytestExtra=`[-k, memory]`, `--` 自身被消耗
  - 新增 `parserAcceptsOptionsBeforeDoubleDashRemainder`: `run --model X
    --dry-run -- -k memory` 三种 token 类型 (option / flag / remainder)
    都能正确分发
  - 修 `mainStripsLeadingDoubleDashRemainderBeforeDispatch` → `mainReachesDispatchWhenRemainderIsGated`:
    main() 走到 cmdRun, validateModel fail → EXIT_CONFIG
  - 修 `trialsDryRunStillFailsWithoutParsedModel` → `trialsDryRunParsesModelAndTrials`:
    trials --model --trials --dry-run 正常 dry-run, EXIT_OK, shell 0 次
  - 新增 `trialsWithoutModelStillFails`: 没有 --model 仍 fail EXIT_CONFIG
- **aethercode-evals: 356/356 Java tests pass** (354 + 2 新 test), 0 回归

## 关键技术决定 (8 条)

1. **Remainder 由 `--` 触发, 不由 declaration 触发** - Python argparse
   标准行为. 老代码 declaration 触发是设计缺陷.
2. **active sub 模式替代固定 sub** - `active` 变量在 nested sub 切换时
   跟变, `findOption` 复用同一查找, 不需要 parent + child 合并.
3. **Nested sub 名字仍进 positionalList** - `list categories --json` 解析后
   `positionalList = ["categories"]`, dispatch 时 `listTarget = "categories"`.
   不破坏 dispatch 逻辑.
4. **删除 main() 里的 `--` strip** - 既然 parser 自己处理, main() 不用再
   strip. 减少代码量 + 集中 `--` 处理.
5. **新 behavior 测新 test, 不改 test 断言** - 把"锁定旧 bug"的 test 改名为
   "测新 behavior", 注释写清楚 R-bugfix-1 改变了什么.
6. **新 behavior 跟 Python argparse 一致** - 用户从 Python 切到 Java CLI
   不会有 surprise.
7. **findSub 是显式方法, 不是 findOption 多态** - 两者职责不同, 合并会
   复杂化查找.
8. **不破坏 ListTarget / aggregateDir 的 positional 收集** - 之前
   `aggregate /tmp/in` 收集 `/tmp/in` 进 positionalList, dispatch 时
   `aggregateDir = Path.of(...)`. 修 nested sub 后这条路仍 work.

## API shape (parser parse 流程)

```
parse(argv, out):
  command = argv[0]                       # top-level sub name
  sub = find subcommands by name
  active = sub
  remainderKey = active.options 中标记 remainder 的 option name (或 null)
  inRemainder = false

  while idx < argv.length:
    arg = argv[idx]
    if inRemainder:
      arg → pytestExtra / extra (按 remainderKey)
      idx++
      continue
    if arg == "--":                      # 显式 sentinel
      inRemainder = true
      idx++
      continue
    if arg 是 option (--xxx 或 -x):
      opt = active.findOption(arg)        # 查 active sub
      if opt.flag: setFlag; idx++
      else: setValue(argv[idx+1]); idx += 2
    else (positional):
      nested = active.findSub(arg)
      if nested != null && !active.subs.isEmpty() && positionalList.isEmpty():
        active = nested                   # 下钻
        remainderKey = nested options 里 remainder 的 key
      positionalList.add(arg)             # 始终收集 (无论是否下钻)
      idx++

  if "list": parsed.listTarget = positionalList[0]
  elif "aggregate": parsed.aggregateDir = Path.of(positionalList[0])
  return parsed
```

## Bug 修复前后对比

### Bug #1: `run --model X --dry-run`

| | 修前 | 修后 |
|---|---|---|
| `--model` 解析 | ❌ 吞进 pytestExtra | ✅ 解析成 model="X" |
| `--dry-run` 解析 | ❌ 吞进 pytestExtra | ✅ 解析成 dryRun=true |
| 实际行为 | validateModel fail, EXIT_CONFIG | dry-run 走 print, EXIT_OK |

### Bug #1: `run -- -k memory`

| | 修前 | 修后 |
|---|---|---|
| `--` 触发 | declaration 触发 (R-bugfix-1 修) | 显式 `--` token 触发 |
| pytestExtra | `[--, -k, memory]` | `[-k, memory]` |
| main() 处理 | strip `--` 后 dispatch | parser 消耗 `--`, 直接 dispatch |

### Bug #2: `list models --group set0 --provider anthropic --json`

| | 修前 | 修后 |
|---|---|---|
| `list` 解析 | ✅ | ✅ |
| `models` 解析 | ❌ 当 unknown positional | ✅ nested sub 切换 |
| `--group` 解析 | ❌ unknown option | ✅ group="set0" |
| `--provider` 解析 | ❌ unknown option | ✅ provider="anthropic" |
| `--json` 解析 | ✅ (parent 上声明) | ✅ |
| 实际行为 | EXIT_CONFIG | listTarget="models", filters 进 Parsed |

## 修前 regression test → 修后新 test 对应

| 修前 test (锁 bug 行为) | 修后 test (验新行为) |
|---|---|
| `parserHonorsNegativeNumbersAsRemainder` | `parserRejectsBareShortFlags` |
| `parserParsesListModelsWithFilters` (assertTrue empty) | `parserParsesListModelsWithFilters` (assertTrue present) |
| `parserParsesListEvalsWithCategory` (assertTrue empty) | `parserParsesListEvalsWithCategory` (assertTrue present) |
| `parserStripsLeadingDoubleDashRemainder` | `parserGatesRemainderOnDoubleDashSentinel` |
| (无) | `parserAcceptsOptionsBeforeDoubleDashRemainder` |
| `mainStripsLeadingDoubleDashRemainderBeforeDispatch` | `mainReachesDispatchWhenRemainderIsGated` |
| `runDryRunDoesNotInvokeShellRunner` (锁 dry-run 失败) | `runDryRunDoesNotInvokeShellRunner` (锁 dry-run EXIT_OK, shell 0 次) |
| `trialsDryRunStillFailsWithoutParsedModel` | `trialsDryRunParsesModelAndTrials` + `trialsWithoutModelStillFails` |

## 累计测试 (R-bugfix-1 后)

- aethercode-deepagents: 253/253 Java
- aethercode-talon: 5/5 Java
- aethercode-a2a: 33/33 Java
- aethercode-a2a-deepagent-bridge: 8/8 Java
- aethercode-cli: 48/48 Java
- aethercode-tools vision: 25/25 Java
- aethercode-workflows: 63/63 Java
- **aethercode-evals: 356/356 Java** (354 R-orch-1 后 + 2 新 test)
- aethercode-tools (含 R-radar-4/5): 322/324 (2 个 pre-existing
  FileReadTool/FileWriteTool sandbox failures, 跟本 round 无关)
- aethercode-desktop (TS): 1042/1042 pass
- aethercode-desktop (Rust): 14/14 pass
- **0 回归** (除 2 pre-existing)

## 教训 (新增 8 条, 累计 208+)

201. **Remainder mode 必须 `--` 触发** - Python argparse 标准, 不按
     declaration 触发. 这是 argparse port 常见错误.
202. **active sub 模式处理 nested sub** - `active = sub` 起步, 遇到
     nested sub name 切换, `findOption` 复用, 不需要 parent + child 合并.
203. **Nested sub 名字仍进 positionalList** - listTarget 等 dispatch 需要.
204. **`--` 由 parser 消耗, 不进 pytestExtra** - 删除 main() 的 strip 逻辑,
     集中处理.
205. **regression test 改名为正向 test** - 锁 bug 的 test 改名 + 重写断言,
     注释写清楚 R-bugfix-1 改了什么.
206. **新 behavior 跟 Python argparse 一致** - 用户切换无 surprise.
207. **sub.findSub 是显式方法** - 跟 findOption 职责不同, 不合并.
208. **不破坏 positional 收集** - aggregate /tmp/in 仍 work, dispatch
     路径不动.

## 后续

- R-orch-2: 把 AgentRuntime 串进 DeepAgentsSystem.respond() 流程
- R-perf-1: cost ceiling + early-exit + cache
- R-mod-1: 抽 `aethercode-orchestration` 独立 module
- R-orch-3: 跟 `reference/papers/` 8 篇 paper 一起跑 end-to-end
