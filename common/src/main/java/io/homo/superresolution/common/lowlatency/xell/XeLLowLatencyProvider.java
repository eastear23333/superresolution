/*
 * Super Resolution
 * Copyright (c) 2026. 187J3X1-114514
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package io.homo.superresolution.common.lowlatency.xell;

import io.homo.superresolution.api.registry.LowLatencyMarker;
import io.homo.superresolution.api.registry.LowLatencyProvider;
import io.homo.superresolution.common.SuperResolution;
import io.homo.superresolution.common.framegeneration.D3D12FrameGeneration;
import io.homo.superresolution.common.lowlatency.LowLatency;
import io.homo.superresolution.common.presentation.d3d12.D3D12PresentationFeature;
import io.homo.superresolution.core.NativeLibManager;
import io.homo.superresolution.core.SuperResolutionConstants;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Intel XeLL backend for the D3D12 presentation.
 *
 * <p>XeLL is the Intel counterpart to NVIDIA Reflex: it only runs under the D3D12
 * presentation (Reflex is Vulkan-only), stamps latency markers on the D3D12 device
 * and sleeps the render thread each frame. The provider creates a XeLL context on
 * the D3D12 presentation device and keeps the context handle alive so the XeSS-FG
 * backend in Wisteria can connect to it later via {@code xefgSwapChainSetLatencyReduction}
 * (XeLL must outlive the XeSS-FG context).</p>
 *
 * <p>Markers and sleep are driven by the shared low-latency hooks ({@link LowLatency});
 * D3D12 presentation also stamps present-start/end since those live in the swapchain
 * present path rather than the Vulkan swapchain.</p>
 */
public final class XeLLowLatencyProvider implements LowLatencyProvider {
    /** xell_latency_marker_type_t values (xell.h). */
    private static final int XELL_SIMULATION_START = 0;
    private static final int XELL_SIMULATION_END = 1;
    private static final int XELL_RENDERSUBMIT_START = 2;
    private static final int XELL_RENDERSUBMIT_END = 3;
    private static final int XELL_PRESENT_START = 4;
    private static final int XELL_PRESENT_END = 5;

    /** xell_sleep_params_t — uint32 minimumIntervalUs @0, uint32 bitfield @4. */
    private static final StructLayout SLEEP_PARAMS_LAYOUT = MemoryLayout.structLayout(
            JAVA_INT.withName("minimumIntervalUs"),
            JAVA_INT.withName("flags"));

    private static final Linker LINKER = Linker.nativeLinker();

    private final Arena arena;
    private final MemorySegment context;
    private final MethodHandle destroyContext;
    private final MethodHandle setSleepMode;
    private final MethodHandle sleep;
    private final MethodHandle addMarkerData;

    /** Last applied minimum frame interval; refresh() only re-applies when it changes. */
    private volatile int appliedFrameIntervalUs = Integer.MIN_VALUE;

    public XeLLowLatencyProvider() {
        Arena arena = null;
        MemorySegment context = MemorySegment.NULL;
        MethodHandle destroyContext = null;
        MethodHandle setSleepMode = null;
        MethodHandle sleep = null;
        MethodHandle addMarkerData = null;
        try {
            Path dllPath = NativeLibManager.LIB_SUPER_RESOLUTION_XELL
                    .getTargetPath(SuperResolutionConstants.NATIVE_LIBRARIES_DIR.getPath())
                    .toAbsolutePath();
            if (!Files.isReadable(dllPath)) {
                throw new IllegalStateException("libxell.dll is missing: " + dllPath);
            }
            MemorySegment device = D3D12PresentationFeature.contextDevice();
            if (device == null) {
                throw new IllegalStateException(
                        "D3D12 presentation is not initialized; cannot create XeLL context");
            }

            arena = Arena.ofShared();
            SymbolLookup library = SymbolLookup.libraryLookup(dllPath, arena);
            MethodHandle createContext = LINKER.downcallHandle(
                    library.find("xellD3D12CreateContext").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            destroyContext = LINKER.downcallHandle(
                    library.find("xellDestroyContext").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS));
            setSleepMode = LINKER.downcallHandle(
                    library.find("xellSetSleepMode").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            sleep = LINKER.downcallHandle(
                    library.find("xellSleep").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
            addMarkerData = LINKER.downcallHandle(
                    library.find("xellAddMarkerData").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));

            MemorySegment pContext = arena.allocate(ADDRESS);
            int result = (int) createContext.invokeExact(device, pContext);
            if (result != 0) {
                throw new IllegalStateException(
                        "xellD3D12CreateContext failed with code " + result
                                + " (GPU or driver may not support XeLL)");
            }
            context = pContext.get(ADDRESS, 0);
            appliedFrameIntervalUs = LowLatency.frameLimitUs();
            applySleepMode(arena, context, setSleepMode, appliedFrameIntervalUs);
            XeLLowLatency.setContext(context);
            SuperResolution.LOGGER.info("[XeLL] context created");
        } catch (Throwable throwable) {
            SuperResolution.LOGGER.error("[XeLL] initialization failed", throwable);
            if (arena != null) {
                arena.close();
            }
            arena = null;
            context = MemorySegment.NULL;
            destroyContext = null;
            setSleepMode = null;
            sleep = null;
            addMarkerData = null;
        }
        this.arena = arena;
        this.context = context;
        this.destroyContext = destroyContext;
        this.setSleepMode = setSleepMode;
        this.sleep = sleep;
        this.addMarkerData = addMarkerData;
    }

    @Override
    public void setMarker(LowLatencyMarker marker) {
        if (context == null || context.address() == 0L) {
            return;
        }
        int xellMarker;
        switch (marker) {
            case SIMULATION_START -> xellMarker = XELL_SIMULATION_START;
            case SIMULATION_END -> xellMarker = XELL_SIMULATION_END;
            case RENDER_SUBMIT_START -> xellMarker = XELL_RENDERSUBMIT_START;
            case RENDER_SUBMIT_END -> xellMarker = XELL_RENDERSUBMIT_END;
            case PRESENT_START -> xellMarker = XELL_PRESENT_START;
            case PRESENT_END -> xellMarker = XELL_PRESENT_END;
            default -> {
                // TRIGGER_FLASH / LATENCY_PING have no XeLL equivalent.
                return;
            }
        }
        try {
            int result = (int) addMarkerData.invokeExact(context, frameId(), xellMarker);
            if (result != 0) {
                SuperResolution.LOGGER.debug("[XeLL] addMarkerData returned {}", result);
            }
        } catch (Throwable throwable) {
            SuperResolution.LOGGER.warn("[XeLL] addMarkerData failed", throwable);
        }
    }

    @Override
    public void sleep() {
        if (context == null || context.address() == 0L) {
            return;
        }
        try {
            int result = (int) sleep.invokeExact(context, frameId());
            if (result != 0) {
                SuperResolution.LOGGER.debug("[XeLL] sleep returned {}", result);
            }
        } catch (Throwable throwable) {
            SuperResolution.LOGGER.warn("[XeLL] sleep failed", throwable);
        }
    }

    @Override
    public void refresh() {
        if (context == null || context.address() == 0L || arena == null) {
            return;
        }
        int intervalUs = LowLatency.frameLimitUs();
        if (intervalUs == appliedFrameIntervalUs) {
            return;
        }
        appliedFrameIntervalUs = intervalUs;
        applySleepMode(arena, context, setSleepMode, intervalUs);
    }

    @Override
    public void release() {
        // XeSS-FG must be torn down before the XeLL context dies: its proxy Present uses
        // the connected XeLL context (and this shared libxell.dll) even in passthrough
        // mode, so destroying XeLL underneath a live takeover crashes the next Present
        // (DEP violation; the XeSS-FG guide requires XeFG destroy before XeLL destroy).
        // No-op when no takeover is active; idempotent for the game-shutdown path.
        D3D12FrameGeneration.teardownForLowLatencyShutdown();
        if (context != null && context.address() != 0L && destroyContext != null) {
            try {
                int result = (int) destroyContext.invokeExact(context);
                if (result != 0) {
                    SuperResolution.LOGGER.debug("[XeLL] destroyContext returned {}", result);
                }
            } catch (Throwable throwable) {
                SuperResolution.LOGGER.warn("[XeLL] destroyContext failed", throwable);
            }
        }
        XeLLowLatency.setContext(null);
        if (arena != null) {
            arena.close();
        }
        SuperResolution.LOGGER.info("[XeLL] context destroyed");
    }

    /** The current SR frame id truncated to XeLL's uint32 frame counter. */
    private static int frameId() {
        return (int) (LowLatency.currentLatencyFrameId() & 0xFFFFFFFFL);
    }

    private static void applySleepMode(Arena arena, MemorySegment ctx, MethodHandle setSleepMode,
                                       int frameIntervalUs) {
        if (ctx == null || ctx.address() == 0L) {
            return;
        }
        MemorySegment params = arena.allocate(SLEEP_PARAMS_LAYOUT);
        SLEEP_PARAMS_LAYOUT.varHandle(
                        MemoryLayout.PathElement.groupElement("minimumIntervalUs"))
                .set(params, 0L, frameIntervalUs);
        // bit0 = bLowLatencyMode, bit1 = bLowLatencyBoost (XeLL has no boost today).
        SLEEP_PARAMS_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("flags"))
                .set(params, 0L, 1);
        try {
            int result = (int) setSleepMode.invokeExact(ctx, params);
            if (result != 0) {
                SuperResolution.LOGGER.debug("[XeLL] setSleepMode returned {}", result);
            }
        } catch (Throwable throwable) {
            SuperResolution.LOGGER.warn("[XeLL] setSleepMode failed", throwable);
        }
    }
}
