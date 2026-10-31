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
 * R373: the workflow list — the pure part.
 *
 * ## Why this is its own module
 *
 * `normalizeWorkflowRow` and `parseWorkflowListing` are pure
 * functions over a daemon envelope, and the honest way to test them
 * is to RUN them. A source pin can only see that the code mentions a
 * field; it cannot see that a workflow whose declared name differs
 * from its file name is still addressed by the right id. R372's
 * lesson: "能跑就跑" — if it can run, run it.
 *
 * So the conversion lives here, in a leaf module with no imports, and
 * the gate imports the compiled output and asserts on the values.
 *
 * ## The two names, and why there are two
 *
 * `name` is the DISPLAY name — the `name:` the YAML declares.
 * `id` is the ADDRESSABLE id — the workflow FILE's name.
 *
 * They are not the same string, and `runWorkflow` resolves a FILE:
 * `WorkflowPaths.workflowFile(cwd, name)` looks for `<name>.yaml` (or
 * `<name>.yml`). A file called `deploy.yaml` that declares
 * `name: ship-it` is listed as "ship-it" and would fail to start
 * under that name. R373 made the daemon publish `id` for exactly
 * this; this module is what reads it.
 */

/**
 * One workflow, normalised from a `listWorkflows` entry.
 */
export interface WorkflowRow {
  /** Display name — the YAML's declared `name:`. */
  name: string;
  /** The addressable id — the workflow file's name. Pass THIS to
   *  `runWorkflow`, not `name`. */
  id: string;
  /** One-line description from the YAML. Empty when the YAML has none. */
  description: string;
  /** Number of steps. */
  stepCount: number;
  /** Declared input parameter names. Empty when the YAML declares none. */
  inputs: string[];
}

/** The parsed `listWorkflows` envelope. */
export interface WorkflowListing {
  workflows: WorkflowRow[];
  /** The directory the daemon scanned. `null` = the daemon has not
   *  said yet, which is NOT the same as "the directory is empty". */
  dir: string | null;
  /** The files the daemon could not read. */
  skipped: string[];
}

/**
 * Normalise one `listWorkflows` entry.
 *
 * `id` falls back to `file` then `name`, so a row is addressable
 * whenever the daemon published anything addressable at all.
 *
 * `stepCount` trusts the step ARRAY when it has entries (that is the
 * thing being counted) and the daemon's declared count only as a
 * fallback. A `0` that actually means "unknown" must never be
 * rendered as "this workflow does nothing".
 */
export function normalizeWorkflowRow(raw: unknown, i: number): WorkflowRow {
  const r = (raw ?? {}) as Record<string, unknown>;
  const name = String(r.name ?? r.id ?? `workflow-${i}`);
  const id = String(r.id ?? r.file ?? name);
  const steps = Array.isArray(r.steps) ? r.steps : [];
  const declared = Number(r.stepCount);
  return {
    name,
    id,
    description: String(r.description ?? r.summary ?? ""),
    stepCount:
      steps.length > 0 ? steps.length : Number.isFinite(declared) && declared > 0 ? declared : 0,
    inputs: Array.isArray(r.inputs) ? r.inputs.map((x) => String(x)) : [],
  };
}

/**
 * Parse the whole `listWorkflows` envelope.
 *
 * A daemon that answers `ok: false` produces an EMPTY listing — the
 * caller decides whether that is an error (it is: the RPC layer
 * surfaces the envelope error) or a genuine empty directory.
 */
export function parseWorkflowListing(raw: unknown): WorkflowListing {
  const env = (raw ?? {}) as {
    workflows?: unknown;
    dir?: unknown;
    skipped?: unknown;
  };
  const arr = Array.isArray(env.workflows) ? env.workflows : [];
  return {
    workflows: (arr as unknown[]).map(normalizeWorkflowRow),
    dir: typeof env.dir === "string" && env.dir.length > 0 ? env.dir : null,
    skipped: Array.isArray(env.skipped) ? env.skipped.map((s) => String(s)) : [],
  };
}

/**
 * The empty-state COPY, as the lines the user actually sees.
 *
 * Split from "is this list empty?" on purpose: the line-mode panel
 * needs the copy to fill a frame's `empty:` slot (which is only
 * rendered when there are no rows), while the Ink panel needs to know
 * whether to render the empty state AT ALL. Two functions, one copy.
 *
 * Three different situations look identical if you only count rows:
 *
 *   1. the directory has no workflows      -> "no workflows defined"
 *   2. every workflow file was unreadable  -> "N files could not be read"
 *   3. the daemon has not said where it scans -> "…has not said which
 *      directory it scans yet"
 *
 * (2) is the one that used to be invisible: the daemon dropped
 * unreadable files silently, so "there is nothing here" and "you
 * cannot read what is in it" were the same answer.
 *
 * `tone` is part of the answer, not decoration: an unreadable file is
 * a problem the user can fix (warn), an empty directory is not
 * (info). Rendering both in the same colour would train the user to
 * ignore the one that matters.
 *
 * Pure and exported so the gate can assert the COPY the user sees,
 * rather than the source that produces it — and so the Ink panel and
 * the line-mode panel cannot drift apart.
 */
export interface WorkflowEmptyState {
  /** "warn" = unreadable files; "info" = the directory is simply empty. */
  tone: "warn" | "info";
  /** The lines to render, in order. */
  lines: string[];
}

/**
 * The empty-state copy, independent of whether the list is empty.
 */
export function workflowEmptyCopy(
  skipped: ReadonlyArray<string>,
  dir: string | null,
): WorkflowEmptyState {
  if (skipped.length > 0) {
    const lines = [
      `${skipped.length} workflow file${skipped.length === 1 ? "" : "s"} could not be read:`,
    ];
    for (const f of skipped.slice(0, 5)) lines.push(`  ${f}`);
    if (skipped.length > 5) lines.push(`  ...and ${skipped.length - 5} more`);
    if (dir) lines.push(`  in ${dir}`);
    return { tone: "warn", lines };
  }
  if (dir) {
    return {
      tone: "info",
      lines: ["no workflows defined", `drop a .yaml file under ${dir}`],
    };
  }
  return {
    tone: "info",
    lines: ["no workflows defined", "the daemon has not said which directory it scans yet"],
  };
}

/**
 * The empty state for a workflow list, or `null` when the list is
 * not empty.
 *
 * `workflows` is typed loosely on purpose: the empty state only
 * reads its LENGTH, and the line-mode panel passes the daemon's RAW
 * rows (it works on untyped RPC payloads). Typing the parameter as
 * `WorkflowRow[]` would make the line-mode caller normalise rows it
 * never looks at.
 */
export function workflowEmptyState(
  workflows: ReadonlyArray<unknown>,
  skipped: ReadonlyArray<string>,
  dir: string | null,
): WorkflowEmptyState | null {
  if (workflows.length > 0) return null;
  return workflowEmptyCopy(skipped, dir);
}
