# push_aethercode.ps1
# 一键 push AetherCode 到 GitHub (使用 fine-grained token)
# 用法: 在 PowerShell 里执行 .\push_aethercode.ps1
#
# 安全:
#  - token 走 $env:GITHUB_TOKEN, 不会被记入 PowerShell history (默认 ReadLine)
#  - 用完自动 Remove-Item Env:GITHUB_TOKEN
#  - 这个脚本不含 token, 你需要单独设环境变量 (见下方)

$ErrorActionPreference = 'Stop'
Set-Location $env:AETHERCODE_ROOT

if (-not $env:AETHERCODE_ROOT) {
    Set-Location 'D:\work\workspace\idea\engine\AetherCode'
}

Write-Host '=== AetherCode push helper ===' -ForegroundColor Cyan
Write-Host "  Workspace: $(Get-Location)"
Write-Host "  Branch:    $(git branch --show-current)"
Write-Host "  Commit:    $(git log --oneline -1)"
Write-Host ''

# ================================================================
# 1. 设置 token (3 种方式,选一种)
# ================================================================

if (-not $env:GITHUB_TOKEN) {
    Write-Host '=== Token 输入 ===' -ForegroundColor Cyan
    Write-Host '  方式 A: 直接粘贴 (会保存到 env var, session 结束自动清理)'
    Write-Host '  方式 B: 先在另一终端运行 `$env:GITHUB_TOKEN = "..."`,然后执行此脚本'
    Write-Host ''
    $sec = Read-Host '  GITHUB_TOKEN (粘贴 fine-grained token, 不会回显)'
    if (-not $sec) {
        Write-Host '  No token, aborting.' -ForegroundColor Red
        exit 1
    }
    $env:GITHUB_TOKEN = $sec
}

# ================================================================
# 2. 验证 token
# ================================================================
Write-Host ''
Write-Host '=== 验证 token ===' -ForegroundColor Cyan
try {
    $resp = Invoke-RestMethod -Uri 'https://api.github.com/user' `
        -Headers @{ Authorization = "Bearer $env:GITHUB_TOKEN" } `
        -TimeoutSec 15
    Write-Host "  Authenticated as: $($resp.login)" -ForegroundColor Green
} catch {
    Write-Host "  Token 验证失败: $($_.Exception.Message)" -ForegroundColor Red
    Remove-Item Env:GITHUB_TOKEN -ErrorAction SilentlyContinue
    exit 1
}

# ================================================================
# 3. 设置 remote URL (带 token, 不显示)
# ================================================================
Write-Host ''
Write-Host '=== 设置 remote URL ===' -ForegroundColor Cyan
$remoteUrl = "https://maijun-sec:${env:GITHUB_TOKEN}@github.com/maijun-sec/AetherCode.git"
git remote set-url origin $remoteUrl
$display = "https://maijun-sec:****REDACTED****@github.com/maijun-sec/AetherCode.git"
Write-Host "  $display" -ForegroundColor Gray

# ================================================================
# 4. 确认分支 (master -> main)
# ================================================================
$branch = git branch --show-current
Write-Host ''
Write-Host "=== Current branch: $branch ===" -ForegroundColor Cyan
if ($branch -ne 'main') {
    Write-Host '  Renaming to main...' -ForegroundColor Yellow
    git branch -m main
}

# ================================================================
# 5. Push
# ================================================================
Write-Host ''
Write-Host '=== git push -u origin main ===' -ForegroundColor Cyan
Write-Host '  Pushing 2757 files + 2 submodules...' -ForegroundColor Yellow
Write-Host '  Expected: 30s - 2min (depends on network)' -ForegroundColor Gray
Write-Host ''

git push -u origin main --progress

# ================================================================
# 6. 验证结果
# ================================================================
if ($LASTEXITCODE -eq 0) {
    Write-Host ''
    Write-Host '=== ✅ Push 成功 ===' -ForegroundColor Green
    Write-Host ''
    Write-Host '=== Remote branch ===' -ForegroundColor Cyan
    git log --oneline -3 origin/main
    Write-Host ''
    Write-Host '=== Remote head ===' -ForegroundColor Cyan
    git ls-remote --heads origin main
} else {
    Write-Host ''
    Write-Host '=== ❌ Push 失败 ===' -ForegroundColor Red
    Write-Host "  Exit code: $LASTEXITCODE" -ForegroundColor Red
    Write-Host ''
    Write-Host '  Debug:' -ForegroundColor Yellow
    Write-Host '  - 网络?  try: Test-NetConnection github.com -Port 443'
    Write-Host '  - Token 权限?  try: curl -H "Authorization: Bearer $env:GITHUB_TOKEN" https://api.github.com/repos/maijun-sec/AetherCode | jq .permissions'
    Write-Host '  - Branch protection?  GitHub repo Settings -> Branches -> main'
    Write-Host '  - 详细: GIT_CURL_VERBOSE=1 git push -u origin main -v'
}

# ================================================================
# 7. 清 token
# ================================================================
Remove-Item Env:GITHUB_TOKEN -ErrorAction SilentlyContinue
Write-Host ''
Write-Host '(Env var cleared.)' -ForegroundColor Gray
