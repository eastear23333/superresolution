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

package io.homo.superresolution.core;

import io.homo.superresolution.core.graphics.vulkan.VkReflectionHelper;
import io.homo.superresolution.core.graphics.vulkan.VulkanDevice;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.vulkan.VK10;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SuperResolutionNativeHelper {
    public static final Logger LOGGER_CPP = LoggerFactory.getLogger("SuperResolution/Native");

    public static void CPP_Log(String msg, int level) {
        switch (level) {
            case 2 -> LOGGER_CPP.info(msg);
            case 1 -> LOGGER_CPP.warn(msg);
            case 0 -> LOGGER_CPP.error(msg);
            case 3 -> LOGGER_CPP.debug(msg);
        }
    }

    public static long CPP_glfwGetProcAddress(String name) {
        return GLFW.glfwGetProcAddress(name);
    }

    public static long CPP_vkGetDeviceProcAddr(String name) {
        if (name.equals("SuperResolution_GetInstance")) {
            return RenderSystems.vulkan().getVulkanInstance().address();
        }
        if (name.equals("SuperResolution_VkGetInstanceProcAddr")) {
            // 走统一的反射工具。原来这里用 getDeclaredMethod + getField 的裸反射，
            // 而 LWJGL 的 getGlobalCommands() 与 vkGetInstanceProcAddr 字段都是包私有
            // （3.4.x 起字段还是 final），getField() 只认 public 字段必然失败，
            // 结果一路传 0 给 native，导致 NSS/DP4A 初始化时报
            // "vkGetInstanceProcAddr 失败 / vulkan call failed"。
            return VkReflectionHelper.getVkGetInstanceProcAddr();
        }
        return VK10.vkGetDeviceProcAddr(
                RenderSystems.vulkan().device().getVkDevice(),
                name
        );
    }
}
