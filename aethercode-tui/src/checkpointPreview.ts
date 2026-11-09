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
 * R388s — the checkpoint PREVIEW.
 *
 * <p>★ WHY THIS EXISTS ★ The rewind menu showed the file NAMES of the
 * selected point, at the BOTTOM of the panel, after the action list. So
 * the user could not see what a point would undo until they had already
 * moved the selection onto it — which is backwards: the preview is
 * what helps you CHOOSE a point.
 *
 * <p>So every point now carries its own footprint, and it is shown
 * INLINE on the point's row.
 *
 * <p>★ AND THE NUMBERS COME FROM THE DAEMON ★ The daemon holds both
 * sides of the comparison — the snapshot it took at that point and the
 * file as it is now — and it is the only place that can answer "what
 * would restoring this point undo?". The renderer never had the
 * snapshot, so it cannot compute this, and a renderer that guesses
 * would guess wrong.
 *
 * <p>★ AND THE COUNTS ARE LINE COUNTS, NOT A DIFF ★ A full diff needs
 * a line-matching algorithm. The daemon has one, but it lives in
 * another module and another language would mean a second copy of it.
 * So the preview reports the honest, computable fact: how many LINES
 * differ. The label says "lines", not "diff", because it is not one.
 *
 * <p>★ AND "UNKNOWN" IS NOT ZERO ★ A binary file, an unreadable path,
 * a file that is now a directory — the restore SKIPS those, so the
 * honest answer is "I do not know". A menu that says "0 lines" for a
 * file it could not read is telling the user a number it does not
 * have, and the user may act on it.
 */

/** One file's preview, as the daemon sent it. */
export interface CheckpointFilePreview {
  /** The file's label. */
  name: string;
  /** The file's current line count (-1 = unknown). */
  linesNow: number;
  /** How many lines differ from the snapshot (-1 = unknown). */
  linesDelta: number;
  /** The session CREATED the file (so a restore deletes it). */
  created: boolean;
  /** Is the preview known? */
  known: boolean;
  /** Why it is unknown, when it is. */
  note?: string | null;
}

/** One checkpoint's preview, as the daemon sent it. */
export interface CheckpointPreview {
  /** The per-file facts. */
  files: CheckpointFilePreview[];
  /** How many files a restore would change. */
  changedFiles: number;
  /** How many lines a restore would change, summed. */
  changedLines: number;
  /** Is every file's preview known? */
  complete: boolean;
}

/**
 * The preview a checkpoint carries, or null when it has none.
 *
 * <p>★ NULL IS NOT "NOTHING WOULD CHANGE" ★ A checkpoint with no
 * preview at all (an older daemon, or a fetch that predates R388s) is
 * a DIFFERENT fact from a checkpoint whose preview says "nothing would
 * change". So the absent case is null, and the caller says so.
 *
 * @param raw the daemon's row
 * @returns the preview, or null
 */
export function checkpointPreviewOf(raw: unknown): CheckpointPreview | null {
  const r = (raw ?? {}) as Record<string, unknown>;
  const arr = Array.isArray(r.preview) ? r.preview : null;
  if (!arr) return null;
  const files: CheckpointFilePreview[] = arr.map((f) => {
    const fr = (f ?? {}) as Record<string, unknown>;
    return {
      name: String(fr.name ?? "?"),
      linesNow: Number(fr.linesNow ?? -1),
      linesDelta: Number(fr.linesDelta ?? -1),
      created: Boolean(fr.created),
      known: Boolean(fr.known),
      note: fr.note == null ? null : String(fr.note),
    };
  });
  return {
    files,
    changedFiles: Number(r.changedFiles ?? 0) || 0,
    changedLines: Number(r.changedLines ?? 0) || 0,
    complete: Boolean(r.previewComplete),
  };
}

/**
 * The one-line summary a checkpoint row shows.
 *
 * <p>★ THE SHAPE IS THE POINT ★ A point's row has room for a summary
 * and a footprint, and the footprint is what the preview is FOR. So it
 * reads like a number, in the same colours as the file-changes card:
 * green for what would come back, red for what would go.
 *
 * <p>★ AND "NO CHANGE" IS ITS OWN WORDS ★ A point where nothing would
 * change says so, rather than showing "0 files 0 lines" — which looks
 * like a measurement of nothing rather than a statement about the
 * point.
 *
 * @param preview the checkpoint's preview, or null
 * @returns the line to show, or null when there is nothing to say
 */
export function previewSummaryLine(preview: CheckpointPreview | null): string | null {
  if (!preview) return null;
  if (preview.changedFiles === 0 && preview.changedLines === 0) {
    // The preview may be incomplete — then "no change" is not something
    // we can claim, and the incompleteness has to show.
    return preview.complete ? "no change" : "no change (some files unknown)";
  }
  const parts = [`${preview.changedFiles} file${preview.changedFiles === 1 ? "" : "s"}`];
  if (preview.changedLines > 0) parts.push(`~${preview.changedLines} lines`);
  if (!preview.complete) parts.push("some unknown");
  return parts.join(" · ");
}

/**
 * The per-file line the detail pane shows.
 *
 * @param file the file's preview
 * @returns the line to show
 */
export function previewFileLine(file: CheckpointFilePreview): string {
  if (!file.known) {
    return `${file.name} — unknown (${file.note ?? "not restorable"})`;
  }
  if (file.created) {
    return file.linesNow > 0
      ? `${file.name} — would be deleted (${file.linesNow} lines)`
      : `${file.name} — would be deleted`;
  }
  if (file.linesDelta === 0) {
    return `${file.name} — unchanged`;
  }
  return `${file.name} — ~${file.linesDelta} lines differ (now ${file.linesNow})`;
}
