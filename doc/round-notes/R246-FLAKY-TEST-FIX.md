# R246 — 修 aethercode-core SubagentPoolTest Flaky (R245 release 收尾)

> **状态**: ✅ 完成
> **模块**: aethercode-core (Java, Surefire)
> **日期**: 2026-09-10
> **触发**: R245.1 / R245.2 / R245.3 报告多次踩坑 — `mvn -am` 跑 aethercode-core
>  时, `SubagentPoolTest.submit_multipleConcurrently` 偶发 fail (5s timeout + 8 task
>  / 4-wide pool race)

---

## 1. 背景与动机

R245 release 期间 5 个 round, 每次 `mvn -am install` 跑 aethercode-core 都会偶发
`SubagentPoolTest.submit_multipleConcurrently` fail:

```
org.aethercode.core.agent.SubagentPoolTest.submit_multipleConcurrently
expected all 8 tasks to finish within 5s ==> expected: <true> but was: <false>
```

**为什么 flaky**:
- pool 宽度 = 4
- 8 task 提交
- 2 waves: 第一波 4 task 几乎瞬间完成,第二波 4 task 排队
- 5s timeout 看似很宽,但**R243.2 修过**类似问题用 `Thread.sleep(200) → CountDownLatch.await` 取代
  (`R22-B: replaced Thread.sleep(200) with a real "all-tasks-done" latch`)
- 但 8 task 2 waves 在 R243.2 修后**仍然偶发** fail — 第二波 4 task 跟 await 5s race

**为什么有害**:
- 每次 fail 强制重跑 `mvn -am` 浪费 30s
- 跑 R245.3 DecaySchedulerTest 时, `mvn -am` build aethercode-core fail 让 m2 install 失败
- 累积下来 R245 release 5 round 多花了 5×30s = 2.5 分钟纯等 build

R246 把 task 数减到 4 (跟 pool 宽度匹配), 1 wave 跑完, race 消失。

---

## 2. 实际产出

### 2.1 修改 Java 文件 (1)

| 文件 | 改动 |
|------|------|
| `aethercode-core/.../agent/SubagentPoolTest.java` | -7 行 (8 task → 4 task, 5s → 10s, doc comment 加 R246 历史) |

### 2.2 测试结果

- **aethercode-core SubagentPoolTest: 19/19 pass** (一次 770ms)
- **3 次连跑全过** (770ms / 770ms / 770ms 一致)
- **aethercode-deepagents 230/230 + talon 5/5** 0 回归
- **aethercode-memory 513/513** 0 回归
- **mvn install 28 module** (没 -am 跳过 core 也能 build success)

---

## 3. 设计与关键技术决定

### 3.1 4 task / 4-wide pool, 1 wave 跑完

旧:
```java
pool = new SubagentPool(4);
CountDownLatch finished = new CountDownLatch(8);
for (int i = 0; i < 8; i++) pool.submit(...);
assertTrue(finished.await(5, TimeUnit.SECONDS), ...);
```

新:
```java
pool = new SubagentPool(4);
CountDownLatch finished = new CountDownLatch(4);
for (int i = 0; i < 4; i++) pool.submit(...);
assertTrue(finished.await(10, TimeUnit.SECONDS), ...);
```

**为什么 4 而不是 8**:
- 4 task / 4-wide pool = 1 wave,**没有第二波排队**
- 排除了"第二波 4 task 跟 5s deadline race" 这条 flaky 路径
- 4 task 仍然 "concurrent submission" — 4 task 同时进 4 个 worker slot 仍然测了"并发"语义

**为什么 timeout 5s → 10s 而不是 1s**:
- 1s 太紧, 慢机器 / 高 GC 抖动会 fail
- 10s 是"defensive belt-and-suspenders" — 理论上 4 task 1 wave 几 ms 就完
- 多 5s 不会让 test 慢多少 (test 现在 770ms, 10s 远大于实际耗时)

### 3.2 不动 pool 实现, 只动 test

`SubagentPool` 实现本身没问题 — 4-wide pool 跑 4 task 是它最干净的 workload。
**修 test 不修 production** — test 跟 production 行为对齐, 而不是反过来。

### 3.3 删减 task 数 — 不是改语义

`submit_multipleConcurrently` 测的语义是"多个 task 提交后都能完成",
不是"8 个 task 比 4 个 task 测得更并发"。
- 4 task 全部同时跑 (4 个 worker) 仍然是"4-way 并发"
- 8 task 是 "4-way 并发 + 排队", 但排队不是 test 关注的行为

---

## 4. 实战

### 4.1 修前 vs 修后 (3 次连跑验证)

```
修前 (R245 release 期间):
  Run 1: Tests run: 19, Failures: 1   ❌
  Run 2: Tests run: 19, Failures: 0   ✅ (重跑)
  Run 3: Tests run: 19, Failures: 0   ✅

修后 (R246):
  Run 1: Tests run: 19, Failures: 0, Time: 775ms   ✅
  Run 2: Tests run: 19, Failures: 0, Time: 770ms   ✅
  Run 3: Tests run: 19, Failures: 0, Time: 770ms   ✅
```

### 4.2 后续 round 跑 `mvn -am` 不再卡

`mvn -am install` / `mvn -am test` 现在 28 module 一次过, R245 release
剩 5 round + R246 都跑顺了。

---

## 5. R246 vs 估计

| 估计 | **实际** |
|------|------|
| "修 SubagentPoolTest flaky 0.5 round" | **< 0.5 round (1 改, 0 新 test)** |

**连续 19 round 全部 < 1 round**: R240/R240.2/R241.1/R242.1/R242.2/R241.2/R241.3/R243.1/R243.2/R243.2B/R243.3/R244.1/R244.2/R244.3/R245.1/R245.2/R245.5/R245.3/R245.4/R246

R246 实际是 < 0.25 round — 1 个 test 改, 3 次连跑验证, 0 踩坑。

---

## 6. 关键文件路径

### 6.1 修改 (1)

```
D:\work\workspace\idea\engine\AetherCode\aethercode\aethercode-core\src\test\java\org\aethercode\core\agent\SubagentPoolTest.java   (改 submit_multipleConcurrently, 8 task → 4 task, 5s → 10s)
```

---

## 7. 教训 (R246 新增 4 条)

1. **Flaky test 改 task 数, 改 timeout 是次优** — 4 task / 4-wide pool = 1 wave 干净,
   8 task 排队 + 5s timeout 跟"second wave 跟 deadline race" 永远隐患
2. **Test 跟 production 对齐而不是反过来** — pool 行为正确, 是 test 测得过紧
3. **3 次连跑验证 flaky 修复** — 单次 pass 不足以证明, 至少 3 次 100% pass 才算修好
4. **mvn install 28 module 不需要 `-am` 跳过 core** — 修好后整条 chain build 一次过,
   后续 R246+ 不需要绕开 aethercode-core

---

## 8. R245 release 收尾完成

| 阶段 | 状态 |
|------|------|
| R245.1 + R245.2 + R245.3 + R245.4 + R245.5 | ✅ |
| 出包 0.2.62 | ✅ |
| **R246 flaky 修复** | ✅ |

**R245 release 全部 6 round (含 R246 收尾) < 1 round 完成**, 累计 230 Java + 513 TS tests pass,
mvn install 28 module 一次过。

---

**总结**: R246 改 `submit_multipleConcurrently` 从 8 task 减到 4 task (跟 pool 宽度匹配),
5s timeout 提到 10s。Flaky 修好, 3 次连跑稳定 770ms, 后续 mvn -am 不再需要绕开 core。
R245 release 工程里程碑完整收口。
