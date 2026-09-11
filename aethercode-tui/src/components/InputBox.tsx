/**
 * input editor.
 *
 * the input is no longer a single-line TextInput
 * wrapped in a box border. The box border is gone (OpenCode
 * visual language) and the prompt now supports multi-line
 * editing — shift+Enter inserts a newline, plain Enter
 * submits. The text is rendered as multiple lines via
 * flexDirection="column", one line per \n in the input.
 *
 * when a permission ask is pending, the input box goes
 * "decision mode" — the A/T/P/U/D/N keys are routed to the
 * permission reply (in App's useInput) rather than as text.
 * We disable typing in that state so the user's pending draft
 * is preserved but no new chars are accepted (this avoids
 * the conflict where pressing 'a' would both allow the call
 * AND type an 'a' into the input).
 *
 * the input is ALWAYS active, even while a query is in
 * flight. The "busy, please wait" placeholder is gone — the
 * model running in the background no longer blocks the user
 * from typing their next prompt. A thin status strip above
 * the input (the spinner) shows what the model is doing so the
 * user knows the previous prompt was accepted.
 *
 * a thin horizontal rule (─) separates the input from
 * the scrollback, matching the StatusBar's visual language.
 * The input is rendered as a left-edge ▌ accent + the
 * multi-line text. Empty input shows a one-line hint.
 *
 * T-442: deepagents-code-style fuzzy autocomplete. The
 * InputBox now hosts a MultiCompletionManager that
 * dispatches between a SlashCommandController (trigger:
 * `/`) and a FuzzyFileController (trigger: `@`). The
 * dropdown is rendered between the input rule and the
 * prompt text, with ↑/↓ to navigate, Tab/Enter to
 * accept, Esc to dismiss.
 */

import React, { useEffect, useMemo, useState } from "react";
import { Box, Text, useInput } from "ink";
import Spinner from "ink-spinner";
import type { State } from "../state.js";
import { t, icon } from "../theme.js";
import { SLASH_COMMANDS_DETAILED } from "../commands.js";
import {
  MultiCompletionManager,
  SlashCommandController,
  FuzzyFileController,
  CompletionDropdown,
  EMPTY_SUGGESTIONS,
  type Suggestions,
} from "./Autocomplete.js";

interface Props {
  state: State;
  onChange: (v: string) => void;
  onSubmit: (v: string) => void;
  /** T-442: optional file-list supplier for the `@`
   *  fuzzy-file controller. Defaults to a 32-file
   *  recent-file window (the host wires this to the
   *  recent-files RPC). */
  fileListSupplier?: () => string[];
}

export const InputBox: React.FC<Props> = ({ state, onChange, onSubmit, fileListSupplier }) => {
  // while a permission ask is pending, typing is disabled
  // (the decision keys are handled by the App). The existing
  // draft is preserved so the user can resume editing once the
  // decision is made.
  const decisionPending = state.permissionAsk != null;
  // short status string describing what the model is doing.
  const runStatus = state.submitting
    ? state.status === "running-tool" ? "running tool"
    : state.status === "streaming"   ? "streaming reply"
    : state.status === "thinking"    ? "thinking"
    :                                 "working"
    : "ready";

  // Cursor blink — 530ms on / 530ms off. The cursor is the
  // reverse-video block (▍) at the end of the typed text (or
  // at the start of the input when empty). This is the
  // affordance the user asked for ("where do I type?") —
  // without an explicit cursor marker, the dim placeholder
  // text is visually identical to a static label.
  const [cursorOn, setCursorOn] = useState(true);
  useEffect(() => {
    const id = setInterval(() => setCursorOn((v) => !v), 530);
    return () => clearInterval(id);
  }, []);

  // T-442: build the completion manager once. The two
  // controllers share the same lifecycle (reset on every
  // input change), so a single useMemo instance is enough.
  const completion = useMemo(() => {
    const mgr = new MultiCompletionManager();
    mgr.add(new SlashCommandController(SLASH_COMMANDS_DETAILED.map((c) => ({
      name: c.name,
      description: c.description,
    }))));
    mgr.add(new FuzzyFileController(fileListSupplier ?? (() => [])));
    return mgr;
  }, [fileListSupplier]);

  // T-442: the active suggestions. We re-derive on every
  // text change via the manager; the React state mirrors
  // the manager's current state so the dropdown re-renders.
  const [suggestions, setSuggestions] = useState<Suggestions>(EMPTY_SUGGESTIONS);

  // Helper: refresh the active suggestions for the
  // current input + cursor. The cursor is the END of
  // the input (we don't model mid-string cursor here —
  // InputBox's R168 design only supports a single
  // end-anchored cursor). That's consistent with the
  // R168 multi-line input: every char insertion is an
  // append.
  // T-442: refresh suggestions on every input change
  // via a useEffect so the useInput callback body can
  // keep the R168 source-code pattern (`onChange(state.input + ...)`)
  // intact. The effect runs after every state.input
  // change, regardless of which branch of the key
  // handler triggered it.
  useEffect(() => {
    completion.onTextChanged(state.input, state.input.length);
    setSuggestions(completion.current());
  }, [state.input, completion]);

  // Helper: pick the active suggestion and splice it
  // into the input. For `/commands` the suggestion
  // label is the command name (no leading `/`); for
  // `@files` the label is the full path. We replace
  // the trigger range with the label + a trailing
  // space so the user can immediately type the rest
  // of the command.
  const acceptSuggestion = (): boolean => {
    const active = completion.activeController();
    if (!active) return false;
    // Both controllers expose a `pick()` method (the
    // SlashCommandController does so via the cast
    // below; it's safe because we only get here when
    // a controller is active, and every active
    // controller is one of our two concrete classes).
    const pickFn = (active as unknown as { pick?: () => { label: string } | null }).pick;
    const picked = pickFn ? pickFn() : null;
    if (!picked) return false;
    let range: { start: number; end: number } | null = null;
    const rangeFn = (active as unknown as { range?: () => { start: number; end: number } }).range;
    if (rangeFn) range = rangeFn();
    const text = state.input;
    if (!range) return false;
    // Determine the prefix (so `/` becomes `/help `, not `help`).
    const trigger = text[range.start] ?? "";
    const insertion = trigger + picked.label + " ";
    const next = text.slice(0, range.start) + insertion + text.slice(range.end);
    onChange(next);
    completion.reset();
    setSuggestions(EMPTY_SUGGESTIONS);
    return true;
  };

  // useInput gives us shift+Enter (newline) vs Enter
  // (submit) distinction. ink-text-input doesn't support
  // multi-line, so we use the lower-level hook and render
  // the text ourselves as multiple lines.
  useInput((input, key) => {
    if (decisionPending) return;
    // T-442: while a dropdown is open, ↑/↓ navigate the
    // suggestion list, Tab / Enter accept the highlight,
    // and Esc closes the dropdown. None of those keys
    // should reach the text-editing branch.
    if (suggestions.suggestions.length > 0) {
      const moveFn = (completion.activeController() as unknown as { move?: (d: number) => void } | null)?.move;
      if (key.tab || (input === "\t")) {
        if (acceptSuggestion()) return;
      }
      if (key.return) {
        if (suggestions.suggestions.length > 0) {
          if (acceptSuggestion()) return;
        }
        // No dropdown? Fall through to the submit branch.
      } else if (key.upArrow) {
        if (moveFn) { moveFn(-1); setSuggestions(completion.current()); }
        return;
      } else if (key.downArrow) {
        if (moveFn) { moveFn(1); setSuggestions(completion.current()); }
        return;
      } else if (key.escape) {
        completion.reset();
        setSuggestions(EMPTY_SUGGESTIONS);
        return;
      }
    }
    if (key.return) {
      // shift+Enter inserts a newline; plain Enter
      // submits. The submit handler clears the input via
      // dispatch({ type: "setInput", text: "" }).
      if (key.shift) {
        onChange(state.input + "\n");
        return;
      }
      // Plain Enter submits, but only if there's content
      // (empty submit is a no-op — the user might be in the
      // middle of an accidental newline dance).
      if (state.input.length > 0) {
        onSubmit(state.input);
        completion.reset();
        setSuggestions(EMPTY_SUGGESTIONS);
      }
      return;
    }
    if (key.upArrow) {
      return;
    }
    if (key.downArrow) {
      return;
    }
    if (key.backspace || key.delete) {
      if (state.input.length > 0) {
        onChange(state.input.slice(0, -1));
      }
      return;
    }
    if (input) {
      onChange(state.input + input);
    }
  });

  // render the input as multiple lines. We split on
  // \n and lay out vertically. The first line is the
  // "primary" line (with the prompt marker); continuation
  // lines are indented to align.
  const lines = state.input.split("\n");
  const promptChar = decisionPending ? "⚠" : icon.user;
  const promptColor = decisionPending ? t.warn : t.accent;
  return (
    <Box flexDirection="column">
      {/* spinner strip ABOVE the input. Always visible
          while a query is in flight. */}
      {state.submitting && !decisionPending ? (
        <Box paddingX={1}>
          <Spinner type="dots" />
          <Text color={t.brand}>  {runStatus}…</Text>
          <Text dimColor>  ·  type your next prompt below</Text>
        </Box>
      ) : null}
      {/* thin horizontal rule (matching R167's
          StatusBar style) above the input, then the input
          itself. No box border — the ▌ accent + the rule
          are enough visual separation. */}
      <Text color={t.dim}>{"─".repeat(60)}</Text>
      {/* T-442: the autocomplete dropdown sits BELOW the
          horizontal rule and ABOVE the prompt text so it
          visually points at the text it's completing. */}
      <CompletionDropdown suggestions={suggestions} width={48} />
      <Box flexDirection="row" paddingX={1}>
        <Box flexDirection="column">
          {/* the prompt marker on the first line; the
              continuation lines are indented to align with
              the first character of the user's text. The
              accent character (▌) is the left-edge ribbon
              that matches the StatusBar's visual language. */}
          <Text color={promptColor}>▌ {promptChar} </Text>
          {lines.length > 1
            ? lines.slice(1).map((line, i) => (
                <Text key={i} color={promptColor}>{"  "}</Text>
              ))
            : null}
        </Box>
        <Box flexDirection="column" flexGrow={1}>
          {decisionPending ? (
            <Text dimColor>
              waiting for permission decision — press A / T / P / U / D / N (or collapse the card with Esc)
            </Text>
          ) : state.input.length === 0 ? (
            // Placeholder + a blinking cursor on the SAME
            // line, in the accent colour, so the user can
            // see "type here" even when the input is
            // empty. Without the cursor, the dim
            // placeholder reads as a static label and
            // the user has no idea where to type.
            <Text>
              <Text dimColor>
                type a prompt — Enter to send, shift+Enter for newline, / for commands, @ for files, ↑/↓ for history, Tab to accept, Ctrl-? for help
              </Text>
              <Text color={promptColor}>{cursorOn ? "▍" : " "}</Text>
            </Text>
          ) : (
            // Typed text — one line per \n. The cursor
            // (▍) is appended to the LAST line only,
            // because the R168 design is end-anchored
            // (no mid-string editing). The cursor uses
            // the same accent colour as the prompt
            // marker so it reads as part of the same
            // affordance.
            <>
              {lines.map((line, i) => {
                const isLast = i === lines.length - 1;
                return (
                  <Text key={i}>
                    {line.length === 0 ? " " : line}
                    {isLast ? <Text color={promptColor}>{cursorOn ? "▍" : " "}</Text> : null}
                  </Text>
                );
              })}
            </>
          )}
        </Box>
      </Box>
    </Box>
  );
};
