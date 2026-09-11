/**
 * T-442 (Phase 5 R5): fuzzy autocomplete for the InputBox.
 *
 * Ported from deepagents-code's `Autocomplete` widget
 * (`deepagents_code.tui.widgets.autocomplete`). The
 * upstream exposes two controllers:
 *
 *   1. SlashCommandController — triggered when the line
 *      starts with `/`. The host injects a CommandEntry
 *      list (name + description) and the controller
 *      filters by name prefix (case-insensitive).
 *
 *   2. FuzzyFileController — triggered when the line
 *      starts with `@`. The host injects a file-list
 *      supplier and the controller does a `contains`
 *      match on the prefix.
 *
 * The TUI port keeps the same public surface
 * (`MultiCompletionManager`, `Suggestions`,
 * `Suggestion`, `CompletionResult`) and adds:
 *
 *   - React-renderable `CompletionDropdown` for the
 *     ink UI.
 *   - "fuzzy" mode in the file controller (matches
 *     every prefix subsequence, not just `contains`).
 *
 * The component is intentionally pure / no React state
 * — the host (InputBox) holds the active `Suggestions`
 * and renders the dropdown.
 */

import React from "react";
import { Box, Text } from "ink";

/** Result of handling a key event in the completion system. */
export type CompletionResult = "ignored" | "handled" | "submit";

/** A single suggestion row. */
export interface Suggestion {
  /** Visible label (e.g. "/help", "src/utils.ts"). */
  label: string;
  /** Optional second line (e.g. command description). */
  description?: string;
}

/** A snapshot of the controller's current state. */
export interface Suggestions {
  suggestions: Suggestion[];
  selectedIndex: number;
}

export const EMPTY_SUGGESTIONS: Suggestions = { suggestions: [], selectedIndex: 0 };

/** A controller that maps a trigger (`/` or `@`) to a list of suggestions. */
export interface CompletionController {
  /** Whether this controller can handle the current input state. */
  canHandle(text: string, cursorIndex: number): boolean;
  /** Refresh the suggestion list after an input change. */
  onTextChanged(text: string, cursorIndex: number): void;
  /** Handle a key event and return how it was handled. */
  onKey(key: string, text: string, cursorIndex: number): CompletionResult;
  /** Reset the controller's state. */
  reset(): void;
  /** The current suggestions and selected index. */
  current(): Suggestions;
}

/** Helper: return the start index of the current line
 *  (the position of the first char after the previous
 *  newline, or 0 if there is no newline). */
function lineStart(text: string, cursorIndex: number): number {
  if (cursorIndex <= 0) return 0;
  const head = text.lastIndexOf("\n", cursorIndex - 1);
  return head < 0 ? 0 : head + 1;
}

/** Slash-command controller. Hosts inject the
 *  {@link SlashCommand} list. */
export interface SlashCommand {
  name: string;
  description?: string;
}

export class SlashCommandController implements CompletionController {
  private commands: SlashCommand[];
  private suggestions: Suggestions = EMPTY_SUGGESTIONS;

  constructor(commands: SlashCommand[] = []) {
    this.commands = commands;
  }

  canHandle(text: string, cursorIndex: number): boolean {
    if (!text) return false;
    const start = lineStart(text, cursorIndex);
    if (start >= text.length || text[start] !== "/") return false;
    // Only fire on the first token of the line (so a
    // body of "see /tmp/foo" doesn't trigger).
    const space = text.indexOf(" ", start);
    return space < 0 || space >= cursorIndex;
  }

  onTextChanged(text: string, cursorIndex: number): void {
    if (!this.canHandle(text, cursorIndex)) {
      this.suggestions = EMPTY_SUGGESTIONS;
      return;
    }
    const start = lineStart(text, cursorIndex);
    const prefix = text.substring(start + 1, cursorIndex).toLowerCase();
    const matches: Suggestion[] = [];
    for (const cmd of this.commands) {
      if (cmd.name.toLowerCase().startsWith(prefix)) {
        matches.push({ label: cmd.name, description: cmd.description });
      }
    }
    // Cap at 8 to keep the dropdown short.
    this.suggestions = { suggestions: matches.slice(0, 8), selectedIndex: 0 };
  }

  onKey(_key: string, _text: string, _cursorIndex: number): CompletionResult {
    // The host (InputBox) consumes Tab / ↑ / ↓ / Enter
    // against the active controller via the dedicated
    // navigation helpers below. The controller itself
    // never needs to do its own key dispatch.
    return "ignored";
  }

  reset(): void {
    this.suggestions = EMPTY_SUGGESTIONS;
  }

  current(): Suggestions {
    return this.suggestions;
  }

  /** Move the selection up / down / to-top / to-bottom.
   *  Returned separately so the host can call it from
   *  the keybinding handler without needing to know
   *  about the active controller. */
  move(delta: number): void {
    const { suggestions, selectedIndex } = this.suggestions;
    if (suggestions.length === 0) return;
    const n = suggestions.length;
    this.suggestions = {
      suggestions,
      selectedIndex: (selectedIndex + delta + n) % n,
    };
  }

  /** Pick the current selection; returns null if
   *  nothing is selected. */
  pick(): Suggestion | null {
    const { suggestions, selectedIndex } = this.suggestions;
    if (suggestions.length === 0) return null;
    return suggestions[selectedIndex] ?? null;
  }
}

/** Fuzzy file controller. Hosts inject the file-list
 *  supplier. */
export class FuzzyFileController implements CompletionController {
  private supplier: () => string[];
  private suggestions: Suggestions = EMPTY_SUGGESTIONS;
  private rangeStart: number = 0;
  private rangeEnd: number = 0;

  constructor(supplier: () => string[] = () => []) {
    this.supplier = supplier;
  }

  canHandle(text: string, cursorIndex: number): boolean {
    if (!text) return false;
    const start = lineStart(text, cursorIndex);
    return start < text.length && text[start] === "@";
  }

  onTextChanged(text: string, cursorIndex: number): void {
    if (!this.canHandle(text, cursorIndex)) {
      this.suggestions = EMPTY_SUGGESTIONS;
      this.rangeStart = 0;
      this.rangeEnd = 0;
      return;
    }
    const start = lineStart(text, cursorIndex);
    this.rangeStart = start;
    this.rangeEnd = cursorIndex;
    const prefix = (cursorIndex > start + 1)
      ? text.substring(start + 1, cursorIndex).toLowerCase()
      : "";
    const files = this.supplier();
    const matches: Suggestion[] = [];
    if (!prefix) {
      // No prefix — show the first 8 files.
      for (const f of files.slice(0, 8)) matches.push({ label: f });
    } else {
      // Fuzzy: every char of the prefix must appear in
      // order in the file path. Case-insensitive.
      for (const f of files) {
        if (fuzzyMatch(f.toLowerCase(), prefix)) {
          matches.push({ label: f });
          if (matches.length >= 8) break;
        }
      }
    }
    this.suggestions = { suggestions: matches, selectedIndex: 0 };
  }

  onKey(_key: string, _text: string, _cursorIndex: number): CompletionResult {
    return "ignored";
  }

  reset(): void {
    this.suggestions = EMPTY_SUGGESTIONS;
    this.rangeStart = 0;
    this.rangeEnd = 0;
  }

  current(): Suggestions {
    return this.suggestions;
  }

  /** Range of the text the controller is currently
   *  completing over. The host uses this to splice a
   *  pick back into the input. */
  range(): { start: number; end: number } {
    return { start: this.rangeStart, end: this.rangeEnd };
  }

  move(delta: number): void {
    const { suggestions, selectedIndex } = this.suggestions;
    if (suggestions.length === 0) return;
    const n = suggestions.length;
    this.suggestions = {
      suggestions,
      selectedIndex: (selectedIndex + delta + n) % n,
    };
  }

  pick(): Suggestion | null {
    const { suggestions, selectedIndex } = this.suggestions;
    if (suggestions.length === 0) return null;
    return suggestions[selectedIndex] ?? null;
  }
}

/** Strict-subsequence fuzzy match: every char in
 *  `prefix` must appear in `text` in order, but
 *  characters can be skipped. */
export function fuzzyMatch(text: string, prefix: string): boolean {
  if (!prefix) return true;
  let ti = 0;
  for (let pi = 0; pi < prefix.length; pi++) {
    const c = prefix[pi];
    while (ti < text.length && text[ti] !== c) ti++;
    if (ti >= text.length) return false;
    ti++;
  }
  return true;
}

/** Multi-completion manager. Dispatches the active
 *  controller based on the current input state. */
export class MultiCompletionManager {
  private controllers: CompletionController[] = [];
  private active: CompletionController | null = null;

  add(controller: CompletionController): void {
    this.controllers.push(controller);
  }

  onTextChanged(text: string, cursorIndex: number): void {
    let next: CompletionController | null = null;
    for (const c of this.controllers) {
      if (c.canHandle(text, cursorIndex)) {
        next = c;
        break;
      }
    }
    if (next !== this.active) {
      this.active?.reset();
      this.active = next;
    }
    this.active?.onTextChanged(text, cursorIndex);
  }

  onKey(key: string, text: string, cursorIndex: number): CompletionResult {
    return this.active?.onKey(key, text, cursorIndex) ?? "ignored";
  }

  current(): Suggestions {
    return this.active?.current() ?? EMPTY_SUGGESTIONS;
  }

  activeController(): CompletionController | null {
    return this.active;
  }

  reset(): void {
    this.active?.reset();
    this.active = null;
  }
}

interface DropdownProps {
  suggestions: Suggestions;
  width?: number;
}

/** Render the suggestions as a small dropdown below
 *  the input. Empty `suggestions` renders nothing. */
export const CompletionDropdown: React.FC<DropdownProps> = ({ suggestions, width = 40 }) => {
  if (suggestions.suggestions.length === 0) return null;
  return (
    <Box flexDirection="column" paddingX={1}>
      {suggestions.suggestions.map((s, i) => {
        const isSelected = i === suggestions.selectedIndex;
        const marker = isSelected ? "▶" : " ";
        const labelColor = isSelected ? "black" : "cyan";
        const bg = isSelected ? "cyan" : undefined;
        const label = s.label.length > width - 4
          ? s.label.slice(0, width - 5) + "…"
          : s.label;
        return (
          <Text key={i} backgroundColor={bg} color={labelColor}>
            {marker} {label}
            {s.description ? <Text dimColor>  {s.description.slice(0, width - label.length - 6)}</Text> : null}
          </Text>
        );
      })}
    </Box>
  );
};
