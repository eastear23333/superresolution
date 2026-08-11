/*
 * Super Resolution
 * Copyright (c) 2026. 187J3X1-114514
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package io.homo.superresolution.common.config.enums;

import java.util.Locale;

/**
 * 上屏(呈现)图形 API 模式。选择器 UI 的三选项:OpenGL / Vulkan / Direct3D 12。
 * <p>超分算法本身不依赖呈现模式(FSR4.1、XeSS-D3D12 通过 GL↔D3D12 互操作在后台跑);
 * 呈现模式只决定最终帧通过哪个 swapchain 上屏,以及哪些 FG/低延迟后端可用。</p>
 */
public enum PresentationMode {
    /** 原版 OpenGL 呈现(默认)。 */
    OPENGL,
    /** 自建 Vulkan swapchain 呈现(Streamline / VK_NV_low_latency2 / DLSS-FG)。 */
    VULKAN,
    /** Direct3D 12 swapchain 呈现(XeSS-FG / XeLL;运行时待实现)。 */
    D3D12;

    public static PresentationMode fromString(String value) {
        if (value == null) {
            return OPENGL;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return OPENGL;
        }
    }
}
