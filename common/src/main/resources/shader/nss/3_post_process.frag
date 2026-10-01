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

// Compile-time option:
// -DSIMULATE_R11G11B10_BEFORE_WRITE

layout(location = 0) in vec2 vUV;

//-----------------------------------------------------------------------------
// Resources
//-----------------------------------------------------------------------------
layout (set=0, binding=0) uniform mediump sampler2D  _ColourTex;  // 540p->1080p HQ/Low/Mid: 960x540 | R11G11B10 32bpp | 1.978 MiB
layout (set=0, binding=1) uniform mediump sampler2D  _HistoryTex; // 540p->1080p HQ/Low/Mid: 1920x1080 | R11G11B10 32bpp | 7.910 MiB
layout (set=0, binding=2, std430) readonly buffer KpnParamsBuffer {
    int8_t data[];
} _KpnParamsBuffer; // 540p->1080p HQ: 240x136x36 | int8 NHWC std430 buffer alias | 1.121 MiB ; Low/Mid: 120x68x16 | 0.125 MiB
layout (set=0, binding=3) uniform lowp    sampler2D  _TemporalTensor; // 540p->1080p HQ: 960x544 | R8G8B8A8_SNORM 32bpp | Tensor->Texture Alias (Linear) | 1.992 MiB ; Low/Mid: 480x272 | 0.498 MiB
layout (set=0, binding=4) uniform mediump sampler2D  _MotionVectorTex; // 540p->1080p HQ/Low/Mid: 960x540 | R16G16_SFLOAT 32bpp | 1.978 MiB
layout (set=0, binding=5) uniform lowp    sampler2D  _NearestDepthOffsetTex; // 540p->1080p HQ: 960x544 | R8_UNORM 8bpp | nearest-depth offset render target | 0.498 MiB ; Low/Mid: 480x272 | R8G8_UNORM 16bpp | 0.249 MiB
#if (NSS_FILTER_MODE == 2) || (NSS_FILTER_MODE == 3)
layout (set=0, binding=6) uniform highp usampler2D _OffsetLutUint4Tex;
#endif // (NSS_FILTER_MODE == 2) || (NSS_FILTER_MODE == 3)

layout(location = 0) out mediump vec4 _ColourOut; // 540p->1080p HQ/Low/Mid: 1920x1080 | R11G11B10 32bpp render target | 7.910 MiB

#define NSS_USE_KPN_BASE_X 1
#include "3_post_process_shared.h"

int8_t ReadKpnParamsInt8FromBase(int32_t kpn_texel_base_x, int32_t kpn_y, int32_t channel)
{
    // SSBO path binds tight NHWC int8 tensor memory.
    int32_t linear_base = ((kpn_y * _KpnDims.x) + kpn_texel_base_x) * kKpnChannels;
    int8_t qv = _KpnParamsBuffer.data[uint32_t(linear_base + channel)];
    return int8_t(qv);
}

void WriteColourOutTarget(int32_t2 coord, half3 out_linear)
{
    _ColourOut = vec4(float3(out_linear), 1.0);
}

void main()
{
    _ColourOut = vec4(0.0, 0.0, 0.0, 1.0);
    PostProcessMain(int32_t2(gl_FragCoord.xy));
}
