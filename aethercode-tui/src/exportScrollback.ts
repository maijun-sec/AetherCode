/**
 * scrollback export.
 *
 * Two formats: markdown (default) and JSON.
 * The user invokes `/export <path>` (markdown) or
 * `/export json <path>` (JSON). The TUI writes the file and
 * shows a toast with the result.
 *
 * The markdown format is intentionally simple — one section
 * per turn, with role / timestamp / text. The user can paste
 * it into Notion / Obsidian / a GitHub issue. The JSON format
 * is the raw turn array, useful for diffing or replaying.
 */

import { writeFileSync } from "node:fs";
import type { Turn } from "./state.js";

/** Render the scrollback as Markdown. */
export function exportToMarkdown(turns: Turn[], model: string, sessionId: string): string {
  const lines: string[] = [];
  lines.push(`# AetherCode session ${sessionId.slice(0, 8)}`);
  lines.push("");
  lines.push(`Model: \`${model}\`  ·  Turns: ${turns.length}  ·  Generated: ${new Date().toISOString()}`);
  lines.push("");
  lines.push("---");
  lines.push("");
  for (const t of turns) {
    const ts = new Date(t.ts).toLocaleString();
    const role = t.role[0].toUpperCase() + t.role.slice(1);
    lines.push(`## ${role}  ·  ${ts}`);
    lines.push("");
    if (t.role === "tool") {
      lines.push("**Tool call:** `" + (t.toolName ?? "tool") + "`");
      if (t.toolArgs) lines.push("```json\n" + t.toolArgs + "\n```");
      if (t.toolResult) lines.push("**Result:**\n```\n" + t.toolResult + "\n```");
    } else if (t.role === "plan" && t.planItems) {
      lines.push("**Plan:**");
      for (let i = 0; i < t.planItems.length; i++) {
        lines.push(`${i + 1}. ${t.planItems[i]}`);
      }
    } else {
      lines.push(t.text);
    }
    lines.push("");
  }
  return lines.join("\n");
}

/** Render the scrollback as JSON. */
export function exportToJson(turns: Turn[]): string {
  return JSON.stringify({
    version: 1,
    exportedAt: new Date().toISOString(),
    turns,
  }, null, 2);
}

/** Write to file. Throws on error. */
export function writeExport(path: string, contents: string): void {
  writeFileSync(path, contents, "utf-8");
}

/** R347: a sensible default export path the user can paste
 *  straight into a chat or PR. The path is rooted in
 *  `<cwd>/.aethercode/exports/` (we mkdir -p lazily in the
 *  caller because `writeFileSync` is sync and a missing
 *  directory throws ENOENT). The filename carries the
 *  session short-id + a YYYYMMDD-HHMM timestamp so two
 *  exports in the same minute don't clobber each other.
 *
 *  example: `<cwd>/.aethercode/exports/session-f38929d2-20260925-1422.md`
 */
export function defaultExportPath(cwd: string, sessionId: string, ext: "md" | "json"): string {
  const id = (sessionId || "session").slice(0, 8);
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, "0");
  const stamp =
    `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}` +
    `-${pad(now.getHours())}${pad(now.getMinutes())}`;
  const dir = cwd.replace(/[\\/]+$/, "") + "/.aethercode/exports";
  return `${dir}/session-${id}-${stamp}.${ext}`;
}

/** R347: prepend YAML frontmatter to a markdown export so the
 *  pasted document carries enough metadata to render in
 *  GitHub PRs / Obsidian / VS Code preview without losing
 *  the session context. The body is the existing
 *  `exportToMarkdown` output. */
export function exportWithFrontmatter(
  turns: Turn[],
  model: string,
  sessionId: string,
  meta: { cwd?: string; cost?: number } = {},
): string {
  const fm: string[] = ["---"];
  fm.push(`session: ${sessionId}`);
  fm.push(`model: ${model}`);
  fm.push(`turns: ${turns.length}`);
  fm.push(`exported_at: ${new Date().toISOString()}`);
  if (meta.cwd) fm.push(`cwd: ${meta.cwd}`);
  if (typeof meta.cost === "number") fm.push(`cost_usd: ${meta.cost.toFixed(4)}`);
  fm.push("---");
  fm.push("");
  // exportToMarkdown already starts with `# AetherCode session …`.
  // Strip the leading `# ` heading so the frontmatter + body
  // reads as one YAML + Markdown document, not YAML + duplicate
  // heading. The body otherwise stays unchanged.
  const body = exportToMarkdown(turns, model, sessionId);
  const stripped = body.replace(/^# AetherCode session[^\n]*\n/, "");
  return fm.join("\n") + "\n" + stripped;
}
