#!/usr/bin/env python3
"""NSS UBO 偏移一致性检查（Java 写入 vs GLSL 声明）。

固化教训：本项目曾因「Java 写入偏移与 GLSL 声明偏移不一致」以及
「_NotHistoryReset 语义写反」导致长时间难定位的画面抖动。
改动 NSS 的 UBO 后，务必跑一次本脚本。

用法：
    python check_nss_ubo_offsets.py
在 superresolution 仓库根目录执行。
"""
import re
import sys
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent
SHADER_DIR = ROOT / "common/src/main/resources/shader/nss"
PARAMS = ROOT / "common/src/main/java/io/homo/superresolution/common/upscale/algo/nss/NssFrameParams.java"

# GLSL 声明：layout(offset = N) <type> <name>
GLSL_RE = re.compile(r"layout\(offset\s*=\s*(\d+)\)\s+(\S+)\s+(\w+)\s*;")

# Java 写入：putVec2(buf, N, ...) / putVec2i(buf, N, ...) / putVec4(buf, N, ...) / buf.putFloat(N, ...)
JAVA_RE = re.compile(r"(?:putVec2i?|putVec4)\(buf,\s*(\d+)\s*,|buf\.putFloat\((\d+)\s*,")


def parse_glsl(path):
    """返回 {offset: (type, name)}，含宏分支的重复项取并集。"""
    out = {}
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        m = GLSL_RE.search(line)
        if m:
            off, typ, name = int(m.group(1)), m.group(2), m.group(3)
            out.setdefault(off, (typ, name))
    return out


def parse_java_method(src, method_name):
    """截取某个 writeXxxUbo 方法体，返回 {offset: None}。"""
    m = re.search(rf"void\s+{method_name}\s*\([^)]*\)\s*\{{", src)
    if not m:
        return None
    i = m.end()
    depth = 1
    while i < len(src) and depth:
        if src[i] == "{":
            depth += 1
        elif src[i] == "}":
            depth -= 1
        i += 1
    body = src[m.end():i]
    offsets = set()
    for jm in JAVA_RE.finditer(body):
        offsets.add(int(jm.group(1) or jm.group(2)))
    return offsets


TARGETS = [
    ("1_pre_process_shared.h", "writePreProcessUbo", "1_pre_process"),
    ("3_post_process_shared.h", "writePostProcessUbo", "3_post_process"),
    ("0_depth_scatter.comp", "writeDepthScatterUbo", "0_depth_scatter"),
]


def main():
    src = PARAMS.read_text(encoding="utf-8", errors="replace")
    failed = False
    for glsl_file, java_method, label in TARGETS:
        path = SHADER_DIR / glsl_file
        if not path.exists():
            print(f"[跳过] {glsl_file} 不存在")
            continue
        decl = parse_glsl(path)
        written = parse_java_method(src, java_method)
        if written is None:
            print(f"[跳过] 未找到方法 {java_method}")
            continue

        print(f"═══ {label} ({glsl_file} vs {java_method}) ═══")
        decl_offsets = set(decl)
        missing = sorted(decl_offsets - written)   # GLSL 声明了但 Java 没写
        extra = sorted(written - decl_offsets)     # Java 写了但 GLSL 没声明
        ok = not missing and not extra
        for off in sorted(decl_offsets | written):
            typ, name = decl.get(off, ("?", "<未声明>"))
            in_java = off in written
            flag = "✓" if in_java and off in decl else ("J" if in_java else "G")
            note = ""
            if in_java and off not in decl:
                note = "  ← Java 写了但 GLSL 未声明"
            if off in decl and not in_java:
                note = "  ← GLSL 声明了但 Java 未写！！"
            print(f"  [{flag}] offset {off:>4}  {typ:<10} {name}{note}")
        print(f"  → {'✓ 一致' if ok else '✗ 不一致！'}\n")
        if not ok:
            failed = True

    # 语义检查：reset 写入必须对应官方 _NotHistoryReset
    print("═══ 语义检查：_NotHistoryReset（官方 1=保留 / 0=重置）═══")
    nss_src = (ROOT / "common/src/main/java/io/homo/superresolution/common/upscale/algo/nss/NSS.java").read_text(
        encoding="utf-8", errors="replace")
    if "notHistoryReset = 1.0f" in src:
        print("  ✓ 默认值为 1.0（保留历史）")
    else:
        print("  ✗ 未找到 notHistoryReset = 1.0f 默认值")
        failed = True
    # 官方 ffx_nss.cpp:1195 —— noHistoryReset = needResetHistory ? 0.0f : 1.0f
    if re.search(r"params\.notHistoryReset\s*=\s*reset\s*\?\s*0\.0f\s*:\s*1\.0f", nss_src):
        print("  ✓ 写入语义正确：reset=true → 0（重置），reset=false → 1（保留）")
    else:
        print("  ✗ 写入语义错误或未找到！应为 params.notHistoryReset = reset ? 0.0f : 1.0f")
        failed = True
    # 前处理必须声明并消费 _NotHistoryReset
    pre = (SHADER_DIR / "1_pre_process_shared.h").read_text(encoding="utf-8", errors="replace")
    if "_NotHistoryReset" in pre and "disocclusion_mask *= history_valid" in pre:
        print("  ✓ 前处理已声明 _NotHistoryReset 并乘到 disocclusion_mask 上")
    else:
        print("  ✗ 前处理缺少 _NotHistoryReset 声明或 history_valid 乘法（官方 preprocess:950-958）")
        failed = True

    # ★ 重投影符号一致性：所有 reproj_* 必须是加号（与官方一致）
    #   教训：1_pre_process_shared.h 的 `reproj_uv` 曾漏改符号，导致整屏鬼影。
    print("\n═══ 重投影符号检查（全部必须为 `+`，与官方一致）═══")
    reproj_patterns = [
        ("1_pre_process_shared.h", r"lane\.reproj_uv\s*=\s*lane\.uv\s*\+"),
        ("1_pre_process_shared.h", r"float2\s+reproj_uv\s*=\s*uv\s*\+"),
        ("1_pre_process_shared.h", r"float2\s+reproj_270p_uv\s*=[^;]*\)\s*\+"),
        ("1_pre_process_shared.h", r"float2\s+reproj_pad_uv\s*=\s*uv_pad\s*\+"),
        ("3_post_process_shared.h", r"float2\s+reproj_uv\s*=\s*uv\s*\+"),
        ("0_depth_scatter.comp", r"float2\s+reproj_uv\s*=\s*uv\s*\+"),
        ("0_disocclusion_mask_lq_shared.h", r"float2\s+reproj_uv\s*=\s*uv\s*\+"),
    ]
    for fname, pat in reproj_patterns:
        fpath = SHADER_DIR / fname
        if not fpath.exists():
            continue
        text = fpath.read_text(encoding="utf-8", errors="replace")
        if re.search(pat, text):
            print(f"  ✓ {fname}: {pat}")
        else:
            print(f"  ✗ {fname}: 未匹配加号形式 → {pat}")
            failed = True

    # 反向检查：不应存在 `uv - (motion` / `uv - motion` 这类减号重投影
    bad = re.compile(r"reproj\w*\s*=\s*uv\s*-\s*\(?\s*\w*motion")
    for fname in {p[0] for p in reproj_patterns}:
        fpath = SHADER_DIR / fname
        if not fpath.exists():
            continue
        for i, line in enumerate(fpath.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
            if line.lstrip().startswith("//"):
                continue
            if bad.search(line):
                print(f"  ✗ {fname}:{i} 发现减号重投影！{line.strip()}")
                failed = True

    # ★ 渲染尺寸取整语义：必须与光影 upscaleRatio 的 floor 一致（不能用 ceil）
    print("\n═══ 渲染尺寸取整语义检查 ═══")
    rhm = (ROOT / "common/src/main/java/io/homo/superresolution/common/minecraft/handler/"
           "RenderHandlerManager.java").read_text(encoding="utf-8", errors="replace")
    if re.search(r"return\s+\(int\)\s*Math\.max\(getScreenHeight\(\)\s*\*\s*getScaleFactor\(\)", rhm):
        print("  ✓ getRenderHeight 用 (int) 截断（= 光影 floor(scale*screen) 语义）")
    else:
        print("  ✗ getRenderHeight 不是截断语义！")
        print("    光影 Uniform.glsl:100 用 `screenSize / floor(SR_RENDER_SCALE_FACTOR*screenSize)`")
        print("    推导 render 尺寸 → SR 侧必须同为截断；改成 ceil 会让 MV 幅度不匹配（鬼影）。")
        failed = True
    if re.search(r"return\s+\(int\)\s*Math\.max\(getScreenWidth\(\)\s*\*\s*getScaleFactor\(\)", rhm):
        print("  ✓ getRenderWidth 用 (int) 截断")
    else:
        print("  ✗ getRenderWidth 不是截断语义！")
        failed = True

    # ★ _MotionToPixels 与 shader 重投影符号的联合一致性（数值验证见 .tmp_mv_joint2.py）
    print("\n═══ _MotionToPixels × reproj 符号联合检查 ═══")
    # 数学等价两种写法，二者取一即可：
    #   A) _MotionToPixels = +render*(render/screen)  + shader `+`   （当前采用）
    #   B) _MotionToPixels = -render*(render/screen)  + shader `+`   （等价）
    # 关键：shader 必须是 `+`；_MotionToPixels 必须为正（符号由 shader 的 `+` 承担）
    mp_ok = ("motionToPixelsX = renderWidth * invUpscaleX * mvScale" in
             (ROOT / "common/src/main/java/io/homo/superresolution/common/upscale/algo/nss/NSS.java"
              ).read_text(encoding="utf-8", errors="replace"))
    if mp_ok:
        print("  ✓ _MotionToPixels 取正号（幅度换算，方向交给 shader 的 `+`）")
    else:
        print("  ✗ _MotionToPixels 符号异常")
        failed = True

    # ★ reset 帧必须真的 GPU 清空历史资源（官方 ffx_nss.cpp:1506-1528）
    print("\n═══ reset 帧清空历史资源检查（官方 ffx_nss.cpp:1506-1528）═══")
    nss_src = (ROOT / "common/src/main/java/io/homo/superresolution/common/upscale/algo/nss/NSS.java"
               ).read_text(encoding="utf-8", errors="replace")
    clear_reqs = {
        "derivativeTextures[0]": "LUMA_DERIV_1",
        "derivativeTextures[1]": "LUMA_DERIV_2",
        "nearestOffsetTexture": "NEAREST_DEPTH_COORD",
        "temporalTextures[0]": "FEEDBACK_TENSOR(乒乓 A)",
        "temporalTextures[1]": "FEEDBACK_TENSOR(乒乓 B)",
        "historyTextures[0]": "HISTORY_1",
        "historyTextures[1]": "HISTORY_2",
    }
    # 只在 clearHistoryResourcesOnReset 方法体内查找
    m = re.search(r"private void clearHistoryResourcesOnReset\(.*?\n    \}", nss_src, re.S)
    if not m:
        print("  ✗ 找不到 clearHistoryResourcesOnReset 方法！")
        print("    官方在 reset 帧会对 7 张资源下 clear job；缺失会让脏像素从")
        print("    ClampHistoryToStats 的 AABB 边界漏进结果。")
        failed = True
    else:
        body = m.group(0)
        for expr, label in clear_reqs.items():
            if expr in body:
                print(f"  ✓ 清空 {label}  ({expr})")
            else:
                print(f"  ✗ 未清空 {label}  ({expr})")
                failed = True
        if "if (reset)" in nss_src and "clearHistoryResourcesOnReset(commandBuffer)" in nss_src:
            print("  ✓ dispatchSRApiContext 在 reset 帧调用了一次清空")
        else:
            print("  ✗ dispatchSRApiContext 未在 reset 帧调用清空！")
            failed = True
        # 清空必须用 GPU clear（vkCmdClearColorImage），不能用 staging 上传
        if "gpuClearTexture" in body or "clearTextureRGBA" in body:
            print("  ✓ 用 GPU clear 通道（clearTextureRGBA / vkCmdClearColorImage）")
        else:
            print("  ✗ 未使用 GPU clear（staging 上传 7 张图会非常慢）")
            failed = True

    # ★ _JitterOffsetTm1 在 reset 帧必须镜像当前 jitter（官方 ffx_nss.cpp:1146）
    print("\n═══ _JitterOffsetTm1 reset 镜像检查（官方 ffx_nss.cpp:1146）═══")
    if "boolean mirrorJitter = reset || !hasPreviousJitter;" in nss_src:
        print("  ✓ reset 帧镜像当前 jitter（避免重置后首个时序相位错配）")
        if re.search(r"params\.jitterPrevX\s*=\s*mirrorJitter\s*\?\s*jitter\.x\s*:\s*previousJitterX", nss_src):
            print("  ✓ jitterPrevX/Y 按 mirrorJitter 取值")
        else:
            print("  ✗ jitterPrevX/Y 未按 mirrorJitter 取值")
            failed = True
    else:
        print("  ✗ 缺少 reset 时的 jitter 镜像逻辑！")
        print("    官方：reset 帧 _JitterOffsetTm1 = 当前 jitter（注释原话")
        print("    「avoid a mismatched first temporal phase」），否则重置后首帧会错位。")
        failed = True

    # ★ clearTextureRGBA 的 color 数组长度契约（2026-09-26 实机踩坑）
    print("\n═══ clearTextureRGBA 分量数契约检查 ═══")
    vk = (ROOT / "common/src/main/java/io/homo/superresolution/core/graphics/vulkan/"
          "VulkanCommandDecoder.java").read_text(encoding="utf-8", errors="replace")
    m = re.search(r"public void clearTextureRGBA\(.*?\n    \}", vk, re.S)
    if not m:
        print("  ✗ 找不到 clearTextureRGBA")
        failed = True
    else:
        body = m.group(0)
        guarded = len(re.findall(r"color\.length\s*>\s*\d", body))
        if guarded >= 4:
            print(f"  ✓ 4 个槽位均有长度防护（缺失分量补 0），{guarded} 处")
        else:
            print(f"  ✗ 仅 {guarded} 处长度防护 → R8(1通道) 会在 color[1] 抛")
            print("    ArrayIndexOutOfBoundsException，R11G11B10F(3通道) 在 color[3] 抛。")
            print("    该异常会让整帧 vk 命令不提交 → 画面在超分/未超分间交替。")
            failed = True
        # 校验层必须仍要求等长，否则 gpuClearTexture 给的分量数会不匹配
        vcd = (ROOT / "common/src/main/java/io/homo/superresolution/core/graphics/impl/validation/"
               "ValidatedCommandDecoder.java").read_text(encoding="utf-8", errors="replace")
        if re.search(r"color\.length\s*!=\s*format\.getChannelCount\(\)", vcd):
            print("  ✓ 校验层仍要求 color.length == getChannelCount()（两层契约需一致）")
        else:
            print("  ! 校验层未要求等长 —— 请确认 gpuClearTexture 传的分量数与之匹配")
    # gpuClearTexture 必须按格式给分量数
    nss2 = (ROOT / "common/src/main/java/io/homo/superresolution/common/upscale/algo/nss/NSS.java"
            ).read_text(encoding="utf-8", errors="replace")
    if re.search(r"int channels = Math\.max\(texture\.getTextureFormat\(\)\.getChannelCount\(\), 1\)", nss2):
        print("  ✓ gpuClearTexture 按格式 getChannelCount() 给分量数")
    else:
        print("  ✗ gpuClearTexture 未按格式给分量数")
        failed = True

    # ★ interop 录制段的 begin/end 兜底（异常不得把未 end 的缓冲留给下一帧）
    print("\n═══ interop 命令缓冲 begin/end 异常兜底检查 ═══")
    gl = (ROOT / "common/src/main/java/io/homo/superresolution/common/upscale/interoplayer/"
          "GlVulkanInteropAlgorithm.java").read_text(encoding="utf-8", errors="replace")
    if re.search(r"commandBuffer\.begin\(\);\s*\n\s*try\s*\{", gl):
        print("  ✓ begin 后立即进 try（异常会被捕获）")
        if "已放弃本帧提交以避免命令缓冲状态错乱" in gl:
            print("  ✓ 异常路径记录日志 + 复位命令缓冲 + return false")
        else:
            print("  ! 有 try 但缺少明确的放弃/复位语义，请人工确认")
    else:
        print("  ✗ begin/end 仍是裸序列：一旦录制中抛异常，end()/submit 被跳过，")
        print("    未 end 的缓冲被放回 ring，下一帧 acquire 到它 → 该帧无超分 → 逐帧交替。")
        failed = True

    print("\n" + ("✗ 存在问题，请修正后重试" if failed else "✓ 全部通过"))
    return 1 if failed else 0

if __name__ == "__main__":
    sys.exit(main())
