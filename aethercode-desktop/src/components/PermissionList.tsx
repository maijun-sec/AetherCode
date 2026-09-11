import { useState } from 'react';
import { useStore, PermissionRequest } from '../store';
import './PermissionList.css';

// prior round: enhanced permission list. Adds:
//   • Tool input preview (rendered as a one-line key=value view
//     with smart truncation). The user can expand to see the
//     raw JSON for tools they don't recognise.
//   • Risk-level colour cue (low=green / medium=amber / high=orange
//     / critical=red). The badge sits next to the tool name.
//   • "Always allow" button: R86 was UI-only (just dismissed
//     pending requests). R90 now wires to the backend
//     installPermissionOverride action, which calls the
//     permissionPolicyOverride RPC. The rule is appended to the
//     daemon's ProjectPermissionPolicy and survives across all
//     tool calls in the current session.
//   • Bulk "Allow all" / "Deny all" actions in the header.

const RISK_COLOR: Record<string, string> = {
  low: '#6cc28b',
  medium: '#f4c471',
  high: '#f4a371',
  critical: '#f48771',
};
const RISK_LABEL: Record<string, string> = {
  low: 'low',
  medium: 'med',
  high: 'high',
  critical: '!',
};

function previewInput(input: unknown): string {
  if (input == null) return '';
  if (typeof input === 'string') return input;
  if (typeof input !== 'object') return String(input);
  // Pick the most identifying key. Tool-specific.
  const obj = input as Record<string, unknown>;
  // Common "command" / "path" / "url" / "pattern" keys
  const preferred = ['command', 'cmd', 'script', 'file_path', 'path', 'pattern', 'query', 'url', 'uri', 'content', 'input', 'prompt', 'subtask_id', 'parent_index', 'todos', 'id'];
  for (const k of preferred) {
    if (k in obj) {
      const v = obj[k];
      if (typeof v === 'string' && v.trim()) {
        return v.length > 100 ? v.slice(0, 97) + '…' : v;
      }
      if (typeof v === 'number' || typeof v === 'boolean') return `${k}=${v}`;
    }
  }
  // Fall back: first non-empty string value
  for (const [, v] of Object.entries(obj)) {
    if (typeof v === 'string' && v.trim()) {
      return v.length > 100 ? v.slice(0, 97) + '…' : v;
    }
  }
  return '';
}

function InputPreview({ input, expanded, onToggle }: { input: unknown; expanded: boolean; onToggle: () => void }) {
  if (input == null) return null;
  const preview = previewInput(input);
  return (
    <div className="permission-input">
      {preview && !expanded && (
        <pre className="permission-input-preview mono">{preview}</pre>
      )}
      {expanded && (
        <pre className="permission-input-raw mono">
          {JSON.stringify(input, null, 2)}
        </pre>
      )}
      <button
        className="permission-input-toggle"
        onClick={(e) => { e.stopPropagation(); onToggle(); }}
        title={expanded ? '显示摘要' : '显示完整 JSON'}
      >
        {expanded ? '▾ 收起' : '▸ 详情'}
      </button>
    </div>
  );
}

function RiskBadge({ level }: { level?: string }) {
  if (!level) return null;
  const lvl = level.toLowerCase();
  const color = RISK_COLOR[lvl] || 'var(--text-dim)';
  const label = RISK_LABEL[lvl] || lvl;
  return (
    <span className="permission-risk" style={{ color, borderColor: color }} title={`风险: ${level}`}>
      {label}
    </span>
  );
}

function PermissionItem({ p }: { p: PermissionRequest }) {
  const { respondPermission, alwaysAllowedTools, installPermissionOverride } = useStore();
  const [expanded, setExpanded] = useState(false);
  const isAlways = alwaysAllowedTools.has(p.tool);

  // "Always allow" used to be a UI-only state. It now calls
  // the backend's permissionPolicyOverride RPC, which appends a
  // rule to the daemon's ProjectPermissionPolicy. The target is
  // pulled from the tool input (e.g. for bash it's the command)
  // so we only auto-approve the *same* command, not all
  // invocations of bash. If the input is missing or unparseable
  // we fall back to "match all invocations of this tool".
  //
  // three "always" buttons — session (volatile), project
  // (persists to <cwd>/.aethercode/permissions.json), and user
  // (persists to ~/.aethercode/permissions.json). The user said
  // they want batch confirmation per project; the project
  // scope is the new default for the "always" button.
  const target = previewInput(p.input) || undefined;
  const onAlways = (e: React.MouseEvent) => {
    e.stopPropagation();
    void installPermissionOverride(p.tool, 'allow', target, 'session');
  };
  const onAlwaysProject = (e: React.MouseEvent) => {
    e.stopPropagation();
    void installPermissionOverride(p.tool, 'allow', target, 'project');
  };
  const onAlwaysUser = (e: React.MouseEvent) => {
    e.stopPropagation();
    void installPermissionOverride(p.tool, 'allow', target, 'user');
  };

  return (
    <li className={`permission-item ${isAlways ? 'permission-always' : ''}`}>
      <div className="permission-row1">
        <span className="permission-tool">{p.tool}</span>
        <RiskBadge level={p.riskLevel} />
        {isAlways && <span className="permission-always-badge" title="本次会话内始终允许">session-allowed</span>}
      </div>
      {p.reason && <div className="permission-reason">{p.reason}</div>}
      <InputPreview input={p.input} expanded={expanded} onToggle={() => setExpanded((v) => !v)} />
      <div className="permission-actions">
        <button className="permission-allow" onClick={() => respondPermission(p.requestId, true)}>✓ Allow</button>
        <button className="permission-allow-always" onClick={onAlways} title="本次会话内此工具的所有请求都自动通过">
          ✓ Always
        </button>
        <button className="permission-allow-project" onClick={onAlwaysProject} title="本项目内此工具的所有请求都自动通过（落盘 .aethercode/permissions.json）">
          ✓ Always (project)
        </button>
        <button className="permission-allow-user" onClick={onAlwaysUser} title="本用户所有项目下此工具都自动通过（落盘 ~/.aethercode/permissions.json）">
          ✓ Always (user)
        </button>
        <button className="permission-deny" onClick={() => respondPermission(p.requestId, false)}>✗ Deny</button>
      </div>
    </li>
  );
}

export function PermissionList() {
  const { pendingPermissions, respondPermission, installPermissionOverride } = useStore();

  if (pendingPermissions.length === 0) {
    return (
      <div className="permission-list">
        <div className="section-header"><span>Permissions</span></div>
        <div className="permission-empty">No pending requests</div>
      </div>
    );
  }

  const onAllowAll = () => {
    for (const p of pendingPermissions) {
      void respondPermission(p.requestId, true);
    }
  };
  // bulk "always allow all" now calls the backend RPC for
  // each unique tool (the daemon rebuilds its policy once per
  // call, so batching matters less than per-tool uniqueness).
  const onAllowAllAlways = () => {
    const tools = new Set(pendingPermissions.map((p) => p.tool));
    for (const t of tools) {
      // For the bulk path we don't try to scope by target — we
      // blanket-allow the tool. The individual "Always" button
      // remains the per-target variant.
      void installPermissionOverride(t, 'allow', undefined);
    }
    for (const p of pendingPermissions) {
      void respondPermission(p.requestId, true);
    }
  };
  const onDenyAll = () => {
    for (const p of pendingPermissions) {
      void respondPermission(p.requestId, false);
    }
  };

  return (
    <div className="permission-list">
      <div className="section-header">
        <span>Permissions</span>
        <span className="section-meta">{pendingPermissions.length}</span>
      </div>
      <div className="permission-bulk">
        <button className="permission-bulk-btn" onClick={onAllowAll} title="允许所有待处理请求">全部允许</button>
        <button className="permission-bulk-btn" onClick={onAllowAllAlways} title="本次会话内始终允许所有出现的工具">全部始终</button>
        <button className="permission-bulk-btn permission-bulk-deny" onClick={onDenyAll} title="拒绝所有待处理请求">全部拒绝</button>
      </div>
      <ul className="permission-items">
        {pendingPermissions.map((p) => (
          <PermissionItem key={p.requestId} p={p} />
        ))}
      </ul>
    </div>
  );
}
