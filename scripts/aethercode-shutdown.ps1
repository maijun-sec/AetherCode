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

# R135.4: Windows PowerShell wrapper for aethercode-shutdown.
# Mirrors the bash version. Windows-native curl (Invoke-WebRequest).
#
# Usage:
#   .\aethercode-shutdown.ps1
#   .\aethercode-shutdown.ps1 -Port 17904 -DrainMs 5000 -Force 1
#
# Environment overrides:
#   AETHERCODE_HOST          default localhost
#   AETHERCODE_PORT          default 17903
#   AETHERCODE_DRAIN_MS      default 30000
#   AETHERCODE_FORCE         default 0
#   AETHERCODE_SHUTDOWN_TOKEN optional auth token (R135.5)

param(
    [string]$Host = $env:AETHERCODE_HOST ?? "localhost",
    [int]$Port = if ($env:AETHERCODE_PORT) { [int]$env:AETHERCODE_PORT } else { 17903 },
    [int]$DrainMs = if ($env:AETHERCODE_DRAIN_MS) { [int]$env:AETHERCODE_DRAIN_MS } else { 30000 },
    [int]$Force = if ($env:AETHERCODE_FORCE) { [int]$env:AETHERCODE_FORCE } else { 0 },
    [string]$Token = $env:AETHERCODE_SHUTDOWN_TOKEN ?? ""
)

$ErrorActionPreference = 'Stop'
$url = "http://${Host}:${Port}/shutdown?drainMs=${DrainMs}&force=${Force}"
Write-Host "AetherCode daemon shutdown: $url"

$headers = @{}
if ($Token) { $headers['Authorization'] = "Bearer $Token" }

try {
    $resp = Invoke-WebRequest -Uri $url -Method POST -Headers $headers -TimeoutSec 10 -UseBasicParsing
    Write-Host "Response body:"
    Write-Host $resp.Content
    Write-Host ""
    Write-Host "Shutdown accepted (HTTP $($resp.StatusCode)). Daemon will drain + exit."
    exit 0
} catch {
    $code = $_.Exception.Response.StatusCode.value__
    if ($code -ge 500 -and $code -lt 600) {
        Write-Host "Server error (HTTP $code) — daemon may be in a bad state."
        exit 1
    } else {
        Write-Host "Connection failed — daemon may have already exited."
        exit 0
    }
}
