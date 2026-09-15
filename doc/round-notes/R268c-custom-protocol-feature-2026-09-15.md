# R268c: tauri/custom-protocol feature flag — fixes "frontend assets not bundled"

## 触发

用户报 "现在 desktop 打不开了" + 截图显示 WebView2 渲染了
Chromium 的 ERR_CONNECTION_REFUSED (localhost 拒绝连接)。

R268b 部署完后所有 bytecode / zip layout / jar SHA 都对，但
desktop 启动时 WebView2 加载 `http://localhost:1420/` (dev URL)，
dev server 没在跑 → ERR_CONNECTION_REFUSED。

## 根因

```bash
# R268 (2026-09-15 9:47):  正确 -- 5.17 MB
cargo build --release --features tauri/custom-protocol

# R268b (2026-09-15 10:26):  错  -- 4.16 MB
cargo build --release    # ←漏了 --features tauri/custom-protocol
```

`tauri/custom-protocol` feature 告诉 Tauri 在 `generate_context!()`
编译时把 `frontendDist` 指定的 dist/ 资源嵌进 exe 里。不开这个
feature，生成的 exe 不知道 assets 在哪，WebView2 启动时只能
fallback 到 `devUrl` (http://localhost:1420/)。

dev server (`npm run dev` → vite on port 1420) 不在跑 →
localhost:1420 拒绝连接 → WebView2 显示 ERR_CONNECTION_REFUSED
页面。

**两个 round 的差别只有这一个 feature flag**：
| Round | 命令 | Size | dist 嵌入 |
|---|---|---|---|
| R268  | `cargo build --release --features tauri/custom-protocol` | 5.17 MB | ✓ |
| R268b | `cargo build --release` | 4.16 MB | ✗ |

devtools 9222 验证：chromium target `url=http://localhost:1420/`
(R268b exe) vs `url=http://tauri.localhost/` (R268c exe)。

## 修复

build_r268_full.py / build_r270_full.py 应该统一用：

```bash
cargo build --release --features tauri/custom-protocol
```

## 产出

| 路径 | SHA | Size |
|---|---|---|
| `release/aethercode-0.2.70/desktop/aethercode-desktop.exe` | `9C2BDB162F2415B584D3C7CB3FC7ED6FD4B98654` | 5,175,808 |
| `release/aethercode-0.2.70/desktop/aethercode.jar` | `337DCAE816EC072665EDE34DBCB3C06F07EC509D` | 56,582,324 |
| `release/aethercode-0.2.70.zip` | `5F0E6CB0224291B59B9DC0BE34FAFAE02FFE5DD6` | 108,482,573 |

## Bytecode verification

```
exe 字节码包含:
  - assets/index-Bl2FnYdo        ✓ JS bundle
  - assets/index-3DsPe2q         ✓ CSS bundle
  - [R268] find_jar_path         4 处
  - [R268b] find_jar_path        2 处
  - 1420 (dev URL fallback)      仍在 (老代码路径保留)
  - localhost                    仍在 (dev URL fallback)
```

## Runtime smoke test

启动 desktop → chromium target URL = `http://tauri.localhost/`
（之前是 `http://localhost:1420/`，dev server 没跑才坏）

stderr 完整链路：
```
[R81] ensure_daemon: start
[prior round] find_jar_path: resource_dir = \\?\...\desktop
[prior round] find_jar_path: Tauri resource (exact) \\?\...\desktop\aethercode.jar
[R81] jar: \\?\...\desktop\aethercode.jar
[R172] daemon JVM args: ["-Xms1g", "-Xmx4g", "-XX:+UseG1GC", "-XX:MaxMetaspaceSize=256m"]
[prior round] cwd: persisted last-project D:\tmp\abc_1
[R81] SUCCESS: http://127.0.0.1:17888 (ws=ws://127.0.0.1:17888/ws)
[R82+] pre_warm_daemon: D:\tmp
[R82+] pre-warm ready: http://127.0.0.1:18889 (cwd=D:\tmp)
```

两个 daemon 都 spawn：
- primary on 17888 (cwd=D:\tmp\abc_1)
- pre-warm on 18889 (cwd=D:\tmp)

## 教训 (新增)

221. **Tauri build feature flag 是部署链路一部分** —
     `cargo build --release` 跟 `cargo build --release
     --features tauri/custom-protocol` 生成的不是同一个 exe。
     后者嵌入 dist/ assets，前者不嵌入 → release 启动 fallback
     到 dev URL。3 MB 体积差 = "资源嵌没嵌入" 的标志。

222. **build script 必须 hard-code 这条 flag** — 不能让 build
     命令靠记忆，build_*.py 必须显式 `cargo build --release
     --features tauri/custom-protocol`。下一轮 fix 把这加到
     R268c commit message + 注释。

223. **GUI 也能从 console 看 stderr** — 用 Start-Process 启动
     GUI 子进程时，子进程的 stderr 会被父进程（PowerShell）
     捕获。eprintln!() / dbg!() 都能看，不用 AttachConsole
     API 或重定向文件。

224. **chromium remote debugging 9222 是 GUI 黑盒的 X 光机** —
     启 `devtools: true` + `additionalBrowserArgs: --remote-debugging-port=9222`
     之后，curl `localhost:9222/json/list` 直接看 WebView2
     在加载什么 URL、报什么错。比看 eprintln 更直接。
     下次 GUI 出问题第一时间查这里。

225. **ERR_CONNECTION_REFUSED on localhost 是 "app config 错了"
     不是 "网络坏了"** — 看到 localhost 拒绝连接 + 没运行
     dev server，先怀疑 app 自己配错了 dev URL (Tauri 没
     嵌入 assets) 或 service 没启动。

## 用户下一步

1. 下载新的 `release/aethercode-0.2.70.zip` (108 MB)
2. SHA = `5F0E6CB0224291B59B9DC0BE34FAFAE02FFE5DD6`
3. 解压到新目录（覆盖旧的）
4. 跑 `desktop/aethercode-desktop.exe`
5. UI 应该能正常打开，看到 prompt 输入框
6. 发个简单 query 测试 tool 调用