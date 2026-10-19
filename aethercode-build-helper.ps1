# aethercode-build-helper.ps1
# Intelligent build entry point for AetherCode + downstream Java projects.
# Replaces the 9-13 minute "stuck build" pattern that the RAG Day 2
# Q19/Q20/Q21 sessions hit when mockito 5.14.x + byte-buddy 1.14.x
# were selected against JDK 25 (class file version 65). R722 standardises
# the dependency pick by JDK version and adds exponential-backoff
# retry on a real BUILD FAILURE — the previous behaviour was to
# let the user (or the model) flail for 9-13 minutes before noticing
# the build had been silently stuck on a Mockito/ByteBuddy incompatibility.
#
# Usage:
#   .\aethercode-build-helper.ps1 -Goals 'package -DskipTests'
#   .\aethercode-build-helper.ps1 -Goals 'test' -MaxAttempts 2
#   .\aethercode-build-helper.ps1 -Help
#
# Behaviour:
#   1. Detects JDK major version (`mvn -v` → java version).
#   2. Picks mockito + byte-buddy versions known to work on that
#      JDK. (Table below — see the version table comment.)
#   3. Runs `mvn <goals>`. On BUILD FAILURE retries with
#      exponential backoff (5s, 15s, 45s by default; tunable).
#   4. Logs to $env:USERPROFILE\aethercode-build-helper.log so the
#      model can read the trail without a separate `tee`.
#
# Exit codes:
#   0 = BUILD SUCCESS
#   1 = BUILD FAILURE after all attempts
#   2 = prerequisites missing (mvn not on PATH, etc.)
#
# Tested on:
#   - JDK 25.0.1 (Adoptium Temurin) + mockito 5.15.2 + byte-buddy 1.15.11
#     — the (current) safe triplet for AetherCode's multi-module
#     build (RAG Day 3 Q20 already uses these).
#   - JDK 21.0.5 (Adoptium Temurin) + mockito 5.12.0 + byte-buddy 1.14.13
#     — pre-R720 baseline.

[CmdletBinding()]
param(
    [string]$Goals = 'package -DskipTests -q',
    [int]$MaxAttempts = 3,
    [int]$FirstBackoffSeconds = 5,
    [string]$LogFile = "$env:USERPROFILE\aethercode-build-helper.log",
    [switch]$Help,
    [switch]$DryRun
)

# Version table — when a JDK major version is added, only this
# table needs to grow. The script applies the table via -D
# overrides on the mvn command line; it does NOT mutate
# pom.xml (the user might be running ad-hoc from a different
# shell, and silently changing a checked-in pom is rude).
#
# Mockito's official JDK support matrix:
#   5.0+        → JDK 17+  (class file version 61)
#   5.10+       → JDK 21+  (class file version 65) requires byte-buddy 1.14.13+
#   5.12+       → JDK 21+  works with byte-buddy 1.14.13+
#   5.14+       → JDK 21+  works with byte-buddy 1.14.13+ (the RAG Day 2 default)
#   5.15+       → JDK 21+  works with byte-buddy 1.15.0+  (JDK 25 verified)
#
# ByteBuddy 1.15.0+ is required for Mockito to handle JDK 25
# class files (version 65) without an IllegalArgumentException
# from the inline mock-maker. 1.14.x can read 65-class files
# but raises on the inline-mock path that Mockito's
# mockStatic / @Spy uses.
$versionTable = @(
    @{ jdk = 25; mockito = '5.15.2'; byteBuddy = '1.15.11';   notes = 'RAG Day 3 verified (Q20 RAG)' },
    @{ jdk = 24; mockito = '5.14.2'; byteBuddy = '1.14.18';   notes = 'RAG Day 2 baseline (Q19)' },
    @{ jdk = 23; mockito = '5.12.0'; byteBuddy = '1.14.13';   notes = 'pre-R720 default' },
    @{ jdk = 22; mockito = '5.12.0'; byteBuddy = '1.14.13';   notes = 'pre-R720 default' },
    @{ jdk = 21; mockito = '5.12.0'; byteBuddy = '1.14.13';   notes = 'LTS line' },
    @{ jdk = 17; mockito = '5.5.0';  byteBuddy = '1.14.0';    notes = 'lowest supported' }
)

function Write-Log {
    param([string]$Message, [string]$Level = 'INFO')
    $ts = Get-Date -Format 'yyyy-MM-ddTHH:mm:ss'
    $line = "[$ts] [$Level] $Message"
    Write-Host $line
    Add-Content -Path $LogFile -Value $line -Encoding UTF8
}

function Show-Help {
    @"
aethercode-build-helper.ps1 — R722 intelligent Java build entry

Usage:
    .\aethercode-build-helper.ps1 [-Goals <string>] [-MaxAttempts <int>]
                                  [-FirstBackoffSeconds <int>] [-DryRun] [-Help]

Examples:
    # default: package -DskipTests -q, retry up to 3 times
    .\aethercode-build-helper.ps1

    # run tests, fail fast on second attempt
    .\aethercode-build-helper.ps1 -Goals 'test' -MaxAttempts 2

    # see what would run, don't actually invoke mvn
    .\aethercode-build-helper.ps1 -DryRun

The script:
  1. Reads the JDK major version from `mvn -v`.
  2. Looks up a safe (mockito, byte-buddy) pair for that JDK.
  3. Invokes mvn with the goal string + the pair as -D
     overrides (mockito.version, byte-buddy.version).
  4. On BUILD FAILURE, retries with exponential backoff
     (5s, 15s, 45s by default).
  5. Logs every step to $env:USERPROFILE\aethercode-build-helper.log.

Exit codes:
  0  = BUILD SUCCESS
  1  = BUILD FAILURE after all attempts
  2  = prerequisites missing (e.g. mvn not on PATH)
"@
}

if ($Help) { Show-Help; exit 0 }

# ---- prerequisites ----
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    Write-Log "mvn not on PATH; install Apache Maven 3.9.x or set M2_HOME." "ERROR"
    exit 2
}
if (-not (Test-Path 'D:\work\workspace\idea\engine\AetherCode\aethercode\pom.xml')) {
    Write-Log "expected AetherCode repo at D:\work\workspace\idea\engine\AetherCode\aethercode; aborting." "ERROR"
    exit 2
}
# R722: auto-cd into the multi-module subdir so the user can
# invoke the script from the repo root. Pre-R722 the script
# expected the user to `cd aethercode` first, which is a
# minor friction that's easy to forget when the model is
# driving the build via a tool call.
Set-Location 'D:\work\workspace\idea\engine\AetherCode\aethercode'
Write-Log "auto-cd into $(Get-Location)"

# ---- JDK detection ----
$mvnv = (& mvn -v) 2>&1 | Out-String
$jdkMajor = 0
if ($mvnv -match 'Java version:\s*(\d+)(?:\.\d+)*') {
    $jdkMajor = [int]$Matches[1]
}
if ($jdkMajor -lt 17) {
    Write-Log "JDK major $jdkMajor is below the supported minimum (17); aborting." "ERROR"
    exit 2
}

# pick the highest entry in the table whose jdk is <= current
$picked = $null
foreach ($row in $versionTable) {
    if ($row.jdk -le $jdkMajor) {
        $picked = $row
        break  # sorted descending; first match wins
    }
}
if ($null -eq $picked) {
    # JDK 17 or below — take the lowest table entry
    $picked = $versionTable[-1]
}
$mockitoVersion = $picked.mockito
$byteBuddyVersion = $picked.byteBuddy
Write-Log "JDK $jdkMajor detected; picked mockito=$mockitoVersion byteBuddy=$byteBuddyVersion ($($picked.notes))"

# ---- mvn command assembly ----
# R722: split goals + -D overrides into an array so PowerShell
# doesn't concatenate them into a single whitespace-stripped
# arg. The first cut of this script used a single $fullGoals
# string and `& mvn $fullGoals`, which mvn then parsed as
# one giant lifecycle-phase name (e.g. "compile -DskipTests
# -q -Dmockito.version=5.15.2" → "Unknown lifecycle phase")
# and failed before any compile work happened. Splatting
# the args via an array is the standard PowerShell idiom.
$goalArgs = @()
foreach ($tok in ($Goals -split '\s+')) {
    if ($tok) { $goalArgs += $tok }
}
$goalArgs += "-Dmockito.version=$mockitoVersion"
$goalArgs += "-Dbyte-buddy.version=$byteBuddyVersion"
$cmd = "mvn $($goalArgs -join ' ')"
Write-Log "mvn command: $cmd"

if ($DryRun) {
    Write-Log "DRY-RUN: not executing mvn. Exit 0."
    exit 0
}

# ---- exponential-backoff retry loop ----
$backoff = $FirstBackoffSeconds
$attempt = 0
while ($attempt -lt $MaxAttempts) {
    $attempt++
    Write-Log "attempt $attempt of $MaxAttempts"
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    # Use a fresh $LASTEXITCODE capture inside a subshell via &
    # so transient env doesn't leak.
    $output = & mvn @goalArgs 2>&1
    $exitCode = $LASTEXITCODE
    $sw.Stop()
    Add-Content -Path $LogFile -Value $output -Encoding UTF8
    if ($exitCode -eq 0) {
        Write-Log "BUILD SUCCESS in $([math]::Round($sw.Elapsed.TotalSeconds, 2))s on attempt $attempt"
        exit 0
    }
    # look for the JDK 25 incompatibility signal
    $errMatch = ($output | Select-String -Pattern 'class file version 65|UnsupportedClassVersionError|byte-buddy' -ErrorAction SilentlyContinue | Select-Object -First 1)
    if ($errMatch) {
        Write-Log "BUILD FAILURE (exit=$exitCode) — detected JDK-compat signal: $($errMatch.Line)" "WARN"
    } else {
        Write-Log "BUILD FAILURE (exit=$exitCode) after $([math]::Round($sw.Elapsed.TotalSeconds, 2))s — see $LogFile" "WARN"
    }
    if ($attempt -ge $MaxAttempts) { break }
    Write-Log "sleeping $backoff seconds before retry (exponential backoff: ${backoff}s -> $(${backoff}*3)s -> $(${backoff}*9)s)"
    Start-Sleep -Seconds $backoff
    $backoff = $backoff * 3
}
Write-Log "BUILD FAILURE after $MaxAttempts attempts; giving up. See $LogFile for the full mvn output." "ERROR"
exit 1
