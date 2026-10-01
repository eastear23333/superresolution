//
// -----------------------------------------------------------------------------
// The proprietary software and information contained in this file is
// confidential and may only be used by an authorized person under a valid
// licensing agreement from Arm Limited or its affiliates.
//
// Copyright (C) 2026 Arm Limited or its affiliates. All rights reserved.
//
// This entire notice must be reproduced on all copies of this file and
// copies of this file may only be made by an authorized person under a valid
// licensing agreement from Arm Limited or its affiliates.
// -----------------------------------------------------------------------------
//

#ifndef NSS_V1_PRE_PROCESS_SHARED_H
#define NSS_V1_PRE_PROCESS_SHARED_H

#ifndef NSS_INPUT_LAYOUT
    // 0: full-resolution preprocess outputs, 1: half-resolution outputs with packed nearest-depth offsets.
    #define NSS_INPUT_LAYOUT 0
#endif // !NSS_INPUT_LAYOUT
#ifndef NSS_V1_FULL_RES_LUMA_DERIVATIVE
    #define NSS_V1_FULL_RES_LUMA_DERIVATIVE (NSS_INPUT_LAYOUT == 0)
#endif // !NSS_V1_FULL_RES_LUMA_DERIVATIVE
#ifndef NSS_V1_HALF_RES_LUMA_DERIVATIVE
    #define NSS_V1_HALF_RES_LUMA_DERIVATIVE (NSS_INPUT_LAYOUT == 1)
#endif // !NSS_V1_HALF_RES_LUMA_DERIVATIVE
#ifndef NSS_YCOCG_LUMA_DERIVATIVE
    #define NSS_YCOCG_LUMA_DERIVATIVE (NSS_V1_FULL_RES_LUMA_DERIVATIVE || NSS_V1_HALF_RES_LUMA_DERIVATIVE)
#endif // !NSS_YCOCG_LUMA_DERIVATIVE

// Layout mirrors Slang's `PushConsts` exactly to preserve parity and avoid
// driver-dependent packing surprises.
//
// 移植说明（SR 框架）：原 Arm 实现走 push_constant；SR 框架暂不提供
// push constant 通道，故提供 NSS_USE_UBO 开关改绑 std140 uniform block。
// 本块无数组元素，std140 与 std430 的成员偏移完全一致（float2 按 8B、
// float4 按 16B 对齐，与显式 offset 声明相符），故布局零差异。
#ifdef NSS_USE_UBO
// 全部资源落在 set=0：binding 9（0..6 是采样器，7 是 DisocclusionMask，
// 8 是 InputTensorBuffer）。SR 框架后端只支持 push descriptor，而
// VUID-VkPipelineLayoutCreateInfo-pSetLayouts-00293 禁止一个管线布局里出现
// 两个 push descriptor set —— 用两个 set 会让 set 0 永远无法绑定。
layout(std140, set = 0, binding = 9) uniform PushConstants {
#else
layout(push_constant, std430) uniform PushConstants {
#endif
    // ─────────────── 16-byte aligned ───────────────
    // Projection params used to reconstruct view-space depth/position.
    layout(offset =   0) float4  _DeviceToViewDepth;      //  16 B
    // Current-frame jitter as (x, y, x/input_w, y/input_h).
    layout(offset =  16) float4  _JitterOffset;           //  16 B
    // Previous-frame jitter in the same packed layout as `_JitterOffset`.
    layout(offset =  32) float4  _JitterOffsetTm1;        //  16 B

    // ───────────────  8-byte aligned ───────────────
    // Logical preprocess extent divided by low-res input extent.
    layout(offset =  48) float2  _Scale;                  //   8 B
    // Inverse of `_Scale`.
    layout(offset =  56) float2  _InvScale;               //   8 B
    // Logical pre-process output dimensions.
    layout(offset =  64) int32_t2 _OutputDims;            //   8 B
    // Low-resolution input dimensions.
    layout(offset =  72) int32_t2 _InputDims;             //   8 B
    // Padded graph/input-tensor dimensions.
    layout(offset =  80) int32_t2 _PaddedDims;            //   8 B
    // Inverse of `_OutputDims`.
    layout(offset =  88) float2  _InvOutputDims;          //   8 B
    // Inverse of `_InputDims`.
    layout(offset =  96) float2  _InvInputDims;           //   8 B
    // Inverse of `_PaddedDims`.
    layout(offset = 104) float2  _InvPaddedDims;          //   8 B
    // Inverse dimensions of the previous-depth feedback surface.
    layout(offset = 112) float2  _InvDepthTm1Dims;        //   8 B
    // Render-resolution dimensions used by depth-clip shaping.
    layout(offset = 120) float2  _RenderSize;             //   8 B
    // Exposure packed as (exposure, 1/exposure).
    layout(offset = 128) float2  _Exposure;               //   8 B

    // ───────────────  4-byte aligned ───────────────
    // Depth-clip separation scale precomputed on the host.
    layout(offset = 136) float _DepthClipRequiredSepScale; //   4 B
    // Depth-clip power/exponent precomputed on the host.
    layout(offset = 140) float _DepthClipPower;            //   4 B

    // ───────────────  8-byte aligned (SR 移植扩展) ───────────────
    // 运动矢量纹理单位 → Arm 期望的「backward 屏幕空间像素位移」的换算系数。
    //
    // ★ 权威出处（逐字核对，勿再凭印象）：
    //   neural-graphics-sdk-for-game-engines/sdk/include/FidelityFX/gpu/nss/
    //     ffx_nss_preprocess.h:261-267   LoadMotion()
    //         float2 v = texelFetch(_MotionTex, pixel, 0).xy * MotionVectorScale();
    //     ffx_nss_preprocess.h:889-890
    //         // Motion is backward direction in pixel space to match FSR/ASR
    //         lane.reproj_uv = lane.uv **+ (lane.motion * InvInputDims());**
    //     ffx_nss_postprocess.h:777-778
    //         half2  motion    = LoadMotionPost(output_px);
    //         float2 reproj_uv = uv **+ (float2(motion) * InvOutputDims());**
    //     docs/user_guide.md:779 / :807  motionVectorScale = -renderSize；
    //         经 scale 后 result 才是 backward 屏幕空间像素位移。
    //
    //   → **两处 shader 都是加号**。注意 (3) 里的 `motion` 是 LoadMotion 之后
    //     的值（已乘 scale），不是原始纹理值。
    //
    // 【与 SR 光影接口的换算 —— 务必按此核对】
    //   光影写入 _MotionVectorTex 的是（Composite7.frag:160 / 248-251）：
    //         velocity = prevProjectionPos - currPosition   ← backward 归一化 UV
    //         再乘 upscaleRatio = screen/render（#if SR_ENABLE）
    //   即 V_tex = V_backward * upscaleRatio。
    //   要让 `uv + LoadMotion * Inv` 落在 prev_uv 上：
    //         uv + V_backward * upscaleRatio * _MotionToPixels / renderSize = uv + V_backward
    //     ⇒   _MotionToPixels = renderSize / upscaleRatio = **+renderSize * render/screen**
    //   ★ 即宿主只补**幅度**，符号一律交给这里的 `+`。
    //     数值验证：/tmp/mv_check.py（正号 → reproj_uv == prev_uv）。
    //
    // ★ 历史教训（连错两轮）：早期这里写成 `uv - motion`（也是"对自身代码的
    //   循环论证"），于是宿主补了负号；之后只翻 shader 又同时去掉宿主负号 ——
    //   那一版正是**正确解**。若再"发现抖动"就把宿主负号加回来，等于把方向
    //   又翻反一次，会重犯旧错。**除非这里的 `+` 被改回 `-`，宿主永不加负号。**
    layout(offset = 144) float2 _MotionToPixels;           //   8 B

    // ───────────────  4-byte aligned ───────────────
    /*
     * ★★ 历史有效性标志 —— **官方叫 `_NotHistoryReset`，与我原来理解的 `_Reset` 语义相反**！
     *
     * 官方定义（ffx_nss_common_glsl.h:136 / :171-173）：
     *     float _NotHistoryReset;                       // 4 B
     *     half NotHistoryReset() { return half(cbNSS._NotHistoryReset); }
     *
     * 官方自己在 ffx_nss_postprocess.h:63-67 就吐槽过这个命名：
     *     // Note: the reset variable in reference implementation is a bit confusing
     *     // based on its usage and value, it should be renamed to NotHistoryReset.
     *
     * 取值语义：
     *     1.0 = 本帧**保留**历史（正常帧）
     *     0.0 = 本帧**重置**历史（首帧 / resize / 显式 reset）
     *
     * 唯一消费点（ffx_nss_preprocess.h:950-958）：
     *     half history_valid = NotHistoryReset();
     *     half disocclusion_mask = half(ComputeDepthClipInt(...));
     *     disocclusion_mask *= history_valid;        // ← ★ 关键
     *
     * 【为什么缺这一行会「整体平移式抖动」】
     *   `disocclusion_mask` 是「本帧该像素是否被遮挡 / 新暴露」的掩码，
     *   由重投影后的深度比较得出。而**每帧的 jitter 都在变**，使这个
     *   二值判定在阈值附近反复翻转（flapping）。掩码一翻转：
     *     - `WarpFeedback`（:424）在 `mask > 0.01` 时把反馈张量清零
     *     - `CalculateLumaDerivative`（:711）把 luma 导数按 mask 截断
     *   于是时序状态在「清零 / 保留」间来回跳 → 画面整体平移式抖动。
     *   乘上 `history_valid` 后（正常帧 = 1）语义不变；
     *   而显式 reset（= 0）时掩码恒为 0，抖动随之消失 ——
     *   这正是「打开 nss_force_history_reset 后抖动消失」的机制。
     */
    layout(offset = 152) float _NotHistoryReset;           //   4 B
                                                   // Total: **160 bytes**
};

const float kEps = 1e-7;
const float kDepthScale = 2147483647.0;
const float kInvDepthScale = 1.0 / kDepthScale;
const float kMotionThreshold = 0.1;
const float kMotionThresholdSq = kMotionThreshold * kMotionThreshold;
// QAT metadata (`_PreprocessTensor` SINT).
const half2 kPreprocessQuant = half2(1.0 / 0.003908163867890835, -128.0);
// Temporal feedback is stored as SNORM alias of the graph int8 output, so
// convert sampled [-1, 1] values back into the model's [0, 1] domain.
const half2 kTemporalFeedbackQuant = half2(0.49999999813735485, -1.0);

#if !NSS_YCOCG_LUMA_DERIVATIVE
    const half kDerivativeDisThresh = 0.01HF;
    const half kDerivMin = 0.05HF;
    const half kDerivMax = 0.3HF;
    const half kDerivAlpha = 0.1HF;
#endif // !NSS_YCOCG_LUMA_DERIVATIVE

#ifdef INVERTED_DEPTH
    #define NSS_NEAREST_STEP(curr_depth, cand_depth) step(curr_depth, cand_depth)
    #define NSS_PLANE_DEPTH(prev_depth, curr_depth) min(prev_depth, curr_depth)
#else
    #define NSS_NEAREST_STEP(curr_depth, cand_depth) step(cand_depth, curr_depth)
    #define NSS_PLANE_DEPTH(prev_depth, curr_depth) max(prev_depth, curr_depth)
#endif // INVERTED_DEPTH

bool IsOnScreen(int32_t2 pos, int32_t2 size)
{
    return all(lessThan(pos, size)) && all(greaterThanEqual(pos, int32_t2(0)));
}

int32_t ReflectIndex(int32_t coord, int32_t size)
{
    return coord < 0 ? -coord - 1 : (coord >= size ? (2 * size - coord - 1) : coord);
}

int32_t2 ReflectIndex(int32_t2 coord, int32_t2 size)
{
    // Matches PyTorch reflect padding used by model-side preprocessing.
    return int32_t2(ReflectIndex(coord.x, size.x), ReflectIndex(coord.y, size.y));
}

int32_t2 GetProcessDims()
{
    return _OutputDims;
}

float2 GetDepthTm1DimsF()
{
    return rcp(max(_InvDepthTm1Dims, float2(kEps)));
}

int32_t2 GetDepthTm1DimsI()
{
    return int32_t2(round(GetDepthTm1DimsF()));
}

int32_t2 ProcessCoordToInputCoord(int32_t2 process_coord)
{
#if NSS_INPUT_LAYOUT == 1
    float2 scaled = (float2(process_coord) + float2(0.5)) * _InvScale;
    return clamp(int32_t2(floor(scaled)), int32_t2(0), _InputDims - int32_t2(1));
#else
    return clamp(process_coord, int32_t2(0), _InputDims - int32_t2(1));
#endif // NSS_INPUT_LAYOUT == 1
}

int32_t2 InputCoordToDepthCoord(int32_t2 input_coord)
{
    int32_t2 depth_size = GetDepthTm1DimsI();
    float2 scaled = (float2(input_coord) + float2(0.5)) * GetDepthTm1DimsF() * _InvInputDims;
    return clamp(int32_t2(floor(scaled)), int32_t2(0), depth_size - int32_t2(1));
}

float2 MotionToPaddedUvDelta(float2 motion)
{
#if NSS_INPUT_LAYOUT == 1
    return motion * _Scale * _InvPaddedDims;
#else
    return motion * _InvPaddedDims;
#endif // NSS_INPUT_LAYOUT == 1
}

struct BilinearSamplingData {
    int32_t2 iOffsets[4];
    float fWeights[4];
    int32_t2 iBasePos;
};

BilinearSamplingData GetBilinearSamplingData(float2 uv, int32_t2 size)
{
    // Convert uv -> bilinear footprint (base coordinate + 4 weights).
    BilinearSamplingData data;

    float2 fPxSample = (uv * float2(size)) - float2(0.5);
    data.iBasePos = int32_t2(floor(fPxSample));
    float2 fPxFrac = fract(fPxSample);

    data.iOffsets[0] = int32_t2(0, 0);
    data.iOffsets[1] = int32_t2(1, 0);
    data.iOffsets[2] = int32_t2(0, 1);
    data.iOffsets[3] = int32_t2(1, 1);

    data.fWeights[0] = (1.0 - fPxFrac.x) * (1.0 - fPxFrac.y);
    data.fWeights[1] = fPxFrac.x * (1.0 - fPxFrac.y);
    data.fWeights[2] = (1.0 - fPxFrac.x) * fPxFrac.y;
    data.fWeights[3] = fPxFrac.x * fPxFrac.y;
    return data;
}

float2 ComputeNdc(float2 pixPos, int32_t2 size)
{
    // Vulkan-style viewport mapping with Y flip.
    return pixPos / float2(size) * float2(2.0, -2.0) + float2(-1.0, 1.0);
}

float GetViewSpaceDepth(float depth, float4 device_to_view)
{
    return device_to_view.y / (depth - device_to_view.x);
}

float3 GetViewSpacePosition(int32_t2 viewport_pos, int32_t2 viewport_size, float device_depth, float4 device_to_view)
{
    // Reconstruct view-space position from depth and projection params.
    float z = GetViewSpaceDepth(device_depth, device_to_view);
    float2 ndc = ComputeNdc(float2(viewport_pos), viewport_size);
    float x = device_to_view.z * ndc.x * z;
    float y = device_to_view.w * ndc.y * z;
    return float3(x, y, z);
}

void FindNearestDepth_4x4_FromPixel(
    int32_t2 px,
    int32_t2 size,
    out float nearest_depth,
    out int32_t2 nearest_offset)
{
    //-------------------------------------------------------------------------
    // Depth dilation:
    // Search a 4x4-ish local neighborhood around the current pixel and pick
    // the sample closest to camera. This stabilizes motion/depth decisions at
    // geometric edges and thin features.
    //-------------------------------------------------------------------------
    float2 inv_size = rcp(float2(size));

    float nearest_depth_local = 0.0;
    float2 nearest_offset_f = float2(0.0);

#define NSS_UPDATE_NEAREST_STEP(OFF_X, OFF_Y, DEPTH)                                                      \
    {                                                                                                      \
        int32_t2 _offset_i = int32_t2((OFF_X), (OFF_Y));                                                  \
        int32_t2 _pos_i = px + _offset_i;                                                                  \
        float _on_screen = float(IsOnScreen(_pos_i, size));                                                \
        float _d = (DEPTH);                                                                                \
        float _take = _on_screen * NSS_NEAREST_STEP(nearest_depth_local, _d);                             \
        nearest_depth_local = mix(nearest_depth_local, _d, _take);                                         \
        nearest_offset_f = mix(nearest_offset_f, float2(_offset_i), _take);                               \
    }

    // q00 covers offsets: (-1,-1), (0,-1), (-1,0), (0,0)
    float4 q00 = textureGather(_DepthTex, (float2(px + int32_t2(-1, -1)) + float2(0.5)) * inv_size, 0).wzxy;
    nearest_depth_local = q00.w; // (0,0)
    NSS_UPDATE_NEAREST_STEP(-1, +0, q00.z); // (-1,  0)
    NSS_UPDATE_NEAREST_STEP(+0, -1, q00.y); // ( 0, -1)
    NSS_UPDATE_NEAREST_STEP(-1, -1, q00.x); // (-1, -1)

    // q10 covers offsets: (1,-1), (2,-1), (1,0), (2,0)
    float4 q10 = textureGather(_DepthTex, (float2(px + int32_t2(+1, -1)) + float2(0.5)) * inv_size, 0).wzxy;
    NSS_UPDATE_NEAREST_STEP(+1, +0, q10.z); // ( 1,  0)
    NSS_UPDATE_NEAREST_STEP(+1, -1, q10.x); // ( 1, -1)
    NSS_UPDATE_NEAREST_STEP(+2, -1, q10.y); // ( 2, -1)
    NSS_UPDATE_NEAREST_STEP(+2, +0, q10.w); // ( 2,  0)

    // q01 covers offsets: (-1,1), (0,1), (-1,2), (0,2)
    float4 q01 = textureGather(_DepthTex, (float2(px + int32_t2(-1, +1)) + float2(0.5)) * inv_size, 0).wzxy;
    NSS_UPDATE_NEAREST_STEP(+0, +1, q01.y); // ( 0,  1)
    NSS_UPDATE_NEAREST_STEP(-1, +1, q01.x); // (-1,  1)
    NSS_UPDATE_NEAREST_STEP(+0, +2, q01.w); // ( 0,  2)
    NSS_UPDATE_NEAREST_STEP(-1, +2, q01.z); // (-1,  2)

    // q11 covers offsets: (1,1), (2,1), (1,2), (2,2)
    float4 q11 = textureGather(_DepthTex, (float2(px + int32_t2(+1, +1)) + float2(0.5)) * inv_size, 0).wzxy;
    NSS_UPDATE_NEAREST_STEP(+1, +1, q11.x); // ( 1,  1)
    NSS_UPDATE_NEAREST_STEP(+2, +1, q11.y); // ( 2,  1)
    NSS_UPDATE_NEAREST_STEP(+1, +2, q11.z); // ( 1,  2)
    NSS_UPDATE_NEAREST_STEP(+2, +2, q11.w); // ( 2,  2)

#undef NSS_UPDATE_NEAREST_STEP

    nearest_depth = nearest_depth_local;
    nearest_offset = int32_t2(nearest_offset_f);
}

void FindNearestDepth_4x4(
    float2 uv,
    out float nearest_depth,
    out int32_t2 nearest_offset)
{
    int32_t2 px = int32_t2(uv * float2(_InputDims));
    FindNearestDepth_4x4_FromPixel(px, _InputDims, nearest_depth, nearest_offset);
}

float ComputeDepthClipInt(
    float2 uv,
    float depth_current,
    float2 render_size,
    float4 device_to_view)
{
    //-------------------------------------------------------------------------
    // Depth clip (best-effort integer previous-depth variant):
    // Estimate disocclusion by comparing current depth to reprojected previous
    // depth neighborhood with FOV/resolution-aware thresholds.
    //-------------------------------------------------------------------------
    const float bilinear_weight_threshold = 0.1;
    int32_t2 depth_tm1_size = GetDepthTm1DimsI();
    float current_view_depth = GetViewSpaceDepth(depth_current, device_to_view);
    float2 sample_px = (uv * float2(depth_tm1_size)) - float2(0.5);
    int32_t2 sample_base = int32_t2(floor(sample_px));
    float2 sample_frac = fract(sample_px);

    float w00 = (1.0 - sample_frac.x) * (1.0 - sample_frac.y);
    float w10 = sample_frac.x * (1.0 - sample_frac.y);
    float w01 = (1.0 - sample_frac.x) * sample_frac.y;
    float w11 = sample_frac.x * sample_frac.y;

    float required_sep_scale = _DepthClipRequiredSepScale;
    float depth_clip_power = _DepthClipPower;

    float fDepth = 0.0;
    float fWeightSum = 0.0;

#define NSS_DEPTH_CLIP_SAMPLE_BLOCK(SAMPLE_POS, SAMPLE_WEIGHT)                                             \
    {                                                                                                      \
        int32_t2 sample_pos = (SAMPLE_POS);                                                                \
        float weight = (SAMPLE_WEIGHT);                                                                    \
        bool onscreen = IsOnScreen(sample_pos, depth_tm1_size);                                            \
        fWeightSum += onscreen ? 0.0 : weight;                                                             \
        if (onscreen && weight > bilinear_weight_threshold) {                                              \
            float prev_depth = float(texelFetch(_DepthTm1Tex, sample_pos, 0).r) * kInvDepthScale;        \
            float prev_view_depth = GetViewSpaceDepth(prev_depth, device_to_view);                         \
            float depth_diff = current_view_depth - prev_view_depth;                                       \
            if (depth_diff > 0.0) {                                                                        \
                float depth_threshold = max(current_view_depth, prev_view_depth);                          \
                float required_sep = required_sep_scale * depth_threshold;                                 \
                float sep_ratio = saturate(required_sep / max(depth_diff, kEps));                         \
                fDepth += pow(sep_ratio, depth_clip_power) * weight;                                       \
                fWeightSum += weight;                                                                      \
            }                                                                                              \
        }                                                                                                  \
    }

    NSS_DEPTH_CLIP_SAMPLE_BLOCK(sample_base + int32_t2(0, 0), w00);
    NSS_DEPTH_CLIP_SAMPLE_BLOCK(sample_base + int32_t2(1, 0), w10);
    NSS_DEPTH_CLIP_SAMPLE_BLOCK(sample_base + int32_t2(0, 1), w01);
    NSS_DEPTH_CLIP_SAMPLE_BLOCK(sample_base + int32_t2(1, 1), w11);

#undef NSS_DEPTH_CLIP_SAMPLE_BLOCK

    return fWeightSum > 0.0 ? saturate(1.0 - fDepth / fWeightSum) : 0.0;
}

float2 LoadMotion(int32_t2 pixel)
{
    pixel = clamp(pixel, int32_t2(0), _InputDims - int32_t2(1));
    float2 v = texelFetch(_MotionVectorTex, pixel, 0).xy;
    // SR 移植：换算成 Arm 期望的「forward 渲染分辨率像素位移」（含符号）。
    // Arm 后续所有算式（阈值、重投影、MotionToPaddedUvDelta、
    // CalculateMotionDetector）都按像素语义书写。
    v *= _MotionToPixels;
    v *= float(dot(v, v) > kMotionThresholdSq);
    return v;
}

half3 LoadColourUnjittered(float2 uv)
{
    // Exposure + Karis tonemap keeps network input numerically bounded.
    return Tonemap(SafeColour(half3(textureLod(_ColourTex, uv, 0.0).rgb) * half3(_Exposure.x)));
}

half3 LoadColourForDerivativeAtResolvedPixel(int32_t2 sample_coord)
{
    half3 c = half3(texelFetch(_ColourTex, sample_coord, 0).rgb);
    c = max(c * half(_Exposure.x), half3(0.HF));
    return sqrt(c);
}

half3 LoadColourForDerivativeAtPixel(int32_t2 pixel)
{
    return LoadColourForDerivativeAtResolvedPixel(ReflectIndex(pixel, _InputDims));
}

half3 WarpHistory(float2 uv)
{
    return Tonemap(SafeColour(half3(textureLod(_HistoryTex, uv, 0.0).rgb) * half3(_Exposure.x)));
}

half4 WarpFeedback(float2 uv, half disocclusion_mask)
{
    // Reset temporal features when disoccluded to avoid ghost carry-over.
    half4 feedback = Dequantize(half4(textureLod(_FeedbackTensor, uv, 0.0)), kTemporalFeedbackQuant);
    return mix(feedback, half4(0.HF), half(disocclusion_mask > 0.01HF));
}

half4 LoadDerivativeTm1(float2 uv)
{
    return half4(textureLod(_LumaDerivTm1Tex, uv, 0.0));
}

half CalculateMotionDetector(float2 vector, float2 render_size)
{
    // Convert motion magnitude into a bounded [0,1] detector feature.
    float2 inv_render_size = rcp(render_size);
    float k_pix_min = length(inv_render_size);
    float k_pix_max = 200.0 * k_pix_min;
    float k_pix_denom = rcp(max(k_pix_max - k_pix_min, kEps));

    float2 motion_norm = vector * inv_render_size;
    float motion_length = length(motion_norm);
    return half(sqrt((clamp(motion_length, k_pix_min, k_pix_max) - k_pix_min) * k_pix_denom));
}

#if NSS_YCOCG_LUMA_DERIVATIVE

// Derivative history is persisted through an R8G8B8A8_SNORM image. These
// constants map between sampled SNORM values and the detector's state:
// Y=[0, 8], Co/Cg=[-8, 8], instability=[0, 1].
const half4 kDerivativeStorageDecodeScale = half4(4.HF, 8.HF, 8.HF, 0.5HF);
const half4 kDerivativeStorageDecodeBias = half4(4.HF, 0.HF, 0.HF, 0.5HF);
const half4 kDerivativeStorageEncodeScale = half4(0.25HF, 0.125HF, 0.125HF, 2.HF);
const half4 kDerivativeStorageEncodeBias = half4(-1.HF, 0.HF, 0.HF, -1.HF);
const half4 kDerivativeStorageZeroState = half4(-1.HF, 0.HF, 0.HF, -1.HF);

half4 RGBToYCoCg(half3 rgb)
{
    half co = rgb.r - rgb.b;
    half t = rgb.b + co * 0.5HF;
    half cg = rgb.g - t;
    half y = t + cg * 0.5HF;
    return half4(y, co, cg, 0.HF);
}

half ComputeDerivativeDelta(half4 ycocg_a, half4 ycocg_b, half4 delta_weight)
{
    half4 delta = ycocg_a - ycocg_b;
    return sqrt(dot(delta * delta, delta_weight));
}

half4 DecodeDerivativeStateFromStorage(half4 stored)
{
    // Previous derivative state is sampled from the SNORM history image in
    // [-1, 1]. Decode it back into the detector's YCoCg/instability domain
    // before comparing it against the current frame.
    return stored * kDerivativeStorageDecodeScale + kDerivativeStorageDecodeBias;
}

half4 EncodeDerivativeStateForStorage(half4 state)
{
    // The side-output image is R8G8B8A8_SNORM, not a float history buffer.
    // Encode the HDR-derived YCoCg state into fixed SNORM ranges, while keeping
    // instability linear so the network-visible derivative preserves precision.
    return state * kDerivativeStorageEncodeScale + kDerivativeStorageEncodeBias;
}

half4 EmptyDerivativeStateForStorage()
{
    return kDerivativeStorageZeroState;
}

half4 CalculateLumaDerivative(
    int32_t2 ref_coord,
    float2 derivative_uv,
    float2 derivative_inv_dims,
    half4 deriv_tm1_h,
    half disocclusion_mask,
    out half instability_out)
{
    const half derivative_dis_thresh = 0.01HF;
    const half4 delta_weight = half4(1.HF, 1.5625HF, 1.5625HF, 0.HF);
    const half recall_floor = 0.065HF;
    const half recall_ceil = 0.420HF;
    const half excursion_floor = 0.025HF;
    const half excursion_ceil = 0.160HF;
    const half mean_gate_floor = 0.070HF;
    const half mean_gate_ceil = 0.230HF;
    const half sustain_cold_floor = 0.177HF;
    const half sustain_cold_ceil = 0.330HF;
    const half sustain_hot_floor = 0.157HF;
    const half sustain_hot_ceil = 0.305HF;
    const half sustain_hysteresis_floor = 0.110HF;
    const half sustain_hysteresis_ceil = 0.210HF;
    const half sustain_support_alpha = 0.30HF;
    const half hot_hold_floor = 0.180HF;
    const half hot_hold_ceil = 0.280HF;
    const half sustain_min_hot_hold = 0.12HF;
    const half decay_min_hot_gate = 0.50HF;
    const half sustain_strength = 0.80HF;
    const half instability_rise_alpha_min = 0.08HF;
    const half instability_rise_alpha_max = 0.22HF;
    const half instability_fast_fall_alpha = 0.24HF;
    const half instability_fall_alpha = 0.05HF;
    const half spatial_support_scale = 0.75HF;
    const half spatial_support_blend = 0.30HF;
#if NSS_V1_HALF_RES_LUMA_DERIVATIVE
    const half moire_temporal_floor = 0.10HF;
    const half moire_temporal_ceil = 0.30HF;
    const half moire_range_floor = 0.99HF;
    const half moire_range_ceil = 0.999HF;
    const half moire_range_scale = 0.50HF;
    const half flat_temporal_floor = 0.015HF;
    const half flat_temporal_ceil = 0.030HF;
    const half flat_range_floor = 0.040HF;
    const half flat_range_ceil = 0.120HF;
    const half flat_blue_floor = 0.220HF;
    const half flat_blue_ceil = 0.300HF;
    const half flat_luma_floor = 0.450HF;
    const half flat_luma_ceil = 1.050HF;
    const half flat_rgb_b_floor = 0.800HF;
    const half flat_rgb_b_ceil = 1.400HF;
    const half flat_flicker_scale = 0.75HF;
#endif // NSS_V1_HALF_RES_LUMA_DERIVATIVE

    half4 deriv_tm1 = DecodeDerivativeStateFromStorage(deriv_tm1_h);
    half raw_zero_state = half(dot(abs(deriv_tm1_h), half4(1.HF)) < 1e-4HF);
    half decoded_zero_state = half(dot(abs(deriv_tm1), half4(1.HF)) < 1e-4HF);
    half uninitialized_state = max(raw_zero_state, decoded_zero_state);
    half4 ycocg_c = RGBToYCoCg(LoadColourForDerivativeAtResolvedPixel(ref_coord));
    half4 ycocg_n = RGBToYCoCg(LoadColourForDerivativeAtPixel(ref_coord + int32_t2(0, -1)));
    half4 ycocg_s = RGBToYCoCg(LoadColourForDerivativeAtPixel(ref_coord + int32_t2(0, 1)));
    half4 ycocg_e = RGBToYCoCg(LoadColourForDerivativeAtPixel(ref_coord + int32_t2(1, 0)));
    half4 ycocg_w = RGBToYCoCg(LoadColourForDerivativeAtPixel(ref_coord + int32_t2(-1, 0)));
    half d_center = ComputeDerivativeDelta(ycocg_c, half4(deriv_tm1.xyz, 0.HF), delta_weight);
    half d_n = ComputeDerivativeDelta(ycocg_c, ycocg_n, delta_weight);
    half d_s = ComputeDerivativeDelta(ycocg_c, ycocg_s, delta_weight);
    half d_e = ComputeDerivativeDelta(ycocg_c, ycocg_e, delta_weight);
    half d_w = ComputeDerivativeDelta(ycocg_c, ycocg_w, delta_weight);
    half4 spatial_deltas = half4(d_n, d_s, d_e, d_w);
    half spatial_delta_sum = dot(spatial_deltas, half4(1.HF));
    half spatial_delta_max = max(max(spatial_deltas.x, spatial_deltas.y), max(spatial_deltas.z, spatial_deltas.w));
    half prev_instability = deriv_tm1.w;

#if NSS_V1_HALF_RES_LUMA_DERIVATIVE
    int32_t2 temporal_input_step = max(int32_t2(_InvScale + float2(0.5)), int32_t2(1));
    int32_t2 moire_input_step = temporal_input_step;
    float2 moire_derivative_step = derivative_inv_dims;
    half4 ycocg_tn = RGBToYCoCg(LoadColourForDerivativeAtPixel(ref_coord + int32_t2(0, -moire_input_step.y)));
    half4 ycocg_ts = RGBToYCoCg(LoadColourForDerivativeAtPixel(ref_coord + int32_t2(0, moire_input_step.y)));
    half4 ycocg_te = RGBToYCoCg(LoadColourForDerivativeAtPixel(ref_coord + int32_t2(moire_input_step.x, 0)));
    half4 ycocg_tw = RGBToYCoCg(LoadColourForDerivativeAtPixel(ref_coord + int32_t2(-moire_input_step.x, 0)));
    half4 deriv_tm1_n = DecodeDerivativeStateFromStorage(
        LoadDerivativeTm1(derivative_uv + float2(0.0, -moire_derivative_step.y))
    );
    half4 deriv_tm1_s = DecodeDerivativeStateFromStorage(
        LoadDerivativeTm1(derivative_uv + float2(0.0, moire_derivative_step.y))
    );
    half4 deriv_tm1_e = DecodeDerivativeStateFromStorage(
        LoadDerivativeTm1(derivative_uv + float2(moire_derivative_step.x, 0.0))
    );
    half4 deriv_tm1_w = DecodeDerivativeStateFromStorage(
        LoadDerivativeTm1(derivative_uv + float2(-moire_derivative_step.x, 0.0))
    );
    half d_tn = ComputeDerivativeDelta(ycocg_tn, half4(deriv_tm1_n.xyz, 0.HF), delta_weight);
    half d_ts = ComputeDerivativeDelta(ycocg_ts, half4(deriv_tm1_s.xyz, 0.HF), delta_weight);
    half d_te = ComputeDerivativeDelta(ycocg_te, half4(deriv_tm1_e.xyz, 0.HF), delta_weight);
    half d_tw = ComputeDerivativeDelta(ycocg_tw, half4(deriv_tm1_w.xyz, 0.HF), delta_weight);
    half r_tn = ComputeDerivativeDelta(ycocg_c, ycocg_tn, delta_weight);
    half r_ts = ComputeDerivativeDelta(ycocg_c, ycocg_ts, delta_weight);
    half r_te = ComputeDerivativeDelta(ycocg_c, ycocg_te, delta_weight);
    half r_tw = ComputeDerivativeDelta(ycocg_c, ycocg_tw, delta_weight);
    half temporal_moire_max = max(max(d_center, d_tn), max(max(d_ts, d_te), d_tw));
    half current_moire_range = max(max(r_tn, r_ts), max(r_te, r_tw));
    half flat_temporal_min = min(min(d_center, d_tn), min(min(d_ts, d_te), d_tw));
#endif // NSS_V1_HALF_RES_LUMA_DERIVATIVE

    half spatial_support = max(spatial_delta_sum - spatial_delta_max, 0.HF) * (1.HF / 3.HF);
    half supported_instability = mix(d_center, spatial_support, spatial_support_blend) * spatial_support_scale;
#if NSS_V1_HALF_RES_LUMA_DERIVATIVE
    half moire_temporal_gate = saturate(
        (temporal_moire_max - moire_temporal_floor) * rcp(moire_temporal_ceil - moire_temporal_floor)
    );
    half moire_range_entry = saturate(
        (current_moire_range - moire_range_floor) * rcp(moire_range_ceil - moire_range_floor)
    ) * moire_temporal_gate * moire_range_scale;
    half flat_temporal_gate = saturate(
        (flat_temporal_min - flat_temporal_floor) * rcp(flat_temporal_ceil - flat_temporal_floor)
    );
    half flat_range_gate = 1.HF - saturate(
        (current_moire_range - flat_range_floor) * rcp(flat_range_ceil - flat_range_floor)
    );
    half flat_blue_bias = (-0.75HF * ycocg_c.y) - (0.5HF * ycocg_c.z);
    half flat_blue_gate = saturate((flat_blue_bias - flat_blue_floor) * rcp(flat_blue_ceil - flat_blue_floor));
    half flat_luma_gate = saturate((ycocg_c.x - flat_luma_floor) * rcp(0.10HF))
        * (1.HF - saturate((ycocg_c.x - flat_luma_ceil) * rcp(0.20HF)));
    half flat_rgb_b = ycocg_c.x - (0.5HF * (ycocg_c.y + ycocg_c.z));
    half flat_rgb_b_gate = saturate((flat_rgb_b - flat_rgb_b_floor) * rcp(0.10HF))
        * (1.HF - saturate((flat_rgb_b - flat_rgb_b_ceil) * rcp(0.20HF)));
    half flat_surface_gate = flat_range_gate * flat_blue_gate * flat_luma_gate * flat_rgb_b_gate;
    half flat_flicker_entry = flat_temporal_gate * flat_surface_gate * flat_flicker_scale;
#endif // NSS_V1_HALF_RES_LUMA_DERIVATIVE

    half recall_excursion = max(supported_instability - prev_instability, 0.HF);
    half recall_score = saturate((supported_instability - recall_floor) * rcp(recall_ceil - recall_floor));
    half excursion_score = saturate((recall_excursion - excursion_floor) * rcp(excursion_ceil - excursion_floor));
    half mean_gate = saturate((supported_instability - mean_gate_floor) * rcp(mean_gate_ceil - mean_gate_floor));
    half raw_entry = sqrt(recall_score) * sqrt(excursion_score) * mean_gate;
#if NSS_V1_HALF_RES_LUMA_DERIVATIVE
    raw_entry = max(raw_entry, max(moire_range_entry, flat_flicker_entry));
#endif // NSS_V1_HALF_RES_LUMA_DERIVATIVE

    half sustain_heat = saturate(
        (prev_instability - sustain_hysteresis_floor) * rcp(sustain_hysteresis_ceil - sustain_hysteresis_floor)
    );
    half sustain_support = mix(prev_instability, supported_instability, sustain_support_alpha);
    half sustain_floor = mix(sustain_cold_floor, sustain_hot_floor, sustain_heat);
    half sustain_ceil = mix(sustain_cold_ceil, sustain_hot_ceil, sustain_heat);
    half sustain_gate = saturate((sustain_support - sustain_floor) * rcp(sustain_ceil - sustain_floor));
    sustain_gate *= sustain_gate;
    half hot_hold = saturate((prev_instability - hot_hold_floor) * rcp(hot_hold_ceil - hot_hold_floor));
    hot_hold *= hot_hold;
    half hot_hold_gate = hot_hold * sustain_min_hot_hold;
    half carry_gate = max(sustain_gate, hot_hold_gate);
    half raw_sustain = prev_instability * carry_gate * sustain_strength;
    half raw_instability = max(raw_entry, raw_sustain);

    half decay_gate = max(sustain_gate, sustain_heat * sustain_heat * decay_min_hot_gate);
    half fall_alpha = mix(instability_fast_fall_alpha, instability_fall_alpha, decay_gate);
    half rise_support = sqrt(recall_score * mean_gate);
    half rise_alpha = mix(instability_rise_alpha_min, instability_rise_alpha_max, rise_support);
    half instability_alpha = raw_instability > prev_instability ? rise_alpha : fall_alpha;
    half filtered_instability = mix(prev_instability, raw_instability, instability_alpha);

    half output_rise_alpha = 0.75HF;
    half output_fall_alpha = 0.80HF;
    half output_alpha = filtered_instability > prev_instability ? output_rise_alpha : output_fall_alpha;
    half visible_instability = mix(prev_instability, filtered_instability, output_alpha);
#if NSS_V1_HALF_RES_LUMA_DERIVATIVE
    visible_instability = max(visible_instability, max(moire_range_entry, flat_flicker_entry));
#endif // NSS_V1_HALF_RES_LUMA_DERIVATIVE

    half4 derivative_state = half4(ycocg_c.xyz, filtered_instability);
    half instability = visible_instability;

    half disocclusion_binary = half(disocclusion_mask > derivative_dis_thresh);
    half reset_history = max(disocclusion_binary, uninitialized_state);
    instability *= 1.HF - reset_history;

    half4 reset_state = half4(ycocg_c.xyz, 0.HF);
    instability_out = instability;
    return EncodeDerivativeStateForStorage(mix(derivative_state, reset_state, reset_history));
}

#else

half4 EmptyDerivativeStateForStorage()
{
    return half4(0.HF);
}

half4 CalculateLumaDerivative(
    half3 unjittered_colour,
    half4 deriv_tm1,
    half disocclusion_mask,
    out half instability)
{
    //-------------------------------------------------------------------------
    // Temporal luma derivative:
    // 1) compute current |delta luma|
    // 2) threshold + clip + power curve
    // 3) accumulate with adaptive alpha
    // 4) zero derivative in disoccluded regions
    //-------------------------------------------------------------------------
    half deriv_max_pow_r = rcp(kDerivMax * sqrt(kDerivMax));
    half luma_tm1 = deriv_tm1.y;
    half luma_derivative_tm1 = deriv_tm1.x;
    half luma_t = Luminance(unjittered_colour);

    half luma_derivative_t = abs(luma_t - luma_tm1);
    half clipped = min(luma_derivative_t, kDerivMax);
    clipped *= step(kDerivMin, luma_derivative_t);
    half curved = clipped * sqrt(clipped) * deriv_max_pow_r;

    half applied_d_alpha = mix(
        kDerivAlpha,
        kDerivAlpha * 0.1HF,
        clamp(luma_derivative_tm1, 0.HF, kDerivMax) * rcp(kDerivMax)
    );
    half luma_derivative = mix(luma_derivative_tm1, curved, applied_d_alpha);

    luma_derivative *= step(disocclusion_mask, kDerivativeDisThresh);
    instability = luma_derivative;
    return half4(luma_derivative, luma_t, 0.HF, 0.HF);
}

#endif // NSS_YCOCG_LUMA_DERIVATIVE

float EncodeNearestDepthCoordUNorm(int32_t2 nearest_offset)
{
    // Pack [-2,2]^2 into a single R8_UNORM code.
    int32_t2 clamped = clamp(nearest_offset, int32_t2(-2), int32_t2(2));
    int32_t code = ((clamped.y + 2) << 3) | (clamped.x + 2);
    return float(code) / 255.0;
}

float4 EncodeNearestOffsetQuadUNormRG8(
    int32_t2 offset_00,
    int32_t2 offset_10,
    int32_t2 offset_01,
    int32_t2 offset_11)
{
    // Low-quality packed quad path stores four {-1..2}^2 offsets in RG8:
    // R = lane00 | lane10<<4, G = lane01 | lane11<<4.
    int32_t byte_r = int32_t(EncodeNearestDepthCoordNibble(offset_00))
        | (int32_t(EncodeNearestDepthCoordNibble(offset_10)) << 4);
    int32_t byte_g = int32_t(EncodeNearestDepthCoordNibble(offset_01))
        | (int32_t(EncodeNearestDepthCoordNibble(offset_11)) << 4);
    return float4(float(byte_r), float(byte_g), 0.0, 255.0) / 255.0;
}

void WriteInputTensorPacked(int32_t2 coord, int8_t4 t_vec0, int8_t4 t_vec1, int8_t4 t_vec2);

void WriteToTensor(
    int32_t2 coord,
    half3 history,
    half3 colour,
    half motion_detector,
    half4 feedback,
    half luma_derivative)
{
    // Network input layout (12 channels):
    // history.rgb | colour.rgb | motion_detector | feedback.rgba | luma_deriv
    // Stored as int8 for `1_nss` graph input, matching QAT metadata.
    int8_t4 t_vec0 = Quantize(half4(history.rgb, colour.r), kPreprocessQuant);
    int8_t4 t_vec1 = Quantize(half4(colour.gb, motion_detector, feedback.r), kPreprocessQuant);
    int8_t4 t_vec2 = Quantize(half4(feedback.gba, luma_derivative), kPreprocessQuant);
    WriteInputTensorPacked(coord, t_vec0, t_vec1, t_vec2);
}

void WriteLumaDerivativeOut(int32_t2 coord, half4 luma);
void WriteNearestOffsetOut(int32_t2 coord, float4 encoded_nearest_offset);

struct PreProcessLaneData
{
    int32_t2 input_coord;
    int32_t2 nearest_offset;
    float depth_dilated;
    float2 motion;
    float2 uv;
    float2 reproj_uv;
};

PreProcessLaneData BuildPreProcessLaneData(int32_t2 input_coord)
{
    PreProcessLaneData lane;
    lane.input_coord = clamp(input_coord, int32_t2(0), _InputDims - int32_t2(1));
    lane.uv = (float2(lane.input_coord) + float2(0.5)) * _InvInputDims;

    lane.depth_dilated = 0.0;
    lane.nearest_offset = int32_t2(0);
    FindNearestDepth_4x4_FromPixel(lane.input_coord, _InputDims, lane.depth_dilated, lane.nearest_offset);

    int32_t2 nearest_input_coord = clamp(
        lane.input_coord + lane.nearest_offset,
        int32_t2(0),
        _InputDims - int32_t2(1)
    );
    lane.motion = LoadMotion(nearest_input_coord);
    // ★ 官方是加号（ffx_nss_preprocess.h:890，注释 "Motion is backward direction
    //   in pixel space to match FSR/ASR"）。_MotionToPixels 已含正确符号，
    //   这里不得再取负 —— 详见 UBO 中 _MotionToPixels 的说明。
    lane.reproj_uv = lane.uv + (lane.motion * _InvInputDims);
    return lane;
}

half3 AverageWarpedHistory2x2(
    PreProcessLaneData lane_00,
    PreProcessLaneData lane_10,
    PreProcessLaneData lane_01,
    PreProcessLaneData lane_11)
{
    return (
        WarpHistory(lane_00.reproj_uv) +
        WarpHistory(lane_10.reproj_uv) +
        WarpHistory(lane_01.reproj_uv) +
        WarpHistory(lane_11.reproj_uv)
    ) * 0.25HF;
}

int32_t2 HalfResProcessCoordToBaseInputCoord(int32_t2 process_coord)
{
    return clamp(process_coord * int32_t2(2), int32_t2(0), _InputDims - int32_t2(1));
}

void PreProcessMain(int32_t2 padded_coord)
{
    //-------------------------------------------------------------------------
    // 1) Dispatch/padding guard
    //-------------------------------------------------------------------------
    if (any(greaterThanEqual(padded_coord, _PaddedDims))) {
        return;
    }

    int32_t2 process_dims = GetProcessDims();
    int32_t2 process_coord = ReflectIndex(padded_coord, process_dims);
    int32_t2 ref_coord = ProcessCoordToInputCoord(process_coord);
    float2 uv = (float2(ref_coord) + float2(0.5)) * _InvInputDims;
    float2 uv_pad = (float2(padded_coord) + float2(0.5)) * _InvPaddedDims;

    //-------------------------------------------------------------------------
    // 2) Depth dilation and nearest-coordinate selection
    //-------------------------------------------------------------------------
    float depth_dilated = 0.0;
    int32_t2 nearest_offset = int32_t2(0);
    FindNearestDepth_4x4(uv, depth_dilated, nearest_offset);

    //-------------------------------------------------------------------------
    // 3) Motion sampling and reprojection setup
    //-------------------------------------------------------------------------
    // SHADER_ACCURATE parity: sample motion at nearest-depth-dilated texel.
    int32_t2 nearest_input_coord = clamp(ref_coord + nearest_offset, int32_t2(0), _InputDims - int32_t2(1));
    float2 motion = LoadMotion(nearest_input_coord);

    // ★★ 与官方 ffx_nss_preprocess.h:940 同构（**加号**）。
    //
    //   官方:  motion = texel × (-renderSize)        ; reproj_uv = uv **+** motion × Inv
    //   本移植: motion = texel × (+renderSize·r/s)   ; reproj_uv = uv **+** motion × Inv
    //   两者数学等价（r/s = render/screen），都会让 reproj_uv 落在 prev_uv 上。
    //   数值验证：.tmp_mv_joint2.py 候选 1 唯一命中。
    //
    //   【历史教训 —— 这一处反复改错过】
    //     曾经写成 `uv - motion * Inv`（与官方 `+` 相反），那时方向整体反，
    //     只是相机平移时表现为「抖动」而非明显鬼影，容易被误判为"能跑"。
    //     2026-09-25 曾在带着 `getRenderHeight` ceil 改动的情况下改成 `+` 并
    //     观察到"鬼影加剧"，但那次变量混淆（ceil 使 render=496 而光影按 495
    //     算 upscaleRatio，MV 幅度不匹配才是真正的加剧原因）。ceil 已回滚，
    //     本行保持与数值验证一致的 `+`。
    float2 reproj_uv = uv + (motion * _InvInputDims);
    float2 unjitter_uv = uv - (_JitterOffset.xy * _InvInputDims);
    int32_t2 depth_coord = InputCoordToDepthCoord(ref_coord);
    // ★ 与官方 ffx_nss_preprocess.h:944 逐字一致（加号）。
    float2 reproj_270p_uv = ((float2(depth_coord) + float2(0.5)) * _InvDepthTm1Dims) + (motion * _InvInputDims);
    // ★ 与官方 ffx_nss_preprocess.h:945 逐字一致（加号）。
    float2 reproj_pad_uv = uv_pad + MotionToPaddedUvDelta(motion);

    //-------------------------------------------------------------------------
    // 4) Disocclusion
    //-------------------------------------------------------------------------
    /*
     * ★★ `history_valid = NotHistoryReset()` 必须乘到 `disocclusion_mask` 上 ——
     * 与官方 ffx_nss_preprocess.h:950-958 逐字一致。此前遗漏了这一因子，
     * 导致掩码在 jitter 抖动下反复翻转，是本项目「整体平移式抖动」的直接根因。
     * 详见 UBO 中 `_NotHistoryReset` 的说明。
     */
    half history_valid = half(_NotHistoryReset);
#if NSS_INPUT_LAYOUT == 1
    float2 disocclusion_uv = (float2(process_coord) + float2(0.5)) * _InvOutputDims;
    half disocclusion_mask = half(textureLod(_DisocclusionMaskLQTex, disocclusion_uv, 0.0).r);
#else
    half disocclusion_mask = half(ComputeDepthClipInt(
        reproj_270p_uv,
        depth_dilated,
        _RenderSize,
        _DeviceToViewDepth
    ));
#endif // NSS_INPUT_LAYOUT == 1
    disocclusion_mask *= history_valid;

    //-------------------------------------------------------------------------
    // 5) Feature preparation for network input tensor
    //-------------------------------------------------------------------------
    half3 unjittered_colour_h = LoadColourUnjittered(unjitter_uv);
    half3 lr_warped_history_h;
#if NSS_INPUT_LAYOUT == 1
    {
        int32_t2 history_base_input_coord = HalfResProcessCoordToBaseInputCoord(process_coord);
        int32_t2 history_lane_input_00 = history_base_input_coord;
        int32_t2 history_lane_input_10 = min(history_base_input_coord + int32_t2(1, 0), _InputDims - int32_t2(1));
        int32_t2 history_lane_input_01 = min(history_base_input_coord + int32_t2(0, 1), _InputDims - int32_t2(1));
        int32_t2 history_lane_input_11 = min(history_base_input_coord + int32_t2(1, 1), _InputDims - int32_t2(1));

        PreProcessLaneData history_lane_00 = BuildPreProcessLaneData(history_lane_input_00);
        PreProcessLaneData history_lane_10 = BuildPreProcessLaneData(history_lane_input_10);
        PreProcessLaneData history_lane_01 = BuildPreProcessLaneData(history_lane_input_01);
        PreProcessLaneData history_lane_11 = BuildPreProcessLaneData(history_lane_input_11);
        lr_warped_history_h = AverageWarpedHistory2x2(
            history_lane_00,
            history_lane_10,
            history_lane_01,
            history_lane_11
        );
    }
#else
    lr_warped_history_h = WarpHistory(reproj_uv);
#endif // NSS_INPUT_LAYOUT == 1
    half4 deriv_tm1 = LoadDerivativeTm1(reproj_pad_uv);
    half instability = 0.HF;
#if NSS_YCOCG_LUMA_DERIVATIVE
    float2 derivative_uv = reproj_uv;
    float2 derivative_inv_dims = _InvInputDims;
#if NSS_INPUT_LAYOUT == 1
    derivative_uv = reproj_pad_uv;
    derivative_inv_dims = _InvPaddedDims;
#endif // NSS_INPUT_LAYOUT == 1
    half4 luma = CalculateLumaDerivative(
        ref_coord,
        derivative_uv,
        derivative_inv_dims,
        deriv_tm1,
        disocclusion_mask,
        instability
    );
#else
    half4 luma = CalculateLumaDerivative(unjittered_colour_h, deriv_tm1, disocclusion_mask, instability);
#endif // NSS_YCOCG_LUMA_DERIVATIVE
    half4 feedback_h = WarpFeedback(reproj_pad_uv, disocclusion_mask);
    half motion_detector = CalculateMotionDetector(motion, _RenderSize);

    //-------------------------------------------------------------------------
    // 6) Write network tensor for padded domain
    //-------------------------------------------------------------------------
    WriteToTensor(
        padded_coord,
        lr_warped_history_h,
        unjittered_colour_h,
        motion_detector,
        feedback_h,
        instability
    );

    //-------------------------------------------------------------------------
    // 7) Write per-frame auxiliary outputs for non-padded region only
    //-------------------------------------------------------------------------
    if (any(greaterThanEqual(padded_coord, process_dims))) {
        WriteLumaDerivativeOut(padded_coord, EmptyDerivativeStateForStorage());
        return;
    }
    WriteLumaDerivativeOut(padded_coord, luma);
#if NSS_INPUT_LAYOUT == 1
    int32_t2 base_input_coord = clamp(process_coord * int32_t2(2), int32_t2(0), _InputDims - int32_t2(1));
    int32_t2 lane_input_00 = base_input_coord;
    int32_t2 lane_input_10 = min(base_input_coord + int32_t2(1, 0), _InputDims - int32_t2(1));
    int32_t2 lane_input_01 = min(base_input_coord + int32_t2(0, 1), _InputDims - int32_t2(1));
    int32_t2 lane_input_11 = min(base_input_coord + int32_t2(1, 1), _InputDims - int32_t2(1));
    int32_t2 quad_offset_00 = int32_t2(0);
    int32_t2 quad_offset_10 = int32_t2(0);
    int32_t2 quad_offset_01 = int32_t2(0);
    int32_t2 quad_offset_11 = int32_t2(0);
    float quad_depth_unused = 0.0;
    FindNearestDepth_4x4_FromPixel(lane_input_00, _InputDims, quad_depth_unused, quad_offset_00);
    FindNearestDepth_4x4_FromPixel(lane_input_10, _InputDims, quad_depth_unused, quad_offset_10);
    FindNearestDepth_4x4_FromPixel(lane_input_01, _InputDims, quad_depth_unused, quad_offset_01);
    FindNearestDepth_4x4_FromPixel(lane_input_11, _InputDims, quad_depth_unused, quad_offset_11);

    float4 encoded_quad = EncodeNearestOffsetQuadUNormRG8(
        quad_offset_00,
        quad_offset_10,
        quad_offset_01,
        quad_offset_11
    );
    WriteNearestOffsetOut(padded_coord, encoded_quad);
#else
    WriteNearestOffsetOut(
        padded_coord,
        float4(EncodeNearestDepthCoordUNorm(nearest_offset), 0.0, 0.0, 1.0)
    );
#endif // NSS_INPUT_LAYOUT == 1
}

#endif // NSS_V1_PRE_PROCESS_SHARED_H
