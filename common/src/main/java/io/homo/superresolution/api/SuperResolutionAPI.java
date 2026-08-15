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

package io.homo.superresolution.api;

import io.homo.superresolution.api.registry.AlgorithmDescription;
import io.homo.superresolution.common.SuperResolution;
import io.homo.superresolution.common.lowlatency.xell.XeLLowLatency;
import io.homo.superresolution.common.minecraft.handler.RenderHandlerManager;
import io.homo.superresolution.core.graphics.impl.framebuffer.IFrameBuffer;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.IEventBus;

import java.lang.foreign.MemorySegment;

public class SuperResolutionAPI {
    public static final IEventBus EVENT_BUS = BusBuilder.builder().build();

    public static IFrameBuffer getOriginMinecraftFrameBuffer() {
        return RenderHandlerManager.getOriginRenderTarget();
    }

    public static IFrameBuffer getMinecraftFrameBuffer() {
        return RenderHandlerManager.getRenderTarget();
    }

    public static int getScreenWidth() {
        return RenderHandlerManager.getScreenWidth();
    }

    public static int getScreenHeight() {
        return RenderHandlerManager.getScreenHeight();
    }

    public static int getRenderWidth() {
        return RenderHandlerManager.getRenderWidth();
    }

    public static int getRenderHeight() {
        return RenderHandlerManager.getRenderHeight();
    }

    public static AlgorithmDescription<?> getCurrentAlgorithmDescription() {
        return SuperResolution.algorithmDescription;
    }

    public static AbstractAlgorithm getCurrentAlgorithm() {
        return SuperResolution.currentAlgorithm;
    }

    /**
     * The active Intel XeLL context handle (opaque), or {@link MemorySegment#NULL} when
     * XeLL is not active. The XeSS-FG backend connects its swap-chain context to it via
     * {@code xefgSwapChainSetLatencyReduction}. XeLL must outlive the XeSS-FG context, so
     * callers must not destroy it through this API.
     */
    public static MemorySegment getXellContext() {
        return XeLLowLatency.context();
    }
}
