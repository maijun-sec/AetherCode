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
