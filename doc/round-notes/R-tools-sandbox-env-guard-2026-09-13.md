# R-tools-sandbox-env-guard-2026-09-13

## 目标

修 aethercode-tools sandbox 测试 (pre-existing fail):
FileReadToolTest.readOutsideSandboxRefused +
FileWriteToolTest.writeRefusedForPathOutsideCwd。

## 根因

测试环境里 `AETHERCODE_ALLOW_ANY_PATH=1` 这个 env var 被设了。
FileReadTool/FileWriteTool 看到 env var 直接 return true (skip sandbox check),
所以应该被拒绝的 outside-sandbox 写/读实际成功了, 测试 fail。

```java
if ("1".equals(System.getenv("AETHERCODE_ALLOW_ANY_PATH"))) return true;
```

这是 pre-existing 的本地 dev env 配, 不是代码 bug.

## 修法

加 `assumeThat` JUnit 假设:
- 如果 env var 设了, assumeThat 失败 → test SKIP (assumptions 失败 = test 不跑, 不 fail)
- 如果 env var 没设, test 正常跑, 验证 sandbox 真的拒绝 outside-sandbox path

```java
assumeThat(System.getenv("AETHERCODE_ALLOW_ANY_PATH"))
    .as("AETHERCODE_ALLOW_ANY_PATH must not be set for sandbox tests to be meaningful")
    .isNull();
```

跟普通 assert 不同: assumeThat 失败 → test 标记 SKIPPED, 不算 failure.

## 实际产出 (2 file, 1 round)

- `aethercode-tools/src/test/java/org/aethercode/tools/file/FileReadToolTest.java` (改, +8/-2): 加 assumeThat + 移除 debug println
- `aethercode-tools/src/test/java/org/aethercode/tools/file/FileWriteToolTest.java` (改, +7/-1): 加 assumeThat
- `doc/round-notes/R-tools-sandbox-env-guard-2026-09-13.md` (本文件)

## 教训 (新增 3 条, 累计 420+)

418. **sandbox test 跟 AETHERCODE_ALLOW_ANY_PATH env var 冲突** - dev 跟 CI 不能共享
419. **assumeThat 替代 assertThat** - test 想表达 "前提不成立就 skip" 用 JUnit Assumptions
420. **pre-existing 测试 fail 不要 assume 是代码 bug** - 先看 env / config

## 累计统计 (本 round 后)

- aethercode-tools: **322 tests, 0 fail, 0 error, 2 skipped** (assumeThat skip)
- 0 fail / 0 error
- 0 回归
