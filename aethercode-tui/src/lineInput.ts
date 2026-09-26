/**
 * Custom line-mode input handler. Replaces Node's built-in
 * `readline.Interface` for the `--line` renderer.
 *
 * Why a custom handler instead of `readline`?
 *
 * `readline`'s internal `_refreshLine` relies on the terminal being able
 * to move the cursor up N rows and overwrite rows reliably. In cmd.exe
 * (Windows' built-in console), the cursor-up sequence is unreliable for
 * text that wraps to multiple rows — especially when the text contains
 * multi-byte characters (CJK). The result: pressing backspace on a
 * long Chinese input leaves the wrap continuations visible and the
 * new rendering stacks on top, producing "ghost rows" that look like
 * duplicated lines.
 *
 * This handler reads stdin in raw mode, keeps the buffer + cursor in
 * JavaScript, and re-renders the whole input area on every change. It
 * counts visual cells (CJK chars take 2 columns) so wrap math is
 * accurate for any language. It deliberately uses `readline.cursorTo`
 * + `readline.clearLine` (which wrap the right ANSI codes for the
 * underlying terminal) instead of raw escape sequences where possible,
 * and falls back to full-row erase + cursor movement where needed.
 *
 * R351.
 */

import * as readline from "node:readline";
import { createRequire } from "node:module";
// `require` here is only used as a lazy loader for koffi on Windows — see
// enableVirtualTerminal(). We can't `import koffi from "koffi"` at the top
// of an ESM module because that would fire at module init and throw
// `ERR_MODULE_NOT_FOUND` on platforms where koffi isn't installed (e.g.
// Linux/macOS users who skip our optional dep). createRequire gives us a
// plain CommonJS require() bound to this file's location, so we can
// resolve koffi on demand only after the platform guard has passed.
const nodeRequire = createRequire(import.meta.url);
// R353c/R354/R354b: koffi gives us a pure-JS FFI to kernel32.dll's
// SetConsoleMode on Windows. We need this because:
//   - cmd.exe (and Windows PowerShell) start every child process
//     with VT mode stripped on stdout AND with echo+line+processed
//     ON on stdin — they deliberately disable ENABLE_VIRTUAL_TERMINAL_*
//     to avoid confusing old apps that emit raw control chars.
//   - We can't fix this from outside the process: even if a sibling
//     powershell.exe spawned from Node.js calls SetConsoleMode, it
//     modifies *its own* console handle (per-process state), not
//     Node.js's. The fix has to happen inside Node.js.
//   - Spawning powershell.exe from Node.js to modify Node.js's
//     handle is impossible — kernel32 only lets a process modify
//     *its own* console mode; there's no cross-process API.
//   - Node.js exposes no SetConsoleMode binding.
// koffi ships prebuilt Windows binaries (no gyp/VS toolchain
// needed) and lets us call kernel32 directly. We call it on BOTH
// STDOUT (enable VT processing for CUP/ESC[J/SGR) and STDIN
// (clear ENABLE_ECHO_INPUT / LINE_INPUT / PROCESSED_INPUT so arrow
// keys don't echo as ^[A/^[B). We deliberately do NOT enable
// ENABLE_VIRTUAL_TERMINAL_INPUT on STDIN — that flag re-introduces
// canonical line-buffer semantics on Windows and silently breaks
// Enter. See enableVirtualTerminal() for the long version.
//
// Cross-platform notes (R354):
//   - kernel32.dll only exists on Windows. koffi.load("kernel32.dll")
//     would throw on Linux/macOS, so the call is gated by
//     process.platform === "win32" below.
//   - On Linux/macOS modern terminals (xterm, gnome-terminal, iTerm2,
//     alacritty, kitty) support VT100 CSI sequences natively — there is
//     no equivalent of SetConsoleMode to call, and none is needed.
//   - koffi is declared as an optionalDependency in package.json so
//     `npm install` on Linux/macOS tolerates the koffi prebuilt being
//     absent. The lazy require below catches that case gracefully.

export interface LineInputCallbacks {
  /** Called when the user submits a line (presses Enter). */
  onSubmit: (text: string) => void;
  /** Called when the user requests close (Ctrl+C / Ctrl+D on empty input). */
  onClose: () => void;
}

/** Visual cell width for the most common CJK + emoji ranges.
 *  Anything not listed here counts as 1. The values match the
 *  East Asian Width property used by terminals. */
function isWide(cp: number): boolean {
  return (
    (cp >= 0x1100 && cp <= 0x115F) ||
    (cp >= 0x2329 && cp <= 0x232A) ||
    (cp >= 0x2E80 && cp <= 0x303E) ||
    (cp >= 0x3041 && cp <= 0x33FF) ||
    (cp >= 0x3400 && cp <= 0x4DBF) ||
    (cp >= 0x4E00 && cp <= 0x9FFF) ||
    (cp >= 0xA000 && cp <= 0xA4CF) ||
    (cp >= 0xAC00 && cp <= 0xD7A3) ||
    (cp >= 0xF900 && cp <= 0xFAFF) ||
    (cp >= 0xFE30 && cp <= 0xFE4F) ||
    (cp >= 0xFF00 && cp <= 0xFF60) ||
    (cp >= 0xFFE0 && cp <= 0xFFE6) ||
    (cp >= 0x1F300 && cp <= 0x1F64F) ||
    (cp >= 0x1F900 && cp <= 0x1F9FF) ||
    (cp >= 0x20000 && cp <= 0x2FFFD) ||
    (cp >= 0x30000 && cp <= 0x3FFFD)
  );
}

/** Strip ANSI escape sequences so visible-cell math isn't fooled by colour. */
function stripAnsi(s: string): string {
  return s.replace(/\x1b\[[0-9;]*m/g, "");
}

/** Visible cell count of a string, accounting for CJK + emoji width. */
function cellWidth(s: string): number {
  let w = 0;
  for (let i = 0; i < s.length; i++) {
    const cp = s.codePointAt(i)!;
    w += isWide(cp) ? 2 : 1;
    if (cp > 0xFFFF) i++; // surrogate pair advances by 2
  }
  return w;
}

/** Walk back from `pos` (a JS string index, not a cell offset) to the
 *  start of the code point that ends at `pos`. Backspace should never
 *  chop a surrogate pair in half. */
function prevCharBoundary(s: string, pos: number): number {
  if (pos <= 1) return 0;
  const c1 = s.charCodeAt(pos - 1);
  if (c1 >= 0xDC00 && c1 <= 0xDFFF && pos >= 2) {
    const c2 = s.charCodeAt(pos - 2);
    if (c2 >= 0xD800 && c2 <= 0xDBFF) return pos - 2;
  }
  return pos - 1;
}

export class LineInput {
  private buffer = "";
  private cursor = 0; // JS string index, not cell offset
  private prompt = "❯ ";
  private isClosedFlag = false;
  private listener?: (chunk: Buffer) => void;
  private started = false;

  // R353: the input area's top row on screen (1-based). When non-zero,
  // render() positions the cursor there with `\x1b[<row>;<col>H` (CUP)
  // and clears from cursor to end-of-screen before re-painting. This
  // replaces the R351b `\x1b[s` / `\x1b[u` (DECSC/DECRC) save/restore
  // strategy, which proved unreliable on cmd.exe — the saved anchor
  // would silently shift over time and a render() that "restored" to
  // the anchor would leave dozens of ghost rows of old wrapped text
  // above the prompt. CUP (Cursor Position) is in the VT100 core that
  // Windows 10 1607+ supports reliably, and by holding the row in
  // JavaScript we never depend on the terminal remembering it.
  //
  // 0 means "unknown" — render() falls back to a single-line write at
  // the current cursor position (matches the R351 pipe-mode path).
  // Callers should call `setInputTopRow(N)` before `start()` when the
  // terminal position is known (line.ts does this after splash).
  private inputTopRow = 0;

  constructor(
    private cbs: LineInputCallbacks,
    private isTty: boolean,
  ) {}

  setPrompt(p: string): void {
    this.prompt = p;
  }

  /**
   * R353c/R354/R354b/R354c/R354e: enable ENABLE_VIRTUAL_TERMINAL_PROCESSING
   * on this process's stdout handle, AND explicitly disable echo /
   * line / processed on the stdin handle. See the module-level koffi
   * note for the long version of why this is needed.
   *
   * R354c: also writes a one-line diagnostic to a log file on every
   * invocation so we can confirm from outside the process whether
   * koffi loaded, what mode bits were set, and whether SetConsoleMode
   * actually took effect. The log is at `D:\tmp\ac-tui-console.log`
   * and is appended (not overwritten) so each invocation adds a row.
   * The path is hard-coded for now; future work could route this
   * through a debug env var.
   *
   * Cross-platform behavior:
   *   - Windows + TTY: lazy-load koffi, call kernel32.SetConsoleMode
   *     on both STD_OUTPUT_HANDLE and STD_INPUT_HANDLE. Log a row.
   *   - Windows + non-TTY (pipe): log "skipped: not TTY" and return.
   *   - Linux/macOS (any TTY): log "skipped: not win32" and return.
   *   - Windows but koffi prebuilt missing or load fails: log the
   *     failure (koffi load, GetStdHandle, SetConsoleMode) and
   *     continue without the fix. This is intentional — we want to
   *     see exactly which step broke instead of silently failing.
   *
   * Calling this more than once is harmless (the bits are idempotent).
   * The picker also calls this on every activate() to re-assert raw
   * mode after lineInput.pause() may have flipped it back.
   */
  private enableVirtualTerminal(): void {
    const logPath = "D:\\tmp\\ac-tui-console.log";
    const log = (msg: string): void => {
      try {
        require("node:fs").appendFileSync(
          logPath,
          `[${new Date().toISOString()}] ${msg}\n`,
        );
      } catch { /* logging itself can't fail closed */ }
    };

    if (process.platform !== "win32") {
      log(`skipped: not win32 (platform=${process.platform})`);
      return;
    }
    if (!process.stdout.isTTY) {
      log(`skipped: stdout.isTTY=false`);
      return;
    }
    log(`enter: pid=${process.pid}`);

    // Lazy-load koffi *only* on Windows, *after* the platform guard.
    let koffi: typeof import("koffi") | undefined;
    try {
      // The dynamic require is string-literal so esbuild still
      // externalizes it (koffi stays in node_modules, not bundled).
      koffi = nodeRequire("koffi") as typeof import("koffi");
      log(`koffi loaded: ${typeof koffi}`);
    } catch (e) {
      log(`koffi load FAILED: ${(e as Error).message}`);
      return;
    }

    try {
      const kernel32 = koffi.load("kernel32.dll");
      const GetStdHandle = kernel32.func("GetStdHandle", "void*", ["uint32"]);
      const GetConsoleMode = kernel32.func("GetConsoleMode", "bool", ["void*", "uint32*"]);
      const SetConsoleMode = kernel32.func("SetConsoleMode", "bool", ["void*", "uint32"]);
      const modePtr = Buffer.alloc(4); // 32-bit mode word

      // (1) STDOUT: turn on VT processing so CUP / ESC[J / SGR land.
      //
      // R355: previous versions did `before | 0x0004` and skipped
      // SetConsoleMode when `after === before`. That's a no-op when
      // GetConsoleMode already reports 0x7 (PROCESSED_OUTPUT |
      // WRAP_AT_EOL_OUTPUT | VT_PROCESSING) — but conhost only
      // ACTIVATES VT processing when SetConsoleMode is *called*, not
      // just when the flag is set in the mode word. In cmd.exe +
      // conhost, the symptom of skipping the call is: CUP `ESC[R;CH`
      // silently does nothing and the picker ends up appending each
      // render to the bottom of the screen ("press down, see another
      // row appear below the previous one" — see R355 user feedback).
      // We force the call by always writing the canonical 0x7 mask,
      // which is idempotent in effect but exercises the syscall.
      //
      // -11 = STD_OUTPUT_HANDLE per WinBase.h.
      const hOut = GetStdHandle(0xFFFFFFF5 /* (uint32)-11 */);
      if (!hOut) {
        log(`STDOUT GetStdHandle returned NULL`);
      } else if (GetConsoleMode(hOut, modePtr)) {
        const before = modePtr.readUInt32LE(0);
        // Canonical STDOUT mask for VT processing:
        //   0x0001 ENABLE_PROCESSED_OUTPUT  (required for VT per docs)
        //   0x0002 ENABLE_WRAP_AT_EOL_OUTPUT
        //   0x0004 ENABLE_VIRTUAL_TERMINAL_PROCESSING
        const TARGET = 0x0001 | 0x0002 | 0x0004;
        // Always call SetConsoleMode — toggling VT off and on forces
        // conhost to actually apply VT processing even when the
        // mode word already contains 0x4. The 2-call dance (clear,
        // then set to TARGET) ensures conhost sees a real transition.
        SetConsoleMode(hOut, before & ~0x0004);
        const ok = SetConsoleMode(hOut, TARGET);
        log(`STDOUT: before=0x${before.toString(16)} forced=0x${TARGET.toString(16)} setConsoleMode=${ok}`);
        if (GetConsoleMode(hOut, modePtr)) {
          const verify = modePtr.readUInt32LE(0);
          log(`STDOUT: verify=0x${verify.toString(16)} (${(verify & 0x0007) === TARGET ? "OK" : "MISMATCH"})`);
        }
      }

      // (2) STDIN: explicitly disable echo / line / processed AND VT_INPUT.
      //
      // History:
      //   R354c cleared PROCESSED|LINE|ECHO. That mostly worked but
      //     `^[B^[B` still floated to the bottom of the picker because
      //     Node's setRawMode(true) on Windows (Node 18+, libuv
      //     PR #4688 / commit 843b64f, landed Jan 2025) calls the new
      //     UV_TTY_MODE_RAW_VT which sets ENABLE_VIRTUAL_TERMINAL_INPUT
      //     (0x200). When that bit is on, conhost in VT-aware mode
      //     actively translates arrow-key VK_* events into VT escape
      //     sequences AND writes those bytes back to the screen buffer
      //     (it is NOT a regular echo — ECHO_INPUT stays OFF — it is
      //     a VT-translation side effect that only fires when VT_INPUT
      //     is on). The picker mode leaves STDIN at 0x208 (WINDOW_INPUT
      //     + VT_INPUT), so ECHO_INPUT is OFF but the screen still
      //     gets `[B` rendered at the cursor position.
      //   R354e removed `| 0x0200` from `after` to avoid a different
      //     regression: R354b had `| 0x200` AND still had LINE_INPUT
      //     left in `before`, so the resulting state was
      //     "LINE_INPUT on + VT_INPUT on" — Microsoft's docs say VT_INPUT
      //     implicitly re-enables canonical line-editing semantics,
      //     and that combination broke Enter (libuv stopped emitting
      //     the `return` keypress). R354e's `& ~COOKED_MASK` left VT_INPUT
      //     alone, so when VT_INPUT was already on (Node 24's
      //     UV_TTY_MODE_RAW_VT path) it stayed on — and the `[B` cosmetic
      //     bug came back.
      //   R355: add VT_INPUT (0x200) to the bits we clear. With both
      //     `before = 0x208` (after Node setRawMode(true)) and
      //     `before = 0x7` (cooked default), `after = before & ~COOKED_MASK`
      //     is 0x0 — fully raw, no VT translation, no line editor.
      //     libuv's UV_TTY_MODE_RAW_VT still translates VK_UP/DOWN/etc.
      //     to CSI sequences itself (that path is independent of the
      //     STDIN console mode flag, per libuv commit message), so we
      //     still receive `\x1b[A` / `\x1b[B` in the data event —
      //     just without the duplicate conhost write-back to the screen.
      //
      // -10 = STD_INPUT_HANDLE per WinBase.h.
      const hIn = GetStdHandle(0xFFFFFFF6 /* (uint32)-10 */);
      if (!hIn) {
        log(`STDIN GetStdHandle returned NULL`);
      } else if (GetConsoleMode(hIn, modePtr)) {
        const before = modePtr.readUInt32LE(0);
        // Clear PROCESSED | LINE | ECHO | VT_INPUT. The 0x200 bit is
        // what Node 24's libuv sets in UV_TTY_MODE_RAW_VT — clearing
        // it here stops conhost from translating arrow keys back to
        // the screen while we're in raw mode.
        const COOKED_MASK = 0x0001 | 0x0002 | 0x0004 | 0x0200;
        const after = before & ~COOKED_MASK;
        if (after !== before) {
          const ok = SetConsoleMode(hIn, after);
          log(`STDIN:  before=0x${before.toString(16)} after=0x${after.toString(16)} setConsoleMode=${ok}`);
          // Verify the write actually took effect (some conhost
          // builds silently swallow the call).
          if (GetConsoleMode(hIn, modePtr)) {
            const verify = modePtr.readUInt32LE(0);
            log(`STDIN:  verify=0x${verify.toString(16)} (${verify === after ? "OK" : "MISMATCH"})`);
          }
        } else {
          log(`STDIN:  already 0x${before.toString(16)}, no change`);
        }
      }
      log(`done`);
    } catch (e) {
      log(`SetConsoleMode block FAILED: ${(e as Error).message}`);
    }
  }

  /** Get the current input text (for slash-command detection etc.). */
  getBuffer(): string {
    return this.buffer;
  }

  /** Set the absolute screen row (1-based) where the input area starts.
   *  R353: render() positions itself here with CUP. Pass the row that
   *  the input area should occupy AFTER any preceding output (splash,
   *  log lines, etc.). Used by line.ts right after writing splash. */
  setInputTopRow(row: number): void {
    if (row > 0) this.inputTopRow = row;
  }

  /** Current absolute row of the input area's top. 0 if unknown. */
  getInputTopRow(): number {
    return this.inputTopRow;
  }

  /** The input area has just been pushed down by N screen rows (because
   *  logAbove wrote N new lines above it). Bumps the cached row so the
   *  next render() lands in the right place. */
  shiftDown(n: number): void {
    if (n > 0 && this.inputTopRow > 0) this.inputTopRow += n;
  }

  /** Programmatically clear the input. Used by `/clear` etc. */
  clearBuffer(): void {
    this.buffer = "";
    this.cursor = 0;
    if (this.started) this.render();
  }

  /** Begin listening to stdin. Safe to call once after construction. */
  start(): void {
    if (this.isClosedFlag || this.started) return;
    this.started = true;
    // R353b: see enableVirtualTerminal() — without this, CUP / clear /
    // SGR are silently dropped on cmd.exe and our renders go to the
    // wrong cursor position. The flag is per-process; once enabled it
    // stays on for the rest of the session.
    this.enableVirtualTerminal();
    if (this.isTty) {
      try {
        (process.stdin as unknown as { setRawMode: (b: boolean) => void }).setRawMode(true);
      } catch { /* ignore */ }
      process.stdin.resume();
    }
    this.listener = (chunk: Buffer) => this.handleData(chunk);
    process.stdin.on("data", this.listener);
    // R353: no more `\x1b[s` here. The anchor is now a JavaScript
    // variable (`inputTopRow`) and render() positions the cursor with
    // CUP rather than relying on the terminal to remember a saved
    // cursor. See inputTopRow declaration above for the rationale.
    this.render();
  }

  /** Stop listening but keep the object reusable for the next prompt. */
  stop(): void {
    if (this.listener) {
      process.stdin.removeListener("data", this.listener);
      this.listener = undefined;
    }
    if (this.isTty) {
      try {
        (process.stdin as unknown as { setRawMode: (b: boolean) => void }).setRawMode(false);
      } catch { /* ignore */ }
    }
    this.started = false;
  }

  /**
   * Temporarily detach our stdin listener so another component
   * (e.g. `readOneChar` for permission prompts) can take over.
   * `resume()` re-attaches. While paused, typing does not feed the
   * input buffer.
   */
  pause(): void {
    this.stop();
  }

  resume(): void {
    if (this.isClosedFlag) return;
    this.start();
  }

  /** Permanently shut down (Ctrl+C, EOF, /exit). */
  close(): void {
    this.stop();
    this.isClosedFlag = true;
  }

  isActive(): boolean {
    return this.started;
  }

  /**
   * R354c: re-apply VT / raw-mode console flags on Windows. Callers
   * that temporarily take over stdin (pickers, read-one-char prompts,
   * etc.) should invoke this AFTER their own setRawMode(true) so the
   * explicit STDIN/ECHO/LINE/PROCESSED bits we set via koffi win
   * out against anything Node's setRawMode may have re-toggled.
   * On non-Windows or non-TTY this is a no-op.
   */
  ensureRawMode(): void {
    this.enableVirtualTerminal();
  }

  /** Repaint the current buffer (used after logAbove / notifications). */
  redraw(): void {
    if (!this.started) return;
    // R353: just render() — CUP handles positioning. The caller is
    // responsible for bumping `inputTopRow` via shiftDown() if it wrote
    // new lines above (writeAbove() does this automatically).
    this.render();
  }

  /** R353: write a block of text ABOVE the input area, then redraw the
   *  prompt at the new (pushed-down) position. This is the only
   *  supported way to surface "log" output from line.ts; calling
   *  `process.stdout.write` directly leaves stale input on screen.
   *
   *  Algorithm:
   *    1. CUP to inputTopRow, col 1  (move to input area's top)
   *    2. clear-to-end-of-screen     (wipe old prompt + input)
   *    3. write "\n" + text + "\n"   (push the new log out)
   *    4. bump inputTopRow by N+1    (account for leading \n + N log rows)
   *    5. render() at the new row    (paint fresh prompt + input)
   *
   *  `text` may itself contain "\n" — its line count is honored.
   */
  writeAbove(text: string): void {
    if (!this.isTty) {
      process.stdout.write(text + "\n");
      return;
    }
    const lines = text.split("\n").length;
    const knownRow = this.inputTopRow > 0;
    if (knownRow) {
      process.stdout.write(`\x1b[${this.inputTopRow};1H`);
      process.stdout.write("\x1b[J");
    }
    process.stdout.write("\n" + text + "\n");
    if (knownRow) {
      // +1 for the leading "\n" we just wrote that pushes the cursor
      // down one row before the text begins.
      this.inputTopRow += lines + 1;
      this.render();
    }
  }

  // ─── internal ──────────────────────────────────────────────────────────

  private handleData(chunk: Buffer): void {
    // Decode as UTF-8. Multi-byte chars come through as multiple JS chars
    // in surrogate pairs; we treat each codepoint as one logical char.
    const text = chunk.toString("utf8");
    let i = 0;
    while (i < text.length) {
      const code = text.charCodeAt(i);

      // CR / LF → Enter.
      if (code === 0x0D || code === 0x0A) {
        this.stop();
        process.stdout.write("\r\n");
        const submitted = this.buffer;
        this.buffer = "";
        this.cursor = 0;
        try {
          this.cbs.onSubmit(submitted);
        } catch (e) {
          process.stderr.write(`lineInput.onSubmit threw: ${(e as Error).message}\n`);
        }
        // R353: no `\x1b[s` here. onSubmit may have called writeAbove
        // (logAbove) which already bumped inputTopRow via shiftDown
        // and re-rendered the prompt. If not, start() → render() will
        // paint the prompt at inputTopRow (unchanged), which is the
        // same row as the previous prompt — correct, because the user
        // pressed Enter on the same prompt they typed into.
        if (!this.isClosedFlag) this.start();
        return;
      }

      // Ctrl+C → close.
      if (code === 0x03) {
        this.stop();
        process.stdout.write("^C\r\n");
        this.buffer = "";
        this.cursor = 0;
        try { this.cbs.onClose(); } catch { /* swallow */ }
        return;
      }

      // Ctrl+D → close if buffer empty, else ignore (mac/linux send 0x04,
      // windows conhost sends it too. We treat it as EOF-on-empty).
      if (code === 0x04) {
        if (this.buffer.length === 0) {
          this.stop();
          process.stdout.write("\r\n");
          try { this.cbs.onClose(); } catch { /* swallow */ }
        }
        i++;
        continue;
      }

      // Backspace / DEL. Real terminals send 0x08 (BS) or 0x7F (DEL);
      // cmd.exe usually sends 0x08 for Backspace and 0x7F for Delete.
      // We treat both as backspace for line editing.
      if (code === 0x08 || code === 0x7F) {
        if (this.cursor > 0) {
          const prev = prevCharBoundary(this.buffer, this.cursor);
          this.buffer = this.buffer.slice(0, prev) + this.buffer.slice(this.cursor);
          this.cursor = prev;
          this.render();
        }
        i++;
        continue;
      }

      // ESC: consume one full CSI sequence if present, otherwise ignore.
      // Arrow keys send ESC [ A/B/C/D — we don't act on them yet.
      if (code === 0x1B) {
        i++;
        if (text[i] === "[") {
          i++;
          while (i < text.length && text.charCodeAt(i) < 0x40) i++;
          if (i < text.length) i++;
        }
        continue;
      }

      // Printable + multi-byte. Insert at cursor.
      if (code >= 0x20 || code === 0x09) {
        const cp = text.codePointAt(i)!;
        const charLen = cp > 0xFFFF ? 2 : 1;
        const ch = text.substr(i, charLen);
        this.buffer = this.buffer.slice(0, this.cursor) + ch + this.buffer.slice(this.cursor);
        this.cursor += charLen;
        this.render();
        i += charLen;
        continue;
      }

      // Other control chars (Ctrl+E etc.) — ignore.
      i++;
    }
  }

  private render(): void {
    if (!this.isTty) {
      // Pipe mode: just append. Caller is responsible for layout.
      process.stdout.write(this.prompt + this.buffer);
      return;
    }

    // R353: CUP-based absolute positioning replaces the R351b
    // save/restore anchor. We hold the input area's top row in
    // JavaScript and use `\x1b[<row>;<col>H` (Cursor Position, the
    // VT100 standard) to move there before clearing + repainting.
    //
    // Why this is more robust than R351b:
    //   - `\x1b[s` / `\x1b[u` (DECSC/DECRC) save/restore has only
    //     one slot per terminal — any other save clobbers it. On
    //     Windows 10 1607+ the implementation is also patchy (some
    //     builds report the cursor as one row off after restore,
    //     which over many renders accumulates into a multi-row
    //     drift — the source of the "30+ rows of duplicated text"
    //     bug that survived R351b).
    //   - CUP (\x1b[<r>;<c>H) is in the VT100 core and supported
    //     reliably on every terminal that does VT (cmd.exe on
    //     Windows 10+, Windows Terminal, iTerm, Linux/macOS).
    //   - Holding the row in JS means we never depend on terminal
    //     state; logAbove() / writeAbove() bump the row exactly
    //     once per line written, keeping render() deterministic.
    //
    // Fallback: if inputTopRow is still 0 (no caller told us where
    // we are), we paint at the current cursor. The caller is then
    // responsible for positioning before the first render.
    if (this.inputTopRow <= 0) {
      process.stdout.write(this.prompt + this.buffer);
      return;
    }

    // Step 1: CUP to the input area's top-left.
    process.stdout.write(`\x1b[${this.inputTopRow};1H`);

    // Step 2: clear from cursor to end-of-screen — wipes the
    // previous prompt + wrapped input rows.
    process.stdout.write("\x1b[J");

    // Step 3: write the new prompt + buffer.
    process.stdout.write(this.prompt + this.buffer);

    // Step 4: CUP to the logical insert position (where the next
    // character will go). This must be expressed in absolute row /
    // col coordinates, because we want the cursor to land in the
    // middle of a freshly-painted line — relying on \x1b[1A within
    // the painted area is reliable on cmd.exe, but we already have
    // CUP, so use CUP directly to keep things uniform.
    const termWidth = process.stdout.columns || 80;
    const promptCells = cellWidth(stripAnsi(this.prompt));
    const cursorCells = promptCells + cellWidth(this.buffer.slice(0, this.cursor));
    const targetRow = Math.floor(cursorCells / termWidth);
    const targetCol = cursorCells % termWidth;
    process.stdout.write(
      `\x1b[${this.inputTopRow + targetRow};${targetCol + 1}H`,
    );
  }
}