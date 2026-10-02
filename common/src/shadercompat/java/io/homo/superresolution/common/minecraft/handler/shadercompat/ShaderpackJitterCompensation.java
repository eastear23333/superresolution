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

import io.homo.superresolution.common.minecraft.handler.RenderHandlerManager;
import org.joml.Vector2f;

/**
 * 光影侧 {@code SRJitterOffset} 的幅度补偿。
 *
 * <h2>背景</h2>
 * 光影（以 Sundial 系为代表）把 TAA jitter 交给 SR 提供，写法是：
 * <pre>
 *   shaders.properties          uniform.vec2.texelSize = vec2(1.0 / viewWidth, 1.0 / viewHeight)
 *   libs/Uniform.glsl           vec2 taaOffset = SRJitterOffset * texelSize * 2.0 * vec2(1.0, -1.0);
 *   programs/gbuffers/*.vert    gl_Position.xy += taaOffset * gl_Position.w;
 * </pre>
 *
 * <h2>结论：默认不补偿</h2>
 * 实证（2026-09-25，曾注入补偿，抖动反而加剧，已回退）：Iris 的
 * {@code viewWidth}/{@code viewHeight} 是<b>当前 pass 的 render target 尺寸</b>
 * （见 irisapi NewCompositeRenderer：{@code pass.viewWidth = passWidth = target.getWidth()}），
 * 在 SR 环境下即<b>渲染分辨率</b>，而非物理窗口尺寸。于是：
 * <pre>
 *   taaOffset_ndc      = SRJitterOffset * (1 / renderSize) * 2
 *   几何位移(渲染像素) = taaOffset_ndc * renderSize / 2 = SRJitterOffset
 * </pre>
 * 即光影实际施加的位移<b>本来就等于</b>算法侧收到的 {@code _JitterOffset}，无需任何补偿
 * （{@link #SHADERPACK_JITTER_SCALE} = 0.0）。
 *
 * <p>补偿分支保留，供将来排查其它光影变体（例如某个光影确实按物理窗口尺寸计算
 * {@code texelSize} 的情况）。
 */
public final class ShaderpackJitterCompensation {

    /**
     * 光影侧 jitter 的补偿倍率。
     *
     * <p>原为 config 旋钮 {@code shaderpack_jitter_scale}，已按默认行为固化为常量。
     * 取值语义：
     * <ul>
     *   <li>{@code 0.0} = 不补偿（默认，实证正确的行为，见类注释）</li>
     *   <li>{@code 1.0} = 按 {@code screenSize / renderSize} 补偿</li>
     *   <li>{@code -1.0} = 按比例补偿且取反</li>
     *   <li>其它值 = 直接作为固定倍率</li>
     * </ul>
     */
    private static final float SHADERPACK_JITTER_SCALE = 0.0f;

    private ShaderpackJitterCompensation() {
    }

    /**
     * 对即将注入光影的 jitter 做幅度补偿。
     *
     * <p>当前倍率为 {@code 0.0}（不补偿）：Iris 的 {@code viewWidth} 是当前 pass 的
     * render target 尺寸，光影本来就会施加正确幅度的 jitter（推导见类注释）。
     *
     * @param rawJitter 原始 jitter（渲染分辨率像素，FSR2 halton，±0.5）
     * @return 补偿后的 jitter；{@code null} 时返回 {@code null}
     */
    public static Vector2f compensate(Vector2f rawJitter) {
        if (rawJitter == null) {
            return null;
        }
        float scale = SHADERPACK_JITTER_SCALE;

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
