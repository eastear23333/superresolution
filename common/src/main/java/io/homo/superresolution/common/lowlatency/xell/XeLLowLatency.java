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

import io.homo.superresolution.common.SuperResolution;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

/**
 * Global access to the active Intel XeLL context.
 *
 * <p>{@link XeLLowLatencyProvider} creates the context once and registers it here; the
 * context is deliberately <em>resident</em>: low-latency toggles release the provider but
 * keep the context alive, because Intel XeLL cannot register its app queue on a second
 * in-process context (xellSetAppQueue fails with -1000) and the doomed XeSS-FG
 * re-takeover that followed used to remove the D3D12 device and freeze the window.
 * Later provider instances reuse the resident context. The XeSS-FG backend (Wisteria)
 * reads it to connect its swap-chain context through {@code xefgSwapChainSetLatencyReduction};
 * the XeLL context must outlive the XeSS-FG context, so consumers must not destroy it
 * here — {@link #destroy()} is only called on real shutdown (game exit or the D3D12
 * presentation closing, after XeSS-FG is already torn down).</p>
 */
public final class XeLLowLatency {
    private static volatile MemorySegment context = MemorySegment.NULL;
    /**
     * Whether a XeLL context has been created and destroyed in this process. Intel XeLL
     * cannot register its app queue on a second context in the same process
     * (xellSetAppQueue fails with -1000), so after the first teardown XeLL/XeSS-FG must
     * stay off until the game restarts — re-creating them leads to a failed XeSS-FG
     * swap-chain takeover that can remove the D3D12 device.
     */
    private static volatile boolean everDestroyed;
    /** Owner arena of the resident context (and its destroy handle); kept open across toggles. */
    private static volatile Arena arena;
    /** Downcall handle that destroys the resident context; valid while {@link #arena} is open. */
    private static volatile MethodHandle destroyContext;

    private XeLLowLatency() {
    }

    static void setContext(MemorySegment ctx) {
        context = ctx == null ? MemorySegment.NULL : ctx;
    }

    /** Records that a XeLL context was destroyed; called on real shutdown. */
    static void markDestroyed() {
        everDestroyed = true;
    }

    /**
     * Hands the resident context's destroy path to this holder. Called by the provider
     * that created the context; first call wins so reuse providers never replace it.
     * A fresh context created after {@link #destroy()} re-attaches its own path.
     */
    static void attach(Arena ownerArena, MethodHandle destroyHandle) {
        if (destroyContext == null && arena == null) {
            arena = ownerArena;
            destroyContext = destroyHandle;
        }
    }

    /**
     * Whether a XeLL context was already torn down this session (blocks re-creation).
     */
    public static boolean wasDestroyed() {
        return everDestroyed;
    }

    /**
     * The active XeLL context handle (opaque), or {@link MemorySegment#NULL} when XeLL
     * is not active. Safe to call from any thread.
     */
    public static MemorySegment context() {
        return context;
    }

    /**
     * Destroys the resident XeLL context and releases its owner arena. Only called on
     * real shutdown — game exit or the D3D12 presentation closing — after XeSS-FG has
     * been torn down first (XeFG destroy must precede XeLL destroy; the proxy Present
     * uses the connected XeLL context even in passthrough). Idempotent: no-ops when no
     * context is resident or the destroy path was already released.
     */
    public static synchronized void destroy() {
        MemorySegment ctx = context;
        MethodHandle destroyHandle = destroyContext;
        Arena ownerArena = arena;
        boolean destroyed = false;
        boolean failed = false;
        if (ctx != null && ctx.address() != 0L && destroyHandle != null) {
            try {
                int result = (int) destroyHandle.invokeExact(ctx);
                if (result != 0) {
                    SuperResolution.LOGGER.debug("[XeLL] destroyContext returned {}", result);
                }
                context = MemorySegment.NULL;
                markDestroyed();
                destroyed = true;
            } catch (Throwable throwable) {
                // Keep the arena and destroy path alive so a later retry can still run.
                SuperResolution.LOGGER.warn("[XeLL] destroyContext failed", throwable);
                failed = true;
            }
        }
        if (!failed && ownerArena != null) {
            ownerArena.close();
            arena = null;
            destroyContext = null;
        }
        if (destroyed) {
            SuperResolution.LOGGER.info("[XeLL] context destroyed");
        } else {
            SuperResolution.LOGGER.debug("[XeLL] destroy: no resident context");
        }
    }
}
