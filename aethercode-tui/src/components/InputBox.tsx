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
import { proseFor } from "../componentKeys.js";
import type { State } from "../state.js";
import { t, icon } from "../theme.js";
import { SLASH_COMMANDS_DETAILED, isCompleteSlashCommand } from "../commands.js";
// R388l: the permission comment box's pure logic (armed decision,
// labels). It lives in a plain `.ts` module so a gate script can
// import it — a `.tsx` component cannot be imported by a Node gate.
import { armedLabel } from "../permissionComment.js";
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
  /** R388l: called when the user edits the permission
   *  comment box. The comment box is a MODE of this
   *  input box (not a separate widget), so the text
   *  editing lives here while the App owns the
   *  control keys (arm / submit / cancel). */
  onCommentChange?: (text: string) => void;
}

export const InputBox: React.FC<Props> = ({
  state,
  onChange,
  onSubmit,
  fileListSupplier,
  onCommentChange,
}) => {
  // while a permission ask is pending, typing is disabled
  // (the decision keys are handled by the App). The existing
  // draft is preserved so the user can resume editing once the
  // decision is made.
  const decisionPending = state.permissionAsk != null;
  // R388l: the COMMENT BOX is the one exception to
  // `decisionPending`. When it is open this input box IS the
  // comment field: typed characters go into the comment, and
  // the decision keys are suspended (otherwise "d" would deny
  // instead of typing the letter d).
  const commentBox = state.commentBox;
  // R388j: an open PANEL owns the keyboard too.
  //
  // Ink's `useInput` is NOT focus-scoped: every mounted hook receives
  // every keystroke. The App-level handler already returns early when
  // a panel is open (so ↑/↓ do not drive the prompt history), but
  // this component has its OWN `useInput` — so the panel's keys were
  // ALSO being typed into the prompt. Pressing `a` in the session
  // panel widened the scope AND left `a` in the prompt box.
  //
  // The draft is preserved (the user can resume editing when the
  // panel closes), it just does not receive the panel's keystrokes.
  const panelOpen = state.activePanel != null;
  // short status string describing what the model is doing.
  const runStatus = state.submitting
    ? state.status === "running-tool"
      ? "running tool"
      : state.status === "streaming"
        ? "streaming reply"
        : state.status === "thinking"
          ? "thinking"
          : "working"
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
    mgr.add(
      new SlashCommandController(
        SLASH_COMMANDS_DETAILED.map((c) => ({
          name: c.name,
          description: c.description,
        })),
      ),
    );
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
  /**
   * Accept the highlighted suggestion.
   *
   * @returns the accepted text, or `null` when nothing was accepted.
   */
  const acceptSuggestion = (): string | null => {
    const active = completion.activeController();
    if (!active) return null;
    // Both controllers expose a `pick()` method (the
    // SlashCommandController does so via the cast
    // below; it's safe because we only get here when
    // a controller is active, and every active
    // controller is one of our two concrete classes).
    //
    // R388h: `pick` MUST be called BOUND. It used to be pulled off
    // the object and invoked bare (`pickFn()`), so `this` was
    // `undefined` inside the method and `this.suggestions` threw
    // `TypeError: Cannot read properties of undefined`. The crash
    // fired on the very first Tab/Enter with the dropdown open —
    // i.e. the TUI DIED when the user tried to accept a
    // completion. A detached method call loses the receiver; that
    // is not a style question, it is a crash.
    const pickFn = (active as unknown as { pick?: () => { label: string } | null }).pick;
    const picked = pickFn ? pickFn.call(active) : null;
    if (!picked) return null;
    let range: { start: number; end: number } | null = null;
    const rangeFn = (active as unknown as { range?: () => { start: number; end: number } }).range;
    if (rangeFn) range = rangeFn.call(active);
    const text = state.input;
    if (!range) return null;
    // Determine the prefix (so `/` becomes `/help `, not `help`).
    const trigger = text[range.start] ?? "";
    const insertion = trigger + picked.label + " ";
    const next = text.slice(0, range.start) + insertion + text.slice(range.end);
    onChange(next);
    completion.reset();
    setSuggestions(EMPTY_SUGGESTIONS);
    return next;
  };

  // useInput gives us shift+Enter (newline) vs Enter
  // (submit) distinction. ink-text-input doesn't support
  // multi-line, so we use the lower-level hook and render
  // the text ourselves as multiple lines.
  useInput((input, key) => {
    // R388l: the comment box owns the text editing while it is
    // open. Enter / Esc / Ctrl+<key> are handled by the App
    // (it owns the decision), so they are NOT consumed here.
    if (commentBox) {
      // ★ A MODIFIER KEY IS NOT TEXT. ★ Ink passes the CHARACTER for
      // Ctrl+A as input="a" with key.ctrl=true, so without this guard
      // arming the decision would ALSO type the letter "a" into the
      // comment. The App owns the modifier chords (Ctrl+A/T/P/U/D/N
      // arm the decision), so the InputBox must not see them as text.
      if (key.ctrl || key.meta || key.tab) {
        return;
      }
      if (key.backspace || key.delete) {
        onCommentChange?.(commentBox.text.slice(0, -1));
        return;
      }
      // Enter is the App's send key; it must not be typed into the
      // comment (and Ink gives it an empty `input`, so it would fall
      // through harmlessly — but being explicit is cheaper than
      // depending on that).
      if (key.return) {
        return;
      }
      if (input) {
        onCommentChange?.(commentBox.text + input);
      }
      return;
    }
    if (decisionPending) return;
    // R388j: the panel owns the keyboard while it is open.
    if (panelOpen) return;
    // R388j: the CANONICAL text to submit, when the dropdown just
    // completed the command. `null` means "submit `state.input`".
    let submitText: string | null = null;
    // T-442: while a dropdown is open, ↑/↓ navigate the
    // suggestion list, Tab / Enter accept the highlight,
    // and Esc closes the dropdown. None of those keys
    // should reach the text-editing branch.
    if (suggestions.suggestions.length > 0) {
      // R388h: `move` must be called BOUND too — see the note in
      // `acceptSuggestion`. A bare `moveFn(-1)` throws the same
      // "Cannot read properties of undefined" on `this.suggestions`.
      const activeController = completion.activeController();
      const moveFn = (activeController as unknown as { move?: (d: number) => void } | null)?.move;
      if (key.tab || input === "\t") {
        if (acceptSuggestion() !== null) return;
      }
      if (key.return) {
        // R388j: ★ ENTER ON A COMPLETE COMMAND MUST RUN IT. ★
        //
        // The dropdown was swallowing Enter: accepting the
        // suggestion returned early, so `/sessions` + Enter filled
        // the input with `/sessions ` and stopped — the user had to
        // press Enter a SECOND time to actually run the command.
        // Two Enters for one command is a trap, and it is exactly
        // the shape a user hits when they type a command they know
        // by heart.
        //
        // So: accept the completion, and if the completion IS the
        // whole command, fall THROUGH to the submit branch. The
        // completion is applied to the input (so the command runs
        // its canonical spelling) and the command runs in the same
        // keystroke.
        let acceptedText: string | null = null;
        if (suggestions.suggestions.length > 0) {
          acceptedText = acceptSuggestion();
          if (acceptedText !== null && !isCompleteSlashCommand(acceptedText)) return;
        }
        // Either nothing was accepted, or the accepted text is a
        // complete command — fall through to the submit branch.
        if (acceptedText !== null) {
          submitText = acceptedText;
        }
      } else if (key.upArrow) {
        if (moveFn) {
          moveFn.call(activeController, -1);
          setSuggestions(completion.current());
        }
        return;
      } else if (key.downArrow) {
        if (moveFn) {
          moveFn.call(activeController, 1);
          setSuggestions(completion.current());
        }
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
      //
      // R388j: `submitText` carries the CANONICAL command when the
      // dropdown just completed one (`/sessions` → `/sessions `).
      // The store's `state.input` is still the pre-completion value
      // inside this same handler, so the canonical text has to be
      // threaded through explicitly.
      const text = submitText ?? state.input;
      if (text.length > 0) {
        onSubmit(text);
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
  //
  // R388l: when the permission COMMENT BOX is open this
  // widget renders the COMMENT FIELD, not the prompt. The
  // prompt draft is preserved untouched (the user can go
  // back to it after the decision).
  const commentLines = (commentBox?.text ?? "").split("\n");
  const lines = commentBox ? commentLines : state.input.split("\n");
  const promptChar = commentBox ? "✎" : decisionPending ? "⚠" : icon.user;
  const promptColor = commentBox ? t.brand : decisionPending ? t.warn : t.accent;
  // R388h: the input is a PILL — a rounded box with the text inside
  // and a send affordance anchored to the right. This mirrors the
  // desktop's `.message-input-wrap` (a rounded textarea with a
  // 32x32 send button bottom-right). It is the ONLY framed element
  // on the centre panel, which is what makes the panel read as a
  // conversation rather than a dashboard.
  // R388m: the dropdown is a child of the input pill, so it gets
  // the SAME width the pill has — the terminal's chat column, minus
  // the pill's own padding and borders.
  const terminalCols = typeof process.stdout.columns === "number" ? process.stdout.columns : 120;
  const completionWidth = Math.max(24, Math.min(72, Math.floor(terminalCols * 0.6)));

  return (
    <Box flexDirection="column">
      {/* spinner strip ABOVE the input. Always visible
          while a query is in flight. */}
      {state.submitting && !decisionPending ? (
        <Box paddingX={1}>
          <Spinner type="dots" />
          <Text color={t.brand}> {runStatus}…</Text>
          <Text dimColor> · type your next prompt below</Text>
        </Box>
      ) : null}
      {/* T-442: the autocomplete dropdown sits ABOVE the input
          pill so it visually points at the text it's completing. */}
      {/* R388m: the dropdown's width follows the TERMINAL, not a
          hard-coded constant. 48 was a guess made when the chat column
          was 48 wide; on a 120-cell terminal the dropdown covered 40% of
          the screen and its right edge did not line up with the input
          pill's. The dropdown is a child of the input, so it should
          share the input's width. */}
      <CompletionDropdown suggestions={suggestions} width={completionWidth} />
      {/* R388l: the comment box header. It names the ARMED DECISION
          the comment will be sent with — the user must be able to see
          what they are about to send before they send it, and the
          armed decision is not the same thing as the default (deny).
          The control keys come from the ONE registry
          (`componentKeys.ts`), so this component does not hand-write
          a key hint that could drift away from the handler. */}
      {commentBox ? (
        <Box paddingX={1} marginTop={1}>
          <Text color={t.brand} bold>
            ✎ comment to the model
          </Text>
          <Text dimColor> · </Text>
          <Text color={t.warn} bold>
            {armedLabel(commentBox)}
          </Text>
          <Text dimColor> </Text>
          <Text dimColor>{proseFor("components/InputBox.tsx", "comment")}</Text>
        </Box>
      ) : null}
      <Box flexDirection="row" paddingX={1} marginTop={1}>
        <Box
          flexDirection="row"
          flexGrow={1}
          borderStyle="round"
          borderColor={commentBox ? t.brand : decisionPending ? t.warn : t.dim}
          paddingX={1}
        >
          {/* the prompt marker on the first line; the
              continuation lines are indented to align with
              the first character of the user's text. The
              accent character (▌) is the left-edge ribbon
              that matches the StatusBar's visual language. */}
          <Box flexDirection="column">
            <Text color={promptColor}>▌ {promptChar} </Text>
            {lines.length > 1
              ? lines.slice(1).map((line, i) => (
                  <Text key={i} color={promptColor}>
                    {"  "}
                  </Text>
                ))
              : null}
          </Box>
          <Box flexDirection="column" flexGrow={1}>
            {commentBox ? (
              // The comment field. An empty comment shows a
              // placeholder (the user needs to know this box
              // expects text) and the blinking cursor sits on
              // the SAME line, so "where do I type?" has an
              // answer.
              commentBox.text.length === 0 ? (
                <Text>
                  <Text dimColor>your comment reaches the model — e.g. why you are declining</Text>
                  <Text color={promptColor}>{cursorOn ? "▍" : " "}</Text>
                </Text>
              ) : (
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
              )
            ) : decisionPending ? (
              <Text dimColor>{proseFor("components/InputBox.tsx", "decision")}</Text>
            ) : state.input.length === 0 ? (
              // Placeholder + a blinking cursor on the SAME
              // line, in the accent colour, so the user can
              // see "type here" even when the input is
              // empty. Without the cursor, the dim
              // placeholder reads as a static label and
              // the user has no idea where to type.
              <Text>
                <Text dimColor>{proseFor("components/InputBox.tsx", "prompt")}</Text>
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
          {/* the send affordance — the desktop anchors a 32x32
              send button to the bottom-right of the textarea. In
              a terminal we can only render a glyph, so it sits
              on the last line's row, at the right edge. It is
              dimmed when there is nothing to send, so it never
              promises an action that would do nothing. */}
          <Box flexDirection="column" justifyContent="flex-end">
            <Text
              color={
                commentBox
                  ? commentBox.text.trim().length > 0
                    ? t.accent
                    : t.dim
                  : state.input.length > 0 && !decisionPending
                    ? t.accent
                    : t.dim
              }
            >
              ↵
            </Text>
          </Box>
        </Box>
      </Box>
    </Box>
  );
};
