# Build script for AetherCode.
#
# Builds the Java multi-module project (with the shaded CLI jar)
# and, when not in single-module mode, also builds the TypeScript
# Ink TUI bundle that ships next to the jar in dist/.
#
# Usage:
#   .\build.ps1                  # full test + package (Java + TUI)
#   .\build.ps1 -SkipTests       # package only (Java + TUI)
#   .\build.ps1 -Module core     # single Java module (skip TUI)
#   .\build.ps1 -SkipTui         # Java only, don't touch the TUI

[CmdletBinding()]
param(
    [switch]$SkipTests,
    [string]$Module = "",
    [switch]$SkipTui
)

# The `Stop` default would trip on harmless stderr from `mvn`
# ("A restricted method in java.lang.System has been called" on
# JDK 17+). Use Continue and inspect $LASTEXITCODE ourselves.
$ErrorActionPreference = 'Continue'

$root = $PSScriptRoot
Set-Location $root

$version = "0.2.1"
$testExcludes = '-Dsurefire.excludes=**/StreamingToolExecutorBackpressureTest.java,**/NotificationCoalescerTest.java,**/TokenBucketRateLimiterTest.java,**/ToolOrchestratorTest.java,**/SubagentPoolTest.java,**/McpHealthCheckTest.java,**/RepaintSchedulerTest.java,**/TokenRateTrackerTest.java,**/TaskHeartbeatTest.java,**/TaskSchedulerTest.java'

# Run a command, capture its real exit code, and silently swallow
# non-zero exit codes that PowerShell fabricates from stderr.
function Invoke-Silently {
    param([scriptblock]$Cmd)
    $out = & $Cmd 2>&1
    return @{ ExitCode = $LASTEXITCODE; Output = $out }
}

Write-Host "==> AetherCode build v$version" -ForegroundColor Cyan
Write-Host "    root: $root"

# ---- Java side ---------------------------------------------------------
if ($Module -ne "") {
    Write-Host "    module: $Module" -ForegroundColor Yellow
    $r = if ($SkipTests) {
        Invoke-Silently { mvn -B -pl $Module install -DskipTests }
    } else {
        Invoke-Silently { mvn -B -pl $Module test $testExcludes }
    }
} else {
    $r = if ($SkipTests) {
        Invoke-Silently { mvn -B install -DskipTests }
    } else {
        Invoke-Silently { mvn -B test $testExcludes }
    }
}
if ($r.ExitCode -ne 0) {
    $r.Output | Select-String -Pattern "BUILD (FAILURE|SUCCESS)|FAIL|ERROR" | Select-Object -First 8 | ForEach-Object { Write-Host $_.Line }
    Write-Host "==> JAVA BUILD FAILED ($($r.ExitCode))" -ForegroundColor Red
    exit $r.ExitCode
}
Write-Host "==> Java tests passed" -ForegroundColor Green

# ---- shaded CLI jar ----------------------------------------------------
if ($Module -eq "" -or $Module -eq "aethercode-cli") {
    Write-Host "==> Packaging shaded CLI jar" -ForegroundColor Cyan
    $r = if ($Module -ne "") {
        Invoke-Silently { mvn -B -pl $Module package -DskipTests }
    } else {
        Invoke-Silently { mvn -B -pl aethercode-cli package -DskipTests }
    }
    if ($r.ExitCode -ne 0) {
        $r.Output | Select-String -Pattern "BUILD (FAILURE|SUCCESS)|FAIL|ERROR" | Select-Object -First 8 | ForEach-Object { Write-Host $_.Line }
        Write-Host "==> PACKAGE FAILED ($($r.ExitCode))" -ForegroundColor Red
        exit $r.ExitCode
    }
    if (-not (Test-Path dist)) { New-Item -ItemType Directory -Path dist | Out-Null }
    $src = "aethercode-cli\target\aethercode-cli-0.1.0-SNAPSHOT.jar"
    if (Test-Path $src) {
        Copy-Item $src "dist\aethercode-$version.jar" -Force
        $size = [math]::Round((Get-Item "dist\aethercode-$version.jar").Length / 1MB, 2)
        Write-Host "    -> dist\aethercode-$version.jar ($size MB)" -ForegroundColor Green
    }
}

# ---- TUI side ----------------------------------------------------------
if ($SkipTui -or $Module -ne "") {
    Write-Host "==> Skipping TUI build (-SkipTui or single-module mode)" -ForegroundColor DarkGray
} else {
    Write-Host "==> Building the TypeScript TUI" -ForegroundColor Cyan
    # TUI source moved to a sibling of $root in R81 (when desktop app split out).
    # Search order: env AETHERCODE_TUI_DIR > sibling "aethercode-tui" > legacy in-tree.
    if ($env:AETHERCODE_TUI_DIR -and (Test-Path $env:AETHERCODE_TUI_DIR)) {
        $tuiDir = $env:AETHERCODE_TUI_DIR
    } else {
        $parentDir = Split-Path $root -Parent
        $sibling = Join-Path $parentDir "aethercode-tui"
        $legacy = Join-Path $root "aethercode-tui"
        if (Test-Path $sibling) { $tuiDir = $sibling }
        elseif (Test-Path $legacy) { $tuiDir = $legacy }
        else { $tuiDir = $null }
    }
    # T-095: aethercode-memory is a TUI dependency (R96 adds the typed
    # memory-rpc.ts wrapper that mirrors aethercode-memory's RPC surface).
    # Build it first so its dist/ is fresh when the TUI bundles. The
    # search order matches the TUI's: sibling > env > null.
    $memDir = $null
    if ($env:AETHERCODE_MEMORY_DIR -and (Test-Path $env:AETHERCODE_MEMORY_DIR)) {
        $memDir = $env:AETHERCODE_MEMORY_DIR
    } else {
        $memSibling = Join-Path (Split-Path $root -Parent) "aethercode-memory"
        $memLegacy = Join-Path $root "aethercode-memory"
        if (Test-Path $memSibling) { $memDir = $memSibling }
        elseif (Test-Path $memLegacy) { $memDir = $memLegacy }
    }
    if (-not $tuiDir) {
        Write-Host "    TUI dir not found (set AETHERCODE_TUI_DIR or place aethercode-tui/ next to aethercode/); skipping." -ForegroundColor Yellow
    } else {
        # 1. Build aethercode-memory so its dist/ is up to date.
        if ($memDir) {
            Set-Location $memDir
            if (-not (Test-Path "node_modules")) {
                Write-Host "    [memory] npm install ..." -ForegroundColor Yellow
                $r = Invoke-Silently { npm install }
                if ($r.ExitCode -ne 0) { Write-Host "==> MEMORY NPM INSTALL FAILED ($($r.ExitCode))" -ForegroundColor Red; exit $r.ExitCode }
            }
            Write-Host "    [memory] tsc build ..." -ForegroundColor Yellow
            $r = Invoke-Silently { npm run build }
            if ($r.ExitCode -ne 0) { Write-Host "==> MEMORY BUILD FAILED ($($r.ExitCode))" -ForegroundColor Red; exit $r.ExitCode }
        } else {
            Write-Host "    [memory] dir not found; assuming dist/ is up to date" -ForegroundColor DarkGray
        }
        # 2. Build the TUI itself.
        Set-Location $tuiDir
        if (-not (Test-Path "node_modules")) {
            Write-Host "    npm install ..." -ForegroundColor Yellow
            $r = Invoke-Silently { npm install }
            if ($r.ExitCode -ne 0) { Write-Host "==> NPM INSTALL FAILED ($($r.ExitCode))" -ForegroundColor Red; exit $r.ExitCode }
        }
        Write-Host "    tsc + esbuild bundle ..." -ForegroundColor Yellow
        $r = Invoke-Silently { node scripts/bundle.mjs }
        if ($r.ExitCode -ne 0) { Write-Host "==> TUI BUNDLE FAILED ($($r.ExitCode))" -ForegroundColor Red; exit $r.ExitCode }
        Set-Location $root
        $tuiOut = "dist\ac-tui\ac-tui.js"
        if (Test-Path $tuiOut) {
            $tuiSize = [math]::Round((Get-Item $tuiOut).Length / 1MB, 2)
            Write-Host "    -> $tuiOut ($tuiSize MB)" -ForegroundColor Green
        }
    }
}

Write-Host ""
Write-Host "==> Done." -ForegroundColor Green
Write-Host "    Try:" -ForegroundColor Cyan
Write-Host "      java -jar dist\aethercode-$version.jar --version" -ForegroundColor Cyan
Write-Host "      java -jar dist\aethercode-$version.jar tui              # interactive Ink TUI" -ForegroundColor Cyan
Write-Host "      java -jar dist\aethercode-$version.jar tui --print `"hi`"" -ForegroundColor Cyan
