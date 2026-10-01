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

#ifndef NSS_V1_POST_PROCESS_SHARED_H
#define NSS_V1_POST_PROCESS_SHARED_H

// Post-process build define matrix.
//
// This lists the defines consumed by this shared post-process header. Defaults
// may be omitted by the scenario JSON, but the effective values should match
// the entries below.
//
//   static LUT / exact 2x | high quality:
//     -DNSS_FILTER_MODE=0
//     -DNSS_INPUT_LAYOUT=0
//     -DNSS_USE_HISTORY_CATMULL=1
//     -DNSS_V1_SHARP_THETA=1
//
//   static LUT / exact 2x | mid quality:
//     -DNSS_FILTER_MODE=1
//     -DNSS_INPUT_LAYOUT=1
//     -DNSS_USE_HISTORY_CATMULL=1
//     -DNSS_V1_SHARP_THETA=1
//
//   static LUT / exact 2x | low quality:
//     -DNSS_FILTER_MODE=1
//     -DNSS_INPUT_LAYOUT=1
//     -DNSS_USE_HISTORY_CATMULL=0
//     -DNSS_V1_SHARP_THETA=1
//
//   non-integer dynamic LUT | high quality:
//     post-process:
//       -DNSS_FILTER_MODE=2
//       -DNSS_INPUT_LAYOUT=0
//       -DNSS_USE_HISTORY_CATMULL=1
//       -DNSS_V1_SHARP_THETA=1
//     paired LUT generator:
//       -DNSS_FILTER_MODE=2
//
//   non-integer dynamic LUT | mid quality:
//     post-process:
//       -DNSS_FILTER_MODE=3
//       -DNSS_INPUT_LAYOUT=1
//       -DNSS_USE_HISTORY_CATMULL=1
//       -DNSS_V1_SHARP_THETA=1
//     paired LUT generator:
//       -DNSS_FILTER_MODE=3
//
//   non-integer dynamic LUT | low quality:
//     post-process:
//       -DNSS_FILTER_MODE=3
//       -DNSS_INPUT_LAYOUT=1
//       -DNSS_USE_HISTORY_CATMULL=0
//       -DNSS_V1_SHARP_THETA=1
//     paired LUT generator:
//       -DNSS_FILTER_MODE=3

#ifndef NSS_USE_HISTORY_CATMULL
    #define NSS_USE_HISTORY_CATMULL 1
#endif // !NSS_USE_HISTORY_CATMULL
#ifndef NSS_FILTER_MODE
    // 0: static 2x high, 1: static 2x sparse, 2: dynamic dense LUT, 3: dynamic sparse LUT.
    #define NSS_FILTER_MODE 0
#endif // !NSS_FILTER_MODE
#ifndef NSS_INPUT_LAYOUT
    // 0: full-resolution preprocess outputs, 1: half-resolution outputs with packed nearest-depth offsets.
    #define NSS_INPUT_LAYOUT 0
#endif // !NSS_INPUT_LAYOUT
#ifndef NSS_V1_SHARP_THETA
    #define NSS_V1_SHARP_THETA 1
#endif // !NSS_V1_SHARP_THETA

#if (NSS_FILTER_MODE < 0) || (NSS_FILTER_MODE > 3)
    #error "NSS_FILTER_MODE must be 0 (static 2x high), 1 (static 2x sparse), 2 (dynamic dense LUT), or 3 (dynamic sparse LUT)."
#endif // filter implementation selection

// Matches Slang layout and includes both geometric scales and temporal controls.
//
// 移植说明（SR 框架）：见 1_pre_process_shared.h —— 同理由 NSS_USE_UBO 切换
// 到 std140 uniform block（本块无数组，两种布局偏移等价）。
#ifdef NSS_USE_UBO
// 全部资源落在 set=0：binding 7（0..5 采样器 + KpnParams SSBO，6 是 OffsetLut）。
// 同 1_pre_process_shared.h：两个 push descriptor set 会违反 00293 并让 set 0
// 永远无法绑定。
layout(std140, set = 0, binding = 7) uniform PushConstants {
#else
layout(push_constant, std430) uniform PushConstants {
#endif
    // ───────────────  8-byte aligned ───────────────
    // High-res output extent divided by low-res input extent.
    layout(offset =  0) float2  _Scale;          //   8 B
    // Inverse of `_Scale`.
    layout(offset =  8) float2  _InvScale;       //   8 B
    // High-resolution output dimensions.
    layout(offset = 16) int32_t2 _OutputDims;    //   8 B
    // Low-resolution input dimensions.
    layout(offset = 24) int32_t2 _InputDims;     //   8 B
    // KPN tensor dimensions.
    layout(offset = 32) int32_t2 _KpnDims;       //   8 B
    // Inverse of `_OutputDims`.
    layout(offset = 40) float2  _InvOutputDims;  //   8 B
    // Inverse of `_InputDims`.
    layout(offset = 48) float2  _InvInputDims;   //   8 B
    // Inverse of `_KpnDims`.
    layout(offset = 56) float2  _InvKpnDims;     //   8 B
    // UV scale from preprocess space into temporal-feedback space.
    layout(offset = 64) float2  _PaddedUvScale;  //   8 B
    // UV scale from temporal-feedback space into KPN space.
    layout(offset = 72) float2  _KpnScale;       //   8 B
    // Tile modulo used for offset-LUT pattern selection.
    layout(offset = 80) int32_t2 _IdxModulo;     //   8 B
    // Exposure packed as (exposure, 1/exposure).
    layout(offset = 88) float2  _Exposure;       //   8 B

    // ───────────────  4-byte aligned ───────────────
    // Temporal reset / history blending gate.
    layout(offset = 96) float _Reset;            //   4 B

    // ─────────────── padding to next 8-byte member ───────────────
    // Offsets [100, 103] are implicit pad to preserve Slang parity.

    // ───────────────  8-byte aligned ───────────────
    // Jitter-dependent tile remap offset inside the modulo lattice.
    layout(offset = 104) int32_t2 _LutOffset;    //   8 B
    // Logical preprocess dimensions used to address temporal feedback.
    layout(offset = 112) int32_t2 _PreprocessDims; //   8 B

    // ───────────────  8-byte aligned (SR 移植扩展) ───────────────
    // 运动矢量纹理单位 → 渲染分辨率像素 的换算系数（见
    // 1_pre_process_shared.h 的同名字段说明）。
    layout(offset = 120) float2 _MotionToPixels;   //   8 B
                                                 // Total: **128 bytes**
};

const float kEps = 1e-7;
const half kMotionThreshold = 0.1HF;
const float kMotionThresholdSq = float(kMotionThreshold) * float(kMotionThreshold);
#if (NSS_FILTER_MODE == 1) || (NSS_FILTER_MODE == 3) || (NSS_INPUT_LAYOUT == 1)
const int32_t kKpnChannels = 16;
#else
const int32_t kKpnChannels = 36;
#endif // (NSS_FILTER_MODE == 1) || (NSS_FILTER_MODE == 3) || (NSS_INPUT_LAYOUT == 1)
const int32_t kKpnPackedChannelsPerTexel = 4;
const int32_t kKpnPackedTexelsPerPixel = kKpnChannels / kKpnPackedChannelsPerTexel;
const int32_t kKpnPackedChannelShift = 2;
const int32_t kKpnPackedChannelMask = kKpnPackedChannelsPerTexel - 1;
const int32_t kKpnLaneX = 0;
const int32_t kKpnLaneY = 1;
const int32_t kKpnLaneZ = 2;
const bool kUseMotionThreshold = false;
// QAT metadata (`_KpnCoefficients` / `_TemporalTensor` SINT).
const half2 kKpnQuant = half2(0.003937007859349251, -127.0);
// Temporal params are sampled from SNORM image alias, so use SNORM dequant.
const half2 kTemporalQuant = half2(0.49999999813735485, -1.0);
const int32_t kLutPatternCount = 4;

struct KernelPattern
{
    int16_t2 base_offset;
    int16_t base_channel;
    int16_t _pad0;
};

#if NSS_FILTER_MODE == 0
const int16_t4 kTapDx0 = int16_t4(0, 0, 0, 2);
const int16_t4 kTapDy0 = int16_t4(0, 2, 4, 0);
const int16_t4 kTapCh0 = int16_t4(0, 2, 4, 12);
const int16_t4 kTapDx1 = int16_t4(2, 2, 4, 4);
const int16_t4 kTapDy1 = int16_t4(2, 4, 0, 2);
const int16_t4 kTapCh1 = int16_t4(14, 16, 24, 26);
const int16_t2 kTapD2 = int16_t2(4, 4);
const int16_t kTapCh2 = int16_t(28);

// 2x scale patterns from generated NSS v1 LUT for jitter in [-0.5, 0.5), with jitter-driven tile remap via _LutOffset.
const KernelPattern kKernelLut[kLutPatternCount] = KernelPattern[kLutPatternCount](
    KernelPattern(
        int16_t2(-1, -1), int16_t(7), int16_t(0)
    ),
    KernelPattern(
        int16_t2(-2, -1), int16_t(1), int16_t(0)
    ),
    KernelPattern(
        int16_t2(-1, -2), int16_t(6), int16_t(0)
    ),
    KernelPattern(
        int16_t2(-2, -2), int16_t(0), int16_t(0)
    )
);
#endif // NSS_FILTER_MODE == 0

#if NSS_FILTER_MODE == 1
// 2x2 sparse low-quality mode keeps the centered 4x4 subset of the original
// 6x6 KPN, preserving the generated offset-LUT tap order after pruning.
const int16_t4 kTap2x2Dx[kLutPatternCount] = int16_t4[kLutPatternCount](
    int16_t4(-1, -1, +1, +1),
    int16_t4(+0, +0, +2, +2),
    int16_t4(-1, +1, -1, +1),
    int16_t4(+0, +0, +2, +2)
);
const int16_t4 kTap2x2Dy[kLutPatternCount] = int16_t4[kLutPatternCount](
    int16_t4(-1, +1, -1, +1),
    int16_t4(-1, +1, -1, +1),
    int16_t4(+0, +0, +2, +2),
    int16_t4(+0, +2, +0, +2)
);
const int16_t4 kTap2x2Ch[kLutPatternCount] = int16_t4[kLutPatternCount](
    int16_t4(0, 2, 8, 10),
    int16_t4(4, 6, 12, 14),
    int16_t4(1, 9, 3, 11),
    int16_t4(5, 7, 13, 15)
);
#endif // NSS_FILTER_MODE == 1

#if (NSS_FILTER_MODE == 2) || (NSS_FILTER_MODE == 3)
// 官方 ffx_nss_postprocess.h:524-526 —— LUT 每个 tile 占用的 texel 组数
// （dense 9 taps → 3 组；sparse 4 taps → 1 组）。LUT 纹理尺寸 =
// (kLutGroupCount * _IdxModulo.x, _IdxModulo.y)，寻址
// texel = (tile_x * kLutGroupCount + group_idx, tile_y)。
#if NSS_FILTER_MODE == 3
const int32_t kLutGroupCount = 1;
#else
const int32_t kLutGroupCount = 3;
#endif

struct OffsetLutTap
{
    int32_t2 lr_offset;
    int32_t tap_channel;
    bool valid;
    bool center;
};

int32_t DecodePackedI8(uint32_t packed, uint32_t shift)
{
    return bitfieldExtract(int32_t(packed), int32_t(shift), int32_t(8));
}

OffsetLutTap DecodePackedOffsetLutTap(uint32_t packed)
{
    OffsetLutTap tap;
    tap.lr_offset = int32_t2(
        DecodePackedI8(packed, uint32_t(0)),
        DecodePackedI8(packed, uint32_t(8))
    );
    tap.tap_channel = int32_t((packed >> uint32_t(16)) & uint32_t(0x3F));
    tap.valid = ((packed >> uint32_t(22)) & uint32_t(1)) != uint32_t(0);
    tap.center = ((packed >> uint32_t(23)) & uint32_t(1)) != uint32_t(0);
    return tap;
}

// 官方 ffx_nss_postprocess.h:544-547 寻址：
//     texel = (tile_idx.x * kLutGroupCount + group_idx, tile_idx.y)
// LUT 纹理尺寸 = (kLutGroupCount * _IdxModulo.x, _IdxModulo.y)。
// ⚠️ 不要改回「(group, flat_idx) 转置」布局 —— 非 2x 时网格可达数千格，
//    转置会把高度堆到超过 GPU 纹理上限（16384）。
uint32_t4 LoadOffsetLutPacked4(int32_t2 tile_idx, int32_t group_idx)
{
    return texelFetch(_OffsetLutUint4Tex,
                      int32_t2(tile_idx.x * kLutGroupCount + group_idx, tile_idx.y), 0);
}
#endif // (NSS_FILTER_MODE == 2) || (NSS_FILTER_MODE == 3)

//-----------------------------------------------------------------------------
// Numeric helpers
//-----------------------------------------------------------------------------
half MaxHalf(half x)
{
    // Clamp to fp16 max to mimic storage/arith limits used in runtime.
    return min(x, 65504.HF);
}

half3 MaxHalf(half3 x)
{
    return min(x, half3(65504.HF));
}

half4 MaxHalf(half4 x)
{
    return min(x, half4(65504.HF));
}

int32_t2 DecodeNearestOffset(int32_t2 pixel)
{
    // Decode packed nearest-depth offset from R8_UNORM or low-quality RG8 quad packing.
    float norm_code = 0.0;
#if NSS_INPUT_LAYOUT == 1
    int32_t2 texel = clamp(
        int32_t2(floor((float2(pixel) + float2(0.5)) * float2(_PreprocessDims) * _InvInputDims)),
        int32_t2(0),
        _PreprocessDims - int32_t2(1)
    );
    float2 norm_code_pair = texelFetch(_NearestDepthOffsetTex, texel, 0).rg;
    int32_t lane_idx = (pixel.y & int32_t(1)) * int32_t(2) + (pixel.x & int32_t(1));
    int32_t packed_byte = lane_idx < int32_t(2)
        ? int32_t(norm_code_pair.x * 255.0 + 0.5)
        : int32_t(norm_code_pair.y * 255.0 + 0.5);
    int32_t packed_nibble = (lane_idx & int32_t(1)) == int32_t(0)
        ? (packed_byte & 0xF)
        : ((packed_byte >> 4) & 0xF);
    return DecodeNearestDepthCoordNibble(packed_nibble);
#else
    // Use logical input domain (not physical image extent) so padded fragment
    // outputs preserve compute-path sampling behaviour.
    pixel = clamp(pixel, int32_t2(0), _InputDims - int32_t2(1));
    norm_code = texelFetch(_NearestDepthOffsetTex, pixel, 0).r;
#endif // NSS_INPUT_LAYOUT == 1
    int32_t code = int32_t(norm_code * 255.0 + 0.5);
    int32_t x = (code & 0x7) - 2;
    int32_t y = ((code >> 3) & 0x7) - 2;
    return int32_t2(x, y);
}

half2 LoadMotion(int32_t2 output_coord)
{
    // Reproject motion lookup using nearest-depth dilated coordinate.
    int32_t2 input_coord = int32_t2(float2(output_coord) * _InvScale);
    int32_t2 dilation_offset = DecodeNearestOffset(input_coord);
    int32_t2 dilated_coord = clamp(input_coord + dilation_offset, int32_t2(0), _InputDims - int32_t2(1));

    // SR 移植：_MotionToPixels（= +renderSize × render/screen，只补幅度，见 UBO 说明）
    // 已把纹理里的「backward 归一化 UV × upscaleRatio」换算成 Arm 期望的
    // 「backward 屏幕空间像素位移」；再乘 _Scale（屏幕/渲染）升到输出分辨率像素。
    // 因此下方 WarpHistory 与官方一致写成 `reproj_uv = uv + motion * _InvOutputDims`。
    // 切勿改成减号，也不要在宿主侧给 _MotionToPixels 加负号（会再次把方向翻反）。
    half2 v = half2(texelFetch(_MotionVectorTex, dilated_coord, 0).xy)
            * half2(_MotionToPixels) * half2(_Scale);
    if (kUseMotionThreshold) {
        v *= half(dot(float2(v), float2(v)) > kMotionThresholdSq);
    }
    return v;
}

half3 LoadColourTap(int32_t2 lr_tap)
{
    return half3(texelFetch(_ColourTex, lr_tap, 0).rgb);
}

half4 LoadColourTap4(int32_t2 lr_tap)
{
    return half4(LoadColourTap(lr_tap), 1.HF);
}

int8_t ReadKpnParamsInt8(int32_t2 kpn_tap, int32_t channel);
int8_t ReadKpnParamsInt8FromBase(int32_t kpn_texel_base_x, int32_t kpn_y, int32_t channel);

int32_t KpnCoordFromInputCoord(int32_t input_coord, int32_t input_dim, int32_t kpn_dim)
{
    int32_t safe_input_dim = max(input_dim, int32_t(1));
    return min((input_coord * kpn_dim) / safe_input_dim, kpn_dim - int32_t(1));
}

int16_t4 KpnCoordFromInputCoord4(int16_t4 input_coord, int32_t input_dim, int32_t kpn_dim)
{
    int32_t safe_input_dim = max(input_dim, int32_t(1));
    int32_t4 scaled = (int32_t4(input_coord) * int32_t4(kpn_dim)) / int32_t4(safe_input_dim);
    return int16_t4(min(scaled, int32_t4(kpn_dim - int32_t(1))));
}

int32_t KpnCoordFromScaledTap(int32_t tap_coord, float scale, int32_t kpn_dim)
{
    int32_t scaled = int32_t(floor((float(tap_coord) + 0.5 + 1e-3) * scale));
    return min(scaled, kpn_dim - int32_t(1));
}

int16_t4 KpnCoordFromScaledTap4(int16_t4 tap_coord, float scale, int32_t kpn_dim)
{
    int32_t4 scaled = int32_t4(floor((float4(tap_coord) + float4(0.5 + 1e-3)) * float4(scale)));
    return int16_t4(min(scaled, int32_t4(kpn_dim - int32_t(1))));
}

half SampleKpnWeight(int32_t tap_z, int32_t2 kpn_tap)
{
    // KPN comes from `1_nss` as an int8 tensor with `kKpnChannels` channels.
    int8_t q = ReadKpnParamsInt8FromBase(kpn_tap.x, kpn_tap.y, tap_z);
    return max(Dequantize(half(q), kKpnQuant), EPS);
}

half4 LoadWarpedHistory(float2 uv)
{
    return half4(texture(_HistoryTex, uv).rgb, 1.HF);
}

#if NSS_USE_HISTORY_CATMULL
half4 LoadWarpedHistoryCatmull(float2 uv)
{
    //------------------------------------------------------------------------------------
    // 1) Compute Catmull–Rom weights
    //------------------------------------------------------------------------------------
    float2 scaledUV = uv * _OutputDims;
    float2 baseFloor = floor(scaledUV - 0.5) + 0.5;

    half2 f  = half2(scaledUV - baseFloor);
    half2 f2 = f * f;
    half2 f3 = f2 * f;

    // Catmull–Rom basis terms used by the 5-tap cross filter.
    half2 w0 = f2 - 0.5HF * (f3 + f);
    half2 w3 = 0.5HF * (f3 - f2);
    half2 w2 = 0.5HF * f + f2 * (2.0HF - 1.5HF * f);
    half2 w12 = 1.0HF + 0.5HF * f - 0.5HF * f2; // w1 + w2

    // Keep axis-combination math in half2 to improve vector utilization.
    half2 wx02 = half2(w0.x, w3.x); // left/right X weights
    half2 wy02 = half2(w0.y, w3.y); // up/down Y weights
    half2 w_ud = half2(w12.x) * wy02; // up, down
    half2 w_lr = half2(w12.y) * wx02; // left, right
    half wCenter = w12.x * w12.y;

    // Fractional offsets for the center sample location.
    half2 dxy = w2 / max(w12, half2(EPS));

    //------------------------------------------------------------------------------------
    // 2) Gather the 5 taps
    //------------------------------------------------------------------------------------
    float2 base_uv = baseFloor * _InvOutputDims;
    float2 center_uv = base_uv + float2(dxy) * _InvOutputDims;
    float left_x = base_uv.x - _InvOutputDims.x;
    float right_x = base_uv.x + 2.0 * _InvOutputDims.x;
    float up_y = base_uv.y - _InvOutputDims.y;
    float down_y = base_uv.y + 2.0 * _InvOutputDims.y;

    half4 left   = half4(texture(_HistoryTex, float2(left_x, center_uv.y)).rgb, 1.HF);
    half4 up     = half4(texture(_HistoryTex, float2(center_uv.x, up_y)).rgb, 1.HF);
    half4 center = half4(texture(_HistoryTex, center_uv).rgb, 1.HF);
    half4 right  = half4(texture(_HistoryTex, float2(right_x, center_uv.y)).rgb, 1.HF);
    half4 down   = half4(texture(_HistoryTex, float2(center_uv.x, down_y)).rgb, 1.HF);

    //------------------------------------------------------------------------------------
    // 3) Accumulate and track min/max
    //------------------------------------------------------------------------------------
    half4 accum = up    * w_ud.x  +
                  left  * w_lr.x  +
                  center* wCenter +
                  right * w_lr.y  +
                  down  * w_ud.y;
    half4 cmin4 = min(up, min(left, min(center, min(right, down))));
    half4 cmax4 = max(up, max(left, max(center, max(right, down))));

    //------------------------------------------------------------------------------------
    // 4) Final color
    //------------------------------------------------------------------------------------
    half3 color = accum.rgb * rcp(accum.w);

    // dering in the case where we have negative values, we don't do this all the time
    // as it can impose unnecessary blurring on the output
    color = any(lessThan(color, half3(0.HF))) ? clamp(color, cmin4.rgb, cmax4.rgb) : color;
    return half4(color, 1.HF);
}
#endif // NSS_USE_HISTORY_CATMULL

half4 SampleKpnWeight4(int16_t4 tap_z, int16_t4 kpn_x, int16_t4 kpn_y)
{
    int8_t4 q = int8_t4(
        ReadKpnParamsInt8FromBase(kpn_x.x, kpn_y.x, tap_z.x),
        ReadKpnParamsInt8FromBase(kpn_x.y, kpn_y.y, tap_z.y),
        ReadKpnParamsInt8FromBase(kpn_x.z, kpn_y.z, tap_z.z),
        ReadKpnParamsInt8FromBase(kpn_x.w, kpn_y.w, tap_z.w)
    );
    return max(Dequantize(half4(q), kKpnQuant), half4(EPS));
}

#define NSS_SQ_MAT(_M)                                                                                   \
    f16mat4x4(_M[0] * _M[0], _M[1] * _M[1], _M[2] * _M[2], _M[3] * _M[3])

#define NSS_LOAD_TAPS_4(TAP_X, TAP_Y, OUT_MAT)                                                           \
    {                                                                                                     \
        OUT_MAT[0] = LoadColourTap4(int32_t2(TAP_X.x, TAP_Y.x));                                         \
        OUT_MAT[1] = LoadColourTap4(int32_t2(TAP_X.y, TAP_Y.y));                                         \
        OUT_MAT[2] = LoadColourTap4(int32_t2(TAP_X.z, TAP_Y.z));                                         \
        OUT_MAT[3] = LoadColourTap4(int32_t2(TAP_X.w, TAP_Y.w));                                         \
    }

void NormalizeFilterMoments(
    half4 accum_m1,
    half4 accum_m2,
    out half4 m1,
    out half4 m2)
{
    half denom = max(accum_m1.a, EPS);
    m1 = half4(accum_m1.rgb * rcp(denom), 0.HF);
    m2 = half4(accum_m2.rgb * rcp(denom), 0.HF);
}

int32_t2 KpnCoordForDenseFilter(int32_t2 lr_tap, int32_t2 kpn_max)
{
#if NSS_INPUT_LAYOUT == 1
    return clamp(
        int32_t2(
            KpnCoordFromInputCoord(lr_tap.x, _InputDims.x, _KpnDims.x),
            KpnCoordFromInputCoord(lr_tap.y, _InputDims.y, _KpnDims.y)
        ),
        int32_t2(0),
        kpn_max
    );
#else
    return clamp(
        int32_t2(
            KpnCoordFromScaledTap(lr_tap.x, _KpnScale.x, _KpnDims.x),
            KpnCoordFromScaledTap(lr_tap.y, _KpnScale.y, _KpnDims.y)
        ),
        int32_t2(0),
        kpn_max
    );
#endif // NSS_INPUT_LAYOUT == 1
}

void KpnCoordsForDenseFilter4(
    int16_t4 tap_x,
    int16_t4 tap_y,
    int16_t4 zero4,
    int16_t4 kpn_max_x4,
    int16_t4 kpn_max_y4,
    out int16_t4 kpn_x,
    out int16_t4 kpn_y)
{
#if NSS_INPUT_LAYOUT == 1
    kpn_x = clamp(KpnCoordFromInputCoord4(tap_x, _InputDims.x, _KpnDims.x), zero4, kpn_max_x4);
    kpn_y = clamp(KpnCoordFromInputCoord4(tap_y, _InputDims.y, _KpnDims.y), zero4, kpn_max_y4);
#else
    kpn_x = clamp(KpnCoordFromScaledTap4(tap_x, _KpnScale.x, _KpnDims.x), zero4, kpn_max_x4);
    kpn_y = clamp(KpnCoordFromScaledTap4(tap_y, _KpnScale.y, _KpnDims.y), zero4, kpn_max_y4);
#endif // NSS_INPUT_LAYOUT == 1
}

#if (NSS_FILTER_MODE == 2) || (NSS_FILTER_MODE == 3)
// 官方 ffx_nss_postprocess.h:549-552：直接取 _IdxModulo（**不要交换 xy**）。
// _IdxModulo 已由 host 按 reducedFractionHrSize 填好 (x=宽, y=高)。
int32_t2 PackedOffsetLutModulo()
{
    return max(_IdxModulo, int32_t2(1));
}

void AccumulatePackedLrOffsetTap(
    OffsetLutTap lut_tap,
    int32_t2 lr_base,
    int32_t2 colour_max,
    int32_t2 kpn_max,
    inout half4 accum_m1,
    inout half4 accum_m2,
    inout half4 center_sample)
{
    if (!lut_tap.valid && !lut_tap.center) {
        return;
    }
    int32_t2 lr_tap = clamp(lr_base + lut_tap.lr_offset, int32_t2(0), colour_max);
    half4 tap_col = LoadColourTap4(lr_tap);
    if (lut_tap.center) {
        center_sample = tap_col;
    }
    if (!lut_tap.valid) {
        return;
    }
    int32_t2 kpn_tap = KpnCoordForDenseFilter(lr_tap, kpn_max);
    half tap_weight = SampleKpnWeight(lut_tap.tap_channel, kpn_tap);
    accum_m1 += tap_col * tap_weight;
    accum_m2 += (tap_col * tap_col) * tap_weight;
}
#endif // (NSS_FILTER_MODE == 2) || (NSS_FILTER_MODE == 3)

#if NSS_FILTER_MODE == 2
void FilterColour(
    int32_t2 output_px,
    out half4 m1,
    out half4 m2,
    out half4 center_sample)
{
    //-------------------------------------------------------------------------
    // Non-integer fastest path: the LUT stores final LR offsets, tap channel,
    // valid, and center bits. The LUT is generated on GPU and consumed as three
    // RGBA32_UINT texels per modulo tile.
    //-------------------------------------------------------------------------
    int32_t2 colour_max = _InputDims - int32_t2(1);
    int32_t2 kpn_max = _KpnDims - int32_t2(1);
    int32_t2 idx_mod_xy = PackedOffsetLutModulo();
    int32_t2 tile_idx = output_px % idx_mod_xy;
    int32_t2 lr_base = int32_t2(floor(float2(output_px) * _InvScale));

    half4 accum_m1 = half4(0.HF);
    half4 accum_m2 = half4(0.HF);
    center_sample = half4(0.HF);

    uint32_t4 packed_taps0 = LoadOffsetLutPacked4(tile_idx, int32_t(0));
    AccumulatePackedLrOffsetTap(
        DecodePackedOffsetLutTap(packed_taps0.x), lr_base, colour_max, kpn_max, accum_m1, accum_m2, center_sample
    );
    AccumulatePackedLrOffsetTap(
        DecodePackedOffsetLutTap(packed_taps0.y), lr_base, colour_max, kpn_max, accum_m1, accum_m2, center_sample
    );
    AccumulatePackedLrOffsetTap(
        DecodePackedOffsetLutTap(packed_taps0.z), lr_base, colour_max, kpn_max, accum_m1, accum_m2, center_sample
    );
    AccumulatePackedLrOffsetTap(
        DecodePackedOffsetLutTap(packed_taps0.w), lr_base, colour_max, kpn_max, accum_m1, accum_m2, center_sample
    );

    uint32_t4 packed_taps1 = LoadOffsetLutPacked4(tile_idx, int32_t(1));
    AccumulatePackedLrOffsetTap(
        DecodePackedOffsetLutTap(packed_taps1.x), lr_base, colour_max, kpn_max, accum_m1, accum_m2, center_sample
    );
    AccumulatePackedLrOffsetTap(
        DecodePackedOffsetLutTap(packed_taps1.y), lr_base, colour_max, kpn_max, accum_m1, accum_m2, center_sample
    );
    AccumulatePackedLrOffsetTap(
        DecodePackedOffsetLutTap(packed_taps1.z), lr_base, colour_max, kpn_max, accum_m1, accum_m2, center_sample
    );
    AccumulatePackedLrOffsetTap(
        DecodePackedOffsetLutTap(packed_taps1.w), lr_base, colour_max, kpn_max, accum_m1, accum_m2, center_sample
    );

    uint32_t4 packed_taps2 = LoadOffsetLutPacked4(tile_idx, int32_t(2));
    AccumulatePackedLrOffsetTap(
        DecodePackedOffsetLutTap(packed_taps2.x), lr_base, colour_max, kpn_max, accum_m1, accum_m2, center_sample
    );

    NormalizeFilterMoments(accum_m1, accum_m2, m1, m2);
}
#endif // NSS_FILTER_MODE == 2

#if NSS_FILTER_MODE == 3
int16_t4 DecodePackedI8x4(uint32_t4 packed, uint32_t shift)
{
    return int16_t4(bitfieldExtract(int32_t4(packed), int32_t(shift), int32_t(8)));
}

int16_t4 DecodePackedTapChannel4(uint32_t4 packed)
{
    return int16_t4((packed >> uint32_t4(16)) & uint32_t4(0x3F));
}

half4 DecodePackedValidMask4(uint32_t4 packed)
{
    return half4((packed >> uint32_t4(22)) & uint32_t4(1));
}

void FilterColour(
    int32_t2 output_px,
    out half4 m1,
    out half4 m2,
    out half4 center_sample)
{
    //-------------------------------------------------------------------------
    // Non-integer sparse path: one RGBA32_UINT LUT texel stores the 4 selected
    // 2x2 taps for the modulo tile. The tap payload is identical to the dense
    // dynamic path, but the generator has already pruned the 4x4 KPN window.
    //-------------------------------------------------------------------------
    int32_t2 colour_max = _InputDims - int32_t2(1);
    int32_t2 kpn_max = _KpnDims - int32_t2(1);
    int32_t2 idx_mod_xy = PackedOffsetLutModulo();
    int32_t2 tile_idx = output_px % idx_mod_xy;
    int32_t2 lr_base = int32_t2(floor(float2(output_px) * _InvScale));

    half4 accum_m1 = half4(0.HF);
    half4 accum_m2 = half4(0.HF);
    center_sample = half4(0.HF);

    uint32_t4 packed_taps = LoadOffsetLutPacked4(tile_idx, int32_t(0));
    int32_t4 lr_tap_x = clamp(
        int32_t4(lr_base.x) + int32_t4(DecodePackedI8x4(packed_taps, uint32_t(0))),
        int32_t4(0),
        int32_t4(colour_max.x)
    );
    int32_t4 lr_tap_y = clamp(
        int32_t4(lr_base.y) + int32_t4(DecodePackedI8x4(packed_taps, uint32_t(8))),
        int32_t4(0),
        int32_t4(colour_max.y)
    );
    int16_t4 tap_x = int16_t4(lr_tap_x);
    int16_t4 tap_y = int16_t4(lr_tap_y);
    int16_t4 kpn_x;
    int16_t4 kpn_y;
    KpnCoordsForDenseFilter4(
        tap_x,
        tap_y,
        int16_t4(0),
        int16_t4(kpn_max.x),
        int16_t4(kpn_max.y),
        kpn_x,
        kpn_y
    );

    f16mat4x4 taps;
    NSS_LOAD_TAPS_4(tap_x, tap_y, taps);
    half4 weights = SampleKpnWeight4(DecodePackedTapChannel4(packed_taps), kpn_x, kpn_y);
    weights *= DecodePackedValidMask4(packed_taps);

    uint32_t4 center_bits = (packed_taps >> uint32_t4(23)) & uint32_t4(1);
    if (center_bits.x != uint32_t(0)) {
        center_sample = taps[0];
    } else if (center_bits.y != uint32_t(0)) {
        center_sample = taps[1];
    } else if (center_bits.z != uint32_t(0)) {
        center_sample = taps[2];
    } else if (center_bits.w != uint32_t(0)) {
        center_sample = taps[3];
    }
    accum_m1 += taps * weights;
    accum_m2 += NSS_SQ_MAT(taps) * weights;

    NormalizeFilterMoments(accum_m1, accum_m2, m1, m2);
}
#endif // NSS_FILTER_MODE == 3

#if NSS_FILTER_MODE == 1
void FilterColour(
    int32_t2 output_px,
    out half4 m1,
    out half4 m2,
    out half4 center_sample)
{
    //-------------------------------------------------------------------------
    // Mid-quality path: sparse 2x2 subset of the 6x6 KPN.
    //-------------------------------------------------------------------------
    int32_t2 colour_max = _InputDims - int32_t2(1);
    int32_t2 kpn_max = _KpnDims - int32_t2(1);
    int16_t4 colour_max_x4 = int16_t4(colour_max.x);
    int16_t4 colour_max_y4 = int16_t4(colour_max.y);
    int16_t4 kpn_max_x4 = int16_t4(kpn_max.x);
    int16_t4 kpn_max_y4 = int16_t4(kpn_max.y);
    int16_t4 zero4 = int16_t4(0);
    int16_t2 out_px16 = int16_t2(output_px);
    int32_t2 tile_idx = (output_px + _LutOffset) & int32_t2(1);
    int32_t lut_idx = (tile_idx.y << int32_t(1)) + tile_idx.x;

    half4 accum_m1 = half4(0.HF);
    half4 accum_m2 = half4(0.HF);

    int16_t4 tap_x = clamp((int16_t4(out_px16.x) + kTap2x2Dx[lut_idx]) >> int16_t(1), zero4, colour_max_x4);
    int16_t4 tap_y = clamp((int16_t4(out_px16.y) + kTap2x2Dy[lut_idx]) >> int16_t(1), zero4, colour_max_y4);
    int16_t4 kpn_x = clamp(KpnCoordFromInputCoord4(tap_x, _InputDims.x, _KpnDims.x), zero4, kpn_max_x4);
    int16_t4 kpn_y = clamp(KpnCoordFromInputCoord4(tap_y, _InputDims.y, _KpnDims.y), zero4, kpn_max_y4);

    f16mat4x4 taps;
    NSS_LOAD_TAPS_4(tap_x, tap_y, taps);
    half4 weights = SampleKpnWeight4(kTap2x2Ch[lut_idx], kpn_x, kpn_y);
    center_sample = (lut_idx == int32_t(3)) ? taps[0] : half4(0.HF);
    accum_m1 += taps * weights;
    accum_m2 += NSS_SQ_MAT(taps) * weights;

    NormalizeFilterMoments(accum_m1, accum_m2, m1, m2);
}
#endif // NSS_FILTER_MODE == 1

#if NSS_FILTER_MODE == 0
void FilterColour(
    int32_t2 output_px,
    out half4 m1,
    out half4 m2,
    out half4 center_sample)
{
    //-------------------------------------------------------------------------
    // High-quality 2x path: static generated 6x6 KPN tap pattern.
    //-------------------------------------------------------------------------
    int32_t2 colour_max = _InputDims - int32_t2(1);
    int32_t2 kpn_max = _KpnDims - int32_t2(1);
    int16_t4 colour_max_x4 = int16_t4(colour_max.x);
    int16_t4 colour_max_y4 = int16_t4(colour_max.y);
    int16_t4 kpn_max_x4 = int16_t4(kpn_max.x);
    int16_t4 kpn_max_y4 = int16_t4(kpn_max.y);
    int16_t4 zero4 = int16_t4(0);
    int16_t2 out_px16 = int16_t2(output_px);
    int32_t2 tile_idx = (output_px + _LutOffset) & int32_t2(1);
    int32_t lut_idx = (tile_idx.y << int32_t(1)) + tile_idx.x;

    half4 accum_m1 = half4(0.HF);
    half4 accum_m2 = half4(0.HF);
    center_sample = half4(0.HF);
    KernelPattern lut = kKernelLut[lut_idx];

    int16_t4 tap_x0 = clamp((int16_t4(out_px16.x + lut.base_offset.x) + kTapDx0) >> int16_t(1), zero4, colour_max_x4);
    int16_t4 tap_y0 = clamp((int16_t4(out_px16.y + lut.base_offset.y) + kTapDy0) >> int16_t(1), zero4, colour_max_y4);
    int16_t4 kpn_x0;
    int16_t4 kpn_y0;
    KpnCoordsForDenseFilter4(tap_x0, tap_y0, zero4, kpn_max_x4, kpn_max_y4, kpn_x0, kpn_y0);

    f16mat4x4 taps0;
    NSS_LOAD_TAPS_4(tap_x0, tap_y0, taps0);
    half4 w0 = SampleKpnWeight4(kTapCh0 + int16_t4(lut.base_channel), kpn_x0, kpn_y0);
    accum_m1 += taps0 * w0;
    accum_m2 += NSS_SQ_MAT(taps0) * w0;

    int16_t4 tap_x1 = clamp((int16_t4(out_px16.x + lut.base_offset.x) + kTapDx1) >> int16_t(1), zero4, colour_max_x4);
    int16_t4 tap_y1 = clamp((int16_t4(out_px16.y + lut.base_offset.y) + kTapDy1) >> int16_t(1), zero4, colour_max_y4);
    int16_t4 kpn_x1;
    int16_t4 kpn_y1;
    KpnCoordsForDenseFilter4(tap_x1, tap_y1, zero4, kpn_max_x4, kpn_max_y4, kpn_x1, kpn_y1);

    f16mat4x4 taps1;
    NSS_LOAD_TAPS_4(tap_x1, tap_y1, taps1);
    half4 w1 = SampleKpnWeight4(kTapCh1 + int16_t4(lut.base_channel), kpn_x1, kpn_y1);
    center_sample = (lut_idx == int32_t(3)) ? taps1[0] : half4(0.HF);
    accum_m1 += taps1 * w1;
    accum_m2 += NSS_SQ_MAT(taps1) * w1;

    int32_t2 tap2 = output_px + int32_t2(lut.base_offset) + int32_t2(kTapD2);
    int32_t2 lr_tap2 = clamp(tap2 >> int32_t(1), int32_t2(0), colour_max);
    int32_t2 kpn_tap2 = KpnCoordForDenseFilter(lr_tap2, kpn_max);
    half4 tap2_col = LoadColourTap4(lr_tap2);
    half tap2_w = SampleKpnWeight(int32_t(lut.base_channel + kTapCh2), kpn_tap2);
    accum_m1 += tap2_col * tap2_w;
    accum_m2 += (tap2_col * tap2_col) * tap2_w;

    NormalizeFilterMoments(accum_m1, accum_m2, m1, m2);
}
#endif // NSS_FILTER_MODE == 0

#undef NSS_LOAD_TAPS_4
#undef NSS_SQ_MAT

void SampleTemporalParams(float2 uv, out half theta, out half alpha, out half gamma)
{
    // Temporal params live over the logical preprocess domain and may be backed
    // by a padded image in the scenario runtime.
    float2 uv_temporal = uv * _PaddedUvScale;
    half4 params = Dequantize(half4(textureLod(_TemporalTensor, uv_temporal, 0.0)), kTemporalQuant);
#if NSS_V1_SHARP_THETA
    half theta_in = clamp(params.x, 0.HF, 1.HF);
    half theta_inv = 1.HF - theta_in;
    half theta_a = theta_in * theta_in;
    half theta_b = theta_inv * theta_inv;
    theta = theta_a * rcp(max(theta_a + theta_b, 1e-6HF));
#else
    theta = params.x;
#endif // NSS_V1_SHARP_THETA
    alpha = params.y * 0.35HF + 0.05HF;
    gamma = params.z * 2.0HF;
}

void WarpHistory(
    int32_t2 output_px,
    float2 uv,
    out half4 warped_colour,
    out half onscreen)
{
    // Reproject history with motion; report if sample stayed on-screen.
    half2 motion = LoadMotion(output_px);
    // ★ 与官方 ffx_nss_postprocess.h:778 逐字一致（加号）：
    //     float2 reproj_uv = uv + (float2(motion) * InvOutputDims());
    //   官方 LoadMotionPost 已施加 motionVectorScale，方向为 backward 屏幕空间像素，
    //   配 `+` 才把当前像素重投影到上一帧位置。改成减号会让历史采样整体反向偏移
    //   —— 表现为相机一动画面平移、静置抖动。
    float2 reproj_uv = uv + (float2(motion) * _InvOutputDims);
    onscreen = half(all(greaterThanEqual(reproj_uv, float2(0.0))) && all(lessThanEqual(reproj_uv, float2(1.0))));
#if NSS_USE_HISTORY_CATMULL
    warped_colour = LoadWarpedHistoryCatmull(reproj_uv);
#else
    warped_colour = LoadWarpedHistory(reproj_uv);
#endif // NSS_USE_HISTORY_CATMULL
}

void ClampHistoryToStats(
    half4 m1,
    half4 m2,
    half4 warped_history,
    half theta,
    half gamma,
    half reset,
    half onscreen,
    out half4 rectified)
{
    //-------------------------------------------------------------------------
    // Statistical clamp (AABB):
    // Clamp reprojected history using local filtered moments to reduce ghosts.
    //-------------------------------------------------------------------------
    half4 sigma_4 = sqrt(max(abs(m2 - m1 * m1), half4(EPS))) * half4(gamma);
    sigma_4.a = 0.HF;
    half4 aabb_min = m1 - sigma_4;
    half4 aabb_max = m1 + sigma_4;
    half4 history_clamped = mix(m1, clamp(warped_history, aabb_min, aabb_max), half4(reset));
    rectified = mix(history_clamped, warped_history, half4(theta * onscreen * reset));
}

half3 ClampToInvertibleRange(half3 accumulated, half inv_exposure)
{
    // Keep value within invertible Karis range, then restore linear domain.
    half3 clamped_output = clamp(accumulated, half3(0.HF), half3(1.HF - EPS));
    return SafeColour(MaxHalf(InverseTonemap(clamped_output) * half3(inv_exposure)));
}

half4 Tonemap4(half4 x)
{
    x = clamp(x, half4(0.HF), half4(MAX_FP16));
    half m = max(max(x.r, x.g), x.b);
    return x * rcp(half4(1.HF + m));
}

half3 SimulateR11G11B10Precision(half3 rgb)
{
    const float epsilon = 1e-12;
    const int32_t3 m_bits = int32_t3(6, 6, 5);
    const int32_t exp_bits = 5;
    const float3 bias = float3((1 << (exp_bits - 1)) - 1); // 15.0
    const float3 exp_max = float3((1 << exp_bits) - 1);

    float3 val = max(float3(rgb), float3(epsilon));
    float3 exp_unclamped = floor(log2(val));
    float3 exp_clipped = clamp(exp_unclamped, -bias, bias + 1.0);
    float3 mant = val / exp2(exp_clipped) - 1.0;

    float3 scale = float3(int32_t3(1) << m_bits);
    float3 rgb_exp = clamp(exp_clipped + bias, float3(0.0), exp_max) - bias;
    float3 rgb_mant = clamp(round(mant * scale), float3(0.0), scale - 1.0) / scale;
    float3 rgb_out = (1.0 + rgb_mant) * exp2(rgb_exp);
    return half3(rgb_out);
}

void WriteColourOutTarget(int32_t2 coord, half3 out_linear);

void WriteColourOut(int32_t2 coord, half3 out_linear)
{
    half3 to_write = SafeColour(out_linear);
#ifdef SIMULATE_R11G11B10_BEFORE_WRITE
    to_write = SimulateR11G11B10Precision(to_write);
#endif // SIMULATE_R11G11B10_BEFORE_WRITE
    WriteColourOutTarget(coord, to_write);
}

void PostProcessMain(int32_t2 output_px)
{
    //-------------------------------------------------------------------------
    // 1) Per-pixel setup
    //-------------------------------------------------------------------------
    half exposure = half(_Exposure.x);
    half reset = half(_Reset);
    float2 uv = (float2(output_px) + float2(0.5)) * _InvOutputDims;

    //-------------------------------------------------------------------------
    // 2) Spatial filtering (KPN) + local moments
    //-------------------------------------------------------------------------
    half4 m1;
    half4 m2;
    half4 center_sample;
    FilterColour(output_px, m1, m2, center_sample);

    //-------------------------------------------------------------------------
    // 3) Read temporal controls and reproject history
    //-------------------------------------------------------------------------
    half theta;
    half alpha;
    half gamma;
    SampleTemporalParams(uv, theta, alpha, gamma);
#if NSS_FORCE_HISTORY_CLAMP
    // 稳定模式（2026-09-27）：theta 上限钳制。
    // 背景：游戏内 temporal 输出 theta 异常偏高 → 历史信任过高 → 鬼影直通；
    // 但一刀切 theta=0 会把时序降噪一并关掉（实测噪点爆炸）。
    // 上限钳制：高 theta 像素（鬼影区）被压到上限，低 theta 像素（平滑区）保留
    // 网络的时序决策。上限由 config nss_history_theta_max 控制（默认 0.5，
    // 可在 0.3~0.8 间权衡「压鬼影 vs 降噪」）。根因（KPN 输入链）修复后可关闭。
    theta = min(theta, half(NSS_HISTORY_THETA_MAX));
#endif

    half4 warped_colour;
    half onscreen;
    WarpHistory(output_px, uv, warped_colour, onscreen);

    //-------------------------------------------------------------------------
    // 4) Clamp and rectify history
    //-------------------------------------------------------------------------
    half4 rectified;
    ClampHistoryToStats(
        m1,
        m2,
        warped_colour,
        theta,
        gamma,
        reset,
        onscreen,
        rectified
    );

    //-------------------------------------------------------------------------
    // 5) Tonemapped accumulation
    //-------------------------------------------------------------------------
    half4 rectified_tm4 = Tonemap4(MaxHalf(rectified * half4(exposure)));
    half learnt_masked_alpha = alpha * center_sample.a * reset;
    half4 colour_to_accum_tm4 = Tonemap4(MaxHalf(center_sample * half4(exposure)));
    half4 accumulated4 = mix(rectified_tm4, colour_to_accum_tm4, half4(learnt_masked_alpha));

    //-------------------------------------------------------------------------
    // 6) Convert back to linear and write outputs
    //-------------------------------------------------------------------------
    half3 out_linear = ClampToInvertibleRange(accumulated4.rgb, half(_Exposure.y));
    WriteColourOut(output_px, out_linear);
}

#endif // NSS_V1_POST_PROCESS_SHARED_H
