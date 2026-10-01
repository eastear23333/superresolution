/*
 * NSS（Arm Neural Super Sampling）的 SRAPI 后端实现。
 *
 * 架构：
 *   光影（color/depth/motion）
 *        ↓
 *   前处理 shader  → 12 通道 int8 输入张量
 *        ↓
 *   nss_dp4a.dll   → KPN 参数 + 时序反馈张量
 *        ↓
 *   后处理 shader  → 全分辨率彩色输出
 *
 * 自产缓冲（光影不提供，算法内部维护，均为乒乓双缓冲）：
 *   - history（屏幕分辨率）：上一帧超分输出
 *   - feedback（渲染分辨率/4）：上一帧 NSS 时序输出
 *   - lumaDerivTm1（渲染分辨率/4）：上一帧 luma 导数
 *   - depthTm1（渲染分辨率/8）：上一帧深度散射结果
 */
#include "sr/nss/nss.h"

#include <atomic>
#include <cstring>
#include <mutex>
#include <new>
#include <string>
#include <vector>

#include "nss_dp4a.h"

#ifdef ON_WIN64
#  include <windows.h>
#else
#  include <dlfcn.h>
#endif

namespace {

/* ------------------------------------------------------------------ 日志 */

void logMsg(SRMessageCallback cb, SRMessageType type, const wchar_t *msg) {
    if (cb) cb(type, msg);
}

void logErr(SRMessageCallback cb, const wchar_t *msg) {
    logMsg(cb, SR_MESSAGE_TYPE_ERROR, msg);
}

void logInfo(SRMessageCallback cb, const wchar_t *msg) {
    logMsg(cb, SR_MESSAGE_TYPE_INFO, msg);
}

/**
 * DP4A 后端的日志桥。
 *
 * <p>nss_dp4a.dll 的调试回调给的是 UTF-8 {@code char*}，而 SR 的消息回调要
 * {@code wchar_t*}，这里做一次转换再转发。
 *
 * <p>这一步不能省：DLL 内部（Vulkan 资源创建、管线编译、缓冲分配）的失败原因
 * 全靠它带出来，否则宿主只看到笼统的 {@code "vulkan call failed"}，
 * 排查时完全不知道是哪一步、缺什么特性。
 */
void dp4aLogBridge(int level, const char *msg, void *userData) {
    auto *cb = static_cast<SRMessageCallback *>(userData);
    if (cb == nullptr || *cb == nullptr || msg == nullptr || *msg == '\0') {
        return;
    }
#ifdef ON_WIN64
    const int len = ::MultiByteToWideChar(CP_UTF8, 0, msg, -1, nullptr, 0);
    if (len <= 1) {
        return;
    }
    std::wstring wide(static_cast<size_t>(len), L'\0');
    ::MultiByteToWideChar(CP_UTF8, 0, msg, -1, wide.data(), len);
    (*cb)(level >= 2 ? SR_MESSAGE_TYPE_ERROR : SR_MESSAGE_TYPE_WARNING, wide.c_str());
#else
    (*cb)(level >= 2 ? SR_MESSAGE_TYPE_ERROR : SR_MESSAGE_TYPE_WARNING, L"(NSS DP4A)");
#endif
}

/* ------------------------------------------------------- DP4A 后端动态加载
 *
 * nss_dp4a.dll 与 NSS 模块同目录。用动态加载而非链接期依赖，
 * 这样模块本身能编出来，缺后端时只报运行时错误。
 */

struct Dp4aApi {
    void *module = nullptr;
    NssDp4aResult (*queryDeviceCaps)(uint64_t, uint64_t, uint32_t, void *, NssDp4aDeviceCaps *) = nullptr;
    NssDp4aResult (*createContext)(const NssDp4aCreateInfo *, NssDp4aContext **) = nullptr;
    void (*destroyContext)(NssDp4aContext *) = nullptr;
    NssDp4aResult (*record)(NssDp4aContext *, uint64_t, const NssDp4aDispatchInfo *) = nullptr;
    uint64_t (*getInternalMemoryUsage)(const NssDp4aContext *) = nullptr;
    uint32_t (*getVersion)() = nullptr;
    const char *(*getResultString)(NssDp4aResult) = nullptr;
    /* 外部上传模式下延迟 staging 的释放入口。
     * 老版本 DLL 没有这个符号，所以用 GetProcAddress 取，取不到就退化为
     * 「不调用」——此时 DLL 内部会在 destroyContext 的 vkDeviceWaitIdle 后回收。 */
    void (*flushPendingUploads)(NssDp4aContext *) = nullptr;
};

Dp4aApi g_dp4a;
std::mutex g_dp4aMutex;
std::atomic<int> g_contextCount{0};

bool loadDp4aApi(SRMessageCallback cb, const char *explicitPath) {
    std::lock_guard<std::mutex> lock(g_dp4aMutex);
    if (g_dp4a.module) return true;

#ifdef ON_WIN64
    const char *path = (explicitPath && explicitPath[0]) ? explicitPath : "nss_dp4a.dll";
    HMODULE m = ::LoadLibraryA(path);
    if (!m) {
        logErr(cb, L"无法加载 nss_dp4a.dll（请确认它与本模块在同一目录）");
        return false;
    }
    g_dp4a.module = m;
    g_dp4a.queryDeviceCaps = reinterpret_cast<decltype(g_dp4a.queryDeviceCaps)>(
        ::GetProcAddress(m, "nssDp4aQueryDeviceCaps"));
    g_dp4a.createContext = reinterpret_cast<decltype(g_dp4a.createContext)>(
        ::GetProcAddress(m, "nssDp4aCreateContext"));
    g_dp4a.destroyContext = reinterpret_cast<decltype(g_dp4a.destroyContext)>(
        ::GetProcAddress(m, "nssDp4aDestroyContext"));
    g_dp4a.record = reinterpret_cast<decltype(g_dp4a.record)>(
        ::GetProcAddress(m, "nssDp4aRecord"));
    g_dp4a.getInternalMemoryUsage = reinterpret_cast<decltype(g_dp4a.getInternalMemoryUsage)>(
        ::GetProcAddress(m, "nssDp4aGetInternalMemoryUsage"));
    g_dp4a.getVersion = reinterpret_cast<decltype(g_dp4a.getVersion)>(
        ::GetProcAddress(m, "nssDp4aGetVersion"));
    g_dp4a.getResultString = reinterpret_cast<decltype(g_dp4a.getResultString)>(
        ::GetProcAddress(m, "nssDp4aGetResultString"));
    g_dp4a.flushPendingUploads = reinterpret_cast<decltype(g_dp4a.flushPendingUploads)>(
        ::GetProcAddress(m, "nssDp4aFlushPendingUploads"));
#else
    (void) explicitPath;
    void *m = dlopen("libnss_dp4a.so", RTLD_NOW);
    if (!m) {
        logErr(cb, L"无法加载 libnss_dp4a.so");
        return false;
    }
    g_dp4a.module = m;
    g_dp4a.queryDeviceCaps = reinterpret_cast<decltype(g_dp4a.queryDeviceCaps)>(dlsym(m, "nssDp4aQueryDeviceCaps"));
    g_dp4a.createContext = reinterpret_cast<decltype(g_dp4a.createContext)>(dlsym(m, "nssDp4aCreateContext"));
    g_dp4a.destroyContext = reinterpret_cast<decltype(g_dp4a.destroyContext)>(dlsym(m, "nssDp4aDestroyContext"));
    g_dp4a.record = reinterpret_cast<decltype(g_dp4a.record)>(dlsym(m, "nssDp4aRecord"));
    g_dp4a.getInternalMemoryUsage = reinterpret_cast<decltype(g_dp4a.getInternalMemoryUsage)>(dlsym(m, "nssDp4aGetInternalMemoryUsage"));
    g_dp4a.getVersion = reinterpret_cast<decltype(g_dp4a.getVersion)>(dlsym(m, "nssDp4aGetVersion"));
    g_dp4a.getResultString = reinterpret_cast<decltype(g_dp4a.getResultString)>(dlsym(m, "nssDp4aGetResultString"));
    g_dp4a.flushPendingUploads = reinterpret_cast<decltype(g_dp4a.flushPendingUploads)>(dlsym(m, "nssDp4aFlushPendingUploads"));
#endif

    if (!g_dp4a.createContext || !g_dp4a.record || !g_dp4a.destroyContext) {
        logErr(cb, L"nss_dp4a.dll 缺少必需导出函数");
        return false;
    }
    return true;
}

void unloadDp4aApi() {
    std::lock_guard<std::mutex> lock(g_dp4aMutex);
    if (!g_dp4a.module) return;
#ifdef ON_WIN64
    ::FreeLibrary(static_cast<HMODULE>(g_dp4a.module));
#else
    dlclose(g_dp4a.module);
#endif
    g_dp4a = Dp4aApi{};
}

/* ------------------------------------------------------------ 私有数据 */

/// NSS 质量档（= 模型档位）。**与放大比例是两个独立的轴**：
///   放大比例决定实际渲染分辨率（渲染分辨率 = 屏幕分辨率 / 放大比例），
///   NSS v1_0_1 架构锁定 2x，所以这一轴只有一个取值；
///   本枚举决定用哪份烘焙权重、预处理跑在哪一档分辨率、KPN 通道数多少。
///   三档的输出分辨率都是 2x。
enum class NssQuality {
    High = 0,     ///< pre = 渲染分辨率，KPN 6x6 = 36 通道
    MidLow = 1,   ///< pre = 渲染分辨率 / 2，KPN 4x4 = 16 通道
};

/// 自产缓冲描述（尺寸在 create 时确定）
struct PingPongSurface {
    int width = 0;
    int height = 0;
    int channels = 0;
    /// 两张纹理轮换：index 0 是本帧要读的（上一帧结果），1 是本帧要写的
    uint64_t images[2] = {0, 0};
    uint64_t views[2] = {0, 0};
    int current = 0;   ///< 本帧写入的下标
};

struct SRNSSPrivateData {
    NssDp4aContext *dp4aContext = nullptr;
    SRMessageCallback messageCallback = nullptr;
    bool available = false;
    bool contextCreated = false;
    bool initialized = false;

    int renderWidth = 0;
    int renderHeight = 0;
    int screenWidth = 0;
    int screenHeight = 0;

    NssQuality quality = NssQuality::High;

    /// 预处理降采样倍数（High=1，MidLow=2）。由 quality 推出，
    /// 决定输入张量尺寸（= align8(渲染分辨率 / 本值)）。
    int preprocessDownscale = 1;

    /// NSS 输入张量尺寸（预处理分辨率向上对齐到 8 的倍数）
    int paddedWidth = 0;
    int paddedHeight = 0;

    /// 自产乒乓缓冲
    PingPongSurface history;         ///< 屏幕分辨率，R11G11B10
    PingPongSurface feedback;        ///< 输入张量尺寸的 4 通道，R8G8B8A8_SNORM
    PingPongSurface lumaDerivTm1;    ///< 输入张量尺寸，R8G8B8A8_SNORM
    PingPongSurface depthTm1;        ///< 深度散射尺寸（渲染/2 或 渲染/4），R32_UINT

    /// 前处理产出的 12 通道 int8 输入张量（VkBuffer）
    uint64_t inputTensorBuffer = 0;
    /// KPN 参数缓冲（int8，NHWC）
    uint64_t kpnBuffer = 0;
    /// 最近深度偏移（渲染分辨率）
    uint64_t nearestDepthOffset = 0;

    uint64_t frameCount = 0;
    bool resetHistory = true;
    /// 初始化阶段的延迟 staging 是否已释放（见 srNSSDispatchUpscale）
    bool pendingUploadsFlushed = false;
};

/* ------------------------------------------------------ extraParams 查询 */

const SRContextExtraParam *findParam(const SRContextExtraParams &params, const char *name) {
    for (uint32_t i = 0; i < params.extraParamCount; ++i) {
        const SRContextExtraParam &p = params.extraParams[i];
        if (p.exist && p.name && std::strcmp(p.name, name) == 0) {
            return &p;
        }
    }
    return nullptr;
}

/// 读取宿主显式指定的模型档位。
///
/// 宿主通过 extraParams 传 `NSS_MODEL`（int32，0=High / 1=MidLow），
/// 该值来自 UI 的「模型档位」下拉框 —— 与超分比例无关。
///
/// 若宿主没传（旧宿主 / 其他调用方），回退到按放大比例推断：NSS v1_0_1
/// 三档输出都是 2x，因此比例 ≥ 2.0 时用 High 只是保守选择，不是真在选档。
NssQuality pickQuality(const SRContextExtraParams &params, int renderW, int screenW) {
    const SRContextExtraParam *modelParam = findParam(params, "NSS_MODEL");
    if (modelParam && modelParam->exist) {
        if (modelParam->valueType == SR_PARAM_VALUE_TYPE_INT32) {
            return (modelParam->value.int32Value == 0) ? NssQuality::High : NssQuality::MidLow;
        }
        if (modelParam->valueType == SR_PARAM_VALUE_TYPE_UINT32) {
            return (modelParam->value.uint32Value == 0) ? NssQuality::High : NssQuality::MidLow;
        }
    }
    if (renderW <= 0) return NssQuality::High;
    const float ratio = static_cast<float>(screenW) / static_cast<float>(renderW);
    return (ratio >= 2.0f) ? NssQuality::High : NssQuality::MidLow;
}

/// 预处理降采样倍数：High 走全分辨率预处理，MidLow 走半分辨率。
int preprocessDownscaleOf(NssQuality quality) {
    return (quality == NssQuality::High) ? 1 : 2;
}

/// KPN 通道数：High 用 6x6 = 36，MidLow 用 4x4 = 16。
int kpnChannelsOf(NssQuality quality) {
    return (quality == NssQuality::High) ? 36 : 16;
}

int alignUp(int value, int alignment) {
    return (value + alignment - 1) / alignment * alignment;
}

}  /* namespace */

/* ==================================================================== API */

#ifdef __cplusplus
extern "C" {
#endif

SR_API SRReturnCode srNSSCreateUpscaleContext(SRUpscaleContext *context,
                                              const SRCreateUpscaleContextDesc *desc) {
    if (!context || !desc) {
        return SR_RETURN_CODE_NULL_POINTER;
    }
    if (desc->renderApiType != SR_RENDER_API_TYPE_VULKAN) {
        logErr(desc->messageCallback, L"NSS 目前仅支持 Vulkan");
        return SR_RETURN_CODE_UNSUPPORTED_RENDER_API;
    }

    /* 1) 加载 DP4A 后端 */
    const char *dllPath = nullptr;
    const SRContextExtraParam *pathParam = findParam(desc->extraParams, "NSS_DP4A_DLL_PATH");
    if (pathParam && pathParam->valueType == SR_PARAM_VALUE_TYPE_STRING) {
        dllPath = pathParam->value.stringValue;
    }
    if (!loadDp4aApi(desc->messageCallback, dllPath)) {
        return SR_RETURN_CODE_CANNOT_FIND_LIBRARY;
    }

    /* 2) 设备能力检查 */
    const SRVulkanDeviceInfo &vk = desc->renderDeviceInfo.vulkan;
    NssDp4aDeviceCaps caps{};
    NssDp4aResult qr = g_dp4a.queryDeviceCaps(
        reinterpret_cast<uint64_t>(vk.instance),
        reinterpret_cast<uint64_t>(vk.physicalDevice),
        0x00403000u,   /* Vulkan 1.3 */
        reinterpret_cast<void *>(vk.instanceProcAddr),
        &caps);
    if (qr != NSS_DP4A_OK) {
        logErr(desc->messageCallback, L"设备不满足 NSS 要求（需要 DP4A + shaderInt64）");
        return SR_RETURN_CODE_UNSUPPORTED;
    }
    if (!caps.dotProduct4x8BitPackedSigned) {
        logMsg(desc->messageCallback, SR_MESSAGE_TYPE_WARNING,
               L"DP4A 无硬件加速，NSS 性能会显著下降");
    }

    /* 3) 建私有数据 */
    auto *pd = new(std::nothrow) SRNSSPrivateData{};
    if (!pd) {
        return SR_RETURN_CODE_ERROR;
    }

    pd->messageCallback = desc->messageCallback;
    pd->renderWidth = static_cast<int>(desc->renderSize.x);
    pd->renderHeight = static_cast<int>(desc->renderSize.y);
    pd->screenWidth = static_cast<int>(desc->upscaledSize.x);
    pd->screenHeight = static_cast<int>(desc->upscaledSize.y);
    /* 模型档位由宿主显式指定（UI 的「模型档位」下拉框），与超分比例无关 */
    pd->quality = pickQuality(desc->extraParams, pd->renderWidth, pd->screenWidth);
    pd->preprocessDownscale = preprocessDownscaleOf(pd->quality);

    if (pd->renderWidth <= 0 || pd->renderHeight <= 0) {
        logErr(desc->messageCallback, L"渲染分辨率为 0");
        delete pd;
        return SR_RETURN_CODE_INVALID_ARGUMENT;
    }

    /* NSS 输入张量尺寸：预处理分辨率向上对齐 8 的倍数（NSS 的约束）。
     * High 档预处理就是渲染分辨率 → align8(渲染分辨率)；
     * MidLow 档预处理是渲染分辨率的一半 → align8(渲染分辨率 / 2)。 */
    pd->paddedWidth = alignUp(pd->renderWidth / pd->preprocessDownscale, 8);
    pd->paddedHeight = alignUp(pd->renderHeight / pd->preprocessDownscale, 8);

    /* 4) 创建 DP4A 上下文（纯推理形态，复用宿主的设备）。
     * 权重上传录制进 SRAPI 的 initCommandBuffer，由宿主统一提交
     * （XeSS 模式：begin → create → init → end → submit）。 */
    NssDp4aCreateInfo ci{};
    ci.instance = reinterpret_cast<uint64_t>(vk.instance);
    ci.physicalDevice = reinterpret_cast<uint64_t>(vk.physicalDevice);
    ci.device = reinterpret_cast<uint64_t>(vk.device);
    ci.queue = 0;   /* 外部上传模式下不自行提交 */
    ci.queueFamilyIndex = 0;
    ci.apiVersion = 0x00403000u;
    ci.uploadCommandBuffer = reinterpret_cast<uint64_t>(vk.initCommandBuffer);
    ci.quality = (pd->quality == NssQuality::High) ? NSS_DP4A_QUALITY_HIGH
                                                   : NSS_DP4A_QUALITY_MID_LOW;
    ci.width = static_cast<uint32_t>(pd->paddedWidth);
    ci.height = static_cast<uint32_t>(pd->paddedHeight);
    ci.vkGetInstanceProcAddr = reinterpret_cast<void *>(vk.instanceProcAddr);
    /* 把 DLL 内部的失败原因带出来。指向 pd->messageCallback 的生命周期
     * 覆盖整个 DP4A 上下文（pd 在 destroy 之后才释放）。 */
    ci.logCallback = dp4aLogBridge;
    ci.logUserData = &pd->messageCallback;

    NssDp4aResult cr = g_dp4a.createContext(&ci, &pd->dp4aContext);
    if (cr != NSS_DP4A_OK) {
        logErr(desc->messageCallback, L"创建 NSS DP4A 上下文失败");
        if (g_dp4a.getResultString) {
            /* 把具体错误码转成可读信息（UTF-8 → wchar_t） */
            const char *s = g_dp4a.getResultString(cr);
            if (s != nullptr && *s != '\0') {
                const int len = ::MultiByteToWideChar(CP_UTF8, 0, s, -1, nullptr, 0);
                if (len > 1) {
                    std::wstring w(static_cast<size_t>(len), L'\0');
                    ::MultiByteToWideChar(CP_UTF8, 0, s, -1, w.data(), len);
                    logErr(desc->messageCallback, w.c_str());
                }
            }
        }
        delete pd;
        return SR_RETURN_CODE_ERROR;
    }

    pd->contextCreated = true;
    pd->available = true;
    context->desc = *desc;
    context->userContext = pd;

    ++g_contextCount;
    logInfo(desc->messageCallback, L"NSS 上下文创建成功");
    return SR_RETURN_CODE_OK;
}

SR_API SRReturnCode srNSSInitUpscaleContext(SRUpscaleContext *context) {
    if (!context || !context->userContext) {
        return SR_RETURN_CODE_NULL_POINTER;
    }
    auto *pd = static_cast<SRNSSPrivateData *>(context->userContext);
    if (!pd->contextCreated || !pd->dp4aContext) {
        return SR_RETURN_CODE_UNSUPPORTED;
    }
    if (pd->initialized) {
        return SR_RETURN_CODE_OK;
    }

    /* 纹理与缓冲的创建由 SRAPI 侧负责（interop 层已建好），
     * 这里只做状态初始化。 */
    pd->frameCount = 0;
    pd->resetHistory = true;
    pd->initialized = true;
    return SR_RETURN_CODE_OK;
}

SR_API SRReturnCode srNSSDestroyUpscaleContext(SRUpscaleContext *context) {
    if (!context || !context->userContext) {
        return SR_RETURN_CODE_NULL_POINTER;
    }
    auto *pd = static_cast<SRNSSPrivateData *>(context->userContext);

    if (pd->dp4aContext && g_dp4a.destroyContext) {
        g_dp4a.destroyContext(pd->dp4aContext);
        pd->dp4aContext = nullptr;
    }
    --g_contextCount;
    if (g_contextCount <= 0) {
        unloadDp4aApi();
    }

    delete pd;
    context->userContext = nullptr;
    return SR_RETURN_CODE_OK;
}

SR_API SRReturnCode srNSSQueryUpscale(SRUpscaleContext *context,
                                      SRUpscaleContextQueryResult *result,
                                      int queryType) {
    if (!context || !result) {
        return SR_RETURN_CODE_NULL_POINTER;
    }
    auto *pd = static_cast<SRNSSPrivateData *>(context->userContext);

    switch (queryType) {
        case SR_UPSCALE_CONTEXT_QUERY_VERSION_INFO: {
            auto *v = static_cast<SRQueryVersionResult *>(result->data);
            if (!v) return SR_RETURN_CODE_NULL_POINTER;
            v->versionNumber = SR_MAKE_VERSION(1, 0, 0);
            v->versionId = g_dp4a.getVersion ? g_dp4a.getVersion() : 0;
            return SR_RETURN_CODE_OK;
        }
        case SR_UPSCALE_CONTEXT_QUERY_GPU_MEMORY_INFO: {
            auto *m = static_cast<SRQueryGpuMemoryResult *>(result->data);
            if (!m) return SR_RETURN_CODE_NULL_POINTER;
            m->gpuMemory = 0;
            if (pd && pd->dp4aContext && g_dp4a.getInternalMemoryUsage) {
                m->gpuMemory = g_dp4a.getInternalMemoryUsage(pd->dp4aContext);
            }
            return SR_RETURN_CODE_OK;
        }
        case SR_UPSCALE_CONTEXT_QUERY_AVAILABLE: {
            auto *a = static_cast<SRQueryAvailabilityResult *>(result->data);
            if (!a) return SR_RETURN_CODE_NULL_POINTER;
            a->isAvailable = pd ? pd->available : false;
            return SR_RETURN_CODE_OK;
        }
        default:
            return SR_RETURN_CODE_INVALID_ARGUMENT;
    }
}

SR_API SRReturnCode srNSSDispatchUpscale(SRUpscaleContext *context,
                                         const SRDispatchUpscaleDesc *desc) {
    if (!context || !context->userContext || !desc) {
        return SR_RETURN_CODE_NULL_POINTER;
    }
    auto *pd = static_cast<SRNSSPrivateData *>(context->userContext);
    if (!pd->contextCreated || !pd->initialized || !pd->dp4aContext) {
        return SR_RETURN_CODE_ERROR;
    }
    if (!desc->color.exist || !desc->output.exist) {
        logErr(pd->messageCallback, L"NSS 需要 color 与 output");
        return SR_RETURN_CODE_INVALID_ARGUMENT;
    }

    const VkCommandBuffer cmd = desc->commandList.apiCommandBuffer.vulkan.commandBuffer;
    if (cmd == VK_NULL_HANDLE) {
        return SR_RETURN_CODE_INVALID_ARGUMENT;
    }

    if (desc->reset) {
        pd->resetHistory = true;
    }

    /* 首次 dispatch 时释放初始化阶段的 staging 缓冲。
     *
     * 依据：srCreateUpscaleContext 把权重/常量上传「录制」进了宿主的
     * initCommandBuffer，没有提交；到第一次 dispatch 时，宿主早已完成
     * 「end → submit → waitForFence」整个初始化提交，GPU 也早已读完 staging。
     * 此时释放才安全 —— 提前释放会让 GPU 访问已释放内存（表现为
     * VK_ERROR_DEVICE_LOST(-4) 与长时间卡顿）。 */
    if (!pd->pendingUploadsFlushed) {
        pd->pendingUploadsFlushed = true;
        if (g_dp4a.flushPendingUploads && pd->dp4aContext) {
            g_dp4a.flushPendingUploads(pd->dp4aContext);
        }
    }

    /* ---- 从 dispatch extraParams 取推理缓冲句柄 ----
     *
     * 这些缓冲由宿主（Java 侧 NSS.java）创建并管理：
     *   NSS_INPUT_TENSOR_BUFFER  : 12ch int8 输入张量，紧凑 NHWC [H][W][12]（8 对齐尺寸）
     *                              nss_dp4a 内部会做带边框拷贝，宿主无需预填 0x80
     *   NSS_KPN_BUFFER           : KPN 输出，int8 NHWC [(H/4)][(W/4)][36 或 16]
     *   NSS_TEMPORAL_BUFFER      : 时序反馈输出，int8 NHWC [H][W][4]
     *
     * 时序反馈的乒乓（下一帧作为 _FeedbackTensor 读回）由宿主负责：
     * 用 BufferImageCopy 把 NSS_TEMPORAL_BUFFER 拷进一张 R8G8B8A8_SNORM 纹理即可
     * （该纹理按 Tensor->Texture Alias 语义供前处理采样）。
     */
    const SRContextExtraParam *inParam = findParam(desc->extraParams, "NSS_INPUT_TENSOR_BUFFER");
    const SRContextExtraParam *kpnParam = findParam(desc->extraParams, "NSS_KPN_BUFFER");
    const SRContextExtraParam *tmpParam = findParam(desc->extraParams, "NSS_TEMPORAL_BUFFER");

    if (!inParam || !kpnParam || !tmpParam ||
        inParam->valueType != SR_PARAM_VALUE_TYPE_POINTER ||
        kpnParam->valueType != SR_PARAM_VALUE_TYPE_POINTER ||
        tmpParam->valueType != SR_PARAM_VALUE_TYPE_POINTER) {
        logErr(pd->messageCallback,
               L"缺少推理缓冲：请在 dispatch extraParams 传入 "
               L"NSS_INPUT_TENSOR_BUFFER / NSS_KPN_BUFFER / NSS_TEMPORAL_BUFFER（pointer 类型）");
        return SR_RETURN_CODE_INVALID_ARGUMENT;
    }

    NssDp4aDispatchInfo di{};
    di.input.buffer = static_cast<uint64_t>(inParam->value.uint64Value);
    di.input.offset = 0;
    di.input.size = 0;
    di.outputKpn.buffer = static_cast<uint64_t>(kpnParam->value.uint64Value);
    di.outputKpn.offset = 0;
    di.outputKpn.size = 0;
    di.outputTemporal.buffer = static_cast<uint64_t>(tmpParam->value.uint64Value);
    di.outputTemporal.offset = 0;
    di.outputTemporal.size = 0;

    /* dispatch 的 renderSize 是真实渲染分辨率（如 960x540），而 DP4A 上下文按
     * 预处理分辨率对齐 8 后的尺寸创建。校验时必须走与创建时完全相同的算式，
     * 否则 MID_LOW 档（preprocessDownscale = 2）会把 align8(renderW)=1920 与
     * 创建时的 align8(renderW/2)=960 相比 —— 恒不相等，每帧直接返回错误，
     * 表现为「启用 NSS 后卡一会然后失败，且后续所有算法都失败」。
     *
     * 正确算式（与 nss.cpp:351-352 的创建侧、Java 侧 NSS.computeDimensions 一致）：
     *   padded = alignUp(renderSize / preprocessDownscale, 8) */
    const int expectW = alignUp(static_cast<int>(desc->renderSize.x) / pd->preprocessDownscale, 8);
    const int expectH = alignUp(static_cast<int>(desc->renderSize.y) / pd->preprocessDownscale, 8);
    if (expectW != pd->paddedWidth || expectH != pd->paddedHeight) {
        logErr(pd->messageCallback, L"renderSize 与创建上下文时不一致（分辨率变化需重建上下文）");
        return SR_RETURN_CODE_ERROR;
    }
    di.width = static_cast<uint32_t>(pd->paddedWidth);
    di.height = static_cast<uint32_t>(pd->paddedHeight);

    NssDp4aResult r = g_dp4a.record(pd->dp4aContext,
                                    reinterpret_cast<uint64_t>(cmd), &di);
    if (r != NSS_DP4A_OK) {
        logErr(pd->messageCallback, L"NSS 推理录制失败");
        return SR_RETURN_CODE_ERROR;
    }

    /* 乒乓翻转：本帧写的那张，下一帧作为历史读 */
    pd->feedback.current ^= 1;
    pd->lumaDerivTm1.current ^= 1;
    pd->history.current ^= 1;
    pd->depthTm1.current ^= 1;
    ++pd->frameCount;
    pd->resetHistory = false;

    return SR_RETURN_CODE_OK;
}

SR_API SRReturnCode srNSSShutdown() {
    unloadDp4aApi();
    return SR_RETURN_CODE_OK;
}

SR_API SRUpscaleContextCallbacks srGetNSSUpscaleCallbacks() {
    static SRUpscaleContextCallbacks callbacks = {
        .pCreate = static_cast<SRCreateFunc>(srNSSCreateUpscaleContext),
        .pInit = static_cast<SRInitFunc>(srNSSInitUpscaleContext),
        .pDestroy = static_cast<SRDestroyFunc>(srNSSDestroyUpscaleContext),
        .pQuery = reinterpret_cast<SRQueryFunc>(srNSSQueryUpscale),
        .pDispatchUpscale = static_cast<SRDispatchUpscaleFunc>(srNSSDispatchUpscale),
        .pShutdown = static_cast<SRShutdownFunc>(srNSSShutdown),
    };
    return callbacks;
}

#ifdef __cplusplus
}  /* extern "C" */
#endif
