#version 460
// 注：项目运行时由 Java 侧 FileIncluder 展开 #include，无需该扩展；
// 此处仅为本机 glslangValidator 离线验证而声明。
#ifndef SR_OFFLINE_GLSLANG
#extension GL_GOOGLE_include_directive : require
#endif
#extension GL_EXT_shader_8bit_storage : require
#extension GL_EXT_shader_16bit_storage : require
#extension GL_EXT_shader_explicit_arithmetic_types : require
#extension GL_EXT_shader_explicit_arithmetic_types_int8 : require
#extension GL_EXT_shader_explicit_arithmetic_types_float16 : require
#extension GL_EXT_shader_explicit_arithmetic_types_float32 : require

#include "typedefs.h"
#include "common.h"

// 最小验证：用 Arm 的真实 typedef（int8_t4 = i8vec4）做 SSBO 读写。
// 目的：确认 GL 驱动接受 8bit storage —— NSS 的 12ch int8 张量依赖它。
layout(local_size_x = 64) in;

layout(std430, binding = 0) readonly buffer Src {
    int8_t4 data[];
} src;

layout(std430, binding = 1) writeonly buffer Dst {
    int8_t4 data[];
} dst;

void main() {
    uint i = gl_GlobalInvocationID.x;
    int8_t4 v = src.data[i];
    // 逐分量取负，验证 int8 算术按 8 位执行
    dst.data[i] = int8_t4(int8_t(-v.x), int8_t(-v.y), int8_t(-v.z), int8_t(-v.w));
}
