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

package io.homo.superresolution.common.gui.options;

import com.google.common.collect.ImmutableList;
import io.homo.superresolution.common.gui.impl.Text;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Builder for a slider-driven choice row, see {@link EnumSliderOptionEntry}.
 */
public class EnumSliderBuilder<T, SELF extends EnumSliderBuilder<T, SELF>>
        extends AbstractOptionBuilder<T, EnumSliderOptionEntry<T>, SELF> {
    protected ImmutableList<T> values;
    protected Function<T, String> nameProvider;
    protected Supplier<List<T>> valuesSupplier = null;

    public EnumSliderBuilder(Text name, T value, T[] valuesArray) {
        super(name, value);
        this.values = ImmutableList.copyOf(valuesArray);
    }

    public Supplier<List<T>> getValuesSupplier() {
        return valuesSupplier;
    }

    @SuppressWarnings("unchecked")
    public SELF setValuesSupplier(Supplier<List<T>> valuesSupplier) {
        this.valuesSupplier = valuesSupplier;
        return (SELF) this;
    }

    @SuppressWarnings("unchecked")
    public SELF setValues(T[] valuesArray) {
        this.values = ImmutableList.copyOf(valuesArray);
        return (SELF) this;
    }

    @SuppressWarnings("unchecked")
    public SELF setNameProvider(Function<T, String> nameProvider) {
        this.nameProvider = nameProvider;
        return (SELF) this;
    }

    @Override
    public EnumSliderOptionEntry<T> build() {
        EnumSliderOptionEntry<T> entry = new EnumSliderOptionEntry<>(
                this.name,
                this.value,
                this.values,
                nameProvider
        );
        entry.setValuesSupplier(valuesSupplier);
        return finishBuild(entry);
    }
}
