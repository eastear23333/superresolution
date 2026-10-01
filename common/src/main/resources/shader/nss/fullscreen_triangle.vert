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

layout(location = 0) out vec2 vUV;

void main() {
    // Fullscreen triangle without vertex buffers
    vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    vUV = vec2(pos.x * 0.5, 1.0 - pos.y * 0.5);
    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
}
