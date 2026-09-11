#!/usr/bin/env bash
# AetherCode end-to-end smoke test (T-500~T-510).
#
# Cross-platform wrapper around scripts/smoke-test.mjs.
# On Windows, run `node scripts\smoke-test.mjs` directly.
# On Linux / macOS, this script just calls node.
#
# Usage:
#   bash scripts/smoke-test.sh
#   ./scripts/smoke-test.sh
#
# Exit code 0 = all green.

set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
ROOT_DIR="$( cd "$SCRIPT_DIR/.." && pwd )"

cd "$ROOT_DIR"

if ! command -v node >/dev/null 2>&1; then
  echo "error: node is not on PATH" >&2
  exit 127
fi

echo "AetherCode smoke test (bash wrapper)"
echo "  ROOT: $ROOT_DIR"
echo "  node: $(node --version)"
echo ""

exec node "$SCRIPT_DIR/smoke-test.mjs" "$@"
