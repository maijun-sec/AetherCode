@echo off
REM AetherCode end-to-end smoke test (T-500~T-510) — Windows cmd wrapper.
REM
REM Cross-platform wrapper around scripts\smoke-test.mjs.
REM On Windows, run `scripts\smoke-test.cmd` or
REM `pnpm smoke` (defined in root package.json).
REM On Linux / macOS, prefer `bash scripts/smoke-test.sh`.
REM
REM Exit code 0 = all green.

setlocal
set "SCRIPT_DIR=%~dp0"
set "ROOT_DIR=%SCRIPT_DIR%.."

cd /d "%ROOT_DIR%"

where node >nul 2>nul
if errorlevel 1 (
  echo error: node is not on PATH 1>&2
  exit /b 127
)

echo AetherCode smoke test (cmd wrapper)
echo   ROOT: %ROOT_DIR%
echo   node: 
node --version
echo.

node "%SCRIPT_DIR%smoke-test.mjs" %*
exit /b %errorlevel%
