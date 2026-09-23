// AetherCode Desktop — Tauri 2 backend.
//
// The renderer NEVER opens a WebSocket directly (WebView2's secure-context
// rules + cross-port WS from http://localhost:1420 to ws://localhost:17888
// is brittle). Instead:
//
//   renderer --Tauri IPC-->  ensure_daemon  -->  spawn java -jar ... --http-port N
//   renderer --Tauri IPC-->  rpc_call      -->  Rust WebSocket client
//   Rust  <--Tauri event---  ws-notify     <--  daemon JSON-RPC notifications
//
// the bank surface is read directly from Rust over HTTP
// (not via the daemon's WebSocket bridge). The bank client lives
// in `bank_client` and is held in AppState so the renderer can
// `invoke('bank_stats')` / `invoke('bank_recall', { kind, n })`.

pub mod bank_client;

// R287: AppPaths is the wire shape returned by
// the `get_app_paths` Tauri command. The
// renderer's TauriSsdDriver uses it to source
// the jar path + cwd when spawning the
// `java -jar … ssd … --interactive` subprocess
// without duplicating the Rust-side lookup
// logic. Kept in its own file so a future
// round can extend the shape (e.g. add the
// resolved java executable) without bloating
// lib.rs.
pub mod java_com_aethercode_app_paths {
    use serde::Serialize;
    #[derive(Debug, Serialize, Clone)]
    #[serde(rename_all = "camelCase")]
    pub struct AppPaths {
        pub jar_path: String,
        pub cwd: String,
    }
}

use bank_client::{BankClient, BankStats, BankUnit};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

use futures_util::{SinkExt, StreamExt};
use once_cell::sync::Lazy;
use serde::Serialize;
use tauri::{AppHandle, Emitter, Manager, State};
use tokio::sync::{mpsc, oneshot, Mutex as TokioMutex};
use tokio_tungstenite::tungstenite::Message as WsMessage;
use tokio_tungstenite::connect_async;

const DAEMON_HEALTH_TIMEOUT_MS: u64 = 15_000;
const DAEMON_HEALTH_POLL_MS: u64 = 200;
const DEFAULT_DAEMON_PORTS: &[u16] = &[7777, 7778, 17888];
const DESKTOP_DAEMON_PORTS: &[u16] = &[17888, 18888, 19888, 20888, 21888, 22888];

// R333: union of every port range any of our daemon
// incarnations can listen on. Used by `kill_orphan_daemons`
// to enforce "at most one AetherCode daemon alive at any
// time" — the user explicitly asked for this constraint
// because seeing two daemon processes (one primary, one
// pre-warm, one orphan-from-previous-session) eat 2-3 GB
// of native memory each is operationally confusing and
// wastes RAM.
//
// We include both DESKTOP_DAEMON_PORTS (xxx88 — the
// primary range) AND PRE_WARM_PORTS (xxx89 — the
// pre-warm daemon range). The desktop spawns primaries
// via `ensure_daemon`; pre-warm daemons are spawned via
// `pre_warm_daemon`. Both are JVMs serving our chat
// engine, and the user wants exactly one of them alive.
const ALL_DESKTOP_DAEMON_PORTS: &[u16] = &[
    17888, 18888, 19888, 20888, 21888, 22888,  // DESKTOP primary range
    18889, 19889, 20889, 21889, 22889, 23889,  // PRE_WARM range
];

#[derive(Debug, Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct DaemonInfo {
    pub port: u16,
    pub ws_url: String,
    pub http_url: String,
    pub spawned: bool,
    pub jar_path: String,
    pub cwd: String,
}

impl DaemonInfo {
    fn from_port(port: u16, spawned: bool, jar: &Path, cwd: &Path) -> Self {
        DaemonInfo {
            port,
            ws_url: format!("ws://127.0.0.1:{}/ws", port),
            http_url: format!("http://127.0.0.1:{}", port),
            spawned,
            jar_path: jar.to_string_lossy().to_string(),
            cwd: cwd.to_string_lossy().to_string(),
        }
    }
}

struct RpcRequest {
    method: String,
    params: serde_json::Value,
    reply: oneshot::Sender<Result<serde_json::Value, String>>,
}

struct AppState {
    ws_tx: TokioMutex<Option<mpsc::UnboundedSender<RpcRequest>>>,
    daemon: TokioMutex<Option<DaemonInfo>>,
    cwd: TokioMutex<Option<PathBuf>>,
    daemon_handle: TokioMutex<Option<std::process::Child>>,
    /// R82+ Issue 3: a second daemon kept warm in a sibling cwd
    /// so a `setCwd` to that sibling is sub-second (no JVM
    /// startup). The pre-warm is independent of the active
    /// primary: it has its own port, its own child process, and
    /// its own WS connection. On `swap_to_pre_warm`, the Rust
    /// side kills the primary, copies the pre-warm's DaemonInfo
    /// into the primary slot, and re-opens the WS — all without
    /// spawning a new JVM.
    pre_warm: TokioMutex<Option<PreWarmSlot>>,
    /// R250b: a single shared BankClient. Constructed lazily
    /// from the daemon's http_url when the daemon is up. Lets
    /// the renderer call `invoke('bank_stats')` / `'bank_recall'`
    /// without routing through the WebSocket JSON-RPC bridge.
    bank_client: TokioMutex<Option<BankClient>>,
    /// prior round: cwd persisted to `~/.aethercode/desktop-state.json`.
    /// Rust is the source of truth for "what project did the
    /// user last open?" — the renderer no longer reads it from
    /// localStorage. On App start, we read this file in
    /// `Default::default()`; if it's missing, the App shows the
    /// first-launch folder picker (no auto-spawn with
    /// `current_dir`). The renderer updates it via `set_cwd`,
    /// which both updates the in-memory slot and rewrites the
    /// file so a restart restores the user's last project.
    persisted_cwd: TokioMutex<Option<PathBuf>>,
    /// R201: set to `true` for the duration of a
    /// `set_cwd_daemon` swap. The WS task consults this flag
    /// before emitting `daemon.disconnected` — the OLD
    /// daemon's WS closing during a swap is expected, not a
    /// reconnect trigger. Without this guard, every cwd
    /// switch would fire a `disconnect` notification that the
    /// store's reconnect handler turns into a fresh
    /// `ensure_daemon`, killing the brand-new primary's
    /// `ws_tx` and re-establishing a connection that has
    /// already been opened. The user reported "every time I
    /// switch cwd, the daemon reconnects again" — the visible reconnect
    /// spinner was the symptom; the cause was the spurious
    /// disconnect.
    swapping: Arc<AtomicBool>,
}

struct PreWarmSlot {
    info: DaemonInfo,
    handle: Option<std::process::Child>,
}

impl Default for AppState {
    fn default() -> Self {
        AppState {
            ws_tx: TokioMutex::new(None),
            daemon: TokioMutex::new(None),
            cwd: TokioMutex::new(None),
            daemon_handle: TokioMutex::new(None),
            pre_warm: TokioMutex::new(None),
            bank_client: TokioMutex::new(None),
            // load the persisted cwd on App start. This
            // restores the user's "last project" without the
            // renderer needing to manage persistence. The file
            // is read once on startup; set_cwd rewrites it.
            persisted_cwd: TokioMutex::new(load_persisted_cwd()),
            // no swap in progress at startup.
            swapping: Arc::new(AtomicBool::new(false)),
        }
    }
}

static HANDLE: Lazy<TokioMutex<Option<DaemonInfo>>> = Lazy::new(|| TokioMutex::new(None));

/// R201: tiny RAII guard that flips an `AtomicBool` back to
/// `false` on drop. Used by `set_cwd_daemon` to release the
/// swap flag when the function returns (normal or error
/// path). The actual flag lives on `AppState.swapping` and
/// is checked by the WS task before emitting
/// `daemon.disconnected`; this guard's only job is the
/// "release on drop" semantics so the flag is always reset
/// even if a swap error panics the function.
struct ScopingGuard {
    flag: Arc<AtomicBool>,
}
impl Drop for ScopingGuard {
    fn drop(&mut self) {
        self.flag.store(false, Ordering::Release);
        eprintln!("[R201] swap guard released");
    }
}

#[tauri::command]
async fn ensure_daemon(
    app: AppHandle,
    state: State<'_, AppState>,
) -> Result<DaemonInfo, String> {
    eprintln!("[R81] ensure_daemon: start");
    let mut daemon_guard = state.daemon.lock().await;
    let mut ws_guard = state.ws_tx.lock().await;

    if let Some(info) = daemon_guard.as_ref() {
        if is_healthy(info.port).await && ws_guard.is_some() {
            return Ok(info.clone());
        }
    }

    for &port in DEFAULT_DAEMON_PORTS {
        if is_healthy(port).await {
            // R298 follow-up. Pre-R298 path set
            // `jar_path = "<external>"` and `cwd = ""` here,
            // which left the renderer with an empty
            // `daemonInfo.jarPath` and `cwd` — the renderer's
            // `SddPhaseBar` then dropped into the MockSsdDriver
            // branch and the SDD subprocess never got spawned.
            // Now: even when attaching to an externally-managed
            // daemon, populate the resolved jar (so the spawner
            // knows what to reuse) and the resolved cwd (so the
            // SDD pipeline writes artefacts under the right
            // directory). `spawned = false` flags that this
            // App didn't launch the JVM — it's still safe to
            // talk to it. We use the App's resolved cwd, not the
            // daemon's internal cwd, because the desktop is
            // the one driving the user's project and the
            // attach path is for "I started and you happen to
            // be alive on a default port already".
            let resolved_jar = find_jar_path(&app)
                .map(|p| p.to_string_lossy().to_string())
                .unwrap_or_else(|_| "<unknown>".to_string());
            let cwd_for_info = {
                let explicit = state.cwd.lock().await.clone();
                if let Some(c) = explicit { c }
                else {
                    state.persisted_cwd.lock().await.clone()
                        .unwrap_or_else(|| PathBuf::from(""))
                }
            };
            let info = DaemonInfo::from_port(
                port, false,
                Path::new(&resolved_jar),
                &cwd_for_info,
            );
            let tx = open_ws(&info.ws_url, app.clone(), state.swapping.clone()).await?;
            *ws_guard = Some(tx);
            *daemon_guard = Some(info.clone());
            *HANDLE.lock().await = Some(info.clone());
            // R298: log the attach so the user can verify the
            // App attached to a known port AND has a real jar
            // path to spawn SDD from.
            if let Ok(mut f) = std::fs::OpenOptions::new()
                .create(true).append(true).open(
                    std::env::temp_dir().join("aethercode-desktop-daemon-info.log"))
            {
                use std::io::Write;
                let _ = writeln!(f,
                    "[R298-attach] external daemon on port {}: jar={} cwd={}",
                    port, resolved_jar, cwd_for_info.display());
            }
            return Ok(info);
        }
    }

    let jar = find_jar_path(&app).map_err(|e| {
        eprintln!("[R81] jar not found: {}", e);
        format!(
            "Could not locate aethercode jar: {}. Drop it in resources/ or \
             next to the executable.",
            e
        )
    })?;
    eprintln!("[R81] jar: {}", jar.display());
    // R298 follow-up: log the resolved jar + cwd into the
    // same rolling log the renderer uses, so a packaged App
    // that reports "MockSsdDriver (no jar)" can be diagnosed
    // without a debugger. Renders a clean diff between
    // "desktop never found the jar" and "desktop found the
    // jar but the renderer never received it".
    if let Ok(mut f) = std::fs::OpenOptions::new()
        .create(true).append(true).open(
            std::env::temp_dir().join("aethercode-desktop-daemon-info.log"))
    {
        use std::io::Write;
        let _ = writeln!(f, "[R298-ensure] jar={} (portable)", jar.display());
    }
    eprintln!(
        "[R172] daemon JVM args: {:?}{}",
        daemon_jvm_args(),
        if std::env::var("AETHERCODE_DAEMON_JVM_OPTS").is_ok() {
            " (override via AETHERCODE_DAEMON_JVM_OPTS)"
        } else {
            ""
        }
    );

    // pick cwd in the new self-contained order —
    //   1. explicit in-memory override (state.cwd)
    //   2. persisted last-project from ~/.aethercode/desktop-state.json
    //   3. NEEDS_CWD error (no auto-spawn with current_dir)
    //
    // Previously the code fell through to `std::env::current_dir()`
    // which made the App launch a daemon on whatever directory
    // the user happened to be in when they double-clicked the
    // .exe. That defeated the first-launch folder picker —
    // by the time the renderer called `getState`, the daemon
    // had auto-created a default session and the Welcome tile
    // flipped to "新会话" (New Session) before the user could see
    // "打开文件夹" (Open folder). Now the App shows a "no project" state and
    // asks the user to pick a folder before any daemon exists.
    let cwd = if let Some(c) = state.cwd.lock().await.clone() {
        eprintln!("[prior round] cwd: explicit in-memory override {}", c.display());
        c
    } else if let Some(c) = state.persisted_cwd.lock().await.clone() {
        eprintln!("[prior round] cwd: persisted last-project {}", c.display());
        c
    } else {
        eprintln!("[prior round] cwd: no project — need user to pick one");
        return Err("NEEDS_CWD".to_string());
    };
    // R298: log the resolved cwd to %TEMP% so the renderer's
    // "MockSsdDriver (no jar)" symptom can be diagnosed.
    // eprintln goes to a console that the App never opens on
    // Windows GUI mode.
    if let Ok(mut f) = std::fs::OpenOptions::new()
        .create(true).append(true).open(
            std::env::temp_dir().join("aethercode-desktop-find-jar.log"))
    {
        use std::io::Write;
        let _ = writeln!(f, "[R298] ensure_daemon resolved cwd = {}", cwd.display());
    }

    let java = find_java().ok_or_else(|| "java executable not found on PATH".to_string())?;

    let (info, child) = spawn_daemon(&app, &jar, &java, &cwd, DESKTOP_DAEMON_PORTS).await?;
    *state.daemon_handle.lock().await = Some(child);
    let tx = open_ws(&info.ws_url, app.clone(), state.swapping.clone()).await?;
    *ws_guard = Some(tx);
    *daemon_guard = Some(info.clone());
    *HANDLE.lock().await = Some(info.clone());
    // build a BankClient from the daemon's http_url so
    // `bank_stats` / `bank_recall` Tauri commands can serve the
    // renderer without routing through the WebSocket JSON-RPC
    // bridge. Mirrors R244.3 TS client and the JVM BankClient
    // in aethercode-deepagents.
    *state.bank_client.lock().await = Some(build_bank_client(&info));
    eprintln!("[R81] SUCCESS: {} (ws={})", info.http_url, info.ws_url);
    Ok(info)
}

/// R82+ Issue 3: shared spawn helper used by both `ensure_daemon`
/// and `pre_warm_daemon`. Tries each port in `ports` until one
/// spawns a healthy daemon for the given cwd. Returns the
/// spawned `DaemonInfo` + `Child` handle on success.
///
/// Critically, this does NOT touch any of the `AppState` slots
/// (ws_tx, daemon, daemon_handle) — callers decide where the
/// result lands. `ensure_daemon` writes to the primary slots;
/// `pre_warm_daemon` writes to the pre-warm slot.
async fn spawn_daemon(
    _app: &AppHandle,
    jar: &Path,
    java: &str,
    cwd: &Path,
    ports: &[u16],
) -> Result<(DaemonInfo, std::process::Child), String> {
    let mut last_err = String::new();
    // R333: enforce "at most one AetherCode daemon alive".
    // Sweep every desktop-owned port and kill any java.exe
    // that's holding one before we attempt to bind a new
    // primary. This covers:
    //   1. orphans from a previous desktop session that was
    //      hard-killed (process gone but JVM kept running)
    //   2. pre-warm daemons the previous session left alive
    //   3. user-launched daemons (e.g. `start-daemon.bat`)
    //      that the user explicitly wants killed when the
    //      desktop takes over
    //
    // We log every kill so the user can correlate
    // "disappearing daemons" with "desktop restarted". The
    // log line includes the port so the user can identify
    // which daemon (which session-dir) just got reaped.
    eprintln!("[R333] pre-bind sweep: killing any java daemon on ports {:?}",
        ALL_DESKTOP_DAEMON_PORTS);
    kill_orphan_daemons();
    for &port in ports {
        // R83 debug: capture daemon stdout/stderr to a per-port log
        // file in %TEMP% so we can diagnose why stream_event
        // notifications never reach the renderer. This is a
        // temporary wire-up to find the missing broadcast.
        let log_path = std::env::temp_dir()
            .join(format!("aethercode-daemon-port{}.log", port));
        // Write a fresh banner so we can correlate daemon restarts
        // with log lines (a real production build will revert to
        // Stdio::null once R83 stabilises).
        if let Ok(mut f) = std::fs::OpenOptions::new()
            .create(true)
            .write(true)
            .truncate(true)
            .open(&log_path)
        {
            use std::io::Write;
            let _ = writeln!(f, "===== prior round daemon spawn port={} pid=???? jar={} cwd={} =====",
                port, jar.display(), cwd.display());
        }
        let (stdout_io, stderr_io) = match std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(&log_path)
        {
            Ok(f) => {
                let so = match f.try_clone() {
                    Ok(c) => Stdio::from(c),
                    Err(_) => Stdio::null(),
                };
                let se = Stdio::from(f);
                (so, se)
            }
            Err(_) => (Stdio::null(), Stdio::null()),
        };
        // hide the JVM's child console window on
        // Windows. Without `CREATE_NO_WINDOW` the JVM
        // pops a `cmd.exe` window alongside the App, and
        // the pre-warm daemon pops a second one. The user
        // sees two black DOS boxes fighting for attention
        // with the App they're trying to use. The flag is
        // a Windows-only `creation_flags` value:
        //   0x08000000 = CREATE_NO_WINDOW
        // On non-Windows this is a no-op (the attribute
        // variant is gated on `cfg(windows)`).
        #[cfg(windows)]
        let mut cmd = {
            use std::os::windows::process::CommandExt;
            let mut c = Command::new(java);
            c.creation_flags(0x08000000);
            c
        };
        #[cfg(not(windows))]
        let mut cmd = Command::new(java);

        // R172 daemon-stability: pass explicit JVM args
        // before -jar. The default JVM heap on a 8-16 GB
        // dev machine is 1-2 GB, which is enough for a
        // trivial query but gets GC-starved on real work
        // (e.g. "generate a Maven project with 5 sort
        // algorithms" → the engine materialises several
        // tool result blobs, the chat client buffers the
        // streaming response, and the heap inflates past
        // 1 GB). When that happens the JVM spends most
        // of its time in full GC, the WS stops emitting
        // chunks, and the desktop's `STREAM_STALE_MS`
        // (30 s) trips — the user sees "[Stream stale]
        // Daemon stopped responding for 30s" mid-task
        // even though the daemon is still alive, just
        // stuck in GC. Pinning a 4 GB heap is enough for
        // every task we've shipped so far without
        // OOM-ing a 16 GB dev box.
        //
        // The env-var override `AETHERCODE_DAEMON_JVM_OPTS`
        // lets power users append / replace these args
        // (e.g. `set AETHERCODE_DAEMON_JVM_OPTS=-Xmx8g
        // -XX:+UseZGC` for a heavier model run). When
        // the env var is set, the defaults below are
        // skipped entirely so an operator has full
        // control; when unset, the defaults below are
        // applied in order, then -jar, then --http-port.
        let extra_jvm_args = daemon_jvm_args();
        for a in &extra_jvm_args {
            cmd.arg(a);
        }

        match cmd
            .arg("-jar")
            .arg(jar)
            .arg("--http-port")
            .arg(port.to_string())
            // pass --sessions-dir so the daemon wires its
            // SessionStore. Without this the new listSessions /
            // createSession / loadSession / deleteSession RPCs
            // return ENGINE_ERROR (gracefully, but the desktop
            // multi-session stack falls back to localStorage
            // instead of using real disk persistence). The path
            // is `<cwd>/.aethercode/sessions`; the daemon creates
            // the directory on first use.
            .arg("--sessions-dir")
            .arg(cwd.join(".aethercode").join("sessions").to_string_lossy().to_string())
            .current_dir(cwd)
            .stdout(stdout_io)
            .stderr(stderr_io)
            .spawn()
        {
            Ok(mut child) => {
                tokio::time::sleep(Duration::from_millis(300)).await;
                if let Ok(Some(status)) = child.try_wait() {
                    last_err = format!("port {}: exited immediately with {:?}", port, status);
                    continue;
                }
                let deadline = Instant::now() + Duration::from_millis(DAEMON_HEALTH_TIMEOUT_MS);
                let mut ok = false;
                while Instant::now() < deadline {
                    if is_healthy(port).await { ok = true; break; }
                    if let Ok(Some(status)) = child.try_wait() {
                        last_err = format!("port {}: died mid-startup with {:?}", port, status);
                        break;
                    }
                    tokio::time::sleep(Duration::from_millis(DAEMON_HEALTH_POLL_MS)).await;
                }
                if !ok {
                    let _ = child.kill();
                    if last_err.is_empty() { last_err = format!("port {}: never came up", port); }
                    continue;
                }
                return Ok((DaemonInfo::from_port(port, true, jar, cwd), child));
            }
            Err(e) => {
                last_err = format!("port {}: spawn failed: {}", port, e);
                continue;
            }
        }
    }
    Err(format!("Failed to spawn daemon: {}", last_err))
}

/// R328: kill any orphan `java` process holding the given
/// TCP port. Windows-only — Linux uses SO_REUSEPORT and
/// the JVM doesn't need this dance. We match by PID and
/// R333: kill any AetherCode daemon (java.exe) holding a
/// port in `ALL_DESKTOP_DAEMON_PORTS`. Used by
/// `spawn_daemon()` as the pre-bind sweep so the user never
/// sees "primary daemon + orphan from a previous desktop
/// session" running side-by-side. The previous round
/// (R328) only swept the specific port the primary was
/// about to bind, leaving any other daemon alive.
///
/// We also kill the "current" port (the one we're about to
/// bind), because in the common "desktop was killed, user
/// restarts" case, the previous instance is still holding
/// it. R328's per-port sweep covered this; R333 widens the
/// sweep to all primary-owned ports.
#[cfg(windows)]
fn kill_orphan_daemons() {
    for &port in ALL_DESKTOP_DAEMON_PORTS {
        kill_orphan_on_port(port);
    }
}

/// process name (java) to avoid killing unrelated
/// processes that might happen to bind a port.
#[cfg(windows)]
fn kill_orphan_on_port(port: u16) {
    use std::process::Command;
    // netstat -ano | findstr :PORT -> "  TCP    0.0.0.0:PORT    ... PID"
    let out = Command::new("netstat")
        .args(["-ano", "-p", "tcp"])
        .output();
    let stdout = match out {
        Ok(o) => String::from_utf8_lossy(&o.stdout).into_owned(),
        Err(_) => return,
    };
    let needle = format!(":{}", port);
    let mut pids: Vec<u32> = Vec::new();
    for line in stdout.lines() {
        // Match lines ending in ":<port>" — `findstr` doesn't
        // give us a clean tab-separated table on every locale,
        // so we anchor on the port token at end-of-address.
        let trimmed = line.trim();
        if !trimmed.contains(&needle.as_str()) { continue; }
        // Last whitespace-separated field.
        if let Some(pid_str) = trimmed.split_whitespace().last() {
            if let Ok(pid) = pid_str.parse::<u32>() {
                if pid > 0 { pids.push(pid); }
            }
        }
    }
    for pid in pids {
        // Filter to java.exe only — defensive against killing
        // a process that happens to bind the same port.
        let name = Command::new("tasklist")
            .args(["/FI", &format!("PID eq {}", pid), "/NH"])
            .output()
            .ok()
            .map(|o| String::from_utf8_lossy(&o.stdout).into_owned())
            .unwrap_or_default();
        if name.to_lowercase().contains("java") {
            eprintln!("[R328] killing orphan java pid={} holding port {}", pid, port);
            let _ = Command::new("taskkill")
                .args(["/F", "/PID", &pid.to_string()])
                .output();
            // Brief pause to release the socket.
            std::thread::sleep(std::time::Duration::from_millis(200));
        }
    }
}

#[cfg(not(windows))]
fn kill_orphan_on_port(_port: u16) {
    // no-op on non-Windows
}

/// R82+ Issue 3: pre-warm a daemon in a sibling cwd so the next
/// `setCwd` to that directory is sub-second. The pre-warm
/// daemon is fully independent of the primary — its own port,
/// its own JVM, its own state. We do NOT open a WS to it from
/// here; the WS is only opened on swap (which is rare).
///
/// If a pre-warm already exists for the requested cwd, this is
/// a no-op (returns the existing info). If a pre-warm exists
/// for a DIFFERENT cwd, the old one is killed first.
///
/// Uses a separate port range (PRE_WARM_PORTS) so the primary
/// and pre-warm never collide even if both are in the middle
/// of (re)starting. The set is shifted up to avoid clashing
/// with `DESKTOP_DAEMON_PORTS`.
const PRE_WARM_PORTS: &[u16] = &[18889, 19889, 20889, 21889, 22889, 23889];

#[tauri::command]
async fn pre_warm_daemon(
    path: String,
    app: AppHandle,
    state: State<'_, AppState>,
) -> Result<DaemonInfo, String> {
    let new_cwd = PathBuf::from(&path);
    if !new_cwd.is_dir() {
        return Err(format!("Not a directory: {}", path));
    }
    eprintln!("[R82+] pre_warm_daemon: {}", path);

    // No-op if a pre-warm for this exact cwd already exists.
    {
        let pw = state.pre_warm.lock().await;
        if let Some(slot) = pw.as_ref() {
            if slot.info.cwd.replace('\\', "/").trim_end_matches('/').to_lowercase()
                == path.replace('\\', "/").trim_end_matches('/').to_lowercase()
            {
                eprintln!("[R82+] pre_warm already warm for {}", path);
                return Ok(slot.info.clone());
            }
        }
    }

    // Replace any existing pre-warm.
    {
        let mut pw = state.pre_warm.lock().await;
        if let Some(mut slot) = pw.take() {
            if let Some(mut child) = slot.handle.take() {
                let _ = child.kill();
                eprintln!("[R82+] killed stale pre-warm on port {}", slot.info.port);
            }
        }
    }

    let jar = find_jar_path(&app).map_err(|e| format!("jar not found: {}", e))?;
    let java = find_java().ok_or_else(|| "java executable not found on PATH".to_string())?;

    let (info, child) = spawn_daemon(&app, &jar, &java, &new_cwd, PRE_WARM_PORTS).await?;
    eprintln!("[R82+] pre-warm ready: {} (cwd={})", info.http_url, info.cwd);

    *state.pre_warm.lock().await = Some(PreWarmSlot { info: info.clone(), handle: Some(child) });
    Ok(info)
}

/// R82+ Issue 3: atomically promote the pre-warm daemon to
/// primary. Kills the current primary JVM, copies the
/// pre-warm's DaemonInfo into the primary slots, re-opens the
/// WS, and clears the pre-warm slot. The renderer's subsequent
/// `initialize()` call will short-circuit on `is_healthy &&
/// ws_guard.is_some()`.
///
/// Returns the new primary's `DaemonInfo`.
#[tauri::command]
async fn swap_to_pre_warm(
    app: AppHandle,
    state: State<'_, AppState>,
) -> Result<DaemonInfo, String> {
    eprintln!("[R82+] swap_to_pre_warm");

    // Take the pre-warm slot first (so we own it).
    let slot = {
        let mut pw = state.pre_warm.lock().await;
        pw.take()
    }.ok_or_else(|| "no pre-warm daemon available".to_string())?;

    // Kill the current primary.
    {
        let mut h = state.daemon_handle.lock().await;
        if let Some(mut child) = h.take() {
            let _ = child.kill();
        }
    }
    // Drop the dead WS sender (the open_ws task will see the
    // closed socket and exit, which fires a daemon.disconnected
    // notification — but the store is already in 'reconnecting'
    // because the renderer set that state BEFORE calling us,
    // so the notification handler is a no-op).
    {
        let mut w = state.ws_tx.lock().await;
        *w = None;
    }
    {
        let mut d = state.daemon.lock().await;
        *d = None;
    }

    // Promote the pre-warm to primary.
    let info = slot.info;
    let handle = slot.handle; // may be None if pre-warm daemon exited
    let tx = open_ws(&info.ws_url, app.clone(), state.swapping.clone()).await?;
    *state.ws_tx.lock().await = Some(tx);
    *state.daemon.lock().await = Some(info.clone());
    *state.cwd.lock().await = Some(PathBuf::from(&info.cwd));
    *state.daemon_handle.lock().await = handle;
    *HANDLE.lock().await = Some(info.clone());
    // refresh the bank client too — the new daemon
    // listens on a different port. The old client (if any)
    // is replaced wholesale; this is the same lifecycle as
    // `state.daemon`.
    *state.bank_client.lock().await = Some(build_bank_client(&info));
    eprintln!("[R82+] swap complete: {} (cwd={})", info.http_url, info.cwd);
    Ok(info)
}

/// R82+ Issue 3: drop the pre-warm daemon. No-op if none.
#[tauri::command]
async fn discard_pre_warm(state: State<'_, AppState>) -> Result<(), String> {
    let mut pw = state.pre_warm.lock().await;
    if let Some(mut slot) = pw.take() {
        if let Some(mut child) = slot.handle.take() {
            let _ = child.kill();
        }
        eprintln!("[R82+] discarded pre-warm on port {}", slot.info.port);
    }
    Ok(())
}

#[tauri::command]
async fn set_cwd(
    path: String,
    app: AppHandle,
    state: State<'_, AppState>,
) -> Result<serde_json::Value, String> {
    // this command used to call `createSession` on the
    // EXISTING primary daemon. That worked when one project
    // = one daemon, but in the multi-daemon world each daemon
    // has a fixed --sessions-dir of <cwd>/.aethercode/sessions.
    // Calling `createSession` on the wrong daemon (e.g. the
    // abc_1 daemon) wrote the new transcript to
    // abc_1/.aethercode/sessions/<id>.jsonl and bound the
    // engine's cwd to abc_2 — but the engine *process* was
    // still the abc_1 JVM, so model state (loaded files,
    // tool history) was from abc_1, and the next file_read
    // / bash ran with relative paths from abc_1. The user
    // saw "I picked abc_2 but it operated on abc_1".
    //
    // The fix is structural: switching cwd must switch the
    // daemon too. We delegate to `set_cwd_daemon` which
    // does the full pre-warm → swap → createSession dance
    // and returns the new sessionId so the renderer can
    // switch its local view.
    set_cwd_daemon(path, app, state).await
}

/// R199: switching cwd must switch the underlying daemon,
/// not just mint a session on the current one. Each daemon
/// has a fixed --sessions-dir of <cwd>/.aethercode/sessions
/// (see `spawn_daemon` above), so the only way to get a
/// daemon that writes transcripts to the right directory
/// and runs the engine with the right cwd is to spawn a
/// fresh daemon rooted at the new cwd and promote it to
/// primary.
///
/// Flow:
///   1. pre_warm_daemon(newCwd) — spawn a fresh JVM in
///      newCwd, wait for /health to come up. Returns the
///      pre-warm slot's DaemonInfo (port, ws_url, cwd).
///   2. swap_to_pre_warm() — kill the current primary,
///      promote the pre-warm to primary, re-open the WS.
///   3. RPC createSession({cwd: newCwd}) on the new
///      primary — gives the engine a clean session id +
///      transcript rooted at newCwd, with no leakage from
///      the old session.
///
/// Persistence: desktop-state.json is written *before* any
/// of the swap work, so a crash mid-flow leaves the file
/// consistent with what the user picked. The next App
/// launch reads the file and spawns a daemon for the
/// picked cwd directly.
async fn set_cwd_daemon(
    path: String,
    app: AppHandle,
    state: State<'_, AppState>,
) -> Result<serde_json::Value, String> {
    eprintln!("[R199] set_cwd_daemon: {}", path);
    let new_cwd = PathBuf::from(&path);
    if !new_cwd.is_dir() {
        return Err(format!("Not a directory: {}", path));
    }
    // persist BEFORE swapping. The file is the
    // source of truth on App start, so it must reflect
    // the user's pick even if we crash mid-swap.
    if let Err(e) = save_persisted_cwd(&new_cwd) {
        eprintln!("[prior round] save_persisted_cwd failed: {}", e);
    }
    *state.cwd.lock().await = Some(new_cwd.clone());
    *state.persisted_cwd.lock().await = Some(new_cwd.clone());

    // lift the swap guard for the duration of the
    // swap so the OLD daemon's WS close (during the kill
    // step of swap_to_pre_warm) doesn't fan out as a
    // `daemon.disconnected` notification. The flag is
    // released at function exit (deferred); we only set
    // it when a daemon is actually alive — otherwise the
    // notify_disconnect path inside the WS task is never
    // triggered anyway, so the flag is moot.
    let daemon_alive = state.ws_tx.lock().await.is_some();
    let _swap_guard = if daemon_alive {
        state.swapping.store(true, Ordering::Release);
        eprintln!("[R201] swap guard lifted");
        Some(ScopingGuard {
            flag: state.swapping.clone(),
        })
    } else {
        None
    };

    // If no daemon is up yet, just update the slot. The
    // next ensure_daemon call will spawn one rooted at
    // new_cwd via spawn_daemon's `cwd` argument — no need
    // to pre-warm + swap. We have no session to mint yet
    // (the engine doesn't exist), so sessionId is null.
    if !daemon_alive {
        eprintln!("[R199] set_cwd_daemon: no daemon up, slot updated; ensure_daemon will pick up {}", new_cwd.display());
        return Ok(serde_json::json!({
            "cwd": new_cwd.to_string_lossy().to_string(),
            "sessionId": null,
            "swapped": false,
        }));
    }

    // 1. Pre-warm a daemon rooted at new_cwd. This is the
    //    long pole (~1-2s JVM startup + health-check). If
    //    one is already warm for this exact cwd, this is
    //    a no-op (returns the existing slot info).
    let pre_warm_info = pre_warm_daemon(new_cwd.to_string_lossy().to_string(), app.clone(), state.clone()).await?;
    eprintln!("[R199] pre-warm ready: port={}, cwd={}", pre_warm_info.port, pre_warm_info.cwd);

    // 2. Promote the pre-warm to primary. This kills the
    //    current primary and re-opens the WS to the new
    //    one.
    let new_primary = swap_to_pre_warm(app.clone(), state.clone()).await?;
    eprintln!("[R199] swap complete: port={}, cwd={}", new_primary.port, new_primary.cwd);

    // 3. Mint a fresh session on the new primary. We use
    //    the same WS that swap_to_pre_warm just opened.
    let create_result = rpc_call(
        "createSession".to_string(),
        serde_json::json!({ "cwd": new_cwd.to_string_lossy() }),
        state.clone(),
    )
    .await;
    let session_id = match create_result {
        Ok(value) => {
            eprintln!("[R199] createSession on new daemon ok: {}", value);
            value.get("sessionId")
                .and_then(|v| v.as_str())
                .map(|s| s.to_string())
                .ok_or_else(|| "createSession returned no sessionId".to_string())?
        }
        Err(e) => {
            // The new daemon is up but createSession
            // failed. We log loudly; the renderer will
            // see the empty session list and prompt the
            // user to retry. The pre-warm was consumed
            // by the swap, so the next setCwd pays the
            // full 1-2s startup again.
            eprintln!("[R199] createSession failed after swap: {}", e);
            return Err(format!("createSession on new daemon failed: {}", e));
        }
    };
    Ok(serde_json::json!({
        "cwd": new_cwd.to_string_lossy().to_string(),
        "sessionId": session_id,
        "swapped": true,
    }))
}

#[tauri::command]
async fn get_cwd(state: State<'_, AppState>) -> Result<Option<String>, String> {
    if let Some(c) = state.cwd.lock().await.clone() {
        return Ok(Some(c.to_string_lossy().to_string()));
    }
    Ok(std::env::current_dir()
        .ok()
        .map(|p| p.to_string_lossy().to_string()))
}

#[tauri::command]
async fn rpc_call(
    method: String,
    params: serde_json::Value,
    state: State<'_, AppState>,
) -> Result<serde_json::Value, String> {
    let guard = state.ws_tx.lock().await;
    let tx = guard.as_ref().ok_or("WS not connected (call ensure_daemon first)")?;
    let (reply, rx) = oneshot::channel();
    tx.send(RpcRequest { method, params, reply })
        .map_err(|_| "WS task is dead".to_string())?;
    drop(guard);
    rx.await.map_err(|_| "WS reply channel dropped".to_string())?
}

/// R250b: read the bank surface directly from Rust over HTTP
/// instead of routing through the WebSocket JSON-RPC bridge.
/// The renderer can call this from React/TS as
/// `await invoke<BankStats>('bank_stats')`.
///
/// We do not auto-call `ensure_daemon` here — the renderer's
/// existing initialisation flow already calls `ensure_daemon`
/// first. If the renderer hits `bank_stats` before any daemon
/// is up, we return a clear error rather than silently
/// spawning one (which would change the lifecycle semantics
/// the renderer currently relies on).
#[tauri::command]
async fn bank_stats(state: State<'_, AppState>) -> Result<BankStats, String> {
    let guard = state.bank_client.lock().await;
    let client = guard
        .as_ref()
        .ok_or_else(|| "bank client not initialised (call ensure_daemon first)".to_string())?;
    client.stats().await.map_err(|e| format!("bank_stats: {}", e))
}

/// R250b: recall the top-N units for a task kind from Rust
/// directly. Returns an empty list when the daemon replies 404
/// (no such kind) — same semantics as the TS / JVM clients so
/// the renderer's "no experience yet" path stays branch-free.
#[tauri::command]
async fn bank_recall(
    kind: String,
    n: Option<u32>,
    state: State<'_, AppState>,
) -> Result<Vec<BankUnit>, String> {
    let guard = state.bank_client.lock().await;
    let client = guard
        .as_ref()
        .ok_or_else(|| "bank client not initialised (call ensure_daemon first)".to_string())?;
    let n = n.unwrap_or(5);
    client.recall_for(&kind, n).await.map_err(|e| format!("bank_recall: {}", e))
}

/// R250b: cross-kind top-N recall (mirrors the TS client's
/// `recallAllKinds(n)` from R244.3). Useful for the
/// `/memory-audit`-style all-kinds surface on the desktop
/// shell.
#[tauri::command]
async fn bank_recall_all_kinds(
    n: Option<u32>,
    state: State<'_, AppState>,
) -> Result<Vec<BankUnit>, String> {
    let guard = state.bank_client.lock().await;
    let client = guard
        .as_ref()
        .ok_or_else(|| "bank client not initialised (call ensure_daemon first)".to_string())?;
    let n = n.unwrap_or(20);
    client
        .recall_all_kinds(n)
        .await
        .map_err(|e| format!("bank_recall_all_kinds: {}", e))
}

/// R123: write a UTF-8 text file. Used by the
/// RpcDiagnosticsPanel's "Export to .jsonl"
/// button — the renderer hands the user a
/// save-file dialog (via tauri-plugin-dialog),
/// then asks Rust to write the chosen path's
/// contents. The path is supplied by the
/// user via the dialog, not the renderer, so
/// there's no path-traversal concern: the OS
/// save dialog only lets the user pick
/// somewhere they have write access anyway.
///
/// <p>We intentionally do NOT pull in
/// tauri-plugin-fs for this single use case —
/// adding a new dependency + capability
/// grants for one tiny write is heavier
/// than a focused 10-line command.
#[tauri::command]
async fn write_text_file(path: String, contents: String) -> Result<(), String> {
    let p = std::path::PathBuf::from(&path);
    if let Some(parent) = p.parent() {
        if !parent.as_os_str().is_empty() && !parent.exists() {
            return Err(format!("parent directory does not exist: {}", parent.display()));
        }
    }
    std::fs::write(&p, contents).map_err(|e| format!("write failed: {}", e))
}

/// R298: append (don't truncate) a UTF-8 string to a file. Used
/// by the renderer to mirror the desktop's runtime observations
/// (daemonInfo at every reconnect, SDD spawn decisions, etc.)
/// into a single rolling log in %TEMP% so the user-visible
/// "MockSsdDriver (no jar)" symptom can be diagnosed without
/// attaching a debugger or opening the WebView DevTools (which
/// isn't enabled in release builds). The file persists across
/// App restarts and is read-only from outside the App — we
/// intentionally do not surface it in the UI to avoid leaking
/// internal state. See `aethercode-desktop-daemon-info.log`
/// in %TEMP% for the most recent daemonInfo snapshot.
#[tauri::command]
async fn append_text_file(path: String, contents: String) -> Result<(), String> {
    let p = std::path::PathBuf::from(&path);
    if let Some(parent) = p.parent() {
        if !parent.as_os_str().is_empty() && !parent.exists() {
            return Err(format!("parent directory does not exist: {}", parent.display()));
        }
    }
    use std::io::Write;
    let mut f = std::fs::OpenOptions::new()
        .create(true).append(true).open(&p)
        .map_err(|e| format!("append open failed: {}", e))?;
    f.write_all(contents.as_bytes())
        .map_err(|e| format!("append write failed: {}", e))
}

/// R287: read a UTF-8 text file by absolute path.
/// Symmetric to {@link write_text_file}; lets
/// the renderer's TauriSsdDriver fetch the full
/// body of a draft artefact that the
/// `ssd --interactive` subprocess wrote to disk.
/// We do NOT pull in tauri-plugin-fs for this —
/// a focused 6-line command is lighter than a
/// full plugin + capability surface. The caller
/// is the renderer, which in turn sources the
/// path from the daemon's `phase-draft` event —
/// the path is trusted (we wrote it ourselves)
/// so no path-traversal guard is needed.
#[tauri::command]
async fn read_text_file(path: String) -> Result<String, String> {
    std::fs::read_to_string(&path).map_err(|e| format!("read failed: {}", e))
}

/// R317: mkdir -p for the renderer. Used by SddPhaseBar to
/// pre-create `<cwd>/.aethercode/sdd/<slug>/` before phase 1
/// starts, so the agent's write_file calls land in a known
/// directory without racing with shell-side mkdir. Path
/// traversal guard: refuse absolute paths that point outside
/// the project's root (the desktop's resolved cwd), so a
/// malicious renderer can't make us mkdir anywhere on disk.
#[tauri::command]
async fn mkdir_p(path: String, root: Option<String>) -> Result<(), String> {
    let p = std::path::PathBuf::from(&path);
    if let Some(root_str) = root.as_ref() {
        let root_p = std::path::PathBuf::from(root_str);
        // Refuse if `path` is not under `root`.
        if !p.starts_with(&root_p) {
            return Err(format!(
                "mkdir_p rejected: {} is not under root {}",
                p.display(),
                root_p.display()
            ));
        }
    }
    std::fs::create_dir_all(&p).map_err(|e| format!("mkdir failed: {}", e))
}

#[tauri::command]
async fn get_daemon_info(state: State<'_, AppState>) -> Result<Option<DaemonInfo>, String> {
    Ok(state.daemon.lock().await.clone())
}

/// R287: surface the resolved jar path + the
/// current cwd so the renderer's
/// {@link TauriSsdDriver} can spawn
/// `java -jar <jarPath> ssd <feature> "<intent>"
/// --interactive --cwd <cwd>` without
/// duplicating the Rust-side lookup logic. The
/// renderer is the source of truth for the cwd
/// (it updates via `set_cwd`); we re-read it
/// here from `state.cwd` so the subprocess gets
/// the same value the daemon would get. The
/// jar path is resolved via
/// {@link find_jar_path} — the same lookup the
/// daemon spawner uses, so a packaged build
/// finds the bundled resource and a dev build
/// finds the ancestor-walked source jar.
#[tauri::command]
async fn get_app_paths(
    app: AppHandle,
    state: State<'_, AppState>,
) -> Result<java_com_aethercode_app_paths::AppPaths, String> {
    let jar = find_jar_path(&app)?;
    // Mirror the daemon-spawn lookup order in
    // {@link ensure_daemon}: explicit in-memory
    // override first, persisted last-project
    // second. We pull them as separate awaits so
    // the closures don't have to be `async`
    // (the Tokio Mutex guard type doesn't support
    // a sync .or_else() returning an awaited
    // value — rustc complains about implicit
    // await in a non-async closure).
    let cwd = {
        let explicit = state.cwd.lock().await.clone();
        if let Some(c) = explicit {
            c
        } else {
            state
                .persisted_cwd
                .lock()
                .await
                .clone()
                .ok_or_else(|| "NEEDS_CWD".to_string())?
        }
    };
    Ok(java_com_aethercode_app_paths::AppPaths {
        jar_path: jar.to_string_lossy().to_string(),
        cwd: cwd.to_string_lossy().to_string(),
    })
}

/// Force-clear the daemon + WS state on the Rust side. The renderer
/// calls this after receiving a `daemon.disconnected` notification so
/// the next `ensure_daemon` will actually re-establish a fresh
/// connection (without this, the dead `ws_tx` would still be
/// `Some(...)` and the `ws_guard.is_some()` short-circuit would skip
/// re-connection). We also kill the spawned daemon child so the
/// OS-side process is gone, but only if we spawned it ourselves
/// (otherwise an external `aethercode start` is left alone — the
/// caller probably wants to keep it running).
#[tauri::command]
async fn disconnect(
    kill_child: Option<bool>,
    state: State<'_, AppState>,
) -> Result<(), String> {
    eprintln!("[R81] disconnect: clearing ws_tx + daemon");
    {
        let mut w = state.ws_tx.lock().await;
        *w = None;
    }
    {
        let mut d = state.daemon.lock().await;
        *d = None;
    }
    if kill_child.unwrap_or(false) {
        let mut h = state.daemon_handle.lock().await;
        if let Some(mut child) = h.take() {
            let _ = child.kill();
        }
    }
    *HANDLE.lock().await = None;
    Ok(())
}

async fn open_ws(
    url: &str,
    app: AppHandle,
    swapping: Arc<AtomicBool>,
) -> Result<mpsc::UnboundedSender<RpcRequest>, String> {
    eprintln!("[prior round] connecting to {}", url);
    let (ws, _) = connect_async(url)
        .await
        .map_err(|e| format!("WebSocket connect to {}: {}", url, e))?;
    let (mut write, mut read) = ws.split();
    let (tx, mut rx) = mpsc::unbounded_channel::<RpcRequest>();

    // pass the Arc clone into the task so the swap
    // guard consults the live flag, not a snapshot.
    let swapping_task = swapping.clone();
    tokio::spawn(async move {
        eprintln!("[prior round] task started");
        let mut pending: HashMap<u64, oneshot::Sender<Result<serde_json::Value, String>>> =
            HashMap::new();
        let mut next_id: u64 = 1;

        // helper that suppresses the daemon.disconnected
        // notification while a cwd swap is in progress. The
        // OLD daemon's WS is expected to close during the
        // swap (because we kill it) — that close should NOT
        // trigger the renderer's reconnect path, because the
        // swap has already opened a new WS to the new
        // primary. Without this guard, every cwd switch
        // would flash a "reconnecting" spinner.
        let notify_disconnect = |reason: String| {
            if swapping_task.load(Ordering::Acquire) {
                eprintln!("[R201] suppressed daemon.disconnected during swap: {}", reason);
                return;
            }
            let _ = app.emit("ws-notify", serde_json::json!({
                "method": "daemon.disconnected",
                "params": { "reason": reason },
            }));
        };

        // R332: WS keep-alive ping. The server side (Javalin
        // / Jetty) is configured with a 5-minute idle
        // timeout — but if the user is on a long SDD turn
        // (multi-minute thinking + tool runs + file writes)
        // and the chat stream goes quiet for >5 min with no
        // WS frame in either direction, Jetty times out and
        // the user sees a "reconnecting…" flash. A long
        // task that ALSO crosses the 5-min boundary mid-way
        // would disconnect mid-stream — the user reported
        // this as "daemon 非常不稳定".
        //
        // The proper fix is a client-side ping every 30s so
        // the server's idle counter never trips. Jetty's
        // PongFrame handler is wired by the WS library, so
        // we don't need to do anything on the read side —
        // the server replies with Pongs automatically and
        // the WS frame budget resets on either Ping or Pong.
        //
        // 30s is well below the 5-min server threshold with
        // 10x margin, and well above the natural chat-stream
        // heartbeat (~5s for text_delta chunks) so it
        // doesn't add measurable bandwidth.
        let mut ping_timer = tokio::time::interval(std::time::Duration::from_secs(30));
        ping_timer.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
        // first tick fires immediately — consume it so the
        // first ping goes out 30s after task start, not
        // immediately at task start (avoids a ping right
        // after the connection handshake).
        ping_timer.tick().await;

        loop {
            tokio::select! {
                _ = ping_timer.tick() => {
                    // R332: keep-alive ping. tokio-tungstenite
                    // auto-responds to Pongs on the read side
                    // via its WS library; we only need to
                    // send the Ping frame ourselves.
                    if let Err(e) = write.send(WsMessage::Ping(Vec::new())).await {
                        let reason = format!("ws ping send failed: {}", e);
                        eprintln!("[R332] {}", reason);
                        for (_, r) in pending.drain() { let _ = r.send(Err("ws closed".into())); }
                        notify_disconnect(reason);
                        break;
                    }
                    eprintln!("[R332] ws ping sent (keep-alive)");
                }
                Some(req) = rx.recv() => {
                    let id = next_id;
                    next_id = next_id.wrapping_add(1);
                    pending.insert(id, req.reply);
                    let payload = serde_json::json!({
                        "jsonrpc": "2.0",
                        "id": id,
                        "method": req.method,
                        "params": req.params,
                    });
                    if let Err(e) = write.send(WsMessage::Text(payload.to_string())).await {
                        eprintln!("[prior round] send error: {}", e);
                        for (_, r) in pending.drain() { let _ = r.send(Err(format!("ws send: {}", e))); }
                        break;
                    }
                }
                msg = read.next() => {
                    match msg {
                        Some(Ok(WsMessage::Text(text))) => {
                            if let Ok(parsed) = serde_json::from_str::<serde_json::Value>(&text) {
                                if let Some(id) = parsed.get("id").and_then(|v| v.as_u64()) {
                                    if let Some(reply) = pending.remove(&id) {
                                        if let Some(err) = parsed.get("error") {
                                            let _ = reply.send(Err(err.to_string()));
                                        } else {
                                            let result = parsed.get("result").cloned()
                                                .unwrap_or(serde_json::Value::Null);
                                            let _ = reply.send(Ok(result));
                                        }
                                    }
                                } else if let Some(method) = parsed.get("method").and_then(|v| v.as_str()) {
                                    let params = parsed.get("params").cloned()
                                        .unwrap_or(serde_json::Value::Null);
                                    let _ = app.emit("ws-notify", serde_json::json!({
                                        "method": method,
                                        "params": params,
                                    }));
                                }
                            }
                        }
                        Some(Ok(WsMessage::Close(frame))) => {
                            let reason = frame.as_ref()
                                .map(|f| format!("close {} {}", f.code, f.reason))
                                .unwrap_or_else(|| "close (no frame)".to_string());
                            eprintln!("[prior round] closed: {}", reason);
                            for (_, r) in pending.drain() { let _ = r.send(Err("ws closed".into())); }
                            // gate the disconnect notification
                            // through the swapping flag (see above).
                            notify_disconnect(reason);
                            break;
                        }
                        Some(Err(e)) => {
                            let reason = format!("ws error: {}", e);
                            eprintln!("[prior round] {}", reason);
                            for (_, r) in pending.drain() { let _ = r.send(Err(reason.clone())); }
                            notify_disconnect(reason);
                            break;
                        }
                        None => {
                            eprintln!("[prior round] stream ended");
                            for (_, r) in pending.drain() { let _ = r.send(Err("ws closed".into())); }
                            notify_disconnect("stream ended".to_string());
                            break;
                        }
                        _ => {}
                    }
                }
            }
        }
        eprintln!("[prior round] task ended");
    });

    Ok(tx)
}

async fn is_healthy(port: u16) -> bool {
    use std::io::{Read, Write};
    use std::net::TcpStream;

    let addr = format!("127.0.0.1:{}", port);
    let mut stream = match TcpStream::connect_timeout(&addr.parse().unwrap(), Duration::from_millis(300)) {
        Ok(s) => s,
        Err(_) => return false,
    };
    let _ = stream.set_read_timeout(Some(Duration::from_millis(300)));
    let _ = stream.set_write_timeout(Some(Duration::from_millis(300)));
    if stream
        .write_all(b"GET /healthz HTTP/1.0\r\nHost: localhost\r\nConnection: close\r\n\r\n")
        .is_err()
    {
        return false;
    }
    let mut buf = [0u8; 64];
    match stream.read(&mut buf) {
        Ok(n) if n > 0 => {
            let s = String::from_utf8_lossy(&buf[..n]);
            s.contains("200") || s.contains("ok")
        }
        _ => false,
    }
}

fn find_jar_path(app: &AppHandle) -> Result<PathBuf, String> {
    // prefer the Tauri-bundled resource first
    // (the canonical "what this App version expects"
    // jar — embedded in the exe by `tauri.conf.json`'s
    // `resources` field). Ancestor-walked jars are a
    // fallback for dev builds where no resource is
    // bundled. Return at the first match in each
    // section so a closer / more-specific source
    // wins over a higher-versioned farther one
    // (a 9.9.9 in release-r97g/aethercode/dist/
    // should not be out-prioritised by a 0.2.14
    // development jar at the project's
    // aethercode/dist/).
    if let Ok(resource_dir) = app.path().resource_dir() {
        eprintln!("[prior round] find_jar_path: resource_dir = {}", resource_dir.display());
        let exact = resource_dir.join("aethercode.jar");
        if exact.is_file() {
            eprintln!("[prior round] find_jar_path: Tauri resource (exact) {}", exact.display());
            return Ok(exact);
        }
        // R298: log a hit so a packaged App can be diagnosed.
        if let Ok(mut f) = std::fs::OpenOptions::new()
            .create(true).append(true).open(
                std::env::temp_dir().join("aethercode-desktop-find-jar.log"))
        {
            use std::io::Write;
            let _ = writeln!(f, "[R298] find_jar_path hit: resource (exact) {}", exact.display());
        }
        if let Ok(entries) = std::fs::read_dir(&resource_dir) {
            for e in entries.flatten() {
                let p = e.path();
                if is_aethercode_jar(&p) && p.is_file() {
                    eprintln!("[prior round] find_jar_path: Tauri resource (scan) {}", p.display());
                    return Ok(p);
                }
            }
        }
    }
    // R268 desktop polish (2026-09-15): portable install
    // support. After the Tauri resource_dir fallback,
    // look for an `aethercode.jar` (no version suffix)
    // sitting NEXT TO the exe itself — the natural
    // layout for a portable release directory like
    // `release/aethercode-0.2.70/desktop/{exe, jar}`.
    //
    // Without this check, find_jar_path falls through
    // to the ancestor walk below, which finds the
    // user's most-recent maven build in
    // `aethercode/dist/` — frequently a STALE jar
    // (a `cp .../aethercode-cli-0.1.0-SNAPSHOT.jar
    // dist/aethercode-0.2.66.jar` from a prior round
    // that the user forgot to clean up). The portable
    // install layout puts a fresh, version-pinned jar
    // next to the exe and gets the highest priority;
    // the ancestor walk is the LAST resort, only for
    // dev builds (`cargo run`).
    //
    // This is the fix for the 0.2.68 / 0.2.69 desktop
    // false-positive bug: the user's old 0.2.66 daemon
    // kept getting picked up because the ancestor walk
    // found the stale dist/ jar before this check
    // existed.
    if let Ok(exe) = std::env::current_exe() {
        if let Some(exe_dir) = exe.parent() {
            // try the canonical name first
            let exact = exe_dir.join("aethercode.jar");
            if exact.is_file() {
                eprintln!("[R268] find_jar_path: portable install (exact next-to-exe) {}", exact.display());
                return Ok(exact);
            }
            // then any aethercode-*.jar in the same dir
            if let Ok(entries) = std::fs::read_dir(exe_dir) {
                let mut jars: Vec<PathBuf> = entries
                    .flatten()
                    .map(|e| e.path())
                    .filter(|p| is_aethercode_jar(p) && p.is_file())
                    .collect();
                if !jars.is_empty() {
                    jars.sort_by_key(|p| std::cmp::Reverse(jar_sort_key(p)));
                    let picked = jars.swap_remove(0);
                    eprintln!("[R268] find_jar_path: portable install (scan next-to-exe) {}", picked.display());
                    return Ok(picked);
                }
            }
            // R268b (2026-09-15): parent-dir versioned jar.
            // Some zip layouts put the jar one level up
            // from the exe (the cli ships as
            // `release/aethercode-0.2.70.jar` while the
            // exe lives in `release/desktop/`). Without
            // this check, the user extracts a zip whose
            // exe has no sibling jar, find_jar_path
            // falls through to the ancestor walk, and the
            // ancestor walk finds a STALE dev jar from
            // `aethercode/dist/`. This was the 0.2.70
            // regression: portable install found nothing
            // (zip didn't bundle the jar in `desktop/`),
            // the cli jar sat in the parent dir with the
            // right SHA, and we ignored it.
            if let Some(parent_dir) = exe_dir.parent() {
                let parent_exact = parent_dir.join("aethercode.jar");
                if parent_exact.is_file() {
                    eprintln!("[R268b] find_jar_path: portable install (parent-dir exact) {}", parent_exact.display());
                    return Ok(parent_exact);
                }
                if let Ok(entries) = std::fs::read_dir(parent_dir) {
                    let mut jars: Vec<PathBuf> = entries
                        .flatten()
                        .map(|e| e.path())
                        .filter(|p| is_aethercode_jar(p) && p.is_file())
                        .collect();
                    if !jars.is_empty() {
                        jars.sort_by_key(|p| std::cmp::Reverse(jar_sort_key(p)));
                        let picked = jars.swap_remove(0);
                        eprintln!("[R268b] find_jar_path: portable install (parent-dir scan) {}", picked.display());
                        return Ok(picked);
                    }
                }
            }
        }
    }
    // Closest-ancestor walk. The first ancestor
    // with a matching `aethercode/dist/` dir
    // wins; within that dir, the highest-version
    // jar is selected. Subsequent ancestors are
    // NOT consulted (this is the bug fix — legacy-C
    // the code collected ALL candidates and picked
    // the highest version regardless of which
    // ancestor it came from, which let a dev jar
    // in a project dir out-prioritise the bundled
    // release jar).
    //
    // R268 caveat: this fallback is also where stale
    // jars (from manual `cp ... dist/` rounds the
    // user forgot to clean up) get picked up. The
    // portable-install check above is the new
    // preferred path; this one is the legacy escape
    // hatch for dev builds that run `cargo run`
    // from aethercode-desktop/src-tauri/.
    if let Ok(exe) = std::env::current_exe() {
        for ancestor in exe.ancestors().take(6) {
            let dist = ancestor.join("aethercode").join("dist");
            if dist.is_dir() {
                if let Ok(entries) = std::fs::read_dir(&dist) {
                    let mut jars: Vec<PathBuf> = entries
                        .flatten()
                        .map(|e| e.path())
                        .filter(|p| is_aethercode_jar(p) && p.is_file())
                        .collect();
                    if !jars.is_empty() {
                        jars.sort_by_key(|p| std::cmp::Reverse(jar_sort_key(p)));
                        let picked = jars.swap_remove(0);
                        eprintln!("[prior round] find_jar_path: ancestor walk (closest) {} ({} candidates in {})",
                            picked.display(), jars.len() + 1, dist.display());
                        return Ok(picked);
                    }
                }
            }
        }
    }
    // Last resort: $AETHERCODE_DIST.
    if let Ok(env_dist) = std::env::var("AETHERCODE_DIST") {
        let p = PathBuf::from(env_dist);
        if p.is_dir() {
            if let Ok(entries) = std::fs::read_dir(&p) {
                for e in entries.flatten() {
                    let q = e.path();
                    if is_aethercode_jar(&q) && q.is_file() {
                        eprintln!("[prior round] find_jar_path: AETHERCODE_DIST {}", q.display());
                        return Ok(q);
                    }
                }
            }
        }
    }
    // R298: also log to %TEMP%\aethercode-desktop-find-jar.log so
    // a packaged App that has no console can still be diagnosed
    // when SddPhaseBar reports "MockSsdDriver (no jar)". The
    // existing eprintln! calls go to a console that the App
    // never opens on Windows GUI mode, so the user-visible
    // symptom had no companion diagnostic until now.
    if let Ok(mut f) = std::fs::OpenOptions::new()
        .create(true).append(true).open(
            std::env::temp_dir().join("aethercode-desktop-find-jar.log"))
    {
        use std::io::Write;
        let _ = writeln!(f, "[R298] find_jar_path: NONE FOUND — no aethercode-*.jar in any of the bundled-resource / next-to-exe / parent / ancestor walk paths");
    }
    Err("no aethercode-*.jar found".to_string())
}

fn collect_jars(dir: &Path, out: &mut Vec<PathBuf>) {
    // legacy helper, no longer called by
    // find_jar_path (which now does its own
    // filtering + closest-ancestor short-circuit).
    // Left here in case a future round wants to
    // collect across multiple ancestors. Marked
    // `#[allow(dead_code)]` to keep the build green.
    let _ = (dir, out);
}

//
// The App's "last opened project" lives in
// `~/.aethercode/desktop-state.json` so a restart of the
// .exe doesn't ask the user to re-pick the folder every
// time. The schema is intentionally minimal:
//
//   { "lastCwd": "C:/path/to/project" }
//
// We could put more here later (last model, last
// permission mode, …) but for prior round cwd is the only
// thing the renderer needs restored on cold start. The
// file is read on `Default::default()` (App start) and
// rewritten by `set_cwd`. Failure modes are all
// non-fatal: a missing file → None, a malformed file
// → None (we don't want a corrupted state file to
// brick the App).

fn desktop_state_path() -> Option<PathBuf> {
    // Resolve `~/.aethercode/`. We use std::env::home_dir
    // on Windows (USERPROFILE) — which is the OS-known
    // per-user root, NOT the App's working directory.
    let home = std::env::var_os("USERPROFILE")
        .map(PathBuf::from)
        .or_else(|| std::env::var_os("HOME").map(PathBuf::from))?;
    Some(home.join(".aethercode").join("desktop-state.json"))
}

fn load_persisted_cwd() -> Option<PathBuf> {
    let path = desktop_state_path()?;
    load_persisted_cwd_from(&path)
}

/// prior round: test-friendly variant that takes an explicit
/// path so the unit tests don't touch the real
/// `~/.aethercode/desktop-state.json`. The public
/// `load_persisted_cwd` is the production entry point.
fn load_persisted_cwd_from(path: &Path) -> Option<PathBuf> {
    let bytes = std::fs::read(path).ok()?;
    let v: serde_json::Value = serde_json::from_slice(&bytes).ok()?;
    let s = v.get("lastCwd")?.as_str()?;
    let p = PathBuf::from(s);
    if p.is_dir() { Some(p) } else { None }
}

fn save_persisted_cwd(cwd: &Path) -> std::io::Result<()> {
    let path = desktop_state_path().ok_or_else(|| {
        std::io::Error::new(std::io::ErrorKind::NotFound, "no home dir")
    })?;
    save_persisted_cwd_to(&path, cwd)
}

/// prior round: test-friendly variant. Writes a single-key
/// `{ "lastCwd": "..." }` JSON file at the given path.
/// Uses an atomic rename (write to <path>.tmp, then
/// rename) so a crash mid-write doesn't leave a
/// half-truncated state file.
fn save_persisted_cwd_to(path: &Path, cwd: &Path) -> std::io::Result<()> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let body = serde_json::json!({ "lastCwd": cwd.to_string_lossy() });
    let bytes = serde_json::to_vec_pretty(&body)
        .map_err(|e| std::io::Error::new(std::io::ErrorKind::Other, e))?;
    let tmp = path.with_extension("json.tmp");
    std::fs::write(&tmp, &bytes)?;
    std::fs::rename(&tmp, path)?;
    Ok(())
}

#[cfg(test)]
mod desktop_state_tests {
    use super::*;
    use std::fs;
    use std::path::PathBuf;

    /// Helper: a fresh temp dir under %TEMP%/.aethercode-tests/
    /// so each test starts from a clean state. We don't
    /// use std::env::temp_dir() directly because Windows
    /// can return a non-writable path in some CI sandboxes.
    fn make_temp_dir(name: &str) -> PathBuf {
        let base = std::env::temp_dir().join("aethercode-tests").join(name);
        let _ = fs::remove_dir_all(&base);
        fs::create_dir_all(&base).unwrap();
        base
    }

    #[test]
    fn load_returns_none_when_file_missing() {
        let dir = make_temp_dir("load-missing");
        let path = dir.join("desktop-state.json");
        assert!(load_persisted_cwd_from(&path).is_none());
    }

    #[test]
    fn save_then_load_round_trip() {
        let dir = make_temp_dir("round-trip");
        let path = dir.join("desktop-state.json");
        let cwd = dir.join("my-project");
        fs::create_dir_all(&cwd).unwrap();
        save_persisted_cwd_to(&path, &cwd).unwrap();
        let loaded = load_persisted_cwd_from(&path).unwrap();
        assert_eq!(loaded, cwd);
    }

    #[test]
    fn load_returns_none_for_garbage_json() {
        let dir = make_temp_dir("garbage");
        let path = dir.join("desktop-state.json");
        fs::write(&path, b"not even json").unwrap();
        assert!(load_persisted_cwd_from(&path).is_none());
    }

    #[test]
    fn load_returns_none_when_path_no_longer_a_dir() {
        // If the user deletes the project after the App
        // saved it, we don't want to silently fall back
        // to a phantom cwd. The load helper checks
        // `is_dir()` and returns None on miss, so the App
        // re-prompts for a folder.
        let dir = make_temp_dir("missing-cwd");
        let path = dir.join("desktop-state.json");
        let cwd = dir.join("ghost");
        save_persisted_cwd_to(&path, &cwd).unwrap();
        // Don't create cwd. Load should refuse.
        assert!(load_persisted_cwd_from(&path).is_none());
    }

    #[test]
    fn save_creates_parent_dir() {
        // The .aethercode/ dir may not exist on a brand-new
        // user profile. save_persisted_cwd_to must create
        // it. We point the path at a deeply-nested target
        // and verify the file lands.
        let dir = make_temp_dir("create-parent");
        let path = dir.join("nested").join("deeper").join("desktop-state.json");
        let cwd = dir.join("proj");
        fs::create_dir_all(&cwd).unwrap();
        save_persisted_cwd_to(&path, &cwd).unwrap();
        assert!(path.is_file());
    }

    #[test]
    fn save_uses_atomic_rename_no_tmp_left_behind() {
        // After save, the .tmp file should be gone (rename
        // moved it into place). A half-written state file
        // would be much worse than a missing one.
        let dir = make_temp_dir("atomic");
        let path = dir.join("desktop-state.json");
        let cwd = dir.join("proj");
        fs::create_dir_all(&cwd).unwrap();
        save_persisted_cwd_to(&path, &cwd).unwrap();
        let tmp = path.with_extension("json.tmp");
        assert!(!tmp.exists(), "tmp file should be gone after rename");
    }
}

fn jar_sort_key(p: &Path) -> Vec<u32> {
    let name = match p.file_name() { Some(n) => n.to_string_lossy().to_string(), None => return vec![] };
    let stripped = name
        .strip_prefix("aethercode-")
        .map(|s| s.trim_end_matches(".jar").to_string())
        .unwrap_or_default();
    stripped
        .split(|c: char| c == '-' || c == '.')
        .filter_map(|s| s.parse::<u32>().ok())
        .collect()
}

fn is_aethercode_jar(p: &Path) -> bool {
    p.extension().map_or(false, |e| e == "jar")
        && p.file_name()
            .map_or(false, |n| n.to_string_lossy().starts_with("aethercode-"))
}

fn find_java() -> Option<String> {
    if let Ok(jh) = std::env::var("JAVA_HOME") {
        let exe = if cfg!(windows) { "java.exe" } else { "java" };
        let p = PathBuf::from(&jh).join("bin").join(exe);
        if p.is_file() { return Some(p.to_string_lossy().to_string()); }
    }
    which("java")
}

fn which(name: &str) -> Option<String> {
    let paths = std::env::var_os("PATH")?;
    for p in std::env::split_paths(&paths) {
        let candidate = p.join(if cfg!(windows) { format!("{}.exe", name) } else { name.to_string() });
        if candidate.is_file() { return Some(candidate.to_string_lossy().to_string()); }
    }
    None
}

/// R250b: build a fresh `BankClient` for the given daemon
/// `DaemonInfo`. We always build from scratch (instead of
/// mutating in place) so a `disconnect` + reconnect cycle
/// cleanly replaces the prior client — reqwest's `Client`
/// already wraps an `Arc`, so the cost is one allocation.
///
/// Honours the same env vars as the JVM daemon's bank
/// server (prior round):
///   - `AETHERCODE_BANK_TOKEN`         → `Authorization: Bearer …` (prior round)
///   - `AETHERCODE_BANK_TLS=1`         → `https://` instead of `http://` (prior round)
///
/// The TS and Rust surfaces share the same wire format
/// (R244.3), so a token set in the env reaches both
/// transparently. The TLS knob is intentionally simple —
/// we don't try to read keystore paths from the desktop
/// side; if the daemon was started with
/// `AETHERCODE_BANK_TLS_KEYSTORE` it speaks TLS on
/// `https://127.0.0.1:<port>/`, which is what this
/// helper emits when `AETHERCODE_BANK_TLS=1`.
fn build_bank_client(info: &DaemonInfo) -> BankClient {
    let token = std::env::var("AETHERCODE_BANK_TOKEN").ok();
    let use_tls = std::env::var("AETHERCODE_BANK_TLS")
        .ok()
        .map(|v| !v.is_empty() && v != "0" && v.to_lowercase() != "false")
        .unwrap_or(false);
    let scheme = if use_tls { "https" } else { "http" };
    let base_url = format!("{}://127.0.0.1:{}", scheme, info.port);
    eprintln!(
        "[R250b] build_bank_client: url={} auth={}",
        base_url,
        if token.is_some() { "yes" } else { "no" }
    );
    BankClient::new(base_url, token)
}

/// R172 daemon-stability: assemble the JVM args that go
/// before `-jar`. Two layers:
///
///   1. Defaults: -Xms1g -Xmx4g -XX:+UseG1GC.
///      Pinned larger than the JVM's auto-1/4-RAM default
///      so a complex multi-tool task (Maven project
///      generation, repo-wide grep, code transformation
///      across many files) doesn't trip the desktop's
///      STREAM_STALE_MS=30s window during a long GC
///      pause. G1 is the JDK default collector for heaps
///      ≥ 4 GB and gives more predictable pauses than
///      Parallel for this kind of mixed allocation
///      workload.
///
///   2. Override: if the operator sets
///      `AETHERCODE_DAEMON_JVM_OPTS` (e.g. `-Xmx8g
///      -XX:+UseZGC`), that env var REPLACES the
///      defaults entirely. The split is intentional —
///      "I want a different collector" usually means
///      "I don't want G1's defaults either" so it's
///      cleaner to give the operator the full string
///      than to splice flags in.
///
///   3. Always-on (appended after either path above):
///      -XX:MaxMetaspaceSize=256m so a runaway class
///      loader can't eat the OS's virtual memory before
///      we get a clear OOM in the log.
fn daemon_jvm_args() -> Vec<String> {
    let mut args: Vec<String> = if let Ok(custom) = std::env::var("AETHERCODE_DAEMON_JVM_OPTS") {
        custom
            .split_whitespace()
            .map(|s| s.to_string())
            .collect()
    } else {
        vec![
            "-Xms1g".to_string(),
            "-Xmx4g".to_string(),
            "-XX:+UseG1GC".to_string(),
        ]
    };
    // Always-on safety cap; applied after the env-var
    // override so an operator can still bump it (set
    // AETHERCODE_DAEMON_JVM_OPTS=-XX:MaxMetaspaceSize=1g
    // and we'll let the user's value win via JVM
    // last-wins semantics — we only append when the
    // operator's override doesn't include this flag).
    let has_metaspace_cap = args
        .iter()
        .any(|a| a == "-XX:MaxMetaspaceSize" || a.starts_with("-XX:MaxMetaspaceSize="));
    if !has_metaspace_cap {
        args.push("-XX:MaxMetaspaceSize=256m".to_string());
    }
    args
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        // R287: register the shell plugin so the
        // renderer's SsdPanel can spawn
        // `java -jar aethercode.jar ssd
        // <feature> "..." --interactive` and stream
        // newline-delimited JSON events over the
        // subprocess's stdout. The plugin scope
        // (which executables are allowed to be
        // spawned) is configured in tauri.conf.json
        // via the `shell > scope` array; the renderer
        // passes the resolved jar path explicitly so
        // the scope doesn't have to whitelist
        // arbitrary `java` invocations.
        .plugin(tauri_plugin_shell::init())
        .manage(AppState::default())
        .invoke_handler(tauri::generate_handler![
            ensure_daemon, rpc_call, get_daemon_info, get_app_paths, set_cwd, get_cwd, disconnect,
            pre_warm_daemon, swap_to_pre_warm, discard_pre_warm,
            write_text_file, read_text_file, append_text_file, mkdir_p,
            // bank surface from Rust over HTTP
            bank_stats, bank_recall, bank_recall_all_kinds
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
