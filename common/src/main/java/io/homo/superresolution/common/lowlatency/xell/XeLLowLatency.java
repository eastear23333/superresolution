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

import java.lang.foreign.MemorySegment;

/**
 * Global access to the active Intel XeLL context.
 *
 * <p>{@link XeLLowLatencyProvider} registers the context here while it owns low-latency
 * handling and clears it on release. The XeSS-FG backend (Wisteria) reads it to connect
 * its swap-chain context through {@code xefgSwapChainSetLatencyReduction}; the XeLL
 * context must outlive the XeSS-FG context, so consumers must not destroy it here.</p>
 */
public final class XeLLowLatency {
    private static volatile MemorySegment context = MemorySegment.NULL;

    private XeLLowLatency() {
    }

    static void setContext(MemorySegment ctx) {
        context = ctx == null ? MemorySegment.NULL : ctx;
    }

    /**
     * The active XeLL context handle (opaque), or {@link MemorySegment#NULL} when XeLL
     * is not active. Safe to call from any thread.
     */
    public static MemorySegment context() {
        return context;
    }
}
