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
#extension GL_EXT_shader_explicit_arithmetic_types : require
#extension GL_EXT_shader_explicit_arithmetic_types_float32 : require
#extension GL_GOOGLE_include_directive : enable

#include "0_disocclusion_mask_lq_shared.h"

layout(location = 0) out mediump float _OutDisocclusionMask;

void main()
{
    int32_t2 pixel = int32_t2(gl_FragCoord.xy);
    int32_t2 depth_size = textureSize(_DepthTm1Tex, 0);

    _OutDisocclusionMask = LqDisocclusionComputeMask(pixel, depth_size);
}
