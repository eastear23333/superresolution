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

#ifndef NSS_V1_DISOCCLUSION_MASK_LQ_SHARED_H
#define NSS_V1_DISOCCLUSION_MASK_LQ_SHARED_H

#include "typedefs.h"
#include "common.h"

layout (set=0, binding=0) uniform mediump sampler2D _MotionTex;
layout (set=0, binding=1) uniform highp   sampler2D _DepthTex;
layout (set=0, binding=2) uniform highp   usampler2D _DepthTm1Tex;

// 移植说明（SR 框架）：与 1_pre_process_shared.h 相同 —— 原 Arm push_constant
// 改为 std140 uniform block（binding 9，与 preprocess 的 UBO 同 binding 但属于
// 不同管线的 set=0，互不冲突）。本块无数组元素，std140 与原 push_constant 的
// 成员偏移完全一致（float4 16B / float2 8B / int2 8B 对齐），布局零差异。
// 实际消费的字段只有 _DeviceToViewDepth / _InputDims / _DepthClip*；
// 其余字段保留以对齐 Arm 原始布局。
#ifdef NSS_USE_UBO
layout(std140, set = 0, binding = 9) uniform PushConstants {
#else
layout(push_constant, std430) uniform PushConstants {
#endif
    layout(offset =   0) float4  _DeviceToViewDepth;
    layout(offset =  16) float4  _JitterOffset;
    layout(offset =  32) float4  _JitterOffsetTm1;
    layout(offset =  48) float2  _Scale;
    layout(offset =  56) float2  _InvScale;
    layout(offset =  64) int32_t2 _OutputDims;
    layout(offset =  72) int32_t2 _InputDims;
    layout(offset =  80) int32_t2 _PaddedDims;
    layout(offset =  88) float2  _InvOutputDims;
    layout(offset =  96) float2  _InvInputDims;
    layout(offset = 104) float2  _InvPaddedDims;
    layout(offset = 112) float2  _InvDepthTm1Dims;
    layout(offset = 120) float2  _RenderSize;
    layout(offset = 128) float2  _Exposure;
    layout(offset = 136) float _DepthClipRequiredSepScale;
    layout(offset = 140) float _DepthClipPower;
} cbDisocclusion;

#define _DeviceToViewDepth            cbDisocclusion._DeviceToViewDepth
#define _JitterOffset                 cbDisocclusion._JitterOffset
#define _JitterOffsetTm1              cbDisocclusion._JitterOffsetTm1
#define _ScaleDiso                    cbDisocclusion._Scale
#define _InvScaleDiso                 cbDisocclusion._InvScale
#define _OutputDimsDiso               cbDisocclusion._OutputDims
#define _InputDims                    cbDisocclusion._InputDims
#define _PaddedDimsDiso               cbDisocclusion._PaddedDims
#define _InvOutputDimsDiso            cbDisocclusion._InvOutputDims
#define _InvInputDimsDiso             cbDisocclusion._InvInputDims
#define _InvPaddedDimsDiso            cbDisocclusion._InvPaddedDims
#define _InvDepthTm1DimsDiso          cbDisocclusion._InvDepthTm1Dims
#define _RenderSizeDiso               cbDisocclusion._RenderSize
#define _ExposureDiso                 cbDisocclusion._Exposure
#define _DepthClipRequiredSepScale    cbDisocclusion._DepthClipRequiredSepScale
#define _DepthClipPower               cbDisocclusion._DepthClipPower

const float kLqDisocclusionEps = 1e-7;
const float kLqDisocclusionDepthScale = 2147483647.0;
const float kLqDisocclusionInvDepthScale = 1.0 / kLqDisocclusionDepthScale;
const float kLqDisocclusionMotionThreshold = 0.1;

#ifndef NSS_LQ_DISOCCLUSION_DEPTH_GRADIENT_TOLERANCE_SCALE
#define NSS_LQ_DISOCCLUSION_DEPTH_GRADIENT_TOLERANCE_SCALE 1.0
#endif

bool LqDisocclusionIsOnScreen(int32_t2 pos, int32_t2 size)
{
    return all(lessThan(pos, size)) && all(greaterThanEqual(pos, int32_t2(0)));
}

float LqDisocclusionGetViewSpaceDepth(float depth, float4 device_to_view)
{
    return device_to_view.y / (depth - device_to_view.x);
}

void LqDisocclusionFindClosestDepthMotion4x4(
    int32_t2 dst_pos,
    int32_t2 dst_size,
    out float closest_depth,
    out float2 closest_motion,
    out float local_view_depth_range)
{
    int32_t2 src_base = int32_t2(float2(dst_pos) * (float2(_InputDims) / float2(dst_size)));
    int32_t2 src_pos = clamp(src_base, int32_t2(0), _InputDims - int32_t2(1));

    closest_depth = texelFetch(_DepthTex, src_pos, 0).r;
    closest_motion = texelFetch(_MotionTex, src_pos, 0).xy;
    // Grazing planes can span a large view-depth range inside one LQ footprint.
    float src_view_depth = LqDisocclusionGetViewSpaceDepth(closest_depth, _DeviceToViewDepth);
    float min_view_depth = src_view_depth;
    float max_view_depth = src_view_depth;

    for (int y = 0; y < 4; ++y) {
        for (int x = 0; x < 4; ++x) {
            int32_t2 sample_pos = src_base + int32_t2(x, y);
            if (LqDisocclusionIsOnScreen(sample_pos, _InputDims)) {
                float sample_depth = texelFetch(_DepthTex, sample_pos, 0).r;
                float sample_view_depth = LqDisocclusionGetViewSpaceDepth(sample_depth, _DeviceToViewDepth);
                min_view_depth = min(min_view_depth, sample_view_depth);
                max_view_depth = max(max_view_depth, sample_view_depth);
                if (sample_depth < closest_depth) {
                    closest_depth = sample_depth;
                    closest_motion = texelFetch(_MotionTex, sample_pos, 0).xy;
                }
            }
        }
    }

    local_view_depth_range = max_view_depth - min_view_depth;
}

float LqDisocclusionComputeDepthClipInt(
    float2 uv,
    float current_depth,
    float local_view_depth_range,
    int32_t2 depth_size)
{
    const float bilinear_weight_threshold = 0.1;
    float current_view_depth = LqDisocclusionGetViewSpaceDepth(current_depth, _DeviceToViewDepth);
    // Allow same-surface 4x4 depth slope before treating the min-depth envelope as a disocclusion.
    float local_depth_tolerance = local_view_depth_range * NSS_LQ_DISOCCLUSION_DEPTH_GRADIENT_TOLERANCE_SCALE;
    float2 sample_px = (uv * float2(depth_size)) - float2(0.5);
    int32_t2 sample_base = int32_t2(floor(sample_px));
    float2 sample_frac = fract(sample_px);

    float w00 = (1.0 - sample_frac.x) * (1.0 - sample_frac.y);
    float w10 = sample_frac.x * (1.0 - sample_frac.y);
    float w01 = (1.0 - sample_frac.x) * sample_frac.y;
    float w11 = sample_frac.x * sample_frac.y;

    float f_depth = 0.0;
    float f_weight_sum = 0.0;

#define NSS_LQ_DISOCCLUSION_DEPTH_CLIP_SAMPLE_BLOCK(SAMPLE_POS, SAMPLE_WEIGHT)                      \
    {                                                                                               \
        int32_t2 sample_pos = (SAMPLE_POS);                                                         \
        float weight = (SAMPLE_WEIGHT);                                                             \
        bool onscreen = LqDisocclusionIsOnScreen(sample_pos, depth_size);                           \
        f_weight_sum += onscreen ? 0.0 : weight;                                                    \
        if (onscreen && weight > bilinear_weight_threshold) {                                       \
            float prev_depth = float(texelFetch(_DepthTm1Tex, sample_pos, 0).r) * kLqDisocclusionInvDepthScale; \
            float prev_view_depth = LqDisocclusionGetViewSpaceDepth(prev_depth, _DeviceToViewDepth); \
            float depth_diff = current_view_depth - prev_view_depth;                                \
            if (depth_diff > 0.0) {                                                                 \
                float depth_threshold = max(current_view_depth, prev_view_depth);                   \
                float required_sep = (_DepthClipRequiredSepScale * depth_threshold) + local_depth_tolerance; \
                float sep_ratio = saturate(required_sep / max(depth_diff, kLqDisocclusionEps));    \
                f_depth += pow(sep_ratio, _DepthClipPower) * weight;                                \
                f_weight_sum += weight;                                                             \
            }                                                                                       \
        }                                                                                           \
    }

    NSS_LQ_DISOCCLUSION_DEPTH_CLIP_SAMPLE_BLOCK(sample_base + int32_t2(0, 0), w00);
    NSS_LQ_DISOCCLUSION_DEPTH_CLIP_SAMPLE_BLOCK(sample_base + int32_t2(1, 0), w10);
    NSS_LQ_DISOCCLUSION_DEPTH_CLIP_SAMPLE_BLOCK(sample_base + int32_t2(0, 1), w01);
    NSS_LQ_DISOCCLUSION_DEPTH_CLIP_SAMPLE_BLOCK(sample_base + int32_t2(1, 1), w11);

#undef NSS_LQ_DISOCCLUSION_DEPTH_CLIP_SAMPLE_BLOCK

    return f_weight_sum > 0.0 ? saturate(1.0 - f_depth / f_weight_sum) : 0.0;
}

float LqDisocclusionComputeMask(int32_t2 pixel, int32_t2 depth_size)
{
    float current_depth = 0.0;
    float2 motion = float2(0.0);
    float local_view_depth_range = 0.0;
    LqDisocclusionFindClosestDepthMotion4x4(pixel, depth_size, current_depth, motion, local_view_depth_range);

    float2 inv_depth_size = rcp(float2(depth_size));
    float2 uv = (float2(pixel) + float2(0.5)) * inv_depth_size;
    float2 motion_depth_pixels = motion * (float2(depth_size) / float2(_InputDims));
    motion_depth_pixels *= float(length(motion) > kLqDisocclusionMotionThreshold);
    // ★ 与官方 ffx_nss_disocclusion_mask_lq.h:175 逐字一致（**加号**）。
    //    （本 pass 仅 MID/LOW 档使用，当前未接入；提前对齐以免后续接档时踩坑。）
    float2 reproj_uv = uv + (motion_depth_pixels * inv_depth_size);

    return LqDisocclusionComputeDepthClipInt(reproj_uv, current_depth, local_view_depth_range, depth_size);
}

#endif // NSS_V1_DISOCCLUSION_MASK_LQ_SHARED_H
