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

package io.homo.superresolution.common.config;

import com.mojang.blaze3d.systems.RenderSystem;
import io.homo.superresolution.api.AbstractAlgorithm;
import io.homo.superresolution.api.config.ModConfigSpec;
import io.homo.superresolution.api.config.ModConfigSpecBuilder;
import io.homo.superresolution.api.config.values.list.StringListValue;
import io.homo.superresolution.api.config.values.single.BooleanValue;
import io.homo.superresolution.api.config.values.single.IntValue;
import io.homo.superresolution.api.config.values.single.EnumValue;
import io.homo.superresolution.api.config.values.single.FloatValue;
import io.homo.superresolution.api.config.values.single.StringValue;
import io.homo.superresolution.api.platform.OperatingSystem;
import io.homo.superresolution.api.platform.OperatingSystemType;
import io.homo.superresolution.api.platform.Platform;
import io.homo.superresolution.api.registry.AlgorithmDescription;
import io.homo.superresolution.api.registry.AlgorithmRegistry;
import io.homo.superresolution.common.SuperResolution;
import io.homo.superresolution.common.config.enums.CaptureMode;
import io.homo.superresolution.common.config.enums.InternalTextureFormat;
import io.homo.superresolution.common.config.special.SpecialConfigs;
import io.homo.superresolution.api.registry.framegeneration.FrameGenerationGroups;
import io.homo.superresolution.common.framegeneration.FrameGenerationMode;
import io.homo.superresolution.common.framegeneration.FrameGenerationDescriptions;
import io.homo.superresolution.common.lowlatency.LowLatency;
import io.homo.superresolution.common.lowlatency.nv.NVIDIAReflexMode;
import io.homo.superresolution.common.minecraft.B3DVulkanBridge;
import io.homo.superresolution.common.minecraft.handler.RenderHandlerManager;
import io.homo.superresolution.common.presentation.api.PresentationBackendType;
import io.homo.superresolution.common.upscale.AlgorithmDescriptions;
import io.homo.superresolution.common.workmode.SRWorkModeManager;
import io.homo.superresolution.common.workmode.SRWorkModeState;
import io.homo.superresolution.core.SuperResolutionConstants;
import io.homo.superresolution.core.graphics.GpuVendor;
import io.homo.superresolution.core.graphics.GraphicsCapabilities;
import io.homo.superresolution.core.graphics.impl.texture.TextureFormat;
import io.homo.superresolution.core.graphics.opengl.GlDebug;
import io.homo.superresolution.core.graphics.vulkan.VulkanDebug;
import io.homo.superresolution.core.gui.MaterialTheme;
import io.homo.superresolution.core.gui.SchemeVariant;
import io.homo.superresolution.core.utils.Color;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class SuperResolutionConfig {
    public static final ModConfigSpec SPEC;
    public static final SpecialConfigs SPECIAL;
    public static final BooleanValue ENABLE_UPSCALE;
    public static final EnumValue<PresentationBackendType> PRESENTATION_BACKEND;
    public static final FloatValue UPSCALE_RATIO;
    public static final StringValue UPSCALE_ALGO;
    public static final FloatValue SHARPNESS;
    public static final EnumValue<CaptureMode> CAPTURE_MODE;
    public static final BooleanValue DEBUG_DUMP_SHADER;
    public static final BooleanValue SKIP_INIT_VULKAN;
    public static final BooleanValue ENABLE_RENDER_DOC;
    public static final BooleanValue ENABLE_IMGUI;
    public static final BooleanValue ENABLE_PRESENT_INDICATOR;
    public static final BooleanValue GENERATE_MOTION_VECTORS;
    public static final BooleanValue PAUSE_GAME_ON_GUI;
    public static final BooleanValue AUTO_HIDE_SHADERPACK_DISABLED_ALGORITHMS;
    public static final StringListValue INJECT_POST_CHAIN_BLACKLIST;
    public static final BooleanValue ENABLE_COMPAT_SHADER_COMPILER;
    public static final BooleanValue ENABLE_DETAILED_PROFILING;
    public static final BooleanValue ENABLE_DEBUG;
    public static final BooleanValue ENABLE_UNSTABLE_INCOMPATIBLE_SHADER_SUPPORT;
    public static final EnumValue<InternalTextureFormat> INTERNAL_TEXTURE_FORMAT;
    public static final EnumValue<MaterialTheme> THEME;
    public static final EnumValue<SchemeVariant> THEME_SCHEME_VARIANT;
    public static final FloatValue THEME_CONTRAST_LEVEL;
    public static final StringValue THEME_COLOR;
    public static final StringValue LOW_LATENCY_MODE;
    public static final EnumValue<NVIDIAReflexMode> NVIDIA_REFLEX_MODE;
    public static final EnumValue<FrameGenerationMode> FRAME_GENERATION_MODE;
    public static final StringValue FRAME_GENERATION_PROVIDER;
    public static final StringValue FRAME_GENERATION_BACKEND;
    public static final BooleanValue FLIP_VK_GL_INTEROP_RESOURCES_Y;
    public static final FloatValue NSS_MOTION_VECTOR_SCALE;
    public static final FloatValue SHADERPACK_JITTER_SCALE;
    public static final BooleanValue NSS_PREPARE_RESOURCES_BEFORE_PASS;
    public static final BooleanValue NSS_FORCE_HISTORY_RESET;
    public static final BooleanValue NSS_DISABLE_JITTER;
    public static final IntValue NSS_JITTER_SIGN_MODE;
    public static final BooleanValue NSS_FORCE_HISTORY_CLAMP;
    public static final FloatValue NSS_HISTORY_THETA_MAX;
    public static final BooleanValue ENABLE_EXPERIMENTAL_ALGORITHMS;
    public static final BooleanValue ENABLE_DLSS_RAY_RECONSTRUCTION;
    public static final BooleanValue ENABLE_OPTISCALER;
    public static final StringValue OPTISCALER_DLL_PATH;

    public static final OperatingSystemType CURRENT_OS_TYPE = new OperatingSystem().type;
    public static final Runnable resolutionChangeCallback;
    private static volatile boolean unstableIncompatibleShaderSupportStartup;
    private static volatile boolean startupOptionsFrozen;

    static {
        ModConfigSpecBuilder builder = new ModConfigSpecBuilder();

        Supplier<String> defaultAlgoSupplier = () -> getDefaultAlgorithm().codeName;

        ENABLE_UPSCALE = builder.defineBoolean(
                "enable_upscale",
                () -> true,
                "Enable super-resolution upscaling"
        );
        PRESENTATION_BACKEND = builder.defineEnum(
                "presentation/backend",
                PresentationBackendType.class,
                () -> PresentationBackendType.OPENGL,
                "Presentation backend. Requires a game restart."
        );
        UPSCALE_RATIO = builder.defineFloat(
                "upscale_ratio",
                () -> 1.7f,
                "Upscale ratio factor",
                value -> value >= 0.5f && value <= 4.0f
        );
        UPSCALE_ALGO = builder.defineString(
                "upscale_algo",
                defaultAlgoSupplier,
                "Algorithm used for upscaling",
                value -> {
                    if (value == null) {
                        return false;
                    }
                    AlgorithmDescription<?> algo = AlgorithmRegistry.getDescriptionByID(value);
                    return algo != null && algo.getExtraResources().checkAll(SuperResolutionConstants.NATIVE_LIBRARIES_DIR).isEmpty();
                }
        );
        SHARPNESS = builder.defineFloat(
                "sharpness",
                () -> 0.55f,
                "Sharpness adjustment factor",
                value -> value >= 0.0f && value <= 1.0f
        );

        CAPTURE_MODE = builder.defineEnum(
                "capture_mode",
                CaptureMode.class,
                () -> CaptureMode.A,
                "Screen capture mode"
        );

        PAUSE_GAME_ON_GUI = builder.defineBoolean(
                "pause_game_on_gui",
                () -> false,
                "Pause game when GUI is open"
        );

        AUTO_HIDE_SHADERPACK_DISABLED_ALGORITHMS = builder.defineBoolean(
                "auto_hide_shaderpack_disabled_algorithms",
                () -> false,
                "Hide algorithms explicitly disabled by the active V3 shader pack from the algorithm selector"
        );

        INJECT_POST_CHAIN_BLACKLIST = builder.defineStringList(
                "inject_post_chain_blacklist",
                ArrayList::new,
                "List of post-processing chains to skip injection",
                value -> value != null && !value.isEmpty()
        );

        FLIP_VK_GL_INTEROP_RESOURCES_Y = builder.defineBoolean(
                "flip_vk_gl_interop_resources_y",
                () -> false,
                "Flip Vulkan-OpenGL interop upscaling resources on the Y axis"
        );
        /*
         * Arm NSS 的重投影在 shader 里与官方一致写成
         *     `reproj_uv = uv + motion * InvDims`（**加号**，见 ffx_nss_preprocess.h:890、
         *     ffx_nss_postprocess.h:778）；
         * 官方示例传 `motionVectorScale = -renderSize`，经 scale 后 motion 成为
         * **backward 屏幕空间像素位移**（user_guide.md:807）。
         * 而 SR 的光影接口原样搬运光影的 motion buffer（backward 归一化 UV，
         * 且 Sundial 这类光影在 `#if SR_ENABLE` 下还多乘了一次 upscaleRatio）。
         * 因此宿主只需补 **幅度** 因子 `renderSize ÷ upscaleRatio`
         * （= `renderSize × render / screen`），**符号不要动** —— 由 shader 侧的 `+` 决定。
         *
         * ★ 教训：此处注释曾误写成「三个 shader 都是 `uv - motion`（减号）」，
         *   那是**对自身代码的循环论证**（把自己写的减号当成官方事实），
         *   并据此在宿主侧补负号 → 两次翻转抵消，净效果为零。
         *   若再"发现抖动"就加负号，会重犯此错。
         *
         * 这里保留一个可调倍率用于对照测试：
         *   1.0  = 上面推导出的默认值（推荐）
         *  -1.0  = 翻转重投影方向（仅用于诊断「拖影方向是否相反」）
         *   0.0  = 完全关闭重投影
         *  0.5 / 2.0 = 验证光影是否多乘/少乘了 upscaleRatio
         */
        NSS_MOTION_VECTOR_SCALE = builder.defineFloat(
                "nss_motion_vector_scale",
                () -> 1.0f,
                "Arm NSS: multiplier applied when converting the shader pack motion-vector buffer "
                        + "into Arm's expected backward screen-space pixel displacement. "
                        + "1.0 = derived default; -1.0 = flip direction; 0 = disable reprojection (debug).",
                value -> value != null && value >= -64.0f && value <= 64.0f
        );

        /*
         * ───────────── 光影侧 jitter 幅度补偿（默认关闭） ─────────────
         *
         * 光影（以 Sundial 系为例）把 jitter 交给 SR 提供：
         *
         *   shaders.properties:  uniform.vec2.texelSize = vec2(1.0 / viewWidth, 1.0 / viewHeight)
         *   libs/Uniform.glsl:   vec2 taaOffset = SRJitterOffset * texelSize * 2.0 * vec2(1.0, -1.0);
         *   programs/gbuffers/*.vert:  gl_Position.xy += taaOffset * gl_Position.w;
         *
         * ★ 实证结论（2026-09-25，实测注入后抖动加剧，已回退）：
         * Iris 的 `viewWidth`/`viewHeight` 是**当前 pass 的 render target 尺寸**
         * （见 irisapi NewCompositeRenderer: `pass.viewWidth = passWidth = target.getWidth()`），
         * 在 SR 环境下即**渲染分辨率**（960x495 实测），而不是物理窗口尺寸。
         * 于是：
         *     taaOffset_ndc      = SRJitterOffset * (1 / renderSize) * 2
         *     几何位移(渲染像素) = taaOffset_ndc * renderSize / 2 = SRJitterOffset
         * 即光影施加的位移**本来就等于**算法侧收到的 `_JitterOffset`，无需补偿。
         * 曾按「screenSize = 物理窗口」的假设乘过 upscaleRatio，结果 jitter 翻倍、
         * 画面剧烈抖动 —— 因此本项默认 0.0（关闭）。
         *
         * 保留该开关仅用于将来排查其他光影变体：
         *   0.0  = 不补偿（默认，正确行为）
         *   1.0  = 按 screenSize / renderSize 补偿
         *  -1.0  = 补偿且取反
         *   其它 = 固定倍率
         */
        SHADERPACK_JITTER_SCALE = builder.defineFloat(
                "shaderpack_jitter_scale",
                () -> 0.0f,
                "Multiplier for the jitter handed to the shader pack's SRJitterOffset. "
                        + "0.0 = raw, no compensation (default and correct: Iris viewWidth is the pass "
                        + "render-target size, so the shader pack already applies the right amount); "
                        + "1.0 = compensate by screenSize/renderSize; -1.0 = compensate and flip; "
                        + "any other value = fixed multiplier.",
                value -> value != null && value >= -64.0f && value <= 64.0f
        );

        /*
         * NSS 的图形 pass 里，采样纹理的布局转换默认发生在 render pass **内部**
         * （VulkanCommandDecoder.bindPipeline -> prepareDescriptorResources），
         * 这违反 VUID-vkCmdPipelineBarrier-oldLayout-01181（pass 内 barrier 的
         * oldLayout 必须等于 newLayout）。驱动拒绝该 barrier，但状态跟踪仍被
         * 乐观更新，导致后续采样读到未转换布局的陈旧数据 —— 表现为抖动/闪烁。
         *
         * 开启本项后，会在 beginRenderPass 之前先把采样纹理转换到位，
         * pass 内那次因状态已一致而变成空操作。
         *   true  = pass 前预转换（默认，符合规范）
         *   false = 保持旧行为（对照测试用）
         */
        NSS_PREPARE_RESOURCES_BEFORE_PASS = builder.defineBoolean(
                "nss_prepare_resources_before_pass",
                () -> true,
                "Arm NSS: transition sampled textures to their target layout before beginRenderPass "
                        + "instead of inside the render pass (avoids illegal in-pass pipeline barriers "
                        + "that make the driver drop the transition and leave stale texture layouts)."
        );

        /*
         * ───── NSS 抖动排查开关（仅诊断，正常游戏保持 false） ─────
         *
         * 打开后每帧把 NSS 的 reset 置 true（历史全部丢弃、每帧重建）。
         * 用来把抖动来源二分：
         *   抖动消失 → 时序/history 累积链路（重投影、反馈乒乓、luma 历史）
         *   抖动依旧 → 单帧链路（张量打包、推理、后处理混合、布局转换）
         *
         * ★ 性能警告：reset 帧会复现官方 ffx_nss.cpp:1506-1528 的 GPU 清空，
         *   一次清 7 张纹理共约 23.5 MB（1080p）。正常游戏只在世界加载/传送时
         *   触发，可忽略；但本开关打开后**每帧**都清 → 约 1.4 GB/s 额外写入，
         *   帧率会明显下降。这是诊断开关的固有代价，不是 bug。
         */
        NSS_FORCE_HISTORY_RESET = builder.defineBoolean(
                "nss_force_history_reset",
                () -> false,
                "Arm NSS (diagnostic): force reset=true every frame so history is rebuilt from scratch. "
                        + "Use only to bisect jitter source; keep false for normal play. "
                        + "NOTE: each reset frame GPU-clears ~23.5MB of history resources, "
                        + "so leaving this on costs ~1.4GB/s of extra writes."
        );

        /*
         * ───────────── NSS 稳定模式开关（2026-09-27 抖动/鬼影攻坚的产物） ─────────────
         *
         * 背景：本轮二分实验（jitter 归零 / 取反、unjitter 取反、theta=0）定位到
         *   1) jitter 存在即微抖（与符号、theta、unjitter 方向均无关）
         *      ⇒ 根因在「jittered 输入 → KPN 推理 → 输出网格」的系统性映射，
         *         与鬼影（theta 异常）大概率同源：KPN 输入张量的 feedback/history 通道。
         *   2) theta 强制收缩（=0）实测可完全压制鬼影。
         *
         * 在 KPN 输入链修复前，以下两项默认开启（稳定优先）：
         */
        NSS_DISABLE_JITTER = builder.defineBoolean(
                "nss_disable_jitter",
                () -> true,
                "Arm NSS: disable the sub-pixel jitter sequence entirely (both shader-pack "
                        + "SRJitterOffset and NSS _JitterOffset). Eliminates the residual micro-jitter "
                        + "observed with jittered input (root cause under investigation in the KPN "
                        + "input chain). Cost: no sub-pixel sampling, slightly lower upscale sharpness. "
                        + "Re-enable after the KPN input-chain fix."
        );

        /*
         * ───────────── jitter 符号实验矩阵（抖动排查用，诊断开关） ─────────────
         *
         * 背景：jitter 启用时仍有残余抖动（_LutOffset 修复后依旧）。嫌疑：
         * 光影施加几何抖动时带 (1,-1) 的 y 翻转（taaOffset = SRJitterOffset
         * × texelSize × 2 × vec2(1.0,-1.0)），而 NSS 内部消费 jitter
         * （unjitter / LUT 生成 / motion）时未同步该翻转 —— 符号约定错配。
         *
         * 本开关对「传入 NSS 的 jitter 向量」施加符号变换（x/y 独立翻转）：
         *   0 = ( jx,  jy)  原样（当前行为，基线）
         *   1 = ( jx, -jy)  仅 y 翻转（对应光影的 (1,-1)）
         *   2 = (-jx,  jy)  仅 x 翻转
         *   3 = (-jx, -jy)  全翻转
         * 请依次测试 0→3，报告哪个档位下静止场景不再抖动。
         * 定位后此开关将被具体的符号修正替代。
         */
        NSS_JITTER_SIGN_MODE = builder.defineInt(
                "nss_jitter_sign_mode",
                () -> 0,
                "Arm NSS (diagnostic): sign transform applied to the jitter vector fed "
                        + "into NSS. 0=(+x,+y) raw, 1=(+x,-y), 2=(-x,+y), 3=(-x,-y). "
                        + "Cycle 0-3 and report which mode stops the residual jitter.",
                v -> v != null && v >= 0 && v <= 3
        );
        NSS_FORCE_HISTORY_CLAMP = builder.defineBoolean(
                "nss_force_history_clamp",
                () -> true,
                "Arm NSS: clamp theta in ClampHistoryToStats to NSS_HISTORY_THETA_MAX so abnormally "
                        + "high history-trust (ghosting) is suppressed while normal pixels keep their "
                        + "temporal smoothing. Measured to remove the on-screen ghosting caused by "
                        + "abnormal theta output (KPN input-chain root cause). Re-enable full theta "
                        + "after the KPN input-chain fix."
        );
        NSS_HISTORY_THETA_MAX = builder.defineFloat(
                "nss_history_theta_max",
                () -> 0.5f,
                "Upper bound for theta when nss_force_history_clamp is on. "
                        + "Lower = stronger anti-ghosting but noisier (weaker temporal denoising); "
                        + "higher = smoother but ghosting may reappear. 0.0 equals the old "
                        + "force-clamp behavior; tune between 0.3 and 0.8.",
                value -> value != null && value >= 0.0f && value <= 1.0f
        );

        THEME = builder.defineEnum(
                "theme",
                MaterialTheme.class,
                () -> MaterialTheme.Light,
                "Interface theme"
        );

        THEME_COLOR = builder.defineString(
                "theme_color",
                () -> "#78DC77",
                "Primary color for the interface theme",
                value -> value != null && value.matches("^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{8})$")
        );

        THEME_SCHEME_VARIANT = builder.defineEnum(
                "theme_scheme_variant",
                SchemeVariant.class,
                () -> SchemeVariant.FIDELITY,
                "Color scheme variant for the interface theme"
        );

        THEME_CONTRAST_LEVEL = builder.defineFloat(
                "theme_contrast_level",
                () -> 0.0f,
                "Contrast level for the interface theme (-1.0 to 1.0)",
                value -> value >= -1.0f && value <= 1.0f
        );

        DEBUG_DUMP_SHADER = builder.defineBoolean(
                "debug/debug_dump_shader",
                () -> false,
                "Dump shaders for debugging purposes"
        );

        SKIP_INIT_VULKAN = builder.defineBoolean(
                "debug/skip_init_vulkan",
                () -> !(CURRENT_OS_TYPE == OperatingSystemType.ANDROID || CURRENT_OS_TYPE == OperatingSystemType.MACOS),
                "Skip Vulkan initialization (auto-set based on OS)"
        );

        ENABLE_RENDER_DOC = builder.defineBoolean(
                "debug/enable_render_doc",
                () -> (CURRENT_OS_TYPE == OperatingSystemType.WINDOWS || CURRENT_OS_TYPE == OperatingSystemType.LINUX) && Platform.currentPlatform.isDevelopmentEnvironment(),
                "Enable RenderDoc integration (auto-disabled on incompatible OS)"
        );

        ENABLE_IMGUI = builder.defineBoolean(
                "debug/enable_imgui",
                () -> (CURRENT_OS_TYPE == OperatingSystemType.WINDOWS || CURRENT_OS_TYPE == OperatingSystemType.LINUX) && Platform.currentPlatform.isDevelopmentEnvironment(),
                "Enable ImGui debug interface (auto-disabled on incompatible OS)"
        );

        ENABLE_DEBUG = builder.defineBoolean(
                "debug/enable_debug",
                () -> false,
                "Enable debug mode"
        );
        ENABLE_PRESENT_INDICATOR = builder.defineBoolean(
                "debug/enable_present_indicator",
                () -> false,
                "Stamp a small square onto every presented frame (white = rendered, cyan = generated) to visualize frame generation cadence"
        );
        ENABLE_EXPERIMENTAL_ALGORITHMS = builder.defineBoolean(
                "experiment/enable_experimental_algorithms",
                () -> false,
                "Enable experimental algorithms"
        );
        ENABLE_DLSS_RAY_RECONSTRUCTION = builder.defineBoolean(
                "experiment/enable_dlss_ray_reconstruction",
                () -> false,
                "Register NVIDIA DLSS Ray Reconstruction during startup"
        );

        ENABLE_OPTISCALER = builder.defineBoolean(
                "optiscaler/enabled",
                () -> false,
                "Load the selected OptiScaler DLL during early game startup."
        );
        OPTISCALER_DLL_PATH = builder.defineString(
                "optiscaler/dll_path",
                () -> "",
                "Absolute path to the OptiScaler DLL file."
        );

        GENERATE_MOTION_VECTORS = builder.defineBoolean(
                "experiment/generate_motion_vectors",
                () -> false,
                "Generate motion vectors for advanced effects"
        );

        ENABLE_COMPAT_SHADER_COMPILER = builder.defineBoolean(
                "compat_shader_compiler",
                () -> {
                    try {
                        if (GL.getCapabilities() == null) {
                            return false;
                        }
                    } catch (Exception e) {
                        return false;
                    }
                    return RenderSystem.isOnRenderThread() ? (
                            GraphicsCapabilities.detectGpuVendor() == GpuVendor.Intel ||
                            !GraphicsCapabilities.hasGLExtension("GL_ARB_gl_spirv") ||
                            (GraphicsCapabilities.getGLVersion()[0] >= 4 && GraphicsCapabilities.getGLVersion()[1] < 2)
                    ) : false;
                },
                "This option enables the use of a compatibility shader compiler for compiling shaders when set to true."
        );

        ENABLE_DETAILED_PROFILING = builder.defineBoolean(
                "debug/enable_detailed_profiling",
                () -> false,
                "Enable more detailed performance profiling for advanced analysis."
        );
        ENABLE_UNSTABLE_INCOMPATIBLE_SHADER_SUPPORT = builder.defineBoolean(
                "enable_unstable_incompatible_shader_support",
                () -> false,
                "Enable unstable super resolution support for incompatible shader packs. Requires a game restart."
        );

        INTERNAL_TEXTURE_FORMAT = builder.defineEnum(
                "internal_texture_format",
                InternalTextureFormat.class,
                () -> InternalTextureFormat.AUTO,
                "The precision of the internal texture format affects video memory consumption: higher precision results in greater consumption, while lower precision leads to smaller consumption. Note: Excessively low precision may cause noticeable color banding in the image."
        );
        INTERNAL_TEXTURE_FORMAT.onChange(
                (oldValue, newValue) ->
                        SuperResolution.recreateAlgorithm()
        );

        LOW_LATENCY_MODE = builder.defineString(
                "low_latency/mode",
                () -> "superresolution:none",
                "Low latency mode",
                // Low-latency backends register after the main config is constructed
                // (and external backends may register even later). Resolve unknown
                // ids at runtime instead of overwriting persisted configuration here.
                value -> value != null && !value.isBlank()
        );
        LOW_LATENCY_MODE.onChange((oldValue, newValue) -> {
            LowLatency.setMode(newValue);
        });


        NVIDIA_REFLEX_MODE = builder.defineEnum(
                "low_latency/nv_reflex/mode",
                NVIDIAReflexMode.class,
                () -> NVIDIAReflexMode.OFF,
                "NVIDIA Reflex low latency mode"
        );

        NVIDIA_REFLEX_MODE.onChange((oldValue, newValue) -> {
            if ("superresolution:nv_reflex".equals(LowLatency.modeId()) && LowLatency.lowLatency() != null) {
                LowLatency.lowLatency().refresh();
            }
        });

        FRAME_GENERATION_MODE = builder.defineEnum(
                "frame_generation/mode",
                FrameGenerationMode.class,
                () -> FrameGenerationMode.OFF,
                "NVIDIA DLSS Frame Generation mode"
        );

        FRAME_GENERATION_PROVIDER = builder.defineString(
                "frame_generation/provider",
                () -> FrameGenerationDescriptions.AUTO_ID,
                "DLSS Frame Generation algorithm group. The automatic entry considers every registered group.",
                // Not checked against the registry: backends register later (and external
                // ones later still), so an id is only resolved when it is used. An
                // unknown id falls back to the automatic entry in FrameGeneration.mode().
                value -> value != null && !value.isBlank()
        );

        FRAME_GENERATION_BACKEND = builder.defineString(
                "frame_generation/backend",
                () -> FrameGenerationDescriptions.AUTO_ID,
                "Concrete DLSS Frame Generation backend preference. Auto keeps the registered backend priority.",
                value -> value != null && !value.isBlank()
        );

        SPECIAL = new SpecialConfigs(builder);
        Path configPath = SuperResolutionConstants.CONFIG_FILE;
        builder.configPath(configPath);
        SPEC = builder.build();
        resolutionChangeCallback = () -> {
            RenderHandlerManager.resize();
            Minecraft.getInstance().gameRenderer.resize(
                    RenderHandlerManager.getScreenWidth(),
                    RenderHandlerManager.getScreenHeight()
            );
            SuperResolution.getInstance().forceResize(
                    RenderHandlerManager.getScreenWidth(),
                    RenderHandlerManager.getScreenHeight()
            );

        };
    }

    public static synchronized void freezeStartupOptions() {
        if (startupOptionsFrozen) {
            return;
        }
        unstableIncompatibleShaderSupportStartup = ENABLE_UNSTABLE_INCOMPATIBLE_SHADER_SUPPORT.get();
        startupOptionsFrozen = true;
    }

    public static boolean isUnstableIncompatibleShaderSupportEnabledAtStartup() {
        return startupOptionsFrozen && unstableIncompatibleShaderSupportStartup;
    }

    public static boolean isEnableUnstableIncompatibleShaderSupport() {
        return ENABLE_UNSTABLE_INCOMPATIBLE_SHADER_SUPPORT.get();
    }

    public static void setEnableUnstableIncompatibleShaderSupport(boolean value) {
        ENABLE_UNSTABLE_INCOMPATIBLE_SHADER_SUPPORT.set(value);
    }

    public static AlgorithmDescription<?> getDefaultAlgorithm() {
        if (B3DVulkanBridge.isB3DVulkanBackend()) {
            return AlgorithmDescriptions.NONE;
        }
        try {
            GL.getCapabilities();
        } catch (Exception e) {
            return AlgorithmDescriptions.FSR1;
        }
        for (AlgorithmDescription<?> algorithmDescription : AlgorithmRegistry.getAlgorithmMap().values()) {
            if (algorithmDescription.requirement.check().support()) {
                return algorithmDescription;
            }
        }

        SuperResolution.LOGGER.info("WHAT? Your hardware does not support all algorithms?"); //WHAT?
        return AlgorithmDescriptions.NONE;
    }

    public static float getRenderScaleFactor() {
        return ENABLE_UPSCALE.get() ? 1 / UPSCALE_RATIO.get() : 1;
    }

    public static AlgorithmDescription<?> getUpscaleAlgorithm() {
        String algoName = UPSCALE_ALGO.get();
        AlgorithmDescription<?> algo = AlgorithmRegistry.getDescriptionByID(algoName);

        if (algo == null) {
            algo = getDefaultAlgorithm();
            UPSCALE_ALGO.set(algo.codeName);
        }

        if (!SuperResolution.isRenderingInitialized) {
            return algo;
        }

        if (!algo.requirement.check().support() && !Platform.currentPlatform.isDevelopmentEnvironment()) {
            SuperResolution.LOGGER.warn("Algorithm {} is unsupported; falling back to the default algorithm", algo.displayName);
            AlgorithmDescription<?> defaultAlgo = getDefaultAlgorithm();
            UPSCALE_ALGO.set(defaultAlgo.codeName);
            return defaultAlgo;
        }

        if (SRWorkModeManager.getCurrentState().disabledAlgorithms().contains(algo.codeName)) {
            SuperResolution.LOGGER.warn("Algorithm {} is disabled by the current shader pack; falling back to the default algorithm", algo.displayName);
            return getDefaultAlgorithm();
        }

        if (AlgorithmDescriptions.NONE.equals(algo)
                && !SRWorkModeManager.getCurrentState().supportsFrameGeneration()) {
            SuperResolution.LOGGER.warn("The current shader pack does not support frame-generation-only mode; the None algorithm is unavailable. Falling back to the default algorithm.");
            return getDefaultAlgorithm();
        }

        return algo;
    }

    public static synchronized boolean setUpscaleAlgorithm(AlgorithmDescription<?> newAlgo) {
        if (newAlgo == null) {
            newAlgo = getDefaultAlgorithm();
        }

        String algoName = UPSCALE_ALGO.get();
        AlgorithmDescription<?> currentAlgo = AlgorithmRegistry.getDescriptionByID(algoName);

        if (currentAlgo == newAlgo) {
            return true;
        }

        AbstractAlgorithm oldAlgorithmInstance = SuperResolution.currentAlgorithm;
        AlgorithmDescription<?> oldDescription = SuperResolution.algorithmDescription;

        try {
            UPSCALE_ALGO.set(newAlgo.codeName);
            SuperResolution.algorithmDescription = newAlgo;

            if (!SuperResolution.createAlgorithm()) {
                throw new RuntimeException("Failed to create algorithm");
            }
        } catch (Throwable failure) {
            SuperResolution.LOGGER.error(
                    "Failed to switch to algorithm {}; attempting rollback",
                    newAlgo.displayName,
                    failure);

            UPSCALE_ALGO.set(oldDescription != null ? oldDescription.codeName : AlgorithmDescriptions.NONE.codeName);
            SuperResolution.algorithmDescription = oldDescription;

            if (SuperResolution.currentAlgorithm == null && oldDescription != null) {
                boolean rollbackSucceeded = false;
                try {
                    rollbackSucceeded = SuperResolution.createAlgorithm();
                } catch (Throwable rollbackFailure) {
                    if (failure != rollbackFailure) {
                        failure.addSuppressed(rollbackFailure);
                    }
                }
                if (!rollbackSucceeded) {
                    try {
                        fallbackToNone();
                    } catch (Throwable fallbackFailure) {
                        if (failure != fallbackFailure) {
                            failure.addSuppressed(fallbackFailure);
                        }
                    }
                }
            }
            SuperResolution.rethrowAlgorithmError(failure);
            return false;
        }

        AbstractAlgorithm newAlgorithmInstance = SuperResolution.currentAlgorithm;
        if (oldAlgorithmInstance != null
                && newAlgorithmInstance != null
                && newAlgorithmInstance != oldAlgorithmInstance) {
            try {
                oldAlgorithmInstance.destroy();
            } catch (Throwable failure) {
                SuperResolution.retainAlgorithmForDestroyRetry(
                        oldAlgorithmInstance,
                        failure);
                SuperResolution.rethrowAlgorithmError(failure);
            }
        }
        return true;
    }

    private static void fallbackToNone() {
        SuperResolution.LOGGER.error("All rollback attempts failed; using the NONE algorithm");
        UPSCALE_ALGO.set(AlgorithmDescriptions.NONE.codeName);
        SuperResolution.algorithmDescription = AlgorithmDescriptions.NONE;
        SuperResolution.createAlgorithm();
    }

    public static boolean isEnableUpscaleOriginal() {
        return ENABLE_UPSCALE.get();
    }

    public static boolean isEnableUpscale() {
        if (!SRWorkModeManager.hasAvailableWorkMode()) {
            return false;
        }
        return isEnableUpscaleOriginal();
    }

    public static boolean setEnableUpscale(boolean value) {
        if (value && !SRWorkModeManager.hasAvailableWorkMode()) {
            return false;
        }
        boolean previousValue = isEnableUpscale();
        ENABLE_UPSCALE.set(value);
        if (previousValue != isEnableUpscale()) {
            resolutionChangeCallback.run();
            if (SRWorkModeManager.isCurrentMode(SRWorkModeManager.SHADER_COMPAT)) {
                SRWorkModeManager.reloadShaderPack();
            }
        }
        return true;
    }

    public static float getSharpness() {
        return SHARPNESS.get();
    }

    public static void setSharpness(float value) {
        SHARPNESS.set(value);
    }

    public static CaptureMode getCaptureMode() {
        return CAPTURE_MODE.get();
    }

    public static void setCaptureMode(CaptureMode value) {
        CAPTURE_MODE.set(value);
    }

    public static float getUpscaleRatio() {
        return Math.max(UPSCALE_RATIO.get(), getMinUpscaleRatio());
    }

    public static void setUpscaleRatio(float value) {
        boolean resolutionChanged = getUpscaleRatio() != value;
        value = Math.max(value, getMinUpscaleRatio());
        UPSCALE_RATIO.set(value);
        if (resolutionChanged) {
            resolutionChangeCallback.run();
        }
    }

    public static boolean isDebugDumpShader() {
        return DEBUG_DUMP_SHADER.get();
    }

    public static void setDebugDumpShader(boolean value) {
        DEBUG_DUMP_SHADER.set(value);
    }

    public static boolean isSkipInitVulkan() {
        return SKIP_INIT_VULKAN.get();
    }

    public static void setSkipInitVulkan(boolean value) {
        SKIP_INIT_VULKAN.set(value);
    }

    public static boolean isEnableRenderDoc() {
        return ENABLE_RENDER_DOC.get();
    }

    public static void setEnableRenderDoc(boolean value) {
        ENABLE_RENDER_DOC.set(value);
    }

    public static boolean isEnableImgui() {
        return ENABLE_IMGUI.get();
    }

    public static void setEnableImgui(boolean value) {
        ENABLE_IMGUI.set(value);
    }

    public static boolean isEnablePresentIndicator() {
        return ENABLE_PRESENT_INDICATOR.get();
    }

    public static void setEnablePresentIndicator(boolean value) {
        ENABLE_PRESENT_INDICATOR.set(value);
    }

    public static PresentationBackendType getPresentationBackend() {
        return PRESENTATION_BACKEND.get();
    }

    public static void setPresentationBackend(PresentationBackendType value) {
        PRESENTATION_BACKEND.set(value);
    }

    public static boolean isGenerateMotionVectors() {
        return false;
    }

    public static void setGenerateMotionVectors(boolean value) {
        GENERATE_MOTION_VECTORS.set(value);
    }

    public static boolean isPauseGameOnGui() {
        return PAUSE_GAME_ON_GUI.get();
    }

    public static void setPauseGameOnGui(boolean value) {
        PAUSE_GAME_ON_GUI.set(value);
    }

    public static boolean isAutoHideShaderpackDisabledAlgorithms() {
        return AUTO_HIDE_SHADERPACK_DISABLED_ALGORITHMS.get();
    }

    public static void setAutoHideShaderpackDisabledAlgorithms(boolean value) {
        AUTO_HIDE_SHADERPACK_DISABLED_ALGORITHMS.set(value);
    }

    public static List<String> getInjectPostChainBlackList() {
        return INJECT_POST_CHAIN_BLACKLIST.get();
    }

    public static void setInjectPostChainBlackList(List<String> value) {
        INJECT_POST_CHAIN_BLACKLIST.set(value);
    }

    public static boolean isEnableCompatShaderCompiler() {
        return ENABLE_COMPAT_SHADER_COMPILER.get() || ENABLE_COMPAT_SHADER_COMPILER.getDefault();
    }

    public static void setEnableCompatShaderCompiler(boolean value) {
        ENABLE_COMPAT_SHADER_COMPILER.set(value);
    }

    public static boolean isEnableDetailedProfiling() {
        return ENABLE_DETAILED_PROFILING.get();
    }

    public static void setEnableDetailedProfiling(boolean value) {
        ENABLE_DETAILED_PROFILING.set(value);
    }

    public static boolean isEnableDebug() {
        return ENABLE_DEBUG.get();
    }

    public static void setEnableDebug(boolean value) {
        ENABLE_DEBUG.set(value);
        GlDebug.setEnabled(value);
        VulkanDebug.setEnabled(value);
    }

    public static boolean isEnableExperimentalAlgorithms() {
        return ENABLE_EXPERIMENTAL_ALGORITHMS.get();
    }

    public static void setEnableExperimentalAlgorithms(boolean value) {
        ENABLE_EXPERIMENTAL_ALGORITHMS.set(value);
    }

    public static boolean isEnableDlssRayReconstruction() {
        return ENABLE_DLSS_RAY_RECONSTRUCTION.get();
    }

    public static void setEnableDlssRayReconstruction(boolean value) {
        ENABLE_DLSS_RAY_RECONSTRUCTION.set(value);
    }

    public static boolean isEnableOptiScaler() {
        return ENABLE_OPTISCALER.get();
    }

    public static void setEnableOptiScaler(boolean value) {
        ENABLE_OPTISCALER.set(value);
    }

    public static String getOptiScalerDllPath() {
        return OPTISCALER_DLL_PATH.get();
    }

    public static void setOptiScalerDllPath(String value) {
        OPTISCALER_DLL_PATH.set(value == null ? "" : value);
    }

    public static String getInternalTextureFormatGlslFormatQualifier() {
        return getInternalTextureFormat().getGlslFormatQualifier();
    }

    public static TextureFormat getInternalTextureFormat() {
        //user settings > shaderPack > default
        if (INTERNAL_TEXTURE_FORMAT.get() == InternalTextureFormat.AUTO) {
            SRWorkModeState state = SRWorkModeManager.getCurrentState();
            TextureFormat format = state.internalTextureFormat();
            return format == null ? TextureFormat.RGBA16F : format;
        }
        return INTERNAL_TEXTURE_FORMAT.get().format();
    }

    public static void setInternalTextureFormat(InternalTextureFormat format) {
        INTERNAL_TEXTURE_FORMAT.set(format);
    }

    public static MaterialTheme getTheme() {
        return THEME.get();
    }

    public static void setTheme(MaterialTheme value) {
        THEME.set(value);
    }

    public static boolean isFlipVkGlInteropResourcesY() {
        return FLIP_VK_GL_INTEROP_RESOURCES_Y.get();
    }

    public static float getNssMotionVectorScale() {
        try {
            Float v = NSS_MOTION_VECTOR_SCALE.get();
            return v != null ? v : 1.0f;
        } catch (Throwable t) {
            return 1.0f;
        }
    }

    /**
     * 光影侧 {@code SRJitterOffset} 的补偿倍率（详见 {@link #SHADERPACK_JITTER_SCALE} 的说明）。
     *
     * @return 0.0 表示不补偿（默认且为正确行为）；1.0 表示按 {@code screenSize / renderSize}
     * 补偿；其余值（含负值）直接作为固定倍率使用。
     */
    public static float getShaderpackJitterScale() {
        try {
            Float v = SHADERPACK_JITTER_SCALE.get();
            return v != null ? v : 0.0f;
        } catch (Throwable t) {
            return 0.0f;
        }
    }

    /**
     * NSS 图形 pass 是否在 {@code beginRenderPass} 之前预转换采样纹理布局。
     *
     * @return 默认 {@code true}，避免 render pass 内的非法 image barrier。
     */
    public static boolean isNssPrepareResourcesBeforePass() {
        try {
            Boolean v = NSS_PREPARE_RESOURCES_BEFORE_PASS.get();
            return v == null || v;
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 诊断开关：每帧强制把 NSS 历史视为失效（{@code reset = true}）。
     *
     * <p>用途是把抖动来源二分：
     * <ul>
     *   <li>打开后抖动消失 → 问题在<b>时序/history 累积链路</b>
     *       （重投影、反馈张量乒乓、luma 导数历史）。</li>
     *   <li>打开后抖动依旧 → 问题在<b>单帧链路</b>
     *       （预处理的张量打包、推理后端、后处理混合、布局转换）。</li>
     * </ul>
     * 仅在排查时开启，正常游戏务必保持 {@code false}（每帧重置会让画面失去时序信息）。
     *
     * @return 默认 {@code false}。
     */
    public static boolean isNssForceHistoryReset() {
        try {
            Boolean v = NSS_FORCE_HISTORY_RESET.get();
            return v != null && v;
        } catch (Throwable t) {
            return false;
        }
    }

    /** {@code true} = 全链禁用 jitter（稳定模式，见 NSS_DISABLE_JITTER 注释）。 */
    public static boolean isNssDisableJitter() {
        try {
            Boolean v = NSS_DISABLE_JITTER.get();
            return v != null && v;
        } catch (Throwable t) {
            return true;
        }
    }

    /** jitter 符号实验模式（0-3），见 {@code nss_jitter_sign_mode}。 */
    public static int getNssJitterSignMode() {
        try {
            Integer v = NSS_JITTER_SIGN_MODE.get();
            return v != null ? v : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** {@code true} = ClampHistoryToStats 强制 theta 上限钳制（稳定模式，见 NSS_FORCE_HISTORY_CLAMP 注释）。 */
    public static boolean isNssForceHistoryClamp() {
        try {
            Boolean v = NSS_FORCE_HISTORY_CLAMP.get();
            return v != null && v;
        } catch (Throwable t) {
            return true;
        }
    }

    /** theta 上限（稳定模式，见 NSS_HISTORY_THETA_MAX 注释）。 */
    public static float getNssHistoryThetaMax() {
        try {
            Float v = NSS_HISTORY_THETA_MAX.get();
            return v != null ? v : 0.5f;
        } catch (Throwable t) {
            return 0.5f;
        }
    }

    public static void setFlipVkGlInteropResourcesY(boolean value) {
        FLIP_VK_GL_INTEROP_RESOURCES_Y.set(value);
    }

    public static float getMinUpscaleRatio() {
        if (SRWorkModeManager.isCurrentMode(SRWorkModeManager.SHADER_COMPAT)) {
            return 1.0f;
        }
        if (
                getUpscaleAlgorithm().equals(AlgorithmDescriptions.DLSS) ||
                        getUpscaleAlgorithm().equals(AlgorithmDescriptions.DLSSRR) ||
                        getUpscaleAlgorithm().equals(AlgorithmDescriptions.XESS)

        ) {
            return 1.0f;
        }
        return 0.5f;
        /*
        int maxSize = 16384;
        if (Minecraft.getInstance().getWindow() == null) return 0.1f;
        double maxWidth = 1 / ((double) maxSize / Minecraft.getInstance().getWindow().getScreenWidth());
        double maxHeight = 1 / ((double) maxSize / Minecraft.getInstance().getWindow().getScreenHeight());
        return (float) Math.max(maxWidth, maxHeight);
        */
    }

    public static Color getThemeColor() {
        String colorStr = THEME_COLOR.get();
        try {
            return Color.from(colorStr);
        } catch (IllegalArgumentException e) {
            SuperResolution.LOGGER.warn("Invalid theme color configuration: {}; using the default color", colorStr);
            return Color.from("#78DC77");
        }
    }

    public static void setThemeColor(Color color) {
        THEME_COLOR.set(color.hex());
    }

    public static SchemeVariant getThemeSchemeVariant() {
        return THEME_SCHEME_VARIANT.get();
    }

    public static void setThemeSchemeVariant(SchemeVariant value) {
        THEME_SCHEME_VARIANT.set(value);
    }

    public static float getThemeContrastLevel() {
        return THEME_CONTRAST_LEVEL.get();
    }

    public static void setThemeContrastLevel(float value) {
        THEME_CONTRAST_LEVEL.set(Math.max(-1.0f, Math.min(1.0f, value)));
    }

    public static String getLowLatencyMode() {
        return LOW_LATENCY_MODE.get();
    }

    public static void setLowLatencyMode(String value) {
        LOW_LATENCY_MODE.set(value);
    }

    public static NVIDIAReflexMode getNVIDIAReflexMode() {
        return NVIDIA_REFLEX_MODE.get();
    }

    public static void setNVIDIAReflexMode(NVIDIAReflexMode value) {
        NVIDIA_REFLEX_MODE.set(value);
    }

    public static FrameGenerationMode getFrameGenerationMode() {
        return FRAME_GENERATION_MODE.get();
    }

    public static void setFrameGenerationMode(FrameGenerationMode value) {
        FRAME_GENERATION_MODE.set(value);
    }

    public static String getFrameGenerationProvider() {
        String stored = FRAME_GENERATION_PROVIDER.get();
        // Configurations written before the algorithm-group split named a concrete backend;
        // both of those backends now live in the DLSS FG group. Normalized on first read.
        String migrated = switch (stored) {
            case "superresolution:streamline", "wisteria:streamline", "wisteria:ngx" ->
                    FrameGenerationGroups.DLSS_FG.getId();
            default -> stored;
        };
        if (!migrated.equals(stored)) {
            if (FrameGenerationDescriptions.AUTO_ID.equals(FRAME_GENERATION_BACKEND.get())) {
                FRAME_GENERATION_BACKEND.set(
                        "wisteria:ngx".equals(stored)
                                ? "wisteria:ngx"
                                : "wisteria:streamline"
                );
            }
            FRAME_GENERATION_PROVIDER.set(migrated);
        }
        return migrated;
    }

    public static void setFrameGenerationProvider(String value) {
        FRAME_GENERATION_PROVIDER.set(value);
    }

    public static String getFrameGenerationBackend() {
        String stored = FRAME_GENERATION_BACKEND.get();
        if (!FrameGenerationDescriptions.AUTO_ID.equals(stored)) {
            return stored;
        }

        // Preserve configurations written before provider selection was split into an
        // algorithm group and a concrete backend preference.
        String legacyProvider = FRAME_GENERATION_PROVIDER.get();
        String migrated = switch (legacyProvider) {
            case "superresolution:streamline", "wisteria:streamline" -> "wisteria:streamline";
            case "wisteria:ngx" -> "wisteria:ngx";
            default -> stored;
        };
        if (!migrated.equals(stored)) {
            FRAME_GENERATION_BACKEND.set(migrated);
        }
        return migrated;
    }

    public static void setFrameGenerationBackend(String value) {
        FRAME_GENERATION_BACKEND.set(
                value == null || value.isBlank() ? FrameGenerationDescriptions.AUTO_ID : value
        );
    }
}
