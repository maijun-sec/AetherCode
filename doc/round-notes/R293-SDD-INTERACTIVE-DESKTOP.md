# R293 — SDD Interactive Desktop Wiring (R292 follow-up)

## 触发

R292 R292 release 后用户在 desktop 上点 📐 规格化流程输入 prompt，发现两个问题：

1. **"一闪而过，什么等待确认，全都是不存在的"** — 输入 prompt 之后各个阶段
   chip 一闪而过，没有任何 UI 让用户确认/修改。
2. **"sdd 流程占了一大截，啥都看不到了"** — SddPhaseBar 的 8 个 chip + 各自
   preview 内容占了 viewport 一大半。

## 根因

R292 的 `store.startSsdFlow` 写死用的是 `MockSsdDriver`（canned 14 events × 280ms ≈
3.9s 自动跑完），**完全没有连上 daemon**。R288/R289 时留下来的 TODO "TauriSsdDriver
later is a one-line change" 在 R292 spec kit 改造时被遗漏了——chip 状态正确但都是
mock 自动发的 phase-accepted 事件，daemon 端 `sdd --interactive` 子进程根本没 spawn。

UI 布局是 R292 时期为了"显示预览给用户看"设计的——每个 chip 把 4KB draft preview
渲染到 chip 内部。看起来信息量大，但 8 个 chip × preview 让 SddPhaseBar 占了
viewport 一大半，且 chip 不能交互（只有右上角 ✕ 发 quit）。

## 改动

### Desktop

1. **`store.startSsdFlow` 改用真 `TauriSsdDriver`** — 当 `daemonInfo.jarPath` 存在时
   spawn `java -Xmx4g -jar aethercode.jar sdd <feature> "<intent>" --interactive
   --cwd <cwd>`；`MockSsdDriver` 降级为 dev fallback（没 daemon 时）。

2. **`SddPhaseBar` 紧凑化** — chip 默认只显示 glyph + number + 中文 title +
   可选 tag，preview 内容移到 chat 流（store 里 `phase-draft` handler 已经 push 成
   `system` 消息）。chip 列表变成单行 pill 条。

3. **`SddPhaseBar` 加 per-phase action bar** — 当 phase state 是 `pending-accept` /
   `clarify-pending` / `converge-pending` 时，下方出现操作条：
   - **pending-accept** → ✅ 接受 / ✏️ 修改（弹 prompt）/ ⏭️ 跳过
   - **clarify-pending** → 输入框 + 📤 发送回答（自动从最近一条
     `sdd-clarify-question` metadata 取 id）
   - **converge-pending** → ✅ 结束（accept not-converged）/ 🔁 再迭代（带反馈）

4. **`sendSsdCommand` 类型扩展** — 已支持 `clarify-answer`（id + answer）和
   `converge-iterate`（text）；driver.sendCommand 转发到 TauriSsdDriver.child.write。

5. **新测试 7 个** — 验证 pending-accept / clarify-pending / converge-pending 三种
   state 各自的按钮渲染 + sendSsdCommand 调用 + 紧凑 UI（chip 不再有 body /
   preview 子节点）。SddPhaseBar 测试从 8 → 15 个，全过。

### Daemon

1. **`InteractiveRepl.readReply` 加 5 分钟超时** — 用
   `Executors.newSingleThreadExecutor` 包装 `System.in.readLine()`，超时自动 accept。
   防止 Tauri shell 2.x 在 Windows 上 stdin pipe 行为异常时 daemon 永久 hang。
   Override via `-Daethercode.sdd.readTimeoutSeconds=N`。

2. **`InteractiveRepl.close()` + SddCommand finally 块** — 关闭 executor，否则
   desktop `kill()` 要等满 5 分钟 timeout 才能让 JVM 退出。

3. **SddCommand 异常路径 close** — `catch (Exception)` 也走 finally，确保
   `interactiveRepl.close()` 必被调。

## 文件清单

| 文件 | 改动 |
|------|------|
| `aethercode-desktop/src/store/index.ts` | startSsdFlow 选 driver + sendSsdCommand 类型扩 6 种 |
| `aethercode-desktop/src/components/SddPhaseBar.tsx` | 紧凑 chip + action bar + 3 套按钮 |
| `aethercode-desktop/src/components/SddPhaseBar.css` | 重写：pill 样式 + action bar |
| `aethercode-desktop/src/components/__tests__/SddPhaseBarR292.test.tsx` | +7 个测试覆盖 R293 UI |
| `aethercode/aethercode-workflows/src/main/java/.../InteractiveRepl.java` | readReply 超时 + close() |
| `aethercode/aethercode-cli/src/main/java/.../SddCommand.java` | finally close executor |

## 验证

- **daemon**: `mvn -pl aethercode-workflows test` 全过（11+10+12+6 = 39 个 Sdd tests）
- **daemon install**: `mvn -pl aethercode-cli -am install` BUILD SUCCESS（14.8s）
- **daemon jar**: `aethercode-cli-0.1.0-SNAPSHOT.jar` 56.6 MB
- **desktop tsc**: 无错误
- **desktop vitest**: 101 files / 1170 tests 全过
- **desktop build**: `pnpm tauri build` 成功：
  - `aethercode-desktop.exe` 6.23 MB
  - `AetherCode_0.3.0_x64-setup.exe` (NSIS) 52.5 MB
  - `AetherCode_0.3.0_x64_en-US.msi` (WiX) 54.1 MB

## 风险

- **Tauri shell 2.x stdin pipe 真能保留** — 这是关键未验证点。如果 Windows 上
  `cmd.spawn()` 后 `child.write()` 真无法传到 JVM stdin，daemon 端 5 分钟超时兜底会
  自动 accept phase（用户看不到 UI 但流程不挂）。需要用户实际跑一次确认。
- **clarify id 传递** — 当前从 store 里"最近一条 `sdd-clarify-question` metadata"
  读 id。理论上 daemon 一次只会有一个 pending question，匹配应该正确。但 R293 后
  续如果出现多个 clarify-question 并发需要补全。

## 教训（新增 619）

619. **Bounded `readLine()` 必须显式 close executor** — `Executors.newSingleThreadExecutor`
    默认非 daemon worker，会阻止进程退出。desktop 端 spawn 的 `sdd --interactive`
    子进程如果不 close，主进程 `kill()` 要等满 `readTimeoutSeconds` 才能让 JVM
    自然退出。诊断招：写任何有限超时 IO 时都要在 finally 里 `shutdownNow()`。

— Mavis, R293, 2026-09-20