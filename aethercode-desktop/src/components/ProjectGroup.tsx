import { useState, useEffect, useRef } from 'react';
import type { SessionInfo } from '../lib/methods';
import { useStore } from '../store';
import { SessionList } from './SessionList';
import './ProjectGroup.css';

// a single project group in the LeftPanel. Each
// project corresponds to a cwd; the sessions under it are
// the ones that have been bound to that cwd via
// `createSession({ cwd })` or `switchProject`. The user can
// collapse / expand the group, click "+" to start a new
// session bound to this project's cwd, and click a session
// row to switch to it (the SessionList component handles
// the per-row click + highlighting).
//
// Project name = the cwd's last path segment (e.g. "abc_2"
// for "D:\tmp\abc_2"). Hovering the header shows the full
// cwd in a tooltip. An empty-cwd session is grouped under
// "Unlinked Project" (Unlinked) so it still has a home.

// Extract a display name from a cwd. Falls back to the
// full path if there's no clear last segment.
function cwdToProjectName(cwd: string | undefined | null): string {
  if (!cwd) return '未关联项目';
  const trimmed = cwd.replace(/[\\/]+$/, '');
  const seg = trimmed.split(/[\\/]/).pop();
  return seg || trimmed || '未关联项目';
}

interface ProjectGroupProps {
  cwd: string | null;
  sessions: SessionInfo[];
  defaultOpen: boolean;
  onNewSession: (cwd: string | null) => void;
}

export function ProjectGroup({ cwd, sessions, defaultOpen, onNewSession }: ProjectGroupProps) {
  // collapse state is local (we use the native
  // <details> element so the open/close is browser-managed
  // and survives re-renders). defaultOpen=true means the
  // group is expanded when first rendered.
  const [open, setOpen] = useState(defaultOpen);
  // Track the last defaultOpen prop so the user's manual
  // collapse choice isn't overridden on every render.
  const lastDefaultOpenRef = useRef(defaultOpen);
  useEffect(() => {
    if (defaultOpen !== lastDefaultOpenRef.current) {
      lastDefaultOpenRef.current = defaultOpen;
      setOpen(defaultOpen);
    }
  }, [defaultOpen]);
  const name = cwdToProjectName(cwd);
  const isUnlinked = !cwd;
  return (
    <details
      open={open}
      className={`project-group ${isUnlinked ? 'project-group-unlinked' : ''}`}
      onToggle={(e) => setOpen((e.currentTarget as HTMLDetailsElement).open)}
    >
      <summary className="project-group-summary" title={cwd ?? '(no cwd)'}>
        <span className="project-group-chevron">{open ? '▾' : '▸'}</span>
        <span className="project-group-name">{name}</span>
        <span className="project-group-count">{sessions.length}</span>
        <button
          className="project-group-new"
          title={cwd ? `新建 session (cwd: ${cwd})` : '新建 session (无 cwd)'}
          onClick={(e) => {
            e.preventDefault();
            e.stopPropagation();
            onNewSession(cwd);
          }}
        >
          ＋
        </button>
      </summary>
      <div className="project-group-body">
        {sessions.length === 0 ? (
          <div className="project-group-empty">这个项目下没有 session</div>
        ) : (
          <SessionList
            items={sessions}
            // was `sessions.length * 56 + 16`; bumped
            // to 72 to match SessionListVirtual's default
            // row height after the R223 font / padding bump.
            // The 16px tail still covers the "+ New Session"
            // button + a 4px gap.
            height={Math.min(200, sessions.length * 72 + 16)}
            emptyMessage="这个项目下没有 session"
            // R266g: the project <summary> already
            // shows the project name + session count;
            // the inner "SESSIONS" header is a
            // redundant second-level label the user
            // explicitly asked to remove. The
            // "+ New Session" button below stays
            // because it's the per-group create action.
            hideSectionHeader
            // route the bottom "+ New Session" through the
            // same `onNewSession(cwd)` callback the project
            // header's `＋` uses. legacy the bottom button
            // called the store's `createNewSession` which
            // always creates for the CURRENT cwd, so clicking
            // it inside "Unlinked Project" silently made an abc_4
            // session. The tooltip now also names the project
            // (or marks it "无 cwd") so the user knows what
            // scope the new session will belong to.
            onNewSession={() => onNewSession(cwd)}
            newSessionLabel={cwd ? cwdToProjectName(cwd) : '无 cwd'}
          />
        )}
      </div>
    </details>
  );
}

interface ProjectGroupListProps {
  sessions: SessionInfo[];
  currentSessionId: string | null;
  onNewSession: (cwd: string | null) => void;
}

/**
 * project-grouped session list. Groups sessions by
 * their bound cwd; each group is a collapsible details
 * element with a "+" button to create a new session in
 * that project. A session with no cwd is grouped under
 * "Unlinked Project".
 */
export function ProjectGroupList({ sessions, currentSessionId: _currentSessionId, onNewSession }: ProjectGroupListProps) {
  // Group sessions by cwd. We use a Map to preserve
  // insertion order (the daemon returns sessions
  // most-recently-used first, so the groups appear in
  // that order).
  const groups = new Map<string | null, SessionInfo[]>();
  for (const s of sessions) {
    const key = s.cwd ?? null;
    const arr = groups.get(key) ?? [];
    arr.push(s);
    groups.set(key, arr);
  }
  // Sort groups: most-recently-used session in each group
  // (already first since `sessions` is sorted). The
  // unlinked group goes last.
  const entries = Array.from(groups.entries()).sort(([a, aArr], [b, bArr]) => {
    if (a === null) return 1;
    if (b === null) return -1;
    const aRecent = aArr[0]?.lastUsedAt ?? 0;
    const bRecent = bArr[0]?.lastUsedAt ?? 0;
    return bRecent - aRecent;
  });
  // The first group (most-recent project) is open by
  // default; the rest are collapsed so the user sees the
  // current project expanded. Once the user manually
  // toggles, the choice persists (the ProjectGroup
  // component tracks that).
  return (
    <div className="project-group-list">
      {entries.map(([cwd, list], idx) => (
        <ProjectGroup
          key={cwd ?? '__unlinked__'}
          cwd={cwd}
          sessions={list}
          defaultOpen={idx === 0}
          onNewSession={onNewSession}
        />
      ))}
    </div>
  );
}

// hook helper — used by LeftPanel to trigger a new-session
// action. Centralised so the LeftPanel + ProjectGroupList
// share the same handler.
//
// R331: ALL callsites go through `createNewSession({})` without
// a mode so the picker fires. The picker then re-enters
// `createNewSession({ mode })` with the user's choice — at
// that point the cwd is already in place (the picker reads
// `pendingNewSession.cwd` from store state).
export function useNewSessionInCwd() {
  const createNewSession = useStore((s) => s.createNewSession);
  return async (cwd: string | null) => {
    if (cwd) {
      // distinguish "user is already on this project,
      // just give me a fresh session" from "user is switching
      // to this project". The first case is cheap (just
      // createSession on the current daemon); the second
      // case is the multi-daemon swap dance (pre_warm +
      // swap + createSession), which costs ~1-2s and kills
      // the current daemon. ProjectGroup's "+" button is
      // hit in both cases — the user is in abc_1 and clicks
      // "+" on abc_1's group (cheap) vs clicks "+" on
      // abc_2's group (swap).
      const curCwd = useStore.getState().cwd;
      const sameCwd = curCwd
        && curCwd.replace(/[\\/]+$/, '').toLowerCase() === cwd.replace(/[\\/]+$/, '').toLowerCase();
      if (!sameCwd) {
        // R199: different cwd — swap to the target daemon
        // first. The picker reads pendingNewSession.cwd
        // AFTER the user has picked a mode, so we swap
        // here, then createNewSession({}) opens the
        // picker with the new cwd.
        await useStore.getState().setCwd(cwd);
      }
    }
    // Open the mode picker (createNewSession with no mode
    // opens it). It will then re-enter with the chosen mode.
    await createNewSession();
  };
}
