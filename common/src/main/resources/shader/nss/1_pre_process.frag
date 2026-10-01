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

#version 460
#extension GL_EXT_shader_8bit_storage : require
#extension GL_EXT_shader_16bit_storage : require
#extension GL_EXT_shader_explicit_arithmetic_types : require
#extension GL_EXT_shader_explicit_arithmetic_types_int8 : require
#extension GL_EXT_shader_explicit_arithmetic_types_float16 : require
#extension GL_EXT_shader_explicit_arithmetic_types_float32 : require
#extension GL_GOOGLE_include_directive : enable

#include "typedefs.h"
#include "common.h"

#ifndef NSS_INPUT_LAYOUT
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

layout(location = 0) in vec2 vUV;

//-----------------------------------------------------------------------------
// Resources
//-----------------------------------------------------------------------------
layout (set=0, binding=0) uniform mediump sampler2D _ColourTex;       // 540p->1080p HQ/Low/Mid: 960x540 | R11G11B10 32bpp | 1.978 MiB
layout (set=0, binding=1) uniform highp   sampler2D _DepthTex;        // 540p->1080p HQ/Low/Mid: 960x540 | R32_SFLOAT 32bpp | 1.978 MiB
layout (set=0, binding=2) uniform mediump sampler2D _MotionVectorTex; // 540p->1080p HQ/Low/Mid: 960x540 | R16G16_SFLOAT 32bpp | 1.978 MiB
layout (set=0, binding=3) uniform mediump sampler2D _HistoryTex;      // 540p->1080p HQ/Low/Mid: 1920x1080 | R11G11B10 32bpp | 7.910 MiB
layout (set=0, binding=4) uniform lowp    sampler2D _FeedbackTensor;  // 540p->1080p HQ: 960x544 | R8G8B8A8_SNORM 32bpp | Tensor->Texture Alias (Linear) | 1.992 MiB ; Low/Mid: 480x272 | 0.498 MiB
layout (set=0, binding=5) uniform highp   usampler2D _DepthTm1Tex;    // 540p->1080p HQ: 480x270 | R32_UINT 32bpp | previous-depth scatter result | 0.494 MiB ; Low/Mid: 240x135 | 0.124 MiB
layout (set=0, binding=6) uniform lowp    sampler2D _LumaDerivTm1Tex; // 540p->1080p HQ: 960x544 | R8G8B8A8_SNORM 32bpp | derivative history | 1.992 MiB ; Low/Mid: 480x272 | 0.498 MiB
#if NSS_INPUT_LAYOUT == 1
layout (set=0, binding=7) uniform highp   sampler2D _DisocclusionMaskLQTex; // Low/Mid 135p depth-domain disocclusion mask
#endif // NSS_INPUT_LAYOUT == 1

// 全部资源落在 set=0：binding 8（见 1_pre_process_shared.h 的说明）。
layout (set=0, binding=8, std430) buffer InputTensorBuffer {
    int8_t4 data[];
} _InputTensorBuffer; // 540p->1080p HQ: 960x544x12 | int8 NHWC std430 buffer alias | 5.977 MiB ; Low/Mid: 480x272x12 | 1.494 MiB

layout(location = 0) out mediump vec4 _LumaDerivOut;          // 540p->1080p HQ: 960x544 | R8G8B8A8_SNORM 32bpp render target | 1.992 MiB ; Low/Mid: 480x272 | 0.498 MiB
layout(location = 1) out mediump vec4 _NearestDepthOffsetOut; // 540p->1080p HQ: 960x544 | R8_UNORM 8bpp render target | 0.498 MiB ; Low/Mid: 480x272 | R8G8_UNORM 16bpp | 0.249 MiB

#include "1_pre_process_shared.h"

void WriteInputTensorPacked(int32_t2 coord, int8_t4 t_vec0, int8_t4 t_vec1, int8_t4 t_vec2)
{
    uint32_t base = (uint32_t(coord.y) * uint32_t(_PaddedDims.x) + uint32_t(coord.x)) * 3u;
    _InputTensorBuffer.data[base + 0u] = t_vec0;
    _InputTensorBuffer.data[base + 1u] = t_vec1;
    _InputTensorBuffer.data[base + 2u] = t_vec2;
}

void WriteLumaDerivativeOut(int32_t2 coord, half4 luma)
{
    _LumaDerivOut = vec4(float4(luma));
}

void WriteNearestOffsetOut(int32_t2 coord, float4 encoded_nearest_offset)
{
    _NearestDepthOffsetOut = vec4(encoded_nearest_offset);
}

void main()
{
    _LumaDerivOut = vec4(float4(EmptyDerivativeStateForStorage()));
    _NearestDepthOffsetOut = vec4(0.0, 0.0, 0.0, 1.0);
    PreProcessMain(int32_t2(gl_FragCoord.xy));
}
