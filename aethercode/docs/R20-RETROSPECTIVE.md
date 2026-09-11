# R20 整体回顾 (2026-08-06)

## 数字

| 指标 | R19 baseline | R20 末 | 增量 |
|---|---|---|---|
| 测试数 | 1227 | **1413** | **+186 / +15.2%** |
| 模块数 | 14 | 16 | +2 (lanterna 进 tui pom) |
| net regression | 0 | 0 | ✓ |

## 10 轮回顾

| 轮 | 内容 | 新增测试 | 状态 |
|---|---|---|---|
| R20-A | TUI 全屏框架 (Lanterna + header + status bar) | +31 | ✅ |
| R20-B | 实时进度 (token rate + tool timer + repaint scheduler) | +25 | ✅ |
| R20-C | 配色 + 边框 + 启动 banner | +12 | ✅ |
| R20-D | 持久化 TaskRegistry (JSONL + 崩溃恢复) | +11 | ✅ |
| R20-E | PlanExecutor (引擎驱动 plan 步骤) | +12 | ✅ |
| R20-F | 滑动窗口 + 摘要链 (10 小时不爆上下文) | +13 | ✅ |
| R20-G | 任务树可视化 (box-drawing + 嵌套 + 计时) | +19 | ✅ |
| R20-H | 自动规划 + 简单 plan 自动批准 | +18 | ✅ |
| R20-I | Subagent 池 + 失败重试 + 指数退避 | +24 | ✅ |
| R20-J | 监督定时器 + Checkpoint + 恢复 | +21 | ✅ |
| **合计** | | **+186** | |

## 关键成就

### TUI 改造
- Lanterna 3.1.3 加入，JLine 3 继续负责输入
- header (model + session + permission + tools) / status bar (plan + tool + token + elapsed + hint)
- 实时进度：token-per-second 滑动窗口、tool 计时器、thinking spinner
- 配色集中化 (Theme，20 角色)
- 启动 banner ASCII art
- 任务树 box-drawing (├── │ └──) + elapsed 计时

### 后端长任务能力
- **持久化 TaskRegistry** — JSONL 持久化 + 崩溃恢复 (R20-D)
- **PlanExecutor** — 引擎自己按 plan 步骤执行 + 失败不中断 (R20-E)
- **滑动窗口 + 摘要链** — 10 小时不爆上下文 (R20-F)
- **PlanClassifier** — 简单 plan 自动批准，复杂 plan 提示用户 (R20-H)
- **RetryPolicy + RetryHelper** — 指数退避 + jitter + 可插拔 sleeper (R20-I)
- **Watchdog** — 60s 无事件触发回调 (R20-J)
- **Checkpoint** — JSONL 序列化 + 列表 (R20-J)

## 重要 pitfall (跨轮总结)

1. **依赖方向** — `aethercode-sdk` 比 `aethercode-tui` 更靠下，SDK 不能依赖 TUI。R20-E/H 都踩过。
2. **Lanterna 限制** — 3.1.3 没有 `SGR.DIM`；`putString` 不接裸 ESC（必须用 TextGraphics API）；`DefaultVirtualTerminal` 用 `setTerminalSize()`。
3. **Spliterator 协议** — `tryAdvance` 一次只能 emit 一个 event。R20-E 起初 abort 路径在 `tryAdvance` 内部循环 emit 多个 SKIPPED 然后 return false，违反协议。
4. **record 紧凑构造器** — 不能 `this(...)` 调 canonical ctor。用静态工厂绕过。
5. **测试阈值算错** — R20-F 起初用 `contextWindow=200_000` 但消息只有 200K chars → 50K tokens < 187K threshold，`shouldCompact` 永远返回 false。改用更小的 contextWindow。
6. **dependency 重建** — 改了 aethercode-tui 后必须先 `mvn -pl aethercode-tui install -DskipTests` 再打 cli 的 shaded jar，否则 NoClassDefFoundError。

## 备份

每个轮次都有独立备份目录 `docs/backups/r20{a-j}/`，包含：
- 主要修改的源文件
- 测试文件
- 完整 pom 变更（如适用）

## 下一步候选 (R21+)

R20 解决了「10 小时长任务的基础设施」—— TUI 美化、持久化、自动执行、retries、watchdog、checkpoint。R21+ 可以从以下方向继续：

- **R21+ TUI 完善** — multi-line input / completion / syntax highlighting in input / 命令面板增强
- **R21+ 后端能力** — subagent 并发池优化 / agent 间消息总线 / 远程 agent
- **R21+ 调试** — debug mode / step-debug / 计划可视化
- **R21+ 部署** — IDEA 插件完善 / 远程服务 / multi-user session

## 关联

- 路线图：`docs/R20-ROADMAP.md`
- 每轮单独 doc：`docs/R20{A-J}-*.md`
- 每轮备份：`docs/backups/r20{a-j}/`
