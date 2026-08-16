/*
 * Super Resolution
 * Copyright (c) 2026. 187J3X1-114514
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package io.homo.superresolution.common.presentation.d3d12;

#if MC_VER >= MC_1_20_1 && MC_VER < MC_26_2
import io.homo.superresolution.api.SuperResolutionAPI;
import io.homo.superresolution.common.SuperResolution;
import io.homo.superresolution.common.config.SuperResolutionConfig;
import io.homo.superresolution.common.config.enums.PresentationMode;
import io.homo.superresolution.common.framegeneration.D3D12FrameGeneration;
import io.homo.superresolution.common.lowlatency.LowLatency;
import io.homo.superresolution.common.minecraft.MinecraftWindow;
import io.homo.superresolution.core.graphics.d3d12.D3D12PresentationContext;
import io.homo.superresolution.core.graphics.impl.framebuffer.FrameBufferAttachmentType;
import io.homo.superresolution.core.graphics.impl.framebuffer.IFrameBuffer;
import io.homo.superresolution.core.graphics.impl.texture.ITexture;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;

import java.lang.foreign.MemorySegment;

/**
 * D3D12 presentation mode runtime (FFM). Owns a DXGI flip-model swap chain on
 * the Minecraft window (created GLFW_NO_API by the presentation window mixin)
 * and presents the final OpenGL frame each tick.
 *
 * <p>Mirrors the Vulkan presentation feature. Native availability is checked in
 * {@link #initialize()} — not in {@link #isRequested()} — because the window
 * mixins ask during window creation, before the D3D12 interop DLL is loaded.</p>
 */
public final class D3D12PresentationFeature {
    private static D3D12PresentationContext context;
    private static Boolean startupRequested;
    private static volatile boolean failed;
    private static volatile boolean vsync = true;

    private D3D12PresentationFeature() {
    }

    public static void setVsync(boolean enabled) {
        vsync = enabled;
        D3D12PresentationContext presentationContext = context;
        if (presentationContext != null) {
            presentationContext.setVsync(enabled);
        }
    }

    public static boolean isRequested() {
        if (failed) {
            return false;
        }
        if (startupRequested == null) {
            startupRequested = SuperResolutionConfig.getPresentationMode() == PresentationMode.D3D12
                    && !SuperResolutionConfig.isSkipInitD3D12();
        }
        return startupRequested;
    }

    public static synchronized boolean initialize() {
        if (context != null || !isRequested()) {
            return context != null;
        }
        if (!D3D12PresentationContext.isNativeAvailable()) {
            disableAfterFailure(new IllegalStateException("D3D12 interop library is unavailable"));
            return false;
        }
        long window = MinecraftWindow.getWindowHandle();
        if (window == 0L) {
            throw new IllegalStateException("Minecraft window is unavailable for D3D12 presentation");
        }
        long hwnd = GLFWNativeWin32.glfwGetWin32Window(window);
        if (hwnd == 0L) {
            throw new IllegalStateException("Could not obtain the Win32 window handle");
        }
        int width = MinecraftWindow.getWindowWidth();
        int height = MinecraftWindow.getWindowHeight();
        try {
            context = D3D12PresentationContext.create(
                    hwnd, Math.max(width, 1), Math.max(height, 1));
        } catch (Throwable throwable) {
            SuperResolution.LOGGER.error("D3D12 presentation initialization failed", throwable);
            disableAfterFailure(throwable);
            return false;
        }
        // The window was created hidden (GLFW_VISIBLE=false) by the presentation
        // window mixin; show it now that the swap chain is ready.
        GLFW.glfwShowWindow(window);
        // If XeSS-FG is the configured frame-generation choice, it takes over the swap
        // chain here (a no-op otherwise).
        D3D12FrameGeneration.initialize(context);
        return true;
    }

    public static void endMinecraftFrame() {
        D3D12PresentationContext presentationContext = context;
        if (presentationContext == null) {
            return;
        }
        int w = MinecraftWindow.getWindowWidth();
        int h = MinecraftWindow.getWindowHeight();
        ITexture finalColor = originColorTexture();
        if (w != presentationContext.width() || h != presentationContext.height()) {
            int[] src = MinecraftWindow.getWindowSourceSize();
            SuperResolution.LOGGER.info(
                    "[D3D12] resize detect mc=({},{}) glfwFb=({},{}) ctx=({},{}) finalColor={}",
                    w, h, src[0], src[1],
                    presentationContext.width(), presentationContext.height(),
                    finalColor == null ? "null"
                            : "(" + finalColor.getWidth() + "," + finalColor.getHeight() + ")");
            try {
                presentationContext.resize(Math.max(w, 1), Math.max(h, 1));
            } catch (Throwable throwable) {
                // Resize could not recover: the old swap chain is already released, so
                // continuing to present would use-after-free. Disable D3D12 presentation
                // to fall back safely instead of crashing.
                SuperResolution.LOGGER.warn("D3D12 swapchain resize failed, disabling D3D12 presentation", throwable);
                disableAfterFailure(throwable);
                return;
            }
        }
        if (finalColor == null) {
            return;
        }
        // The Vulkan path stamps render-submit-end from its presentation window; the D3D12
        // presentation has no such hook, so stamp it here so the XeLL markers are complete.
        LowLatency.endRenderSubmission();
        try {
            presentationContext.present(finalColor);
        } catch (Throwable throwable) {
            // A removed device or a broken swap chain cannot recover inside the frame
            // loop (presenting again would spin in fence timeouts or crash natively);
            // disable the D3D12 presentation so the game falls back instead.
            SuperResolution.LOGGER.warn("D3D12 present failed, disabling D3D12 presentation", throwable);
            disableAfterFailure(throwable);
        }
    }

    public static boolean isInitialized() {
        return context != null;
    }

    /**
     * The FFM D3D12 device of the active presentation, or null when the D3D12
     * presentation is not initialized. Used by the XeLL low-latency provider.
     */
    public static MemorySegment contextDevice() {
        D3D12PresentationContext presentationContext = context;
        return presentationContext == null ? null : presentationContext.device();
    }

    /**
     * The active D3D12 presentation context, or null. Exposes the D3D12 device/queue/
     * swap chain/factory to the XeSS-FG backend so it can build the proxy swap chain.
     */
    public static D3D12PresentationContext context() {
        return context;
    }

    public static synchronized void shutdown() {
        // XeSS-FG must be torn down before the D3D12 device/swap chain it borrowed.
        D3D12FrameGeneration.shutdown();
        if (context != null) {
            context.close();
            context = null;
        }
        startupRequested = null;
    }

    public static synchronized void disableAfterFailure(Throwable failure) {
        SuperResolution.LOGGER.error("Disabling D3D12 presentation after failure", failure);
        failed = true;
        shutdown();
    }

    private static ITexture originColorTexture() {
        IFrameBuffer framebuffer = SuperResolutionAPI.getOriginMinecraftFrameBuffer();
        return framebuffer == null ? null : framebuffer.getTexture(FrameBufferAttachmentType.Color);
    }
}
#else
public final class D3D12PresentationFeature {
    public static boolean isRequested() {
        return false;
    }

    public static boolean initialize() {
        return false;
    }

    public static void endMinecraftFrame() {
    }

    public static void shutdown() {
    }

    public static void disableAfterFailure(Throwable failure) {
    }

    public static MemorySegment contextDevice() {
        return null;
    }

    public static io.homo.superresolution.core.graphics.d3d12.D3D12PresentationContext context() {
        return null;
    }
}
#endif
