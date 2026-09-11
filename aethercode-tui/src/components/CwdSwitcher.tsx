/**
 * T-423 (Phase 5 R3): CwdSwitcher.
 *
 * 1:1 port of deepagents-code's `CwdSwitchPromptScreen` (Java port
 * at `CwdSwitchPromptScreen.java`). The original Java screen is a
 * *modal prompt* — it asks the user to switch / stay / abort when
 * resuming or switching threads. Our TUI's CwdSwitcher is the
 * "I want to go elsewhere" surface: the user types a path, we
 * validate it (existence + directory), and we dispatch the
 * `cwd/set` RPC. The host wires the result.
 *
 * design.md §5.2: "path input, validate, then `cwd/set` RPC."
 *
 * spec.md §3.x: switching cwd triggers a re-init of project
 * config (rules, MCP, .env, AGENTS.md) — the picker shows a
 * notice about that, matching the Java screen's
 * `projectSettingsChangeDetected` note.
 *
 * The component is prop-driven. It does NOT touch the
 * filesystem directly — it calls a `validate(path)` callback
 * supplied by the host (so the test harness can avoid I/O).
 */

import React, { useEffect, useState } from "react";
import { Box, Text, useInput } from "ink";
import TextInput from "ink-text-input";

/** A failure reason from the validator. The strings are stable
 *  so the host can decide whether to surface a toast or a
 *  side-note. */
export type CwdValidationKind =
  | "empty"
  | "not-found"
  | "not-directory"
  | "not-absolute"
  | "permission-denied";

export interface CwdValidationResult {
  /** True if the path is acceptable. */
  ok: boolean;
  /** Failure kind when `ok === false`. */
  kind?: CwdValidationKind;
  /** Optional human-readable message. */
  message?: string;
}

export interface CwdSwitcherProps {
  /** The current working directory. Surfaced as a hint above the
   *  input. */
  currentCwd: string;
  /** Optional target directory (e.g. the thread's original cwd).
   *  If supplied and different from `currentCwd`, the picker
   *  pre-fills the input. */
  initialPath?: string;
  /** Validate a candidate path. The host is the one that knows
   *  about the filesystem — tests can supply a stub. */
  validate: (path: string) => CwdValidationResult;
  /** Called when the user confirms a path. */
  onSelect: (path: string) => void;
  /** Called when the user dismisses the picker. */
  onClose: () => void;
  /** Show the "switching reloads project config" notice. Matches
   *  the Java screen's `projectSettingsChangeDetected`. */
  noticeReload?: boolean;
  /** Optional title override. */
  title?: string;
}

export const CwdSwitcher: React.FC<CwdSwitcherProps> = ({
  currentCwd,
  initialPath,
  validate,
  onSelect,
  onClose,
  noticeReload = true,
  title = "Switch CWD",
}) => {
  const [path, setPath] = useState(initialPath ?? "");
  const [err, setErr] = useState<string | null>(null);

  // Live-validate as the user types — but only flag hard errors
  // (empty / not-found / not-directory) so we don't pester the
  // user with "not absolute" mid-typing.
  useEffect(() => {
    if (!path.trim()) {
      setErr(null);
      return;
    }
    const r = validate(path);
    if (!r.ok && r.kind && (r.kind === "not-found" || r.kind === "not-directory")) {
      setErr(r.message ?? `path is not a directory: ${path}`);
    } else {
      setErr(null);
    }
  }, [path, validate]);

  useInput((_input, key) => {
    if (key.escape) {
      onClose();
      return;
    }
  });

  const submit = (raw: string) => {
    const v = raw.trim();
    if (!v) {
      setErr("path is empty");
      return;
    }
    const r = validate(v);
    if (!r.ok) {
      setErr(r.message ?? `invalid path: ${v} (${r.kind ?? "error"})`);
      return;
    }
    onSelect(v);
    onClose();
  };

  return (
    <Box
      flexDirection="column"
      borderStyle="double"
      borderColor="cyan"
      paddingX={2}
      paddingY={1}
    >
      <Box>
        <Text color="cyan" bold>◆ {title}</Text>
        <Text dimColor>  Enter: switch  Esc: cancel</Text>
      </Box>
      <Text> </Text>
      <Text>
        <Text dimColor>current: </Text>
        <Text color="gray">{currentCwd}</Text>
      </Text>
      <Text> </Text>
      <Box>
        <Text color="yellow">{">"}</Text>
        <Text> </Text>
        <TextInput
          value={path}
          onChange={setPath}
          onSubmit={submit}
          placeholder="absolute path…  (e.g. /work/my-project)"
        />
      </Box>
      {err ? (
        <Text color="red">  ✗ {err}</Text>
      ) : null}
      {noticeReload ? (
        <Box marginTop={1} flexDirection="column">
          <Text dimColor>  Switching reloads project-specific config (rules, MCP,</Text>
          <Text dimColor>  skills, .env, AGENTS.md).</Text>
        </Box>
      ) : null}
    </Box>
  );
};

/** Default validator used by the host when it doesn't have a
 *  richer one. The function takes a `stat` function so callers
 *  (and tests) can inject a stub. */
export function makeStatValidator(
  stat: (p: string) => { isDirectory: () => boolean; isFile: () => boolean } | null,
  isAbsolute: (p: string) => boolean = (p) => p.startsWith("/") || /^[a-zA-Z]:[\\/]/.test(p),
): (path: string) => CwdValidationResult {
  return (path: string) => {
    const v = path.trim();
    if (!v) return { ok: false, kind: "empty", message: "path is empty" };
    if (!isAbsolute(v)) return { ok: false, kind: "not-absolute", message: `not an absolute path: ${v}` };
    const s = stat(v);
    if (s === null) return { ok: false, kind: "not-found", message: `path does not exist: ${v}` };
    if (!s.isDirectory()) return { ok: false, kind: "not-directory", message: `not a directory: ${v}` };
    return { ok: true };
  };
}
