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

package io.homo.superresolution.core.graphics.impl.shader;

import io.homo.superresolution.core.graphics.impl.shader.uniform.ShaderResourceAccess;
import io.homo.superresolution.core.graphics.impl.shader.uniform.ShaderResourceDescription;
import io.homo.superresolution.core.graphics.impl.shader.uniform.ShaderResourceType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class ShaderResourcesLayout {
    private final Map<String, ShaderResourceDescription> resources = new HashMap<>();

    public ShaderResourcesLayout() {
    }

    public static Builder builder() {
        return new Builder();
    }

    public ShaderResourcesLayout addUniformBuffer(String name, int binding, int bufferSize) {
        return addUniformBuffer(name, 0, binding, bufferSize);
    }

    public ShaderResourcesLayout addUniformBuffer(String name, int set, int binding, int bufferSize) {
        resources.put(name, ShaderResourceDescription.builder(name, ShaderResourceType.UniformBuffer)
                .set(set)
                .binding(binding)
                .bufferSize(bufferSize)
                .build());
        return this;
    }

    public ShaderResourcesLayout addSamplerTexture(String name, int binding) {
        return addSamplerTexture(name, 0, binding);
    }

    public ShaderResourcesLayout addSamplerTexture(String name, int set, int binding) {
        resources.put(name, ShaderResourceDescription.builder(name, ShaderResourceType.SamplerTexture)
                .set(set)
                .binding(binding)
                .build());
        return this;
    }

    public ShaderResourcesLayout addStorageTexture(String name, int binding, ShaderResourceAccess access) {
        return addStorageTextureInSet(name, 0, binding, access);
    }

    public ShaderResourcesLayout addStorageTexture(String name, int binding) {
        return addStorageTexture(name, binding, ShaderResourceAccess.Both);
    }

    /// 带 set 索引的变体（Vulkan 多 set 用）。命名为 addStorageTextureInSet 以避免
    /// 与 addStorageTexture(String,int,ShaderResourceAccess) 产生重载歧义。
    public ShaderResourcesLayout addStorageTextureInSet(String name, int set, int binding, ShaderResourceAccess access) {
        resources.put(name, ShaderResourceDescription.builder(name, ShaderResourceType.StorageTexture)
                .set(set)
                .binding(binding)
                .access(access)
                .build());
        return this;
    }

    public ShaderResourcesLayout addStorageTextureInSet(String name, int set, int binding) {
        return addStorageTextureInSet(name, set, binding, ShaderResourceAccess.Both);
    }

    /// SSBO。bufferSize 仅作记录（Vulkan 绑定用 VK_WHOLE_SIZE，GL 用 glBindBufferBase）。
    public ShaderResourcesLayout addStorageBuffer(String name, int binding) {
        return addStorageBuffer(name, 0, binding);
    }

    public ShaderResourcesLayout addStorageBuffer(String name, int set, int binding) {
        return addStorageBuffer(name, set, binding, ShaderResourceAccess.Both);
    }

    public ShaderResourcesLayout addStorageBuffer(String name, int binding, ShaderResourceAccess access) {
        return addStorageBuffer(name, 0, binding, access);
    }

    public ShaderResourcesLayout addStorageBuffer(String name, int set, int binding, ShaderResourceAccess access) {
        resources.put(name, ShaderResourceDescription.builder(name, ShaderResourceType.StorageBuffer)
                .set(set)
                .binding(binding)
                .access(access)
                .build());
        return this;
    }

    public ShaderResourcesLayout addResource(ShaderResourceDescription resource) {
        resources.put(resource.name(), resource);
        return this;
    }

    public Map<String, ShaderResourceDescription> getResources() {
        return resources;
    }

    /// 按 Vulkan 描述符集合索引分组，供多 set 管线使用。
    /// 返回值按 set 升序（LinkedHashMap 保持插入序 + 排序），只包含出现过的 set。
    public Map<Integer, List<ShaderResourceDescription>> getResourcesBySet() {
        Map<Integer, List<ShaderResourceDescription>> grouped = new TreeMap<>();
        for (ShaderResourceDescription res : resources.values()) {
            grouped.computeIfAbsent(res.set(), k -> new ArrayList<>()).add(res);
        }
        return grouped;
    }

    /// 本布局用到的最大 set 索引（无资源时返回 -1）。
    public int maxSet() {
        int max = -1;
        for (ShaderResourceDescription res : resources.values()) {
            max = Math.max(max, res.set());
        }
        return max;
    }

    public ShaderResourceDescription getResource(String name) {
        return resources.get(name);
    }

    public boolean hasResource(String name) {
        return resources.containsKey(name);
    }

    public static class Builder {
        private final ShaderResourcesLayout layout = new ShaderResourcesLayout();

        public Builder uniformBuffer(String name, int binding, int bufferSize) {
            layout.addUniformBuffer(name, binding, bufferSize);
            return this;
        }

        public Builder storageBuffer(String name, int binding) {
            layout.addStorageBuffer(name, binding);
            return this;
        }

        public Builder samplerTexture(String name, int binding) {
            layout.addSamplerTexture(name, binding);
            return this;
        }

        public Builder storageTexture(String name, int binding, ShaderResourceAccess access) {
            layout.addStorageTexture(name, binding, access);
            return this;
        }

        public Builder storageTexture(String name, int binding) {
            layout.addStorageTexture(name, binding);
            return this;
        }

        public Builder resource(ShaderResourceDescription resource) {
            layout.addResource(resource);
            return this;
        }

        public ShaderResourcesLayout build() {
            return layout;
        }
    }
}
