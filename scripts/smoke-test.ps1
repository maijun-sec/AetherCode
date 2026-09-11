# AetherCode end-to-end smoke test (T-500~T-510) — PowerShell wrapper.
#
# Cross-platform wrapper around scripts/smoke-test.mjs.
# On Windows, run `pwsh scripts\smoke-test.ps1` or
# `powershell -ExecutionPolicy Bypass -File scripts\smoke-test.ps1`.
# On Linux / macOS, prefer `bash scripts/smoke-test.sh`.
#
# Usage:
#   pwsh scripts/smoke-test.ps1
#
# Exit code 0 = all green.

[CmdletBinding()]
param(
    [switch]$SkipBenchmarks = $false
)

$ErrorActionPreference = 'Stop'

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RootDir = (Resolve-Path (Join-Path $ScriptDir '..')).Path

Set-Location $RootDir

$node = (Get-Command node -ErrorAction SilentlyContinue)
if (-not $node) {
    Write-Error "error: node is not on PATH"
    exit 127
}

Write-Host "AetherCode smoke test (PowerShell wrapper)"
Write-Host "  ROOT: $RootDir"
Write-Host "  node: $($node.Source) $(& node --version)"
Write-Host ""

$args = @($ScriptDir, 'smoke-test.mjs')
if ($SkipBenchmarks) {
    & $node.Source (Join-Path $ScriptDir 'smoke-test.mjs') --skip-benchmarks
} else {
    & $node.Source (Join-Path $ScriptDir 'smoke-test.mjs')
}

exit $LASTEXITCODE
