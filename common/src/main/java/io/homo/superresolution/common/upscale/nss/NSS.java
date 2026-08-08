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
package io.homo.superresolution.common.upscale.nss;

import io.homo.superresolution.api.InitializationDescription;
import io.homo.superresolution.common.SuperResolution;
import io.homo.superresolution.common.config.SuperResolutionConfig;
import io.homo.superresolution.common.config.enums.NssModelSize;
import io.homo.superresolution.common.minecraft.handler.RenderHandlerManager;
import io.homo.superresolution.common.upscale.SRApiAlgorithm;
import io.homo.superresolution.common.upscale.VulkanInteropAlgorithm;
import io.homo.superresolution.core.NativeLibManager;
import io.homo.superresolution.core.RenderSystems;
import io.homo.superresolution.core.SuperResolutionConstants;
import io.homo.superresolution.core.graphics.vulkan.VkReflectionHelper;
import io.homo.superresolution.core.graphics.vulkan.VulkanCommandBuffer;
import io.homo.superresolution.core.graphics.vulkan.VulkanDevice;
import io.homo.superresolution.srapi.*;
import org.joml.Vector2f;
import org.joml.Vector2i;

import java.nio.file.Path;
import java.util.EnumSet;

/**
 * Arm Neural Super Sampling algorithm.
 *
 * Uses the FFX API (ffxCreateContext / ffxDispatch) via SRAPI provider.
 * Neural network model size (L/M/S) is configurable via NSSSpecialConfig.
 * L = High model (36ch KPN, full-res)
 * M = Balanced model (16ch KPN, half-res, Catmull)
 * S = Performance model (16ch KPN, half-res, no Catmull)
 */
public class NSS extends SRApiAlgorithm {

    @Override
    protected void recreateSRApiContext(InitializationDescription desc) {
        if (NativeLibManager.LIB_SUPER_RESOLUTION_NSS == null) {
            return;
        }
        Path lib = NativeLibManager.LIB_SUPER_RESOLUTION_NSS
                .getTargetPath(SuperResolutionConstants.NATIVE_LIBRARIES_DIR.getPath());
        if (!(lib.toFile().isFile() && lib.toFile().canRead())) {
            return;
        }

        if (context != null) {
            if (context.nativePtr > 0) {
                context.destroy();
            }
        }
        SuperResolutionNativeAPI.srLoadUpscaleProvidersFromLibrary(
                lib.toAbsolutePath().toString(),
                "srGetNSSUpscaleProviders",
                "srGetNSSUpscaleProvidersCount"
        );
        try (SRUpscaleProvider provider = new SRUpscaleProvider(0)) {
            SuperResolution.LOGGER.info("'srGetUpscaleProvider' return code: {}",
                    SuperResolutionNativeAPI.srGetUpscaleProvider(
                            provider,
                            0x8000006
                    )
            );

            this.context = new SRUpscaleContext(0);
            VulkanDevice vulkanDevice = RenderSystems.vulkan().device();
            VulkanCommandBuffer commandBuffer = vulkanDevice.createCommandBuffer();
            EnumSet<SRUpscaleContextCreateFlags> flags = EnumSet.noneOf(SRUpscaleContextCreateFlags.class);
            // NSS requires HDR input
            flags.add(SRUpscaleContextCreateFlags.ENABLE_HDR);
            // Minecraft 使用反向深度 (1.0=near, 0.0=far), 与 FSR2 的 enableDepthInverted(true) 一致
            flags.add(SRUpscaleContextCreateFlags.ENABLE_DEPTH_INVERTED);
            if (desc.isMotionJittered()) {
                flags.add(SRUpscaleContextCreateFlags.ENABLE_MOTION_VECTORS_JITTERED);
            }
            try (
                    SRCreateUpscaleContextDesc upscaleContextDesc = SRCreateUpscaleContextDesc.createVulkan(
                            new SRVulkanDeviceInfo(
                                    RenderSystems.vulkan().getVulkanInstance(),
                                    vulkanDevice.getPhysicalDevice(),
                                    vulkanDevice.getVkDevice(),
                                    commandBuffer.getNativeCommandBuffer(),
                                    vulkanDevice.getVkDevice().getCapabilities().vkGetDeviceProcAddr,
                                    VkReflectionHelper.getVkGetInstanceProcAddr()
                            ),
                            new Vector2i(RenderHandlerManager.getScreenWidth(),
                                    RenderHandlerManager.getScreenHeight()),
                            new Vector2i(RenderHandlerManager.getRenderWidth(),
                                    RenderHandlerManager.getRenderHeight()),
                            flags
                    );
                    SRContextExtraParams extraParams = new SRContextExtraParams()
            ) {
                upscaleContextDesc.setExtraParams(extraParams);
                // Pass the NSS SDK DLL path to the native adapter
                extraParams.setString(
                        "NSS_DLL_PATH",
                        SuperResolutionConstants.NATIVE_LIBRARIES_DIR.getPath().resolve("ngsdk_windows_x64.dll").toAbsolutePath().toString()
                );
                // Pass the model size selection (L=0, M=1, S=2)
                NssModelSize modelSize = SuperResolutionConfig.SPECIAL.NSS.MODEL_SIZE.get();
                extraParams.setInt32("NSS_QUALITY_MODE", modelSize.getCode());
                // NSS requires quantized (int8) mode
                extraParams.setBool("NSS_QUANTIZED", true);
                // NSS requires HDR
                extraParams.setBool("NSS_HDR", true);
                // Let the SDK manage history internally
                extraParams.setBool("NSS_MANAGE_HISTORY", true);

                commandBuffer.begin();
                SRReturnCode createUpscaleContextCode = SuperResolutionNativeAPI.srCreateUpscaleContext(context, provider, upscaleContextDesc);
                SRReturnCode initUpscaleContextCode = createUpscaleContextCode == SRReturnCode.OK
                        ? SuperResolutionNativeAPI.srInitUpscaleContext(context)
                        : createUpscaleContextCode;
                commandBuffer.end();
                if (createUpscaleContextCode != SRReturnCode.OK) {
                    SuperResolution.LOGGER.error("Failed to create NSS upscale context. Return code: {}", createUpscaleContextCode);
                    throw new RuntimeException("Failed to create NSS upscale context");
                }
                if (initUpscaleContextCode != SRReturnCode.OK) {
                    SuperResolution.LOGGER.error("Failed to initialize NSS upscale context. Return code: {}", initUpscaleContextCode);
                    throw new RuntimeException("Failed to initialize NSS upscale context");
                }
                vulkanDevice.submitCommandBuffer(commandBuffer);
                commandBuffer.waitForFence();
            } finally {
                commandBuffer.destroy();
            }
        }
    }

    @Override
    protected void destroySRApiContext() {
        if (context != null) {
            SRReturnCode code = context.destroy();
            if (code != SRReturnCode.OK) {
                SuperResolution.LOGGER.error("Failed to destroy NSS upscale context. Return code: {}", code);
                throw new RuntimeException("Failed to destroy NSS upscale context");
            }
            context = null;
        }
    }

    @Override
    public void dispatchSRApiContext(
            VulkanCommandBuffer commandBuffer,
            VulkanInteropAlgorithm.InFlightFrameResourcesSet inFlightFrameResourcesSet
    ) {
        try (SRDispatchUpscaleDesc desc = new SRDispatchUpscaleDesc()) {
            desc.setCommandBuffer(SRDispatchCommandBufferInfo.createVulkan(commandBuffer.getNativeCommandBuffer()));
            desc.setColor(new SRTextureResource(inFlightFrameResourcesSet.inputColorVkTexture));
            desc.setDepth(new SRTextureResource(inFlightFrameResourcesSet.inputDepthVkTexture));
            desc.setMotionVectors(new SRTextureResource(inFlightFrameResourcesSet.inputMotionVectorsVkTexture));
            desc.setExposure(new SRTextureResource(inFlightFrameResourcesSet.inputExposureVkTexture));
            desc.setOutput(new SRTextureResource(inFlightFrameResourcesSet.outputColorVkTexture));
            desc.setJitterOffset(new Vector2f(inFlightFrameResourcesSet.frameData.jitterOffset()));
            // NSS 期望后向运动矢量, shader 中 reproj_uv = uv + motion*scale*InvDims (ffx_nss_preprocess.h:
            // "Motion is backward direction in pixel space to match FSR/ASR")。
            // 模组 MV 纹理约定与 FSR/XeSS 相同 (XeSS/FfxFSR 都用正数 renderSize 作 scale),
            // 因此这里用正数 renderSize 与其它算法保持一致。
            desc.setMotionVectorScale(new Vector2f(
                    inFlightFrameResourcesSet.frameData.renderSize().x,
                    inFlightFrameResourcesSet.frameData.renderSize().y
            ));
            desc.setRenderSize(new Vector2i(inFlightFrameResourcesSet.frameData.renderWidth(), inFlightFrameResourcesSet.frameData.renderHeight()));
            desc.setUpscaleSize(new Vector2i(inFlightFrameResourcesSet.frameData.screenWidth(), inFlightFrameResourcesSet.frameData.screenHeight()));
            desc.setFrameTimeDelta(inFlightFrameResourcesSet.frameData.frameTimeDelta());
            desc.setEnableSharpening(false);
            desc.setPreExposure(inFlightFrameResourcesSet.frameData.preExposure());
            desc.setCameraNear(inFlightFrameResourcesSet.frameData.cameraNear());
            desc.setCameraFar(inFlightFrameResourcesSet.frameData.cameraFar());
            desc.setCameraFovAngleVertical(inFlightFrameResourcesSet.frameData.verticalFov());
            desc.setViewSpaceToMetersFactor(1.0f);
            desc.setReset(consumeHistoryReset());
            desc.setFlags(0);
            SRReturnCode code = SuperResolutionNativeAPI.srDispatchUpscale(context, desc);
            if (code != SRReturnCode.OK) {
                SuperResolution.LOGGER.error("Failed to dispatch NSS upscale context. Return code: {}", code);
            }
        }
    }
}
