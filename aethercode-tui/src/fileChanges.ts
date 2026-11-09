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
 * R388q — the FILE-CHANGES SUMMARY: how many files the session touched,
 * and how many lines it added and removed.
 *
 * <p>★ WHY THIS MODULE EXISTS ★
 *
 * <p>The desktop's end-of-turn card says one thing:
 *
 * <pre>
 *   ⊕  已编辑 19 个文件  +2235 -36   │  查看文件   [撤销] [审查]
 * </pre>
 *
 * <p>One line that says what the turn DID. The Ink TUI had no way to
 * say that: it rendered one card per tool call, each of which carried
 * only the tool's NAME — because the renderer read {@code ev.args} and
 * {@code ev.result}, and the daemon sends {@code input} and
 * {@code content}. So the TUI's tool cards were nearly empty boxes.
 *
 * <p>★ WHY THE COUNTS ARE COMPUTED HERE, CLIENT-SIDE ★
 *
 * <p>The daemon already has diff machinery
 * ({@code ToolWidgets.DiffStats}, {@code DiffRender}) — but it serves
 * the LINE renderer's approval widget, not the Ink transcript. The
 * Ink TUI receives the tool's INPUT map on the wire, and the input
 * map carries exactly what the counts need:
 *
 * <ul>
 *   <li>{@code file_write} — {@code content} is the whole new file, so
 *       the additions are its line count;</li>
 *   <li>{@code file_edit} — {@code old_string} is what is removed and
 *       {@code new_string} is what is added.</li>
 * </ul>
 *
 * <p>So the counts are DERIVED from the same input the tool used —
 * not re-derived from the file on disk, which may have changed since.
 *
 * <p>★ WHY A PLAIN .ts FILE ★ A gate script must be able to import
 * it; a {@code .tsx} component would pull React and Ink into the
 * gate.
 */

/** One file the session changed, and what it cost. */
export interface FileChange {
  /** The file's path, as the tool was given it. */
  path: string;
  /** The file's NAME (the last path segment). */
  name: string;
  /** Lines added. */
  added: number;
  /** Lines removed. */
  removed: number;
  /** The tool that made the change. */
  tool: string;
}

/**
 * The lines a string spans.
 *
 * <p>★ AN EMPTY STRING IS ZERO LINES, NOT ONE ★ `""` split on "\n"
 * yields `[""]` — one empty line — which would report a write of an
 * empty file as "+1". A file with no content has no lines.
 *
 * @param s the text
 */
export function countLines(s: string | undefined | null): number {
  if (s == null) return 0;
  if (s.length === 0) return 0;
  // A trailing newline does not start a new line: "a\n" is ONE line.
  return s.replace(/\r\n/g, "\n").split("\n").length - (s.endsWith("\n") ? 1 : 0);
}

/**
 * The diff counts for ONE tool call.
 *
 * <p>★ THE COUNTS MUST COME FROM THE TOOL'S OWN INPUT ★ A write's
 * additions are the lines of the content it wrote; an edit's
 * additions are the lines of {@code new_string} and its removals are
 * the lines of {@code old_string}. Counting the file on disk instead
 * would report the CURRENT file, not the change the tool made.
 *
 * <p>A tool that is not a file change yields {@code null} — and
 * {@code null} is different from "zero changes": a bash command that
 * touched nothing is not a file change, and a file change with no
 * line movement still IS one.
 *
 * @param tool  the tool's name
 * @param input the tool's input map (may be undefined)
 */
export function diffStatsFor(
  tool: string | undefined,
  input: Record<string, unknown> | undefined,
): { added: number; removed: number } | null {
  if (!tool || !input) return null;
  const path = typeof input.file_path === "string" ? input.file_path : undefined;
  if (!path) return null;

  if (tool === "file_write" || tool === "file_create") {
    const content = typeof input.content === "string" ? input.content : undefined;
    if (content === undefined) return null;
    // A write REPLACES the file: the new content is what survives.
    // The lines it removed are not knowable from the input alone
    // (the old content is not in the input), so this reports the
    // additions only — which is also what the daemon's own
    // `ToolWidgets.resolveStats` does for a write.
    return { added: countLines(content), removed: 0 };
  }

  if (tool === "file_edit") {
    const oldString = typeof input.old_string === "string" ? input.old_string : undefined;
    const newString = typeof input.new_string === "string" ? input.new_string : undefined;
    if (oldString === undefined && newString === undefined) return null;
    return {
      added: countLines(newString),
      removed: countLines(oldString),
    };
  }

  return null;
}

/**
 * The file change a tool call made, or {@code null}.
 *
 * @param tool  the tool's name
 * @param input the tool's input map
 */
export function fileChangeFor(
  tool: string | undefined,
  input: Record<string, unknown> | undefined,
): FileChange | null {
  const stats = diffStatsFor(tool, input);
  if (!stats) return null;
  const path = String(input?.file_path ?? "");
  const slash = Math.max(path.lastIndexOf("/"), path.lastIndexOf("\\"));
  const name = slash >= 0 ? path.slice(slash + 1) : path;
  return { path, name, added: stats.added, removed: stats.removed, tool: String(tool) };
}

/**
 * The file change a TOOL TURN made, or null.
 *
 * <p>★ ONE RULE, USED BY BOTH SIDES ★ The card groups the turns and
 * the app finds the focused card — and if the two used different
 * rules, a key would act on a card the user is not looking at. So the
 * rule lives here, once, and both read it.
 *
 * <p>The turn's shape is structural (it carries a role, a tool name
 * and the tool's JSON args), so this takes the three fields rather
 * than the whole `Turn` — that keeps this module free of the state
 * module and importable by a gate.
 *
 * @param turn the turn's role
 * @param toolName the turn's tool name
 * @param toolArgs the turn's tool arguments (a JSON string)
 * @returns the change, or null
 */
export function fileChangeOfTurnParts(
  role: string | undefined,
  toolName: string | undefined,
  toolArgs: string | undefined,
): FileChange | null {
  if (role !== "tool") return null;
  if (!toolArgs) return null;
  let input: Record<string, unknown> | undefined;
  try {
    const parsed = JSON.parse(toolArgs);
    if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
      input = parsed as Record<string, unknown>;
    }
  } catch {
    // The args are not JSON — there is nothing to count.
    return null;
  }
  return fileChangeFor(toolName, input);
}

/**
 * The id of the group a turn belongs to, or null when the turn is not
 * a file change.
 *
 * <p>★ THE GROUP IS A RUN OF CONSECUTIVE FILE CHANGES ★ A non-file
 * turn (a bash command) BREAKS the group — otherwise two batches of
 * writes would silently merge into one summary.
 *
 * <p>This walks the turns the same way `groupTurns` does in the
 * Scrollback, so the card the app targets is the card the user sees.
 *
 * @param turns the turns, in order
 * @param index the turn's index
 * @returns the group's first turn id, or null
 */
export function fileChangeGroupIdAt(
  turns: ReadonlyArray<{ id: number; role?: string; toolName?: string; toolArgs?: string }>,
  index: number,
): number | undefined {
  const t = turns[index];
  if (!t || !fileChangeOfTurnParts(t.role, t.toolName, t.toolArgs)) return undefined;
  let start = index;
  while (start > 0) {
    const prev = turns[start - 1];
    if (!fileChangeOfTurnParts(prev.role, prev.toolName, prev.toolArgs)) break;
    start--;
  }
  return turns[start].id;
}

/**
 * The summary of every file change in a set of tool calls.
 *
 * <p>★ ONE CARD FOR MANY FILES ★ The desktop says "已编辑 19 个文件"
 * — NINETEEN files, ONE card. Nineteen separate cards would be a wall
 * of noise and would not answer the question the user actually has
 * ("what did this turn change?"). So the changes are AGGREGATED, and
 * the per-file detail lives behind an expander.
 *
 * <p>★ THE COUNTS SUM ★ The additions and removals are the SUM over
 * the files, so `+2235 -36` is the turn's total.
 *
 * @param changes the per-file changes
 */
export interface FileChangeSummary {
  /** How many files changed. */
  fileCount: number;
  /** Total lines added. */
  added: number;
  /** Total lines removed. */
  removed: number;
  /** The per-file changes, in the order they happened. */
  files: FileChange[];
}

/**
 * Aggregate per-file changes into one summary.
 *
 * @param changes the per-file changes
 */
export function summarizeFileChanges(changes: ReadonlyArray<FileChange>): FileChangeSummary {
  const files = changes.filter((c) => c !== null && c !== undefined);
  let added = 0;
  let removed = 0;
  for (const c of files) {
    added += c.added;
    removed += c.removed;
  }
  return { fileCount: files.length, added, removed, files: [...files] };
}
