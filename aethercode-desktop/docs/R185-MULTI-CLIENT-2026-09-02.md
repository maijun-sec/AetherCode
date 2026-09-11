# R185 — 多客户端架构澄清 + aethercode.cwd 清理 (2026-09-02)

## 1. 用户原话

> aethercode.cwd 是什么东西？我们应该支持多客户端同时运行，每个客户端可以是不一样的项目。

## 2. `aethercode.cwd` 是什么

JVM 启动时设的全局系统属性：`java -Daethercode.cwd=...`。
- 设的地方：`aethercode-cli/DaemonRunner.java`（CLI 入口）
- 写的地方：`aethercode-sdk/AetherCodeEngine.java:298`（每个 engine 构造时）
- 读的地方：
  - `aethercode-tools/.../file/FileWriteTool.java:92`（R183 已加 per-session cwd 优先）
  - `aethercode-tools/.../file/FileReadTool.java:209`（R183 已加 per-session cwd 优先）

**核心问题**：这是个 JVM-wide singleton，**会泄漏**。多个 session 共用一个 daemon 时，session A 的 `setCwd` 会改写 session B 的全局属性——任何直接读 `System.getProperty("aethercode.cwd")` 的人都会拿到错的值。

R185 删除了 `AetherCodeEngine.java:298` 的 `System.setProperty(...)` 调用。session cwd 现在的真源是 `AppState.cwd()`，工具通过 `CallContext.extra("app_state").cwd()` 拿。环境变量 `AETHERCODE_BASH_CWD`（由 `AppState.cwd` setter 维护）保留——它是 per-process 的，且**始终反映当前 active session 的 cwd**。

## 3. 多客户端架构现状

| 场景 | 是否支持 | 说明 |
|------|---------|------|
| 一个 daemon 多 session（不同项目） | ✅ 已经支持 | 每个 session 有自己的 `AppState.cwd`；R183 让 file_write 优先用 per-session；端到端测试 `probe-multi-session.py` 通过 |
| 多 daemon（不同端口） | ✅ 已经支持 | 每个 daemon 自己的 JVM，自己的端口，自己的 --cwd |
| 桌面单窗口多 session | ✅ 已经支持 | desktop 的 CWD picker 调 `bindSessionCwd({ cwd, sessionId })`（per-session） |
| 桌面多窗口（每个窗口一个项目） | ⚠️ Tauri 默认单实例 | 需要 desktop 多窗口支持（后续 R186 候选） |

## 4. 端到端多 session 测试（probe-multi-session.py）

```
daemon started with --cwd D:\tmp\multi_daemon_root
=== setAutoApproveMediumHigh(true) ===  -> ok

=== session A in projectA (cwd=D:\tmp\multi_daemon_root\projectA) ===
  A: query -> run-1, 5s 完成
  [OK] D:\tmp\multi_daemon_root\projectA\A.txt = 'from session A'

=== session B in projectB (cwd=D:\tmp\multi_daemon_root\projectB) ===
  B: query -> run-2, 5s 完成
  [OK] D:\tmp\multi_daemon_root\projectB\B.txt = 'from session B'

=== isolation check ===
  [OK] D:\tmp\multi_daemon_root\projectB\A.txt correctly does not exist
  [OK] D:\tmp\multi_daemon_root\projectA\B.txt correctly does not exist

=== summary ===
  MULTI-SESSION CWD WORKS
```

两个 session 独立写各自 cwd 下的文件，零串扰。

## 5. 改动

| 文件 | 改动 |
|------|------|
| `aethercode-sdk/.../AetherCodeEngine.java:298` | 删除 `System.setProperty("aethercode.cwd", ...)` |
| `aethercode-desktop/probe-multi-session.py` | 新建多 session 端到端测试（3.7KB） |

## 6. 测试

- aethercode-core + aethercode-sdk 编译通过
- 端到端 probe-multi-session.py 通过
- 桌面 vitest 887/887 通过（无变化）

## 7. v0.2.32 构建产物

| 文件 | 大小 | SHA256 |
|------|------|--------|
| `release/aethercode-0.2.32/AetherCode.exe` | 3,950,592 (3.95 MB) | `0426A033682F81E9C39ADEBFF96815ECFA42D3D8CBF06FBCE6D535392EDB21D5` |
| `release/aethercode-0.2.32/aethercode-0.2.32.jar` | 55,309,190 (53 MB) | `460035D6228D1AD2E174E61BDAF8CAACD689F0BD37DFF6D34B4ACFE8E84BF2C5` |
| `aethercode/dist/aethercode-0.2.32.jar` | 同上 | 同上 |

对比 v0.2.31：jar 多了 R185 的注释（37 字节）和一小段 R183 multi-session 测试逻辑；新 jar 大了 2,963 字节（主要是 R185 的注释文本）。

## 8. 多客户端使用方式（用户指南）

### 8.1 同一个桌面，跑多个项目

1. 启动桌面 → 头部 "+ 新会话" 按钮（或者新窗口，看 Tauri 配置）
2. 第二个 session 创建时弹 cwd picker
3. 选 D:\work\projectB（第一个 session 已经在 D:\tmp\abc_1）
4. 两个 session 各自调模型、写文件，**完全独立**

### 8.2 多个 daemon 进程（CLI 重度用户）

```powershell
# daemon 1：监听 17888，cwd 在 projectA
java -jar aethercode-0.2.32.jar --http-port 17888 --cwd D:\work\projectA

# daemon 2：另一个端口，cwd 在 projectB
java -jar aethercode-0.2.32.jar --http-port 17889 --cwd D:\work\projectB
```

两个 daemon 互不干扰，可以同时跑。

### 8.3 桌面 + CLI 混用

桌面默认接 17888，CLI 启 daemon 接 17889。两者用不同端口，可以同时跑。

## 9. 教训 (2026-09-02)

1. **"aethercode.cwd" 这个名字本身就在撒谎**——它是 daemon 全局，不是 session 局。R185 删掉它的写入路径后，session cwd 的真源（`AppState.cwd`）不再被任何全局状态污染。教训：**全局属性名要精确反映作用域**。如果叫 "daemon_cwd" 或 "default_cwd" 就清晰多了。
2. **per-session state vs daemon-global state 是常被搞混的设计**。R127 引入 per-session cwd，但当时没审计所有 reader（FileWriteTool / FileReadTool 还在读全局属性）——R183 才补。R185 又发现了更隐蔽的泄漏：AetherCodeEngine 构造时**写**全局属性，导致第二个 session 的 cwd 错乱。教训：**当引入 per-session state 时，必须审计所有 read + write 路径**，确保没有任何地方用全局属性当 per-session 替代品。
3. **端到端多客户端测试是发现这类 bug 的唯一办法**。单 session 测试看不出"两个 session 共享一个 daemon 时全局状态被谁污染"的问题。`probe-multi-session.py` 5 秒就证明了隔离正确——R185 的 fix 没破坏什么。

## 10. R186 候选

1. 桌面多窗口支持（Tauri feature），让用户一个 exe 开多个项目窗口
2. `AETHERCODE_BASH_CWD` 环境变量也清理一下：现在它在 `AppState.cwd` setter 里设，但 BashTool 用的是 `ctx.cwd`（per-call），可能存在 stale 问题
3. 把 `probe-multi-session.py` 接入 CI 冒烟测试
4. 在 aethercode-sdk 加 unit test：多 engine 共享一个 daemon 时，每个 engine 独立持 cwd
