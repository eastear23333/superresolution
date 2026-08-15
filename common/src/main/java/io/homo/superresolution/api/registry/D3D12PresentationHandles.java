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
 * Opaque D3D12 presentation handles passed to a {@link D3D12FrameGenerationProvider}
 * when the D3D12 presentation activates. Exposed as raw addresses so the API stays
 * independent of the FFM bindings in the core graphics package.
 */
public record D3D12PresentationHandles(
        /** ID3D12Device* */
        long device,
        /** ID3D12CommandQueue* */
        long queue,
        /** HWND */
        long hwnd,
        /** IDXGISwapChain* (the presentation swap chain the provider may take over) */
        long swapchain,
        /** IDXGIFactory2* */
        long factory,
        /** xell_context_handle_t (XeLL context, must outlive the provider) */
        long xellContext,
        /** Back buffer width */
        int width,
        /** Back buffer height */
        int height
) {
}
