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

package io.homo.superresolution.common.config.enums;

import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * Arm NSS 的模型档位。
 *
 * <p><b>这是与「超分比例」完全独立的另一个轴</b>，不要和 {@code QualityPreset} 混淆：
 * <ul>
 *   <li><b>超分比例</b>决定实际渲染分辨率（渲染分辨率 = 原始分辨率 / 比例）。
 *       NSS v1_0_1 架构锁定 2x，所以这个轴上只有一个 2.0 档。</li>
 *   <li><b>模型档位</b>（本枚举）决定用哪份烘焙权重、预处理跑在哪一档分辨率上、
 *       KPN 通道数多少。<b>三档的输出分辨率都是 2x</b>，差别只在预处理与
 *       后处理的配置。</li>
 * </ul>
 *
 * <p>取值对应 Arm scenario 的三份 config
 * （{@code nss-model/scenario/configs/960x540_1920x1080_{high,mid,low}_fragment.json}）：
 *
 * <table border="1">
 *   <caption>三档差异</caption>
 *   <tr><th>档</th><th>DP4A 权重</th><th>预处理输出</th><th>深度散射输出</th>
 *       <th>KPN 通道</th><th>pre/post build options</th></tr>
 *   <tr><td>HIGH</td><td>high（KPN 6x6）</td><td>= 渲染分辨率</td><td>= 渲染/2</td>
 *       <td>36</td><td>{@code FULL_RES_LUMA_DERIVATIVE=1}, {@code FILTER_MODE=0}</td></tr>
 *   <tr><td>MID</td><td>mid_low（KPN 4x4）</td><td>= 渲染/2</td><td>= 渲染/4</td>
 *       <td>16</td><td>{@code INPUT_LAYOUT=1}, {@code FILTER_MODE=1},
 *       {@code USE_HISTORY_CATMULL=1}</td></tr>
 *   <tr><td>LOW</td><td>mid_low（同上）</td><td>= 渲染/2</td><td>= 渲染/4</td>
 *       <td>16</td><td>同 MID，但 {@code USE_HISTORY_CATMULL=0}</td></tr>
 * </table>
 *
 * <p>注意 MID 与 LOW <b>共用同一份 .vgf 权重</b>，差别只在后处理是否用
 * Catmull-Rom 历史重采样。
 */
public enum NSSModel {

    /** Arm high 档：全分辨率预处理，KPN 6x6 = 36 通道。 */
    HIGH(0, 1, 36, true),

    /** Arm mid 档：半分辨率预处理 + 去遮挡掩码，KPN 4x4 = 16 通道，历史用 Catmull-Rom。 */
    MID(1, 2, 16, true),

    /** Arm low 档：同 MID 的权重与分辨率，历史不做 Catmull-Rom（更省）。 */
    LOW(1, 2, 16, false);

    private final int qualityCode;
    private final int preprocessDownscale;
    private final int kpnChannels;
    private final boolean historyCatmull;

    NSSModel(int qualityCode, int preprocessDownscale, int kpnChannels, boolean historyCatmull) {
        this.qualityCode = qualityCode;
        this.preprocessDownscale = preprocessDownscale;
        this.kpnChannels = kpnChannels;
        this.historyCatmull = historyCatmull;
    }

    /** 对应 native 侧 {@code NssDp4aQuality}：0 = HIGH，1 = MID_LOW。 */
    public int getQualityCode() {
        return qualityCode;
    }

    /** 预处理输出 = 渲染分辨率 / 本值（HIGH 为 1，MID/LOW 为 2）。 */
    public int getPreprocessDownscale() {
        return preprocessDownscale;
    }

    /** 深度散射输出 = 渲染分辨率 / 本值（HIGH 为 2，MID/LOW 为 4）。 */
    public int getDepthScatterDownscale() {
        return preprocessDownscale * 2;
    }

    /** KPN 张量通道数（HIGH 36 / MID,LOW 16）。 */
    public int getKpnChannels() {
        return kpnChannels;
    }

    /** 后处理是否对历史做 Catmull-Rom 重采样（Arm 的 {@code NSS_USE_HISTORY_CATMULL}）。 */
    public boolean isHistoryCatmull() {
        return historyCatmull;
    }

    /** 预处理是否走半分辨率布局（Arm 的 {@code NSS_INPUT_LAYOUT == 1}）。 */
    public boolean isHalfResolutionInputLayout() {
        return preprocessDownscale != 1;
    }

    /**
     * 该档的 Java 侧管线是否已经接好。
     *
     * <p>三档均已接入（2026-09-27）：
     * <ul>
     *   <li>HIGH：全分辨率预处理 + dense 滤波（FILTER_MODE=0）</li>
     *   <li>MID：半分辨率预处理（INPUT_LAYOUT=1）+ disocclusion LQ pass
     *       + sparse 滤波（FILTER_MODE=1）+ Catmull-Rom 历史</li>
     *   <li>LOW：同 MID，历史不用 Catmull-Rom（USE_HISTORY_CATMULL=0）</li>
     * </ul>
     * 非 2x 超分比例仍走 FILTER_MODE=2/3（dynamic LUT），待 offset LUT
     * 生成管线接入后开放。
     */
    public boolean isImplemented() {
        return true;
    }

    /** UI 显示名（本地化）。 */
    public Component getDisplayName() {
        return Component.translatable(
                "superresolution.screen.config.special.nss.model." + name().toLowerCase(Locale.ROOT));
    }
}
