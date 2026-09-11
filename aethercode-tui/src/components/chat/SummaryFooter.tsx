/**
 * T-6-13 / spec.md §12.3 / design.md §5: the mandatory summary
 * footer for every assistant turn.
 *
 * <p>Every assistant turn MUST end with a 1-3 line
 * {@code ## Summary} block naming what the assistant did and
 * what it intends to do next. The renderer extracts that block
 * and displays it as a highlighted, always-visible card
 * immediately below the assistant message. The summary card
 * is **never collapsed** — it's the user's quick way to know
 * what happened without expanding cards.
 *
 * <p>How it's enforced:
 * <ul>
 *   <li>The system prompt tells the LLM to end every turn
 *       with a {@code ## Summary} block.</li>
 *   <li>The renderer extracts the block (regex) and displays
 *       it as a separate card.</li>
 *   <li>If the LLM forgets the summary, the runtime
 *       auto-appends a tiny "post-turn" model call. The
 *       response is injected as the summary footer; the
 *       runtime also flips a flag on the message so the
 *       renderer can show a "⚠ auto-summary pending" badge
 *       while waiting.</li>
 * </ul>
 *
 * <p>Two public exports:
 * <ul>
 *   <li>{@link extractSummary} — pure function: text → Summary
 *       record (or null if no block found). Independent of
 *       React/Ink so it's testable in plain node.</li>
 *   <li>{@link SummaryFooter} — the React/Ink renderer that
 *       displays a {@code Summary} record (or the
 *       "auto-summary pending" card if missing).</li>
 * </ul>
 */

import React from "react";
import { Box, Text } from "ink";
import { t } from "../../theme.js";

/** Result of extracting the {@code ## Summary} block from an
 *  assistant message. `null` if no block was found; the
 *  renderer falls back to the "auto-summary pending" card. */
export interface Summary {
  /** The verbatim text of the summary, trimmed. May be
   *  multi-line (the spec allows 1-3 lines). */
  text: string;
  /** True if the summary was injected by the runtime's
   *  post-turn auto-summary hook (not written by the LLM
   *  itself). The renderer surfaces this with a small
   *  "auto" badge so the user knows it's a fallback. */
  autoInjected: boolean;
}

/** The regex used to extract the summary block. Matches:
 *    ## Summary
 *    <body until next `##` heading or end-of-text>
 *
 *  The `##` heading must be at the start of a line (the
 *  `m` flag enables multi-line mode for `^` / `$`). The body
 *  is captured up to the next `##` heading or end of input.
 *  Body text is trimmed of leading/trailing whitespace in the
 *  consumer.
 *
 *  Implementation note: the body is matched with `[\s\S]*`
 *  (greedy) and then trimmed; the strict "stop at next
 *  heading" boundary is enforced by the consumer when needed
 *  via {@link splitAssistantMessage}.
 */
const SUMMARY_RE = /^##\s+Summary[ \t]*$\n?([\s\S]*)/m;

/** Pure helper: extract the {@code ## Summary} block from an
 *  assistant message. Returns `null` if no block was found.
 *
 *  When the input contains a {@code <auto-summary>} tag at
 *  the start of the body, the returned record is flagged with
 *  {@code autoInjected: true} so the UI can badge it. The tag
 *  itself is stripped from the displayed text.
 *
 *  Tests are pure-runtime — no React needed.
 */
export function extractSummary(text: string): Summary | null {
  if (typeof text !== "string" || text.length === 0) return null;
  const m = SUMMARY_RE.exec(text);
  if (!m) return null;
  let body = (m[1] ?? "").trim();
  let autoInjected = false;
  // Runtime may tag an auto-injected summary with a leading
  // <auto-summary> marker. Strip it + flip the flag.
  if (body.startsWith("<auto-summary>")) {
    autoInjected = true;
    body = body.slice("<auto-summary>".length).trim();
  }
  if (body.length === 0) return null;
  return { text: body, autoInjected };
}

/** Pure helper: split an assistant message into the
 *  "summary-bearing" text and the "lead" text above it.
 *  Returns:
 *    - lead: everything BEFORE the `## Summary` heading
 *    - summary: the parsed Summary record (or null)
 *  This is what the Scrollback uses to render the assistant
 *  body separately from the always-visible footer card.
 *
 *  When the body contains a subsequent `## <heading>` after
 *  the summary, the summary text stops at that next heading.
 *  The remaining text is preserved as a suffix on the lead
 *  (in practice this is rare — the spec puts the summary at
 *  the very end — but we handle it for robustness).
 */
export function splitAssistantMessage(
  text: string,
): { lead: string; summary: Summary | null } {
  if (typeof text !== "string" || text.length === 0) {
    return { lead: "", summary: null };
  }
  const m = SUMMARY_RE.exec(text);
  if (!m) return { lead: text, summary: null };
  // Truncate the captured body at the next `## <heading>` (if
  // any). The full text after that heading is rejoined to the
  // lead so we don't drop content.
  let body = m[1] ?? "";
  let suffix = "";
  const nextHeading = body.match(/\n##\s+\S/);
  if (nextHeading && nextHeading.index !== undefined) {
    suffix = body.slice(nextHeading.index);
    body = body.slice(0, nextHeading.index);
  }
  const lead = (text.slice(0, m.index) + suffix).trimEnd();
  const trimmed = body.trim();
  if (trimmed.length === 0) return { lead, summary: null };
  let autoInjected = false;
  let body2 = trimmed;
  if (body2.startsWith("<auto-summary>")) {
    autoInjected = true;
    body2 = body2.slice("<auto-summary>".length).trim();
    if (body2.length === 0) return { lead, summary: null };
  }
  return { lead, summary: { text: body2, autoInjected } };
}

interface Props {
  /** The extracted summary record. Pass `null` to render the
   *  "auto-summary pending" fallback card. */
  summary: Summary | null;
  /** When true (default), the footer shows the always-visible
   *  card. When false, the renderer returns `null` (used by
   *  Scrollback when the user is in some narrow compact
   *  layout that hides the footer — not a current use case,
   *  but the prop keeps the contract honest). */
  visible?: boolean;
}

/**
 * T-6-13: the always-visible summary card.
 *
 * <p>Renders as a compact, single-line (or 2-3 line) card with
 * a yellow border. The badge is:
 * <ul>
 *   <li>{@code SUMMARY} — the LLM wrote it.</li>
 *   <li>{@code ⚠ AUTO-SUMMARY PENDING} — the runtime
 *       hasn't filled it in yet (transient; a separate
 *       post-turn hook will populate it within ~1s).</li>
 *   <li>{@code ⚠ AUTO-SUMMARY (injected)} — the runtime
 *       filled it in via the post-turn hook; the user's
 *       mental model: "the LLM forgot, we asked a
 *       follow-up model to summarise, and pasted the
 *       result here".</li>
 * </ul>
 */
export const SummaryFooter: React.FC<Props> = ({ summary, visible = true }) => {
  if (!visible) return null;

  if (summary == null) {
    return (
      <Box
        flexDirection="row"
        marginY={0}
        marginLeft={2}
        paddingX={1}
        borderStyle="single"
        borderColor={t.warn}
      >
        <Text>
          <Text color={t.warn} bold>⚠ auto-summary pending</Text>
        </Text>
      </Box>
    );
  }

  if (summary.autoInjected) {
    return (
      <Box
        flexDirection="column"
        marginY={0}
        marginLeft={2}
        paddingX={1}
        borderStyle="single"
        borderColor={t.warn}
      >
        <Text>
          <Text color={t.warn} bold>⚠ auto-summary</Text>
          <Text dimColor>  (injected by post-turn hook)</Text>
        </Text>
        {summary.text.split(/\r?\n/).map((line, i) => (
          <Text key={i}>{line}</Text>
        ))}
      </Box>
    );
  }

  return (
    <Box
      flexDirection="column"
      marginY={0}
      marginLeft={2}
      paddingX={1}
      borderStyle="single"
      borderColor="yellowBright"
    >
      <Text>
        <Text color="yellowBright" bold>SUMMARY</Text>
      </Text>
      {summary.text.split(/\r?\n/).map((line, i) => (
        <Text key={i}>{line}</Text>
      ))}
    </Box>
  );
};

export default SummaryFooter;
