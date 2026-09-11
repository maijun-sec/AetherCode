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
