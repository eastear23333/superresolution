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
import io.homo.superresolution.core.graphics.impl.framebuffer.FrameBufferAttachmentType;
import io.homo.superresolution.core.graphics.impl.framebuffer.IFrameBuffer;
import io.homo.superresolution.core.graphics.impl.texture.ITexture;
import io.homo.superresolution.core.graphics.impl.texture.TextureDescription;
import io.homo.superresolution.core.graphics.impl.texture.TextureFormat;
import io.homo.superresolution.core.graphics.impl.texture.TextureType;
import io.homo.superresolution.core.graphics.impl.texture.TextureUsages;
import io.homo.superresolution.core.graphics.opengl.Gl;
import io.homo.superresolution.core.graphics.opengl.texture.GlTexture2D;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.joml.Vector2f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL41;

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
    /** Pre-UI shader-compat color, tagged as XeSS-FG's HUD-less color for UI composition. */
    private static ITexture cachedHudlessColor;
    /**
     * Full-resolution pre-UI scene snapshot taken right before GUI compositing
     * (FogRenderer.endFrame), used as XeSS-FG's HUDLESS_COLOR input. The shader-compat
     * dispatch color is the low-resolution pre-upscale render target and must NOT feed
     * the SDK (it interpolates at back-buffer resolution; a low-res input shows up as a
     * faint low-res overlay whose size tracks the render-scale factor).
     */
    private static ITexture cachedSceneSnapshot;
    private static int snapshotWidth = -1;
    private static int snapshotHeight = -1;
    /**
     * Render jitter (render-resolution pixels) from the last dispatch event. XeSS-FG
     * samples the low-res motion vectors at the jittered pixel positions, so the SDK
     * must receive the same jitter the scene was rendered with.
     */
    private static Vector2f cachedJitterOffset = new Vector2f(0.0f, 0.0f);
    /** Whether UI composition is currently enabled on the provider. */
    private static boolean uiCompositionApplied;
    /**
     * Last frame time delta from the dispatch event, in NANOSECONDS
     * (PerformanceTracker.getLastResultCPU("Frame") stores System.nanoTime() deltas).
     * Converted to milliseconds right before tagging the XeSS-FG frame constants, which
     * expect frameRenderTime in ms (the previous code passed raw nanoseconds — a 1e6x
     * overshoot that pushed the SDK's interpolation timing far outside the real frame
     * cadence and contributed ghost-like overlays in low-texture/dark regions).
     */
    private static float cachedFrameTimeDelta;
    /**
     * True vertical FOV (degrees) of the render projection, taken from the dispatch event
     * (Iris gbufferProjection, m11-derived) so the rebuilt XeSS-FG projection matches the
     * actual depth/motion-vector rendering instead of the vanilla-only fallback FOV.
     * &lt;=0 when no dispatch has arrived yet (fall back to MinecraftCameraState.fov).
     */
    private static float cachedFovDegrees = -1f;
    /** Last multiplier pushed to the provider; re-synced from the config each present. */
    private static int appliedInterpolatedFrames = -1;
    /**
     * Extent of the shared depth/motion-vector textures the current frame's inputs were
     * flipped into: the dispatch textures' native size on the low-res path, or the
     * presentation extent on the fallback path (dispatch depth/MV sizes disagree).
     * Zero until the first frame with inputs decides it.
     */
    private static int inputExtentWidth;
    private static int inputExtentHeight;
    /** Frame-input extent last pushed to the provider's tagged resource size. */
    private static int reportedInputWidth;
    private static int reportedInputHeight;
    /** HUD-less extent last pushed to the provider (always the presentation extent). */
    private static int reportedHudlessWidth = -1;
    private static int reportedHudlessHeight = -1;
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
        boolean hudlessPresent = resources.colorTexture() != null;
        boolean inputsChanged = (cachedDepth != null) != depthPresent
                || (cachedMotionVector != null) != mvPresent
                || (cachedHudlessColor != null) != hudlessPresent;
        cachedDepth = resources.depthTexture();
        cachedMotionVector = resources.motionVectorsTexture();
        cachedHudlessColor = resources.colorTexture();
        cachedFrameTimeDelta = event.getDispatchResource().frameTimeDelta();
        cachedJitterOffset = new Vector2f(event.getDispatchResource().jitterOffset());
        float projectedFov = event.getDispatchResource().verticalFov();
        if (!Float.isNaN(projectedFov) && projectedFov > 0f) {
            cachedFovDegrees = projectedFov;
        }
        if (inputsChanged) {
            SuperResolution.LOGGER.info("[D3D12] XeSS-FG dispatch: depth={} motionVector={} hudlessColor={}",
                    depthPresent, mvPresent, hudlessPresent);
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
        // Session quarantine: XeLL was already torn down this session and cannot re-register
        // its app queue in-process (xellSetAppQueue -1000). A takeover attempt would fail
        // (xefgSwapChainD3D12InitFromSwapChainDesc -15) and the failure restore has removed
        // the D3D12 device (0x887A0005), disabling the presentation and freezing the
        // GLFW_NO_API window. Stay in passthrough on the restored plain swap chain.
        if (XeLLowLatency.wasDestroyed()) {
            pendingInit = false;
            SuperResolution.LOGGER.info(
                    "[D3D12] XeSS-FG skipped: XeLL torn down this session; restart to re-enable");
            return;
        }
        // D3D12 activation flips which FG backends are supported; the registry caches
        // requirement results, so drop the stale cache before consulting it. Runs even
        // when the mode is OFF: the UI capability query (FrameGeneration.isSupported)
        // relies on the refreshed cache to enable the frame-generation multiplier options
        // for XeSS-FG while the presentation is up.
        FrameGenerationRegistry.clearSupportCache();
        // Frame-generation mode OFF: do not take over. The SDK proxy presents at ~1 fps
        // in passthrough (enabled=false), so the plain swap chain stays until the mode
        // flips on (beforePresent retries the deferred initialize each frame).
        if (!FrameGeneration.displayedMode().isEnabled()) {
            pendingInit = true;
            SuperResolution.LOGGER.info("[D3D12] XeSS-FG deferred: frame generation mode is OFF");
            return;
        }
        String configured = SuperResolutionConfig.getFrameGenerationProvider();
        if (!XESS_FG_GROUP_ID.equals(configured)) {
            // Arm the deferred retry: selecting XeSS-FG mid-session must take over
            // without a restart (beforePresent re-checks the config each frame).
            pendingInit = true;
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
            reportedInputWidth = 0;
            reportedInputHeight = 0;
            reportedHudlessWidth = -1;
            reportedHudlessHeight = -1;
            lastCameraStateValid = false;
            uiCompositionApplied = false;
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
        // If initialization was deferred (XeLL context not ready, XeSS-FG selected
        // mid-session, or the frame-generation mode was OFF), retry now that the
        // low-latency renegotiation has run, the configured provider matches and the
        // mode is on; the config checks keep the per-frame retry cheap.
        if (provider == null && pendingInit && presentation != null
                && XESS_FG_GROUP_ID.equals(SuperResolutionConfig.getFrameGenerationProvider())
                && XeLLowLatency.context().address() != 0L
                && FrameGeneration.displayedMode().isEnabled()) {
            initialize(presentation);
        }
        D3D12FrameGenerationProvider active = provider;
        if (active == null) {
            return;
        }
        // Frame-generation mode switched off: release the takeover and restore the plain
        // swap chain. The SDK proxy presents at ~1 fps in passthrough (enabled=false),
        // so an off mode must not keep the proxy on the window; re-takeover re-arms from
        // the deferred retry above when the mode flips back on.
        if (!FrameGeneration.displayedMode().isEnabled()) {
            releaseTakeover("frame generation mode OFF");
            return;
        }
        // Own per-present counter for the XeSS-FG frame id (see field docs).
        int xefgFrameId = (int) (xefgPresentCounter++ & 0xFFFFFFFFL);
        try {
            syncConfiguredState(active);
            // Toggle UI composition with the HUD-less color's availability: interpolating
            // the UI itself (semi-transparent Minecraft HUD) produces jelly-like warping.
            boolean hudlessAvailable = cachedSceneSnapshot != null && presentation != null;
            if (hudlessAvailable != uiCompositionApplied) {
                active.setUiCompositionEnabled(hudlessAvailable);
                uiCompositionApplied = hudlessAvailable;
                SuperResolution.LOGGER.info("[D3D12] XeSS-FG UI composition {}",
                        hudlessAvailable ? "enabled (scene snapshot tagged)" : "disabled");
            }
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
                        cachedJitterOffset.x, cachedJitterOffset.y, 1.0f, 1.0f,
                        cameraDiscontinuity(), frameRenderTimeMs(cachedFrameTimeDelta),
                        presentation.depthTexture().address(),
                        presentation.mvTexture().address(),
                        hudlessAvailable ? presentation.hudlessTexture().address() : 0L);
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
        // The frame-input extent was decided in writeFrameInputs (the dispatch textures'
        // native size, or the presentation extent on the fallback path); the HUD-less
        // extent always tracks the presentation (back buffer) extent per the SDK guide.
        if (inputExtentWidth != reportedInputWidth || inputExtentHeight != reportedInputHeight) {
            reportedInputWidth = inputExtentWidth;
            reportedInputHeight = inputExtentHeight;
            if (reportedInputWidth > 0 && reportedInputHeight > 0) {
                active.updateFrameInputExtent(reportedInputWidth, reportedInputHeight);
            }
        }
        if (presentation.width() != reportedHudlessWidth || presentation.height() != reportedHudlessHeight) {
            reportedHudlessWidth = presentation.width();
            reportedHudlessHeight = presentation.height();
            active.updateHudlessExtent(reportedHudlessWidth, reportedHudlessHeight);
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
     * Copies the cached Iris depth/motion-vector GL textures and the full-resolution scene
     * snapshot into the D3D12 presentation's shared textures. Called from the D3D12 present
     * path while recording the frame's GL work, so the copies land before the shared-fence
     * signal.
     *
     * <p>The depth/motion-vector shared textures are (re)created at the inputs' native
     * dispatch resolution and the SDK tags them at that extent: XeSS-FG then treats them
     * as low-res motion vectors (documented default) and upsamples/dilates internally.
     * Only when the two dispatch textures disagree on size does the fallback tag the
     * presentation extent, matching the old full-resolution blit behavior.</p>
     */
    public static void writeFrameInputs(D3D12PresentationContext presentationContext) {
        ITexture depth = cachedDepth;
        ITexture mv = cachedMotionVector;
        ITexture snapshot = cachedSceneSnapshot;
        boolean inputsWanted = frameGenerationInputsWanted();
        if (inputsWanted && depth != null && mv != null) {
            int depthWidth = depth.getWidth();
            int depthHeight = depth.getHeight();
            int mvWidth = mv.getWidth();
            int mvHeight = mv.getHeight();
            // One extent for both inputs (SDK constraint). A dispatch size larger than
            // the presentation (supersampling) also takes the fallback: XeSS-FG expects
            // motion vectors at or below the target resolution.
            boolean nativeLowRes = depthWidth == mvWidth && depthHeight == mvHeight
                    && depthWidth > 0 && depthHeight > 0
                    && depthWidth <= presentationContext.width()
                    && depthHeight <= presentationContext.height();
            int targetWidth = nativeLowRes ? depthWidth : presentationContext.width();
            int targetHeight = nativeLowRes ? depthHeight : presentationContext.height();
            if (targetWidth != inputExtentWidth || targetHeight != inputExtentHeight) {
                SuperResolution.LOGGER.info(
                        "[D3D12] XeSS-FG frame inputs: depth={}x{} mv={}x{} -> {} extent {}x{}",
                        depthWidth, depthHeight, mvWidth, mvHeight,
                        nativeLowRes ? "native low-res" : "fallback",
                        targetWidth, targetHeight);
            }
            presentationContext.ensureFrameInputResources(targetWidth, targetHeight);
            inputExtentWidth = targetWidth;
            inputExtentHeight = targetHeight;
            InteropResourcesConverter.flipY(depth, presentationContext.depthGlTexture());
            InteropResourcesConverter.flipMotionVectorY(mv, presentationContext.mvGlTexture());
        }
        if (inputsWanted && snapshot != null) {
            InteropResourcesConverter.flipY(snapshot, presentationContext.hudlessGlTexture());
        }
    }

    /**
     * Whether this frame should copy frame-generation inputs into the shared D3D12
     * textures at all. Skips the per-frame copies when XeSS-FG cannot tag this frame
     * anyway (mode disabled, presentation down, or takeover impossible) — previously the
     * flips ran unconditionally and burned GPU time with frame generation off or in menus.
     */
    private static boolean frameGenerationInputsWanted() {
        if (!FrameGeneration.displayedMode().isEnabled() || presentation == null) {
            return false;
        }
        // Armed but not yet taken over: initialize() may succeed in this frame's
        // beforePresent, which tags immediately — the inputs must already be in place.
        return FrameGeneration.displayedMode().isEnabled()
                && (provider != null || (pendingInit
                && XESS_FG_GROUP_ID.equals(SuperResolutionConfig.getFrameGenerationProvider())));
    }

    /**
     * Snapshots the full-resolution pre-UI scene layer (the upscaled origin render target)
     * into a dedicated GL texture. Called from GameRenderer.render right before GUI
     * compositing (FogRenderer.endFrame), mirroring the Vulkan capture point, so XeSS-FG's
     * HUDLESS_COLOR input is a genuine full-resolution HUD-less frame instead of the
     * low-resolution pre-upscale dispatch color.
     */
    public static void captureSceneSnapshot() {
        // A full-resolution copyImageSubData every frame; skip entirely when XeSS-FG
        // cannot consume it (mode disabled or presentation down). Deferred takeovers
        // (pendingInit) still snapshot so the first tagged frame has a HUD-less color.
        if (presentation == null || !FrameGeneration.displayedMode().isEnabled()) {
            return;
        }
        D3D12PresentationContext ctx = presentation;
        if (ctx == null) {
            return;
        }
        try {
            IFrameBuffer origin = SuperResolutionAPI.getOriginMinecraftFrameBuffer();
            ITexture source = origin == null
                    ? null : origin.getTexture(FrameBufferAttachmentType.Color);
            if (source == null || source.handle() == 0) {
                return;
            }
            int width = ctx.width();
            int height = ctx.height();
            if (width <= 0 || height <= 0) {
                return;
            }
            // Same-thread GL DSA copy; the snapshot texture mirrors the back-buffer extent
            // (the flipY compute reads it at the presentation size). While the extents do
            // not match (window resize), keep the previous snapshot instead of a partial copy.
            if (source.getWidth() != width || source.getHeight() != height) {
                return;
            }
            if (cachedSceneSnapshot == null
                    || snapshotWidth != width || snapshotHeight != height) {
                destroySceneSnapshot();
                cachedSceneSnapshot = GlTexture2D.create(
                        TextureDescription.create()
                                .type(TextureType.Texture2D)
                                .width(width)
                                .height(height)
                                .format(TextureFormat.RGBA8)
                                .usages(TextureUsages.create().sampler())
                                .label("SRD3D12SceneSnapshot")
                                .build());
                snapshotWidth = width;
                snapshotHeight = height;
            }
            Gl.DSA.copyImageSubData(
                    (int) source.handle(), GL41.GL_TEXTURE_2D, 0, 0, 0, 0,
                    (int) cachedSceneSnapshot.handle(), GL41.GL_TEXTURE_2D, 0, 0, 0, 0,
                    width, height, 1);
        } catch (Throwable throwable) {
            SuperResolution.LOGGER.warn("[D3D12] XeSS-FG scene snapshot failed", throwable);
            destroySceneSnapshot();
        }
    }

    private static void destroySceneSnapshot() {
        if (cachedSceneSnapshot != null) {
            try {
                cachedSceneSnapshot.destroy();
            } catch (Throwable throwable) {
                SuperResolution.LOGGER.debug(
                        "[D3D12] scene snapshot destroy failed", throwable);
            }
            cachedSceneSnapshot = null;
            snapshotWidth = -1;
            snapshotHeight = -1;
        }
    }

    /**
     * Releases an active XeSS-FG takeover mid-game and restores the plain presentation
     * swap chain. Used when the frame-generation mode flips off: the SDK proxy presents
     * at ~1 fps in passthrough (enabled=false), so an off mode must not keep the proxy
     * on the window. The reverse of {@link #initialize}: destroy the provider first
     * (after dropping the presentation's proxy reference, which the SDK refuses to
     * destroy while aliased), then recreate the presentation swap chain. Re-takeover is
     * re-armed by the per-frame deferred retry in {@link #beforePresent} when the mode
     * flips back on.
     */
    /** Whether the XeSS-FG proxy currently owns the presentation swap chain. */
    public static boolean isTakeoverActive() {
        return provider != null;
    }

    public static void releaseTakeover(String reason) {
        D3D12PresentationContext presentationContext = presentation;
        if (provider == null || presentationContext == null) {
            return;
        }
        try {
            if (enabled) {
                provider.setEnabled(false);
            }
            // Drop our reference to the proxy swap chain before destroying the context
            // (see shutdown(): xefgSwapChainDestroy fails with -19 POINTER_STILL_IN_USE
            // while any proxy reference is alive).
            presentationContext.releaseSwapchain();
            presentationContext.resetPresentSlot();
            provider.shutdownD3D12();
        } catch (Throwable throwable) {
            SuperResolution.LOGGER.warn("[D3D12] XeSS-FG release failed", throwable);
        }
        provider = null;
        enabled = false;
        appliedInterpolatedFrames = -1;
        inputExtentWidth = 0;
        inputExtentHeight = 0;
        reportedInputWidth = 0;
        reportedInputHeight = 0;
        reportedHudlessWidth = -1;
        reportedHudlessHeight = -1;
        try {
            presentationContext.recreateSwapchain(
                    presentationContext.width(), presentationContext.height());
            SuperResolution.LOGGER.info("[D3D12] XeSS-FG released ({}); swap chain restored", reason);
        } catch (Throwable throwable) {
            SuperResolution.LOGGER.error(
                    "[D3D12] XeSS-FG release swap chain restore failed", throwable);
            D3D12PresentationFeature.disableAfterFailure(throwable);
        }
        // Re-arm the deferred retry: beforePresent re-attempts initialize() each frame
        // while pendingInit is set, so flipping the mode back on re-takes the swap chain
        // without a restart. Failing to arm this left the session stuck in "released"
        // after the first OFF -> ON cycle.
        pendingInit = true;
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
            inputExtentWidth = 0;
            inputExtentHeight = 0;
            reportedInputWidth = 0;
            reportedInputHeight = 0;
            reportedHudlessWidth = -1;
            reportedHudlessHeight = -1;
        }
        presentation = null;
        cachedDepth = null;
        cachedMotionVector = null;
        cachedHudlessColor = null;
        destroySceneSnapshot();
        cachedJitterOffset = new Vector2f(0.0f, 0.0f);
        cachedFrameTimeDelta = 0.0f;
        lastCameraStateValid = false;
        uiCompositionApplied = false;
    }

    /**
     * Tears the XeSS-FG takeover down mid-game and restores a plain presentation swap
     * chain. Called right before the XeLL context is destroyed: the proxy Present uses
     * the connected XeLL context (and the shared libxell.dll) even in passthrough mode
     * (frame-generation mode OFF only disables interpolation), so destroying XeLL
     * underneath a live XeSS-FG context crashes the next Present with a DEP violation
     * (the XeSS-FG guide requires destroying XeFG before XeLL).
     *
     * <p>The takeover does NOT come back in this session: XeLL cannot register its app
     * queue on a second in-process context (xellSetAppQueue -1000), and re-arming the
     * retry produced a failed XeSS-FG takeover that removed the D3D12 device
     * (0x887A0005), disabled the presentation and froze the GLFW_NO_API window. A game
     * restart is required to re-enable XeSS-FG.</p>
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
            // Keep the presentation on the restored plain swap chain; do not re-arm the
            // takeover (XeLL cannot re-create in-process, see the class note above).
            presentation = presentationContext;
            pendingInit = false;
            SuperResolution.LOGGER.info(
                    "[D3D12] XeSS-FG torn down before XeLL shutdown; swap chain restored "
                            + "(XeSS-FG stays off until restart)");
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
     * Converts the cached dispatch frame-time delta (nanoseconds) to the milliseconds that
     * XeSS-FG's frameRenderTime constant expects. Non-positive deltas (first frame, or no
     * completed "Frame" sample yet) tag as 0 ms so the SDK treats the frame as the stream
     * start rather than an absurdly long interval.
     */
    private static float frameRenderTimeMs(float frameTimeDeltaNanos) {
        return frameTimeDeltaNanos > 0f ? frameTimeDeltaNanos / 1_000_000.0f : 0f;
    }

    /**
     * Row-major perspective projection (DirectX convention, Z in [0,1]) built from the
     * render FOV. Prefers the dispatch-captured true vertical FOV (from the Iris
     * gbufferProjection that produced the depth/motion-vector buffers); falls back to the
     * vanilla camera FOV while no dispatch has arrived.
     */
    private static float[] projectionMatrix() {
        float fovDegrees = cachedFovDegrees;
        if (fovDegrees <= 0.0f) {
            fovDegrees = MinecraftCameraState.fov;
        }
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
