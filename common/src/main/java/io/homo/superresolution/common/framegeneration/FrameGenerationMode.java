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

package io.homo.superresolution.common.framegeneration;

import io.homo.superresolution.core.streamline.StreamlineTypes;

public enum FrameGenerationMode {
	OFF(StreamlineTypes.DlssGMode.OFF, 0, "superresolution.screen.config.options.frame_generation.off"),
	AUTO(StreamlineTypes.DlssGMode.AUTO, 1, "superresolution.screen.config.options.frame_generation.auto"),
	/* Every multiplier entry uses the two-argument constructor: its translation key is
	 * derived from the multiplier it represents, which is generatedFrameCount + 1
	 * (x2 for 1 generated frame, x32 for 31). The stock provider only ever reports a
	 * ceiling of a handful; the rest are offered when a backend reports more (an
	 * unlocked XeSS-FG build reports up to 31). */
	X2(StreamlineTypes.DlssGMode.ON, 1),
	X3(StreamlineTypes.DlssGMode.ON, 2),
	X4(StreamlineTypes.DlssGMode.ON, 3),
	X5(StreamlineTypes.DlssGMode.ON, 4),
	X6(StreamlineTypes.DlssGMode.ON, 5),
	X7(StreamlineTypes.DlssGMode.ON, 6),
	X8(StreamlineTypes.DlssGMode.ON, 7),
	X9(StreamlineTypes.DlssGMode.ON, 8),
	X10(StreamlineTypes.DlssGMode.ON, 9),
	X11(StreamlineTypes.DlssGMode.ON, 10),
	X12(StreamlineTypes.DlssGMode.ON, 11),
	X13(StreamlineTypes.DlssGMode.ON, 12),
	X14(StreamlineTypes.DlssGMode.ON, 13),
	X15(StreamlineTypes.DlssGMode.ON, 14),
	X16(StreamlineTypes.DlssGMode.ON, 15),
	X17(StreamlineTypes.DlssGMode.ON, 16),
	X18(StreamlineTypes.DlssGMode.ON, 17),
	X19(StreamlineTypes.DlssGMode.ON, 18),
	X20(StreamlineTypes.DlssGMode.ON, 19),
	X21(StreamlineTypes.DlssGMode.ON, 20),
	X22(StreamlineTypes.DlssGMode.ON, 21),
	X23(StreamlineTypes.DlssGMode.ON, 22),
	X24(StreamlineTypes.DlssGMode.ON, 23),
	X25(StreamlineTypes.DlssGMode.ON, 24),
	X26(StreamlineTypes.DlssGMode.ON, 25),
	X27(StreamlineTypes.DlssGMode.ON, 26),
	X28(StreamlineTypes.DlssGMode.ON, 27),
	X29(StreamlineTypes.DlssGMode.ON, 28),
	X30(StreamlineTypes.DlssGMode.ON, 29),
	X31(StreamlineTypes.DlssGMode.ON, 30),
	X32(StreamlineTypes.DlssGMode.ON, 31);

	/** Prefix of a multiplier entry's translation key; the multiplier is appended. */
	private static final String MULTIPLIER_KEY_PREFIX =
			"superresolution.screen.config.options.frame_generation.x";

	private final int nativeMode;
	private final int generatedFrameCount;
	/** Null for multiplier entries, whose key is derived on demand. */
	private final String translationKey;

	FrameGenerationMode(int nativeMode, int generatedFrameCount, String translationKey) {
		this.nativeMode = nativeMode;
		this.generatedFrameCount = generatedFrameCount;
		this.translationKey = translationKey;
	}

	FrameGenerationMode(int nativeMode, int generatedFrameCount) {
		this(nativeMode, generatedFrameCount, null);
	}

	public int nativeMode() {
		return nativeMode;
	}

	public int generatedFrameCount() {
		return generatedFrameCount;
	}

	public boolean isEnabled() {
		return generatedFrameCount > 0;
	}

	public String translationKey() {
		return translationKey != null
				? translationKey
				: MULTIPLIER_KEY_PREFIX + (generatedFrameCount + 1);
	}
}
