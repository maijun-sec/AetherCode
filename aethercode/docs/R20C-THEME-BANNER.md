# R20-C: 配色 + 边框 + 启动 banner (2026-08-06)

## 目标

把 R20-A 搭好的全屏框架做"漂亮"：统一调色板、box-drawing 边框、启动 banner。

## 变更

### 新增类

- `Theme` — 集中式调色板。20 个语义角色（BRAND / SPINNER / TOOL_ACTIVE / TOKEN_LOW/MED/HIGH / ...），每个映射到 `TextColor.ANSI` + `SGR[]` 修饰符。提供 `Theme.Entry.of(color, modifiers...)` 工厂、`get(role)` 查询、`setPalette(map)` 整体替换、`ansi(role)` 转 ANSI 转义码（给行式 REPL 用）。
- `Banner` — 启动 banner 渲染器。figlet 风格 5 行 "AETHERCODE" ASCII art + 上/下边框 + 版本号 + model/session/tools 信息行 + tagline；窄终端 fallback 到单行版本戳。

### 改造

- `HeaderBar` — 用 `Theme.get(role)` 替代硬编码 `TextColor.ANSI.CYAN_BRIGHT` 等。状态条 + 主体用 STATUS_IDLE 配色，BRAND 配色给无状态时。分隔线用 BORDER 角色（cyan）。
- `StatusBar` — `paintSegment()` 签名从 `TextColor.ANSI color, boolean bold` 改为 `Theme.Entry entry, boolean bold`，所有 4 段（plan / tool / tokens / hint）按 role 上色。新增 `roleForPlan/Tool/Token()` helper。状态栏上方画一条 BORDER 颜色的分隔线。
- `ReplApp.withFullScreen()` — 启动后调 `Banner.paint()` + `screen.refresh()` + `Thread.sleep(900ms)` 让 banner 可见 0.9s，然后进入正常 REPL loop。

## 测试

新增 12 tests，0 regression：

| Test class | Tests |
|---|---|
| `ThemeTest` | 8 (Entry.of / canonical / get / setPalette / null reset / ansi) |
| `BannerTest` | 4 (wide / narrow / very small / null model) |
| **合计** | **12** |

总测试数：**1295** (R20-B: 1283 → R20-C: 1295, +0.9%)

## 关键 pitfall

1. **record 紧凑构造器不能 `this(...)`** — Java 21 record 的紧凑构造器不能显式调用 canonical constructor。重构成 `Entry.of(color, SGR...)` 静态工厂。
2. **TextColor.ANSI 没有 `BRIGHT`** — 只有 `WHITE_BRIGHT` / `BRIGHT_*` 八个亮色。case 写错编译报错 "找不到符号 BRIGHT"。
3. **Lanterna `paintSegment` 之前是 `TextColor.ANSI` + `boolean bold`** — 改成 `Theme.Entry` 后要重新调 `enableModifiers` / `disableModifiers`，并用 `hasBold(entry)` 检查避免重复 enable/disable BOLD。
4. **Banner 在窄终端不能崩** — `cols < 60 || rows < 10` 时 fallback 到单行紧凑版。

## 验证

```
$ mvn -B test          # BUILD SUCCESS, 1295 tests, 0 fail, 0 error
$ mvn -B -pl aethercode-tui install -DskipTests
$ mvn -B -pl aethercode-cli package -DskipTests
$ java -jar aethercode-cli-0.1.0-SNAPSHOT-shaded.jar --version
  → aethercode 0.1.0
```

## 关联

- 备份目录：`docs/backups/r20c/`
- 上一轮：`docs/R20B-LIVE-PROGRESS.md`
- 路线图：`docs/R20-ROADMAP.md`
- 下一轮：R20-D 后端持久化 TaskRegistry (JSONL)
