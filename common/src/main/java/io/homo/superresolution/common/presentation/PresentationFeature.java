/*
 * Super Resolution
 * Copyright (c) 2026. 187J3X1-114514
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package io.homo.superresolution.common.presentation;

import io.homo.superresolution.common.presentation.d3d12.D3D12PresentationFeature;
import io.homo.superresolution.common.presentation.vulkan.VulkanPresentationFeature;

/**
 * Unified gate for "a non-default presentation backend is active". When either
 * the Vulkan or the D3D12 presentation feature is requested, the presentation
 * window mixins run (main window GLFW_NO_API + hidden OpenGL helper window), and
 * Minecraft's OpenGL swap-chain present is skipped in favour of the active backend.
 */
public final class PresentationFeature {
    private PresentationFeature() {
    }

    /** Whether Super Resolution takes over final presentation (Vulkan or D3D12). */
    public static boolean isPresentationRequested() {
        return VulkanPresentationFeature.isRequested()
                || D3D12PresentationFeature.isRequested();
    }
}
