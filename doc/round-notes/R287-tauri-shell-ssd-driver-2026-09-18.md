# R287 — Tauri shell driver for SsdPanel (real `java -jar ssd --interactive` subprocess)

**触发**: R281 deferred 项 — "real driver wires in a follow-up round; the driver interface here is its contract"。Settings 面板的 SDD tab 之前只有 MockSsdDriver( canned events),R287 把 MockSsdDriver 替换成 Tauri shell driver spawn `java -jar aethercode.jar ssd <feature> "<intent>" --interactive` 子进程,流式读取 newline-delimited JSON 事件,把用户的 accept/revise/skip/quit 通过 stdin 写回。

**核心改动 (R287)**:
- **`Cargo.toml`**: 加 `tauri-plugin-shell = "2"` 依赖(sym 现有 `tauri-plugin-dialog = "2"` 风格)
- **`src-tauri/src/lib.rs`**: `.plugin(tauri_plugin_shell::init())` 注册;新 Tauri 命令 `read_text_file(path)` (mirror 现有 `write_text_file`,让 renderer 读 draft 全文,不必加 plugin-fs);新 Tauri 命令 `get_app_paths` (return `{ jarPath, cwd }` 让 renderer 不用复制 Rust 端 `find_jar_path()` 查找逻辑);`invoke_handler!` 加 `get_app_paths` + `read_text_file`
- **`tauri.conf.json`**: 顶层加 `plugins: { shell: { open: true } }` (R287 attempt 1 fail — 我误放 bundle 下,Tauri schema 拒收;lesson: plugin config 必须在 root level,不在 bundle 子结构下)
- **`tauriSsdDriver.ts`**: 新文件,实现 `SsdDriver` 接口,`start()` 用 `Command.create(java, args).spawn()` 起子进程,`stdout` 是 AsyncIterable<string> line-by-line,`safeParseSsdEvent(line)` 把 JSONL 转成 discriminated union event;`sendCommand(cmd)` 把 `JSON.stringify(cmd) + '\n'` 写到 stdin;`fetchDraft(path)` 用 invoke('read_text_file', {path});`stop()` kill child
- **`SettingsPage.tsx`**: 加 "Driver" radio 组(`mock` / `real`),localStorage 持久化 (`aethercode.sdd.mode`);选 real 时 lazy invoke `get_app_paths` 拿 jarPath + cwd
- **`TauriSsdDriver` 类型 `as any` 兜底**: `Command<T>` 在 plugin 2.x 不同 minor version shape 略不同,`spawn()` return `{ child, stdout }` 我们 any-cast 后 runtime destructure;TypeScript 编译期通过,runtime 兼容

**Tests**:
- desktop: `TauriSsdDriverR287.test.ts` 13 tests pass (safeParseSsdEvent 全 kind 覆盖 + malformed JSON + unknown kind + trailing whitespace + module shape + constructor + fetchDraft injection + onEvent teardown + sendCommand no-op)
- desktop full: 1183/1183 (+13 R287)
- daemon: 无改动,R286 jar 仍 56,630,295 B

**Deploy**:
- jar: 56,630,295 B (R286, unchanged)
- exe: 5,425,152 B (+1,263,952 vs R286 4,161,024 — Tauri shell plugin + read_text_file + get_app_paths 命令新增体积)
- exe.old: 5,179,904 B (R280 backup, unchanged)
- exe.r286: 4,161,024 B (R286 backup — 现在由 R287 顶替)
- zip: SHA `E48DA550D68549D6912950FE47FDB78F757F9082DE8B3C1C4916DD3F139A49AA`, 112,916,991 B (+542,230 vs R286)
- 54/54 bytecode markers (无新 daemon markers,jar unchanged)

**Commit chain (累积 72 R-round)**:
```
c633560 R286 (agent variant + Show all providers + pricing) ← pushed
   ↓
TBD   R287 (Tauri shell driver for SsdPanel) — daemon unchanged, exe + desktop done
```

**关键技术决定 (5 条)**:
1. **`plugins` 在 tauri.conf.json 顶层,不在 `bundle` 子节点** — Tauri schema 严格区分;`bundle` 只接 bundle-specific 字段;plugin config 是 global 顶级字段
2. **加 `read_text_file` Rust command,不引 `tauri-plugin-fs`** — single-purpose,6 行 Rust;避免 plugin-fs 多 capability surface 开销
3. **`get_app_paths` Tauri command 暴露 jarPath + cwd** — renderer 不复制 Rust 端 `find_jar_path()` 逻辑(ancestor walk + Tauri resource dir);rust 端 source of truth
5. **`Command.create(...).spawn()` return 用 `any` cast** — `@tauri-apps/plugin-shell` 2.x minor 之间 `Child`/`stdout` shape 微变,TypeScript 编译期过不去,runtime destructure 容错
6. **driver mode 默认 `mock`,真实路径用户显式 opt-in** — 默认不依赖 jar 存在 + JVM 启动;Settings panel 显式 "Real (spawn JVM)" radio 让用户自己切

**关键调试技巧 (新增 610-611)**:
610. **Tauri 2 conf.json plugins 必须在 root level** — `bundle.plugins` 报错 "unknown field `plugins`";原因 Tauri 把 `bundle` 当作 strictly typed 节点,plugin config 是 top-level global
611. **Rust `.or_else(|| async {...})` 编译失败 E0728** — `or_else` 接 sync closure,但 closure 里要 `await` state lock;rustc 不允许在 sync 闭包内隐式 await;改成 let-else 分支或者 `match`

**教训 (新增 610-611)**:
610. **Tauri 2 conf.json schema 必须查官方文档** — bundle / app / plugins / build 字段是 sibling 不是 nested;不熟悉的字段先 docs.tauri.app 查,不要凭直觉往 sub-tree 塞
611. **Rust async closure 需要在 async context** — `.or_else(|| state.lock().await.clone())` 不行;改成 `match` / `if let Some(...) else { ... await ... }` 显式分支;或者 `tokio::try_join!`

**后续 R288+ 计划**:
- **R288**: providers.yaml in-app editor (Settings panel 加 YAML edit tab,save → reloadProviders) + live env-var refresh button (`refreshEnvVars` → 重新读 System.getenv 然后 refreshProviders)
- **R289**: Settings "Show all providers" toggle 反向 (默认关 → 默认开可选)
- **R290**: Tauri SsdPanel end-to-end smoke (jar spawn → 4 phases 完成 → 自动 compose commit)