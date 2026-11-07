// Copyright (c) 2026 maijun
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

/**
 * R388c: the Ink worktree panel.
 *
 * A worktree is an isolated git checkout on its own
 * branch. Creating one gives the user a place to work
 * without disturbing the other sessions on the same repo.
 *
 * ★ THE VERDICT COMES FROM THE DAEMON ★
 * Each row shows the daemon's `isolated` flag. A worktree
 * with `isolated: false` is a plain directory that only
 * LOOKS like isolation — the daemon could not create a
 * real git worktree, and the row says why.
 *
 * The renderer must NOT derive isolation from the absence
 * of a branch: a detached-HEAD worktree has no branch but
 * IS isolated, and a stub worktree has no branch and is
 * NOT (R383's lesson — the verdict comes from the daemon).
 *
 * The host owns the RPC (like every other Ink panel): the
 * panel receives the data and the callbacks, so the test
 * harness never touches the daemon.
 */

import React, { useState } from "react";
import { Box, Text, useInput } from "ink";
import TextInput from "ink-text-input";
import { t } from "../theme.js";
import { hintFor, proseFor } from "../componentKeys.js";

/** One worktree as the daemon reports it. */
export interface WorktreeInfo {
  name: string;
  path: string;
  branch: string | null;
  sourceRepo: string | null;
  /** The daemon's verdict. */
  isolated: boolean;
  /** Why it is degraded (when it is). */
  reason: string | null;
}

/** The daemon's worktree configuration. */
export interface WorktreeStatus {
  available: boolean;
  sourceRepo: string | null;
  root: string;
  reason?: string;
}

export interface WorktreePanelProps {
  worktrees: WorktreeInfo[];
  /** null when the daemon does not expose the worktree surface. */
  status: WorktreeStatus | null;
  /** The worktree the next session binds to. */
  selected: string | null;
  /** Called when the user picks a worktree to bind to (or null to clear). */
  onSelect: (name: string | null) => void;
  /** Create a worktree. Resolves to the daemon's verdict. */
  onCreate: (
    name: string,
  ) => Promise<{ ok: boolean; isolated?: boolean; reason?: string | null; error?: string }>;
  /** Remove a worktree. */
  onRemove: (name: string) => Promise<{ ok: boolean; error?: string }>;
  /** Merge a worktree's branch back into a target branch. */
  onMerge: (
    name: string,
    targetBranch: string | null,
  ) => Promise<{ ok: boolean; message?: string; error?: string }>;
  /** Dismiss the panel. */
  onClose: () => void;
  /**
   * R388j: the CONTENT width — the cells available between the
   * shell's border columns. Every row is laid out to this number,
   * so nothing can overflow the frame.
   */
  width?: number;
}

export const WorktreePanel: React.FC<WorktreePanelProps> = ({
  worktrees,
  status,
  selected,
  onSelect,
  onCreate,
  onRemove,
  onMerge,
  onClose,
  width = 76,
}) => {
  const [mode, setMode] = useState<"list" | "create" | "merge">("list");
  const [name, setName] = useState("");
  const [target, setTarget] = useState("");
  const [busy, setBusy] = useState<string | null>(null);
  const [notice, setNotice] = useState<{ kind: "ok" | "warn" | "err"; text: string } | null>(null);

  useInput((input, key) => {
    if (key.escape) {
      if (mode === "list") onClose();
      else setMode("list");
      return;
    }
    if (mode !== "list") return;
    // R388c: the ACTION keys. Each one is in the registry's table
    // (`src/componentKeys.ts`), and the hint the user sees is
    // rendered FROM that table — so the two cannot drift.
    if (input === "n") {
      setMode("create");
      return;
    }
    if (input === "m") {
      setMode("merge");
      return;
    }
    if (input === "d") {
      if (selected) void doRemove(selected);
      return;
    }
    // Enter on a selected row merges it — the merge is the
    // action that brings the work HOME.
    if (key.return && selected) {
      void doMerge(selected);
    }
  });

  const doCreate = async (): Promise<void> => {
    const n = name.trim();
    if (!n) return;
    setBusy(n);
    setNotice(null);
    const r = await onCreate(n);
    setBusy(null);
    if (!r.ok) {
      setNotice({ kind: "err", text: r.error ?? "worktree create failed" });
      return;
    }
    // R388c: a stub worktree comes back ok:true AND
    // isolated:false — the user asked for isolation and
    // did not get it, so say so with the reason.
    if (r.isolated === false) {
      setNotice({
        kind: "warn",
        text: `"${n}" is a plain directory, NOT git isolation. ${r.reason ?? ""}`.trim(),
      });
    } else {
      setNotice({ kind: "ok", text: `worktree "${n}" created` });
    }
    setName("");
    setMode("list");
  };

  const doMerge = async (n: string): Promise<void> => {
    setBusy(n);
    setNotice(null);
    const r = await onMerge(n, target.trim() || null);
    setBusy(null);
    if (!r.ok) {
      // The daemon's reason is the useful part — it says
      // WHY (e.g. "has uncommitted changes").
      setNotice({ kind: "err", text: r.message ?? "merge failed" });
      return;
    }
    setNotice({ kind: "ok", text: r.message ?? `merged "${n}"` });
  };

  const doRemove = async (n: string): Promise<void> => {
    setBusy(n);
    setNotice(null);
    const r = await onRemove(n);
    setBusy(null);
    if (!r.ok) {
      setNotice({ kind: "err", text: r.error ?? "worktree remove failed" });
      return;
    }
    setNotice({ kind: "ok", text: `worktree "${n}" removed` });
  };

  if (mode === "create") {
    return (
      <Box flexDirection="column" paddingY={1}>
        <Text bold color={t.accent ?? "cyan"}>
          New worktree
        </Text>
        <Text dimColor>Creates a git worktree on branch aethercode/&lt;name&gt;.</Text>
        <Box marginTop={1}>
          <TextInput
            value={name}
            onChange={setName}
            onSubmit={() => void doCreate()}
            placeholder="worktree name"
            focus
          />
        </Box>
        <Text dimColor>{proseFor("components/WorktreePanel.tsx", "create-form")}</Text>
      </Box>
    );
  }

  if (mode === "merge") {
    return (
      <Box flexDirection="column" paddingY={1}>
        <Text bold color={t.accent ?? "cyan"}>
          Merge worktree branch
        </Text>
        <Text dimColor>Leave the target empty to merge into the current branch.</Text>
        <Box marginTop={1}>
          <TextInput
            value={target}
            onChange={setTarget}
            onSubmit={() => {
              if (selected) void doMerge(selected);
            }}
            placeholder="target branch (optional)"
            focus
          />
        </Box>
        <Text dimColor>{proseFor("components/WorktreePanel.tsx", "merge-form")}</Text>
      </Box>
    );
  }

  const isolationUnavailable = status !== null && !status.available;

  return (
    <Box flexDirection="column" paddingY={1}>
      <Text bold color={t.accent ?? "cyan"}>
        Worktrees ({worktrees.length})
      </Text>

      {isolationUnavailable ? (
        <Box marginTop={1} flexDirection="column">
          <Text color="yellow">Git isolation unavailable.</Text>
          <Text dimColor>{status?.reason ?? "The daemon is not configured for worktrees."}</Text>
          <Text dimColor>
            Start it with --worktree-source-repo &lt;path&gt;, or set
            AETHERCODE_WORKTREE_SOURCE_REPO.
          </Text>
        </Box>
      ) : null}

      {notice ? (
        <Box marginTop={1}>
          <Text color={notice.kind === "err" ? "red" : notice.kind === "warn" ? "yellow" : "green"}>
            {notice.text}
          </Text>
        </Box>
      ) : null}

      {worktrees.length === 0 ? (
        <Text dimColor>No worktrees yet. Press "n" to create one.</Text>
      ) : (
        <Box flexDirection="column" marginTop={1}>
          {worktrees.map((w) => {
            const isSelected = selected === w.name;
            const label = isSelected ? "▸ " : "  ";
            return (
              <Box key={`${w.name}:${w.path}`} flexDirection="column" marginBottom={1}>
                <Text>
                  {label}
                  <Text bold color={isSelected ? (t.accent ?? "cyan") : undefined}>
                    {w.name}
                  </Text>
                  {"  "}
                  {w.isolated ? (
                    <Text color="green">[git]</Text>
                  ) : (
                    <Text color="yellow">[plain dir]</Text>
                  )}
                  {"  "}
                  <Text dimColor>{w.branch ?? "(no branch)"}</Text>
                </Text>
                {!w.isolated && w.reason ? (
                  <Text dimColor color="yellow">
                    {w.reason}
                  </Text>
                ) : null}
                {busy === w.name ? <Text dimColor>working…</Text> : null}
              </Box>
            );
          })}
        </Box>
      )}

      {selected ? (
        <Text dimColor>
          Selected: {selected} — {proseFor("components/WorktreePanel.tsx", "selected")}
        </Text>
      ) : null}

      <Box marginTop={1} flexDirection="column">
        <Text dimColor>{hintFor("components/WorktreePanel.tsx")}</Text>
      </Box>
    </Box>
  );
};
