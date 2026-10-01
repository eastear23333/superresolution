/*
 * Super Resolution
 * Copyright (c) 2026. 187J3X1-114514
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package io.homo.superresolution.common.upscale.algo.nss;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * NSS 三组 push constant / UBO 的布局与取值。
 *
 * <p>Arm 原版走 {@code push_constant, std430}；SR 框架暂无 push constant 通道，
 * 因此改用 {@code std140} uniform block（两块都无数组元素，且每个成员都带显式
 * {@code layout(offset=)}，std140 与 std430 的成员偏移在本例中完全一致）。
 *
 * <p>全部偏移、字段含义与实测取值来自 {@code nss-model/scenario} 的权威数据：
 * <ul>
 *   <li>{@code configs/960x540_1920x1080_{high,mid,low}_fragment.json} —— 每档的
 *       命令序列、绑定表、渲染范围与 shader build options</li>
 *   <li>{@code assets/960x540_1920x1080/*_push_consts__*.npy} —— 每档 push constant
 *       的<b>逐字节实测值</b>（144 / 120 / 40 字节），用于反推每个字段的公式</li>
 * </ul>
 *
 * <p>本类把「实测值 → 公式」的推导固定下来，方便后续与 oracle 对拍时逐字段核对。
 * 公式正确性依据见各字段注释里给出的实测样本。
 */
public final class NssFrameParams {

    /** 1_pre_process 的 UBO 大小（与 Arm push_constants_size 一致）。 */
    /** 152 B（Arm 的 144 B + SR 移植新增的 `_MotionToPixels` float2，按 16 B 对齐取 160）。 */
    public static final int PRE_PROCESS_SIZE = 160;
    /** 3_post_process 的 UBO 大小。 */
    /** 128 B（Arm 的 120 B + SR 移植新增的 `_MotionToPixels` float2）。 */
    public static final int POST_PROCESS_SIZE = 128;
    /** 0_depth_scatter / 0_depth_scatter_init 的 UBO 大小。 */
    /** 48 B（Arm 的 40 B + SR 移植新增的 `_MotionToPixels` float2）。 */
    public static final int DEPTH_SCATTER_SIZE = 48;
    /** 0_disocclusion_mask_lq 的 UBO：字段布局 = Arm push_constant（144 字节）。 */
    public static final int DISOCCLUSION_LQ_SIZE = 144;
    /**
     * 0_generate_offset_lut 的 UBO：ScaleFactor(16) + Jitter(16) + IdxModulo(8)
     * + ReducedInputModulo(8) = 48 字节。
     */
    public static final int LUT_GEN_SIZE = 48;

    /**
     * 深度剪辑的相对分离阈值。
     *
     * <p><b>这是全链路里唯一未能从数据反推出生成公式的常量</b>：它在 Arm 的
     * 960x540→1920x1080 测试场景里恒为 {@code 0.036522276699543}（三档相同），
     * 注释只说明「Depth-clip separation scale precomputed on the host」，没有
     * 给出推导。这里直接沿用该实测值，并按渲染高度做线性缩放以缓解分辨率差异；
     * 待与 oracle 对拍时应优先标定此值。
     */
    public static final float DEPTH_CLIP_REQUIRED_SEP_SCALE_AT_540P = 0.036522276699543f;
    /** 940x540 场景标定时的参考高度。 */
    public static final float DEPTH_CLIP_SEP_SCALE_REFERENCE_HEIGHT = 540.0f;
    /** 深度剪辑幂次（实测三档均为 3.0）。 */
    public static final float DEPTH_CLIP_POWER = 3.0f;

    // ─────────────────────────────── 帧输入 ───────────────────────────────

    /** 逻辑渲染分辨率（光影提供的输入尺寸）。 */
    public int renderWidth;
    public int renderHeight;
    /** 8 对齐后的张量尺寸。 */
    public int paddedWidth;
    public int paddedHeight;
    /** 预处理输出尺寸（HIGH 档 = 渲染分辨率；MID/LOW 档 = 渲染分辨率/2）。 */
    public int preprocessWidth;
    public int preprocessHeight;
    /** 深度散射输出尺寸（HIGH 档 = 渲染分辨率/2；MID/LOW 档 = 渲染分辨率/4）。 */
    public int depthScatterWidth;
    public int depthScatterHeight;
    /** 超分输出（屏幕）尺寸。 */
    public int screenWidth;
    public int screenHeight;
    /** KPN 张量尺寸（padded/4）与通道数（HIGH=36 / MID_LOW=16）。 */
    public int kpnWidth;
    public int kpnHeight;
    public int kpnChannels;

    /** 当前帧 jitter（渲染像素单位）。 */
    public float jitterX;
    public float jitterY;
    /** 上一帧 jitter（渲染像素单位）。 */
    public float jitterPrevX;
    public float jitterPrevY;

    /** 曝光倍数（= 1 表示不做曝光补偿）。 */
    public float exposure = 1.0f;

    /**
     * ★ 历史有效性标志，<b>必须写成官方的 {@code _NotHistoryReset} 语义</b>：
     * <pre>
     *   1.0 = 本帧<b>保留</b>历史（正常帧）   ← 与 `reset` 布尔值相反
     *   0.0 = 本帧<b>重置</b>历史（首帧 / resize / 显式 reset）
     * </pre>
     *
     * <p><b>官方命名与我的旧实现差异（曾导致抖动）</b>：
     * {@code ffx_nss_common_glsl.h:136} 字段名是 {@code _NotHistoryReset}，
     * 官方自己在 {@code ffx_nss_postprocess.h:63-67} 注释里承认
     * 「the reset variable ... should be renamed to NotHistoryReset」。
     *
     * <p>默认必须为 <b>1.0</b>（保留历史）。此前默认值为 1.0 但注释写成
     * 「1 表示本帧丢弃历史」，语义描述是反的；写入值
     * {@code reset ? 1 : 0} 更是<b>整个反了</b> —— 正常帧写 0 会让 shader
     * 每帧都走「重置」路径。修正为 {@code reset ? 0 : 1}。
     *
     * <p>消费点：
     * <ul>
     *   <li>前处理 {@code 1_pre_process_shared.h} —— {@code history_valid}
     *       乘到 {@code disocclusion_mask} 上（官方 preprocess:950-958）</li>
     *   <li>后处理 {@code 3_post_process_shared.h:918} —— {@code reset = half(_Reset)}，
     *       用于 {@code ClampHistoryToStats} 与 {@code learnt_masked_alpha}</li>
     * </ul>
     */
    public float notHistoryReset = 1.0f;

    /** 深度解线性化常量：(a, b, c, d)，见 {@link #computeDeviceToViewDepth}。 */
    public final float[] deviceToViewDepth = {1.0f, -1.0f, 1.0f, 1.0f};

    /** 深度剪辑相对分离阈值。 */
    public float depthClipRequiredSepScale = DEPTH_CLIP_REQUIRED_SEP_SCALE_AT_540P;
    /** 深度剪辑幂次。 */
    public float depthClipPower = DEPTH_CLIP_POWER;

    /**
     * 供 post_process 使用的 KPN 空间重映射偏移（静态 LUT 档专用）。
     *
     * <p>官方 {@code computeJitterTileOffset}（ffx_nss.cpp:1067-1085）按当前帧
     * jitter 相位算出 tile 格点内的重映射量，post 以
     * {@code tile_idx = (output_px + _LutOffset) & 1} 选核。**动态 LUT 档恒为 0**
     * （LUT 内容本身已按 jitter 生成）。
     */
    public int lutOffsetX;
    public int lutOffsetY;
    /**
     * 偏移 LUT 的 tile 模数 = {@code reducedFractionHrSize}（官方 ffx_nss.cpp:1199）。
     *
     * <p>静态 2x 时 = (2, 2)；动态比例时 = {@code displaySize / gcd(displaySize, renderSize)}。
     * 由 {@code NSS.updateFrameParams} 每帧按当前比例写入。
     */
    public int idxModuloX = 2;
    public int idxModuloY = 2;
    /**
     * {@code reducedFractionLrSize} = {@code renderSize / gcd}（官方 ffx_nss.cpp:1200）。
     * 供动态 LUT 生成（{@code AxisSourceForHr} 的 {@code reduced_input_modulo}）使用。
     */
    public int reducedInputModuloX = 1;
    public int reducedInputModuloY = 1;

    /**
     * 运动矢量纹理单位 → Arm 期望的「<b>backward 屏幕空间像素位移</b>」的换算系数。
     *
     * <p><b>★ 权威出处（逐字核对，勿再凭印象）</b>：
     * {@code neural-graphics-sdk-for-game-engines/sdk/include/FidelityFX/gpu/nss/}
     * <ul>
     *   <li>{@code ffx_nss_preprocess.h:261-267} —— {@code LoadMotion()}：
     *       {@code texelFetch(_MotionTex, pixel, 0).xy * MotionVectorScale()}。
     *       注意下游 {@code reproj_uv} 里的 {@code motion} 是<b>乘完 scale 之后</b>
     *       的值，不是原始纹理值。</li>
     *   <li>{@code ffx_nss_preprocess.h:889-890} ——
     *       {@code // Motion is backward direction in pixel space to match FSR/ASR}
     *       后接 {@code reproj_uv = uv + (motion * InvInputDims())}</li>
     *   <li>{@code ffx_nss_postprocess.h:777-778} ——
     *       {@code reproj_uv = uv + (float2(motion) * InvOutputDims())}</li>
     *   <li>{@code docs/user_guide.md:779} —— {@code motionVectorScale = -1.0f * maxRenderSize}</li>
     *   <li>{@code docs/user_guide.md:807} —— NSS 期望「经 motionVectorScale 之后的
     *       backward 屏幕空间 MV，范围 ±width/±height」</li>
     * </ul>
     *
     * <p><b>★ 换算推导</b>：光影写入 {@code _MotionVectorTex} 的是
     * （{@code Composite7.frag:160} / {@code :248-251}）
     * {@code velocity = prevProjectionPos - currPosition}（<b>backward</b> 归一化 UV），
     * 且 {@code #if SR_ENABLE} 下再乘 {@code upscaleRatio = screen/render}，
     * 即 {@code V_tex = V_backward * upscaleRatio}。
     * 要求重投影落在 {@code prev_uv}：
     * <pre>
     *   uv + V_backward * upscaleRatio * _MotionToPixels / renderSize = uv + V_backward
     *   ⇒  _MotionToPixels = renderSize / upscaleRatio = +renderSize * (render / screen)
     * </pre>
     * <b>结论：本字段只补幅度，取正号。</b>方向完全由 shader 侧与官方一致的
     * {@code +}（{@code uv + motion * Inv}）决定。数值验证见 {@code /tmp/mv_check.py}：
     * 正号 → {@code reproj_uv == prev_uv} ✓，负号 → 反向 ✗。
     *
     * <p><b>★ 历史教训（连错两轮）</b>：早期 shader 写成 {@code uv - motion}（与官方
     * {@code +} 相反），宿主又补负号 → 两次翻转抵消，等价于没翻；
     * 之后只翻 shader 又同时去掉宿主负号 —— 那一版才是正确解。
     * <b>除非 shader 的 {@code +} 被改回 {@code -}，否则此处永远不要加负号。</b>
     */
    public float motionToPixelsX = 1.0f;
    public float motionToPixelsY = 1.0f;

    /**
     * 按 FSR2 的约定计算 {@code _DeviceToViewDepth}：
     * {@code (d*c, e, 1/a, 1/b)}，其中 c/e 取自 FSR2 的二维查表
     * （{@code [inverted][infinite]}）。
     *
     * <p>该约定与 Arm 的实测样本<b>逐位吻合</b>：把测试场景解释成
     * {@code near = 10, far = +inf}（无穷远深度范围），fovY = 36.951520°，
     * aspect = 960/540 代入，得到
     * {@code (1.0000001192092896, -10.0, 0.5939999222755429, 0.33412495255470276)}，
     * 与 {@code 1_pre_process_push_consts__..._high_fragment.npy} 的前 16 字节
     * <b>float32 逐位一致</b>（已用 check_nss_ubo.py 穷举 (near, far, inverted,
     * infinite) 空间验证，唯一命中即上述组合）。
     *
     * <p>注意 {@code d.x} 在无穷远分支下与 near/far 无关（恒为 1+FLT_EPSILON）——
     * 这正是判定"场景用的是无穷远深度范围"的依据：有限远分支会得到
     * {@code d.y = fQ*fMin = -10.000001}，与实测的 -10.0 差 1 ULP。
     *
     * @param cameraNear      近平面
     * @param cameraFar       远平面；{@code +inf}（或非正）表示无穷远深度范围
     * @param depthInverted   true 表示 reverse-Z（1=近）
     * @param renderWidth     用于推导 aspect 的渲染宽度
     * @param renderHeight    用于推导 aspect 的渲染高度
     * @param fovYRadians     垂直 FOV（弧度）
     */
    public void computeDeviceToViewDepth(
            float cameraNear, float cameraFar, boolean depthInverted,
            int renderWidth, int renderHeight, float fovYRadians) {
        float fMin = Math.min(cameraNear, cameraFar);
        float fMax = Math.max(cameraNear, cameraFar);
        boolean infinite = !(fMax > 0.0f) || Float.isInfinite(fMax);
        if (depthInverted) {
            float tmp = fMin;
            fMin = fMax;
            fMax = tmp;
        }
        final float fltEpsilon = 1e-7f;
        // FSR2 的二维查表：c = matrix_elem_c[inverted][infinite]
        //                   e = matrix_elem_e[inverted][infinite]
        final float fq = fMax / (fMin - fMax);
        float c;
        float e;
        if (!depthInverted && !infinite) {
            c = fq;
            e = fq * fMin;
        } else if (!depthInverted) {
            c = -1.0f - fltEpsilon;
            e = -fMin - fltEpsilon;
        } else if (!infinite) {
            c = fq;
            e = fq * fMin;
        } else {
            c = 0.0f + fltEpsilon;
            e = fMax;
        }
        deviceToViewDepth[0] = -1.0f * c;
        deviceToViewDepth[1] = e;

        float aspect = (float) renderWidth / Math.max(1.0f, (float) renderHeight);
        float halfFov = 0.5f * fovYRadians;
        float cotHalfFovY = (float) (Math.cos(halfFov) / Math.sin(halfFov));
        float a = cotHalfFovY / aspect;
        float b = cotHalfFovY;
        deviceToViewDepth[2] = 1.0f / a;
        deviceToViewDepth[3] = 1.0f / b;
    }

    // ─────────────────────────── UBO 打包 ───────────────────────────

    /** 分配一个本机字节序的堆缓冲，供 {@code writeToBuffer} 使用。 */
    public static ByteBuffer allocate(int size) {
        return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
    }

    /**
     * 打包 1_pre_process 的 UBO（144 字节）。
     *
     * <p>字段取值全部对照实测样本（HIGH 档 960x540→1920x1080）：
     * <pre>
     *   _Scale            = preprocess/input          -> (1, 1)      [MID/LOW: (0.5, 0.5)]
     *   _OutputDims       = preprocess 逻辑尺寸        -> (960, 540)
     *   _InputDims        = 渲染分辨率                 -> (960, 540)
     *   _PaddedDims       = 8 对齐尺寸                 -> (960, 544)
     *   _InvDepthTm1Dims  = 1/深度散射尺寸             -> (1/480, 1/270)
     *   _RenderSize       = 屏幕分辨率                 -> (1920, 1080)
     *   _Exposure         = (exposure, 1/exposure)     -> (7.3890562, 0.13533528)
     * </pre>
     */
    public void writePreProcessUbo(ByteBuffer buf) {
        buf.clear();
        putVec4(buf, 0, deviceToViewDepth[0], deviceToViewDepth[1],
                deviceToViewDepth[2], deviceToViewDepth[3]);

        putVec4(buf, 16,
                jitterX, jitterY,
                jitterX / renderWidth, jitterY / renderHeight);
        putVec4(buf, 32,
                jitterPrevX, jitterPrevY,
                jitterPrevX / renderWidth, jitterPrevY / renderHeight);

        float scaleX = (float) preprocessWidth / renderWidth;
        float scaleY = (float) preprocessHeight / renderHeight;
        putVec2(buf, 48, scaleX, scaleY);
        putVec2(buf, 56, 1.0f / scaleX, 1.0f / scaleY);

        putVec2i(buf, 64, preprocessWidth, preprocessHeight);
        putVec2i(buf, 72, renderWidth, renderHeight);
        putVec2i(buf, 80, paddedWidth, paddedHeight);

        putVec2(buf, 88, 1.0f / preprocessWidth, 1.0f / preprocessHeight);
        putVec2(buf, 96, 1.0f / renderWidth, 1.0f / renderHeight);
        putVec2(buf, 104, 1.0f / paddedWidth, 1.0f / paddedHeight);
        putVec2(buf, 112, 1.0f / depthScatterWidth, 1.0f / depthScatterHeight);
        putVec2(buf, 120, screenWidth, screenHeight);

        float exposureSafe = exposure == 0.0f ? 1.0f : exposure;
        putVec2(buf, 128, exposureSafe, 1.0f / exposureSafe);

        buf.putFloat(136, depthClipRequiredSepScale);
        buf.putFloat(140, depthClipPower);
        // 偏移 [144, 151] = SR 移植扩展的 _MotionToPixels
        putVec2(buf, 144, motionToPixelsX, motionToPixelsY);
        // 偏移 [152, 155] = _NotHistoryReset（官方 _NotHistoryReset 的对应字段，
        // 前处理用它乘 disocclusion_mask，见 1_pre_process_shared.h 的 UBO 说明）
        buf.putFloat(152, notHistoryReset);
        buf.position(0).limit(PRE_PROCESS_SIZE);
    }

    /**
     * 打包 3_post_process 的 UBO（120 字节）。
     *
     * <p>对照实测样本（HIGH 档）：
     * <pre>
     *   _Scale          = screen/render        -> (2, 2)
     *   _OutputDims     = 屏幕分辨率            -> (1920, 1080)
     *   _InputDims      = 渲染分辨率            -> (960, 540)
     *   _KpnDims        = padded/4            -> (240, 136)
     *   _PaddedUvScale  = preprocess/padded    -> (1.0, 540/544 = 0.99264705)
     *   _KpnScale       = kpn/padded           -> (0.25, 0.25)
     *   _IdxModulo      = (2, 2)
     *   _PreprocessDims = preprocess 逻辑尺寸   -> (960, 540)
     * </pre>
     */
    public void writePostProcessUbo(ByteBuffer buf) {
        buf.clear();
        float scaleX = (float) screenWidth / renderWidth;
        float scaleY = (float) screenHeight / renderHeight;
        putVec2(buf, 0, scaleX, scaleY);
        putVec2(buf, 8, 1.0f / scaleX, 1.0f / scaleY);

        putVec2i(buf, 16, screenWidth, screenHeight);
        putVec2i(buf, 24, renderWidth, renderHeight);
        putVec2i(buf, 32, kpnWidth, kpnHeight);

        putVec2(buf, 40, 1.0f / screenWidth, 1.0f / screenHeight);
        putVec2(buf, 48, 1.0f / renderWidth, 1.0f / renderHeight);
        putVec2(buf, 56, 1.0f / kpnWidth, 1.0f / kpnHeight);
        putVec2(buf, 64,
                (float) preprocessWidth / paddedWidth,
                (float) preprocessHeight / paddedHeight);
        putVec2(buf, 72, (float) kpnWidth / paddedWidth, (float) kpnHeight / paddedHeight);

        putVec2i(buf, 80, idxModuloX, idxModuloY);

        float exposureSafe = exposure == 0.0f ? 1.0f : exposure;
        putVec2(buf, 88, exposureSafe, 1.0f / exposureSafe);

        buf.putFloat(96, notHistoryReset);
        // 偏移 [100, 103] 是 Arm 结构体里为对齐留的隐式填充，不写。

        putVec2i(buf, 104, lutOffsetX, lutOffsetY);
        putVec2i(buf, 112, preprocessWidth, preprocessHeight);
        // 偏移 [120, 127] = SR 移植扩展的 _MotionToPixels
        putVec2(buf, 120, motionToPixelsX, motionToPixelsY);
        buf.position(0).limit(POST_PROCESS_SIZE);
    }

    /**
     * 打包 0_depth_scatter / 0_depth_scatter_init 的 UBO（40 字节）。
     *
     * <p>对照实测样本：
     * <pre>
     *   _Scale        = input/depthScatter -> (2, 2)   [MID/LOW: (4, 4)]
     *   _OutputDims   = 深度散射尺寸        -> (480, 270)
     *   _RenderDims   = 屏幕分辨率          -> (1920, 1080)
     * </pre>
     */
    public void writeDepthScatterUbo(ByteBuffer buf) {
        buf.clear();
        float scaleX = (float) renderWidth / depthScatterWidth;
        float scaleY = (float) renderHeight / depthScatterHeight;
        putVec2(buf, 0, scaleX, scaleY);
        putVec2(buf, 8, 1.0f / scaleX, 1.0f / scaleY);
        putVec2i(buf, 16, depthScatterWidth, depthScatterHeight);
        putVec2(buf, 24, screenWidth, screenHeight);
        putVec2(buf, 32, 1.0f / depthScatterWidth, 1.0f / depthScatterHeight);
        // 偏移 [40, 47] = SR 移植扩展的 _MotionToPixels
        putVec2(buf, 40, motionToPixelsX, motionToPixelsY);
        buf.position(0).limit(DEPTH_SCATTER_SIZE);
    }

    /**
     * 打包 0_disocclusion_mask_lq 的 UBO（144 字节）。
     *
     * <p>字段布局与 Arm push_constant 完全一致（shader 侧见
     * 0_disocclusion_mask_lq_shared.h）。仅 MID/LOW 档使用：
     * <pre>
     *   _DeviceToViewDepth = deviceToViewDepth[0..3]
     *   _JitterOffset      = (jitterXY 像素, jitterXY/render)
     *   _JitterOffsetTm1   = 同上（上一帧）
     *   _Scale / _InvScale = preprocess 与 depth 域之比（render/2 与 depthScatter 之比 = 2）
     *   _OutputDims        = disocclusion 输出（= depthScatter 尺寸）
     *   _InputDims         = 渲染分辨率
     *   _PaddedDims        = preprocess padded
     *   _InvOutputDims     = 1/depthScatter
     *   _InvInputDims      = 1/render
     *   _InvPaddedDims     = 1/padded
     *   _InvDepthTm1Dims   = 1/depthScatter（上一帧深度散射输出域）
     *   _RenderSize        = 屏幕分辨率
     *   _Exposure          = (exposure, 1/exposure)
     *   _DepthClip*        = 与 preprocess 一致
     * </pre>
     * 实际被 shader 消费的字段只有 _DeviceToViewDepth / _InputDims / _DepthClip*；
     * 其余照 Arm 布局填写以保持一致。
     */
    public void writeDisocclusionLqUbo(ByteBuffer buf) {
        buf.clear();
        putVec4(buf, 0, deviceToViewDepth[0], deviceToViewDepth[1],
                deviceToViewDepth[2], deviceToViewDepth[3]);
        putVec4(buf, 16,
                jitterX, jitterY,
                jitterX / renderWidth, jitterY / renderHeight);
        putVec4(buf, 32,
                jitterPrevX, jitterPrevY,
                jitterPrevX / renderWidth, jitterPrevY / renderHeight);

        // disocclusion 工作在 depth 域（= depthScatter 尺寸，render/4）。
        // _Scale 语义对齐 depth_scatter 的用法：input/depth 域之比。
        float scaleX = (float) renderWidth / depthScatterWidth;
        float scaleY = (float) renderHeight / depthScatterHeight;
        putVec2(buf, 48, scaleX, scaleY);
        putVec2(buf, 56, 1.0f / scaleX, 1.0f / scaleY);

        putVec2i(buf, 64, depthScatterWidth, depthScatterHeight);   // _OutputDims
        putVec2i(buf, 72, renderWidth, renderHeight);               // _InputDims
        putVec2i(buf, 80, paddedWidth, paddedHeight);               // _PaddedDims

        putVec2(buf, 88, 1.0f / depthScatterWidth, 1.0f / depthScatterHeight); // _InvOutputDims
        putVec2(buf, 96, 1.0f / renderWidth, 1.0f / renderHeight);             // _InvInputDims
        putVec2(buf, 104, 1.0f / paddedWidth, 1.0f / paddedHeight);            // _InvPaddedDims
        putVec2(buf, 112, 1.0f / depthScatterWidth, 1.0f / depthScatterHeight); // _InvDepthTm1Dims
        putVec2(buf, 120, screenWidth, screenHeight);                          // _RenderSize

        float exposureSafe = exposure == 0.0f ? 1.0f : exposure;
        putVec2(buf, 128, exposureSafe, 1.0f / exposureSafe);

        buf.putFloat(136, depthClipRequiredSepScale);
        buf.putFloat(140, depthClipPower);
        buf.position(0).limit(DISOCCLUSION_LQ_SIZE);
    }

    /**
     * 打包 0_generate_offset_lut 的 UBO（48 字节）。
     *
     * <p>{@code _ScaleFactor} 的语义与官方 {@code ffx_nss.cpp:1165-1168} 一致：
     * {@code .xy = _OutputDims / _InputDims}（= 屏幕/渲染，即放大比），
     * {@code .zw = _InputDims / _OutputDims}（= 逆放大比）。
     */
    public void writeLutGenUbo(ByteBuffer buf) {
        buf.clear();
        float scaleX = renderWidth > 0 ? (float) screenWidth / renderWidth : 1.0f;
        float scaleY = renderHeight > 0 ? (float) screenHeight / renderHeight : 1.0f;
        putVec4(buf, 0, scaleX, scaleY, 1.0f / scaleX, 1.0f / scaleY);
        putVec4(buf, 16, jitterX, jitterY,
                jitterX / renderWidth, jitterY / renderHeight);
        putVec2i(buf, 32, idxModuloX, idxModuloY);
        putVec2i(buf, 40, reducedInputModuloX, reducedInputModuloY);
        buf.position(0).limit(LUT_GEN_SIZE);
    }

    /**
     * 按当前帧参数更新 LUT 相关的四个整数常量。
     *
     * <p><b>逐字复现官方 host 逻辑</b>：
     * <ul>
     *   <li>{@code ffx_nss.cpp:1199-1200} —— {@code _IdxModulo = reducedFractionHrSize
     *       = displaySize / gcd(displaySize, renderSize)}，
     *       {@code _ReducedInputModulo = reducedFractionLrSize = renderSize / gcd}</li>
     *   <li>静态 2x 档官方直接写死 (2,2)/(1,1)（{@code ffx_nss.cpp:752-757}），
     *       与 gcd 公式结果一致，此处统一走 gcd 公式即可。</li>
     *   <li>{@code ffx_nss.cpp:1067-1085 computeJitterTileOffset} —— 仅静态档
     *       （{@code !useDynamicOffsetLut}）计算 {@code _LutOffset}；动态档恒 0。</li>
     * </ul>
     *
     * @param dynamicLut {@code true} = 非 2x 动态 LUT 档（LutOffset 恒 0）
     */
    public void updateLutConstants(boolean dynamicLut) {
        int gcdW = gcd(screenWidth, renderWidth);
        int gcdH = gcd(screenHeight, renderHeight);
        idxModuloX = Math.max(gcdW > 0 ? screenWidth / gcdW : 2, 1);
        idxModuloY = Math.max(gcdH > 0 ? screenHeight / gcdH : 2, 1);
        reducedInputModuloX = Math.max(gcdW > 0 ? renderWidth / gcdW : 1, 1);
        reducedInputModuloY = Math.max(gcdH > 0 ? renderHeight / gcdH : 1, 1);

        if (dynamicLut) {
            // 官方：动态 LUT 的 jitter 相位已烘焙进 LUT 内容，不再重映射 tile。
            lutOffsetX = 0;
            lutOffsetY = 0;
            return;
        }

        // ── 官方 computeJitterTileOffset（静态 2x 档）──
        // 把 base 与 jittered 的 LR 像素中心分别投影到 HR 索引空间，取差值模
        // modulo 网格尺寸。这正是「jitter 相位 → 选核 pattern」的映射：
        // 不同 jitter 子格必须配不同的 kernel pattern，否则相位错配。
        float sx = renderWidth > 0 ? (float) screenWidth / renderWidth : 2.0f;
        float sy = renderHeight > 0 ? (float) screenHeight / renderHeight : 2.0f;
        int baseHrX = (int) Math.floor(0.5f * sx);
        int baseHrY = (int) Math.floor(0.5f * sy);
        int jitteredHrX = (int) Math.floor((jitterX + 0.5f) * sx);
        int jitteredHrY = (int) Math.floor((jitterY + 0.5f) * sy);

        int dx = (jitteredHrX - baseHrX) % idxModuloX;
        if (dx < 0) {
            dx += idxModuloX;
        }
        int dy = (jitteredHrY - baseHrY) % idxModuloY;
        if (dy < 0) {
            dy += idxModuloY;
        }
        lutOffsetX = dx;
        lutOffsetY = dy;
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return a;
    }

    // ─────────────────────────── 写入工具 ───────────────────────────

    private static void putVec2(ByteBuffer buf, int offset, float x, float y) {
        buf.putFloat(offset, x);
        buf.putFloat(offset + 4, y);
    }

    private static void putVec2i(ByteBuffer buf, int offset, int x, int y) {
        buf.putInt(offset, x);
        buf.putInt(offset + 4, y);
    }

    private static void putVec4(ByteBuffer buf, int offset, float x, float y, float z, float w) {
        buf.putFloat(offset, x);
        buf.putFloat(offset + 4, y);
        buf.putFloat(offset + 8, z);
        buf.putFloat(offset + 12, w);
    }
}
