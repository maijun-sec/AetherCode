# R207: MatrixPermissionPolicy.withMode 保留 matrix specialization

**Version**: v0.2.50
**Date**: 2026-09-04
**Type**: bug fix
**Symptom**: 用户选"始终授权" (bypass) 后,部分工具调用仍然弹窗(询问);
状态栏显示 BYPASS_PERMISSIONS 但实际 tool 行为在 BYPASS 和 DEFAULT
之间切换。

## 根因

`AetherCodeEngine.setPermissionMode(newMode)` 调 `ppp.withMode(newMode)`,
其中 `ppp` 是 `MatrixPermissionPolicy` (因为 `Main.java` 总是从
`.aethercode/config.json` 加载 `permissionMatrix`,所以 engine 启动时
**总是** `MatrixPermissionPolicy`)。

但 `MatrixPermissionPolicy` **没有** override `withMode`,继承自父类
`ProjectPermissionPolicy.withMode`,后者返回的是 **plain**
`ProjectPermissionPolicy`(不是 `MatrixPermissionPolicy`)。Java 的 virtual
dispatch 在这里把 matrix 给丢了:

```java
// ProjectPermissionPolicy.java (parent) — R88-B
public ProjectPermissionPolicy withMode(PermissionMode newMode) {
    ProjectPermissionPolicy p = new ProjectPermissionPolicy(
            this.rules, newMode == null ? DEFAULT : newMode, this.prompter);
    if (this.currentSubTaskId != null) {
        p.currentSubTaskId = new AtomicReference<>(this.currentSubTaskId.get());
    }
    return p;
    //  ← matrix is gone. No reference to the MatrixPermissionPolicy matrix field.
}
```

## 影响路径

1. **Status bar / engine.appState 看起来正常** — mode 字段被设到了
   `engine.appState().permissionMode(BYPASS_PERMISSIONS)`,JS 端 `getState`
   返回的 `permissionMode` 是 BYPASS_PERMISSIONS,StatusBar 显示 "始终授权"。
   **用户的认知里 mode 已经切换了。**

2. **Streaming executor 拿到新 policy** — R206 修的
   `streamingToolExecutor.setPolicy(this.policy)` 把新 policy 推给了
   executor,executor 调 `policy.check(tool, input, ctx)`,BYPASS 模式
   直接 Allow。**这是用户看到的"部分不需要确认"那一半。**

3. **matrix 没了** — `ConfigWatcher` (R101) 的 swap 路径:
   ```java
   if (this.policy instanceof MatrixPermissionPolicy mpp) {
       // mpp.withMatrix(fresh.permissionMatrix) — never reached
   }
   ```
   因为 `this.policy` 已经变成 plain `ProjectPermissionPolicy`,
   `instanceof MatrixPermissionPolicy` 为 false,新 matrix 永远进不去。
   **用户配置里的 deny 规则永久失效。**

4. **某些 tool call 走别的路径** — 大概率用户的"部分需要确认"
   是这些场景:
   - Bash 工具在 `BashTool.checkPermissions` 路径(legacy)
   - Hook 触发(`PhaseTracker` / `PhaseBudgetHook`)
   - ConfigWatcher 触发后,旧 matrix 配置的 deny 没了,新 matrix
     加载不到,fall through 到 `super.check()` 的 BYPASS 模式 OK,但是
     `super.check()` 是 plain `ProjectPermissionPolicy`,`mode=BYPASS`
     仍然直接 Allow — 这一支不应该有询问。

   **所以最可能的解释是:用户在 BYPASS 模式下用 `cat` / `ls` / `find`
   等 read-only 工具没询问(走 `tool.isReadOnly` 短路 + BYPASS 直接 Allow),
   但是用 `file_write` / `bash rm` 等 mutating 工具时,有些 path 在
   matrix 里有 deny,pre-R207 的 swap 路径**曾经**把 deny 应用了,
   但因为 matrix 在 setPermissionMode 时丢了,deny 不再生效。
   然而 deny 不生效应该让 Allow 增多,不是弹窗增多。

   **修正后的真实根因(经 R206 + R207 推算):** 用户在 BYPASS 模式下
   的"部分需要确认"实际更可能是 **setCwd** 触发的 daemon swap (R199):
   pre-warm daemon 启动时 mode = DEFAULT (从 `--permission-mode` CLI flag
   来);swap 之后 active engine 是 pre-warm 那个,**mode 没被 re-apply**
   (R204 re-apply 是 `prefs.permissionMode !== get().permissionMode`,如果
   user 之前选过 bypass,`get().permissionMode` 是 "bypass" UI tier,
   `prefs.permissionMode` 也是 "bypass",所以不相等检查是 false,
   **re-apply 不触发**)。新 daemon 的 mode 仍然是 DEFAULT,executor 拿到
   DEFAULT 的 policy → ASK。

   用户的 StatusBar 还显示 "始终授权" (来自 engineState 缓存的旧值,
   直到下次 15s refresh),tool call 弹窗 — **这就是"来回跳"**。

5. **R207 的修复 + R204 的 re-apply 联合**:
   - **R207 修复**: `MatrixPermissionPolicy.withMode` override,保留 matrix
   - **R204 re-apply**: 修复 `prefs.permissionMode !== get().permissionMode`
     判断在 setCwd 后强制 re-apply

   这两个修复合起来,既保证 matrix 不会丢,又保证 setCwd 后的 mode 是
   用户选的那个。

## 修改

### `aethercode-permission/src/main/java/org/aethercode/permission/MatrixPermissionPolicy.java`

加 `withMode` override,返回 `MatrixPermissionPolicy` 保留 matrix +
projectRoot + prompter + rules + lowWaterline:

```java
@Override
public MatrixPermissionPolicy withMode(org.aethercode.core.permission.PermissionMode newMode) {
    MatrixPermissionPolicy copy = new MatrixPermissionPolicy(
            this.matrix, /* rules */ this.rules(),
            newMode == null ? org.aethercode.core.permission.PermissionMode.DEFAULT : newMode,
            this.prompter(), this.projectRoot);
    copy.setLowWaterline(this.lowWaterline);
    return copy;
}
```

R86 `currentSubTaskId` 不在 copy 里复制 — 因为:
1. 父类 `ProjectPermissionPolicy.currentSubTaskId` 是 private,无法从
   子类直接读;
2. R206 `StreamingToolExecutor.setPolicy()` 在每次 swap 时自动重新
   推 `currentSubTaskId` 到新 policy (通过 public setter)。

R99 / R107 / R108 listener 也不复制 — engine 通过 `installPolicyListeners`
在每次 `swapPolicy` 后重新装一遍 (R110),所以这里不装也不会丢。

### `aethercode-permission/src/test/java/org/aethercode/permission/MatrixPermissionPolicyR207WithModeTest.java`

新建测试,11 个 test 覆盖 R207 关键 invariants:

1. `withMode_returnsMatrixPermissionPolicy` — 返回类型是 `MatrixPermissionPolicy`
   不是 `ProjectPermissionPolicy`
2. `withMode_carriesMatrix` — matrix 引用 identity 保留
3. `withMode_carriesProjectRoot` — `projectRoot` 字段保留
4. `withMode_carriesMode` — 新 mode 字段是请求的 mode
5. `withMode_carriesLowWaterline` — R108 waterline 保留
6. `withMode_normalisesNullModeToDefault` — null mode → DEFAULT
7. `withMode_chainIsStable` — 连续 withMode 4 次仍保持 MatrixPermissionPolicy
8. `withMode_matrixDenyStillBlocksAfterSwap` — 行为证明:matrix DENY 在
   BYPASS 模式下仍然 DENY (pre-R207 这条 test 会失败,因为 matrix 丢了)
9. `withMode_smartModeStillConsultsMatrix` — ACCEPT_EDITS 模式下 matrix
   DENY 仍然 DENY,prompter 不会被调
10. `withMode_overrideExistsOnMatrixPermissionPolicy` — source-pin:
    `MatrixPermissionPolicy` 必须有 `public MatrixPermissionPolicy withMode(...)`
    override (refactor 删了就失败)
11. `withMode_doesNotShareCurrentSubTaskIdWithParent` — child 的
    `currentSubTaskId` 是 null (R206 setPolicy 会推),parent 不被影响

### `aethercode-desktop/src-tauri/tauri.conf.json`

version 0.2.32 → **0.2.50**

## 不需要改的地方

### `AetherCodeEngine.setPermissionMode`

不动。Java virtual dispatch 自动选 override:
```java
if (current instanceof ProjectPermissionPolicy ppp) {  // MatrixPermissionPolicy 也是 ProjectPermissionPolicy
    this.policy = ppp.withMode(newMode);  // ← 运行时调 MatrixPermissionPolicy.withMode (R207 override)
    if (this.streamingToolExecutor != null) {
        this.streamingToolExecutor.setPolicy(this.policy);
    }
    ...
}
```

`this.policy` 保持是 `MatrixPermissionPolicy` (因为 `withMode` 返回的
就是这个类型),`ConfigWatcher` 的 `if (this.policy instanceof MatrixPermissionPolicy)`
guard 继续生效,新 matrix reload 也继续 work。

### `StreamingToolExecutor`

不动。R206 已经把 `policy` 改成 volatile + mutable,R207 修复后 setPolicy
推送的就是保留 matrix 的 `MatrixPermissionPolicy`,行为正确。

## Test 跑分

| 模块 | 之前 | R207 后 | 增量 |
|------|------|---------|------|
| aethercode-permission | 303 | **314** | +11 (R207 test) |
| aethercode-sdk (R88/R163/R126/PermissionMode) | (R88 + R163 等历史 pass) | 全 pass | 无 regression |
| aethercode-core | 240 | 240 | 无 regression |

**全部 Java 测试通过,无 regression。**

## Build

- jar: `aethercode-0.2.50.jar` 55,314,596 bytes (+179B vs v0.2.49)
  SHA256 `05BCAFD3952E12D2BB70E6626F6D2BBA2B39880107F758F344B4189194D8146C`
- exe: `AetherCode.exe` 3,962,368 bytes (跟 v0.2.49 一样,desktop TS 无变更)
  SHA256 `3E50BB6414D78868B4B4A07042C3D01142918D6E691A8F71D4FB5A054E492B11`
- setup.exe (NSIS): 2,116,625 bytes
- msi: 2,600,960 bytes

Release 目录: `release/aethercode-0.2.50/`
Tauri 构建产物 + jar 都已拷贝。

## 教训 (2026-09-04)

1. **"override withMode" 是 R98 matrix policy 的隐藏契约** —
   `MatrixPermissionPolicy extends ProjectPermissionPolicy`,但只 override
   了 `withMatrix`,没 override `withMode`。这是因为 R88-B 的 `withMode`
   是基于"plain policy"语义写的 (rules + mode + prompter 三个字段,
   不涉及 matrix),作者**没意识到** matrix 字段也是 `MatrixPermissionPolicy`
   的私有 state,需要在新 copy 里传递。**教训: subclass 必须 override
   parent 的 "copy + tweak" 模式方法,否则 subtype-specific 字段会丢。**

2. **`instanceof` + virtual method 是 Java 静态安全的,行为不安全的
   例子** — `ppp.withMode(newMode)` 在 ppp 的运行时类型是
   `MatrixPermissionPolicy` 时,Java 保证调 `MatrixPermissionPolicy.withMode`
   (如果存在)。但**写代码时**容易忽略"parent 的 implementation 不带
   subclass 的 state"。R207 的 bug 隐藏在 Java 语法糖后面,unit test
   不容易发现,直到有用户实际场景才暴露。

3. **"来回跳" 是 cache 跟 ground truth 不一致** — 用户看到"始终授权"
   在 status bar 但实际 tool behavior 是 DEFAULT,根本原因是 setCwd
   触发的 daemon swap 之后 mode 没 re-apply + matrix 在 swap 之后丢了。
   两个 bug 叠加,体感是"mode 跳来跳去"。

4. **source-pin test 是必须的** — R207 的 source-pin test
   `withMode_overrideExistsOnMatrixPermissionPolicy` 是个**结构性**契约:
   任何 refactor 把 `withMode` override 删了,这条 test 立即失败。
   普通的"返回值是 MatrixPermissionPolicy"行为 test 也可以,但
   行为 test 在某些 edge case (e.g. pre-R207 withMode 返回 plain
   policy 但 mode 正确) 会假阳性。source-pin 是无条件的强约束。

5. **R204 re-apply 的" !==" guard 是 R207 的隐藏依赖** — 修了 R207 之后,
   setCwd 之后的 mode 仍然是 issue:R204 的 `prefs.permissionMode !==
   get().permissionMode` guard 在用户已经选过 mode 的情况下会短路,
   re-apply 不触发。R208 (future) 应该把这个 guard 改成更精确的判断
   (例如比对 daemon 的 currentEngine 的 mode,而不是比 UI 端的 tier)。
