/*
 * Super Resolution
 * Copyright (c) 2026. 187J3X1-114514
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

package io.homo.superresolution.common.upscale.algo.nss;

import io.homo.superresolution.api.InitializationDescription;
import io.homo.superresolution.common.SuperResolution;
import io.homo.superresolution.common.config.SuperResolutionConfig;
import io.homo.superresolution.common.config.enums.NSSModel;
import io.homo.superresolution.common.minecraft.handler.RenderHandlerManager;
import io.homo.superresolution.common.upscale.SRApiAlgorithm;
import io.homo.superresolution.core.NativeLibManager;
import io.homo.superresolution.core.RenderSystems;
import io.homo.superresolution.common.perf.PerformanceTracker;
import io.homo.superresolution.core.graphics.vulkan.VulkanTimestampProfiler;
import io.homo.superresolution.core.SuperResolutionConstants;
import io.homo.superresolution.core.graphics.impl.FullscreenQuad;
import io.homo.superresolution.core.graphics.impl.buffer.BufferDescription;
import io.homo.superresolution.core.graphics.impl.buffer.BufferUsage;
import io.homo.superresolution.core.graphics.impl.buffer.IBuffer;
import io.homo.superresolution.core.graphics.impl.command.MemoryBarrierType;
import io.homo.superresolution.core.graphics.impl.framebuffer.FramebufferDescription;
import io.homo.superresolution.core.graphics.impl.framebuffer.IFrameBuffer;
import io.homo.superresolution.core.graphics.impl.pipeline.ComputePipeline;
import io.homo.superresolution.core.graphics.impl.pipeline.GraphicsPipeline;
import io.homo.superresolution.core.graphics.impl.pipeline.RenderPass;
import io.homo.superresolution.core.graphics.impl.pipeline.state.CullMode;
import io.homo.superresolution.core.graphics.impl.pipeline.state.DynamicStateFlags;
import io.homo.superresolution.core.graphics.impl.sampler.ISampler;
import io.homo.superresolution.core.graphics.impl.sampler.SamplerBorderColor;
import io.homo.superresolution.core.graphics.impl.sampler.SamplerDescription;
import io.homo.superresolution.core.graphics.impl.sampler.SamplerMipmapMode;
import io.homo.superresolution.core.graphics.impl.shader.IShaderProgram;
import io.homo.superresolution.core.graphics.impl.shader.ShaderDescription;
import io.homo.superresolution.core.graphics.impl.shader.ShaderSource;
import io.homo.superresolution.core.graphics.impl.shader.ShaderType;
import io.homo.superresolution.core.graphics.impl.texture.ITexture;
import io.homo.superresolution.core.graphics.impl.texture.TextureDescription;
import io.homo.superresolution.core.graphics.impl.texture.TextureFilterMode;
import io.homo.superresolution.core.graphics.impl.texture.TextureFormat;
import io.homo.superresolution.core.graphics.impl.texture.TextureType;
import io.homo.superresolution.core.graphics.impl.texture.TextureUsages;
import io.homo.superresolution.core.graphics.impl.texture.TextureWrapMode;
import io.homo.superresolution.core.graphics.impl.vertex.IVertexBuffer;
import io.homo.superresolution.core.graphics.impl.vertex.PrimitiveType;
import io.homo.superresolution.core.graphics.vulkan.VkReflectionHelper;
import io.homo.superresolution.core.graphics.vulkan.VulkanCommandBuffer;
import io.homo.superresolution.core.graphics.vulkan.VulkanCommandDecoder;
import io.homo.superresolution.core.graphics.vulkan.VulkanDevice;
import io.homo.superresolution.srapi.*;
import org.joml.Vector2f;
import org.joml.Vector2i;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.EnumSet;

import static io.homo.superresolution.api.interop.InteropResourceType.*;

/**
 * Arm NSS（Neural Super Sampling）—— DP4A 推理后端 + Java 侧前后处理。
 *
 * <p>完整管线（与 Arm scenario {@code configs/960x540_1920x1080_high_fragment.json}
 * 的命令序列逐条对应）：
 * <pre>
 *   光影（colour / depth / motion，经光影接口由兼容光影提供）
 *        │
 *   [0] 0_depth_scatter_init   compute   清空 R32_UINT 深度散射图
 *   [2] 0_depth_scatter        compute   按运动矢量重投影当前深度 → 上一帧深度估计
 *        │
 *   [4] 1_pre_process          2× MRT    → _LumaDerivOut(RGBA8_SNORM) + _NearestDepthOffsetOut(R8)
 *        │                                + 12ch int8 输入张量（SSBO）
 *        │
 *   [6] nss_dp4a（SRAPI 推理）          → KPN 参数(int8) + 时序反馈张量(int8)
 *        │
 *   [8] temporal_to_texture    compute   int8 时序张量 → RGBA8_SNORM 纹理
 *   [10] 3_post_process        frag      → 本帧超分结果（写入自产 history 纹理）
 *   [12] present_blit          frag      → 互操作 OutputColor
 * </pre>
 *
 * <p>本算法自产、光影不提供的资源：
 * <ul>
 *   <li>{@code depthScatter}（渲染分辨率 / 2，R32_UINT）—— 每帧重算，非乒乓</li>
 *   <li>{@code lumaDerivative}（8 对齐尺寸，RGBA8_SNORM）—— 乒乓</li>
 *   <li>{@code nearestDepthOffset}（8 对齐尺寸，R8_UNORM）—— 本帧内 post 读</li>
 *   <li>{@code temporalTensor}（8 对齐尺寸，int8 SSBO）—— 乒乓；对应纹理亦乒乓</li>
 *   <li>{@code history}（屏幕分辨率，R11G11B10F）—— 乒乓，= 上一帧超分输出</li>
 * </ul>
 *
 * <p><b>质量档</b>：Arm 的 NSS v1_0_1 是<b>架构锁定 2x</b> 的超分器（三档 config 的
 * 输出全是 1920x1080）。三档的差别只在预处理分辨率：HIGH 走全分辨率预处理，
 * MID / LOW 走半分辨率预处理（额外需要一个低分辨率去遮挡掩码 pass）。当前只实现
 * HIGH 档 —— 这也是 native 侧 {@code SRNativeNSS} 目前唯一建立起来的形态。
 */
public class NSS extends SRApiAlgorithm {

    // ────────────────────────────── 尺寸 ──────────────────────────────
    /** 逻辑渲染分辨率（光影输入尺寸）。 */
    private int renderWidth;
    private int renderHeight;
    /**
     * 预处理输出尺寸 = 渲染分辨率 / {@link NSSModel#getPreprocessDownscale()}。
     * HIGH 档等于渲染分辨率，MID/LOW 档是它的一半。
     */
    private int preprocessWidth;
    private int preprocessHeight;
    /** 8 对齐后的张量 / 预处理纹理尺寸（= 预处理输出尺寸向上对齐 8）。 */
    private int paddedWidth;
    private int paddedHeight;
    /** 深度散射输出尺寸（渲染分辨率 / {@link NSSModel#getDepthScatterDownscale()}）。 */
    private int depthScatterWidth;
    private int depthScatterHeight;
    /** 超分输出（屏幕）尺寸。 */
    private int screenWidth;
    private int screenHeight;
    /** KPN 张量尺寸（8 对齐尺寸 / 4）。 */
    private int kpnWidth;
    private int kpnHeight;
    /** 生效的模型档位（配置里选了未接入的档位时会回退到 HIGH）。 */
    private NSSModel model = NSSModel.MID;

    // ─────────────────────────── 推理缓冲 ───────────────────────────
    /** 12ch int8 输入张量，紧凑 NHWC [paddedH][paddedW][12]。 */
    private IBuffer inputTensorBuffer;
    /** KPN 输出，int8 NHWC [paddedH/4][paddedW/4][36]。 */
    private IBuffer kpnBuffer;
    /** 时序反馈输出，int8 NHWC [paddedH][paddedW][4]；乒乓。 */
    private final IBuffer[] temporalBuffers = new IBuffer[2];
    /** 本帧 DLL 写入的下标。 */
    private int temporalWriteIndex;

    // ─────────────────────────── 自产纹理 ───────────────────────────
    private ITexture depthScatterTexture;
    private final ITexture[] derivativeTextures = new ITexture[2];
    /** 本帧写入的下标；读下标恒为 {@code 1 - derivativeWriteIndex}。 */
    private int derivativeWriteIndex;
    private ITexture nearestOffsetTexture;
    /** MID/LOW 档专用的 LQ 去遮挡掩码（depth 域 = depthScatter 尺寸，R8）。 */
    private ITexture disocclusionMaskTexture;

    private final ITexture[] temporalTextures = new ITexture[2];
    private final ITexture[] historyTextures = new ITexture[2];
    /** 本帧写入的下标；读下标恒为 {@code 1 - historyWriteIndex}。 */
    private int historyWriteIndex;

    // ─────────────────────────── UBO ───────────────────────────
    private IBuffer preProcessUbo;
    private IBuffer postProcessUbo;
    private IBuffer depthScatterUbo;
    /** MID/LOW 档专用：0_disocclusion_mask_lq 的 UBO。 */
    private IBuffer disocclusionUbo;
    private ByteBuffer preProcessScratch;
    private ByteBuffer postProcessScratch;
    private ByteBuffer depthScatterScratch;
    private ByteBuffer disocclusionScratch;

    // ─────────────────────────── 管线 ───────────────────────────
    private ComputePipeline depthScatterInitPipeline;
    private ComputePipeline depthScatterPipeline;
    private ComputePipeline temporalConvertPipeline;
    private GraphicsPipeline preProcessPipeline;
    private GraphicsPipeline postProcessPipeline;
    /** MID/LOW 档专用：disocclusion LQ 掩码 pass（fullscreen fragment）。 */
    private GraphicsPipeline disocclusionPipeline;
    private RenderPass disocclusionRenderPass;

    /// 呈现走 compute：把结果写进互操作 OutputColor 的 storage image，
    /// 这样纹理在提交时落在 GENERAL 布局，与 GL 侧的假设一致。
    private ComputePipeline presentPipeline;
    /// 乒乓双份：索引与 derivativeTextures / historyTextures 一一对应。
    /// 管线只在创建时读 renderPass 的附件格式，因此用 [0] 建管线、绘制时按写入侧选 pass。
    private final RenderPass[] preProcessRenderPasses = new RenderPass[2];
    private final RenderPass[] postProcessRenderPasses = new RenderPass[2];
    private IVertexBuffer fullscreenVertexBuffer;

    /// Arm 的采样器约定：所有 textureLod 采样的资源都是 LINEAR +
    /// CLAMP_EDGE（输入侧）/ CLAMP_BORDER + 透明黑（post 的时序张量）。
    /// 框架默认采样器是 NEAREST，会改变亚像素重采样结果，故显式指定。
    private ISampler linearEdgeSampler;
    private ISampler linearBorderSampler;

    /** 每帧重算的 UBO 取值。 */
    private final NssFrameParams params = new NssFrameParams();

    /** 上一帧 jitter（NSS 需要 _JitterOffsetTm1）。 */
    private float previousJitterX;
    private float previousJitterY;
    private boolean hasPreviousJitter;

    private static int alignUp(int value, int alignment) {
        return (value + alignment - 1) / alignment * alignment;
    }

    // ══════════════════════════════════════════════════════════════════
    //  SRAPI 上下文
    // ══════════════════════════════════════════════════════════════════

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

        destroySRApiContext();
        SuperResolutionNativeAPI.srLoadUpscaleProvidersFromLibrary(
                lib.toAbsolutePath().toString(),
                "srGetNSSUpscaleProviders",
                "srGetNSSUpscaleProvidersCount");
        try (SRUpscaleProvider provider = new SRUpscaleProvider(0)) {
            SuperResolution.LOGGER.info("'srGetUpscaleProvider'(NSS) return code: {}",
                    SuperResolutionNativeAPI.srGetUpscaleProvider(provider, 0x8000007));

            this.context = new SRUpscaleContext(0);
            VulkanDevice vulkanDevice = RenderSystems.vulkan().device();
            VulkanCommandBuffer commandBuffer = vulkanDevice.createCommandBuffer();

            model = resolveSelectedModel();
            computeDimensions();
            SuperResolution.LOGGER.info(
                    "NSS 模型档位：{}（预处理降采样 1/{}，深度散射降采样 1/{}，KPN {} 通道，历史 Catmull={}）",
                    model, model.getPreprocessDownscale(), model.getDepthScatterDownscale(),
                    model.getKpnChannels(), model.isHistoryCatmull());

            EnumSet<SRUpscaleContextCreateFlags> flags = EnumSet.noneOf(SRUpscaleContextCreateFlags.class);
            if (desc.isAutoExposure()) {
                flags.add(SRUpscaleContextCreateFlags.ENABLE_AUTO_EXPOSURE);
            }
            if (desc.isHdrInput()) {
                flags.add(SRUpscaleContextCreateFlags.ENABLE_HDR);
            }
            if (desc.isMotionJittered()) {
                flags.add(SRUpscaleContextCreateFlags.ENABLE_MOTION_VECTORS_JITTERED);
            }
            if (desc.isDepthInverted()) {
                flags.add(SRUpscaleContextCreateFlags.ENABLE_DEPTH_INVERTED);
            }

            Path dp4aDll = SuperResolutionConstants.NATIVE_LIBRARIES_DIR.getPath()
                    .resolve("nss_dp4a.dll").toAbsolutePath();

            try (
                    SRCreateUpscaleContextDesc upscaleContextDesc = SRCreateUpscaleContextDesc.createVulkan(
                            new SRVulkanDeviceInfo(
                                    RenderSystems.vulkan().getVulkanInstance(),
                                    vulkanDevice.getPhysicalDevice(),
                                    vulkanDevice.getVkDevice(),
                                    commandBuffer.getNativeCommandBuffer(),
                                    vulkanDevice.getVkDevice().getCapabilities().vkGetDeviceProcAddr,
                                    VkReflectionHelper.getVkGetInstanceProcAddr()),
                            new Vector2i(screenWidth, screenHeight),
                            new Vector2i(renderWidth, renderHeight),
                            flags
                    );
                    SRContextExtraParams extraParams = new SRContextExtraParams()
            ) {
                upscaleContextDesc.setExtraParams(extraParams);
                extraParams.setString("NSS_DP4A_DLL_PATH", dp4aDll.toString());
                // 模型档位（0=HIGH / 1=MID_LOW）：native 用它决定 KPN 通道数与输入张量尺寸。
                // 与超分比例完全无关 —— 比例决定渲染分辨率，档位决定用哪份权重。
                extraParams.setInt32("NSS_MODEL", model.getQualityCode());
                commandBuffer.begin();
                SRReturnCode createCode = SuperResolutionNativeAPI.srCreateUpscaleContext(context, provider, upscaleContextDesc);
                SRReturnCode initCode = createCode == SRReturnCode.OK
                        ? SuperResolutionNativeAPI.srInitUpscaleContext(context)
                        : createCode;
                commandBuffer.end();
                if (createCode != SRReturnCode.OK) {
                    SuperResolution.LOGGER.error("Failed to create NSS upscale context. Return code: {}", createCode);
                    throw new RuntimeException("Failed to create NSS upscale context");
                }
                if (initCode != SRReturnCode.OK) {
                    SuperResolution.LOGGER.error("Failed to init NSS upscale context. Return code: {}", initCode);
                    throw new RuntimeException("Failed to init NSS upscale context");
                }
                // 权重/scratch 上传已录制进本命令缓冲（外部上传模式），此处统一提交
                vulkanDevice.submitCommandBuffer(commandBuffer);
                commandBuffer.waitForFence();
            } finally {
                commandBuffer.destroy();
            }
        }

        createResources();
    }

    /** 从当前分辨率与所选模型档位推导全部尺寸。 */
    private void computeDimensions() {
        renderWidth = RenderHandlerManager.getRenderWidth();
        renderHeight = RenderHandlerManager.getRenderHeight();
        screenWidth = RenderHandlerManager.getScreenWidth();
        screenHeight = RenderHandlerManager.getScreenHeight();

        // 预处理输出 = 渲染分辨率 / 档位降采样倍数（HIGH=1，MID/LOW=2）
        preprocessWidth = renderWidth / model.getPreprocessDownscale();
        preprocessHeight = renderHeight / model.getPreprocessDownscale();

        // NSS 的输入张量必须是 8 的倍数。
        // HIGH 档：align8(渲染分辨率) = (960, 544)；
        // MID/LOW 档：align8(渲染/2) = (480, 272)。
        paddedWidth = alignUp(preprocessWidth, 8);
        paddedHeight = alignUp(preprocessHeight, 8);

        // 深度散射：预处理降采样的两倍（HIGH = 渲染/2，MID/LOW = 渲染/4）
        depthScatterWidth = renderWidth / model.getDepthScatterDownscale();
        depthScatterHeight = renderHeight / model.getDepthScatterDownscale();

        // KPN：8 对齐尺寸的 1/4
        kpnWidth = paddedWidth / 4;
        kpnHeight = paddedHeight / 4;
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return a;
    }

    /** 读取配置里选择的模型档位（未配置时回退 HIGH）。 */
    private NSSModel resolveSelectedModel() {
        NSSModel selected = SuperResolutionConfig.SPECIAL.NSS.MODEL.get();
        return selected != null ? selected : NSSModel.MID;
    }

    @Override
    protected void destroySRApiContext() {
        destroyResources();
        if (context != null) {
            SRReturnCode code = context.destroy();
            if (code != SRReturnCode.OK) {
                SuperResolution.LOGGER.error("Failed to destroy NSS upscale context. Return code: {}", code);
                throw new RuntimeException("Failed to destroy NSS upscale context");
            }
            context = null;
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  资源创建
    // ══════════════════════════════════════════════════════════════════

    /** 创建推理缓冲、自产纹理、UBO 与全部管线。分辨率变化时整体重建。 */
    private void createResources() {
        destroyResources();
        VulkanDevice device = RenderSystems.vulkan().device();

        // ── 推理缓冲（DLL 的输入 / 输出）──
        inputTensorBuffer = device.createBuffer(BufferDescription.create()
                .size((long) paddedHeight * paddedWidth * 12)
                /*
                 * usage：
                 *   Storage     —— 前处理 shader 写入张量
                 *   TransferDst —— 宿主侧也可能上传
                 *
                 * ★ 不要再加 TransferSrc：VulkanBuffer 构造里
                 *   `hostVisible = usages.has(BufferUsage.TransferSrc)` —— 一旦带上
                 *   就会退化成 HOST_VISIBLE|HOST_COHERENT（宿主内存），前处理写它、
                 *   DLL 读它都要走 PCIe（实测那份 inputMirror 拷贝占单帧 19%，
                 *   有效带宽仅 8.9GB/s；拷贝慢的原因是逐区域开销，不是数据量）。
                 *
                 *   现在 DLL 的第一个 conv 直接绑定这块缓冲（越界 tap 由 kernel 用
                 *   z_a 填充代替 —— 见 conv_rq.comp 的 inBorded=0 分支），不再需要
                 *   镜像拷贝，因此保持 Storage|TransferDst ⇒ DEVICE_LOCAL。
                 */
                .usage(BufferUsage.Storage)
                .usage(BufferUsage.TransferDst)
                .build());
        kpnBuffer = device.createBuffer(BufferDescription.create()
                .size((long) kpnHeight * kpnWidth * model.getKpnChannels())
                /*
                 * TransferDst 不可省：native 侧（nss_dp4a.cpp）用 vkCmdCopyBuffer
                 * 把去边框后的 KPN 从内部 scratch 缓冲拷进来。目标缓冲若未声明
                 * TRANSFER_DST 位，属于 Vulkan 未定义行为 —— 实测表现为
                 * GPU 侧崩溃、vkQueueSubmit 返回 VK_ERROR_DEVICE_LOST(-4)。
                 */
                .usage(BufferUsage.Storage)
                .usage(BufferUsage.TransferDst)
                .build());
        long temporalBytes = (long) paddedHeight * paddedWidth * 4;
        for (int i = 0; i < 2; i++) {
            temporalBuffers[i] = device.createBuffer(BufferDescription.create()
                    .size(temporalBytes)
                    // 同上：native 用 vkCmdCopyBuffer 写入
                    .usage(BufferUsage.Storage)
                    .usage(BufferUsage.TransferDst)
                    .build());
        }

        // ── 自产纹理 ──
        TextureUsages computeTextureUsages = TextureUsages.create()
                .sampler().storage().transferDestination();
        TextureUsages attachmentUsages = TextureUsages.create()
                .sampler().attachmentColor().transferDestination();

        depthScatterTexture = device.createTexture(TextureDescription.create()
                .type(TextureType.Texture2D)
                .size(depthScatterWidth, depthScatterHeight)
                .format(TextureFormat.R32UI)
                .usages(computeTextureUsages)
                .label("NSS-DepthScatter")
                .build());
        nearestOffsetTexture = device.createTexture(TextureDescription.create()
                .type(TextureType.Texture2D)
                .size(paddedWidth, paddedHeight)
                // HIGH：R8（单通道 nearest offset）；MID/LOW：RG8（half-res 布局下
                // nearest offset + 额外通道打包，见官方 post 绑定注释 Low/Mid: R8G8）。
                .format(model.isHalfResolutionInputLayout()
                        ? TextureFormat.RG8 : TextureFormat.R8)
                .usages(attachmentUsages)
                .label("NSS-NearestDepthOffset")
                .build());
        if (model.isHalfResolutionInputLayout()) {
            disocclusionMaskTexture = device.createTexture(TextureDescription.create()
                    .type(TextureType.Texture2D)
                    .size(depthScatterWidth, depthScatterHeight)
                    .format(TextureFormat.R8)
                    .usages(attachmentUsages)
                    .label("NSS-DisocclusionMaskLQ")
                    .build());
        }

        for (int i = 0; i < 2; i++) {
            derivativeTextures[i] = device.createTexture(TextureDescription.create()
                    .type(TextureType.Texture2D)
                    .size(paddedWidth, paddedHeight)
                    .format(TextureFormat.RGBA8_SNORM)
                    .usages(attachmentUsages)
                    .label("NSS-LumaDerivative" + i)
                    .build());
            temporalTextures[i] = device.createTexture(TextureDescription.create()
                    .type(TextureType.Texture2D)
                    .size(paddedWidth, paddedHeight)
                    .format(TextureFormat.RGBA8_SNORM)
                    .usages(computeTextureUsages)
                    .label("NSS-TemporalTensor" + i)
                    .build());
            historyTextures[i] = device.createTexture(TextureDescription.create()
                    .type(TextureType.Texture2D)
                    .size(screenWidth, screenHeight)
                    .format(TextureFormat.R11G11B10F)
                    .usages(attachmentUsages)
                    .label("NSS-History" + i)
                    .build());
        }

        // ── UBO ──
        preProcessUbo = device.createBuffer(BufferDescription.create()
                .size(NssFrameParams.PRE_PROCESS_SIZE)
                .usage(BufferUsage.Ubo).usage(BufferUsage.TransferDst).build());
        postProcessUbo = device.createBuffer(BufferDescription.create()
                .size(NssFrameParams.POST_PROCESS_SIZE)
                .usage(BufferUsage.Ubo).usage(BufferUsage.TransferDst).build());
        depthScatterUbo = device.createBuffer(BufferDescription.create()
                .size(NssFrameParams.DEPTH_SCATTER_SIZE)
                .usage(BufferUsage.Ubo).usage(BufferUsage.TransferDst).build());
        if (model.isHalfResolutionInputLayout()) {
            disocclusionUbo = device.createBuffer(BufferDescription.create()
                    .size(NssFrameParams.DISOCCLUSION_LQ_SIZE)
                    .usage(BufferUsage.Ubo).usage(BufferUsage.TransferDst).build());
        }

        preProcessScratch = NssFrameParams.allocate(NssFrameParams.PRE_PROCESS_SIZE);
        postProcessScratch = NssFrameParams.allocate(NssFrameParams.POST_PROCESS_SIZE);
        depthScatterScratch = NssFrameParams.allocate(NssFrameParams.DEPTH_SCATTER_SIZE);
        if (model.isHalfResolutionInputLayout()) {
            disocclusionScratch = NssFrameParams.allocate(NssFrameParams.DISOCCLUSION_LQ_SIZE);
        }


        // ── 帧缓冲 ──
        for (int i = 0; i < 2; i++) {
            IFrameBuffer preFrameBuffer = device.createFramebuffer(FramebufferDescription.create()
                    .colorAttachment(derivativeTextures[i])
                    .extraColorAttachment(nearestOffsetTexture)
                    .label("NSS-PreProcess" + i)
                    .build());
            preProcessRenderPasses[i] = device.createRenderPass(RenderPass.builder()
                    .frameBuffer(preFrameBuffer)
                    .clearColorOnBegin(0, 0.0f, 0.0f, 0.0f, 0.0f)
                    .clearColorOnBegin(1, 0.0f, 0.0f, 0.0f, 1.0f)
            );
            IFrameBuffer postFrameBuffer = device.createFramebuffer(FramebufferDescription.create()
                    .colorAttachment(historyTextures[i])
                    .label("NSS-PostProcess" + i)
                    .build());
            postProcessRenderPasses[i] = device.createRenderPass(RenderPass.builder()
                    .frameBuffer(postFrameBuffer)
                    .clearColorOnBegin(0, 0.0f, 0.0f, 0.0f, 1.0f)
            );
        }
        if (disocclusionMaskTexture != null) {
            IFrameBuffer disocclusionFrameBuffer = device.createFramebuffer(FramebufferDescription.create()
                    .colorAttachment(disocclusionMaskTexture)
                    .label("NSS-DisocclusionMaskLQ")
                    .build());
            disocclusionRenderPass = device.createRenderPass(RenderPass.builder()
                    .frameBuffer(disocclusionFrameBuffer)
                    .clearColorOnBegin(0, 0.0f, 0.0f, 0.0f, 0.0f)
            );
        }
        fullscreenVertexBuffer = FullscreenQuad.create(device);

        linearEdgeSampler = device.createSampler(SamplerDescription.create()
                .filterMode(TextureFilterMode.Linear)
                .wrapMode(TextureWrapMode.ClampToEdge)
                .mipmapMode(SamplerMipmapMode.None)
                .build());
        linearBorderSampler = device.createSampler(SamplerDescription.create()
                .filterMode(TextureFilterMode.Linear)
                .wrapMode(TextureWrapMode.ClampToBorder)
                .borderColor(SamplerBorderColor.TransparentBlack)
                .mipmapMode(SamplerMipmapMode.None)
                .build());

        createPipelines(device);
        zeroInitializeTextures(device);
    }

    private void createPipelines(VulkanDevice device) {
        // ── [0] 深度散射初始化（清空为 int32 最大值）──
        IShaderProgram scatterInitShader = device.createShaderProgram(ShaderDescription.create()
                .compute(ShaderSource.text(ShaderType.Compute,
                        NssShaderLibrary.load("0_depth_scatter_init.comp")))
                .name("nss_depth_scatter_init")
                .addDefine("NSS_USE_UBO", "1")
                .uniformStorageTexture("_OutDepth", 0, 0)
                .uniformBuffer("PushConstants", 0, 1, NssFrameParams.DEPTH_SCATTER_SIZE)
                .build());
        scatterInitShader.compile();
        depthScatterInitPipeline = ComputePipeline.builder()
                .shader(scatterInitShader)
                .build(device);

        // ── [2] 深度散射 ──
        IShaderProgram scatterShader = device.createShaderProgram(ShaderDescription.create()
                .compute(ShaderSource.text(ShaderType.Compute,
                        NssShaderLibrary.load("0_depth_scatter.comp")))
                .name("nss_depth_scatter")
                .addDefine("NSS_USE_UBO", "1")
                .uniformSamplerTexture("_MotionTex", 0, 0)
                .uniformSamplerTexture("_DepthTex", 0, 1)
                .uniformStorageTexture("_OutDepth", 0, 2)
                .uniformBuffer("PushConstants", 0, 3, NssFrameParams.DEPTH_SCATTER_SIZE)
                .build());
        scatterShader.compile();
        depthScatterPipeline = ComputePipeline.builder()
                .shader(scatterShader)
                .build(device);

        // ── [4] 前处理（MRT 双输出 + 12ch 张量 SSBO）──
        // build options 逐条对应 Arm scenario 的 high/mid/low config：
        //   high     : -DNSS_V1_FULL_RES_LUMA_DERIVATIVE=1 -DNSS_V1_HALF_RES_LUMA_DERIVATIVE=0
        //   mid / low: -DNSS_INPUT_LAYOUT=1 -DNSS_V1_FULL_RES_LUMA_DERIVATIVE=0 -DNSS_V1_HALF_RES_LUMA_DERIVATIVE=1
        boolean halfResLayout = model.isHalfResolutionInputLayout();
        IShaderProgram preShader = device.createShaderProgram(ShaderDescription.create()
                .vertex(ShaderSource.text(ShaderType.Vertex,
                        NssShaderLibrary.load("fullscreen_triangle.vert")))
                .fragment(ShaderSource.text(ShaderType.Fragment,
                        NssShaderLibrary.load("1_pre_process.frag")))
                .name("nss_pre_process")
                .addDefine("NSS_USE_UBO", "1")
                .addDefine("NSS_INPUT_LAYOUT", halfResLayout ? "1" : "0")
                .addDefine("NSS_V1_FULL_RES_LUMA_DERIVATIVE", halfResLayout ? "0" : "1")
                .addDefine("NSS_V1_HALF_RES_LUMA_DERIVATIVE", halfResLayout ? "1" : "0")
                .uniformSamplerTexture("_ColourTex", 0, 0)
                .uniformSamplerTexture("_DepthTex", 0, 1)
                .uniformSamplerTexture("_MotionVectorTex", 0, 2)
                .uniformSamplerTexture("_HistoryTex", 0, 3)
                .uniformSamplerTexture("_FeedbackTensor", 0, 4)
                .uniformSamplerTexture("_DepthTm1Tex", 0, 5)
                .uniformSamplerTexture("_LumaDerivTm1Tex", 0, 6)
                // MID/LOW：half-res 布局下由 0_disocclusion_mask_lq pass 提供
                // depth 域去遮挡掩码；HIGH（INPUT_LAYOUT=0）不消费此纹理，
                // 绑 nearestOffsetTexture 作占位以满足描述符布局。
                .uniformSamplerTexture("_DisocclusionMaskLQTex", 0, 7)
                .storageBuffer("_InputTensorBuffer", 0, 8)
                .uniformBuffer("PushConstants", 0, 9, NssFrameParams.PRE_PROCESS_SIZE)
                .build());
        preShader.compile();
        preProcessPipeline = device.createGraphicsPipeline(GraphicsPipeline.builder()
                .shader(preShader)
                .renderPass(preProcessRenderPasses[0])
                .primitiveType(PrimitiveType.Triangle)
                .rasterization(r -> r.cullMode(CullMode.None))
                .dynamicStates(DynamicStateFlags.ViewportScissor)
                .vertexFormat(FullscreenQuad.getVertexFormat())
        );

        // ── [4.5] MID/LOW 专用：LQ 去遮挡掩码（depth 域，fullscreen frag）──
        // 官方 pass 顺序：depth scatter → disocclusion LQ → preprocess → 推理 → post。
        // 输出被 preprocess 以 _DisocclusionMaskLQTex（binding 7）采样，替代 HIGH 档
        // 「preprocess 内联 ComputeDepthClipInt」的遮挡判定路径。
        if (disocclusionRenderPass != null) {
            IShaderProgram disocclusionShader = device.createShaderProgram(ShaderDescription.create()
                    .vertex(ShaderSource.text(ShaderType.Vertex,
                            NssShaderLibrary.load("fullscreen_triangle.vert")))
                    .fragment(ShaderSource.text(ShaderType.Fragment,
                            NssShaderLibrary.load("0_disocclusion_mask_lq.frag")))
                    .name("nss_disocclusion_mask_lq")
                    .addDefine("NSS_USE_UBO", "1")
                    .uniformSamplerTexture("_MotionTex", 0, 0)
                    .uniformSamplerTexture("_DepthTex", 0, 1)
                    .uniformSamplerTexture("_DepthTm1Tex", 0, 2)
                    .uniformBuffer("PushConstants", 0, 9, NssFrameParams.DISOCCLUSION_LQ_SIZE)
                    .build());
            disocclusionShader.compile();
            disocclusionPipeline = device.createGraphicsPipeline(GraphicsPipeline.builder()
                    .shader(disocclusionShader)
                    .renderPass(disocclusionRenderPass)
                    .primitiveType(PrimitiveType.Triangle)
                    .rasterization(r -> r.cullMode(CullMode.None))
                    .dynamicStates(DynamicStateFlags.ViewportScissor)
                    .vertexFormat(FullscreenQuad.getVertexFormat())
            );
        }


        // ── [8] 时序张量 → SNORM 纹理 ──
        IShaderProgram temporalShader = device.createShaderProgram(ShaderDescription.create()
                .compute(ShaderSource.text(ShaderType.Compute,
                        NssShaderLibrary.load("temporal_to_texture.comp")))
                .name("nss_temporal_to_texture")
                .storageBuffer("_TemporalTensor", 0, 0)
                .uniformStorageTexture("_TemporalImage", 0, 1)
                .build());
        temporalShader.compile();
        temporalConvertPipeline = ComputePipeline.builder()
                .shader(temporalShader)
                .build(device);

        // ── [10] 后处理 ──
        // build options 逐条对应 Arm scenario：
        //   high     : -DNSS_V1_SHARP_THETA=1                              （FILTER_MODE 默认 0、INPUT_LAYOUT 默认 0）
        //   mid      : -DNSS_INPUT_LAYOUT=1 -DNSS_V1_SHARP_THETA=1 -DNSS_FILTER_MODE=1
        //   low      : -DNSS_INPUT_LAYOUT=1 -DNSS_USE_HISTORY_CATMULL=0 -DNSS_V1_SHARP_THETA=1 -DNSS_FILTER_MODE=1
        // 三个宏在 header 里都有默认值（CATMULL=1 / FILTER_MODE=0 / INPUT_LAYOUT=0），
        // 所以只在偏离默认时才显式给值 —— 与 Arm 的 build_options 逐字一致。
        ShaderDescription.Builder postShaderBuilder = ShaderDescription.create()
                .vertex(ShaderSource.text(ShaderType.Vertex,
                        NssShaderLibrary.load("fullscreen_triangle.vert")))
                .fragment(ShaderSource.text(ShaderType.Fragment,
                        NssShaderLibrary.load("3_post_process.frag")))
                .name("nss_post_process")
                .addDefine("NSS_USE_UBO", "1")
                .addDefine("NSS_V1_SHARP_THETA", "1")
                // MID/LOW（2x）：static sparse 滤波（编译期常量核，无需 LUT 纹理）；
                // HIGH 保持官方默认 FILTER_MODE=0（static 2x dense）。
                .addDefine("NSS_FILTER_MODE", halfResLayout ? "1" : "0")
                // MID/LOW（half-res preprocess）：sparse 分支里 KPN 坐标改由
                // InputDims 直接换算（官方 ffx_nss_postprocess.h 的
                // NSS_PREPROCESS_HALF_RES_INPUT 路径）。
                .addDefine("NSS_INPUT_LAYOUT", halfResLayout ? "1" : "0")
                // 稳定模式：ClampHistoryToStats 的 theta 上限钳制（压鬼影、保留时序降噪）。
                // ★ 注意：theta_max 越低 = 抗鬼影越强、但历史权重越低 → **噪点越明显**。
                .addDefine("NSS_FORCE_HISTORY_CLAMP",
                        SuperResolutionConfig.isNssForceHistoryClamp() ? "1" : "0")
                .addDefine("NSS_HISTORY_THETA_MAX",
                        String.valueOf(SuperResolutionConfig.getNssHistoryThetaMax()));
        // NSS_INPUT_LAYOUT / NSS_FILTER_MODE 已在上方按 halfResLayout 条件定义
        // （MID/LOW: INPUT_LAYOUT=1 + FILTER_MODE=1 static sparse；HIGH: 0 + 0 dense）。
        if (!model.isHistoryCatmull()) {
            postShaderBuilder.addDefine("NSS_USE_HISTORY_CATMULL", "0");
        }
        IShaderProgram postShader = device.createShaderProgram(postShaderBuilder
                .uniformSamplerTexture("_ColourTex", 0, 0)
                .uniformSamplerTexture("_HistoryTex", 0, 1)
                .storageBuffer("_KpnParamsBuffer", 0, 2)
                .uniformSamplerTexture("_TemporalTensor", 0, 3)
                .uniformSamplerTexture("_MotionVectorTex", 0, 4)
                .uniformSamplerTexture("_NearestDepthOffsetTex", 0, 5)
                .uniformBuffer("PushConstants", 0, 7, NssFrameParams.POST_PROCESS_SIZE)
                .build());
        postShader.compile();
        postProcessPipeline = device.createGraphicsPipeline(GraphicsPipeline.builder()
                .shader(postShader)
                .renderPass(postProcessRenderPasses[0])
                .primitiveType(PrimitiveType.Triangle)
                .rasterization(r -> r.cullMode(CullMode.None))
                .dynamicStates(DynamicStateFlags.ViewportScissor)
                .vertexFormat(FullscreenQuad.getVertexFormat())
        );

        // ── [12] 呈现到互操作输出（compute：保证输出纹理落在 GENERAL 布局）──
        String outputQualifier = frameResourcesSet.vulkan(OutputColor)
                .getTextureFormat().getGlslFormatQualifier();
        if (outputQualifier == null) {
            throw new IllegalStateException(
                    "NSS 需要一个带 GLSL 格式限定符的输出纹理，实际格式："
                            + frameResourcesSet.vulkan(OutputColor).getTextureFormat());
        }
        IShaderProgram presentShader = device.createShaderProgram(ShaderDescription.create()
                .compute(ShaderSource.text(ShaderType.Compute,
                        NssShaderLibrary.load("present_blit.comp")))
                .name("nss_present_blit")
                .addDefine("DEST_FORMAT", outputQualifier)
                .uniformSamplerTexture("_Source", 0, 0)
                .uniformStorageTexture("_Dest", 0, 1)
                .build());
        presentShader.compile();
        presentPipeline = ComputePipeline.builder()
                .shader(presentShader)
                .build(device);
    }

    /**
     * 一次性把乒乓纹理清零。
     *
     * <p>首帧的「上一帧」数据不存在：Arm 的参考实现从磁盘喂入初始 dds，这里没有对应的
     * 资产，所以显式清零，避免第一帧读到未初始化显存（NaN 会污染网络输入）。
     */
    private void zeroInitializeTextures(VulkanDevice device) {
        VulkanCommandBuffer commandBuffer = device.createCommandBuffer();
        commandBuffer.begin();
        zeroFill(commandBuffer, derivativeTextures[0]);
        zeroFill(commandBuffer, derivativeTextures[1]);
        zeroFill(commandBuffer, temporalTextures[0]);
        zeroFill(commandBuffer, temporalTextures[1]);
        zeroFill(commandBuffer, historyTextures[0]);
        zeroFill(commandBuffer, historyTextures[1]);
        zeroFill(commandBuffer, nearestOffsetTexture);
        commandBuffer.end();
        device.submitCommandBuffer(commandBuffer);
        commandBuffer.waitForFence();
        commandBuffer.destroy();
        hasPreviousJitter = false;
    }

    private static void zeroFill(VulkanCommandBuffer commandBuffer, ITexture texture) {
        int bytes = texture.getWidth() * texture.getHeight() * texture.getTextureFormat().getBytesPerPixel();
        ByteBuffer zeros = ByteBuffer.allocateDirect(bytes).order(java.nio.ByteOrder.nativeOrder());
        commandBuffer.writeToTexture(texture, zeros, 0, 0, texture.getWidth(), texture.getHeight());
    }

    /**
     * 用 {@code vkCmdClearColorImage} 快速清零一张颜色纹理（全 0，含 alpha）。
     *
     * <p>与 {@link #zeroFill} 的区别：那个走 staging buffer 上传（要分配并拷一整张图大小的
     * 主存缓冲，1080p RGBA 就是 8MB/张），这个直接让 GPU 清，代价与面积无关。
     * reset 帧要一次清 7 张纹理，必须用这个。
     *
     * <p><b>分量数必须精确等于格式的通道数</b>，因为
     * {@code ValidatedCommandDecoder.clearTextureRGBA} 会校验
     * {@code color.length == format.getChannelCount()}。本实现用到的三种格式：
     * <pre>
     *   R8           → 1   (nearestOffsetTexture)
     *   R11G11B10F   → 3   (historyTextures —— 只声明 R/G/B，无 A！)
     *   RGBA8_SNORM  → 4   (derivativeTextures / temporalTextures)
     * </pre>
     * 解码器侧已对缺失分量补 0（见 {@code VulkanCommandDecoder.clearTextureRGBA}），
     * 所以这里只需按格式给足、不要多给。
     */
    private static void gpuClearTexture(VulkanCommandBuffer commandBuffer, ITexture texture) {
        int channels = Math.max(texture.getTextureFormat().getChannelCount(), 1);
        commandBuffer.clearTextureRGBA(texture, new float[channels]);   // 全 0
    }

    /**
     * 复现官方 {@code ffx_nss.cpp:1506-1528} 在 reset 帧的 GPU 清空行为。
     *
     * <p>官方在 {@code resetAccumulation}（= {@code firstExecution || params->reset}）时，
     * 无条件清空下列资源：
     * <pre>
     *   always_clear: LUMA_DERIV_1, LUMA_DERIV_2, NEAREST_DEPTH_COORD, FEEDBACK_TENSOR
     *   if manageHistory: HISTORY_1, HISTORY_2
     * </pre>
     * 对应到本实现：{@code derivativeTextures[0/1]}、{@code nearestOffsetTexture}、
     * {@code temporalTextures[1]}（本帧反馈张量的写入侧）、{@code historyTextures[0/1]}。
     *
     * <p><b>为什么必须真的清、不能只靠 {@code _NotHistoryReset=0}：</b>
     * {@code _NotHistoryReset} 只是让 shader 把 {@code disocclusion_mask} 乘 0、
     * 并让后处理走重置分支，但**纹理里仍是上一个场景的脏像素**。后处理在
     * {@code ClampHistoryToStats} 里仍会读 history 做 AABB clamp —— 脏数据会通过
     * clamp 边界漏进结果；反馈张量同理。官方选择直接清显存，从根上断掉。
     *
     * <p>本方法在 reset 帧**紧跟 UBO 上传之后、所有 pass 之前**调用。
     */
    private void clearHistoryResourcesOnReset(VulkanCommandBuffer commandBuffer) {
        // always_clear 部分
        gpuClearTexture(commandBuffer, derivativeTextures[0]);
        gpuClearTexture(commandBuffer, derivativeTextures[1]);
        gpuClearTexture(commandBuffer, nearestOffsetTexture);
        /*
         * ★ FEEDBACK_TENSOR：官方是**单个**资源，本实现是双缓冲。
         *   本帧会写入 temporalWriteIndex 侧（推理写 temporalBuffers[temporalWriteIndex]，
         *   再经 temporalConvert 转到 temporalTextures[temporalWriteIndex]），
         *   读的是 temporalRead = 1 - temporalWriteIndex 侧。
         *   两侧都清才是安全且与官方语义等价的（官方单缓冲天然只有一份）。
         */
        gpuClearTexture(commandBuffer, temporalTextures[0]);
        gpuClearTexture(commandBuffer, temporalTextures[1]);
        // manageHistory 部分（官方默认 manageHistory=true）
        gpuClearTexture(commandBuffer, historyTextures[0]);
        gpuClearTexture(commandBuffer, historyTextures[1]);
        commandBuffer.memoryBarrier(MemoryBarrierType.ALL);
    }

    private void destroyResources() {
        // 管线与着色器
        destroyComputePipeline(depthScatterInitPipeline);
        depthScatterInitPipeline = null;
        destroyComputePipeline(depthScatterPipeline);
        depthScatterPipeline = null;
        destroyComputePipeline(temporalConvertPipeline);
        temporalConvertPipeline = null;
        destroyGraphicsPipeline(preProcessPipeline);
        preProcessPipeline = null;
        destroyGraphicsPipeline(postProcessPipeline);
        postProcessPipeline = null;
        destroyComputePipeline(presentPipeline);
        presentPipeline = null;
        destroyGraphicsPipeline(disocclusionPipeline);
        disocclusionPipeline = null;

        if (disocclusionRenderPass != null) {
            disocclusionRenderPass.destroy();
            disocclusionRenderPass = null;
        }

        for (int i = 0; i < 2; i++) {
            if (preProcessRenderPasses[i] != null) {
                preProcessRenderPasses[i].destroy();
                preProcessRenderPasses[i] = null;
            }
            if (postProcessRenderPasses[i] != null) {
                postProcessRenderPasses[i].destroy();
                postProcessRenderPasses[i] = null;
            }
        }

        if (linearEdgeSampler != null) {
            linearEdgeSampler.destroy();
            linearEdgeSampler = null;
        }
        if (linearBorderSampler != null) {
            linearBorderSampler.destroy();
            linearBorderSampler = null;
        }

        if (fullscreenVertexBuffer != null) {
            fullscreenVertexBuffer.destroy();
            fullscreenVertexBuffer = null;
        }

        for (int i = 0; i < 2; i++) {
            destroyTexture(derivativeTextures[i]);
            derivativeTextures[i] = null;
            destroyTexture(temporalTextures[i]);
            temporalTextures[i] = null;
            destroyTexture(historyTextures[i]);
            historyTextures[i] = null;
            destroyBuffer(temporalBuffers[i]);
            temporalBuffers[i] = null;
        }
        destroyTexture(depthScatterTexture);
        depthScatterTexture = null;
        destroyTexture(nearestOffsetTexture);
        nearestOffsetTexture = null;
        if (disocclusionMaskTexture != null) {
            destroyTexture(disocclusionMaskTexture);
            disocclusionMaskTexture = null;
        }
        if (disocclusionUbo != null) {
            destroyBuffer(disocclusionUbo);
            disocclusionUbo = null;
        }
        disocclusionScratch = null;

        destroyBuffer(inputTensorBuffer);
        inputTensorBuffer = null;
        destroyBuffer(kpnBuffer);
        kpnBuffer = null;
        destroyBuffer(preProcessUbo);
        preProcessUbo = null;
        destroyBuffer(postProcessUbo);
        postProcessUbo = null;
        destroyBuffer(depthScatterUbo);
        depthScatterUbo = null;

        temporalWriteIndex = 0;
        derivativeWriteIndex = 0;
        historyWriteIndex = 0;
    }

    private static void destroyComputePipeline(ComputePipeline pipeline) {
        if (pipeline == null) {
            return;
        }
        IShaderProgram shader = pipeline.shader();
        pipeline.destroy();
        if (shader != null) {
            shader.destroy();
        }
    }

    private static void destroyGraphicsPipeline(GraphicsPipeline pipeline) {
        if (pipeline == null) {
            return;
        }
        IShaderProgram shader = pipeline.shader();
        pipeline.destroy();
        if (shader != null) {
            shader.destroy();
        }
    }

    private static void destroyTexture(ITexture texture) {
        if (texture != null) {
            texture.destroy();
        }
    }

    private static void destroyBuffer(IBuffer buffer) {
        if (buffer != null) {
            buffer.destroy();
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  每帧调度
    // ══════════════════════════════════════════════════════════════════

    @Override
    public void dispatchSRApiContext(VulkanCommandBuffer commandBuffer, FrameResourcesSet frameResourcesSet) {
        if (preProcessPipeline == null) {
            return;
        }
        /*
         * GPU 分段计时：与 DLSS/DLSSRR 共用同一个 VulkanTimestampProfiler（同一查询池、
         * 同一上报通道 PerformanceTracker），因此 "NSS Total" 可与 DLSS 的 "VK Upscale"
         * 直接比较，口径一致。
         *
         * 划分：Prepass  = 深度散射 + 去遮挡掩码 + 前处理
         *       Inference = native/DLL(nss_dp4a) 推理
         *       Post     = 时序转换 + 后处理 + 呈现
         * 注意：reset 帧的历史清零（约 23.5MB）计入 Total 但不属于任何子段。
         */
        VulkanTimestampProfiler profiler = null;
        try {
            profiler = RenderSystems.vulkan().device().timestampProfiler();
        } catch (Throwable ignored) {
            // 非 Vulkan 后端 / 不支持时间戳 / 设备未就绪：静默跳过。
        }
        var nativeCmd = commandBuffer.getNativeCommandBuffer();
        // Setup 段：历史清零（仅 reset 帧）+ 三个 UBO 上传，到散射开始为止。
        int nssSetupSlot = (profiler == null || nativeCmd == null)
                ? -1 : profiler.beginRegion(nativeCmd, PerformanceTracker.VK_NSS_SETUP);

        boolean reset = consumeHistoryReset();
        updateFrameParams(commandBuffer, frameResourcesSet, reset);

        /*
         * ★★ reset 帧：真的把历史资源清成 0（复现官方 ffx_nss.cpp:1506-1528）★★
         *
         * 这是本实现长期缺失的一环：此前 reset 只体现为 UBO 里的
         * `_NotHistoryReset = 0`，shader 层"弱化"历史，但纹理里仍是脏像素。
         * 官方在同一时机直接对 7 张资源下 clear job。
         *
         * 首帧也走这条（`consumeHistoryReset()` 新实例返回 true），
         * 与 zeroInitializeTextures() 的初始清零重叠但无害 —— 且它更完整
         * （初始清零漏了 temporalTextures[1] 的语义等价物，这里是通用路径）。
         */
        if (reset) {
            clearHistoryResourcesOnReset(commandBuffer);
        }

        // 本帧读写的乒乓下标
        int derivativeRead = 1 - derivativeWriteIndex;
        int historyRead = 1 - historyWriteIndex;
        int temporalRead = 1 - temporalWriteIndex;

        ITexture color = frameResourcesSet.vulkan(Color);
        ITexture depth = frameResourcesSet.vulkan(Depth);
        ITexture motion = frameResourcesSet.vulkan(MotionVectors);

        if (nssSetupSlot >= 0) {
            profiler.endRegion(nativeCmd, nssSetupSlot);
        }

        // ── [0] 深度散射初始化（清空）──
        int nssScatterInitSlot = (profiler == null || nativeCmd == null)
                ? -1 : profiler.beginRegion(nativeCmd, PerformanceTracker.VK_NSS_SCATTER_INIT);
        depthScatterInitPipeline.descriptorSet()
                .storageImage("_OutDepth", depthScatterTexture)
                .uniformBuffer("PushConstants", depthScatterUbo)
                .update();
        commandBuffer.bindPipeline(depthScatterInitPipeline);
        commandBuffer.dispatch(
                (depthScatterWidth + 7) / 8,
                (depthScatterHeight + 7) / 8,
                1);
        commandBuffer.memoryBarrier(MemoryBarrierType.ALL);
        if (nssScatterInitSlot >= 0) {
            profiler.endRegion(nativeCmd, nssScatterInitSlot);
        }

        // ── [2] 深度散射（运动矢量重投影）──
        int nssScatterSlot = (profiler == null || nativeCmd == null)
                ? -1 : profiler.beginRegion(nativeCmd, PerformanceTracker.VK_NSS_SCATTER);

        depthScatterPipeline.descriptorSet()
                .samplerTexture("_MotionTex", motion)
                .samplerTexture("_DepthTex", depth)
                .storageImage("_OutDepth", depthScatterTexture)
                .uniformBuffer("PushConstants", depthScatterUbo)
                .update();
        commandBuffer.bindPipeline(depthScatterPipeline);
        commandBuffer.dispatch(
                (depthScatterWidth + 7) / 8,
                (depthScatterHeight + 7) / 8,
                1);
        commandBuffer.memoryBarrier(MemoryBarrierType.ALL);
        if (nssScatterSlot >= 0) {
            profiler.endRegion(nativeCmd, nssScatterSlot);
        }

        // ── [4.5] MID/LOW：LQ 去遮挡掩码（depth 域）──
        if (disocclusionPipeline != null) {
            int nssDisoccSlot = (profiler == null || nativeCmd == null)
                    ? -1 : profiler.beginRegion(nativeCmd, PerformanceTracker.VK_NSS_DISOCC);
            commandBuffer.writeToBuffer(disocclusionUbo, 0, disocclusionScratch);
            disocclusionPipeline.descriptorSet()
                    .samplerTexture("_MotionTex", motion)
                    .samplerTexture("_DepthTex", depth)
                    .samplerTexture("_DepthTm1Tex", depthScatterTexture)
                    .uniformBuffer("PushConstants", disocclusionUbo)
                    .update();
            runGraphicsPass(commandBuffer, disocclusionPipeline,
                    disocclusionRenderPass, depthScatterWidth, depthScatterHeight);
            commandBuffer.memoryBarrier(MemoryBarrierType.ALL);
            if (nssDisoccSlot >= 0) {
                profiler.endRegion(nativeCmd, nssDisoccSlot);
            }
        }

        int nssPreprocessSlot = (profiler == null || nativeCmd == null)
                ? -1 : profiler.beginRegion(nativeCmd, PerformanceTracker.VK_NSS_PREPROCESS);

        // ── [4] 前处理：MRT 双输出 + 12ch 张量 ──
        // 采样器：Arm 约定这些资源都以 textureLod 采样且 min/mag = LINEAR；
        // `_DepthTm1Tex` 是 R32_UINT（texelFetch），整数格式不能线性过滤，故不指定。
        preProcessPipeline.descriptorSet()
                .samplerTexture("_ColourTex", color, linearEdgeSampler)
                .samplerTexture("_DepthTex", depth, linearEdgeSampler)
                .samplerTexture("_MotionVectorTex", motion, linearEdgeSampler)
                .samplerTexture("_HistoryTex", historyTextures[historyRead], linearEdgeSampler)
                .samplerTexture("_FeedbackTensor", temporalTextures[temporalRead], linearEdgeSampler)
                .samplerTexture("_DepthTm1Tex", depthScatterTexture)
                .samplerTexture("_LumaDerivTm1Tex", derivativeTextures[derivativeRead], linearEdgeSampler)
                // MID/LOW：disocclusion LQ 掩码；HIGH：不消费，绑占位纹理满足布局。
                .samplerTexture("_DisocclusionMaskLQTex",
                        disocclusionMaskTexture != null ? disocclusionMaskTexture : nearestOffsetTexture)
                .storageBuffer("_InputTensorBuffer", inputTensorBuffer)
                .uniformBuffer("PushConstants", preProcessUbo)
                .update();
        runGraphicsPass(commandBuffer, preProcessPipeline,
                preProcessRenderPasses[derivativeWriteIndex], paddedWidth, paddedHeight);
        commandBuffer.memoryBarrier(MemoryBarrierType.ALL);
        if (nssPreprocessSlot >= 0) {
            profiler.endRegion(nativeCmd, nssPreprocessSlot);
        }

        // ── [6] 推理：nss_dp4a ──
        int nssInferenceSlot = (profiler == null || nativeCmd == null)
                ? -1 : profiler.beginRegion(nativeCmd, PerformanceTracker.VK_NSS_INFERENCE);
        dispatchInference(commandBuffer, frameResourcesSet, reset);
        commandBuffer.memoryBarrier(MemoryBarrierType.ALL);
        if (nssInferenceSlot >= 0) {
            profiler.endRegion(nativeCmd, nssInferenceSlot);
        }

        int nssTemporalSlot = (profiler == null || nativeCmd == null)
                ? -1 : profiler.beginRegion(nativeCmd, PerformanceTracker.VK_NSS_TEMPORAL);

        // ── [8] 时序张量 → SNORM 纹理 ──
        temporalConvertPipeline.descriptorSet()
                .storageBuffer("_TemporalTensor", temporalBuffers[temporalWriteIndex])
                .storageImage("_TemporalImage", temporalTextures[temporalWriteIndex])
                .update();
        commandBuffer.bindPipeline(temporalConvertPipeline);
        commandBuffer.dispatch(
                (paddedWidth + 7) / 8,
                (paddedHeight + 7) / 8,
                1);
        commandBuffer.memoryBarrier(MemoryBarrierType.ALL);
        if (nssTemporalSlot >= 0) {
            profiler.endRegion(nativeCmd, nssTemporalSlot);
        }
        int nssPostProcSlot = (profiler == null || nativeCmd == null)
                ? -1 : profiler.beginRegion(nativeCmd, PerformanceTracker.VK_NSS_POSTPROC);

        // ── [10] 后处理：本帧超分结果写入 history 乒乓的写入侧 ──
        // `_TemporalTensor` 按 Arm 约定用 CLAMP_BORDER + 透明黑（越界处取 0）。
        postProcessPipeline.descriptorSet()
                .samplerTexture("_ColourTex", color, linearEdgeSampler)
                .samplerTexture("_HistoryTex", historyTextures[historyRead], linearEdgeSampler)
                .storageBuffer("_KpnParamsBuffer", kpnBuffer)
                .samplerTexture("_TemporalTensor", temporalTextures[temporalWriteIndex], linearBorderSampler)
                .samplerTexture("_MotionVectorTex", motion, linearEdgeSampler)
                .samplerTexture("_NearestDepthOffsetTex", nearestOffsetTexture)
                .uniformBuffer("PushConstants", postProcessUbo)
                .update();
        runGraphicsPass(commandBuffer, postProcessPipeline,
                postProcessRenderPasses[historyWriteIndex], screenWidth, screenHeight);
        commandBuffer.memoryBarrier(MemoryBarrierType.ALL);
        if (nssPostProcSlot >= 0) {
            profiler.endRegion(nativeCmd, nssPostProcSlot);
        }
        int nssPresentSlot = (profiler == null || nativeCmd == null)
                ? -1 : profiler.beginRegion(nativeCmd, PerformanceTracker.VK_NSS_PRESENT);

        // ── [12] 呈现到互操作输出 ──
        presentPipeline.descriptorSet()
                .samplerTexture("_Source", historyTextures[historyWriteIndex])
                .storageImage("_Dest", frameResourcesSet.vulkan(OutputColor))
                .update();
        commandBuffer.bindPipeline(presentPipeline);
        commandBuffer.dispatch(
                (screenWidth + 7) / 8,
                (screenHeight + 7) / 8,
                1);

        if (nssPresentSlot >= 0) {
            profiler.endRegion(nativeCmd, nssPresentSlot);
        }

        // 帧尾翻转乒乓
        temporalWriteIndex ^= 1;
        derivativeWriteIndex ^= 1;
        historyWriteIndex ^= 1;
    }

    /** 计算并上传本帧的三个 UBO。 */
    private void updateFrameParams(
            VulkanCommandBuffer commandBuffer,
            FrameResourcesSet frameResourcesSet,
            boolean reset) {
        var frameData = frameResourcesSet.frameData;

        params.renderWidth = renderWidth;
        params.renderHeight = renderHeight;
        params.paddedWidth = paddedWidth;
        params.paddedHeight = paddedHeight;
        params.preprocessWidth = preprocessWidth;
        params.preprocessHeight = preprocessHeight;
        params.depthScatterWidth = depthScatterWidth;
        params.depthScatterHeight = depthScatterHeight;
        params.screenWidth = screenWidth;
        params.screenHeight = screenHeight;
        params.kpnWidth = kpnWidth;
        params.kpnHeight = kpnHeight;
        params.kpnChannels = model.getKpnChannels();

        /*
         * ════════════════════════════════════════════════════════════════════
         *  运动矢量单位换算 —— 完整链路推导（务必按此逐步核对，勿凭印象改）
         * ════════════════════════════════════════════════════════════════════
         *
         * 【官方侧的三段事实】
         *   (1) 官方示例：motionVectorScale = -1.0f * maxRenderSize
         *       （user_guide.md:779-780）
         *   (2) 官方 LoadMotion：v = texelFetch(_MotionTex, px).xy * MotionVectorScale()
         *       （ffx_nss_preprocess.h:264）
         *   (3) 官方重投影：reproj_uv = uv + motion * InvInputDims
         *       （ffx_nss_preprocess.h:890，注释「Motion is backward direction」）
         *       postprocess 同理：reproj_uv = uv + motion * InvOutputDims
         *       （ffx_nss_postprocess.h:778）
         *
         *   ★ 注意 (3) 里的 `motion` 是 **LoadMotion 之后**的值（已乘 scale），
         *     不是原始纹理值。官方等价写法：
         *         reproj_uv = uv + V_official * (-renderSize) * (1/renderSize)
         *                   = uv - V_official
         *     而 user_guide.md:807 说明经 scale 后 result 是 backward 像素位移，
         *     所以这个 `motion` 的方向是 backward。
         *
         * 【SR 光影侧的事实 —— 这是关键】
         *   光影写入 _MotionVectorTex 的值（Composite7.frag:160 / 248-251）：
         *         velocity = prevProjectionPos - currPosition   ← **backward 归一化 UV**
         *         再乘 upscaleRatio = screen/render（#if SR_ENABLE）
         *   即   V_tex = V_backward * upscaleRatio
         *
         * 【换算 —— 第三轮修正（2026-09-27）：三 pass 统一推导 + 运行时标定】
         *
         * 设 _MotionVectorTex 里的值为 V_tex = V_backward_norm × k
         * （k = 光影在 SR_ENABLE 下对 velocity 的实际放大倍率；
         *   backward 方向由 Composite7.frag:160 `view - coord` 决定，不变）。
         * 宿主传 _MotionToPixels = MTP，三个 pass 的实际换算链：
         *
         *   pre    : motion = V_tex*MTP             ; reproj = uv + motion*InvInputDims(render)
         *   post   : motion = V_tex*MTP*_Scale(s/r) ; reproj = uv + motion*InvOutputDims(screen)
         *   scatter: motion = V_tex*MTP*InvScale    ; reproj = uv + motion*InvOutputDims(scatter)
         *
         * 重投影成立（reproj_uv = uv + V_backward_norm）的统一条件：
         *   k * MTP / renderSize = 1   =>   **MTP = renderSize / k（三个 pass 相同）**
         * （_Scale/InvScale 逐项约掉后分母都是 renderSize —— 这正是官方把
         *   motionVectorScale 定成 ±renderSize 的原因，官方假设纹理值域 ±1。）
         * 【k 的运行时标定 —— 实测优先于静态推导】
         *   静态推导：Sundial-Lite Composite7.frag:252 `velocity *= upscaleRatio`，
         *   upscaleRatio = screenSize/floor(0.5*screenSize) = 2.0 => 理论 k=2，
         *   MTP 应为 render/2（=383，即旧公式 mvScale=1.0 的值）。
         *   实测（2026-09-27 10:23 vs 10:28，同一 jar、同一段 History 修复代码）：
         *   mvScale=1.0 糊+鬼影，mvScale=2.0（MTP=renderSize=766）明显改善
         *   => **运行时 k=1.0**。最可能原因：SR_RENDER_SCALE_FACTOR 运行时
         *   实际注入 1.0（或 SR_ENABLE 分支未生效），光影的 *2 从未发生 —— 待查。
         *
         *   ★★ 结论：采用与官方 motionVectorScale 相同的形式 MTP = renderSize * mvScale ★★
         *   - 默认 mvScale=1.0 时 MTP=renderSize：与官方 ±renderSize 约定一致，
         *     数值上与实测有效的「旧公式*2.0」完全相同（如 766x354 时 = 766）；
         *   - 非整除分辨率（如 1920x991）下 y 轴直接用精确 renderHeight=495，
         *     而旧公式给 494.5005（约 0.5px 误差）；
         *   - 若日后修正宏注入使 k 真正=2.0，此处须改回
         *     renderSize * (render/screen) * mvScale，并同步核对三个 pass。
         *
         * 【历史教训 —— 这里连错两轮，务必看清】
         *   第一轮：shader 写成 `uv - motion*Inv`（与官方 `+` 相反），宿主又补负号，
         *           两次翻转抵消 = 等价于没翻 → 「整体平移 + 抖动」。
         *   第二轮：只把 shader 的 6 处 `-` 改成 `+`，同时把宿主的负号去掉 ——
         *           这正是**正确解**（shader 与官方一致，宿主只留幅度）。
         *           ★ 若日后「又发现抖动」而把负号加回来，就会再次把方向翻反，
         *             属于重犯第一轮的错误。**除非 shader 的 `+` 被改回 `-`，
         *             否则宿主永远不应该出现负号。**
         *
         */
        params.motionToPixelsX = renderWidth;
        params.motionToPixelsY = renderHeight;

        Vector2f jitter = frameData.jitterOffset();
        params.jitterX = jitter.x;
        params.jitterY = jitter.y;
        /*
         * ★★ _JitterOffsetTm1 的取值规则（复现官方 ffx_nss.cpp:1146-1160）★★
         *
         * 官方：
         *     if (needResetHistory) {                 // firstExecution || params->reset
         *         _JitterOffsetTm1 = params->jitterOffset;   // ← **镜像当前帧 jitter**
         *     } else {
         *         _JitterOffsetTm1 = _JitterOffset;          // ← 上一帧的 jitter
         *     }
         *
         * 动机（官方注释原话）：「On history reset, mirror current jitter into tm1
         * to avoid a mismatched first temporal phase.」——重置后历史被丢弃，
         * 若 tm1 仍用「上一帧」的 jitter，重投影会按错误的相位差去对齐，
         * 在重置后第一帧引入一次错位。
         *
         * 【本实现的历史缺陷】此前只在「首帧」（hasPreviousJitter==false）才镜像，
         * 运行期 reset（世界加载/传送）时仍用上一帧 jitter → 重置后首帧相位错配。
         */

        boolean mirrorJitter = reset || !hasPreviousJitter;
        params.jitterPrevX = mirrorJitter ? jitter.x : previousJitterX;
        params.jitterPrevY = mirrorJitter ? jitter.y : previousJitterY;

        // LUT 常量：IdxModulo/ReducedInputModulo（gcd 约分）+ 静态档的 jitter tile 重映射。
        params.updateLutConstants(false);   // 静态 2x 档：LutOffset 按 jitter 相位计算

        float preExposure = frameData.preExposure();
        params.exposure = preExposure > 0.0f ? preExposure : 1.0f;
        /*
         * ★★ 注意这里与官方 `_NotHistoryReset` 的语义对应（不要再写反）：
         *
         *     reset == false（正常帧）→ notHistoryReset = 1.0  保留历史
         *     reset == true （重置帧）→ notHistoryReset = 0.0  丢弃历史
         *
         * 官方字段名是 `_NotHistoryReset`（ffx_nss_common_glsl.h:136），
         * 取值 1 = 保留、0 = 重置。历史 bug：此处曾写成 `reset ? 1 : 0`，
         * 于是正常帧写 0（= 官方语义的"重置"），
         * 前处理 `history_valid` 被置 0 → disocclusion_mask 恒为 0，
         * 后处理 `ClampHistoryToStats` 走 m1 分支 → **每帧都在异常路径上跑**，
         * 表现为「整体平移式抖动」。
         *
         * 这也解释了为什么 `nss_force_history_reset = true` 反而让抖动消失：
         * 它把值从 0 翻成 1，等于误打误撞切回了官方的正常路径。
         */
        params.notHistoryReset = reset ? 0.0f : 1.0f;

        params.computeDeviceToViewDepth(
                frameData.cameraNear(),
                frameData.cameraFar(),
                initDesc.isDepthInverted(),
                renderWidth,
                renderHeight,
                frameData.verticalFov());

        params.writePreProcessUbo(preProcessScratch);
        params.writePostProcessUbo(postProcessScratch);
        params.writeDepthScatterUbo(depthScatterScratch);

        commandBuffer.writeToBuffer(preProcessUbo, 0, preProcessScratch);
        commandBuffer.writeToBuffer(postProcessUbo, 0, postProcessScratch);
        commandBuffer.writeToBuffer(depthScatterUbo, 0, depthScatterScratch);
        if (disocclusionScratch != null) {
            params.writeDisocclusionLqUbo(disocclusionScratch);
            commandBuffer.writeToBuffer(disocclusionUbo, 0, disocclusionScratch);
        }


        previousJitterX = jitter.x;
        previousJitterY = jitter.y;
        hasPreviousJitter = true;
    }

    /**
     * 录制一次图形 pass。
     *
     * <p>顺序必须是 beginRenderPass → bindPipeline → draw → endRenderPass ——
     * 框架的 {@code bindPipeline(graphics)} 要求 render pass 已经激活。
     * viewport / scissor 都是动态状态且管线里没有静态值可用（Vulkan 侧的静态
     * scissor 是 1x1），因此每个 pass 都要显式设置。
     */
    private void runGraphicsPass(
            VulkanCommandBuffer commandBuffer,
            GraphicsPipeline pipeline,
            RenderPass renderPass,
            int width,
            int height) {
        /*
         * ★ 采样纹理的布局转换必须放在 render pass **之外**。
         *
         * Vulkan 规定：在 render pass 实例内调用 vkCmdPipelineBarrier 时，
         * 所有 image memory barrier 的 oldLayout 必须等于 newLayout
         * （VUID-vkCmdPipelineBarrier-oldLayout-01181），且 stageMask 只能取
         * framebuffer-space 阶段并要求 VK_DEPENDENCY_BY_REGION_BIT（09556/07891）。
         *
         * `bindPipeline` 内部会在 pass 里对这些采样纹理做
         * xxx -> SHADER_READ_ONLY_OPTIMAL 的转换，这违反上述规则。驱动/校验层
         * 拒绝该 barrier，但 VulkanCommandDecoder.transitionTexture 末尾仍会
         * 记录「已是 SHADER_READ_ONLY」——状态跟踪与 GPU 实际布局就此脱节，
         * 之后 requiresBarrier 会判定「无需转换」，采样纹理停留在旧布局，
         * 读到不稳定的上一帧数据 —— 表现为画面抖动/闪烁。
         *
         * 因此在 beginRenderPass 之前先把采样纹理转好；pass 内那次因状态一致
         * 变成空操作，不再产生非法 barrier。
         */
        if (SuperResolutionConfig.isNssPrepareResourcesBeforePass()
                && RenderSystems.vulkan().device().commandDecoder() instanceof VulkanCommandDecoder vkDecoder) {
            vkDecoder.prepareGraphicsPipelineResources(commandBuffer, pipeline);
        }
        commandBuffer.beginRenderPass(renderPass);
        try {
            commandBuffer.bindPipeline(pipeline);
            commandBuffer.setViewport(0, 0, width, height);
            commandBuffer.setScissor(0, 0, width, height);
            commandBuffer.draw(fullscreenVertexBuffer, 3, 0);
        } finally {
            /*
             * 异常安全：render pass 一旦 begin 就**必须** end，否则校验层的
             * `activeRenderPass` 会残留，导致后续 bindPipeline(compute) 抛出
             * 「cannot bind compute pipeline inside a render pass」——
             * 一个与本 pass 无关的、极具误导性的报错。
             *
             * 这里吞掉 end 自身的异常，避免掩盖真正的原始异常。
             */
            try {
                commandBuffer.endRenderPass();
            } catch (Throwable ignored) {
                // 命令缓冲状态已不可用时 end 会失败，此时保留原始异常更重要
            }
        }
    }

    /** [6] 纯推理：DLL 读输入张量，写 KPN 与时序反馈。 */
    private void dispatchInference(
            VulkanCommandBuffer commandBuffer,
            FrameResourcesSet frameResourcesSet,
            boolean reset) {
        try (SRDispatchUpscaleDesc desc = new SRDispatchUpscaleDesc()) {
            desc.setCommandBuffer(SRDispatchCommandBufferInfo.createVulkan(commandBuffer.getNativeCommandBuffer()));
            desc.setColor(new SRTextureResource(frameResourcesSet.vulkan(Color)));
            desc.setDepth(new SRTextureResource(frameResourcesSet.vulkan(Depth)));
            desc.setMotionVectors(new SRTextureResource(frameResourcesSet.vulkan(MotionVectors)));
            desc.setOutput(new SRTextureResource(frameResourcesSet.vulkan(OutputColor)));
            desc.setJitterOffset(new Vector2f(frameResourcesSet.frameData.jitterOffset()));
            /*
             * motionVectorScale 是 SRAPI 的必填字段：Java 侧 srDispatchUpscale
             * 无条件解引用 desc.motionVectorScale.x，不设就抛 NullPointerException。
             * 语义与 XeSS 一致 —— 把运动矢量乘成「渲染空间像素」，
             * 因此取渲染分辨率而非屏幕分辨率。
             */
            desc.setMotionVectorScale(new Vector2f(
                    frameResourcesSet.frameData.renderSize()));
            desc.setRenderSize(new Vector2i(renderWidth, renderHeight));
            desc.setUpscaleSize(new Vector2i(screenWidth, screenHeight));
            desc.setFrameTimeDelta(frameResourcesSet.frameData.frameTimeDelta());
            desc.setReset(reset);

            SRContextExtraParams extraParams = desc.getExtraParams();
            extraParams.setPointer("NSS_INPUT_TENSOR_BUFFER", inputTensorBuffer.handle());
            extraParams.setPointer("NSS_KPN_BUFFER", kpnBuffer.handle());
            extraParams.setPointer("NSS_TEMPORAL_BUFFER", temporalBuffers[temporalWriteIndex].handle());

            SRReturnCode code = SuperResolutionNativeAPI.srDispatchUpscale(context, desc);
            if (code != SRReturnCode.OK) {
                SuperResolution.LOGGER.error("Failed to dispatch NSS upscale context. Return code: {}", code);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  乒乓辅助
    // ══════════════════════════════════════════════════════════════════
}
