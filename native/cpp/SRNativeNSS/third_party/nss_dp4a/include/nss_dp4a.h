/*
 * nss_dp4a.h —— Arm NSS 神经超分的 DP4A 推理后端（公开 C API）
 *
 * 设计取向：**纯推理 DLL**。宿主拥有 VkDevice / VkQueue / VkCommandBuffer，
 * 本库只在宿主的设备上建管线、往宿主的命令缓冲里录 dispatch。
 * 输入输出都是宿主已有的 VkBuffer，**零拷贝**。
 *
 * 接口风格对齐 FidelityFX 的 ffx-api（FfxErrorCode 返回值 + 描述符结构体 + 上下文句柄），
 * 便于未来替换 SDK 里 `ffx_vk.cpp` 那两处 vkCmdDispatchDataGraphARM。
 *
 * 与 Vulkan ML 仿真层的区别：不需要 VK_ARM_data_graph / VK_ARM_tensors 扩展，
 * 只需要 VK_KHR_shader_integer_dot_product（Vulkan 1.3 核心）+ shaderInt64。
 *
 * 用法：
 *     NssDp4aContext *ctx = NULL;
 *     NssDp4aCreateInfo ci = { ... };          // 填入宿主的 device / physDev / queue 等
 *     nssDp4aCreateContext(&ci, &ctx);
 *
 *     // 每帧：在宿主的命令缓冲里录制
 *     NssDp4aDispatchInfo di = { ... };        // 填入 inBuf / outKpnBuf / outTemporalBuf
 *     nssDp4aRecord(ctx, cmd, &di);
 *
 *     nssDp4aDestroyContext(ctx);
 */
#ifndef NSS_DP4A_H
#define NSS_DP4A_H

#include <stddef.h>
#include <stdint.h>

#if defined(_WIN32)
#  ifdef NSS_DP4A_BUILD
#    define NSS_DP4A_API __declspec(dllexport)
#  else
#    define NSS_DP4A_API __declspec(dllimport)
#  endif
#  define NSS_DP4A_CALL __cdecl
#else
#  define NSS_DP4A_API __attribute__((visibility("default")))
#  define NSS_DP4A_CALL
#endif

#ifdef __cplusplus
extern "C" {
#endif

/* ------------------------------------------------------------------ 版本 */
#define NSS_DP4A_VERSION_MAJOR 1
#define NSS_DP4A_VERSION_MINOR 0
#define NSS_DP4A_VERSION_PATCH 0
#define NSS_DP4A_MAKE_VERSION(maj, min, pat) (((maj) << 22) | ((min) << 12) | (pat))
#define NSS_DP4A_VERSION NSS_DP4A_MAKE_VERSION(1, 0, 0)

/* ------------------------------------------------------------------ 错误码 */
typedef enum NssDp4aResult {
    NSS_DP4A_OK = 0,
    NSS_DP4A_ERROR_GENERIC = 1,
    NSS_DP4A_ERROR_UNSUPPORTED = 2,       /* 设备缺 DP4A / shaderInt64 */
    NSS_DP4A_ERROR_INVALID_ARGUMENT = 3,
    NSS_DP4A_ERROR_OUT_OF_MEMORY = 4,
    NSS_DP4A_ERROR_VULKAN_FAILED = 5,     /* 底层 Vulkan 调用失败 */
    NSS_DP4A_ERROR_NOT_READY = 6,         /* 模型未加载 */
    NSS_DP4A_ERROR_MODEL_MISMATCH = 7     /* 输入尺寸与烘焙模型不符 */
} NssDp4aResult;

/* ------------------------------------------------------------------ 质量档 */
typedef enum NssDp4aQuality {
    NSS_DP4A_QUALITY_HIGH = 0,     /* KPN 6x6 = 36 通道，输入 1/2 分辨率 */
    NSS_DP4A_QUALITY_MID_LOW = 1   /* KPN 4x4 = 16 通道，输入 1/4 分辨率 */
} NssDp4aQuality;

/* ------------------------------------------------------------------ 设备能力 */
typedef struct NssDp4aDeviceCaps {
    uint32_t integerDotProduct;                    /* VK_KHR_shader_integer_dot_product */
    uint32_t dotProduct4x8BitPackedSigned;         /* DP4A 硬件加速可用 */
    uint32_t shaderInt64;
    uint32_t maxComputeWorkGroupInvocations;
    uint32_t maxComputeSharedMemorySize;
    uint32_t maxStorageBufferRange;
    char     deviceName[256];
} NssDp4aDeviceCaps;

/* ------------------------------------------------------------------ 创建信息
 *
 * 宿主把已建好的 Vulkan 对象传进来。库不会创建或销毁它们。
 * 所有句柄都按 uint64_t 传递，避免头文件依赖 vulkan.h。
 */
typedef struct NssDp4aCreateInfo {
    uint64_t instance;          /* VkInstance */
    uint64_t physicalDevice;    /* VkPhysicalDevice */
    uint64_t device;            /* VkDevice */
    uint64_t queue;             /* VkQueue（必须支持 compute） */
    uint32_t queueFamilyIndex;
    uint32_t apiVersion;        /* 宿主的 VkPhysicalDeviceProperties::apiVersion */

    /* 可选：外部上传命令缓冲（VkCommandBuffer）。
     * 非 0 时，权重与内部缓冲的初始化拷贝会录制进它，由宿主统一提交
     * （SRAPI 流程：srCreateUpscaleContext / srInitUpscaleContext 录制，
     *  宿主 end + submit）。此时 queue 仅作占位，可为 0。
     * 为 0 时（独立测试模式），本库自建一次性命令缓冲并用 queue 提交。 */
    uint64_t uploadCommandBuffer;

    NssDp4aQuality quality;

    /* 本实例要处理的输入分辨率。必须是 8 的倍数。
     * NSS 输入尺寸随画质档与输出分辨率变化：
     *   HIGH 档跑 1080p 输出 -> 960x544
     *   MID_LOW 档跑 1080p 输出 -> 480x272
     * 若宿主分辨率会变，请为每个尺寸建一个上下文。 */
    uint32_t width;
    uint32_t height;

    /* 宿主的 Vulkan 加载器入口。传 NULL 则库自行 LoadLibrary("vulkan-1.dll")。
     * 用于非 Windows 或宿主已有加载器的场景：
     *   PFN_vkGetInstanceProcAddr 的地址。 */
    void *vkGetInstanceProcAddr;

    /* 可选：调试回调，可为 NULL */
    void (*logCallback)(int level, const char *msg, void *userData);
    void *logUserData;
} NssDp4aCreateInfo;

/* ------------------------------------------------------------------ 缓冲描述
 *
 * 宿主提供自己的 VkBuffer。库只把它们绑成 storage buffer 描述符。
 * 缓冲必须带 VK_BUFFER_USAGE_STORAGE_BUFFER_BIT，且足够大。
 */
typedef struct NssDp4aBuffer {
    uint64_t buffer;            /* VkBuffer */
    uint64_t offset;            /* 起始偏移，通常 0 */
    uint64_t size;              /* 可用字节数 */
} NssDp4aBuffer;

/* ------------------------------------------------------------------ 每帧输入
 *
 * 输入布局：NHWC int8，H×W×12，H/W 必须是 8 的倍数。
 * 输出布局：见下方注释，与 Arm 官方数据图的输出一致。
 */
typedef struct NssDp4aDispatchInfo {
    /* 输入：预处理后的 12 通道张量，int8 NHWC [H][W][12] */
    NssDp4aBuffer input;

    /* 输出 1：KPN 系数
     *   HIGH 档   -> [H/4][W/4][36]  int8
     *   MID_LOW 档 -> [H/4][W/4][16]  int8 */
    NssDp4aBuffer outputKpn;

    /* 输出 2：时序反馈张量，[H][W][4] int8 */
    NssDp4aBuffer outputTemporal;

    /* 本帧输入的像素尺寸。必须是 8 的倍数，且与创建时声明的一致。 */
    uint32_t width;
    uint32_t height;

    /* 库内部用到的临时显存。宿主可复用一个够大的缓冲；传 0 则由库自建。
     * 需要的大小可用 nssDp4aGetScratchSize() 查询。 */
    NssDp4aBuffer scratch;
} NssDp4aDispatchInfo;

/* ------------------------------------------------------------------ 上下文 */
typedef struct NssDp4aContext NssDp4aContext;

/* ------------------------------------------------------------------ API */

/* 查询设备是否满足要求。可在创建上下文前调用，用于能力探测与降级决策。 */
NSS_DP4A_API NssDp4aResult NSS_DP4A_CALL
nssDp4aQueryDeviceCaps(uint64_t instance,
                       uint64_t physicalDevice,
                       uint32_t apiVersion,
                       void    *vkGetInstanceProcAddr,
                       NssDp4aDeviceCaps *outCaps);

/* 创建上下文：建管线、上传权重、分配内部资源。
 * 内部会做一次 GPU 提交来上传权重并等待完成。 */
NSS_DP4A_API NssDp4aResult NSS_DP4A_CALL
nssDp4aCreateContext(const NssDp4aCreateInfo *createInfo,
                     NssDp4aContext **outContext);

/* 销毁上下文。只释放本库自己创建的资源，不动宿主的 Vulkan 对象。 */
NSS_DP4A_API void NSS_DP4A_CALL
nssDp4aDestroyContext(NssDp4aContext *context);

/* 把一帧推理录进宿主的命令缓冲。
 *
 * 会插入必要的 pipeline barrier（SHADER_WRITE -> SHADER_READ|WRITE）。
 * 不调用 vkQueueSubmit —— 提交由宿主决定，便于与前后处理同批提交。
 *
 * 调用者需保证：
 *   - cmd 处于 recording 状态
 *   - input 缓冲内容已就绪（前面的写操作已可见）
 *   - outputKpn / outputTemporal 未被其他 pass 占用
 */
NSS_DP4A_API NssDp4aResult NSS_DP4A_CALL
nssDp4aRecord(NssDp4aContext *context,
              uint64_t        commandBuffer,     /* VkCommandBuffer */
              const NssDp4aDispatchInfo *dispatchInfo);

/* 查询内部临时缓冲所需字节数（若宿主想自备 scratch）。 */
NSS_DP4A_API uint64_t NSS_DP4A_CALL
nssDp4aGetScratchSize(const NssDp4aContext *context);

/* 查询本库内部占用的显存字节数（不含宿主的输入输出缓冲）。 */
NSS_DP4A_API uint64_t NSS_DP4A_CALL
nssDp4aGetInternalMemoryUsage(const NssDp4aContext *context);

/* 取出版本号与一段可读的状态描述（用于日志）。 */
NSS_DP4A_API uint32_t NSS_DP4A_CALL nssDp4aGetVersion(void);
NSS_DP4A_API const char * NSS_DP4A_CALL nssDp4aGetResultString(NssDp4aResult result);

#ifdef __cplusplus
}  /* extern "C" */
#endif

#endif /* NSS_DP4A_H */
