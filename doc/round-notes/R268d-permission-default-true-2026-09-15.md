# R268d: autoApproveMediumHigh default flipped to true — fixes "unattended batch workflow hangs at permission timeout"

## 触发

用户截图显示 (14:53, 2026-09-15): desktop 上 task "Writing unit tests"
跑了 6 次 file_write (14:50-14:51)，然后 daemon 在 permission ask 状态
挂了 5 分钟 (到 14:56)，300s 超时后 fail。LLM 不懂失败原因，
retry 3 次，每次都被 R89 write-guard 拦截。WS 在 15:01:05 重连。
用户发了"继续执行"两次都没被处理。

port 17888 daemon log:
```
14:50:03 R86 policy override installed: tool=bash target=mvn compile
14:50:33-14:51:03 file_write ×6 (AbstractSortTest / Bubble / Selection /
                                  Insertion / Merge / Quick)
14:56:02 WARN permission ask for file_write timed out / failed: null ->
       'file_write' timed out after 300s with no user response.
       DO NOT retry this tool call
15:01:05 ws client disconnected (重连)
15:01:08-10 WriteExistingFileGuardHook blocking overwrite ×3 (heapsorttest)
```

## 根因

daemon 的 `autoApproveMediumHigh` 默认是 `false`:
```java
// AetherCodeMethods.java line 372 (改之前)
private volatile boolean autoApproveMediumHigh = false;
```

意思是 file_write (medium risk) 和 bash (high risk) **每次都
需要用户确认**。用户的"写 6 个 unit test"task 触发 6 次
permission ask，每次 5 分钟 (300s) 超时。LLM 拿到错误信息
("DO NOT retry") 但模型还是会 retry，触发 R89 write-guard
("已存在的文件不覆盖")，整个 task 死循环。

## 修复

把 daemon 默认值改为 `true`:
- file_write (medium) 自动 allow
- bash 安全命令 (high) 自动 allow
- critical (rm -rf / sudo / mkfs / dd) **永不** auto-approved
  (有专门的 risk classifier 在 daemon 侧短路)

具体改动:
1. `AetherCodeMethods.java` line 372: `false` → `true` (加新注释)
2. `desktop/store/index.ts` line 3160: `false` → `true` (跟 daemon 对齐)
3. 测试更新:
   - `AetherCodeMethodsR126Test.setAutoApproveMediumHigh_defaultFalse` → `_defaultTrue`
   - `JsonRpcPermissionPrompterR120Test.highRisk_bypassesShortCircuit` →
     `highRisk_autoApproveMediumHigh_autoApproves` (新行为)
   - `JsonRpcPermissionPrompterR181TimeoutTest.StubMethods` 显式
     调 `setAutoApproveMediumHigh(false)`，因为该 test 测的是
     timeout 路径，需要 prompt 真的走到 RPC 层

## 兼容

- 新用户：默认 true，task 不再卡 ✓
- 老用户 localStorage 里有 `prefs.autoApproveMediumHigh = false`：
  desktop 在 initialize() 时检测 prefs 和 daemon 不一致，会 push
  `false` 给 daemon (`store/index.ts` line 3610-3619)。所以老
  用户的明确选择被保留 ✓
- 老用户没 localStorage prefs：跟新用户一样，daemon 默认 true ✓
- Critical risk (rm -rf/sudo/mkfs/dd) **永不** auto-approved,
  即使 flag 是 true (R183 logic in JsonRpcPermissionPrompter)
- 用户想 opt-out：Settings → Permissions → 关掉 auto-approve medium+high

## 产出

| 路径 | SHA | Size |
|---|---|---|
| `release/aethercode-0.2.70/desktop/aethercode-desktop.exe` | `80832BE67C7CEE6AED96F00DF0C364E82763F75D` | 5,175,808 |
| `release/aethercode-0.2.70/desktop/aethercode.jar` | `01C6069EE86A11F994CE0432D5CC8F94811C98A2` | 56,582,323 |
| `release/aethercode-0.2.70/aethercode-0.2.70.jar` | `01C6069EE86A11F994CE0432D5CC8F94811C98A2` | 56,582,323 |
| `release/aethercode-0.2.70.zip` | `E176D11DC80C0B685308D6DA9489E741E7B658CD` | 108,482,674 |

jar 字节码验证 (verify_r268d.py):
- isStructurallyEmpty / emptyInputStreak / lastLoopKind / empty_tool_input /
  buildMissingParamError / missing fields (R266i markers) ✓
- autoApproveMediumHigh / setAutoApproveMediumHigh (新 R268d markers) ✓
- 8/8 markers

## 测试

- vitest (aethercode-core): 1166/1166 (含 8 个 R266i isStructurallyEmpty test)
- mvn aethercode-protocol: 全 pass (含 R126 / R120 / R181 / R183 tests 更新后)
- daemon smoke test: `java -jar aethercode.jar --http-port 18890` 启动成功,
  监听 18890, HTTP+JSON-RPC server ready

## 用户下一步

1. 下载 `release/aethercode-0.2.70.zip` (108 MB, SHA `E176D11D...`)
2. 解压覆盖旧的
3. 重新跑 `desktop/aethercode-desktop.exe`
4. 继续 "Writing unit tests" task:
   - 之前 daemon 卡在 permission ask 超时, LLM retry
   - 现在的 daemon 默认 autoApprove medium+high, file_write 直接通过
   - critical risk (rm -rf / sudo) 还是需要确认, 安全网还在
5. 如果用户想 opt-out: Settings → Permissions → 关掉 "medium+high auto-approve"

## 教训 (新增)

220. **Permission ask timeout 默认 5min 对 batch workflow 太长** — daemon 内部 LLM
     拿到 timeout 错误还会 retry, 每次都重新进 ask 循环, 没真正 "fail fast"。要么
     缩短 timeout (例如 60s), 要么默认 auto-approve non-critical。R268d 选后者
     (更符合 batch task 实际场景, critical risk 仍有安全网)。

221. **前端 sendMessage 没 isConnected 检查** — 用户在 disconnected/reconnecting 状态
     发 prompt, prompt 进 pendingFollowUp 但 queue 状态不够显眼, 用户以为被忽略。
     应加: sendMessage 头检查 isConnected, false 时 surface error + 自动 trigger
     reconnect, 加 followup-pill 在 reconnecting 状态可见。这条留给 R269 单独做。

222. **Critical risk 永不让 auto-approved** — R268d 改动只影响 medium + high, critical
     (rm -rf / sudo / mkfs / dd) 仍走 askPermission 路径, 安全网一直在。
     这是设计的关键不变量, 改默认值时一定不能动这条。

223. **老用户的明确选择必须保留** — localStorage prefs.autoApproveMediumHigh = false
     是用户主动设的, 不能因为改默认值就覆盖。要在 init 时检测 prefs 和 daemon
     不一致, push prefs 给 daemon (existing pattern in store/index.ts line 3610)。

## 待办

- R269: DisconnectedBanner + sendMessage isConnected 检查 + followup-pill 显眼化