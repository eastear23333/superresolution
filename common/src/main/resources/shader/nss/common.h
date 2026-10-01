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

#ifndef NSS_COMMON
#define NSS_COMMON

#include "typedefs.h"

#define MAX_FP16 65504.HF
#define EPS 1e-7HF

// Activation Functions
// -----------------------------------------------------------------------------
half Sigmoid(half x)   { return rcp(half(1.0) + exp(-x)); }
half2 Sigmoid(half2 x) { return rcp(half2(1.0) + exp(-x)); }
half3 Sigmoid(half3 x) { return rcp(half3(1.0) + exp(-x)); }
half4 Sigmoid(half4 x) { return rcp(half4(1.0) + exp(-x)); }

// Quantize/Dequantize
// -----------------------------------------------------------------------------
// all expect .x = scale, .y = zero point
half Dequantize(half i, half2 quant_params)   { return (i - quant_params.y) * quant_params.x; }
half2 Dequantize(half2 i, half2 quant_params) { return (i - quant_params.y) * quant_params.x; }
half3 Dequantize(half3 i, half2 quant_params) { return (i - quant_params.y) * quant_params.x; }
half4 Dequantize(half4 i, half2 quant_params) { return (i - quant_params.y) * quant_params.x; }

int8_t Quantize(half f, half2 quant_params)   { return int8_t(f * quant_params.x + quant_params.y); }
int8_t2 Quantize(half2 f, half2 quant_params) { return int8_t2(f * quant_params.x + quant_params.y); }
int8_t3 Quantize(half3 f, half2 quant_params) { return int8_t3(f * quant_params.x + quant_params.y); }
int8_t4 Quantize(half4 f, half2 quant_params) { return int8_t4(f * quant_params.x + quant_params.y); }

// Encode/Decode
// -----------------------------------------------------------------------------
uint8_t EncodeNearestDepthCoord(int32_t2 o)
{
    // o in {-2, -1, 0, +1, +2}^2
    o = clamp(o, int32_t2(-2), int32_t2(2));
    return uint8_t(((o.y + 2) << 3) | (o.x + 2)); // 0-24
}

int32_t2 DecodeNearestDepthCoord(int32_t code)
{
    int32_t x = int32_t(code & 0x7) - 2;        // bits 0-2
    int32_t y = int32_t((code >> 3) & 0x7) - 2; // bits 3-5
    return int32_t2(x, y);
}

uint8_t EncodeNearestDepthCoordNibble(int32_t2 o)
{
    // The packed low-quality quad path only emits offsets inside the current
    // 4x4 search footprint: {-1, 0, +1, +2}^2.
    o = clamp(o, int32_t2(-1), int32_t2(2));
    return uint8_t(((o.y + 1) << 2) | (o.x + 1)); // 0-15
}

int32_t2 DecodeNearestDepthCoordNibble(int32_t code)
{
    int32_t x = int32_t(code & 0x3) - 1;        // bits 0-1
    int32_t y = int32_t((code >> 2) & 0x3) - 1; // bits 2-3
    return int32_t2(x, y);
}

uint8_t EncodeJitterOffset(int32_t2 offset)
{
    uint8_t col = uint8_t(offset.x + 1);
    uint8_t row = uint8_t(offset.y + 1);
    return uint8_t(row * 3 + col + 1); // reserve 0 for holes
}

int32_t2 DecodeJitterOffset(uint8_t c8)
{
    uint8_t c = uint8_t(c8);
    uint8_t mask = uint8_t(c != 0u);
    uint8_t idx = (c - uint8_t(1)) * mask;
    int32_t x = int32_t(idx % 3u) - int32_t(mask);
    int32_t y = int32_t(idx / 3u) - int32_t(mask);
    return int32_t2(x, y);
}

// Image Operations
// -----------------------------------------------------------------------------
half Luminance(half3 rgb)
{
    return dot(rgb, half3(0.2126, 0.7152, 0.0722));
}

half3 Tonemap(half3 x)
{
    x = clamp(x, half3(0.HF), half3(MAX_FP16));
    return x * rcp(half3(1.HF) + max(max(x.r, x.g), x.b));
}

half3 InverseTonemap(half3 x)
{
    x = clamp(x, half3(0.HF), half3(1.HF - EPS));
    return x * rcp(half3(1.HF) - max(max(x.r, x.g), x.b));
}

half3 SafeColour(half3 x)
{
    return clamp(x, half3(0.HF), half3(MAX_FP16));
}

#endif // NSS_COMMON
