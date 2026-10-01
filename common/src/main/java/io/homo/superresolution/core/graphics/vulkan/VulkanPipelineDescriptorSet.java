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

package io.homo.superresolution.core.graphics.vulkan;

import io.homo.superresolution.core.graphics.impl.buffer.IBuffer;
import io.homo.superresolution.core.graphics.impl.pipeline.PipelineDescriptorSet;
import io.homo.superresolution.core.graphics.impl.shader.IShaderProgram;
import io.homo.superresolution.core.graphics.impl.shader.ShaderResourcesLayout;
import io.homo.superresolution.core.graphics.impl.shader.uniform.ShaderResourceDescription;
import io.homo.superresolution.core.graphics.impl.shader.uniform.ShaderResourceType;
import io.homo.superresolution.core.graphics.impl.texture.ITexture;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static io.homo.superresolution.core.graphics.vulkan.VulkanUtils.VK_CHECK;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRPushDescriptor.VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR;
import static org.lwjgl.vulkan.VK10.*;

public class VulkanPipelineDescriptorSet extends PipelineDescriptorSet {
    private static final Logger LOGGER = LoggerFactory.getLogger(VulkanPipelineDescriptorSet.class);
    private final VulkanDevice device;
    /// 每个 Vulkan 描述符集合一个 layout。NSS 等移植 shader 用 set=0（采样器）+ set=1（SSBO）。
    /// 索引即 set 号，未用到的 set 位置为 VK_NULL_HANDLE（管线布局里填 null 占位）。
    private final Map<Integer, Long> descriptorSetLayouts = new TreeMap<>();
    private final Map<Integer, Long> samplerCache = new HashMap<>();
    private final DescriptorLayoutKey descriptorLayoutKey;

    public VulkanPipelineDescriptorSet(VulkanDevice device, IShaderProgram shader) {
        super(shader);
        this.device = device;
        this.descriptorLayoutKey = createDescriptorLayoutKey(shader.getDescription().resourcesLayout());
        createDescriptorSetLayouts();
    }

    private static int toVkDescriptorType(ShaderResourceType type) {
        return switch (type) {
            case UniformBuffer -> VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
            case StorageBuffer -> VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            case SamplerTexture -> VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            case StorageTexture -> VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        };
    }

    /// 为每个出现过的 set 单独建 layout。set 之间不能合并 ——
    /// vkCmdPushDescriptorSetKHR 的 set 参数必须以 layout 为单位传，
    /// 且管线布局要求 set 号连续（中间空洞用空 layout 占位）。
    private void createDescriptorSetLayouts() {
        ShaderResourcesLayout layout = shader.getDescription().resourcesLayout();
        Map<Integer, List<ShaderResourceDescription>> grouped = layout.getResourcesBySet();

        int maxSet = layout.maxSet();
        /*
         * 硬限制：本后端只通过 vkCmdPushDescriptorSetKHR 写描述符，从不调用
         * vkCmdBindDescriptorSets。而 VUID-VkPipelineLayoutCreateInfo-pSetLayouts-00293
         * 规定一个管线布局里最多只能有一个 set layout 带 PUSH_DESCRIPTOR 标志，
         * 于是 set 数 > 1 时除 set 0 之外的 set 永远无法被真正绑定 ——
         * 表现为 vkCmdDraw/vkCmdDispatch 报 None-08600「set n is not bound」，
         * GPU 读到的是未初始化的描述符（画面全黑/无超分）而不是崩溃。
         * 因此任何用到 set >= 1 的 shader 都必须合并到 set 0，这里显式报错。
         */
        if (maxSet > 0) {
            LOGGER.error(
                    "Shader '{}' 使用了 {} 个 descriptor set（maxSet={}），但本后端只支持单一 push descriptor set。"
                            + " 请把所有资源合并到 set 0（用不同的 binding 号），否则 pipeline 布局会违反 "
                            + "VUID-VkPipelineLayoutCreateInfo-pSetLayouts-00293 且 set 0 无法绑定。",
                    shader.getDescription().shaderName(), grouped.size(), maxSet);
        }
        for (int set = 0; set <= maxSet; set++) {
            // 空洞 set（布局里没资源但序号被占）也要建空 layout，否则管线布局 set 号会错位
            List<ShaderResourceDescription> resources = grouped.getOrDefault(set, List.of());
            descriptorSetLayouts.put(set, createDescriptorSetLayout(set, resources));
        }
    }

    private long createDescriptorSetLayout(int set, List<ShaderResourceDescription> resources) {
        try (MemoryStack stack = stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer layoutBindings =
                    VkDescriptorSetLayoutBinding.calloc(resources.size(), stack);

            for (int i = 0; i < resources.size(); i++) {
                ShaderResourceDescription res = resources.get(i);
                layoutBindings.get(i)
                        .binding(res.binding())
                        .descriptorType(toVkDescriptorType(res.type()))
                        .descriptorCount(1)
                        .stageFlags(VK_SHADER_STAGE_ALL)
                        .pImmutableSamplers(null);
            }

            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .flags(VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR)
                    .pBindings(layoutBindings);

            LongBuffer pLayout = stack.mallocLong(1);
            VK_CHECK(vkCreateDescriptorSetLayout(device.getVkDevice(), layoutInfo, null, pLayout),
                    "Failed to create descriptor set layout (set=" + set + ")");
            long handle = pLayout.get(0);
            device.setDebugName(VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, handle,
                    "DescriptorSetLayout:" + shader.getDescription().shaderName() + ":set" + set);
            return handle;
        }
    }

    /// 把多个 set 的 layout 组成供 vkCreatePipelineLayout 使用的数组，按 set 号升序。
    public long[] getDescriptorSetLayouts() {
        return descriptorSetLayouts.values().stream().mapToLong(Long::longValue).toArray();
    }

    /// 该 shader 用到的 set 数量。
    public int getDescriptorSetCount() {
        return descriptorSetLayouts.size();
    }

    /// 单个 set 的布局指纹。按 set 隔离，避免不同 set 之间互相误判"布局未变"。
    private final Map<Integer, DescriptorLayoutKey> perSetLayoutKeys = new TreeMap<>();

    private DescriptorLayoutKey layoutKeyForSet(int set) {
        return perSetLayoutKeys.computeIfAbsent(set, s -> {
            List<DescriptorLayoutBindingKey> keys = new ArrayList<>();
            for (DescriptorLayoutBindingKey key : descriptorLayoutKey.bindings()) {
                if (key.setIndex() == s) {
                    keys.add(key);
                }
            }
            return new DescriptorLayoutKey(List.copyOf(keys));
        });
    }

    void pushDescriptorsIfNeeded(VulkanCommandBuffer commandBuffer, VkCommandBuffer cmd, int bindPoint, long pipelineLayout) {
        if (bindings.isEmpty()) {
            dirty = false;
            return;
        }

        // 按 set 分别推送：vkCmdPushDescriptorSetKHR 一次只处理一个 set
        for (Map.Entry<Integer, List<DescriptorBindingSnapshotKey>> setEntry : collectSnapshotKeysBySet().entrySet()) {
            int set = setEntry.getKey();
            List<DescriptorBindingSnapshotKey> requestedBindings = setEntry.getValue();
            DescriptorLayoutKey setLayoutKey = layoutKeyForSet(set);
            List<DescriptorBindingSnapshotKey> bindingsToPush = commandBuffer.collectDescriptorUpdates(
                    bindPoint,
                    set,
                    setLayoutKey,
                    requestedBindings
            );
            if (bindingsToPush.isEmpty()) {
                continue;
            }
            pushDescriptorSet(cmd, bindPoint, pipelineLayout, set, bindingsToPush);
            commandBuffer.recordDescriptorPush(bindPoint, set, setLayoutKey, bindingsToPush);
        }
        dirty = false;
    }

    private void pushDescriptorSet(VkCommandBuffer cmd, int bindPoint, long pipelineLayout, int set,
                                   List<DescriptorBindingSnapshotKey> bindingsToPush) {
        try (MemoryStack stack = stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(bindingsToPush.size(), stack);

            for (int i = 0; i < bindingsToPush.size(); i++) {
                DescriptorBindingSnapshotKey binding = bindingsToPush.get(i);
                VkWriteDescriptorSet write = writes.get(i);
                write.sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                        .dstBinding(binding.binding())
                        .dstArrayElement(0)
                        .descriptorCount(1);

                switch (binding.descriptorType()) {
                    case VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER -> {
                        VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack)
                                .buffer(binding.buffer())
                                .offset(binding.bufferOffset())
                                .range(binding.bufferRange());
                        write.descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                                .pBufferInfo(bufferInfo);
                    }
                    // SSBO：与 UBO 同为 buffer descriptor，仅类型不同
                    case VK_DESCRIPTOR_TYPE_STORAGE_BUFFER -> {
                        VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack)
                                .buffer(binding.buffer())
                                .offset(binding.bufferOffset())
                                .range(binding.bufferRange());
                        write.descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                                .pBufferInfo(bufferInfo);
                    }
                    case VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER -> {
                        VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                                .imageLayout(binding.imageLayout())
                                .imageView(binding.imageView())
                                .sampler(binding.sampler());
                        write.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                                .pImageInfo(imageInfo);
                    }
                    case VK_DESCRIPTOR_TYPE_STORAGE_IMAGE -> {
                        VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                                .imageLayout(binding.imageLayout())
                                .imageView(binding.imageView())
                                .sampler(VK_NULL_HANDLE);
                        write.descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                                .pImageInfo(imageInfo);
                    }
                    default -> throw new IllegalStateException("Unsupported descriptor type: " + binding.descriptorType());
                }
            }

            KHRPushDescriptor.vkCmdPushDescriptorSetKHR(cmd, bindPoint, pipelineLayout, set, writes);
        }
    }

    boolean needsPush() {
        return dirty;
    }

    @Override
    public void apply() {
    }

    @Override
    protected void updateImpl() {
    }

    private long resolveImageView(ITexture textureOrView) {
        if (textureOrView instanceof VulkanTextureView vtv) {
            return vtv.handle();
        }
        if (textureOrView instanceof VulkanTexture vt) {
            return vt.getImageView();
        }
        if (textureOrView instanceof VulkanExternalTexture vet) {
            return vet.getImageView();
        }
        throw new IllegalArgumentException("Cannot resolve image view from: " + textureOrView.getClass());
    }

    private long resolveStorageImageView(ITexture textureOrView) {
        if (textureOrView instanceof VulkanTextureView vtv) {
            return vtv.handle();
        }
        if (textureOrView instanceof VulkanTexture vt) {
            return vt.getImageView();
        }
        if (textureOrView instanceof VulkanExternalTexture vet) {
            return vet.getImageView();
        }
        throw new IllegalArgumentException("Cannot resolve storage image view from: " + textureOrView.getClass());
    }

    private long getOrCreateSamplerForTexture(ITexture texture) {
        int filterMode = texture.getTextureFilterMode().vk();
        int wrapMode = texture.getTextureWrapMode().vk();
        int key = (filterMode << 16) | wrapMode;
        Long sampler = samplerCache.get(key);
        if (sampler != null) {
            return sampler;
        }
        try (MemoryStack stack = stackPush()) {
            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                    .magFilter(filterMode)
                    .minFilter(filterMode)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(wrapMode)
                    .addressModeV(wrapMode)
                    .addressModeW(wrapMode)
                    .minLod(0.0f)
                    .maxLod(0.0f);

            LongBuffer pSampler = stack.mallocLong(1);
            VK_CHECK(vkCreateSampler(device.getVkDevice(), samplerInfo, null, pSampler),
                    "Failed to create sampler for texture");
            long handle = pSampler.get(0);
            samplerCache.put(key, handle);
            device.setDebugName(VK_OBJECT_TYPE_SAMPLER, handle, descriptorSamplerDebugLabel(texture, filterMode, wrapMode));
            return handle;
        }
    }

    private String descriptorSamplerDebugLabel(ITexture texture, int filterMode, int wrapMode) {
        String textureLabel = texture.getTextureDescription().getLabel();
        if (textureLabel != null && !textureLabel.isBlank()) {
            return "DescriptorSampler:" + textureLabel;
        }
        return "DescriptorSampler filter=" + filterMode + " wrap=" + wrapMode;
    }

    public List<Long> getDescriptorSetLayoutList() {
        return new ArrayList<>(descriptorSetLayouts.values());
    }

    public DescriptorLayoutKey getDescriptorLayoutKey() {
        return descriptorLayoutKey;
    }

    @Override
    public IShaderProgram getShader() {
        return shader;
    }

    public void destroy() {
        for (long sampler : samplerCache.values()) {
            if (sampler != VK_NULL_HANDLE) {
                device.queueForDestroy(() -> vkDestroySampler(device.getVkDevice(), sampler, null));
            }
        }
        samplerCache.clear();
        for (long layout : descriptorSetLayouts.values()) {
            if (layout != VK_NULL_HANDLE) {
                device.queueForDestroy(() -> vkDestroyDescriptorSetLayout(device.getVkDevice(), layout, null));
            }
        }
        descriptorSetLayouts.clear();
    }

    private static DescriptorLayoutKey createDescriptorLayoutKey(ShaderResourcesLayout layout) {
        List<DescriptorLayoutBindingKey> bindingKeys = new ArrayList<>();
        for (ShaderResourceDescription res : layout.getResources().values()) {
            bindingKeys.add(new DescriptorLayoutBindingKey(
                    res.set(),
                    res.binding(),
                    toVkDescriptorType(res.type()),
                    1,
                    VK_SHADER_STAGE_ALL,
                    0L
            ));
        }
        bindingKeys.sort(Comparator.comparingInt(DescriptorLayoutBindingKey::setIndex)
                .thenComparingInt(DescriptorLayoutBindingKey::binding));
        return new DescriptorLayoutKey(List.copyOf(bindingKeys));
    }

    /// 按 set 分组生成快照 key。set 号取自资源布局（binding 时已解析进 ResourceBinding）。
    private Map<Integer, List<DescriptorBindingSnapshotKey>> collectSnapshotKeysBySet() {
        Map<Integer, List<DescriptorBindingSnapshotKey>> grouped = new TreeMap<>();
        for (Map.Entry<String, ResourceBinding> entry : bindings.entrySet()) {
            ResourceBinding binding = entry.getValue();
            List<DescriptorBindingSnapshotKey> list =
                    grouped.computeIfAbsent(binding.setIndex(), k -> new ArrayList<>());
            list.add(createSnapshotKey(binding));
        }
        for (List<DescriptorBindingSnapshotKey> list : grouped.values()) {
            list.sort(Comparator.comparingInt(DescriptorBindingSnapshotKey::binding));
        }
        return grouped;
    }

    private DescriptorBindingSnapshotKey createSnapshotKey(ResourceBinding binding) {
        return switch (binding.type()) {
            case UNIFORM_BUFFER -> {
                IBuffer buffer = (IBuffer) binding.resource();
                yield new DescriptorBindingSnapshotKey(
                        binding.bindingPoint(),
                        VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,
                        buffer.handle(),
                        binding.offset(),
                        binding.range(),
                        0L,
                        0,
                        0L
                );
            }
            // SSBO：与 UBO 同形，仅 descriptor type 不同
            case STORAGE_BUFFER -> {
                IBuffer buffer = (IBuffer) binding.resource();
                yield new DescriptorBindingSnapshotKey(
                        binding.bindingPoint(),
                        VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                        buffer.handle(),
                        binding.offset(),
                        binding.range(),
                        0L,
                        0,
                        0L
                );
            }
            case SAMPLER_TEXTURE -> {
                ITexture texture = (ITexture) binding.resource();
                long imageView = resolveImageView(texture);
                long sampler = binding.sampler() != null
                        ? binding.sampler().handle()
                        : getOrCreateSamplerForTexture(texture);
                yield new DescriptorBindingSnapshotKey(
                        binding.bindingPoint(),
                        VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                        0L,
                        0L,
                        0L,
                        imageView,
                        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        sampler
                );
            }
            case STORAGE_IMAGE -> {
                ITexture texture = (ITexture) binding.resource();
                long imageView = resolveStorageImageView(texture);
                yield new DescriptorBindingSnapshotKey(
                        binding.bindingPoint(),
                        VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                        0L,
                        0L,
                        0L,
                        imageView,
                        VK_IMAGE_LAYOUT_GENERAL,
                        0L
                );
            }
        };
    }

    public record DescriptorLayoutKey(List<DescriptorLayoutBindingKey> bindings) {
    }

    public record DescriptorLayoutBindingKey(
            int setIndex,
            int binding,
            int descriptorType,
            int descriptorCount,
            int stageFlags,
            long immutableSamplerIdentity
    ) {
    }

    public record DescriptorBindingSnapshotKey(
            int binding,
            int descriptorType,
            long buffer,
            long bufferOffset,
            long bufferRange,
            long imageView,
            int imageLayout,
            long sampler
    ) {
    }
}
