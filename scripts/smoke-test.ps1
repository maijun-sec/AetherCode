# Copyright (c) 2026 maijun
#
# Permission is hereby granted, free of charge, to any person obtaining a copy
# of this software and associated documentation files (the "Software"), to deal
# in the Software without restriction, including without limitation the rights
# to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
# copies of the Software, and to permit persons to whom the Software is
# furnished to do so, subject to the following conditions:
#
# The above copyright notice and this permission notice shall be included in all
# copies or substantial portions of the Software.
#
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
# IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
# FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
# AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
# LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
# OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
# SOFTWARE.

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
