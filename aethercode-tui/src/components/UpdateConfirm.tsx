/**
 * T-430 (Phase 5 R4): UpdateConfirm.
 *
 * 1:1 port of deepagents-code's `UpdateConfirm.*` (Java port
 * at `UpdateConfirm.java`). Two confirmation modals for the
 * `/update --deps` flow:
 *
 *  1. `UpdateBeforeDependenciesConfirmScreen` — "Update dcode
 *     first?" when an app update is available alongside dep
 *     refreshes. Enter → app update, Esc → refresh deps.
 *  2. `RefreshDependenciesConfirmScreen` — "Refresh
 *     dependencies?" for the standalone dep refresh.
 *
 * Both share the same shape: a title, a body, a help line, and
 * Enter / Esc bindings. We model them as a single
 * `<UpdateConfirm />` component driven by a `kind`
 * discriminator — the host picks the kind and the title + body
 * + help are derived.
 *
 * design.md §5.2: "update flow".
 *
 * The component is prop-driven.
 */

import React from "react";
import { Box, Text, useInput } from "ink";

/** Outcome of the confirm. Mirrors the Java Boolean dismiss. */
export type UpdateConfirmResult = boolean;

/** Which sub-confirm this is. Drives title + body + help. */
export type UpdateConfirmKind =
  | "before-deps"    // Update dcode first?  (a new app version + dep refresh available)
  | "refresh-deps";  // Refresh dependencies?

export interface UpdateConfirmProps {
  /** Which sub-confirm. */
  kind: UpdateConfirmKind;
  /** Current / latest app version (for `before-deps`). */
  current?: string;
  latest?: string;
  /** Optional list of planned changes (for `refresh-deps`).
   *  When supplied, the body lists them; otherwise the body
   *  is the generic "re-resolve to newest compatible
   *  versions" copy. */
  plannedChanges?: string;
  /** Called when the user confirms. */
  onConfirm: () => void;
  /** Called when the user cancels. */
  onCancel: () => void;
  /** Optional title override. */
  title?: string;
  /** Optional body override. */
  body?: string;
  /** Optional help override. */
  help?: string;
}

function defaultTitle(kind: UpdateConfirmKind): string {
  switch (kind) {
    case "before-deps": return "Update aethercode first?";
    case "refresh-deps": return "Refresh dependencies?";
  }
}

function defaultBody(kind: UpdateConfirmKind, current?: string, latest?: string, planned?: string): string {
  switch (kind) {
    case "before-deps":
      return `A newer aethercode version is available (${current ?? "?"} → ${latest ?? "?"}). Update aethercode now, or refresh dependencies for the current version you already have.`;
    case "refresh-deps":
      if (planned && planned.trim().length > 0) {
        return `aethercode is already up to date, but compatible dependency updates are available. Refresh to apply these changes:\n\n${planned}`;
      }
      return "aethercode is already up to date, but its dependencies can be re-resolved to the newest compatible versions. This may pull in newer minor releases of packages like langchain-openai.";
  }
}

function defaultHelp(kind: UpdateConfirmKind): string {
  switch (kind) {
    case "before-deps": return "Enter: update aethercode  ·  Esc: refresh current dependencies";
    case "refresh-deps": return "Enter: refresh  ·  Esc: cancel";
  }
}

export const UpdateConfirm: React.FC<UpdateConfirmProps> = ({
  kind,
  current,
  latest,
  plannedChanges,
  onConfirm,
  onCancel,
  title,
  body,
  help,
}) => {
  useInput((_input, key) => {
    if (key.escape) {
      onCancel();
      return;
    }
    if (key.return) {
      onConfirm();
      return;
    }
  });

  const t = title ?? defaultTitle(kind);
  const b = body ?? defaultBody(kind, current, latest, plannedChanges);
  const h = help ?? defaultHelp(kind);

  return (
    <Box
      flexDirection="column"
      borderStyle="double"
      borderColor="cyan"
      paddingX={2}
      paddingY={1}
    >
      <Box>
        <Text color="cyan" bold>◆ {t}</Text>
      </Box>
      <Text> </Text>
      {b.split("\n").map((line, i) => (
        <Text key={i}>{line}</Text>
      ))}
      <Text> </Text>
      <Text dimColor>  {h}</Text>
    </Box>
  );
};

/** Pure helper: produce the result of a confirm. Mirrors
 *  the Java `Boolean.TRUE` / `Boolean.FALSE` dismiss. */
export function updateConfirmResult(confirmed: boolean): UpdateConfirmResult {
  return confirmed;
}

/** Default kind decision: when the daemon reports a new app
 *  version AND there are pending dep refreshes, route the
 *  user to `before-deps`; otherwise route to `refresh-deps`.
 *  Mirrors `UpdateConfirm.chooseKind` in deepagents-code. */
export function chooseUpdateKind(
  newAppVersionAvailable: boolean,
  pendingDepRefreshes: boolean,
): UpdateConfirmKind {
  if (newAppVersionAvailable && pendingDepRefreshes) return "before-deps";
  return "refresh-deps";
}
