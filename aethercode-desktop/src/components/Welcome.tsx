import { useStore } from '../store';
import { Kbd, KbdPlus } from './atoms/Kbd';
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
 * welcome / empty-state. R350 (UX P1-1) re-orders the visual
 * hierarchy to:
 *
 *   Logo (24px) → Headline (32px / 600) → Subhead (--text-secondary)
 *   → Primary CTA (big button, accent-tinted, dominant)
 *   → Secondary tiles (Skills + Recent session, smaller)
 *   → Recent sessions strip (small, dim, max 3 entries)
 *   → Keyboard map footer (kbd atoms, 10px)
 *
 * The previous version put "Last session" BEFORE the tiles —
 * the user's eye landed on a strip of text instead of the
 * primary CTA. Power users come back to the app to ACT, not to
 * read their own history. Headline first, history last.
 *
 * R107: 3-tile grid replaced a single centred paragraph that
 * the user complained "feels blank, 30 seconds to figure out
 * what to do".
 *
 * R107+: fourth mode for `awaitingCwd` (Rust side reports
 * NEEDS_CWD) — single full-width "Open folder" tile replaces
 * the 3-tile grid.
 *
 * R347: "Last session" strip added (now repositioned per
 * R350).
 *
 * R348: `hasProject` source fixed — was `engineState.sessionId`
 * (always non-null due to daemon auto-session), now `cwd`
 * from store (null until user picks a folder).
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
        {/* R350: same Logo / Headline / Subhead hierarchy as
            the regular surface, but the headline is the cwd
            prompt so the user reads it before clicking. */}
        <div className="welcome-logo">✦ AetherCode</div>
        <h1 className="welcome-headline">选择项目目录开始</h1>
        <p className="welcome-subtitle">
          欢迎！项目目录是 daemon 的工作根路径，可随时通过 Header 切换
        </p>
        {/* R350: awaitingCwd uses a single full-width primary
            CTA. The 3-tile grid has nothing useful to show
            without a cwd — the user picks a folder, the
            daemon restarts, and the next render shows the
            normal CTA + Recent strip. */}
        <button
          className="welcome-cta-primary"
          onClick={() => void pickCwd()}
          title="打开文件夹（首次）"
        >
          <span className="welcome-cta-primary-icon">📂</span>
          <span className="welcome-cta-primary-label">打开文件夹</span>
          <span className="welcome-cta-primary-kbd">
            <Kbd>Enter</Kbd>
          </span>
        </button>
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

  // R350: Recent sessions now rendered as a horizontal row of
  // compact chips BELOW the CTA group. The previous "Last:
  // session-name · 3 turns · 5m ago" strip sat between the
  // subtitle and the tile grid, so the user's eye hit it before
  // the primary CTA. UX P1-1 wants the first focus on the CTA.
  const fmtRelative = (ts: number): string => {
    const delta = Date.now() - ts;
    const m = Math.floor(delta / 60_000);
    if (m < 1) return 'just now';
    if (m < 60) return `${m}m ago`;
    const h = Math.floor(m / 60);
    if (h < 24) return `${h}h ago`;
    const d = Math.floor(h / 24);
    if (d < 30) return `${d}d ago`;
    return new Date(ts).toISOString().slice(0, 10);
  };

  // R350: CTA group is now a stacked layout — Primary CTA at
  // top (full-width accent button, height 44px) + two secondary
  // tiles (Skills + Recent) in a 2-column row below. The
  // primary dominates because of height + accent background,
  // not because of tile-grid positioning.
  const handlePrimaryCta = () => {
    if (hasProject) {
      void createNewSession();
    } else {
      // No project yet — the user picks a folder, the daemon
      // restarts in that folder, and the next render shows
      // the normal primary CTA label.
      void pickCwd();
    }
  };
  const handleOpenSkills = () => {
    // Open the RightPanel's Skills section by dispatching
    // a custom event the SkillsPanel listens for.
    window.dispatchEvent(new CustomEvent('aethercode:open-skills-panel'));
  };

  return (
    <div className="welcome">
      {/* R350: Logo demoted from h1 (28px) to a small brand
          mark (24px). The h1 slot is now the Headline. */}
      <div className="welcome-logo">✦ AetherCode</div>
      {/* R350: Headline is now the largest type on the page
          (32px / 600). UX P1-1 says: "first focus on the
          primary CTA" — but you can't have a CTA without a
          headline telling the user what they're starting.
          The headline sits between logo and CTA. */}
      <h1 className="welcome-headline">
        {hasProject ? '开始新对话' : '选择项目目录开始'}
      </h1>
      <p className="welcome-subtitle">
        Java 21 port of Claude Code · TUI + IDEA plugin + 本地桌面
      </p>

      {/* R350: Primary CTA — accent-tinted, full-width,
          height 44px. The user's first click target. UX P1-1
          says: "primary CTA 必须是首屏焦点". */}
      <button
        className="welcome-cta-primary"
        onClick={handlePrimaryCta}
        disabled={!isConnected}
        title={hasProject ? '新会话 (Enter)' : '点击选择项目文件夹'}
        aria-label={hasProject ? 'Start a new session' : 'Pick a project folder'}
      >
        <span className="welcome-cta-primary-icon">✦</span>
        <span className="welcome-cta-primary-label">
          {hasProject ? '新会话' : '打开文件夹'}
        </span>
        <span className="welcome-cta-primary-kbd">
          <Kbd>Enter</Kbd>
        </span>
      </button>

      {/* R350: Secondary tiles — Skills + Recent session,
          side-by-side, smaller. The "Recent session" tile
          still works as a one-click shortcut; we removed
          the duplicative strip above. */}
      <div className="welcome-secondary-row">
        <button
          className="welcome-tile"
          onClick={handleOpenSkills}
          disabled={!isConnected}
          title="加载 skill"
        >
          <div className="welcome-tile-icon">🧩</div>
          <div className="welcome-tile-title">加载 skill</div>
          <div className="welcome-tile-desc">
            粘 git URL 或本地路径，按 scope 装到 user 或 project 目录
          </div>
        </button>
        <button
          className="welcome-tile"
          onClick={() => {
            // The "most recent session" tile — switchSession
            // jumps straight into the most-recent entry.
            // Falls back to createNewSession when no history.
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
            {recent.length > 0
              ? recent[0].name?.trim() ||
                `Session ${recent[0].id.slice(-8)}`
              : '没有历史 — 新建一个'}
          </div>
        </button>
      </div>

      {/* R350: Recent sessions moved BELOW the CTA group.
          R347's "Last: session-name · N turns · Xm ago" strip
          becomes a row of compact chips. Power users see "I
          can pick up here" without it competing with the CTA. */}
      {recent.length > 0 && (
        <div className="welcome-recent-strip" data-testid="welcome-recent-strip">
          <span className="welcome-recent-strip-label">Recent</span>
          <ul className="welcome-recent-chips">
            {recent.map((s) => (
              <li key={s.id}>
                <button
                  className="welcome-recent-chip"
                  onClick={() => void switchSession(s.id)}
                  title={`${s.name?.trim() || s.id.slice(-8)} · ${
                    s.messageCount ?? 0
                  } turns · ${fmtRelative(s.lastUsedAt ?? 0)}`}
                >
                  <span className="welcome-recent-chip-name">
                    {s.name?.trim() || `session ${s.id.slice(-8)}`}
                  </span>
                  <span className="welcome-recent-chip-meta">
                    {s.messageCount ?? 0}t · {fmtRelative(s.lastUsedAt ?? 0)}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}

      {/* R350: Keyboard map footer — 8 keyboard shortcuts via
          shared <Kbd> atoms. Power users scan this in 2s. */}
      <p className="welcome-hint">
        <KbdPlus>{['Ctrl', 'Enter']}</KbdPlus> send ·{' '}
        <KbdPlus>{['Shift', 'Enter']}</KbdPlus> newline · <Kbd>Esc</Kbd> cancel ·{' '}
        <Kbd>/</Kbd> commands · <KbdPlus>{['Ctrl', 'Shift', 'H']}</KbdPlus> re-open welcome ·{' '}
        <KbdPlus>{['Ctrl', 'K']}</KbdPlus> command palette
      </p>
    </div>
  );
}
