#!/bin/bash
# R135.4: CI-friendly graceful shutdown for the AetherCode HTTP daemon.
# Replaces `taskkill /PID` (which doesn't trigger JVM shutdown hooks on
# Windows) and `kill -TERM` (which Linux CI scripts can use but Windows
# can't). This script works on both.
#
# Usage:
#   aethercode-shutdown.sh                          # default: localhost:17903, 30s drain
#   aethercode-shutdown.sh -p 17904 -d 5000 -f 1    # custom port, 5s drain, force-kill
#   aethercode-shutdown.sh --help
#
# Environment overrides:
#   AETHERCODE_HOST  default localhost
#   AETHERCODE_PORT  default 17903
#   AETHERCODE_DRAIN_MS  default 30000
#   AETHERCODE_FORCE  default 0 (set to 1 to skip drain)
#   AETHERCODE_SHUTDOWN_TOKEN  optional auth token (R135.5)

set -e

# Parse args
PORT="${AETHERCODE_PORT:-17903}"
HOST="${AETHERCODE_HOST:-localhost}"
DRAIN_MS="${AETHERCODE_DRAIN_MS:-30000}"
FORCE="${AETHERCODE_FORCE:-0}"
TOKEN="${AETHERCODE_SHUTDOWN_TOKEN:-}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        -p|--port)        PORT="$2"; shift 2;;
        -h|--host)        HOST="$2"; shift 2;;
        -d|--drain-ms)    DRAIN_MS="$2"; shift 2;;
        -f|--force)       FORCE="$2"; shift 2;;
        -t|--token)       TOKEN="$2"; shift 2;;
        --help)
            echo "Usage: $0 [-h host] [-p port] [-d drain_ms] [-f 0|1] [-t token]"
            echo "Default: $HOST:$PORT, drain=$DRAIN_MS ms, force=$FORCE"
            exit 0
            ;;
        *) echo "Unknown arg: $1"; exit 1;;
    esac
done

URL="http://$HOST:$PORT/shutdown?drainMs=$DRAIN_MS&force=$FORCE"
echo "AetherCode daemon shutdown: $URL"

ARGS=()
if [[ -n "$TOKEN" ]]; then
    ARGS+=(-H "Authorization: Bearer $TOKEN")
fi

# curl handles both 202 (Accepted) and 5xx (server crashed mid-shutdown)
HTTP_CODE=$(curl -sS -o /tmp/aethercode-shutdown-resp.txt -w "%{http_code}" \
    -X POST \
    "${ARGS[@]}" \
    "$URL" || echo "000")

if [[ -f /tmp/aethercode-shutdown-resp.txt ]]; then
    echo "Response body:"
    cat /tmp/aethercode-shutdown-resp.txt
    echo
    rm -f /tmp/aethercode-shutdown-resp.txt
fi

# 202 = Accepted (drain started, daemon will exit on its own)
# 000 = curl failed (daemon might have already exited)
# 5xx = server error (daemon might be in a bad state)
case "$HTTP_CODE" in
    202) echo "Shutdown accepted. Daemon will drain + exit."; exit 0;;
    000) echo "Connection failed — daemon may have already exited."; exit 0;;
    5*)  echo "Server error — daemon may be in a bad state."; exit 1;;
    *)   echo "Unexpected response: $HTTP_CODE"; exit 1;;
esac
