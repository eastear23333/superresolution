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

package io.homo.superresolution.core.graphics.impl.shader.uniform;

public class ShaderResourceDescription {
    private final String name;
    private final ShaderResourceType type;
    private int binding = -1;
    /// Vulkan 描述符集合索引。GL 后端忽略此字段（GL 无 set 概念）。
    /// NSS 等移植自 Vulkan 的 shader 把采样器放 set=0、SSBO 放 set=1。
    private int set = 0;
    private int bufferSize = -1;
    private ShaderResourceAccess access = ShaderResourceAccess.Both;

    private ShaderResourceDescription(Builder builder) {
        this.name = builder.name;
        this.type = builder.type;
        this.binding = builder.binding;
        this.set = builder.set;
        this.bufferSize = builder.bufferSize;
        this.access = builder.access;
    }

    public static Builder builder(String name, ShaderResourceType type) {
        return new Builder(name, type);
    }

    public ShaderResourceAccess access() {
        return this.access;
    }

    public String name() {
        return name;
    }

    public ShaderResourceType type() {
        return type;
    }

    public int binding() {
        return binding;
    }

    public int set() {
        return set;
    }

    public int bufferSize() {
        return bufferSize;
    }

    public static class Builder {
        private final String name;
        private final ShaderResourceType type;
        private int binding = -1;
        private int set = 0;
        private int bufferSize = -1;
        private ShaderResourceAccess access = ShaderResourceAccess.Both;

        public Builder(String name, ShaderResourceType type) {
            this.name = name;
            this.type = type;
        }

        public Builder access(ShaderResourceAccess access) {
            this.access = access;
            return this;
        }

        public Builder binding(int binding) {
            this.binding = binding;
            return this;
        }

        /// Vulkan 描述符集合索引（默认 0）。GL 后端会忽略。
        public Builder set(int set) {
            if (set < 0) {
                throw new IllegalArgumentException("Descriptor set index must be >= 0, got " + set);
            }
            this.set = set;
            return this;
        }

        public Builder bufferSize(int size) {
            if (type != ShaderResourceType.UniformBuffer) {
                throw new IllegalArgumentException("Buffer size only applicable to uniform blocks");
            }
            this.bufferSize = size;
            return this;
        }

        public ShaderResourceDescription build() {
            return new ShaderResourceDescription(this);
        }
    }
}