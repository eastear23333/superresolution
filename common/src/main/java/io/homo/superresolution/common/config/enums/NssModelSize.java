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
package io.homo.superresolution.common.config.enums;

import net.minecraft.network.chat.Component;

/**
 * Arm NSS neural network model size selection.
 * Maps to FfxApiNssShaderQualityMode in the NSS SDK.
 *
 * L (Large)     -> qualityMode=0 (QUALITY)      -> nss_v1_0_1_high_int8.vgf   (36ch KPN, full-res, Catmull)
 * M (Medium)    -> qualityMode=1 (BALANCED)     -> nss_v1_0_1_mid_low_int8.vgf (16ch KPN, half-res, Catmull)
 * S (Small)     -> qualityMode=2 (PERFORMANCE)  -> nss_v1_0_1_mid_low_int8.vgf (16ch KPN, half-res, no Catmull)
 */
public enum NssModelSize {
    L(0, Component.literal("L")),
    M(1, Component.literal("M")),
    S(2, Component.literal("S"));

    private final int code;
    private final Component component;

    NssModelSize(int code, Component component) {
        this.code = code;
        this.component = component;
    }

    public int getCode() {
        return code;
    }

    public Component getComponent() {
        return component;
    }
}
