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

package io.homo.superresolution.core.graphics.impl.framebuffer;

import io.homo.superresolution.core.graphics.impl.texture.ITexture;
import io.homo.superresolution.core.graphics.impl.texture.ITextureView;
import io.homo.superresolution.core.graphics.impl.texture.TextureFormat;

public class FramebufferDescription {
    private ITexture colorAttachment;
    private ITexture depthAttachment;
    /// 额外的颜色附件（MRT）。索引 1 起；索引 0 仍是 colorAttachment。
    /// NSS 的 pre_process 需要双输出（_LumaDerivOut + _NearestDepthOffsetOut）。
    private final java.util.List<ITexture> extraColorAttachments = new java.util.ArrayList<>();
    private TextureFormat colorFormat;
    private TextureFormat depthFormat;
    private int width;
    private int height;
    private String label;

    private FramebufferDescription() {
    }

    public static Builder create() {
        return new Builder();
    }

    public ITexture getColorAttachment() {
        return colorAttachment;
    }

    /// 全部颜色附件，索引 0 为主颜色附件。无 MRT 时返回单元素或空列表。
    public java.util.List<ITexture> getColorAttachments() {
        java.util.List<ITexture> all = new java.util.ArrayList<>();
        if (colorAttachment != null) {
            all.add(colorAttachment);
        }
        all.addAll(extraColorAttachments);
        return all;
    }

    public int getColorAttachmentCount() {
        return (colorAttachment != null ? 1 : 0) + extraColorAttachments.size();
    }

    public ITexture getDepthAttachment() {
        return depthAttachment;
    }

    public TextureFormat getColorFormat() {
        return colorFormat;
    }

    public TextureFormat getDepthFormat() {
        return depthFormat;
    }

    public String getLabel() {
        return label;
    }

    public int getWidth() {
        if (width > 0) {
            return width;
        }
        if (colorAttachment != null) {
            return colorAttachment.getWidth();
        }
        if (depthAttachment != null) {
            return depthAttachment.getWidth();
        }
        throw new IllegalStateException("Framebuffer must have at least one attachment or explicit size");
    }

    public int getHeight() {
        if (height > 0) {
            return height;
        }
        if (colorAttachment != null) {
            return colorAttachment.getHeight();
        }
        if (depthAttachment != null) {
            return depthAttachment.getHeight();
        }
        throw new IllegalStateException("Framebuffer must have at least one attachment or explicit size");
    }

    @Override
    public String toString() {
        return "FramebufferDescription{" +
                "colorAttachment=" + (colorAttachment != null ? colorAttachment.string() : "null") +
                ", depthAttachment=" + (depthAttachment != null ? depthAttachment.string() : "null") +
                '}';
    }

    public static class Builder {
        private final FramebufferDescription description;

        public Builder() {
            this.description = new FramebufferDescription();
        }

        public Builder colorAttachment(ITexture colorTexture) {
            description.colorAttachment = colorTexture;
            return this;
        }

        /// 追加一个 MRT 颜色附件（索引 1 起）。必须先设置 colorAttachment。
        public Builder extraColorAttachment(ITexture colorTexture) {
            description.extraColorAttachments.add(colorTexture);
            return this;
        }

        /// 一次性设置全部颜色附件：第 0 个为主附件，其余为 MRT 附件。
        public Builder colorAttachments(java.util.List<ITexture> attachments) {
            description.extraColorAttachments.clear();
            description.colorAttachment = null;
            for (int i = 0; i < attachments.size(); i++) {
                if (i == 0) {
                    description.colorAttachment = attachments.get(i);
                } else {
                    description.extraColorAttachments.add(attachments.get(i));
                }
            }
            return this;
        }

        public Builder depthAttachment(ITexture depthTexture) {
            description.depthAttachment = depthTexture;
            return this;
        }

        public Builder colorAttachment(ITextureView view) {
            return colorAttachment((ITexture) view);
        }

        public Builder depthAttachment(ITextureView view) {
            return depthAttachment((ITexture) view);
        }

        public Builder colorFormat(TextureFormat format) {
            description.colorFormat = format;
            return this;
        }

        public Builder depthFormat(TextureFormat format) {
            description.depthFormat = format;
            return this;
        }

        public Builder size(int width, int height) {
            description.width = width;
            description.height = height;
            return this;
        }

        public Builder label(String label) {
            description.label = label;
            return this;
        }

        public FramebufferDescription build() {
            boolean hasAttachment = description.colorAttachment != null || description.depthAttachment != null;
            boolean hasFormat = description.colorFormat != null || description.depthFormat != null;
            if (!hasAttachment && !hasFormat) {
                throw new IllegalStateException("Framebuffer must have at least one attachment or format");
            }
            if (hasFormat && description.width <= 0) {
                throw new IllegalStateException("Framebuffer with format-based attachments must specify size");
            }
            return description;
        }
    }
}

