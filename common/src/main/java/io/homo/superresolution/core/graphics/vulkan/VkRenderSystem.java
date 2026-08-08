/*
 * Super Resolution
 * Copyright (c) 2025-2026. 187J3X1-114514
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package io.homo.superresolution.core.graphics.vulkan;

import io.homo.superresolution.api.platform.Platform;
import io.homo.superresolution.common.config.SuperResolutionConfig;
import io.homo.superresolution.common.presentation.vulkan.VulkanPresentationFeature;
import io.homo.superresolution.core.graphics.GraphicsDevice;
import io.homo.superresolution.core.graphics.system.IRenderSystem;
import io.homo.superresolution.core.streamline.Streamline;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import static io.homo.superresolution.core.graphics.vulkan.VulkanUtils.VK_CHECK;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memUTF8;
import static org.lwjgl.vulkan.EXTMutableDescriptorType.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MUTABLE_DESCRIPTOR_TYPE_FEATURES_EXT;
import static org.lwjgl.vulkan.EXTPrivateData.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRIVATE_DATA_FEATURES_EXT;
import static org.lwjgl.vulkan.KHRDynamicRendering.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DYNAMIC_RENDERING_FEATURES_KHR;
import static org.lwjgl.vulkan.KHRDynamicRenderingLocalRead.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DYNAMIC_RENDERING_LOCAL_READ_FEATURES_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_KHR_SURFACE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME;

import static org.lwjgl.vulkan.KHRShaderIntegerDotProduct.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_INTEGER_DOT_PRODUCT_FEATURES_KHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2;
import static org.lwjgl.vulkan.VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES;
import static org.lwjgl.vulkan.VK13.VK_API_VERSION_1_3;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES;

public class VkRenderSystem implements IRenderSystem {
    public static final Logger LOGGER = LoggerFactory.getLogger("SuperResolution/Vulkan");
    public static final boolean ENABLE_VALIDATION = VulkanValidationLayers.checkValidationLayerSupport() &&(
             Platform.currentPlatform.isDevelopmentEnvironment() ||
                     SuperResolutionConfig.isEnableDebug()
    );
    private static final int DEFAULT_API_VERSION = VK_API_VERSION_1_3;

    private final List<String> instanceExtensions = new ArrayList<>();
    private final List<String> deviceExtensions = new ArrayList<>();
    protected VulkanValidationLayers validationLayers;
    protected VkInstance instance;
    protected VulkanCapabilities capabilities = new VulkanCapabilities();
    private VulkanDevice vulkanDevice;
    private boolean borrowed;

    public VkRenderSystem() {
    }

    public static VkRenderSystem borrowed(VkInstance instance, VkPhysicalDevice physicalDevice, VkDevice device, int graphicsQueueFamilyIndex) {
        VkRenderSystem renderSystem = new VkRenderSystem();
        renderSystem.borrowed = true;
        renderSystem.instance = instance;
        renderSystem.capabilities.init(instance, physicalDevice);
        renderSystem.vulkanDevice = new VulkanDevice(instance, physicalDevice, device, graphicsQueueFamilyIndex, false);
        LOGGER.info("Vulkan borrowed 初始化完成");
        return renderSystem;
    }

    private static PointerBuffer asPointerBuffer(MemoryStack stack, List<String> list) {
        PointerBuffer buffer = stack.mallocPointer(list.size());
        list.forEach(e -> buffer.put(stack.UTF8(e)));
        return buffer.rewind();
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    private static String uuidListToHex(List<byte[]> uuids) {
        List<String> uuidStrings = new ArrayList<>(uuids.size());
        for (byte[] uuid : uuids) {
            uuidStrings.add(bytesToHex(uuid));
        }
        return String.join(", ", uuidStrings);
    }

    public VkInstance getVulkanInstance() {
        return instance;
    }

    public VkRenderSystem addInstanceExtension(String ext) {
        if (!instanceExtensions.contains(ext)) {
            instanceExtensions.add(ext);
        }
        return this;
    }

    public VkRenderSystem addDeviceExtension(String ext) {
        if (!deviceExtensions.contains(ext)) {
            deviceExtensions.add(ext);
        }
        return this;
    }

    public VulkanCapabilities getCapabilities() {
        return capabilities;
    }

    @Override
    public void initRenderSystem() {
        VulkanPresentationFeature.prepare(this);
        setupNssVulkanLayer();
        // Debug: enumerate available instance layers BEFORE creating VkInstance
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            int[] layerCount = new int[1];
            int rc = org.lwjgl.vulkan.VK10.vkEnumerateInstanceLayerProperties(layerCount, null);
            if (rc == org.lwjgl.vulkan.VK10.VK_SUCCESS && layerCount[0] > 0) {
                org.lwjgl.vulkan.VkLayerProperties.Buffer layers =
                        org.lwjgl.vulkan.VkLayerProperties.calloc(layerCount[0], stack);
                org.lwjgl.vulkan.VK10.vkEnumerateInstanceLayerProperties(layerCount, layers);
                LOGGER.info("=== Vulkan 可用实例层 (共 {} 个) ===", layerCount[0]);
                for (int i = 0; i < layerCount[0]; i++) {
                    LOGGER.info("  层 [{}]: {}", i, layers.get(i).layerNameString());
                }
            } else {
                LOGGER.warn("无法枚举实例层 (rc={}, count={})", rc, layerCount[0]);
            }
        } catch (Exception e) {
            LOGGER.warn("枚举实例层失败: {}", e.getMessage());
        }
        createInstance();
        VulkanPresentationFeature.createSurface(this);
        validationLayers = new VulkanValidationLayers(instance);
        if (ENABLE_VALIDATION) {
            validationLayers.setupDebugMessenger();
        }
        VkPhysicalDevice physicalDevice = selectPhysicalDevice();
        capabilities.init(instance, physicalDevice);
        VulkanPresentationFeature.validateDevice(this, physicalDevice);
        this.vulkanDevice = createLogicalDeviceWithCapabilities(physicalDevice);
        VulkanPresentationFeature.completeInitialization(this);
        LOGGER.info("Vulkan 初始化完成");
    }

    /**
     * 设置 Arm NSS Vulkan 层路径。
     *
     * VkLayer_arm_NG 是一个隐式 Vulkan 层，提供 VK_ARM_tensors 和 VK_ARM_data_graph 扩展。
     * 这些扩展在 x86 GPU (NVIDIA/AMD/Intel) 上不存在，需要通过此层进行模拟。
     *
     * Windows 上 Vulkan loader 通过以下方式发现隐式层：
     * 1. 注册表 HKCU\SOFTWARE\Khronos\Vulkan\ImplicitLayers
     * 2. VK_LAYER_PATH 环境变量
     *
     * 此方法同时使用两种方式注册，确保层能被发现。
     */
    private boolean nssLayerAvailable = false;

    private void setupNssVulkanLayer() {
        try {
            Path nssLayerDir = io.homo.superresolution.core.SuperResolutionConstants
                    .NATIVE_LIBRARIES_DIR.getPath().toAbsolutePath();

            // 3 layers: Tensor (emulation), Graph (emulation), arm_NG (stripper)
            // JSON 均取自部署目录 (资源文件), library_path 统一由 rewriteLibraryPath 重写为绝对路径。
            String[][] layers = {
                {"VkLayer_Tensor.json", "VkLayer_Tensor.dll"},
                {"VkLayer_Graph.json", "VkLayer_Graph.dll"},
                {"VkLayer_arm_NG.json", "VkLayer_arm_NG.dll"},
            };

            // 不再写注册表 (隐式加载路径: JSON 无 disable_environment, 注册表条目被 loader 拒绝且污染全局)。
            // 只保留 VK_ADD_LAYER_PATH + VK_INSTANCE_LAYERS + ppEnabledLayerNames 显式加载。
            // 顺带清理历史版本写入的残留。
            cleanLayerRegistryEntries(nssLayerDir);

            boolean allFound = true;
            for (String[] layer : layers) {
                Path json = nssLayerDir.resolve(layer[0]);
                Path dll = nssLayerDir.resolve(layer[1]);
                if (!java.nio.file.Files.exists(json) || !java.nio.file.Files.exists(dll)) {
                    LOGGER.warn("{} 或 {} 未找到", layer[0], layer[1]);
                    allFound = false;
                    continue;
                }
                // Rewrite JSON with absolute DLL path
                // 注意: JSON 文件内 "\\" 是两个字面反斜杠 (JSON 转义), 旧正则只匹配单个, 从未命中。
                // 统一用基于 indexOf 的字面替换, 对任何现有值 (相对/损坏/绝对) 幂等自愈。
                String content = java.nio.file.Files.readString(json);
                String rewritten = rewriteLibraryPath(content, dll);
                if (!rewritten.equals(content)) {
                    java.nio.file.Files.writeString(json, rewritten);
                    LOGGER.info("已重写 {} 的 library_path 为绝对路径", layer[0]);
                } else {
                    LOGGER.info("{} 的 library_path 已是绝对路径", layer[0]);
                }
            }

            setNativeEnvVar("VK_ADD_LAYER_PATH", nssLayerDir.toString());
            setNativeEnvVar("VK_INSTANCE_LAYERS",
                    "VK_LAYER_ARM_NG;VK_LAYER_ML_Graph_Emulation;VK_LAYER_ML_Tensor_Emulation");
            setNativeEnvVar("VK_LOADER_DEBUG", "all");
            // Redirect C-level stderr to capture Vulkan loader debug output
            try {
                com.sun.jna.NativeLibrary lib = com.sun.jna.NativeLibrary.getInstance("msvcrt");
                com.sun.jna.Function freopen = lib.getFunction("freopen");
                com.sun.jna.Function fopen = lib.getFunction("fopen");
                long fp = ((java.lang.Long) fopen.invokeLong(new Object[]{"E:\\.opus\\vulkan_loader.log", "w"})).longValue();
                if (fp != 0) {
                    freopen.invokeInt(new Object[]{null, "w", com.sun.jna.Pointer.createConstant(fp)});
                    LOGGER.info("C-level stderr redirected to E:\\.opus\\vulkan_loader.log");
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to redirect stderr: {}", e.getMessage());
            }

            if (allFound) {
                nssLayerAvailable = true;
                LOGGER.info("NSS Vulkan 层已就绪 (arm_NG + Tensor + Graph)，将在创建 VkInstance 时显式启用");
                // NSS SDK 的 InitVulkanWrapper 必选加载 vkGetPhysicalDeviceSurfaceSupportKHR (VK_KHR_surface,
                // 实例扩展) 和 swapchain 系列 (VK_KHR_swapchain, 设备扩展)。OpenGL 模式下默认未启用,
                // 不启用则函数指针为 NULL, InitVulkanWrapper 返回 false, NSS 上下文创建失败。
                addInstanceExtension(VK_KHR_SURFACE_EXTENSION_NAME);
                addDeviceExtension(VK_KHR_SWAPCHAIN_EXTENSION_NAME);
                LOGGER.info("已添加 VK_KHR_surface (instance) + VK_KHR_swapchain (device) 以满足 NSS SDK 函数指针加载");
            }
        } catch (Exception e) {
            LOGGER.warn("设置 NSS Vulkan 层路径失败: {}", e.getMessage());
        }
    }

    /**
     * 清理历史版本写入注册表的层条目 (三个层 JSON 均无 disable_environment, 隐式加载路径本就无效)。
     */
    private static void cleanLayerRegistryEntries(Path nssLayerDir) {
        String[] jsons = {"VkLayer_arm_NG.json", "VkLayer_Tensor.json", "VkLayer_Graph.json"};
        for (String json : jsons) {
            String jsonPath = nssLayerDir.resolve(json).toString().replace("/", "\\");
            runRegDelete("HKEY_CURRENT_USER\\SOFTWARE\\Khronos\\Vulkan\\ImplicitLayers", jsonPath);
            runRegDelete("HKEY_CURRENT_USER\\SOFTWARE\\Khronos\\Vulkan\\ExplicitLayers", jsonPath);
        }
    }

    private static void runRegDelete(String regKey, String valueName) {
        try {
            ProcessBuilder pb = new ProcessBuilder("reg", "delete", regKey, "/v", valueName, "/f");
            pb.redirectErrorStream(true);
            pb.start().waitFor(); // 0=已删除, 1=不存在, 均视为成功
            LOGGER.info("注册表清理: {}\\{}", regKey, valueName);
        } catch (Exception e) {
            LOGGER.warn("注册表清理失败: {}", e.getMessage());
        }
    }

    /**
     * 将 JSON 中 "library_path" 字段的值替换为绝对路径 (JSON 转义形式, \ -> \\)。
     * 基于 indexOf 定位而非正则, 对任何现有值 (相对/损坏/绝对) 都稳健。
     */
    private static String rewriteLibraryPath(String json, Path dllPath) {
        String escaped = dllPath.toString().replace("\\", "\\\\");
        String marker = "\"library_path\"";
        int markerIdx = json.indexOf(marker);
        if (markerIdx < 0) return json;
        int valueStart = json.indexOf('"', markerIdx + marker.length());
        int valueEnd = valueStart >= 0 ? json.indexOf('"', valueStart + 1) : -1;
        if (valueStart < 0 || valueEnd <= valueStart) return json;
        return json.substring(0, valueStart + 1) + escaped + json.substring(valueEnd);
    }

    /**
     * 通过反射修改进程级环境变量 (C-level getenv 可见)
     */
    @SuppressWarnings("unchecked")
    private static void setNativeEnvVar(String key, String value) {
        // Method 1: JNA - call SetEnvironmentVariableW directly (most reliable on Java 25+)
        try {
            com.sun.jna.NativeLibrary lib = com.sun.jna.NativeLibrary.getInstance("kernel32");
            com.sun.jna.Function func = lib.getFunction("SetEnvironmentVariableW");
            int result = func.invokeInt(new Object[]{key, value});
            if (result != 0) {
                LOGGER.info("SetEnvironmentVariableW OK: {}={}", key, value);
                return;
            } else {
                LOGGER.warn("SetEnvironmentVariableW returned 0 for {}", key);
            }
        } catch (Exception jnaEx) {
            LOGGER.warn("JNA SetEnvironmentVariableW failed: {}", jnaEx.getMessage());
        }
        // Method 2: Reflection (fallback, may fail on Java 25)
        try {
            Class<?> processEnvironmentClass = Class.forName("java.lang.ProcessEnvironment");
            try {
                Field field = processEnvironmentClass.getDeclaredField("theEnvironment");
                field.setAccessible(true);
                Map<String, String> env = (Map<String, String>) field.get(null);
                env.put(key, value);
                LOGGER.info("Reflection setNativeEnvVar OK for {}", key);
            } catch (NoSuchFieldException e) {
                try {
                    Field field = processEnvironmentClass.getDeclaredField("theCaseInsensitiveEnvironment");
                    field.setAccessible(true);
                    Map<String, String> env = (Map<String, String>) field.get(null);
                    env.put(key, value);
                    LOGGER.info("Reflection setNativeEnvVar (CI) OK for {}", key);
                } catch (NoSuchFieldException e2) {
                    LOGGER.warn("无法设置 C-level 环境变量 {}", key);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Reflection env var failed: {}", e.getMessage());
        }
    }

    @Override
    public void destroyRenderSystem() {
        if (vulkanDevice != null) {
            vulkanDevice.destroy();
            if (vulkanDevice.ownsVkDevice()) {
                vkDestroyDevice(vulkanDevice.getVkDevice(), null);
            }
            vulkanDevice = null;
        }
        if (validationLayers != null) {
            validationLayers.destroy();
            validationLayers = null;
        }
        if (instance != null) {
            if (!borrowed) {
                vkDestroyInstance(instance, null);
            }
            instance = null;
        }
        if (capabilities != null) {
            capabilities.destroy();
            capabilities = null;
        }
        LOGGER.info("Vulkan 已销毁");
    }

    @Override
    public VulkanDevice device() {
        return vulkanDevice;
    }

    @Override
    public void finish() {
        vkDeviceWaitIdle(vulkanDevice.getVkDevice());
    }

    private void createInstance() {
        try (MemoryStack stack = stackPush()) {
            VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                    .apiVersion(DEFAULT_API_VERSION)
                    .pEngineName(memUTF8("Engine"))
                    .engineVersion(VK_MAKE_VERSION(0, 1, 0))
                    .pApplicationName(memUTF8("App"))
                    .applicationVersion(VK_MAKE_VERSION(1, 0, 0));

            VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                    .pApplicationInfo(appInfo)
                    .ppEnabledExtensionNames(asPointerBuffer(stack, instanceExtensions));

            // Build enabled layers list: validation + NSS
            java.util.List<String> enabledLayers = new java.util.ArrayList<>();
            if (ENABLE_VALIDATION) {
                enabledLayers.addAll(VulkanValidationLayers.REQUIRED_LAYERS);
                VkDebugUtilsMessengerCreateInfoEXT debugCreateInfo = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack);
                VulkanValidationLayers.populateDebugMessengerCreateInfo(debugCreateInfo);
                createInfo.pNext(debugCreateInfo.address());
            }
            // NSS layers are enabled via ppEnabledLayerNames.
            // Order per NSS SDK docs: arm_NG first (outermost), then Graph, then Tensor.
            if (nssLayerAvailable) {
                enabledLayers.add("VK_LAYER_ARM_NG");
                enabledLayers.add("VK_LAYER_ML_Graph_Emulation");
                enabledLayers.add("VK_LAYER_ML_Tensor_Emulation");
                LOGGER.info("显式启用 arm_NG + Graph + Tensor (arm_NG 在最外层)");
            }
            if (!enabledLayers.isEmpty()) {
                createInfo.ppEnabledLayerNames(asPointerBuffer(stack, enabledLayers));
            }

            PointerBuffer instancePtr = stack.mallocPointer(1);
            LOGGER.info("Streamline.isInitialized()={}, nssLayerAvailable={}", Streamline.isInitialized(), nssLayerAvailable);
            LOGGER.info("ppEnabledLayerNames count={}, layers={}", enabledLayers.size(), String.join(", ", enabledLayers));
            if (Streamline.isInitialized() && !nssLayerAvailable){
                LOGGER.info("使用 Streamline slCreateVkInstance 创建 VkInstance");
                long instance = Streamline.createVkInstance(createInfo.address());
                VK_CHECK(Streamline.getLastVkResult(), "Failed to create VkInstance");
                instancePtr.put(0, instance);
            }else {
                LOGGER.info("使用标准 vkCreateInstance 创建 VkInstance (隐式层将被加载)");
                VK_CHECK(vkCreateInstance(createInfo, null, instancePtr), "Failed to create VkInstance");
            }
            instance = VkReflectionHelper.createVkInstanceSafely(instancePtr.get(0), createInfo);
        }
    }

    private VkPhysicalDevice selectPhysicalDevice() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer deviceCount = stack.ints(0);
            VK_CHECK(vkEnumeratePhysicalDevices(instance, deviceCount, null));
            if (deviceCount.get(0) == 0) {
                throw new RuntimeException("No Vulkan-compatible GPU found");
            }
            PointerBuffer devices = stack.mallocPointer(deviceCount.get(0));
            VK_CHECK(vkEnumeratePhysicalDevices(instance, deviceCount, devices));
            List<GraphicsDevice> graphicsDevices = new ArrayList<>();
            GraphicsDevice openglDevice = GraphicsDevice.createFromOpenGL();
            LOGGER.info("OpenGL 设备: {} (Device UUIDs: {}, Driver UUID: {})",
                    openglDevice.deviceName(),
                    uuidListToHex(openglDevice.deviceUUIDs()),
                    bytesToHex(openglDevice.driverUUID())
            );
            for (int i = 0; i < deviceCount.get(0); i++) {
                VkPhysicalDevice physicalDevice = new VkPhysicalDevice(devices.get(i), instance);
                graphicsDevices.add(GraphicsDevice.createFromVulkan(physicalDevice));
            }
            LOGGER.info("检测到 {} 个 Vulkan 物理设备:", graphicsDevices.size());
            for (int i = 0; i < deviceCount.get(0); i++) {
                GraphicsDevice device = graphicsDevices.get(i);
                LOGGER.info("[{}] {} (Device UUIDs: {}, Driver UUID: {})",
                        i,
                        device.deviceName(),
                        uuidListToHex(device.deviceUUIDs()),
                        bytesToHex(device.driverUUID())
                );
            }

            for (int i = 0; i < deviceCount.get(0); i++) {
                if (graphicsDevices.get(i).isCompatibleWith(openglDevice)) {
                    return new VkPhysicalDevice(devices.get(i), instance);
                }
            }
            // UUID 匹配失败: 优先选 apiVersion >= 1.3 的设备
            // (VK_ARM_data_graph 与 arm_NG 层 (NGLayer.cpp:479) 的硬门槛, 1.2 设备上 arm_NG 会跳过注入)
            for (int i = 0; i < deviceCount.get(0); i++) {
                VkPhysicalDevice p = new VkPhysicalDevice(devices.get(i), instance);
                VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
                vkGetPhysicalDeviceProperties(p, props);
                int version = props.apiVersion();
                LOGGER.info("[{}] {} apiVersion=0x{:08X} ({}.{}.{})",
                        i, graphicsDevices.get(i).deviceName(),
                        version, VK_VERSION_MAJOR(version), VK_VERSION_MINOR(version), VK_VERSION_PATCH(version));
                if (version >= VK_API_VERSION_1_3) {
                    return p;
                }
            }
            LOGGER.error("未找到与 OpenGL 设备匹配且 apiVersion >= 1.3 的 Vulkan 物理设备，默认选择第一个设备");
            return new VkPhysicalDevice(devices.get(0), instance);
        }
    }

    private VulkanDevice createLogicalDeviceWithCapabilities(VkPhysicalDevice physicalDevice) {
        try (MemoryStack stack = stackPush()) {
            int graphicsFamilyIndex = VulkanPresentationFeature.findGraphicsPresentQueueFamily(
                    stack,
                    VK_QUEUE_GRAPHICS_BIT,
                    physicalDevice
            );
            if (graphicsFamilyIndex == -1) {
                throw new RuntimeException("No suitable queue family found");
            }

            VkDeviceQueueCreateInfo.Buffer queueCreateInfos = VkDeviceQueueCreateInfo.calloc(1, stack);
            queueCreateInfos.get(0)
                    .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                    .queueFamilyIndex(graphicsFamilyIndex)
                    .pQueuePriorities(stack.floats(1.0f));

            List<String> enableDeviceExts = new ArrayList<>();
            List<String> supportedDeviceExts = capabilities.getDeviceExtensions();
            for (String ext : deviceExtensions) {
                if (supportedDeviceExts.contains(ext)) {
                    enableDeviceExts.add(ext);
                    LOGGER.info("启用设备扩展: {}", ext);
                } else {
                    LOGGER.warn("扩展 {} 不被当前物理设备支持，已跳过", ext);
                }
            }

            // Force-add ARM extensions per NSS SDK Option B documentation.
            // 三重门控: 层就绪 / 设备 apiVersion >= 1.3 (arm_NG 硬门槛 NGLayer.cpp:479) / 模拟层确实枚举出 ARM 扩展名
            if (nssLayerAvailable) {
                int deviceApiVersion = capabilities.getDeviceProperties().apiVersion();
                if (deviceApiVersion < VK_API_VERSION_1_3) {
                    LOGGER.warn("NSS: 当前设备 apiVersion=0x{:08X} < 1.3, 跳过 ARM 扩展请求 (VK_ARM_data_graph 依赖 1.3)",
                            deviceApiVersion);
                } else if (!supportedDeviceExts.contains("VK_ARM_tensors") || !supportedDeviceExts.contains("VK_ARM_data_graph")) {
                    LOGGER.error("NSS: 模拟层未生效——未枚举到 VK_ARM_tensors/VK_ARM_data_graph, 跳过 ARM 扩展请求 (NSS 将不可用)");
                } else {
                    enableDeviceExts.add("VK_ARM_tensors");
                    enableDeviceExts.add("VK_ARM_data_graph");
                    LOGGER.info("已添加 ARM 扩展到设备扩展请求列表 (层已生效, 设备 apiVersion>=1.3)");
                }
            }

            VkPhysicalDeviceMutableDescriptorTypeFeaturesEXT mutableDescriptorTypeFeaturesEXT =
                    VkPhysicalDeviceMutableDescriptorTypeFeaturesEXT.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MUTABLE_DESCRIPTOR_TYPE_FEATURES_EXT);
            VkPhysicalDevicePrivateDataFeaturesEXT privateDataFeatures =
                    VkPhysicalDevicePrivateDataFeaturesEXT.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRIVATE_DATA_FEATURES_EXT);
            VkPhysicalDeviceShaderIntegerDotProductFeaturesKHR shaderIntegerDotProductFeaturesKHR =
                    VkPhysicalDeviceShaderIntegerDotProductFeaturesKHR.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_INTEGER_DOT_PRODUCT_FEATURES_KHR)
                            .pNext(privateDataFeatures.address());
            mutableDescriptorTypeFeaturesEXT.pNext(shaderIntegerDotProductFeaturesKHR.address());

            VkPhysicalDeviceDynamicRenderingFeaturesKHR dynamicRenderingFeatures =
                    VkPhysicalDeviceDynamicRenderingFeaturesKHR.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DYNAMIC_RENDERING_FEATURES_KHR)
                            .pNext(mutableDescriptorTypeFeaturesEXT.address());

            VkPhysicalDeviceDynamicRenderingLocalReadFeaturesKHR dynamicRenderingLocalReadFeatures =
                    VkPhysicalDeviceDynamicRenderingLocalReadFeaturesKHR.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DYNAMIC_RENDERING_LOCAL_READ_FEATURES_KHR)
                            .pNext(dynamicRenderingFeatures.address());

            // VkPhysicalDeviceVulkan13Features: 仅设备 >= 1.3 时加入 (该结构 VUID 要求 apiVersion >= 1.3)。
            // 同步启用 synchronization2, 与 Arm 官方 NSS 示例 (nss.cpp:118) 一致。
            // 只加 13Features 一个 sync2 载体, 避免 VUID-06532 (Synchronization2Features 与 Vulkan13Features 不得同链)。
            int deviceApiVersion = capabilities.getDeviceProperties().apiVersion();
            VkPhysicalDeviceVulkan13Features features13 = null;
            if (deviceApiVersion >= VK_API_VERSION_1_3) {
                features13 = VkPhysicalDeviceVulkan13Features.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES)
                        .pNext(dynamicRenderingLocalReadFeatures.address());
            }

            VkPhysicalDeviceVulkan12Features features12 = VkPhysicalDeviceVulkan12Features.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES)
                    .pNext(features13 != null ? features13.address() : dynamicRenderingLocalReadFeatures.address());

            VkPhysicalDeviceFeatures2 features2 = VkPhysicalDeviceFeatures2.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2)
                    .pNext(features12.address());

            vkGetPhysicalDeviceFeatures2(physicalDevice, features2);

            boolean deviceSupportsMutableDescriptor = mutableDescriptorTypeFeaturesEXT.mutableDescriptorType();
            boolean deviceSupportsShaderInt8 = features12.shaderInt8();
            boolean deviceSupportsShaderInt16 = features2.features().shaderInt16();
            boolean deviceSupportsShaderFloat16 = features12.shaderFloat16();
            boolean deviceSupportsShaderIntegerDotProduct = shaderIntegerDotProductFeaturesKHR.shaderIntegerDotProduct();
            boolean deviceSupportsShaderStorageImageWriteWithoutFormat = features2.features().shaderStorageImageWriteWithoutFormat();
            boolean deviceSupportsBufferDeviceAddress = features12.bufferDeviceAddress();
            boolean deviceSupportsDescriptorIndexing = features12.descriptorIndexing();
            boolean deviceSupportsDynamicRendering = dynamicRenderingFeatures.dynamicRendering();
            boolean deviceSupportsDynamicRenderingLocalRead = dynamicRenderingLocalReadFeatures.dynamicRenderingLocalRead();
            boolean deviceSupportsPrivateData = privateDataFeatures.privateData();
            boolean deviceSupportsSynchronization2 = features13 != null && features13.synchronization2();
            LOGGER.info("Vulkan 设备特性支持状态:");
            LOGGER.info("  mutableDescriptorType: {}", deviceSupportsMutableDescriptor);
            LOGGER.info("  shaderInt8: {}", deviceSupportsShaderInt8);
            LOGGER.info("  shaderInt16: {}", deviceSupportsShaderInt16);
            LOGGER.info("  shaderFloat16: {}", deviceSupportsShaderFloat16);
            LOGGER.info("  shaderStorageImageWriteWithoutFormat: {}", deviceSupportsShaderStorageImageWriteWithoutFormat);
            LOGGER.info("  shaderIntegerDotProduct: {}", deviceSupportsShaderIntegerDotProduct);
            LOGGER.info("  bufferDeviceAddress: {}", deviceSupportsBufferDeviceAddress);
            LOGGER.info("  descriptorIndexing: {}", deviceSupportsDescriptorIndexing);
            LOGGER.info("  dynamicRendering: {}", deviceSupportsDynamicRendering);
            LOGGER.info("  dynamicRenderingLocalRead: {}",deviceSupportsDynamicRenderingLocalRead);
            LOGGER.info("  privateData: {}", deviceSupportsPrivateData);
            LOGGER.info("  synchronization2: {}", deviceSupportsSynchronization2);

            VkPhysicalDeviceMutableDescriptorTypeFeaturesEXT deviceMutableFeatures =
                    VkPhysicalDeviceMutableDescriptorTypeFeaturesEXT.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MUTABLE_DESCRIPTOR_TYPE_FEATURES_EXT)
                            .mutableDescriptorType(deviceSupportsMutableDescriptor);
            VkPhysicalDevicePrivateDataFeaturesEXT devicePrivateDataFeatures =
                    VkPhysicalDevicePrivateDataFeaturesEXT.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRIVATE_DATA_FEATURES_EXT)
                            .privateData(deviceSupportsPrivateData);

            VkPhysicalDeviceShaderIntegerDotProductFeaturesKHR deviceShaderIntFeatures =
                    VkPhysicalDeviceShaderIntegerDotProductFeaturesKHR.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_INTEGER_DOT_PRODUCT_FEATURES_KHR)
                            .pNext(devicePrivateDataFeatures.address())
                            .shaderIntegerDotProduct(deviceSupportsShaderIntegerDotProduct);
            deviceMutableFeatures.pNext(deviceShaderIntFeatures.address());

            VkPhysicalDeviceDynamicRenderingFeaturesKHR deviceDynamicRenderingFeatures =
                    VkPhysicalDeviceDynamicRenderingFeaturesKHR.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DYNAMIC_RENDERING_FEATURES_KHR)
                            .pNext(deviceMutableFeatures.address())
                            .dynamicRendering(deviceSupportsDynamicRendering);
            VkPhysicalDeviceDynamicRenderingLocalReadFeaturesKHR deviceDynamicRenderingLocalReadFeatures =
                    VkPhysicalDeviceDynamicRenderingLocalReadFeaturesKHR.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DYNAMIC_RENDERING_LOCAL_READ_FEATURES_KHR)
                            .dynamicRenderingLocalRead(deviceSupportsDynamicRenderingLocalRead)
                            .pNext(deviceDynamicRenderingFeatures.address());

            VkPhysicalDeviceVulkan13Features deviceFeatures13 = null;
            if (features13 != null) {
                deviceFeatures13 = VkPhysicalDeviceVulkan13Features.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES)
                        .pNext(deviceDynamicRenderingLocalReadFeatures.address())
                        .synchronization2(deviceSupportsSynchronization2);
            }
            VkPhysicalDeviceVulkan12Features deviceFeatures12 = VkPhysicalDeviceVulkan12Features.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES)
                    .pNext(deviceFeatures13 != null ? deviceFeatures13.address() : deviceDynamicRenderingLocalReadFeatures.address())
                    .shaderFloat16(deviceSupportsShaderFloat16)
                    .shaderInt8(deviceSupportsShaderInt8)
                    .bufferDeviceAddress(deviceSupportsBufferDeviceAddress)
                    .descriptorIndexing(deviceSupportsDescriptorIndexing);

            VkPhysicalDeviceFeatures2 deviceFeatures2 = VkPhysicalDeviceFeatures2.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2)
                    .pNext(deviceFeatures12.address());
            deviceFeatures2.features().shaderInt16(deviceSupportsShaderInt16);
            deviceFeatures2.features().shaderStorageImageWriteWithoutFormat(deviceSupportsShaderStorageImageWriteWithoutFormat);
            VkDeviceCreateInfo createInfo = VkDeviceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                    .pNext(deviceFeatures2.address())
                    .pQueueCreateInfos(queueCreateInfos)
                    .ppEnabledExtensionNames(asPointerBuffer(stack, enableDeviceExts))
                    .pEnabledFeatures(null);

            //https://docs.vulkan.org/refpages/latest/refpages/source/VkDeviceCreateInfo.html#:~:text=//%20ppEnabledLayerNames%20is%20legacy%20and%20not%20used
            //if (ENABLE_VALIDATION) {
            //    createInfo.ppEnabledLayerNames(VulkanValidationLayers.getValidationLayersPointerBuffer(stack));
            //}

            PointerBuffer pDevice = stack.mallocPointer(1);
            if (Streamline.isInitialized()){
                long device = Streamline.createVkDevice(
                        getVulkanInstance().address(),
                        physicalDevice.address(),
                        createInfo.address()
                );
                VK_CHECK(Streamline.getLastVkResult(), "Failed to create logical device");
                pDevice.put(0, device);
            }else {
                VK_CHECK(vkCreateDevice(physicalDevice, createInfo, null, pDevice),
                        "Failed to create logical device");
            }

            return new VulkanDevice(
                    instance,
                    physicalDevice,
                    new VkDevice(pDevice.get(0), physicalDevice, createInfo),
                    graphicsFamilyIndex
            );
        }
    }

}
