# SuperResolution Windows 构建一键脚本(需管理员运行)
# 用法: 右键"以管理员身份运行" PowerShell, 然后执行:
#   .\script\setup_build.ps1
# 或:  .\script\setup_build.ps1 -SkipInstall   (跳过 VS Build Tools 安装,仅构建)

param(
    [switch]$SkipInstall,
    [string]$StreamlineBin = "F:\streamline-sdk-v2.12.0\bin\x64"
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

Write-Host "=== SuperResolution Windows 构建 ==="
Write-Host "仓库根: $RepoRoot"

# ---------- 1. 管理员检查 ----------
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent())
    .IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Warning "未以管理员运行。若跳过安装(仅构建)可继续,否则请以管理员重新运行。"
}

# ---------- 2. 安装 VS Build Tools(MSVC + Windows SDK) ----------
if (-not $SkipInstall) {
    Write-Host ""
    Write-Host "=== [1/4] 安装 VS Build Tools(VCTools + Windows SDK)==="
    if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
        throw "未找到 winget。请手动安装 Visual Studio Build Tools:"
        + " https://visualstudio.microsoft.com/visual-cpp-build-tools/ (勾选 '使用 C++ 的桌面开发')"
    }
    winget install --id Microsoft.VisualStudio.2022.BuildTools `
        --accept-source-agreements --accept-package-agreements `
        --override "--quiet --wait --norestart --add Microsoft.VisualStudio.Workload.VCTools --includeRecommended"
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "winget 安装退出码 $LASTEXITCODE。若已安装过可忽略,继续尝试构建。"
    }
}

# ---------- 3. 初始化子模块 ----------
Write-Host ""
Write-Host "=== [2/4] 初始化 git 子模块 ==="
git submodule update --init --recursive
if ($LASTEXITCODE -ne 0) {
    Write-Warning "子模块初始化失败,后续 native 构建可能缺依赖(如 glslang / XeSS / DLSS SDK)。"
}

# ---------- 4. native 构建 ----------
Write-Host ""
Write-Host "=== [3/4] native 构建(build_windows.ps1)==="
Push-Location native\cpp
& .\build_windows.ps1 -StreamlineBin $StreamlineBin
if ($LASTEXITCODE -ne 0) { throw "native 构建失败" }
Pop-Location

# ---------- 5. gradle 构建 fabric 1.21.11 ----------
Write-Host ""
Write-Host "=== [4/4] gradle 构建 fabric 1.21.11 ==="
& .\gradlew :fabric:build -Pminecraft_version_config=1.21.11
if ($LASTEXITCODE -ne 0) { throw "gradle 构建失败" }

Write-Host ""
Write-Host "=== 全部完成 ==="
Write-Host "mod jar 位于 fabric/build/libs/"
