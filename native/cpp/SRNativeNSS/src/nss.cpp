#include "sr/sr_api.h"
#include "sr/nss/nss.h"
#include "FidelityFX/host/backends/vk/ffx_vk.h"
#include "FidelityFX/host/backends/vk/vk_wrapper.h"
#include "FidelityFX/host/ffx_nss.h"
#include <cstring>
#include <cstdlib>
#include <string>
#include "sr/nss/sr_provider.h"

// NSS context flags: 直接使用 SDK 头文件 ffx_nss.h 的 FfxNssInitializationFlagBits 枚举。
// 注意: 不要自定义位值! 早期实现从 FSR 抄了自定义宏 (MANAGE_HISTORY=1<<4),
// 与 SDK 官方定义错位 (官方 MANAGE_HISTORY=1<<6), 导致 SDK 误判 MANAGE_HISTORY 未设置,
// dispatch 时强制要求 outputTm1 非空 → FFX_ERROR_INVALID_POINTER 黑屏。

struct SRNssPrivateData {
    FfxInterface *ffxInterface;
    FfxNssContext *context;
    void *scratchBuffer;
    FfxNssShaderQualityMode qualityMode;
    uint32_t contextFlags;
    SRMessageCallback messageCallback;
    bool contextInitialized = false;  // true after ffxNssContextCreate succeeds
};

#ifdef __cplusplus
extern "C" {
    #endif

    // --- Resource conversion helpers (inline, same as FSR adapter) ---

    static FfxSurfaceFormat srTextureFormatToFfxSurfaceFormat(SRTextureFormat format) {
        switch (format) {
            case SR_TEXTURE_FORMAT_UNKNOWN:               return FFX_SURFACE_FORMAT_UNKNOWN;
            case SR_TEXTURE_FORMAT_R32G32B32A32_TYPELESS: return FFX_SURFACE_FORMAT_R32G32B32A32_TYPELESS;
            case SR_TEXTURE_FORMAT_R32G32B32A32_UINT:     return FFX_SURFACE_FORMAT_R32G32B32A32_UINT;
            case SR_TEXTURE_FORMAT_R32G32B32A32_FLOAT:    return FFX_SURFACE_FORMAT_R32G32B32A32_FLOAT;
            case SR_TEXTURE_FORMAT_R16G16B16A16_FLOAT:    return FFX_SURFACE_FORMAT_R16G16B16A16_FLOAT;
            case SR_TEXTURE_FORMAT_R16G16B16A16_SNORM:    return FFX_SURFACE_FORMAT_UNKNOWN;
            case SR_TEXTURE_FORMAT_R32G32B32_FLOAT:       return FFX_SURFACE_FORMAT_R32G32B32_FLOAT;
            case SR_TEXTURE_FORMAT_R32G32_FLOAT:          return FFX_SURFACE_FORMAT_R32G32_FLOAT;
            case SR_TEXTURE_FORMAT_R8_UINT:               return FFX_SURFACE_FORMAT_R8_UINT;
            case SR_TEXTURE_FORMAT_R32_UINT:              return FFX_SURFACE_FORMAT_R32_UINT;
            case SR_TEXTURE_FORMAT_R8G8B8A8_TYPELESS:      return FFX_SURFACE_FORMAT_R8G8B8A8_UNORM;
            case SR_TEXTURE_FORMAT_R8G8B8A8_UNORM:        return FFX_SURFACE_FORMAT_R8G8B8A8_UNORM;
            case SR_TEXTURE_FORMAT_R8G8B8A8_SNORM:         return FFX_SURFACE_FORMAT_R8G8B8A8_SNORM;
            case SR_TEXTURE_FORMAT_R8G8B8A8_SRGB:          return FFX_SURFACE_FORMAT_R8G8B8A8_SRGB;
            case SR_TEXTURE_FORMAT_B8G8R8A8_TYPELESS:      return FFX_SURFACE_FORMAT_B8G8R8A8_UNORM;
            case SR_TEXTURE_FORMAT_B8G8R8A8_UNORM:         return FFX_SURFACE_FORMAT_B8G8R8A8_UNORM;
            case SR_TEXTURE_FORMAT_B8G8R8A8_SRGB:          return FFX_SURFACE_FORMAT_B8G8R8A8_SRGB;
            case SR_TEXTURE_FORMAT_R11G11B10_FLOAT:       return FFX_SURFACE_FORMAT_R11G11B10_FLOAT;
            case SR_TEXTURE_FORMAT_R10G10B10A2_UNORM:      return FFX_SURFACE_FORMAT_R10G10B10A2_UNORM;
            case SR_TEXTURE_FORMAT_R16G16_FLOAT:           return FFX_SURFACE_FORMAT_R16G16_FLOAT;
            case SR_TEXTURE_FORMAT_R16G16_UINT:            return FFX_SURFACE_FORMAT_R16G16_UINT;
            case SR_TEXTURE_FORMAT_R16G16_SINT:            return FFX_SURFACE_FORMAT_R16G16_SINT;
            case SR_TEXTURE_FORMAT_R16_FLOAT:              return FFX_SURFACE_FORMAT_R16_FLOAT;
            case SR_TEXTURE_FORMAT_R16_UINT:               return FFX_SURFACE_FORMAT_R16_UINT;
            case SR_TEXTURE_FORMAT_R16_UNORM:              return FFX_SURFACE_FORMAT_R16_UNORM;
            case SR_TEXTURE_FORMAT_R16_SNORM:              return FFX_SURFACE_FORMAT_R16_SNORM;
            case SR_TEXTURE_FORMAT_R8_UNORM:               return FFX_SURFACE_FORMAT_R8_UNORM;
            case SR_TEXTURE_FORMAT_R8G8_UNORM:             return FFX_SURFACE_FORMAT_R8G8_UNORM;
            case SR_TEXTURE_FORMAT_R8G8_UINT:              return FFX_SURFACE_FORMAT_R8G8_UINT;
            case SR_TEXTURE_FORMAT_R32_FLOAT:              return FFX_SURFACE_FORMAT_R32_FLOAT;
            case SR_TEXTURE_FORMAT_R9G9B9E5_SHAREDEXP:    return FFX_SURFACE_FORMAT_R9G9B9E5_SHAREDEXP;
            case SR_TEXTURE_FORMAT_D32_SFLOAT:             return FFX_SURFACE_FORMAT_UNKNOWN;
            default:                                       return FFX_SURFACE_FORMAT_UNKNOWN;
        }
    }

    static FfxResourceUsage srTextureResourceUsageToFfx(SRResourceUsage usage) {
        FfxResourceUsage ffxUsage = FFX_RESOURCE_USAGE_READ_ONLY;
        if (usage & SR_RESOURCE_USAGE_RENDERTARGET)
            ffxUsage = (FfxResourceUsage)(ffxUsage | FFX_RESOURCE_USAGE_RENDERTARGET);
        if (usage & SR_RESOURCE_USAGE_UAV)
            ffxUsage = (FfxResourceUsage)(ffxUsage | FFX_RESOURCE_USAGE_UAV);
        if (usage & SR_RESOURCE_USAGE_DEPTHTARGET)
            ffxUsage = (FfxResourceUsage)(ffxUsage | FFX_RESOURCE_USAGE_DEPTHTARGET);
        if (usage & SR_RESOURCE_USAGE_INDIRECT)
            ffxUsage = (FfxResourceUsage)(ffxUsage | FFX_RESOURCE_USAGE_INDIRECT);
        if (usage & SR_RESOURCE_USAGE_ARRAYVIEW)
            ffxUsage = (FfxResourceUsage)(ffxUsage | FFX_RESOURCE_USAGE_ARRAYVIEW);
        if (usage & SR_RESOURCE_USAGE_STENCILTARGET)
            ffxUsage = (FfxResourceUsage)(ffxUsage | FFX_RESOURCE_USAGE_STENCILTARGET);
        if (usage & SR_RESOURCE_USAGE_DCC_RENDERTARGET)
            ffxUsage = (FfxResourceUsage)(ffxUsage | FFX_RESOURCE_USAGE_DCC_RENDERTARGET);
        return ffxUsage;
    }

    static FfxResource srTextureResourceToFfxResource(const SRTextureResource *srTex) {
        FfxResource resource = {};
        resource.resource = (void*)(srTex->handle);
        resource.state = FFX_RESOURCE_STATE_COMPUTE_READ;
        resource.description.format = srTextureFormatToFfxSurfaceFormat(srTex->desc.format);
        resource.description.width = srTex->desc.width;
        resource.description.height = srTex->desc.height;
        resource.description.depth = 1;
        resource.description.mipCount = srTex->desc.mipmapCount;
        resource.description.type = FFX_RESOURCE_TYPE_TEXTURE2D;
        resource.description.usage = srTextureResourceUsageToFfx(srTex->desc.usage);
        resource.description.flags = FFX_RESOURCE_FLAGS_NONE;
        return resource;
    }

    static FfxResource srTextureResourceToFfxOutputResource(const SRTextureResource *srTex) {
        FfxResource resource = srTextureResourceToFfxResource(srTex);
        resource.state = FFX_RESOURCE_STATE_COMPUTE_UAV;
        return resource;
    }

    // --- Message callback bridge: FfxMsgType(const char*) -> SRMessageCallback(const wchar_t*) ---

    // 全局 SR 消息回调 (由 srNssCreateUpscaleContext 注册), 用于把 SDK 的 printMessage 转发到游戏日志。
    // 注意: SDK 的 fpMessage 在 context description 上设置, 但 backend 的 fpSetMessageCallback 也需要设置,
    // 否则 backend 内部 (ffx_vk.cpp) 的错误消息被吞掉, dispatch 失败原因完全不可见。
    static SRMessageCallback g_srMessageCallback = nullptr;

    static void nssMessageBridge(FfxMsgType type, const char *message) {
        if (!g_srMessageCallback || !message) return;
        SRMessageType srType = (type == FFX_MESSAGE_TYPE_ERROR) ? SR_MESSAGE_TYPE_ERROR
                             : (type == FFX_MESSAGE_TYPE_WARNING) ? SR_MESSAGE_TYPE_WARNING
                             : SR_MESSAGE_TYPE_INFO;
        // 窄字符 -> 宽字符
        size_t len = strlen(message);
        std::wstring wmsg(message, message + len);
        g_srMessageCallback(srType, wmsg.c_str());
    }

    // --- SRAPI callback implementations ---

    SR_API SRReturnCode srNssInitUpscaleContext(SRUpscaleContext *context) {
        const SRCreateUpscaleContextDesc *desc = &context->desc;
        auto *privateData = static_cast<SRNssPrivateData *>(context->userContext);

        // Check if VK_ARM_tensors/VK_ARM_data_graph extensions are available.
        // Without these extensions, ffxNssContextCreate will crash because
        // ARM-specific Vulkan function pointers (vkCreateTensorARM, etc.) will be null.
        if (desc->renderApiType == SR_RENDER_API_TYPE_VULKAN) {
            VkPhysicalDevice vkPhysicalDevice = (VkPhysicalDevice)(desc->renderDeviceInfo.vulkan.physicalDevice);
            VkInstance vkInstance = (VkInstance)(desc->renderDeviceInfo.vulkan.instance);
            SRGetFuncAddress instanceProcAddr = desc->renderDeviceInfo.vulkan.instanceProcAddr;

            PFN_vkEnumerateDeviceExtensionProperties pfnEnumerateExtensions = nullptr;
            if (instanceProcAddr && vkInstance) {
                pfnEnumerateExtensions = (PFN_vkEnumerateDeviceExtensionProperties)
                    instanceProcAddr(vkInstance, "vkEnumerateDeviceExtensionProperties");
            }
            if (!pfnEnumerateExtensions) {
                if (desc->messageCallback) {
                    desc->messageCallback(SR_MESSAGE_TYPE_ERROR,
                        L"Cannot query Vulkan device extensions. "
                        L"VK_ARM_tensors/VK_ARM_data_graph not available - NSS requires ML emulation layers.");
                }
                return (SRReturnCode)SR_RETURN_CODE_UNSUPPORTED;
            }

            uint32_t extensionCount = 0;
            pfnEnumerateExtensions(vkPhysicalDevice, nullptr, &extensionCount, nullptr);
            bool hasTensors = false;
            bool hasDataGraph = false;
            if (extensionCount > 0) {
                VkExtensionProperties *extensions = (VkExtensionProperties *)malloc(sizeof(VkExtensionProperties) * extensionCount);
                pfnEnumerateExtensions(vkPhysicalDevice, nullptr, &extensionCount, extensions);
                for (uint32_t i = 0; i < extensionCount; i++) {
                    if (strcmp(extensions[i].extensionName, "VK_ARM_tensors") == 0) hasTensors = true;
                    if (strcmp(extensions[i].extensionName, "VK_ARM_data_graph") == 0) hasDataGraph = true;
                }
                free(extensions);
            }
            if (!hasTensors || !hasDataGraph) {
                if (desc->messageCallback) {
                    desc->messageCallback(SR_MESSAGE_TYPE_ERROR,
                        L"VK_ARM_tensors or VK_ARM_data_graph extension not available. "
                        L"NSS requires ML emulation layers (VkLayer_Tensor + VkLayer_Graph) to be loaded.");
                }
                return (SRReturnCode)SR_RETURN_CODE_UNSUPPORTED;
            }
        }

        FfxNssContextDescription nssContextDesc = {};
        nssContextDesc.qualityMode = privateData->qualityMode;
        nssContextDesc.flags = privateData->contextFlags;
        nssContextDesc.renderSize = {desc->renderSize.x, desc->renderSize.y};
        nssContextDesc.upscaleSize = {desc->upscaledSize.x, desc->upscaledSize.y};
        nssContextDesc.displaySize = {desc->upscaledSize.x, desc->upscaledSize.y};
        nssContextDesc.backendInterface = *(privateData->ffxInterface);
        nssContextDesc.fpMessage = nssMessageBridge;

        // 诊断: 打印实际传入 SDK 的 contextFlags 十六进制值 (验证 MANAGE_HISTORY=0x40 是否设置)
        if (desc->messageCallback) {
            char buf[256];
            snprintf(buf, sizeof(buf),
                "[NSS-CFG] flags=0x%08X quality=%u render=%ux%u upscale=%ux%u",
                nssContextDesc.flags, (uint32_t)nssContextDesc.qualityMode,
                nssContextDesc.renderSize.width, nssContextDesc.renderSize.height,
                nssContextDesc.upscaleSize.width, nssContextDesc.upscaleSize.height);
            std::wstring wbuf(buf, buf + strlen(buf));
            desc->messageCallback(SR_MESSAGE_TYPE_WARNING, wbuf.c_str());
        }

        FfxErrorCode code = ffxNssContextCreate(privateData->context, &nssContextDesc);
        if (code != FFX_OK) {
            if (desc->messageCallback) {
                desc->messageCallback(SR_MESSAGE_TYPE_ERROR, L"NSS Context init failed");
                desc->messageCallback(SR_MESSAGE_TYPE_ERROR, std::to_wstring(code).c_str());
            }
            return (SRReturnCode)SR_RETURN_CODE_ERROR;
        }
        if (desc->messageCallback) {
            desc->messageCallback(SR_MESSAGE_TYPE_INFO, L"NSS Context init successful");
        }
        privateData->contextInitialized = true;
        return (SRReturnCode)SR_RETURN_CODE_OK;
    }

    SR_API SRReturnCode
    srNssCreateUpscaleContext(SRUpscaleContext *context, const SRCreateUpscaleContextDesc *desc) {
        if (desc->renderApiType != SR_RENDER_API_TYPE_VULKAN) {
            if (desc->messageCallback) {
                desc->messageCallback(SR_MESSAGE_TYPE_ERROR, L"NSS only supports Vulkan");
            }
            return SR_RETURN_CODE_UNSUPPORTED_RENDER_API;
        }

        // --- Read extra params ---
        uint32_t qualityModeValue = 1; // default: BALANCED
        bool quantized = true;
        bool manageHistory = true;

        const SRContextExtraParam *qualityParam = srFindParam(&desc->extraParams, "NSS_QUALITY_MODE");
        if (qualityParam && qualityParam->valueType == SR_PARAM_VALUE_TYPE_INT32) {
            qualityModeValue = static_cast<uint32_t>(qualityParam->value.int32Value);
        }

        const SRContextExtraParam *quantizedParam = srFindParam(&desc->extraParams, "NSS_QUANTIZED");
        if (quantizedParam && quantizedParam->valueType == SR_PARAM_VALUE_TYPE_BOOL) {
            quantized = quantizedParam->value.boolValue;
        }

        const SRContextExtraParam *manageHistoryParam = srFindParam(&desc->extraParams, "NSS_MANAGE_HISTORY");
        if (manageHistoryParam && manageHistoryParam->valueType == SR_PARAM_VALUE_TYPE_BOOL) {
            manageHistory = manageHistoryParam->value.boolValue;
        }

        // --- Setup FFX Vulkan backend ---
        // 注意: VkDeviceContext 含 5 个字段 (ffx_vk.h:101-108), vkInstance/vkGetInstanceProcAddr 必须填充,
        // 否则 SDK 的 VkFuncLoader 拿到 NULL 的 getInstanceProcAddrPtr, 调用时跳转空地址崩溃 (PC=0)。
        VkDeviceContext deviceContext = {
            (VkDevice)(desc->renderDeviceInfo.vulkan.device),
            (VkPhysicalDevice)(desc->renderDeviceInfo.vulkan.physicalDevice),
            (PFN_vkGetDeviceProcAddr)(desc->renderDeviceInfo.vulkan.deviceProcAddr),
            (VkInstance)(desc->renderDeviceInfo.vulkan.instance),
            (PFN_vkGetInstanceProcAddr)(desc->renderDeviceInfo.vulkan.instanceProcAddr),
        };
        // 注册 SDK 消息桥接: 让 SDK 内部 printMessage 转发到 SR 消息回调
        g_srMessageCallback = desc->messageCallback;
        // 关键: 直接走 SDK 层 API (ffxGetDeviceVK/ffxGetInterfaceVK) 绕过了 ffx-api 的 CreateBackend,
        // 而 VulkanWrapper 函数表只在那里初始化 (backends.cpp:51)。
        // 不初始化的话 CreateBackendContextVK 里 VulkanWrapper().vkEnumerateDeviceExtensionProperties
        // 是空指针 → 跳转空地址 → PC=0 崩溃。这里必须显式初始化。
        if (!InitVulkanWrapper(deviceContext)) {
            // 诊断: 列出 InitVulkanWrapper 必选加载失败的函数 (定位缺失的实例/设备扩展)
            if (desc->messageCallback) {
                const char *instanceFuncs[] = {
                    "vkGetPhysicalDeviceSurfaceSupportKHR",
                };
                const char *deviceFuncs[] = {
                    "vkCreateSwapchainKHR", "vkDestroySwapchainKHR", "vkGetSwapchainImagesKHR",
                    "vkAcquireNextImageKHR", "vkQueuePresentKHR",
                };
                std::string missing;
                for (const char *name : instanceFuncs) {
                    if (!desc->renderDeviceInfo.vulkan.instanceProcAddr ||
                        !desc->renderDeviceInfo.vulkan.instanceProcAddr(
                            desc->renderDeviceInfo.vulkan.instance, name)) {
                        missing += " instance:";
                        missing += name;
                    }
                }
                for (const char *name : deviceFuncs) {
                    if (!desc->renderDeviceInfo.vulkan.deviceProcAddr ||
                        !desc->renderDeviceInfo.vulkan.deviceProcAddr(
                            desc->renderDeviceInfo.vulkan.device, name)) {
                        missing += " device:";
                        missing += name;
                    }
                }
                std::wstring msg = L"InitVulkanWrapper failed (likely missing VK_KHR_surface instance ext / VK_KHR_swapchain device ext). Missing:";
                msg.append(missing.begin(), missing.end());
                desc->messageCallback(SR_MESSAGE_TYPE_ERROR, msg.c_str());
            }
            return (SRReturnCode)SR_RETURN_CODE_ERROR;
        }
        FfxDevice device = ffxGetDeviceVK(&deviceContext);
        size_t scratchBufferSize = ffxGetScratchMemorySizeVK(deviceContext, 1);
        void *scratchBuffer = malloc(scratchBufferSize);
        memset(scratchBuffer, 0, scratchBufferSize);
        FfxInterface *ffxInterface = new FfxInterface();
        if (FfxErrorCode _rc = ffxGetInterfaceVK(ffxInterface, device, scratchBuffer, scratchBufferSize, 1);
            _rc != FFX_OK) {
            free(scratchBuffer);
            delete ffxInterface;
            if (desc->messageCallback) {
                desc->messageCallback(SR_MESSAGE_TYPE_ERROR, L"Failed to get FFX VK interface for NSS");
            }
            return (SRReturnCode)SR_RETURN_CODE_ERROR;
        }
        // 设置 backend 消息回调: backend 内部 (CreatePipeline/CreateResource 等) 的错误
        // 通过 fpSetMessageCallback → fpMessage 转发, 否则 dispatch 失败原因完全不可见。
        if (ffxInterface->fpSetMessageCallback) {
            ffxInterface->fpSetMessageCallback(ffxInterface, (FfxBackendMessage)nssMessageBridge);
        }

        // --- Build context flags ---
        // 使用 SDK 官方枚举 (FfxNssInitializationFlagBits), 与 ffx_nss.h 完全一致
        uint32_t contextFlags = 0;
        if (quantized) contextFlags |= FFX_NSS_CONTEXT_FLAG_QUANTIZED;
        if (manageHistory) contextFlags |= FFX_NSS_CONTEXT_FLAG_MANAGE_HISTORY;
        if (desc->flags & SR_UPSCALE_CONTEXT_CREATE_FLAG_ENABLE_DEPTH_INVERTED) {
            contextFlags |= FFX_NSS_CONTEXT_FLAG_DEPTH_INVERTED;
        }
        // NSS always uses HDR input
        contextFlags |= FFX_NSS_CONTEXT_FLAG_HIGH_DYNAMIC_RANGE;
        contextFlags |= FFX_NSS_CONTEXT_FLAG_ALLOW_16BIT;

        // --- Create private data ---
        FfxNssContext *nssContext = new FfxNssContext();

        SRNssPrivateData *privateData = new SRNssPrivateData();
        privateData->context = nssContext;
        privateData->ffxInterface = ffxInterface;
        privateData->scratchBuffer = scratchBuffer;
        privateData->qualityMode = static_cast<FfxNssShaderQualityMode>(qualityModeValue);
        privateData->contextFlags = contextFlags;
        privateData->messageCallback = desc->messageCallback;

        context->desc = *const_cast<SRCreateUpscaleContextDesc *>(desc);
        context->userContext = privateData;
        return (SRReturnCode)SR_RETURN_CODE_OK;
    }

    SR_API SRReturnCode srNssDestroyUpscaleContext(SRUpscaleContext *context) {
        if (!context || !context->userContext) {
            return SR_RETURN_CODE_NULL_POINTER;
        }

        SRNssPrivateData *privateData = (SRNssPrivateData *)context->userContext;

        if (privateData->contextInitialized && privateData->context) {
            FfxErrorCode errorCode = ffxNssContextDestroy(privateData->context);
            if (errorCode != FFX_OK) {
                if (context->desc.messageCallback) {
                    context->desc.messageCallback(SR_MESSAGE_TYPE_ERROR, L"NSS Context destroy failed");
                    context->desc.messageCallback(SR_MESSAGE_TYPE_ERROR, std::to_wstring(errorCode).c_str());
                }
            }
        }

        if (privateData->scratchBuffer) {
            free(privateData->scratchBuffer);
            privateData->scratchBuffer = nullptr;
        }

        delete privateData->context;
        privateData->context = nullptr;

        delete privateData->ffxInterface;
        privateData->ffxInterface = nullptr;

        delete privateData;
        context->userContext = nullptr;

        return (SRReturnCode)SR_RETURN_CODE_OK;
    }

    SR_API SRReturnCode srNssQueryUpscale(SRUpscaleContext *context, SRUpscaleContextQueryResult *result,
                                          SRUpscaleContextQueryType queryType) {
        switch (queryType) {
            case SR_UPSCALE_CONTEXT_QUERY_VERSION_INFO: {
                static SRQueryVersionResult outResult = {};
                outResult.versionId = SR_MAKE_VERSION(1, 0, 1);
                outResult.versionNumber = SR_MAKE_VERSION(1, 0, 1);
                result->data = &outResult;
                break;
            }
            case SR_UPSCALE_CONTEXT_QUERY_GPU_MEMORY_INFO: {
                static SRQueryGpuMemoryResult outResult = {};
                outResult.gpuMemory = 0;
                result->data = &outResult;
                break;
            }
            case SR_UPSCALE_CONTEXT_QUERY_AVAILABLE: {
                static SRQueryAvailabilityResult outResult = {};
                outResult.isAvailable = true;
                result->data = &outResult;
                break;
            }
            default:
                break;
        }
        return (SRReturnCode)SR_RETURN_CODE_OK;
    }

    SR_API SRReturnCode srNssDispatchUpscale(SRUpscaleContext *context, const SRDispatchUpscaleDesc *desc) {
        FfxNssContext *nssContext = ((SRNssPrivateData *)context->userContext)->context;

        FfxNssDispatchDescription dispatchDesc = {};
        dispatchDesc.commandList = ffxGetCommandListVK(desc->commandList.apiCommandBuffer.vulkan.commandBuffer);

        if (desc->color.exist)
            dispatchDesc.color = srTextureResourceToFfxResource(&desc->color);
        if (desc->depth.exist)
            dispatchDesc.depth = srTextureResourceToFfxResource(&desc->depth);
        if (desc->motionVectors.exist)
            dispatchDesc.motionVectors = srTextureResourceToFfxResource(&desc->motionVectors);
        if (desc->output.exist)
            dispatchDesc.output = srTextureResourceToFfxOutputResource(&desc->output);
        // 诊断(二分定位): outputTm1 用 output 的资源填充。若 MANAGE_HISTORY 生效, 它被忽略; 若不生效,
        // 这能通过 nssDispatch 1495 行的 outputTm1 非空检查, 区分 "history 标志问题" 与 "更深层问题"。
        if (desc->output.exist)
            dispatchDesc.outputTm1 = srTextureResourceToFfxResource(&desc->output);

        dispatchDesc.jitterOffset = {desc->jitterOffset.x, desc->jitterOffset.y};
        dispatchDesc.renderSize = {desc->renderSize.x, desc->renderSize.y};
        dispatchDesc.upscaleSize = {desc->upscaleSize.x, desc->upscaleSize.y};
        dispatchDesc.cameraNear = desc->cameraNear;
        dispatchDesc.cameraFar = desc->cameraFar;
        dispatchDesc.cameraFovAngleVertical = desc->cameraFovAngleVertical;
        dispatchDesc.exposure = desc->preExposure;
        dispatchDesc.motionVectorScale = {desc->motionVectorScale.x, desc->motionVectorScale.y};
        dispatchDesc.frameTimeDelta = desc->frameTimeDelta;
        dispatchDesc.reset = desc->reset;
        dispatchDesc.flags = 0;
        dispatchDesc.debugViewMode = 0;

        // 诊断: 输出各资源指针与尺寸, 定位 FFX_ERROR_INVALID_POINTER 的源头
        auto *pdiag = (SRNssPrivateData *)context->userContext;
        if (pdiag->messageCallback) {
            char buf[512];
            snprintf(buf, sizeof(buf),
                "[NSS-DIAG] exist: color=%d depth=%d mv=%d output=%d | handle: color=%p depth=%p mv=%p output=%p | size: render=%ux%u upscale=%ux%u",
                desc->color.exist, desc->depth.exist, desc->motionVectors.exist, desc->output.exist,
                desc->color.exist ? desc->color.handle : nullptr,
                desc->depth.exist ? desc->depth.handle : nullptr,
                desc->motionVectors.exist ? desc->motionVectors.handle : nullptr,
                desc->output.exist ? desc->output.handle : nullptr,
                desc->renderSize.x, desc->renderSize.y,
                desc->upscaleSize.x, desc->upscaleSize.y);
            std::wstring wbuf(buf, buf + strlen(buf));
            pdiag->messageCallback(SR_MESSAGE_TYPE_WARNING, wbuf.c_str());
        }

        FfxErrorCode errorCode = ffxNssContextDispatch(nssContext, &dispatchDesc);
        if (errorCode != FFX_OK) {
            auto *privateData = (SRNssPrivateData *)context->userContext;
            if (privateData->messageCallback) {
                privateData->messageCallback(SR_MESSAGE_TYPE_ERROR, L"NSS dispatch failed");
                privateData->messageCallback(SR_MESSAGE_TYPE_ERROR, std::to_wstring(errorCode).c_str());
            }
            return (SRReturnCode)SR_RETURN_CODE_ERROR;
        }
        return (SRReturnCode)SR_RETURN_CODE_OK;
    }

    SR_API SRReturnCode srNssShutdown() {
        return (SRReturnCode)SR_RETURN_CODE_OK;
    }

    SR_API SRUpscaleContextCallbacks srGetNSSUpscaleCallbacks() {
        static SRUpscaleContextCallbacks callbacks = {
            .pCreate = (SRCreateFunc)srNssCreateUpscaleContext,
            .pInit = (SRInitFunc)srNssInitUpscaleContext,
            .pDestroy = (SRDestroyFunc)srNssDestroyUpscaleContext,
            .pQuery = (SRQueryFunc)srNssQueryUpscale,
            .pDispatchUpscale = (SRDispatchUpscaleFunc)srNssDispatchUpscale,
            .pShutdown = (SRShutdownFunc)srNssShutdown,
        };
        return callbacks;
    }

    // Stub for missing frame generation symbol (FI disabled in NSS build)
    FfxErrorCode ffxSetFrameGenerationConfigToSwapchainVK(const FfxFrameGenerationConfig*) { return FFX_OK; }

    #ifdef __cplusplus
}
#endif
