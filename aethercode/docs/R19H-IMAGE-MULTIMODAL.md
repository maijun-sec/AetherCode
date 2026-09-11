# R19-H — Image / Multimodal Support

**Date**: 2026-08-06
**Status**: DONE — 1208 tests (+3 FileReadToolTest for images, +6 BashToolTest from R19-G), 0 net regression
**Goal**: Let the `file_read` tool read image files (PNG, JPG, GIF,
WebP) and return them as base64-encoded attachments the TUI can
render.

---

## Why

Claude Code and OpenCode can both read images: a user attaches a
screenshot, the model can "see" it and reason about it. Until now
AetherCode's `file_read` refused any non-text file with "binary
file detected" — the user had to use `bash` + `xxd` to inspect a
PNG, which is useless for actually viewing it.

This round adds image reading to the tool. The model-integration
side (passing the image as a spring-ai `Media` so the model can
actually "see" it) is staged for R20+.

## What changed

### `Tool.Attachment.ImageAttachment`

New variant on the `Attachment` sealed interface. Carries:
- `mimeType` — `"image/png"`, `"image/jpeg"`, `"image/gif"`, `"image/webp"`
- `base64` — base64-encoded image bytes
- `byteCount` — original byte count (for size display in UI)

Same visibility rules as other attachments: visible to the TUI,
never to the model directly.

### `FileReadTool` detects images

Two-tier detection:

1. **Path extension** — fast, catches the common case (`.png`,
   `.jpg`, `.gif`, `.webp`)
2. **Magic bytes** — checks the first 16 bytes for the known image
   signatures (PNG `89 50 4E 47`, JPEG `FF D8 FF`, GIF `47 49 46 38`).
   Catches files saved with the wrong extension (e.g. a PNG
   renamed to `.bin`)

When an image is detected:
- The file is read fully (up to `MAX_IMAGE_BYTES = 10 MB`)
- The bytes are base64-encoded
- The tool returns a `ToolResult` with a `TextBlock` body
  (e.g. `"(image: image/png, 12345 bytes)"`) and an
  `ImageAttachment` carrying the base64 data
- Larger images are refused with a clear error

### Non-image binary files still refused

The old behaviour is preserved for non-image binary files: a file
with >10% NUL bytes in the first 4 KiB is rejected as "binary file
detected". This catches `.class`, `.jar`, `.zip`, executables,
etc.

## What's NOT in this round

The model itself cannot "see" the image yet. The tool reads and
encodes the file, and the TUI can render the attachment, but the
spring-ai media wiring (translate `ImageAttachment` → `Media` on
the way to the model) is staged for R20+. The current behaviour
means: a user can drop a screenshot in a directory, the model can
read it and see "this is a 1234-byte image/png file" but can't
actually view the pixels.

This is the right staging. The tool side is straightforward and
easy to test. The engine side requires plumbing `Media` through
the spring-ai adapter and the transcript, which is a bigger change
that touches more modules. R20 will land the engine side.

## Tests

- `aethercode-tools/.../file/FileReadToolTest.java` (+3 new):
  - `readsPngByExtension` — writes a real (tiny) PNG, reads it
    back, asserts the attachment has the right mime + base64
  - `detectsPngByMagicBytesEvenWithWrongExtension` — same PNG
    saved with `.bin` extension, still recognised as PNG
  - `detectImageMime_returnsMimeForKnownTypes` — unit test for
    the detection helper (PNG, JPG, GIF, TXT)

## Files

- `aethercode-core/.../tool/Tool.java` — new
  `Attachment.ImageAttachment` record
- `aethercode-tools/.../file/FileReadTool.java` — image detection
  (path + magic bytes), `readImage(...)` path, `MAX_IMAGE_BYTES`
  cap, `detectImageMime(Path)` helper
- `aethercode-tools/src/test/.../file/FileReadToolTest.java` —
  3 new image tests

## Pitfalls (R19-H)

1. **Model can't see images yet** — see "What's NOT in this
   round" above. The TUI / --print see the body text
   "(image: image/png, 1234 bytes)" but the model itself only
   sees the text. R20 will add the engine-side media plumbing.
2. **10 MB cap is hard-coded** — fine for most use cases
   (screenshots, diagrams) but rejects camera RAW files or
   multi-megapixel scans. A future round could accept a
   `max_bytes` parameter.
3. **No GIF animation handling** — we treat animated GIFs as a
   single still image. The base64 encodes the whole file
   (including all frames) but the TUI will show only the first
   frame.
4. **WebP support is path-based only** — we don't have a WebP
   magic-byte check (the RIFF header isn't unique to WebP and
   would false-positive on WAV/AVI). The `RIFF....WEBP` check
   is added in a future round if needed.
5. **No clipboard paste support** — the user can drop a file in
   the working directory, but can't paste a screenshot directly
   into the REPL prompt. That would require a TUI feature
   (image paste on Windows/Mac) and is a separate round.

## Backups

`D:\work\workspace\idea\engine\AetherCode\aethercode\docs\backups\r19h\`
(planned)

## Next

R19-I: WebSearch / WebFetch verify. Both tools exist (R8-ish)
but might have rough edges. Round audits them, fixes bugs,
ensures they're actually used by the model in `--print` mode.
