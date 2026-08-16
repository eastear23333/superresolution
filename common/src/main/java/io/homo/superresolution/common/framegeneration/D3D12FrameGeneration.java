/*
 * Super Resolution
 * Copyright (c) 2026. 187J3X1-114514
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package io.homo.superresolution.common.framegeneration;

#if MC_VER >= MC_1_20_1 && MC_VER < MC_26_2
import io.homo.superresolution.api.InputResourceSet;
import io.homo.superresolution.api.SuperResolutionAPI;
import io.homo.superresolution.api.event.AlgorithmDispatchEvent;
import io.homo.superresolution.api.registry.D3D12FrameGenerationProvider;
import io.homo.superresolution.api.registry.D3D12PresentationHandles;
import io.homo.superresolution.api.registry.FrameGenerationDescription;
import io.homo.superresolution.api.registry.FrameGenerationRegistry;
import io.homo.superresolution.common.SuperResolution;
import io.homo.superresolution.common.config.SuperResolutionConfig;
import io.homo.superresolution.common.framegeneration.constants.MinecraftCameraState;
import io.homo.superresolution.common.lowlatency.xell.XeLLowLatency;
import io.homo.superresolution.common.minecraft.MinecraftUtils;
import io.homo.superresolution.common.presentation.d3d12.D3D12PresentationFeature;
import io.homo.superresolution.common.upscale.InteropResourcesConverter;
import io.homo.superresolution.core.graphics.d3d12.D3D12PresentationContext;
import io.homo.superresolution.core.graphics.impl.texture.ITexture;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.lang.foreign.MemorySegment;

/**
 * Drives the D3D12 frame-generation provider (Intel XeSS-FG) from the D3D12
 * presentation.
 *
 * <p>The Vulkan swap-chain-centric {@link FrameGeneration} scheduling never runs for the
 * D3D12 presentation, so this facade owns the lifecycle and per-present calls for the
 * single registered {@link D3D12FrameGenerationProvider}: initialize it once the D3D12
 * presentation comes up (taking over the swap chain with the proxy), tag constants and
 * resources right before every Present, and tear it down with the presentation.</p>
 *
 * <p>Depth and motion vectors come from the shader-compat path: {@code IrisShaderCompatUpscaleDispatcher}
 * publishes an {@link AlgorithmDispatchEvent} every frame carrying the Iris depth and
 * velocity buffers; this class caches those GL textures and copies them into the D3D12
 * presentation's shared depth/motion-vector textures (flipY / flipMotionVectorY) so
 * XeSS-FG gets {@code ID3D12Resource*} inputs on the presentation device.</p>
 */
public final class D3D12FrameGeneration {
    /** Wisteria's XeSS-FG backend id (registered through FrameGenerationRegisterEvent). */
    private static final String XEFG_BACKEND_ID = "wisteria:xefg";
    /** The XESS_FG group representative id, which the config stores when selected. */
    private static final String XESS_FG_GROUP_ID = "superresolution:xess_fg";
    /**
     * XeSS-FG proxy swap-chain takeover. Disabled previously because the proxy Present
     * crashed in native code; re-enabled after aligning the per-frame path with OptiScaler
     * (tag/SetPresentId only when depth+motion vectors are present, own present counter,
     * NULL command list for UNTIL_NEXT_PRESENT on SDK 1.3.1+).
     */
    private static final boolean XEFG_TAKEOVER_ENABLED = true;

    private static D3D12FrameGenerationProvider provider;
    private static boolean enabled;
    private static boolean pendingInit;
    private static D3D12PresentationContext presentation;
    private static boolean eventRegistered;
    private static ITexture cachedDepth;
    private static ITexture cachedMotionVector;
    /** Last frame time delta from the dispatch event, passed as frameRenderTime (ms). */
    private static float cachedFrameTimeDelta;
    /** Last multiplier pushed to the provider; re-synced from the config each present. */
    private static int appliedInterpolatedFrames = -1;
    /** Presentation extent last pushed to the provider's tagged resource size. */
    private static int reportedWidth = -1;
    private static int reportedHeight = -1;
    /** Camera discontinuity detection inputs for resetHistory (position/dimension/fov). */
    private static Vector3d lastCameraPosition;
    private static float lastCameraFov = Float.NEGATIVE_INFINITY;
    private static boolean lastCameraStateValid;
    /**
     * Monotonically increasing present id for XeSS-FG resource tagging. OptiScaler tags
     * each frame with an internal per-present counter; the XeLL latency frame id is not
     * kept in lock-step with the present sequence, so it is not used here.
     */
    private static long xefgPresentCounter;

    private D3D12FrameGeneration() {
    }

    private static void ensureEventRegistered() {
        if (eventRegistered) {
            return;
        }
        SuperResolutionAPI.EVENT_BUS.addListener(D3D12FrameGeneration::onAlgorithmDispatch);
        eventRegistered = true;
    }

    /** Caches the Iris depth/motion-vector GL textures and frame time for the next present. */
    private static void onAlgorithmDispatch(AlgorithmDispatchEvent event) {
        if (event == null || event.getDispatchResource() == null) {
            return;
        }
        InputResourceSet resources = event.getDispatchResource().resources();
        boolean depthPresent = resources.depthTexture() != null;
        boolean mvPresent = resources.motionVectorsTexture() != null;
        boolean inputsChanged = (cachedDepth != null) != depthPresent
                || (cachedMotionVector != null) != mvPresent;
        cachedDepth = resources.depthTexture();
        cachedMotionVector = resources.motionVectorsTexture();
        cachedFrameTimeDelta = event.getDispatchResource().frameTimeDelta();
        if (inputsChanged) {
            SuperResolution.LOGGER.info("[D3D12] XeSS-FG dispatch: depth={} motionVector={}",
                    depthPresent, mvPresent);
        }
    }

    /**
     * Initializes the XeSS-FG provider and swaps in its proxy swap chain. Called once
     * after the D3D12 presentation initializes; a no-op unless XeSS-FG is the configured
     * frame-generation choice and the backend is registered and supported. If the XeLL
     * context is not created yet (it is created on the first tick by the low-latency
     * renegotiation), initialization is deferred and retried from {@link #beforePresent}.
     */
    public static synchronized void initialize(D3D12PresentationContext presentationContext) {
        presentation = presentationContext;
        if (provider != null) {
            return;
        }
        // D3D12 activation flips which FG backends are supported; the registry caches
        // requirement results, so drop the stale cache before consulting it.
        FrameGenerationRegistry.clearSupportCache();
        String configured = SuperResolutionConfig.getFrameGenerationProvider();
        if (!XESS_FG_GROUP_ID.equals(configured)) {
            SuperResolution.LOGGER.info("[D3D12] XeSS-FG skipped: configured provider='{}'", configured);
            return;
        }
        if (!XEFG_TAKEOVER_ENABLED) {
            pendingInit = false;
            SuperResolution.LOGGER.info(
                    "[D3D12] XeSS-FG is registered but disabled (takeover off pending rework); "
                            + "D3D12 presentation runs stable");
            return;
        }
        if (XeLLowLatency.context().address() == 0L) {
            pendingInit = true;
            SuperResolution.LOGGER.info("[D3D12] XeSS-FG deferred: XeLL context not ready");
            return;
        }
        FrameGenerationDescription description = FrameGenerationRegistry.getDescriptionById(XEFG_BACKEND_ID);
        boolean supported = description != null && FrameGenerationRegistry.isSupported(description);
        if (!supported) {
            SuperResolution.LOGGER.info("[D3D12] XeSS-FG skipped: desc={} supported={}",
                    description != null, supported);
            return;
        }
        io.homo.superresolution.api.registry.FrameGenerationProvider created = description.createProvider();
        if (!(created instanceof D3D12FrameGenerationProvider d3d12Provider)) {
            SuperResolution.LOGGER.info("[D3D12] XeSS-FG skipped: provider is {} not D3D12FrameGenerationProvider",
                    created == null ? "null" : created.getClass().getName());
            return;
        }
        pendingInit = false;
        ensureEventRegistered();
        // xefgSwapChainD3D12InitFromSwapChainDesc creates its own swap chain on the window,
        // which requires the presentation's swap chain to be released first.
        presentation.releaseSwapchain();
        D3D12PresentationHandles handles = new D3D12PresentationHandles(
                presentation.device().address(),
                presentation.queue().address(),
                presentation.hwnd(),
                presentation.swapchain().address(),
                presentation.factory().address(),
                XeLLowLatency.context().address(),
                presentation.width(),
                presentation.height());
        long proxy = d3d12Provider.initializeD3D12(handles);
        if (proxy != 0L) {
            presentation.setSwapchain(MemorySegment.ofAddress(proxy));
            provider = d3d12Provider;
            // Force the next beforePresent to re-sync the multiplier and tagged extent.
            appliedInterpolatedFrames = -1;
            reportedWidth = -1;
            reportedHeight = -1;
            lastCameraStateValid = false;
            // Keep enabled=false: XeSS-FG starts disabled and is only enabled once a frame
            // actually provides depth/motion vectors (beforePresent). Marking it enabled
            // here made the first input-less frame think interpolation was on and call
            // SetEnabled(false), which crashed the freshly-created proxy Present.
            enabled = false;
            // Capture the proxy's Present slot now; the proxy nulls it after the first
            // Present, so this cached pointer is what later presents call.
            presentation.capturePresentSlot();
            SuperResolution.LOGGER.info("[D3D12] XeSS-FG took over the swap chain (proxy={})",
                    Long.toHexString(proxy));
        } else {
            // Restore the presentation swap chain so rendering keeps working.
            try {
                presentation.recreateSwapchain(presentation.width(), presentation.height());
                SuperResolution.LOGGER.info("[D3D12] XeSS-FG initialization failed; swap chain restored");
            } catch (Throwable restoreFailure) {
                // The presentation swap chain is already released and cannot be recreated
                // (e.g. the device died mid-initialization). A null swap chain would crash
                // the next present natively, so fall out of the D3D12 presentation entirely.
                SuperResolution.LOGGER.error(
                        "[D3D12] XeSS-FG init failed and swap chain restore failed", restoreFailure);
                D3D12PresentationFeature.disableAfterFailure(restoreFailure);
            }
        }
    }

    /**
     * Tags this frame's constants and resources and sets the present id on the provider.
     * Called from the D3D12 present path right before the swap chain {@code Present}.
     * No-op while depth/motion vectors are unavailable, so the proxy stays in passthrough.
     */
    public static void beforePresent() {
        // If initialization was deferred waiting for the XeLL context, retry now that the
        // low-latency renegotiation has run.
        if (provider == null && pendingInit && presentation != null
                && XeLLowLatency.context().address() != 0L) {
            initialize(presentation);
        }
        D3D12FrameGenerationProvider active = provider;
        if (active == null) {
            return;
        }
        // Own per-present counter for the XeSS-FG frame id (see field docs).
        int xefgFrameId = (int) (xefgPresentCounter++ & 0xFFFFFFFFL);
        try {
            syncConfiguredState(active);
            FrameGenerationMode mode = FrameGeneration.displayedMode();
            boolean hasInputs = mode.isEnabled() && cachedDepth != null
                    && cachedMotionVector != null && presentation != null;
            if (hasInputs) {
                // Only tag constants/resources (and enable interpolation) once frame
                // generation is configured on and both depth and motion vectors are
                // available for this frame; OptiScaler never tags a disabled/inputless
                // frame (it crashes the proxy Present in native code).
                if (!enabled) {
                    active.setEnabled(true);
                    enabled = true;
                    SuperResolution.LOGGER.info("[D3D12] XeSS-FG interpolation enabled");
                }
                SuperResolution.LOGGER.debug("[D3D12] XeSS-FG tag frame {} (enabled={})",
                        xefgFrameId, enabled);
                active.prepareD3D12Present(
                        xefgFrameId, viewMatrix(), projectionMatrix(),
                        0.0f, 0.0f, 1.0f, 1.0f,
                        cameraDiscontinuity(), cachedFrameTimeDelta,
                        presentation.depthTexture().address(),
                        presentation.mvTexture().address());
            } else if (enabled) {
                // Frame generation was switched off, or the depth/motion-vector stream
                // dropped (e.g. a menu/pause covers the scene); disable interpolation so
                // the next Present is a plain passthrough instead of an inputless tag.
                SuperResolution.LOGGER.info(
                        "[D3D12] XeSS-FG disabling (mode={}, depth={}, mv={})",
                        mode, cachedDepth != null, cachedMotionVector != null);
                active.setEnabled(false);
                enabled = false;
            }
        } catch (Throwable throwable) {
            SuperResolution.LOGGER.warn("[D3D12] XeSS-FG preparePresent failed", throwable);
        }
    }

    /**
     * Pushes the configured frame-generation mode's multiplier and the presentation extent
     * to the provider when they change. Runs every present; both checks are cheap
     * comparisons once synced.
     */
    private static void syncConfiguredState(D3D12FrameGenerationProvider active) {
        if (presentation == null) {
            return;
        }
        int interpolatedFrames;
        FrameGenerationMode mode = FrameGeneration.displayedMode();
        interpolatedFrames = mode.isEnabled() ? Math.max(1, mode.generatedFrameCount()) : 1;
        if (interpolatedFrames != appliedInterpolatedFrames) {
            appliedInterpolatedFrames = interpolatedFrames;
            SuperResolution.LOGGER.info(
                    "[D3D12] XeSS-FG interpolated frames per present: {}", interpolatedFrames);
            active.setNumInterpolatedFrames(interpolatedFrames);
        }
        if (presentation.width() != reportedWidth || presentation.height() != reportedHeight) {
            reportedWidth = presentation.width();
            reportedHeight = presentation.height();
            active.updateResourceExtent(reportedWidth, reportedHeight);
        }
    }

    /**
     * Detects camera discontinuities (teleport/large jump/FOV change) so the frame tag can
     * reset XeSS-FG's interpolation history for one frame, preventing ghost trails after
     * camera cuts.
     */
    private static boolean cameraDiscontinuity() {
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        #if MC_VER >= MC_1_21_11
        Vector3d position = new Vector3d(camera.position().x, camera.position().y, camera.position().z);
        #else
        Vector3d position = new Vector3d(camera.getPosition().x, camera.getPosition().y, camera.getPosition().z);
        #endif
        float fov = MinecraftCameraState.fov;
        boolean discontinuity = lastCameraStateValid
                && (lastCameraPosition.distanceSquared(position) > 64.0
                || Math.abs(lastCameraFov - fov) > 1.0f);
        lastCameraPosition = position;
        lastCameraFov = fov;
        lastCameraStateValid = true;
        return discontinuity;
    }

    /**
     * Copies the cached Iris depth/motion-vector GL textures into the D3D12 presentation's
     * shared depth/motion-vector textures. Called from the D3D12 present path while
     * recording the frame's GL work, so the copy lands before the shared-fence signal.
     */
    public static void writeFrameInputs(D3D12PresentationContext presentationContext) {
        ITexture depth = cachedDepth;
        ITexture mv = cachedMotionVector;
        if (depth != null) {
            InteropResourcesConverter.flipY(depth, presentationContext.depthGlTexture());
        }
        if (mv != null) {
            InteropResourcesConverter.flipMotionVectorY(mv, presentationContext.mvGlTexture());
        }
    }

    /** Enables or disables interpolation on the provider. */
    public static void setEnabled(boolean value) {
        if (provider != null && value != enabled) {
            provider.setEnabled(value);
            enabled = value;
        }
    }

    /**
     * Called by the D3D12 presentation when it detects the device was removed
     * (fence GetCompletedValue returns UINT64_MAX). Disables interpolation so the
     * dead SDK context is not fed further tags; the caller disables the whole
     * D3D12 presentation afterwards.
     */
    public static void onDeviceRemoved(int removalReason) {
        SuperResolution.LOGGER.error(
                "[D3D12] D3D12 device removed (GetDeviceRemovedReason=0x{}); disabling XeSS-FG",
                Integer.toHexString(removalReason));
        if (provider != null && enabled) {
            try {
                provider.setEnabled(false);
            } catch (Throwable throwable) {
                SuperResolution.LOGGER.warn(
                        "[D3D12] XeSS-FG disable after device removal failed", throwable);
            }
            enabled = false;
        }
    }

    /** Tears down the provider and restores the presentation swap chain. */
    public static synchronized void shutdown() {
        if (provider != null) {
            // Disable interpolation before destroying the context (cheap insurance for a
            // mid-game teardown while frames are still being tagged).
            if (enabled) {
                try {
                    provider.setEnabled(false);
                } catch (Throwable throwable) {
                    SuperResolution.LOGGER.debug("[D3D12] XeSS-FG disable on shutdown failed", throwable);
                }
            }
            // Drop our reference to the proxy swap chain first: the SDK refuses
            // xefgSwapChainDestroy with -19 (POINTER_STILL_IN_USE) while any proxy
            // reference is alive, and the presentation holds the one returned by
            // GetSwapChainPtr.
            if (presentation != null) {
                presentation.releaseSwapchain();
                presentation.resetPresentSlot();
            }
            try {
                provider.shutdownD3D12();
            } catch (Throwable throwable) {
                SuperResolution.LOGGER.warn("[D3D12] XeSS-FG shutdown failed", throwable);
            }
            provider = null;
            enabled = false;
            appliedInterpolatedFrames = -1;
            reportedWidth = -1;
            reportedHeight = -1;
        }
        presentation = null;
        cachedDepth = null;
        cachedMotionVector = null;
        cachedFrameTimeDelta = 0.0f;
        lastCameraStateValid = false;
    }

    /**
     * Tears the XeSS-FG takeover down mid-game and restores a plain presentation swap
     * chain. Called right before the XeLL context is destroyed: the proxy Present uses
     * the connected XeLL context (and the shared libxell.dll) even in passthrough mode
     * (frame-generation mode OFF only disables interpolation), so destroying XeLL
     * underneath a live XeSS-FG context crashes the next Present with a DEP violation
     * (the XeSS-FG guide requires destroying XeFG before XeLL). The takeover comes back
     * automatically once a new XeLL context exists.
     */
    public static synchronized void teardownForLowLatencyShutdown() {
        D3D12PresentationContext presentationContext = presentation;
        if (presentationContext == null || provider == null) {
            return;
        }
        shutdown();
        try {
            presentationContext.recreateSwapchain(
                    presentationContext.width(), presentationContext.height());
            // Keep the presentation and arm the deferred retry so the takeover resumes
            // when a new XeLL context appears (XeLL re-enabled).
            presentation = presentationContext;
            pendingInit = true;
            SuperResolution.LOGGER.info(
                    "[D3D12] XeSS-FG torn down before XeLL shutdown; swap chain restored");
        } catch (Throwable throwable) {
            SuperResolution.LOGGER.error(
                    "[D3D12] XeSS-FG teardown swap chain restore failed", throwable);
            D3D12PresentationFeature.disableAfterFailure(throwable);
        }
    }

    /** Row-major view matrix rebuilt from the Minecraft camera basis vectors. */
    private static float[] viewMatrix() {
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        #if MC_VER >= MC_1_21_11
        Vector3d position = new Vector3d(camera.position().x, camera.position().y, camera.position().z);
        Vector3f forward = new Vector3f(camera.forwardVector());
        Vector3f up = new Vector3f(camera.upVector());
        Vector3f right = new Vector3f(camera.leftVector()).negate();
        #else
        Vector3d position = new Vector3d(camera.getPosition().x, camera.getPosition().y, camera.getPosition().z);
        Vector3f forward = new Vector3f(camera.getLookVector());
        Vector3f up = new Vector3f(camera.getUpVector());
        Vector3f right = new Vector3f(camera.getLeftVector()).negate();
        #endif
        return buildView(right, up, forward, position);
    }

    private static float[] buildView(Vector3f right, Vector3f up, Vector3f forward, Vector3d position) {
        return new float[]{
                right.x, right.y, right.z, (float) -(right.x * position.x + right.y * position.y + right.z * position.z),
                up.x, up.y, up.z, (float) -(up.x * position.x + up.y * position.y + up.z * position.z),
                forward.x, forward.y, forward.z, (float) -(forward.x * position.x + forward.y * position.y + forward.z * position.z),
                0f, 0f, 0f, 1f
        };
    }

    /**
     * Row-major perspective projection (DirectX convention, Z in [0,1]) built from the
     * Minecraft camera FOV. The Minecraft render projection is not reachable from this
     * facade (1.21.11 has no RenderSystem#getProjectionMatrix), and the FOV/eye-depth
     * conventions are reconciled when the depth/motion-vector capture lands (next phase).
     */
    private static float[] projectionMatrix() {
        float fovDegrees = MinecraftCameraState.fov;
        if (fovDegrees <= 0.0f) {
            fovDegrees = 70.0f; // Minecraft default FOV fallback
        }
        float fovRadians = (float) Math.toRadians(fovDegrees);
        int width = Minecraft.getInstance().getWindow().getScreenWidth();
        int height = Minecraft.getInstance().getWindow().getScreenHeight();
        float aspect = height <= 0 ? 1.0f : (float) width / height;
        return buildPerspective(fovRadians, aspect,
                MinecraftUtils.getCameraNear(), MinecraftUtils.getCameraFar());
    }

    private static float[] buildPerspective(float fovRadians, float aspect, float near, float far) {
        float f = (float) (1.0 / Math.tan(fovRadians / 2.0));
        float range = near - far;
        return new float[]{
                f / aspect, 0f, 0f, 0f,
                0f, f, 0f, 0f,
                0f, 0f, far / range, near * far / range,
                0f, 0f, -1f, 0f
        };
    }
}
#else
public final class D3D12FrameGeneration {
    private D3D12FrameGeneration() {
    }
}
#endif
