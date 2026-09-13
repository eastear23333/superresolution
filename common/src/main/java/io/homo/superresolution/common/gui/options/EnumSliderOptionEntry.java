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

import io.homo.superresolution.common.gui.impl.Text;
import io.homo.superresolution.core.gui.core.ContainerWidget;
import io.homo.superresolution.core.gui.core.backends.render.RenderContext;
import io.homo.superresolution.core.gui.core.event.events.MouseEvent;
import io.homo.superresolution.core.gui.widgets.label.MaterialLabel;
import io.homo.superresolution.core.gui.widgets.sliders.MaterialSlider;
import io.homo.superresolution.core.gui.widgets.sliders.MaterialSliderSize;
import io.homo.superresolution.thirdparty.yoga.appliedenergistics.yoga.YogaAlign;
import io.homo.superresolution.thirdparty.yoga.appliedenergistics.yoga.YogaFlexDirection;
import io.homo.superresolution.thirdparty.yoga.appliedenergistics.yoga.YogaGutter;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * An option row backed by a slider that walks an ordered list of choices instead of a
 * dropdown. Built for the frame-generation multiplier, which grew to more entries than
 * a menu could show on screen: every step stays reachable by dragging, by clicking the
 * track and by scrolling the wheel while hovering the slider.
 */
public class EnumSliderOptionEntry<T> extends AbstractOptionEntry<T, EnumSliderOptionEntry<T>> {
    private static final float SLIDER_WIDTH = 250f;
    /** Past this many steps the tick dots merge into a solid line, so they are hidden. */
    private static final int MAX_VISIBLE_STEPS = 16;

    protected List<T> values;
    protected MaterialSlider slider;
    protected MaterialLabel valueLabel;
    protected ContainerWidget sliderContainer;
    protected Function<T, String> nameProvider;
    protected @Nullable Supplier<List<T>> valuesSupplier = null;
    protected final T originalValue;
    private boolean suppressSliderChangeEvent = false;
    private boolean dragging = false;

    public EnumSliderOptionEntry(
            Text name,
            T value,
            List<T> values,
            @Nullable Function<T, String> nameProvider
    ) {
        super(name, value);
        this.values = new ArrayList<>(values);
        if (this.values.isEmpty() && value != null) {
            this.values.add(value);
        }
        this.nameProvider = nameProvider != null ? nameProvider : String::valueOf;
        this.originalValue = value;
    }

    public EnumSliderOptionEntry<T> setValuesSupplier(@Nullable Supplier<List<T>> valuesSupplier) {
        this.valuesSupplier = valuesSupplier;
        return this;
    }

    public List<T> getValues() {
        return Collections.unmodifiableList(values);
    }

    public EnumSliderOptionEntry<T> setValues(List<T> newValues) {
        applyValues(newValues, value());
        return this;
    }

    public EnumSliderOptionEntry<T> setNameProvider(@Nullable Function<T, String> nameProvider) {
        this.nameProvider = nameProvider != null ? nameProvider : String::valueOf;
        return this;
    }

    /**
     * Re-reads the available choices. Skipped while the user drags the handle so the
     * list cannot be swapped out from under an in-progress gesture.
     */
    public EnumSliderOptionEntry<T> refreshDynamicValues() {
        if (valuesSupplier == null) {
            return this;
        }
        List<T> supplied = valuesSupplier.get();
        if (supplied != null) {
            applyValues(supplied, value());
        }
        return this;
    }

    public EnumSliderOptionEntry<T> setSelectedValue(T newValue) {
        if (newValue == null) {
            return this;
        }
        int newIndex = values.indexOf(newValue);
        if (newIndex < 0) {
            return this;
        }
        this.value = newValue;
        if (slider != null) {
            suppressSliderChangeEvent = true;
            try {
                slider.setValue(newIndex);
            } finally {
                suppressSliderChangeEvent = false;
            }
        }
        return this;
    }

    public boolean isEdited() {
        return !Objects.equals(value(), originalValue);
    }

    @Override
    protected void init() {
        this.container = new OptionContainerWidget(this);
        initLayout();
        initWidget();
    }

    @Override
    protected void initLayout() {
    }

    @Override
    protected void initWidget() {
        sliderContainer = new ContainerWidget();
        sliderContainer.layout().setFlexDirection(YogaFlexDirection.ROW);
        sliderContainer.layout().setAlignItems(YogaAlign.CENTER);
        sliderContainer.layout().setGap(YogaGutter.ALL, 12);

        valueLabel = MaterialLabel.create()
                .text(() -> nameFor(currentIndex()))
                .fontSize(14);
        valueLabel.layout().setMinWidth(50);

        slider = MaterialSlider.create(MaterialSliderSize.Small, SLIDER_WIDTH);
        slider.style().valueIndicator(true);
        slider.setMin(0);
        slider.setMax(Math.max(0, values.size() - 1));
        slider.setStep(1);
        slider.setValueIndicatorTextFormater(number -> nameFor(number.intValue()));
        slider.setValue(index());
        slider.onMousePress(event -> dragging = true);
        slider.onMouseRelease(event -> dragging = false);
        slider.onMouseScroll(this::onMouseScroll);
        slider.onChange(event -> {
            if (suppressSliderChangeEvent) {
                return;
            }
            applyIndex(((Number) event.getNewValue()).intValue());
        });

        sliderContainer.addChild(valueLabel);
        sliderContainer.addChild(slider);
        slider.setTooltipSupplier(this::resolveTooltip);

        container.addControl(sliderContainer);
    }

    @Override
    public void tick(RenderContext ctx) {
        if (!dragging) {
            refreshDynamicValues();
        }
        boolean enabled = updateRequirements();
        slider.setDisabled(!enabled);
        int steps = Math.max(0, values.size() - 1);
        slider.style().steps(steps > 0 && steps <= MAX_VISIBLE_STEPS);
    }

    /**
     * The frame dispatches the wheel straight to the widget under the cursor, but the
     * option list may also forward it through this entry; cover both by handing it to
     * the slider whenever the cursor is on it.
     */
    @Override
    public boolean mouseScroll(float x, float y, double scrollX) {
        if (slider != null && !slider.isDisabled() && slider.isHovered()) {
            return slider.mouseScroll(x, y, scrollX);
        }
        return super.mouseScroll(x, y, scrollX);
    }

    private void onMouseScroll(MouseEvent.MouseScrollEvent event) {
        if (slider == null || slider.isDisabled() || !slider.isHovered()) {
            return;
        }
        if (values.size() <= 1 || event.getScrollY() == 0f) {
            return;
        }
        // Claim the wheel so the surrounding scrollable page stays put while adjusting.
        event.consume();
        int next = clampIndex(currentIndex() + (event.getScrollY() > 0 ? 1 : -1));
        if (next == currentIndex()) {
            return;
        }
        slider.setValue(next);
    }

    private void applyIndex(int newIndex) {
        if (values.isEmpty()) {
            return;
        }
        int clamped = clampIndex(newIndex);
        T newValue = values.get(clamped);
        if (Objects.equals(newValue, this.value)) {
            return;
        }
        T oldValue = this.value;
        this.value = newValue;
        if (saveConsumer != null && !saveConsumer.apply(newValue)) {
            this.value = oldValue;
            suppressSliderChangeEvent = true;
            try {
                slider.setValue(index());
            } finally {
                suppressSliderChangeEvent = false;
            }
            return;
        }
        if (saveRunnable != null) {
            saveRunnable.run();
        }
    }

    private void applyValues(@Nullable List<T> newValues, @Nullable T preferredValue) {
        List<T> sanitizedValues = newValues == null ? Collections.emptyList() : new ArrayList<>(newValues);
        if (sanitizedValues.isEmpty()) {
            if (preferredValue != null) {
                sanitizedValues.add(preferredValue);
            } else if (!values.isEmpty()) {
                sanitizedValues.add(values.get(0));
            } else {
                return;
            }
        }

        if (sanitizedValues.equals(this.values)) {
            return;
        }

        T previousValue = preferredValue != null ? preferredValue : value();
        this.values = sanitizedValues;

        T selectedValue = sanitizedValues.contains(previousValue) ? previousValue : sanitizedValues.get(0);
        this.value = selectedValue;
        if (slider != null) {
            suppressSliderChangeEvent = true;
            try {
                slider.setMax(Math.max(0, sanitizedValues.size() - 1));
                slider.setValue(Math.max(0, sanitizedValues.indexOf(selectedValue)));
            } finally {
                suppressSliderChangeEvent = false;
            }
        }
    }

    /** Index the slider currently points at, including mid-drag positions. */
    private int currentIndex() {
        if (slider != null) {
            return clampIndex(slider.value().intValue());
        }
        return index();
    }

    private int index() {
        int index = values.indexOf(value);
        return index < 0 ? 0 : index;
    }

    private int clampIndex(int index) {
        if (values.isEmpty()) {
            return 0;
        }
        return Math.max(0, Math.min(values.size() - 1, index));
    }

    private String nameFor(int index) {
        if (values.isEmpty()) {
            return "";
        }
        return nameProvider.apply(values.get(clampIndex(index)));
    }
}
