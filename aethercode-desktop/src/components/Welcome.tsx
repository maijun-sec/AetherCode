import { useStore } from '../store';
import './Welcome.css';

interface WelcomeProps {
  error?: string;
  onRetry?: () => void;
  /**
   * when the Rust side reports NEEDS_CWD (no in-memory
   * override and no persisted last-project), the App shows this
   * variant instead of the regular 3-tile grid. The full-width
   * "Open folder" prompt is the only way forward — there is no
   * daemon to talk to, so the "new session" / "skill" / "Recent"
   * tiles would be useless (and confusing) without a project.
   */
  awaitingCwd?: boolean;
}

/**
 * welcome / empty-state. The previous welcome was
 * a single centred paragraph ("Type a message below to
 * start") which the end-user complained felt "blank, 30 seconds
 * can't figure out what to do" (30 seconds to figure out what to do).
 *
 * R107 replaces it with a 3-tile grid: "new session" / "Load
 * skill" / "Recent session". Each tile has an emoji, a
 * headline, and a one-line description. Click → fires an
 * action (create a new session, open the skill panel, or
 * jump into the most recent session).
 *
 * the prior round adds a fourth mode: when no project is set (no
 * persisted last-project, no in-memory cwd), the first
 * tile is "Open folder" instead of "new session". The user picks
 * a folder and the tile becomes the regular "new session" on
 * every subsequent launch.
 *
 * the prior round fixes the `hasProject` source: the legacy-D check
 * was `engineState?.sessionId`, which is the daemon's auto-
 * created default session — always non-null. We now read
 * `cwd` from the store directly, which is set by `setCwd`
 * and cleared on App start when there's no persisted file.
 */
export function Welcome({ error, onRetry, awaitingCwd }: WelcomeProps) {
  const {
    createNewSession,
    sessions,
    switchSession,
    isConnected,
    pickCwd,
    cwd,
  } = useStore();
  // the source of truth for "do we have a project
  // the user picked?" is the store's `cwd` field (string |
  // null). legacy-D this came from `engineState.sessionId`
  // which is wrong: the daemon auto-creates a default
  // session, so the field was always non-null. Use `cwd` —
  // it is null when no project is set (App start with no
  // persisted file) and non-null after the user picks one.
  const hasProject = typeof cwd === 'string' && cwd.length > 0;
  if (error) {
    return (
      <div className="welcome error">
        <h1>⚠ Connection failed</h1>
        <p className="welcome-error-msg">{error}</p>
        {onRetry && <button className="primary" onClick={onRetry}>Retry</button>}
        <p className="welcome-hint">
          The Tauri Rust core auto-spawns the R80 daemon from <code>resources/aethercode.jar</code> or
          <code>..\aethercode\dist\aethercode-*.jar</code>. If the daemon can't bind a port,
          another instance may be using it.
        </p>
      </div>
    );
  }

  // full-width first-launch prompt. There is no
  // daemon yet (the Rust side refused to spawn without a
  // cwd), so we hide the regular 3-tile grid and ask the
  // user to pick a folder before any other UI is reachable.
  // The path they pick gets persisted to
  // ~/.aethercode/desktop-state.json so the next launch
  // goes straight to the normal empty-state.
  if (awaitingCwd) {
    return (
      <div className="welcome welcome-awaiting-cwd">
        <h1>✦ AetherCode</h1>
        <p className="welcome-subtitle">
          欢迎！请选择一个项目目录开始
        </p>
        <div className="welcome-tiles">
          <button
            className="welcome-tile welcome-tile-primary welcome-tile-cwd"
            onClick={() => void pickCwd()}
            title="打开文件夹（首次）"
          >
            <div className="welcome-tile-icon">📂</div>
            <div className="welcome-tile-title">打开文件夹</div>
            <div className="welcome-tile-desc">选择一个项目目录，作为 daemon 的工作目录</div>
          </button>
        </div>
        <p className="welcome-hint">
          项目路径会保存到 <code>~/.aethercode/desktop-state.json</code>，
          下次启动自动恢复。Header 的 📂 按钮可以随时切换项目。
        </p>
      </div>
    );
  }

  // The 3 most recent sessions (excluding the active one,
  // which the user has just left by hitting "new").
  const recent = sessions
    .filter((s) => s.lastUsedAt)
    .sort((a, b) => (b.lastUsedAt ?? 0) - (a.lastUsedAt ?? 0))
    .slice(0, 3);

  return (
    <div className="welcome">
      <h1>✦ AetherCode</h1>
      <p className="welcome-subtitle">
        Java 21 port of Claude Code · TUI + IDEA plugin + 本地桌面
      </p>
      <div className="welcome-tiles">
        <button
          className="welcome-tile welcome-tile-primary"
          onClick={() => {
            if (hasProject) {
              void createNewSession();
            } else {
              // a project path is required before
              // we can do anything meaningful. The user
              // picks a folder, the daemon restarts in
              // that folder, and the next render shows
              // the normal "new session" tile.
              void pickCwd();
            }
          }}
          disabled={!isConnected}
          title={hasProject ? '新会话' : '点击选择项目文件夹'}
        >
          <div className="welcome-tile-icon">✦</div>
          <div className="welcome-tile-title">{hasProject ? '新会话' : '打开文件夹'}</div>
          <div className="welcome-tile-desc">{hasProject ? '开始一段新的对话' : '选择一个项目目录'}</div>
        </button>
        <button
          className="welcome-tile"
          onClick={() => {
            // Open the RightPanel's Skills section by
            // dispatching a custom event the SkillsPanel
            // listens for. A R108+ iteration could surface
            // a dedicated modal instead of the right panel.
            window.dispatchEvent(new CustomEvent('aethercode:open-skills-panel'));
          }}
          disabled={!isConnected}
          title="加载 skill"
        >
          <div className="welcome-tile-icon">🧩</div>
          <div className="welcome-tile-title">加载 skill</div>
          <div className="welcome-tile-desc">粘 git URL 或本地路径，按 scope 装到 user 或 project 目录，刷新 daemon 列表</div>
        </button>
        <button
          className="welcome-tile"
          onClick={() => {
            // Create a new session — the "most recent
            // session" tile is replaced by "new session"
            // when no history exists, otherwise it shows
            // a dropdown. For now, the simplest path is
            // to fire createNewSession and let the user
            // find the recent in the LeftPanel.
            if (recent.length > 0) {
              void switchSession(recent[0].id);
            } else {
              void createNewSession();
            }
          }}
          disabled={!isConnected}
          title="最近 session"
        >
          <div className="welcome-tile-icon">↩</div>
          <div className="welcome-tile-title">最近 session</div>
          <div className="welcome-tile-desc">
            {recent.length > 0 ? recent[0].name?.trim() || `Session ${recent[0].id.slice(-8)}` : '没有历史 — 新建一个'}
          </div>
        </button>
      </div>
      <p className="welcome-hint">
        Type a message below to start. <kbd>Ctrl</kbd>+<kbd>Enter</kbd> to send,
        <kbd>Esc</kbd> to cancel. Adjust model & permission in the bar above the input.
        Use 📂 in the header to switch projects.
      </p>
    </div>
  );
}
