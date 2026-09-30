import { useStore, permissionModeLabel } from '../store';
import './PermissionPromptBanner.css';

// pending permission requests are surfaced in a banner
// above the message list, not hidden inside the right
// (Telemetry) panel. The legacy flow put PermissionList
// inside the right panel, which is closed by default — so a
// permission request arrived, the model waited, and the user
// never saw the prompt. The model retried the same call
// multiple times in a loop, blocking the whole task.
//
// The new banner mirrors AwaitingDecisionBanner: it's a
// sticky strip above the chat, only visible when there are
// pending requests, with Allow / Deny / Always buttons. The
// user can act immediately without opening any panel.

const RISK_COLOR: Record<string, string> = {
  low: '#6cc28b',
  medium: '#f4c471',
  high: '#f4a371',
  critical: '#f48771',
};

function previewInput(input: unknown): string {
  if (input == null) return '';
  if (typeof input === 'string') return input;
  if (typeof input !== 'object') return String(input);
  const obj = input as Record<string, unknown>;
  const preferred = ['command', 'cmd', 'script', 'file_path', 'path', 'pattern', 'query', 'url', 'uri', 'content', 'input', 'prompt', 'todos', 'id'];
  for (const k of preferred) {
    if (k in obj) {
      const v = obj[k];
      if (typeof v === 'string' && v.trim()) {
        return v.length > 100 ? v.slice(0, 97) + '...' : v;
      }
    }
  }
  return '';
}

// when the input has no usable preview, show a
// tool-aware hint. The previous "(no input)" was
// uninformative — the user couldn't tell if bash was
// missing a command or a file_write was missing a path.
// The hint explains what's wrong so the user can decide
// (e.g. "this is a useless permission request — deny and
// tell the model to add the missing parameter").
function missingInputHint(toolName: string | undefined): string {
  if (!toolName) return '';
  const n = toolName.toLowerCase();
  if (n === 'bash' || n === 'shell' || n === 'exec' || n === 'run_command') {
    return 'missing `command` parameter';
  }
  if (n === 'file_read' || n === 'read_file' || n === 'fileread'
      || n === 'file_write' || n === 'write_file' || n === 'filewrite'
      || n === 'file_edit' || n === 'edit_file' || n === 'fileedit') {
    return 'missing `file_path` parameter';
  }
  if (n === 'web_search') return 'missing `query` parameter';
  if (n === 'web_fetch' || n === 'fetch') return 'missing `url` parameter';
  if (n === 'grep' || n === 'search' || n === 'code_search') return 'missing `pattern` parameter';
  return 'no usable input';
}

function targetFromInput(input: unknown): string | undefined {
  const p = previewInput(input);
  return p || undefined;
}

export function PermissionPromptBanner() {
  const {
    pendingPermissions,
    respondPermission,
    installPermissionOverride,
    // surface the current permission mode in
    // the banner so the user can see "why am I
    // being asked" (主动询问 = ask for everything;
    // 智能授权 = this is a mutation; 始终授权
    // wouldn't prompt at all). The mode is
    // canonical (engineState holds the daemon's
    // authoritative value); we run it through
    // permissionModeLabel for the Chinese label.
    engineState,
    // R703 (UX-P2-4): auto-approve counters so the
    // status strip can show "auto-allow N" even when
    // there's no pending request. The counters come
    // from the daemon (cumulative since session
    // start) so the user can see "12 tool calls have
    // been auto-approved this session" at a glance —
    // answering "is the perm system actually working?"
    // without needing to wait for a real prompt.
    autoApproveLowRisk,
    autoApproveMediumHigh,
    autoApprovedCount,
    autoApprovedElevatedCount,
    // R703: jump-to-settings shortcut so the user
    // can flip the mode or auto-allow flags without
    // hunting through Settings → Permissions. We
    // dispatch a window CustomEvent that App.tsx
    // listens for and flips its local `showSettings`
    // state — keeping the banner decoupled from
    // App.tsx's local state and matching the same
    // pattern the command palette uses.
  } = useStore();
  // R703 (UX-P2-4): always-on perm status strip.
  // The legacy banner only rendered when there was
  // a pending request, which meant the user had no
  // visible feedback that the permission system was
  // even wired up — "is it actually asking?" The
  // new strip is a compact read-only line that
  // shows the current mode + the two auto-allow
  // flags + cumulative auto-approved counts. Hidden
  // when the engine hasn't reported a mode yet
  // (i.e. right after launch before initialize()
  // completes) so we don't flash an empty pill.
  const showStatusStrip = !!engineState?.permissionMode && pendingPermissions.length === 0;
  if (pendingPermissions.length === 0 && !showStatusStrip) return null;

  // Show the OLDEST pending request first (FIFO). The user
  // should answer the first one before the model gets
  // confused. If they want a list view, the right panel still
  // has the full PermissionList.
  const p = pendingPermissions[0];
  const more = pendingPermissions.length - 1;
  const riskColor = RISK_COLOR[(p.riskLevel || '').toLowerCase()] || 'var(--text-dim)';
  // current mode label for the chip. Falls
  // through to '—' if the engine state isn't
  // loaded yet (e.g. the banner mounted before
  // initialize() finished). The chip is purely
  // informational — the user can change the
  // mode from the MessageInput dropdown and
  // future prompts will respect the new mode.
  const currentModeLabel = permissionModeLabel(engineState?.permissionMode);

  const onAllow = () => { void respondPermission(p.requestId, true); };
  const onDeny = () => { void respondPermission(p.requestId, false); };
  const onAlways = (scope: 'session' | 'project' | 'user') => {
    void installPermissionOverride(p.tool, 'allow', targetFromInput(p.input), scope);
  };

  // R703 (UX-P2-4): when there's no pending request,
  // render a compact read-only perm status strip
  // instead of the full banner. The strip shows the
  // current mode + the two auto-allow flags +
  // cumulative auto-approved counts so the user can
  // see at a glance "the perm system is wired up and
  // here's what it would do right now". Critical
  // risk (rm -rf, sudo) is never auto-approved
  // regardless of flags — we surface this in the
  // tooltip so the user doesn't need to dig through
  // Settings to confirm.
  if (showStatusStrip) {
    const rawMode = engineState?.permissionMode ?? '';
    const modeLabel = permissionModeLabel(rawMode);
    return (
      <div className="perm-status-strip" role="status" aria-label="Permission status">
        <span className="perm-status-strip-icon" aria-hidden="true">🔐</span>
        <span className="perm-status-strip-mode" title={`当前权限模式: ${modeLabel} (${rawMode})`}>
          {modeLabel}
        </span>
        <span
          className={`perm-status-strip-flag ${autoApproveLowRisk ? 'is-on' : 'is-off'}`}
          title={`低风险自动放行: ${autoApproveLowRisk ? 'ON' : 'OFF'} · ${autoApprovedCount} 此会话`}
        >
          ✓ auto-low {autoApproveLowRisk ? 'ON' : 'OFF'}{autoApprovedCount > 0 ? ` · ${autoApprovedCount}` : ''}
        </span>
        <span
          className={`perm-status-strip-flag ${autoApproveMediumHigh ? 'is-on' : 'is-off'}`}
          title={`中/高风险自动放行: ${autoApproveMediumHigh ? 'ON' : 'OFF'} · ${autoApprovedElevatedCount} 此会话. 关键风险 (rm -rf / sudo) 永不自动放行.`}
        >
          ▲ auto-med+ {autoApproveMediumHigh ? 'ON' : 'OFF'}{autoApprovedElevatedCount > 0 ? ` · ${autoApprovedElevatedCount}` : ''}
        </span>
        <button
          className="perm-status-strip-settings"
          onClick={() => window.dispatchEvent(new CustomEvent('aethercode:open-settings'))}
          title="打开 Settings 调整权限"
        >
          ⚙
        </button>
      </div>
    );
  }

  return (
    <div className="perm-banner" role="alert">
      <div className="perm-banner-text">
        <div className="perm-banner-title">
          <span className="perm-banner-icon" style={{ color: riskColor }}>!</span>
          <span>等待权限确认</span>
          <span className="perm-banner-tool">{p.tool}</span>
          {p.riskLevel && (
            <span className="perm-banner-risk" style={{ color: riskColor, borderColor: riskColor }}>
              {p.riskLevel}
            </span>
          )}
          {more > 0 && (
            <span className="perm-banner-more" title={`还有 ${more} 个待处理的请求`}>
              +{more}
            </span>
          )}
          {currentModeLabel !== '—' && (
            // tiny chip showing the active
            // permission mode. Tooltip explains
            // what the mode means so a user
            // who's not sure can hover. The
            // chip is intentionally low-key —
            // the action buttons (allow / deny
            // / always) are the focus.
            <span
              className="perm-banner-mode"
              title={`当前权限模式: ${currentModeLabel} — 切换方式: 输入框下拉 (Perm)`}
            >
              {currentModeLabel}
            </span>
          )}
        </div>
        {p.reason && <div className="perm-banner-reason">{p.reason}</div>}
        {previewInput(p.input) ? (
          <code className="perm-banner-input">{previewInput(p.input)}</code>
        ) : (
          // a permission request with no usable
          // input is almost always a model bug (e.g. bash
          // called with no `command`). Surface that
          // explicitly so the user can deny + retry, rather
          // than guessing why the banner is asking.
          <code className="perm-banner-input perm-banner-input-empty">{missingInputHint(p.tool)}</code>
        )}
      </div>
      <div className="perm-banner-actions">
        <button className="perm-banner-btn primary" onClick={onAllow} title="仅此一次允许此工具调用">
          允许
        </button>
        <button className="perm-banner-btn" onClick={() => onAlways('session')} title="本次会话内此工具的所有请求都自动通过">
          本会话始终
        </button>
        <button className="perm-banner-btn" onClick={() => onAlways('project')} title="本项目内此工具的所有请求都自动通过 (落盘 .aethercode/permissions.json)">
          本项目始终
        </button>
        <button className="perm-banner-btn danger" onClick={onDeny} title="拒绝此次请求">
          拒绝
        </button>
      </div>
    </div>
  );
}
