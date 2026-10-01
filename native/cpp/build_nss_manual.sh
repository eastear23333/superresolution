#!/bin/bash
# 用 MSVC 直编 libSuperResolutionNSS+win64+release.dll
#
# 背景：本机 MSBuild 会因 PATH/Path 大小写重复触发 MSB6001 而失败，
# 所以绕开 cmake --build，直接用 cl.exe + link.exe。
#
# 注意：调用时需带 MSYS2_ARG_CONV_EXCL='*' MSYS_NO_PATHCONV=1，
# 否则 Git Bash 会把 /MT /I... 等开关当路径转换。
set -e

CL="/f/VS2022BuildTools/VC/Tools/MSVC/14.44.35207/bin/Hostx64/x64/cl.exe"
LINK="/f/VS2022BuildTools/VC/Tools/MSVC/14.44.35207/bin/Hostx64/x64/link.exe"
MSVC='F:/VS2022BuildTools/VC/Tools/MSVC/14.44.35207'
SDKW='C:/Program Files (x86)/Windows Kits/10'
SDKV='10.0.26100.0'

ROOT='E:/.luna/superresolution/native/cpp'
NSS="$ROOT/SRNativeNSS"
OUT="$ROOT/output/bin"
OBJ="$ROOT/buildNSS_manual"
VKINC="$ROOT/third_party"

mkdir -p "$OBJ"

echo "=== 编译 nss.cpp / sr_provider.cpp ==="
"$CL" /nologo /c /LD /EHsc /W3 /utf-8 /std:c++20 /MT /O2 \
  /DON_WIN64 /D_DISABLE_CONSTEXPR_MUTEX_CONSTRUCTOR /DNSS_DP4A_BUILD \
  "$NSS/src/nss.cpp" \
  "$NSS/src/sr_provider.cpp" \
  "/Fo:$OBJ/" "/Fd:$OBJ/nss.pdb" \
  "/I$MSVC/include" \
  "/I$SDKW/Include/$SDKV/ucrt" "/I$SDKW/Include/$SDKV/shared" "/I$SDKW/Include/$SDKV/um" \
  "/I$NSS/include" "/I$NSS/src" \
  "/I$ROOT/SRNativeMain/include" \
  "/I$NSS/third_party/nss_dp4a/include" \
  "/I$VKINC" \
  2>&1

echo
echo "=== 链接 ==="
"$LINK" /nologo /DLL /OUT:"$OUT/libSuperResolutionNSS+win64+release.dll" \
  "$OBJ/nss.obj" "$OBJ/sr_provider.obj" \
  "/LIBPATH:$ROOT/output/lib" \
  "/LIBPATH:$MSVC/lib/x64" \
  "/LIBPATH:$SDKW/Lib/$SDKV/ucrt/x64" \
  "/LIBPATH:$SDKW/Lib/$SDKV/um/x64" \
  "libSuperResolution+win64+release.lib" \
  /IMPLIB:"$OUT/libSuperResolutionNSS+win64+release.lib" \
  /PDB:"$OUT/libSuperResolutionNSS+win64+release.pdb" \
  2>&1

echo
echo "=== 输出 ==="
ls -la "$OUT/libSuperResolutionNSS+win64+release.dll"
