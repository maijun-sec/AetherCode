# TUI tests live in `scripts/test/`

The TUI uses `node --test scripts/test/*.test.mjs`. There is
no vitest / no `__tests__/` directory inside `src/`. Tests
import the compiled pure-helper output via a shim written
into `src/` and compiled with `tsc`. See the DEVELOPER_GUIDE.md
§9 for the full pattern.
