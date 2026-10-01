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

import io.homo.superresolution.common.SuperResolution;
import io.homo.superresolution.core.impl.Destroyable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import static io.homo.superresolution.core.graphics.vulkan.VulkanUtils.VK_CHECK;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.VK10.*;

public class VulkanValidationLayers implements Destroyable {
    private static final Logger LOGGER = LoggerFactory.getLogger(VulkanValidationLayers.class);
    private static final Set<String> REQUIRED_LAYERS = Collections.singleton("VK_LAYER_KHRONOS_validation");

    /**
     * 常见 Vulkan SDK 安装位置。用于诊断「校验层为什么没生效」。
     *
     * <p>背景：外部启动器常把 {@code VK_LAYER_PATH} 设为
     * super_resolution/libraries（里面只有 Arm 的 VkLayer_arm_NG /
     * VkLayer_Graph / VkLayer_Tensor）。而 Vulkan loader 的层搜索规则是：
     * {@code VK_LAYER_PATH} 里的目录会被搜索，<b>默认路径同时仍然生效</b>。
     * 所以理论上 SDK 装在注册表登记的位置就能找到 ——
     * 若找不到，通常意味着 SDK 是解压式的（未走安装程序、未写注册表），
     * 此时必须显式把 SDK 的 Bin 目录加进 {@code VK_LAYER_PATH}。
     */
    private static final String[] SDK_LAYER_CANDIDATES = {
            "E:\\VulkanSDK\\Bin",
            "C:\\VulkanSDK\\Bin",
            "D:\\VulkanSDK\\Bin",
    };

    private final VkInstance instance;
    private long debugMessenger;

    public VulkanValidationLayers(VkInstance instance) {
        this.instance = instance;
    }

    /**
     * 诊断：校验层不可用时，找出机器上「看起来有校验层」的目录，
     * 并在日志里给出可直接复制的修复指引。
     *
     * <p>这个方法<b>不修改</b>进程环境变量 —— JDK 从 16 起
     * {@code ProcessEnvironment} 被模块封装，纯 Java 无法写入。
     * 可行的做法只有两条：改启动器的环境变量配置，或在 JVM 参数里加
     * {@code -DVK_LAYER_PATH=...}（loader 不认这个，故只有前者有效）。
     * 所以这里只负责把「该往哪加」讲清楚，把动作留给用户。
     *
     * @return 找到的 SDK 层目录；没有时返回 null
     */
    public static String diagnoseMissingLayer() {
        if (!enumerateLayers().stream().anyMatch(REQUIRED_LAYERS::contains)) {
            for (String candidate : SDK_LAYER_CANDIDATES) {
                if (Files.isRegularFile(Path.of(candidate, "VkLayer_khronos_validation.json"))) {
                    LOGGER.warn(
                            "检测到 Vulkan SDK 校验层位于 {}，但当前 VK_LAYER_PATH({}) 无法发现它。"
                                    + "修复：把 {} 加入启动器的 VK_LAYER_PATH 环境变量（前置），"
                                    + "或在 Windows 系统环境变量里设置 VK_LAYER_PATH={}{}{}",
                            candidate,
                            System.getenv("VK_LAYER_PATH"),
                            candidate,
                            candidate,
                            File.pathSeparator,
                            System.getenv("VK_LAYER_PATH") == null ? "" : System.getenv("VK_LAYER_PATH")
                    );
                    return candidate;
                }
            }
        }
        return null;
    }

    /**
     * 校验层是否可用。
     *
     * <p>注意：这个方法是 {@code ENABLE_VALIDATION} 的第一个条件，而
     * {@code ENABLE_VALIDATION} 是 {@code static final} 字段，在类加载时求值。
     * 因此这里的日志是排查「为什么校验层没生效」的唯一入口 ——
     * 一旦返回 false，整个会话都不会有任何
     * {@code [Vulkan Validation Error]} 输出，排查就变成了盲猜。
     */
    public static boolean checkValidationLayerSupport() {
        List<String> available = enumerateLayers();
        boolean supported = available.stream().anyMatch(REQUIRED_LAYERS::contains);
        if (!supported) {
            LOGGER.warn(
                    "未找到 Vulkan 校验层 {}，本会话不会输出任何校验信息。已枚举到的层：{}；VK_LAYER_PATH={}",
                    REQUIRED_LAYERS,
                    available,
                    System.getenv("VK_LAYER_PATH")
            );
            diagnoseMissingLayer();
        } else {
            LOGGER.info("已启用 Vulkan 校验层：{}", REQUIRED_LAYERS);
        }
        return supported;
    }

    private static List<String> enumerateLayers() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer layerCount = stack.ints(0);
            VK_CHECK(vkEnumerateInstanceLayerProperties(layerCount, null));

            VkLayerProperties.Buffer availableLayers = VkLayerProperties.malloc(layerCount.get(0), stack);
            VK_CHECK(vkEnumerateInstanceLayerProperties(layerCount, availableLayers));

            return availableLayers.stream()
                    .map(VkLayerProperties::layerNameString)
                    .collect(Collectors.toList());
        }
    }

    public static void populateDebugMessengerCreateInfo(VkDebugUtilsMessengerCreateInfoEXT createInfo) {
        createInfo.sType(VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT)
                .messageSeverity(VK_DEBUG_UTILS_MESSAGE_SEVERITY_VERBOSE_BIT_EXT |
                        VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT |
                        VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
                .messageType(VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT |
                        VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT |
                        VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
                .pfnUserCallback(new VkDebugUtilsMessengerCallbackEXT() {
                    @Override
                    public int invoke(int messageSeverity, int messageTypes, long pCallbackData, long pUserData) {
                        VkDebugUtilsMessengerCallbackDataEXT callbackData = VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData);
                        String message = callbackData.pMessageString();
                        Thread callbackThread = Thread.currentThread();
                        Thread renderThread = SuperResolution.renderThread;
                        StackTraceElement[] callbackStack = callbackThread.getStackTrace();
                        StackTraceElement[] renderThreadSnapshot = renderThread == null || renderThread == callbackThread
                                ? null
                                : renderThread.getStackTrace();

                        if ((messageSeverity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0) {
                            LOGGER.error(
                                    "[Vulkan Validation Error] [callback thread={}] {}",
                                    callbackThread.getName(),
                                    message
                            );
                            logThreadStacks(
                                    (format, argument) -> LOGGER.error(format, argument),
                                    callbackThread,
                                    callbackStack,
                                    renderThread,
                                    renderThreadSnapshot
                            );
                        } else if ((messageSeverity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT) != 0) {
                            LOGGER.warn(
                                    "[Vulkan Validation Warn] [callback thread={}] {}",
                                    callbackThread.getName(),
                                    message
                            );
                            logThreadStacks(
                                    (format, argument) -> LOGGER.warn(format, argument),
                                    callbackThread,
                                    callbackStack,
                                    renderThread,
                                    renderThreadSnapshot
                            );
                        } else {
                            LOGGER.info(
                                    "[Vulkan Validation Debug] [callback thread={}] {}",
                                    callbackThread.getName(),
                                    message
                            );
                            logThreadStacks(
                                    (format, argument) -> LOGGER.info(format, argument),
                                    callbackThread,
                                    callbackStack,
                                    renderThread,
                                    renderThreadSnapshot
                            );
                        }

                        return VK_FALSE;
                    }
                });
    }

    private static void logThreadStacks(
            BiConsumer<String, Object> logger,
            Thread callbackThread,
            StackTraceElement[] callbackStack,
            Thread renderThread,
            StackTraceElement[] renderThreadSnapshot
    ) {
        logger.accept(
                "    Vulkan validation callback stack [thread={}]:",
                callbackThread.getName()
        );
        for (StackTraceElement element : callbackStack) {
            logger.accept("    {}", element.toString());
        }
        if (renderThreadSnapshot == null) {
            return;
        }
        logger.accept(
                "    Render-thread snapshot [thread={}, not the Vulkan caller]:",
                renderThread.getName()
        );
        for (StackTraceElement element : renderThreadSnapshot) {
            logger.accept("    {}", element.toString());
        }
    }

    public static PointerBuffer getValidationLayersPointerBuffer(MemoryStack stack) {
        PointerBuffer buffer = stack.mallocPointer(REQUIRED_LAYERS.size());
        REQUIRED_LAYERS.forEach(layer -> buffer.put(stack.UTF8(layer)));
        return buffer.rewind();
    }

    public void setupDebugMessenger() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDebugUtilsMessengerCreateInfoEXT createInfo = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack);
            populateDebugMessengerCreateInfo(createInfo);

            LongBuffer pDebugMessenger = stack.mallocLong(1);
            VK_CHECK(createDebugUtilsMessengerEXT(instance, createInfo, pDebugMessenger), "Failed to set up debug messenger");
            debugMessenger = pDebugMessenger.get(0);
        }
    }

    private int debugCallback(int messageSeverity, int messageType, long pCallbackData, long pUserData) {
        VkDebugUtilsMessengerCallbackDataEXT callbackData = VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData);
        String message = callbackData.pMessageString();

        if ((messageSeverity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0) {
            LOGGER.info("[Vulkan Validation Error] {}", message);
        } else if ((messageSeverity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT) != 0) {
            LOGGER.info("[Vulkan Validation Warn] {}", message);
        } else {
            LOGGER.info("[Vulkan Validation Debug] {}", message);
        }

        return VK_FALSE;
    }

    @Override
    public void destroy() {
        if (debugMessenger != NULL) {
            destroyDebugUtilsMessengerEXT(instance, debugMessenger);
        }
    }

    private int createDebugUtilsMessengerEXT(VkInstance instance,
                                             VkDebugUtilsMessengerCreateInfoEXT createInfo,
                                             LongBuffer pDebugMessenger) {
        long func = vkGetInstanceProcAddr(instance, "vkCreateDebugUtilsMessengerEXT");
        return func != NULL ?
                VK_CHECK(vkCreateDebugUtilsMessengerEXT(instance, createInfo, null, pDebugMessenger)) :
                VK_ERROR_EXTENSION_NOT_PRESENT;
    }

    private void destroyDebugUtilsMessengerEXT(VkInstance instance, long debugMessenger) {
        long func = vkGetInstanceProcAddr(instance, "vkDestroyDebugUtilsMessengerEXT");
        if (func != NULL) {
            vkDestroyDebugUtilsMessengerEXT(instance, debugMessenger, null);
        }
    }
}
