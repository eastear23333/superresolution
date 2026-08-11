param(
    # Streamline SDK 的 bin 目录(包含 sl.common.dll / sl.interposer.dll / sl.dlss_g.dll 等)
    [string]$StreamlineBin = "F:\streamline-sdk-v2.12.0\bin\x64",
    # 是否同时构建 Debug(默认只构建 Release)
    [switch]$Debug
)

$ErrorActionPreference = "Stop"

# 目录:脚本在 native/cpp 下运行
Set-Location $PSScriptRoot
$RepoRoot = Split-Path -Parent $PSScriptRoot
$OutputBin = Join-Path $PSScriptRoot "output\bin"
$LibDir = Join-Path $RepoRoot "common\src\main\resources\lib"

Write-Host "=== SuperResolution Windows native 构建 ==="
Write-Host "输出目录: $OutputBin"
Write-Host "打包目录: $LibDir"

# 前置检查
if (-not (Test-Path "third_party\Streamline\include")) {
    Write-Warning "third_party/Streamline/include 不存在。请确认子模块已初始化:"
    Write-Warning "  git submodule update --init --recursive"
}
if (-not $env:VULKAN_SDK -and -not (Test-Path "C:\VulkanSDK")) {
    Write-Warning "未检测到 Vulkan SDK(需要 VULKAN_SDK 环境变量或 C:\VulkanSDK)。"
    Write-Warning "下载: https://vulkan.lunarg.com/sdk/home"
}

# 1. CMake 配置 + 构建
$types = @("Release")
if ($Debug) { $types += "Debug" }

foreach ($type in $types) {
    $buildDir = "buildWindows-$type"
    Write-Host ""
    Write-Host "=== [$type] CMake 配置 ==="
    cmake -S . -B $buildDir -DCMAKE_BUILD_TYPE=$type `
        -DSR_FSR=ON -DSR_XESS=ON -DSR_NGX=ON `
        -DSR_FSR4=ON -DSR_D3D12_INTEROP=ON `
        -DSR_FSROGL=OFF
    if ($LASTEXITCODE -ne 0) { throw "CMake 配置失败($type)" }

    Write-Host "=== [$type] 编译 ==="
    cmake --build $buildDir --config $type
    if ($LASTEXITCODE -ne 0) { throw "CMake 编译失败($type)" }
}

# 2. 拷贝 native 产物到 resources/lib
Write-Host ""
Write-Host "=== 拷贝 native DLL -> $LibDir ==="
New-Item -ItemType Directory -Force -Path $LibDir | Out-Null
$copied = @()
Get-ChildItem $OutputBin -Filter "*.dll" | ForEach-Object {
    Copy-Item $_.FullName $LibDir -Force
    $copied += $_.Name
    Write-Host "  -> $($_.Name)"
}

# 3. 拷贝 Streamline SDK 运行时 DLL
Write-Host ""
Write-Host "=== 拷贝 Streamline SDK DLL(从 $StreamlineBin)==="
if (-not (Test-Path $StreamlineBin)) {
    throw "Streamline SDK 路径不存在: $StreamlineBin"
}
Get-ChildItem $StreamlineBin -Filter "*.dll" | ForEach-Object {
    Copy-Item $_.FullName $LibDir -Force
    $copied += $_.Name
    Write-Host "  -> $($_.Name)"
}

# 4. 可选:拷贝 XeSS 运行时 libxess.dll(子模块已初始化时)
$xessDll = "SRNativeXeSS\third_party\XeSS\bin\x64\libxess.dll"
if (Test-Path $xessDll) {
    Copy-Item $xessDll $LibDir -Force
    Write-Host "  -> libxess.dll(XeSS 运行时)"
}

Write-Host ""
Write-Host "=== 完成。已拷贝 $($copied.Count) 个 DLL ==="
Write-Host "下一步(管理员/普通均可):"
Write-Host "  cd $RepoRoot"
Write-Host "  .\gradlew :fabric:build -Pminecraft_version_config=1.21.11"
