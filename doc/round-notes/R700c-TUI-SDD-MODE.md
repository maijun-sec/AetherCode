# R700c — TUI SDD mode (command-line CLI + diff viewer)

> **基线**: 2026-09-29 (after R700 commit `286d366`)
> **前置**: R700 daemon bundle + desktop RPC wired. TUI was deliberately left untouched because PowerShell's Set-Content was corrupting the non-ASCII em-dash inside tui/commands.ts.
> **Rounds**: R700c (this) + R700d (modify multi-line editor + diff viewer) + R700e (cross-machine pack verify).

---

## 这是什么

TUI 端把 SDD 完整驱动起来。Mavis agent 的 chat 主循环在 desktop / TUI / 任何 RPC client 都通过 daemon 的 `sdd.*` RPC 工作，TUI 不能例外 —— 只是它没有 SddPhaseBar 这种 React 组件，所以用更轻量的 Ink `<box>` 实现一个紧凑的 chip-strip banner。

R317 之后 desktop 端的 "per-phase handler mode" 同样是这个思路：phase 调度权完全在桌面端，agent 永远不自己推进。TUI 也是桌面端调度者 —— 只是渲染方式不同。

## 范围

- `commands.ts` 加 `/sdd` `/sdd-status` `/sdd-list` `/sdd-approve` `/sdd-modify <feedback>` `/sdd-skip` `/sdd-abort`
- `state.ts` 加 `SddRunState` / `SddPhaseEntry` 类型 + `sddRun` 字段 + reducer actions (`sddRun.set` / `sddRun.clear`)
- `components/SddMode.tsx` Ink 组件: 8-chip strip + 当前 phase title + artefact path + 操作提示
- `tui.tsx` 拦截 `__SDD_*__` local tokens → 触发 `sdd.*` RPC + dispatch `sddRun.set/clear`
- 渲染 `<SddMode run={state.sddRun} cwd={cwd} />` 在 scrollback 上方 (when `state.sddRun` is non-null)

## 不变量

1. **LLM 调只在 daemon 端** ✓ (R700)
2. **工具调用只在 daemon 端** ✓ (R700)
3. **TUI 不读 SKILL.md** ✓ (R700)
4. **TUI slash command 单词 `sdd` 不跟 R317 的 desktop `startSsdFlow` 路径绑定** —— TUI 全程走 RPC

## 测试

- `npm run build` 通过
- `npm test` 现有 5 个 TUI tests 不破（不在 SDD 改动路径上）
- 手动：daemon + TUI binary，启动 SDD、approve / modify / skip / abort

## Risk

- PowerShell Set-Content 之前破坏 tui/commands.ts 的中文字符。本 round 全程用 Edit 工具（Read + Edit）做修改，避开 Set-Content。如果 PowerShell 实在跑不了，可以改用 Python + UTF-8 写文件。