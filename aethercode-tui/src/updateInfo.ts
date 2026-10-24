/**
 * R374: what `/update` can honestly say.
 *
 * ## The situation
 *
 * The daemon has **no update surface at all** — no `update/check`,
 * no `update/refreshDeps`, no `update/install`, and no updater module
 * in the Maven reactor. The registry row claimed
 * `blocker: 'not-wired'` ("the backend works, nobody wrote the UI"),
 * which was wrong: the backend does not work. Per the R365 blocker
 * vocabulary that difference is load-bearing — `not-wired` means
 * "write the UI", `backend-missing` means "do NOT write the UI, it
 * would be a dead button".
 *
 * So R374 does **not** mount `UpdateAvailable` / `UpdateConfirm` /
 * `UpdateProgress`. All three need a fact this daemon cannot produce
 * ("an update is available" / "a newer version exists" / "a download
 * is in progress"), so every one of them would render a button that
 * does nothing.
 *
 * ## What it does instead
 *
 * It reports the facts that DO exist, and points at the path that
 * does work. That is what mature updaters do when the mechanism is
 * not available:
 *
 *   - **Electron's `autoUpdater`** documents the whole flow as
 *     `checkForUpdates()` → `update-available` → `downloadUpdate()` →
 *     `update-downloaded` → `quitAndInstall()`, and says plainly that
 *     "a successfully downloaded update will still be applied the next
 *     time the application starts". The CHECK is read-only; the
 *     INSTALL is the mutating step.
 *   - **electron-updater** is configured in the wild with
 *     `autoDownload = false` and `autoInstallOnAppQuit = false`, so
 *     nothing happens until the user confirms (Appium Inspector's
 *     "Check for Updates…" does exactly this).
 *   - **Electron's own docs** note there is no built-in auto-updater
 *     on Linux and recommend the distribution's package manager
 *     instead — so "update it yourself, like this" is a first-class
 *     answer, not a failure.
 *   - **Homebrew** keeps three verbs apart: `brew update` refreshes
 *     the formula INDEX, `brew upgrade` installs newer versions, and
 *     `brew outdated` only reports. Merging them is a UX bug.
 *
 * So this module models the same three verbs, and answers each one
 * separately.
 *
 * ## The rule this module enforces
 *
 * **A panel that cannot act must not offer an action that pretends
 * to.** There is no "Install" row here, because there is nothing to
 * install. The one interactive row — the changelog — is a URL, and
 * printing a URL is something the terminal can actually do.
 */

/** The three update verbs. Mirrors Homebrew's `update` / `upgrade` /
 *  `outdated` split, and Electron's check → download → install
 *  stages. */
export type UpdateVerb = "check" | "install" | "deps";

/** The JSON-RPC endpoint each verb probes. */
export const UPDATE_ENDPOINTS: Record<UpdateVerb, string> = {
  check: "update/check",
  install: "update/install",
  deps: "update/refreshDeps",
};

/**
 * Which verb a sentinel means.
 *
 * <p>Three sentinels, one intent, one panel — the R370 pattern: a
 * FLAG, not a second `PanelId`. The flag is written once, at
 * `openPanel`, so the fetch and the render can never disagree about
 * which verb the user asked for.
 *
 * <p>Anything unrecognised falls back to `check`, the read-only
 * verb. The fallback is deliberately the SAFE one: an unknown
 * sentinel must never turn into an install.
 */
export function updateVerbForSentinel(sentinel: string): UpdateVerb {
  if (sentinel === "__UPDATE_INSTALL__") return "install";
  if (sentinel === "__UPDATE_DEPS__") return "deps";
  return "check";
}

/**
 * AetherCode's own release page.
 *
 * <p>NOT deepagents' changelog: `UpdateAvailable.tsx` shipped with
 * `DEFAULT_CHANGELOG_URL` pointing at
 * `github.com/langchain-ai/deepagents/...` — an upstream default that
 * was never repointed. Following it would send a user to another
 * project's release notes.
 */
export const CHANGELOG_URL = "https://github.com/maijun-sec/AetherCode/releases";

/** What the daemon told us, and what it did not. */
export interface UpdateFacts {
  /** The TUI's own version. */
  tuiVersion: string;
  /**
   * The daemon's version, or `null` when the daemon did not report
   * one.
   *
   * <p>`null` means "not reported" and is rendered as such. It is
   * NOT `0.0.0` and NOT "unknown" pretending to be a version — the
   * daemon's `ping` answers `version` from the jar manifest's
   * `Implementation-Version`, which a plain `mvn package` does not
   * write, so `null` is the NORMAL case for a locally built jar.
   */
  daemonVersion: string | null;
  /**
   * True when the daemon has no endpoint for this verb.
   *
   * <p>Derived from a real probe, never declared. If the daemon grows
   * an update RPC, the next run says so — the panel does not carry a
   * hard-coded "this is unavailable".
   */
  endpointMissing: boolean;
  /** The daemon's reason, when it had one. */
  reason: string | null;
  /**
   * The fields the daemon reported, as label/value pairs. Empty when
   * the endpoint was missing.
   */
  reported: Array<[string, string]>;
}

/** The panel's whole content, as data. */
export interface UpdatePanelModel {
  /** The panel title. */
  title: string;
  /** The endpoint this verb probes. */
  endpoint: string;
  /**
   * True when the daemon has no endpoint for this verb.
   *
   * <p>Carried on the model so a renderer can TINT the status line
   * without re-deriving it — and so the two renderers cannot colour
   * the same situation differently.
   */
  endpointMissing: boolean;
  /**
   * The one-sentence ANSWER to the verb.
   *
   * <p>It leads with the honest state, not with the feature. A panel
   * titled "Install an update" whose first line says "there is no
   * install operation here" is truthful; one whose first line is a
   * button is not.
   */
  headline: string;
  /** The version facts and the daemon's report, as label/value pairs. */
  rows: Array<[string, string]>;
  /** What the user can do instead, line by line. */
  manual: string[];
  /** The changelog URL — printed, not fetched. */
  changelogUrl: string;
}

/** The panel title for a verb. */
export function updateVerbTitle(verb: UpdateVerb): string {
  switch (verb) {
    case "check": return "Check for an update";
    case "install": return "Install an update";
    case "deps": return "Refresh dependencies";
  }
}

/**
 * The honest answer to the verb.
 *
 * <p>Every branch says what did NOT happen. "No release feed was
 * contacted and nothing was changed" is the sentence a user can act
 * on; "update failed" is not, because nothing failed.
 */
export function updateHeadline(verb: UpdateVerb, endpointMissing: boolean): string {
  if (!endpointMissing) {
    switch (verb) {
      case "check": return "The daemon reported its update status.";
      case "install": return "The daemon accepted the install request.";
      case "deps": return "The daemon refreshed the dependency index.";
    }
  }
  switch (verb) {
    case "check":
      return "This daemon has no update-check operation, so whether a " +
        "newer release exists cannot be answered here. No release feed " +
        "was contacted and nothing was changed.";
    case "install":
      return "This daemon has no install operation. Nothing was " +
        "downloaded, nothing was replaced, and nothing was written.";
    case "deps":
      return "This daemon has no dependency-refresh operation. No index " +
        "was refreshed and nothing was changed.";
  }
}

/**
 * What the user can do instead.
 *
 * <p>Modelled on Electron's own advice for platforms with no built-in
 * updater ("it is recommended to use the distribution's package
 * manager to update your app") and on the "apply on restart" rule:
 * a running daemon keeps the jar it was started from, so a new
 * version only takes effect the next time the TUI starts.
 */
export function updateManualPath(verb: UpdateVerb): string[] {
  switch (verb) {
    case "check":
      return [
        "To see whether a newer release exists, check it where you installed this:",
        "  npm outdated -g aethercode-tui",
        "  or read the release notes at the changelog URL below",
      ];
    case "install":
      return [
        "To install a new version, install it the way you installed this one:",
        "  npm i -g aethercode-tui@latest",
        "A running daemon keeps the jar it was started from, so a new",
        "version takes effect the next time the TUI starts.",
      ];
    case "deps":
      return [
        "To refresh dependencies, do it where the TUI is installed:",
        "  npm outdated      # what moved",
        "  npm update        # re-resolve to the newest compatible versions",
      ];
  }
}

/** The daemon's version from a `ping` answer, or `null`.
 *
 *  `null` means "not reported" and is rendered as such. It is NOT
 *  `0.0.0` and NOT a made-up version: `ping` answers `version` from
 *  the jar manifest's `Implementation-Version`, which a plain
 *  `mvn package` does not write, so `null` is the NORMAL case for a
 *  locally built jar. */
export function daemonVersionOf(raw: unknown): string | null {
  const v = (raw as { version?: unknown } | null)?.version;
  return typeof v === "string" && v.length > 0 ? v : null;
}

/** The scalar fields of a daemon answer, as label/value pairs.
 *
 *  Objects and arrays are skipped: the update panel is a flat
 *  key/value report, and a nested blob would have nowhere to go. */
export function scalarRows(raw: unknown): Array<[string, string]> {
  if (!raw || typeof raw !== "object") return [];
  return Object.entries(raw as Record<string, unknown>)
    .filter(([, v]) => v === null || typeof v !== "object")
    .map(([k, v]) => [k, String(v)] as [string, string]);
}

/**
 * Build the whole panel content.
 *
 * <p>Pure, so the gate can RUN it and assert the copy the user
 * actually reads.
 */
export function buildUpdatePanel(verb: UpdateVerb, facts: UpdateFacts): UpdatePanelModel {
  const rows: Array<[string, string]> = [
    ["tui version", facts.tuiVersion || "not reported"],
    [
      "daemon version",
      facts.daemonVersion ?? "not reported by the daemon",
    ],
  ];
  for (const [k, v] of facts.reported) rows.push([k, v]);
  if (facts.endpointMissing && facts.reason) {
    // The daemon's own words, so the user can tell a missing method
    // from a refused one.
    rows.push(["daemon said", facts.reason]);
  }
  return {
    title: updateVerbTitle(verb),
    endpoint: UPDATE_ENDPOINTS[verb],
    endpointMissing: facts.endpointMissing,
    headline: updateHeadline(verb, facts.endpointMissing),
    rows,
    manual: updateManualPath(verb),
    changelogUrl: CHANGELOG_URL,
  };
}
