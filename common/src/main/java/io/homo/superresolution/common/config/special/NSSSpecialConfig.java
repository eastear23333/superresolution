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

package io.homo.superresolution.common.config.special;

import io.homo.superresolution.api.SuperResolutionAPI;
import io.homo.superresolution.api.config.ModConfigSpecBuilder;
import io.homo.superresolution.api.config.values.single.EnumValue;
import io.homo.superresolution.common.SuperResolution;
import io.homo.superresolution.common.config.ConfigSpecType;
import io.homo.superresolution.common.config.enums.NSSModel;
import io.homo.superresolution.common.upscale.AlgorithmDescriptions;
import net.minecraft.network.chat.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Arm NSS 的专有配置 —— 目前只有一个「模型档位」选择器，
 * 与 {@link DLSSSpecialConfig} 的 {@code RENDER_PRESET} 放在同一处 UI。
 *
 * <p>为什么必须是独立轴：NSS v1_0_1 架构锁定 2x，超分比例上只有 2.0 一个可用值
 * （见 {@code AlgorithmDescriptions.NSS_QUALITY_PRESETS}）。模型档位决定的是
 * 权重、预处理分辨率与 KPN 通道数，<b>不改变放大比例</b>。
 */
public class NSSSpecialConfig extends SpecialConfig {

    public EnumValue<NSSModel> MODEL = specBuilder.defineEnum(
            "special/nss/model",
            NSSModel.class,
            () -> NSSModel.HIGH
    );

    public NSSSpecialConfig(ModConfigSpecBuilder specBuilder) {
        super(specBuilder);
    }

    @Override
    protected void buildDescriptions(Map<String, SpecialConfigDescription<?>> map) {
        map.put(
                "model",
                new SpecialConfigDescription<NSSModel>()
                        .setKey("model")
                        .setName(Component.translatable("superresolution.screen.config.special.nss.model.name"))
                        .setTooltip(v -> Optional.of(Component.translatable(v.isImplemented()
                                ? "superresolution.screen.config.special.nss.model.tooltip"
                                : "superresolution.screen.config.special.nss.model.tooltip.unimplemented")))
                        .setValueNameSupplier(v -> Optional.of(v.getDisplayName()))
                        .setType(ConfigSpecType.ENUM)
                        .setClazz(NSSModel.class)
                        .setDefaultValue(NSSModel.HIGH)
                        // 未接入的档位在 UI 中置灰，避免选到会产出错误画面的配置
                        .setItemEnableRequirement(NSSModel::isImplemented)
                        .setSaveConsumer((v) -> {
                            if (getSpecialConfigs().NSS.MODEL.get() != v) {
                                getSpecialConfigs().NSS.MODEL.set(v);
                                // 换档要重建 DP4A 上下文与全部自产纹理/缓冲
                                if (SuperResolutionAPI.getCurrentAlgorithmDescription() == AlgorithmDescriptions.NSS) {
                                    SuperResolution.recreateAlgorithm();
                                }
                            }
                        })
                        .setValue(MODEL.get())
                        .setValueSupplier(MODEL::get)
        );
    }
}
