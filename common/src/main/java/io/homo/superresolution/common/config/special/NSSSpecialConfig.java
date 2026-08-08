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
package io.homo.superresolution.common.config.special;

import io.homo.superresolution.api.SuperResolutionAPI;
import io.homo.superresolution.api.config.ModConfigSpecBuilder;
import io.homo.superresolution.api.config.values.single.EnumValue;
import io.homo.superresolution.common.SuperResolution;
import io.homo.superresolution.common.config.ConfigSpecType;
import io.homo.superresolution.common.config.enums.NssModelSize;
import io.homo.superresolution.common.upscale.AlgorithmDescriptions;
import net.minecraft.network.chat.Component;

import java.util.Map;
import java.util.Optional;

public class NSSSpecialConfig extends SpecialConfig {
    public EnumValue<NssModelSize> MODEL_SIZE = specBuilder.defineEnum(
            "special/nss/model_size",
            NssModelSize.class,
            () -> NssModelSize.M
    );

    public NSSSpecialConfig(ModConfigSpecBuilder specBuilder) {
        super(specBuilder);
    }

    @Override
    protected void buildDescriptions(Map<String, SpecialConfigDescription<?>> map) {
        map.put(
                "model_size",
                new SpecialConfigDescription<NssModelSize>()
                        .setKey("model_size")
                        .setName(Component.translatable("superresolution.screen.config.special.nss.model_size.name"))
                        .setTooltip(Component.translatable("superresolution.screen.config.special.nss.model_size.tooltip"))
                        .setType(ConfigSpecType.ENUM)
                        .setClazz(NssModelSize.class)
                        .setDefaultValue(NssModelSize.M)
                        .setSaveConsumer((v) -> {
                            if (getSpecialConfigs().NSS.MODEL_SIZE.get() != v) {
                                getSpecialConfigs().NSS.MODEL_SIZE.set(v);
                                if (SuperResolutionAPI.getCurrentAlgorithmDescription() == AlgorithmDescriptions.NSS) {
                                    SuperResolution.recreateAlgorithm();
                                }
                            }
                        })
                        .setValueNameSupplier((variant) -> switch ((NssModelSize) variant) {
                            case L -> Optional.of(Component.literal("L (High)"));
                            case M -> Optional.of(Component.literal("M (Balanced)"));
                            case S -> Optional.of(Component.literal("S (Performance)"));
                        })
                        .setValue(MODEL_SIZE.get())
        );
    }
}
