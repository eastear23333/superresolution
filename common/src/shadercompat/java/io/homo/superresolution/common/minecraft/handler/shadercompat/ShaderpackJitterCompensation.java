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

package io.homo.superresolution.common.minecraft.handler.shadercompat;

import io.homo.superresolution.common.config.SuperResolutionConfig;
import io.homo.superresolution.common.minecraft.handler.RenderHandlerManager;
import org.joml.Vector2f;

/**
 * 光影侧 {@code SRJitterOffset} 的幅度补偿。
 *
 * <h2>问题</h2>
 * 光影（以 Sundial 系为代表）把 TAA jitter 交给 SR 提供，写法是：
 * <pre>
 *   shaders.properties          uniform.vec2.texelSize = vec2(1.0 / viewWidth, 1.0 / viewHeight)
 *   libs/Uniform.glsl           vec2 taaOffset = SRJitterOffset * texelSize * 2.0 * vec2(1.0, -1.0);
 *   programs/gbuffers/*.vert    gl_Position.xy += taaOffset * gl_Position.w;
 * </pre>
 * 其中 {@code viewWidth}/{@code viewHeight} 是**物理屏幕**尺寸，而几何实际渲染到的是
 * **渲染目标**（超分前的低分辨率）。把 {@code taaOffset} 从 NDC 折算回「渲染分辨率像素」：
 * <pre>
 *   taaOffset_ndc * renderSize / 2
 *     = SRJitterOffset * (1 / screenSize) * 2 * renderSize / 2
 *     = SRJitterOffset * renderSize / screenSize
 * </pre>
 * 也就是说，SR 若按 FSR2 标准（±0.5 渲染像素）原样注入 {@code SRJitterOffset}，
 * 光影实际只施加了 {@code renderSize / screenSize} 倍的偏移（2x 超分时为 0.5 倍）。
 * 而算法侧（如 NSS 的 {@code _JitterOffset}）仍按完整 ±0.5 像素做去抖与重投影，
 * 两者不自洽 → 相机静止时画面整体缓慢平移，运动时抖动。
 *
 * <h2>修复</h2>
 * 注入光影前把 jitter 乘以 {@code screenSize / renderSize}，使光影实际施加的
 * 渲染像素位移与算法侧收到的 {@code _JitterOffset} 完全一致。
 */
public final class ShaderpackJitterCompensation {

    private ShaderpackJitterCompensation() {
    }

    /**
     * 对即将注入光影的 jitter 做幅度补偿。
     *
     * <p>注意：实证表明 Iris 的 {@code viewWidth} 是当前 pass 的 render target 尺寸，
     * 光影本来就会施加正确幅度的 jitter，因此默认配置 {@code 0.0}（不补偿）即为正确行为。
     *
     * @param rawJitter 原始 jitter（渲染分辨率像素，FSR2 halton，±0.5）
     * @return 补偿后的 jitter
     */
    public static Vector2f compensate(Vector2f rawJitter) {
        if (rawJitter == null) {
            return null;
        }
        float scale = SuperResolutionConfig.getShaderpackJitterScale();

        // 0.0：不补偿（默认，正确行为）。
        if (scale == 0.0f) {
            return rawJitter;
        }

        if (scale == 1.0f || scale == -1.0f) {
            // ±1.0 为「按 screen/render 比例补偿」模式。
            float renderWidth = RenderHandlerManager.getRenderWidth();
            float renderHeight = RenderHandlerManager.getRenderHeight();
            float screenWidth = RenderHandlerManager.getScreenWidth();
            float screenHeight = RenderHandlerManager.getScreenHeight();

            float ratioX = renderWidth > 0.0f ? screenWidth / renderWidth : 1.0f;
            float ratioY = renderHeight > 0.0f ? screenHeight / renderHeight : 1.0f;

            float sign = scale < 0.0f ? -1.0f : 1.0f;
            return new Vector2f(
                    rawJitter.x * ratioX * sign,
                    rawJitter.y * ratioY * sign
            );
        }

        // 其它值：直接当作固定倍率。
        return new Vector2f(rawJitter.x * scale, rawJitter.y * scale);
    }
}
