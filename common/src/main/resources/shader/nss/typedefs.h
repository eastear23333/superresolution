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

#ifndef NSS_TYPEDEFS
#define NSS_TYPEDEFS

// fp16 types
#define half  float16_t
#define half2 f16vec2
#define half3 f16vec3
#define half4 f16vec4

// fp32 types
#define float  float32_t
#define float2 f32vec2
#define float3 f32vec3
#define float4 f32vec4

// int8 types
#define int8_t  int8_t
#define int8_t2 i8vec2
#define int8_t3 i8vec3
#define int8_t4 i8vec4

// int16 types
#define int16_t  int16_t
#define int16_t2 i16vec2
#define int16_t3 i16vec3
#define int16_t4 i16vec4

// uint16 types
#define uint16_t  uint16_t
#define uint16_t2 u16vec2
#define uint16_t3 u16vec3
#define uint16_t4 u16vec4

// int32 types
#define int32_t  int32_t
#define int32_t2 i32vec2
#define int32_t3 i32vec3
#define int32_t4 i32vec4

// uint32 types
#define uint32_t  uint32_t
#define uint32_t2 u32vec2
#define uint32_t3 u32vec3
#define uint32_t4 u32vec4

// methods
#define lerp mix

// --- RCP functions for float16 types ---
half rcp(half x)     { return half(1.HF) / x; }
half2 rcp(half2 x)   { return half2(1.HF) / x; }
half3 rcp(half3 x)   { return half3(1.HF) / x; }
half4 rcp(half4 x)   { return half4(1.HF) / x; }

// --- RCP functions for float32 types ---
float rcp(float x)   { return float(1.0f) / x; }
float2 rcp(float2 x) { return float2(1.0f) / x; }
float3 rcp(float3 x) { return float3(1.0f) / x; }
float4 rcp(float4 x) { return float4(1.0f) / x; }

// --- Saturate functions for float16 types ---
half saturate(half x)    { return clamp(x, half(0.HF), half(1.HF)); }
half2 saturate(half2 x)  { return clamp(x, half2(0.HF), half2(1.HF)); }
half3 saturate(half3 x)  { return clamp(x, half3(0.HF), half3(1.HF)); }
half4 saturate(half4 x)  { return clamp(x, half4(0.HF), half4(1.HF)); }

// --- Saturate functions for float32 types ---
float saturate(float x)    { return clamp(x, 0.f, 1.f); }
float2 saturate(float2 x)  { return clamp(x, float2(0.f), float2(1.f)); }
float3 saturate(float3 x)  { return clamp(x, float3(0.f), float3(1.f)); }
float4 saturate(float4 x)  { return clamp(x, float4(0.f), float4(1.f)); }

#endif  // NSS_TYPEDEFS
