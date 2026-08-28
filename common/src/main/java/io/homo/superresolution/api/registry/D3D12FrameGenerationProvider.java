/*
 * Super Resolution
 * Copyright (c) 2026. 187J3X1-114514
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package io.homo.superresolution.api.registry;

/**
 * Frame generation backend driven directly by the D3D12 presentation path.
 *
 * <p>The {@code FrameGenerationProvider} scheduling is Vulkan-swapchain-centric, so a
 * D3D12 swap-chain provider (Intel XeSS-FG) is driven from
 * {@code D3D12PresentationFeature} instead: the provider takes over the presentation
 * swap chain and tags per-frame constants/resources right before Present. It must still
 * implement {@link FrameGenerationProvider} (and be registered through
 * {@link FrameGenerationRegistry}) so the UI/negotiator can see and pair it, but its
 * lifecycle/per-frame calls come from this interface.</p>
 */
public interface D3D12FrameGenerationProvider extends FrameGenerationProvider {

    /**
     * Create the frame generation context and take over the D3D12 swap chain.
     * Called once when the D3D12 presentation initializes. Returns the proxy
     * {@code IDXGISwapChain*} address the presentation must use from now on,
     * or 0 on failure (the original swap chain stays active).
     */
    long initializeD3D12(D3D12PresentationHandles handles);

    /**
     * Tag this frame's constants and depth/motion-vector resources and set the present
     * id. Called on the render thread right before the swap chain {@code Present}.
     * {@code viewMatrix}/{@code projectionMatrix} are 16-float row-major arrays without
     * applied jitter; {@code frameRenderTimeMs} paces the interpolated frames;
     * {@code depthResource}/{@code motionVectorResource} are {@code ID3D12Resource*}
     * addresses on the presentation device, or 0 when not yet available;
     * {@code hudlessResource} is the pre-UI color resource for UI composition, or 0 when
     * the frame has no HUD-less color (UI composition is toggled separately).
     */
    void prepareD3D12Present(
            int presentId,
            float[] viewMatrix,
            float[] projectionMatrix,
            float jitterOffsetX,
            float jitterOffsetY,
            float motionVectorScaleX,
            float motionVectorScaleY,
            boolean resetHistory,
            float frameRenderTimeMs,
            long depthResource,
            long motionVectorResource,
            long hudlessResource);

    /**
     * Enables or disables XeSS-FG UI composition (interpolating the HUD-less color and
     * compositing the UI instead of interpolating the UI itself). Called when the
     * HUD-less color availability changes.
     */
    default void setUiCompositionEnabled(boolean enabled) {
    }

    /**
     * Updates the extent reported when tagging the depth/motion-vector resources. This is
     * the frame inputs' native (dispatch) resolution, which is lower than the presentation
     * extent when upscaling — XeSS-FG treats a resourceSize smaller than the back buffer
     * (without the HIGH_RES_MV flag) as low-res motion vectors and upsamples/dilates them
     * internally, which is the documented default and recommended configuration. Depth and
     * motion vectors must share this extent.
     */
    default void updateFrameInputExtent(int width, int height) {
    }

    /**
     * Updates the extent reported when tagging the HUD-less color resource. The XeSS-FG
     * guide requires the HUD-less extent to match the back buffer, so this always tracks
     * the presentation extent, independent of the frame-input extent.
     */
    default void updateHudlessExtent(int width, int height) {
    }

    /** Enable or disable interpolation. */
    void setEnabled(boolean enabled);

    /**
     * Set how many interpolated frames the backend generates per presented frame
     * (1 = 2x total). Called after a successful takeover and when the configured
     * frame-generation mode changes; the backend clamps to its supported maximum.
     */
    default void setNumInterpolatedFrames(int interpolatedFrames) {
    }

    /** Release the context and restore the presentation swap chain. */
    void shutdownD3D12();
}
