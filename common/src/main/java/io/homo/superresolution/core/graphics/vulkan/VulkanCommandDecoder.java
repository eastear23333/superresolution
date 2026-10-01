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

import io.homo.superresolution.core.graphics.impl.buffer.BufferDescription;
import io.homo.superresolution.core.graphics.impl.buffer.BufferUsage;
import io.homo.superresolution.core.graphics.impl.buffer.BufferUsages;
import io.homo.superresolution.core.graphics.impl.buffer.IBuffer;
import io.homo.superresolution.core.graphics.impl.command.*;
import io.homo.superresolution.core.graphics.impl.device.IDevice;
import io.homo.superresolution.core.graphics.impl.pipeline.ComputePipeline;
import io.homo.superresolution.core.graphics.impl.pipeline.GraphicsPipeline;
import io.homo.superresolution.core.graphics.impl.pipeline.PipelineDescriptorSet;
import io.homo.superresolution.core.graphics.impl.pipeline.RenderPass;
import io.homo.superresolution.core.graphics.impl.shader.uniform.ShaderResourceDescription;
import io.homo.superresolution.core.graphics.impl.shader.uniform.ShaderResourceAccess;
import io.homo.superresolution.core.graphics.impl.texture.ITexture;
import io.homo.superresolution.core.graphics.impl.texture.ITextureView;
import io.homo.superresolution.core.graphics.impl.vertex.IVertexBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.lwjgl.vulkan.KHRDynamicRendering.*;
import static org.lwjgl.vulkan.VK10.*;

public class VulkanCommandDecoder implements ICommandDecoder {
    private VulkanDevice vulkanDevice;

    public VulkanCommandDecoder(VulkanDevice vulkanDevice) {
        this.vulkanDevice = vulkanDevice;
    }

    private void beginLabel(VkCommandBuffer commandBuffer, String label) {
        vulkanDevice.beginDebugLabel(commandBuffer, label);
    }

    private void endLabel(VkCommandBuffer commandBuffer) {
        vulkanDevice.endDebugLabel(commandBuffer);
    }

    private void insertLabel(VkCommandBuffer commandBuffer, String label) {
        vulkanDevice.insertDebugLabel(commandBuffer, label);
    }

    private void withLabel(VkCommandBuffer commandBuffer, String label, Runnable action) {
        beginLabel(commandBuffer, label);
        try {
            action.run();
        } finally {
            endLabel(commandBuffer);
        }
    }

    private static int vkLayoutFor(ResourceAccessType access) {
        return switch (access) {
            case UNDEFINED -> VK_IMAGE_LAYOUT_UNDEFINED;
            case SAMPLED_READ -> VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            case STORAGE_READ, STORAGE_WRITE, STORAGE_READ_WRITE -> VK_IMAGE_LAYOUT_GENERAL;
            case COLOR_ATTACHMENT_WRITE -> VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
            case DEPTH_ATTACHMENT_WRITE -> VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
            case TRANSFER_SRC -> VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
            case TRANSFER_DST -> VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        };
    }

    private static int vkStageFor(ResourceAccessType access) {
        return switch (access) {
            case UNDEFINED -> VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
            case SAMPLED_READ, STORAGE_READ, STORAGE_WRITE, STORAGE_READ_WRITE ->
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
            case COLOR_ATTACHMENT_WRITE -> VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
            case DEPTH_ATTACHMENT_WRITE ->
                    VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
            case TRANSFER_SRC, TRANSFER_DST -> VK_PIPELINE_STAGE_TRANSFER_BIT;
        };
    }

    private static int vkStageFor(ResourceAccessType access, int bindPoint) {
        return switch (access) {
            case SAMPLED_READ, STORAGE_READ, STORAGE_WRITE, STORAGE_READ_WRITE -> {
                if (bindPoint == VK_PIPELINE_BIND_POINT_COMPUTE) {
                    yield VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
                }
                yield VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
            }
            default -> vkStageFor(access);
        };
    }

    private static int vkAccessFor(ResourceAccessType access) {
        return switch (access) {
            case UNDEFINED -> 0;
            case SAMPLED_READ, STORAGE_READ -> VK_ACCESS_SHADER_READ_BIT;
            case STORAGE_WRITE -> VK_ACCESS_SHADER_WRITE_BIT;
            case STORAGE_READ_WRITE -> VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
            case COLOR_ATTACHMENT_WRITE -> VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
            case DEPTH_ATTACHMENT_WRITE -> VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
            case TRANSFER_SRC -> VK_ACCESS_TRANSFER_READ_BIT;
            case TRANSFER_DST -> VK_ACCESS_TRANSFER_WRITE_BIT;
        };
    }

    private static int vkStageFor(BufferUsage usage) {
        return switch (usage) {
            case StaticDraw, DynamicDraw -> VK_PIPELINE_STAGE_VERTEX_INPUT_BIT;
            case Ubo ->
                    VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
            case Storage -> VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
            case TransferSrc, TransferDst -> VK_PIPELINE_STAGE_TRANSFER_BIT;
        };
    }

    private static int vkStageFor(BufferUsages usages) {
        int flags = 0;
        for (BufferUsage usage : usages.getUsages()) {
            flags |= vkStageFor(usage);
        }
        return flags;
    }

    private static int vkAccessFor(BufferUsage usage) {
        return switch (usage) {
            case StaticDraw, DynamicDraw -> VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT | VK_ACCESS_INDEX_READ_BIT;
            case Ubo -> VK_ACCESS_UNIFORM_READ_BIT;
            case Storage -> VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
            case TransferSrc -> VK_ACCESS_TRANSFER_READ_BIT;
            case TransferDst -> VK_ACCESS_TRANSFER_WRITE_BIT;
        };
    }

    private static int vkAccessFor(BufferUsages usages) {
        int flags = 0;
        for (BufferUsage usage : usages.getUsages()) {
            flags |= vkAccessFor(usage);
        }
        return flags;
    }

    private static int vkPostTransferStageFor(BufferUsages usages) {
        int flags = 0;
        for (BufferUsage usage : usages.getUsages()) {
            if (usage != BufferUsage.TransferDst && usage != BufferUsage.TransferSrc) {
                flags |= vkStageFor(usage);
            }
        }
        return flags;
    }

    private static int vkPostTransferAccessFor(BufferUsages usages) {
        int flags = 0;
        for (BufferUsage usage : usages.getUsages()) {
            if (usage != BufferUsage.TransferDst && usage != BufferUsage.TransferSrc) {
                flags |= vkAccessFor(usage);
            }
        }
        return flags;
    }

    private static VulkanResourceState vkStateFor(ResourceAccessType access) {
        return new VulkanResourceState(
                vkLayoutFor(access),
                vkAccessFor(access),
                vkStageFor(access),
                access
        );
    }

    private static VulkanResourceState vkStateFor(ResourceAccessType access, int bindPoint) {
        return new VulkanResourceState(
                vkLayoutFor(access),
                vkAccessFor(access),
                vkStageFor(access, bindPoint),
                access
        );
    }

    @Override
    public ResourceStateTracker getStateTracker() {
        return new ResourceStateTracker();
    }

    @Override
    public void declareExternalResource(ITexture texture, ResourceAccessType currentState) {
        if (!(texture instanceof VulkanExternalTexture ext)) {
            throw new IllegalArgumentException(
                    "declareExternalResource: 仅允许外部导入纹理 (VulkanExternalTexture)");
        }
        ext.setCurrentResourceState(vkStateFor(currentState));
    }

    @Override
    public void restoreExternalResource(ICommandBuffer commandBuffer, ITexture texture, ResourceAccessType targetState) {
        if (!(texture instanceof VulkanExternalTexture ext)) {
            throw new IllegalArgumentException(
                    "restoreExternalResource: 仅允许外部导入纹理 (VulkanExternalTexture)");
        }
        VulkanCommandBuffer vcb = (VulkanCommandBuffer) commandBuffer;
        VkCommandBuffer cmd = vcb.getNativeCommandBuffer();
        transitionTexture(cmd, ext, vkStateFor(targetState), "Restore External Resource Barrier");
    }

    @Override
    public void clearTextureRGBA(ICommandBuffer commandBuffer, ITexture texture, float[] color) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            /*
             * ★★ 不要假设 color 一定有 4 个分量！★★
             *
             * ValidatedCommandDecoder.clearTextureRGBA 的校验是
             *     color.length == format.getChannelCount()
             * 而 getChannelCount() 对非 RGBA 格式会返回 < 4：
             *     R8           → 1
             *     R11G11B10F   → 3   （只声明 R/G/B，无 A）
             *     RGBA8_SNORM  → 4
             * 原实现硬访问 color[0..3]，于是 R8 会 `Index 1 out of bounds for length 1`、
             * R11G11B10F 会在 color[3] 越界。两者都是**通过校验后才在解码器里炸**，
             * 属于两层契约不一致。
             *
             * 修复：缺失的分量按 0 补齐（对 clear 语义而言 0 就是期望值）。
             * 这样上层只需按格式给足分量数，不必关心 VkClearColorValue 固定 4 槽。
             */
            VkClearColorValue clearColor = VkClearColorValue.calloc(stack);
            clearColor.float32(0, color.length > 0 ? color[0] : 0.0f);
            clearColor.float32(1, color.length > 1 ? color[1] : 0.0f);
            clearColor.float32(2, color.length > 2 ? color[2] : 0.0f);
            clearColor.float32(3, color.length > 3 ? color[3] : 0.0f);
            VulkanTexture vulkanTexture = (VulkanTexture) texture;
            long imageHandle = vulkanTexture.handle();
            VulkanCommandBuffer vulkanCommandBuffer = (VulkanCommandBuffer) commandBuffer;
            VkCommandBuffer commandBufferHandle = vulkanCommandBuffer.getNativeCommandBuffer();
            transitionTexture(commandBufferHandle, texture, ResourceAccessType.TRANSFER_DST);
            VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack);
            range.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            range.baseMipLevel(0);
            range.levelCount(1);
            range.baseArrayLayer(0);
            range.layerCount(1);
            withLabel(commandBufferHandle, "Clear Texture", () -> vkCmdClearColorImage(
                    commandBufferHandle,
                    imageHandle,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    clearColor,
                    range
            ));
            markTextureState(texture, ResourceAccessType.TRANSFER_DST);
        }
    }

    @Override
    public void clearTextureDepth(ICommandBuffer commandBuffer, ITexture texture, float depth) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkClearDepthStencilValue clearDepth = VkClearDepthStencilValue.calloc(stack);
            clearDepth.depth(depth);
            clearDepth.stencil(0);
            VulkanTexture vulkanTexture = (VulkanTexture) texture;
            long imageHandle = vulkanTexture.handle();
            VulkanCommandBuffer vulkanCommandBuffer = (VulkanCommandBuffer) commandBuffer;
            VkCommandBuffer commandBufferHandle = vulkanCommandBuffer.getNativeCommandBuffer();
            transitionTexture(commandBufferHandle, texture, ResourceAccessType.TRANSFER_DST);
            VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack);
            range.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT);
            range.baseMipLevel(0);
            range.levelCount(1);
            range.baseArrayLayer(0);
            range.layerCount(1);

            withLabel(commandBufferHandle, "Clear Texture Depth", () -> vkCmdClearDepthStencilImage(
                    commandBufferHandle,
                    imageHandle,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    clearDepth,
                    range
            ));
            markTextureState(texture, ResourceAccessType.TRANSFER_DST);
        }
    }

    @Override
    public void clearTextureStencil(ICommandBuffer commandBuffer, ITexture texture, int stencil) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkClearDepthStencilValue clearStencil = VkClearDepthStencilValue.calloc(stack);
            clearStencil.depth(1.0f);
            clearStencil.stencil(stencil);
            VulkanTexture vulkanTexture = (VulkanTexture) texture;
            long imageHandle = vulkanTexture.handle();
            VulkanCommandBuffer vulkanCommandBuffer = (VulkanCommandBuffer) commandBuffer;
            VkCommandBuffer commandBufferHandle = vulkanCommandBuffer.getNativeCommandBuffer();
            transitionTexture(commandBufferHandle, texture, ResourceAccessType.TRANSFER_DST);
            VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack);
            range.aspectMask(VK_IMAGE_ASPECT_STENCIL_BIT);
            range.baseMipLevel(0);
            range.levelCount(1);
            range.baseArrayLayer(0);
            range.layerCount(1);

            withLabel(commandBufferHandle, "Clear Texture Stencil", () -> vkCmdClearDepthStencilImage(
                    commandBufferHandle,
                    imageHandle,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    clearStencil,
                    range
            ));
            markTextureState(texture, ResourceAccessType.TRANSFER_DST);
        }
    }

    @Override
    public void copyTexture(ICommandBuffer commandBuffer, ITexture src, ITexture dst, int srcX0, int srcY0, int srcX1, int srcY1, int srcLevel, int dstX0, int dstY0, int dstX1, int dstY1, int dstLevel) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanTexture srcTexture = (VulkanTexture) src;
            VulkanTexture dstTexture = (VulkanTexture) dst;
            VulkanCommandBuffer vulkanCommandBuffer = (VulkanCommandBuffer) commandBuffer;
            VkCommandBuffer commandBufferHandle = vulkanCommandBuffer.getNativeCommandBuffer();
            transitionTexture(commandBufferHandle, src, ResourceAccessType.TRANSFER_SRC);
            transitionTexture(commandBufferHandle, dst, ResourceAccessType.TRANSFER_DST);

            VkImageCopy.Buffer copyRegion = VkImageCopy.calloc(1, stack);
            copyRegion.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            copyRegion.srcSubresource().mipLevel(srcLevel);
            copyRegion.srcSubresource().baseArrayLayer(0);
            copyRegion.srcSubresource().layerCount(1);
            copyRegion.srcOffset().set(srcX0, srcY0, 0);
            copyRegion.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            copyRegion.dstSubresource().mipLevel(dstLevel);
            copyRegion.dstSubresource().baseArrayLayer(0);
            copyRegion.dstSubresource().layerCount(1);
            copyRegion.dstOffset().set(dstX0, dstY0, 0);

            withLabel(commandBufferHandle, "Copy Texture", () -> vkCmdCopyImage(
                    commandBufferHandle,
                    srcTexture.handle(),
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    dstTexture.handle(),
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    copyRegion
            ));
            markTextureState(src, ResourceAccessType.TRANSFER_SRC);
            markTextureState(dst, ResourceAccessType.TRANSFER_DST);
        }
    }

    @Override
    public void copyBuffer(ICommandBuffer commandBuffer, IBuffer src, IBuffer dst, long srcOffset, long dstOffset, long size) {
        VulkanCommandBuffer vcb = (VulkanCommandBuffer) commandBuffer;
        VkCommandBuffer cmd = vcb.getNativeCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCopy.Buffer copyRegion = VkBufferCopy.calloc(1, stack)
                    .srcOffset(srcOffset)
                    .dstOffset(dstOffset)
                    .size(size);
            withLabel(cmd, "Copy Buffer", () -> vkCmdCopyBuffer(cmd, src.handle(), dst.handle(), copyRegion));
        }
    }

    @Override
    public void writeToBuffer(ICommandBuffer commandBuffer, IBuffer dst, long dstOffset, long size, ByteBuffer data) {
        if (!(commandBuffer instanceof VulkanCommandBuffer vcb)) {
            throw new IllegalArgumentException("writeToBuffer: invalid commandBuffer type: " + commandBuffer.getClass().getName());
        }
        if (!(dst instanceof VulkanBuffer vkBuffer)) {
            throw new IllegalArgumentException("writeToBuffer: invalid buffer type: " + dst.getClass().getName());
        }
        if (data == null) {
            throw new IllegalArgumentException("writeToBuffer: data must not be null");
        }
        if (dstOffset < 0) {
            throw new IllegalArgumentException("writeToBuffer: dstOffset must not be negative");
        }

        ByteBuffer src = data.duplicate();
        if (size <= 0) {
            return;
        }
        if (dstOffset + size > dst.getSize()) {
            throw new IllegalArgumentException("writeToBuffer: write range exceeds buffer size");
        }

        if (vkBuffer.getUsages().has(BufferUsage.TransferSrc)) {
            vkBuffer.writeHostVisible(src, Math.toIntExact(dstOffset));
            return;
        }

        VulkanBuffer stagingBuffer = new VulkanBuffer(
                vulkanDevice,
                BufferDescription.create()
                        .size(size)
                        .usage(BufferUsage.TransferSrc)
                        .build()
        );
        stagingBuffer.writeHostVisible(src, 0);
        copyBuffer(commandBuffer, stagingBuffer, vkBuffer, 0, dstOffset, size);
        insertTransferWriteBarrier(vcb.getNativeCommandBuffer(), vkBuffer, dstOffset, size, vkPostTransferStageFor(vkBuffer.getUsages()), vkPostTransferAccessFor(vkBuffer.getUsages()));
        vcb.addTransientResource(stagingBuffer);
    }

    @Override
    public void writeToBuffer(ICommandBuffer commandBuffer, IVertexBuffer dst, long dstOffset, ByteBuffer data) {
        if (!(commandBuffer instanceof VulkanCommandBuffer vcb)) {
            throw new IllegalArgumentException("writeToBuffer(IVertexBuffer): invalid commandBuffer type: " + commandBuffer.getClass().getName());
        }
        if (!(dst instanceof VulkanVertexBuffer vkVertexBuffer)) {
            throw new IllegalArgumentException("writeToBuffer(IVertexBuffer): invalid vertexBuffer type: " + dst.getClass().getName());
        }
        if (data == null) {
            throw new IllegalArgumentException("writeToBuffer(IVertexBuffer): data must not be null");
        }
        if (dstOffset < 0) {
            throw new IllegalArgumentException("writeToBuffer(IVertexBuffer): dstOffset must not be negative");
        }

        ByteBuffer src = data.duplicate();
        int size = src.remaining();
        if (size <= 0) {
            return;
        }
        if (dstOffset + size > dst.getSizeInBytes()) {
            throw new IllegalArgumentException("writeToBuffer(IVertexBuffer): write range exceeds buffer size");
        }

        VulkanBuffer stagingBuffer = new VulkanBuffer(
                vulkanDevice,
                BufferDescription.create()
                        .size(size)
                        .usage(BufferUsage.TransferSrc)
                        .build()
        );
        stagingBuffer.writeHostVisible(src, 0);

        VkCommandBuffer cmd = vcb.getNativeCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCopy.Buffer copyRegion = VkBufferCopy.calloc(1, stack)
                    .srcOffset(0)
                    .dstOffset(dstOffset)
                    .size(size);
            withLabel(cmd, "Write To Vertex Buffer", () -> vkCmdCopyBuffer(cmd, stagingBuffer.handle(), vkVertexBuffer.handle(), copyRegion));
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .buffer(vkVertexBuffer.handle())
                    .offset(dstOffset)
                    .size(size);
            withLabel(cmd, "Vertex Buffer Upload Barrier", () -> vkCmdPipelineBarrier(
                    cmd,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_VERTEX_INPUT_BIT,
                    0,
                    null,
                    barrier,
                    null
            ));
        }

        vcb.addTransientResource(stagingBuffer);
    }

    @Override
    public void writeToTexture(ICommandBuffer commandBuffer, ITexture texture, ByteBuffer data, int x, int y, int width, int height, int mipLevel) {
        if (!(commandBuffer instanceof VulkanCommandBuffer vcb)) {
            throw new IllegalArgumentException("writeToTexture: invalid commandBuffer type: " + commandBuffer.getClass().getName());
        }
        if (!(texture instanceof VulkanTexture vkTexture)) {
            throw new IllegalArgumentException("writeToTexture: invalid texture type: " + texture.getClass().getName());
        }
        if (data == null) {
            throw new IllegalArgumentException("writeToTexture: data must not be null");
        }
        if (x < 0 || y < 0 || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("writeToTexture: invalid texture region arguments");
        }

        VkCommandBuffer cmd = vcb.getNativeCommandBuffer();

        transitionTexture(cmd, texture, ResourceAccessType.TRANSFER_DST);

        VulkanBuffer stagingBuffer = new VulkanBuffer(
                vulkanDevice,
                BufferDescription.create()
                        .size(data.remaining())
                        .usage(BufferUsage.TransferSrc)
                        .build()
        );
        stagingBuffer.writeHostVisible(data, 0);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferImageCopy.Buffer copyRegion = VkBufferImageCopy.calloc(1, stack)
                    .bufferOffset(0)
                    .bufferRowLength(0)
                    .bufferImageHeight(0)
                    .imageSubresource(VkImageSubresourceLayers.calloc(stack)
                            .aspectMask(vkTexture.getAspectMask())
                            .mipLevel(mipLevel)
                            .baseArrayLayer(0)
                            .layerCount(1))
                    .imageOffset(VkOffset3D.calloc(stack).set(x, y, 0))
                    .imageExtent(VkExtent3D.calloc(stack).set(width, height, 1));

            withLabel(cmd, "Write To Texture", () -> vkCmdCopyBufferToImage(
                    cmd,
                    stagingBuffer.handle(),
                    vkTexture.handle(),
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    copyRegion
            ));
        }

        vcb.addTransientResource(stagingBuffer);

        transitionTexture(cmd, texture, ResourceAccessType.SAMPLED_READ);
    }

    @Override
    public void setViewport(ICommandBuffer commandBuffer, float x, float y, float width, float height) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkViewport.Buffer viewport = VkViewport.calloc(1, stack);
            viewport.x(x);
            viewport.y(y);
            viewport.width(width);
            viewport.height(height);
            viewport.minDepth(0.0f);
            viewport.maxDepth(1.0f);

            VulkanCommandBuffer vulkanCommandBuffer = (VulkanCommandBuffer) commandBuffer;
            VkCommandBuffer commandBufferHandle = vulkanCommandBuffer.getNativeCommandBuffer();

            insertLabel(commandBufferHandle, "Set Viewport");
            vkCmdSetViewport(
                    commandBufferHandle,
                    0,
                    viewport
            );
        }
    }

    @Override
    public void setScissor(ICommandBuffer commandBuffer, int x, int y, int width, int height) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.offset().set(x, y);
            scissor.extent().set(width, height);

            VulkanCommandBuffer vulkanCommandBuffer = (VulkanCommandBuffer) commandBuffer;
            VkCommandBuffer commandBufferHandle = vulkanCommandBuffer.getNativeCommandBuffer();

            insertLabel(commandBufferHandle, "Set Scissor");
            vkCmdSetScissor(
                    commandBufferHandle,
                    0,
                    scissor
            );
        }

    }

    @Override
    public void setLineWidth(ICommandBuffer commandBuffer, float width) {
        VulkanCommandBuffer vulkanCommandBuffer = (VulkanCommandBuffer) commandBuffer;
        VkCommandBuffer commandBufferHandle = vulkanCommandBuffer.getNativeCommandBuffer();

        insertLabel(commandBufferHandle, "Set Line Width");
        vkCmdSetLineWidth(
                commandBufferHandle,
                width
        );
    }

    @Override
    public void setBlendConstants(ICommandBuffer commandBuffer, float r, float g, float b, float a) {
        VulkanCommandBuffer vulkanCommandBuffer = (VulkanCommandBuffer) commandBuffer;
        VkCommandBuffer commandBufferHandle = vulkanCommandBuffer.getNativeCommandBuffer();

        float[] blendConstants = new float[]{r, g, b, a};
        insertLabel(commandBufferHandle, "Set Blend Constants");
        vkCmdSetBlendConstants(
                commandBufferHandle,
                blendConstants
        );
    }

    @Override
    public void beginRenderPass(ICommandBuffer commandBuffer, RenderPass renderPass) {
        if (!(commandBuffer instanceof VulkanCommandBuffer vcb)) {
            throw new IllegalArgumentException("beginRenderPass: invalid commandBuffer type: " + commandBuffer.getClass().getName());
        }
        if (renderPass == null) {
            throw new IllegalArgumentException("beginRenderPass: renderPass must not be null");
        }
        if (!(renderPass instanceof VulkanRenderPass vkRenderPass)) {
            throw new IllegalArgumentException("beginRenderPass: invalid renderPass type: " + renderPass.getClass().getName());
        }

        VkCommandBuffer cmd = vcb.getNativeCommandBuffer();
        VulkanFramebuffer vkFramebuffer = (VulkanFramebuffer) vkRenderPass.frameBuffer();

        ITexture colorAttachment = vkFramebuffer.getColorAttachmentTexture();
        if (colorAttachment != null) {
            transitionTexture(cmd, colorAttachment, ResourceAccessType.COLOR_ATTACHMENT_WRITE);
        }
        // MRT：额外颜色附件同样需要转入 COLOR_ATTACHMENT_WRITE
        for (ITexture extra : vkFramebuffer.getExtraColorAttachmentTextures()) {
            transitionTexture(cmd, extra, ResourceAccessType.COLOR_ATTACHMENT_WRITE);
        }

        ITexture depthAttachment = vkFramebuffer.getDepthAttachmentTexture();
        if (depthAttachment != null) {
            transitionTexture(cmd, depthAttachment, ResourceAccessType.DEPTH_ATTACHMENT_WRITE);
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkRenderingInfoKHR renderingInfo = VkRenderingInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDERING_INFO_KHR)
                    .layerCount(1);
            renderingInfo.renderArea().offset().set(0, 0);
            renderingInfo.renderArea().extent().set(vkFramebuffer.getWidth(), vkFramebuffer.getHeight());

            List<ITexture> allColorTextures = new ArrayList<>();
            if (colorAttachment != null) {
                allColorTextures.add(colorAttachment);
            }
            allColorTextures.addAll(vkFramebuffer.getExtraColorAttachmentTextures());

            if (!allColorTextures.isEmpty()) {
                VkRenderingAttachmentInfoKHR.Buffer colorAttachmentInfo =
                        VkRenderingAttachmentInfoKHR.calloc(allColorTextures.size(), stack);

                for (int i = 0; i < allColorTextures.size(); i++) {
                    ITexture tex = allColorTextures.get(i);
                    long imageView;
                    if (i == 0) {
                        imageView = vkFramebuffer.resolveColorImageView();
                    } else {
                        // MRT 附件视图：索引 i-1 对应额外附件列表
                        imageView = vkFramebuffer.resolveExtraColorImageViews().get(i - 1);
                    }
                    int loadOp = renderPass.clearState().shouldClearColorOnBegin(i)
                            ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD;

                    colorAttachmentInfo.get(i)
                            .sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR)
                            .imageView(imageView)
                            .imageLayout(tex instanceof VulkanExternalTexture
                                    ? ((VulkanExternalTexture) tex).getCurrentLayout()
                                    : VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                            .loadOp(loadOp)
                            .storeOp(VK_ATTACHMENT_STORE_OP_STORE);

                    if (loadOp == VK_ATTACHMENT_LOAD_OP_CLEAR) {
                        float[] cc = renderPass.clearState().getColorClearValueOnBegin(i);
                        colorAttachmentInfo.get(i).clearValue().color()
                                .float32(0, cc[0]).float32(1, cc[1]).float32(2, cc[2]).float32(3, cc[3]);
                    }
                }

                renderingInfo.pColorAttachments(colorAttachmentInfo);
            }

            VkRenderingAttachmentInfoKHR depthAttachmentInfo = null;
            VkRenderingAttachmentInfoKHR stencilAttachmentInfo = null;

            if (depthAttachment != null) {
                long depthImageView = vkFramebuffer.resolveDepthImageView();
                int depthLoadOp = renderPass.clearState().shouldClearDepthOnBegin()
                        ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD;
                int stencilLoadOp = renderPass.clearState().shouldClearStencilOnBegin()
                        ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD;

                depthAttachmentInfo = VkRenderingAttachmentInfoKHR.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR)
                        .imageView(depthImageView)
                        .imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                        .loadOp(depthLoadOp)
                        .storeOp(VK_ATTACHMENT_STORE_OP_STORE);

                VkClearDepthStencilValue depthClear = depthAttachmentInfo.clearValue().depthStencil();
                if (renderPass.clearState().shouldClearDepthOnBegin()) {
                    depthClear.depth(renderPass.clearState().getDepthClearValueOnBegin());
                } else {
                    depthClear.depth(1.0f);
                }

                boolean hasStencil = vkFramebuffer.getDepthTextureFormat().isStencil();
                if (hasStencil) {
                    stencilAttachmentInfo = VkRenderingAttachmentInfoKHR.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR)
                            .imageView(depthImageView)
                            .imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                            .loadOp(stencilLoadOp)
                            .storeOp(VK_ATTACHMENT_STORE_OP_STORE);

                    VkClearDepthStencilValue stencilClear = stencilAttachmentInfo.clearValue().depthStencil();
                    if (renderPass.clearState().shouldClearStencilOnBegin()) {
                        stencilClear.stencil(renderPass.clearState().getStencilClearValueOnBegin());
                    } else {
                        stencilClear.stencil(0);
                    }
                }

                renderingInfo.pDepthAttachment(depthAttachmentInfo);
                renderingInfo.pStencilAttachment(stencilAttachmentInfo);
            }

            beginLabel(cmd, "Render Pass:" + vkFramebuffer.getLabel());
            withLabel(cmd, "Begin Render Pass", () -> vkCmdBeginRenderingKHR(cmd, renderingInfo));
        }

        vcb._beginRenderPass(vkRenderPass);
    }

    @Override
    public void endRenderPass(ICommandBuffer commandBuffer) {
        if (!(commandBuffer instanceof VulkanCommandBuffer vcb)) {
            throw new IllegalArgumentException("endRenderPass: invalid commandBuffer type: " + commandBuffer.getClass().getName());
        }
        if (!vcb.isRenderPassActive()) {
            throw new IllegalStateException("endRenderPass: no render pass is active");
        }

        VkCommandBuffer cmd = vcb.getNativeCommandBuffer();
        withLabel(cmd, "End Render Pass", () -> vkCmdEndRenderingKHR(cmd));
        endLabel(cmd);

        vcb._endRenderPass();
    }

    /**
     * 在 <b>render pass 之前</b>把图形管线要采样/读写的纹理转到目标布局。
     *
     * <p>{@link #bindPipeline(ICommandBuffer, GraphicsPipeline)} 里也会做同样的事，
     * 但那时已经在 render pass 内部，而
     * {@code vkCmdPipelineBarrier} 在 render pass 实例里<b>不允许做布局转换</b>
     * （{@code VUID-vkCmdPipelineBarrier-oldLayout-01181}），且 stage mask 被限制在
     * framebuffer-space 阶段、必须带 {@code VK_DEPENDENCY_BY_REGION_BIT}
     * （{@code 09556} / {@code 07891}）。驱动对这类非法 barrier 的处理是未定义的 ——
     * 实测会让时序型算法（NSS/XeSS/FSR 这类读写历史纹理的）读到尚未可见的上一帧结果，
     * 表现为画面抖动/闪烁。
     *
     * <p>{@link #transitionTexture} 是幂等的：目标状态一致时不会发 barrier。
     * 所以先在这里转好，{@code bindPipeline} 里的那次就变成空操作，不再产生非法 barrier。
     */
    public void prepareGraphicsPipelineResources(ICommandBuffer commandBuffer, GraphicsPipeline pipeline) {
        if (!(commandBuffer instanceof VulkanCommandBuffer vcb)) {
            throw new IllegalArgumentException("prepareGraphicsPipelineResources: invalid commandBuffer type: "
                    + commandBuffer.getClass().getName());
        }
        if (vcb.isRenderPassActive()) {
            throw new IllegalStateException(
                    "prepareGraphicsPipelineResources: must be called outside a render pass; call it before beginRenderPass");
        }
        if (pipeline == null) {
            throw new IllegalArgumentException("prepareGraphicsPipelineResources: pipeline must not be null");
        }
        if (!(pipeline instanceof VulkanGraphicsPipeline vkGraphicsPipeline)) {
            throw new IllegalArgumentException("prepareGraphicsPipelineResources: invalid pipeline type: "
                    + pipeline.getClass().getName());
        }

        VkCommandBuffer cmd = vcb.getNativeCommandBuffer();
        vkGraphicsPipeline.ensurePipelineCreated();
        VulkanPipelineDescriptorSet vkDescriptorSet = (VulkanPipelineDescriptorSet) pipeline.descriptorSet();
        prepareDescriptorResources(cmd, vkDescriptorSet, VK_PIPELINE_BIND_POINT_GRAPHICS);
    }

    @Override
    public void bindPipeline(ICommandBuffer commandBuffer, GraphicsPipeline pipeline) {
        if (!(commandBuffer instanceof VulkanCommandBuffer vcb)) {
            throw new IllegalArgumentException("bindPipeline(graphics): invalid commandBuffer type: " + commandBuffer.getClass().getName());
        }
        if (!vcb.isRenderPassActive()) {
            throw new IllegalStateException("bindPipeline(graphics): no render pass is active; call beginRenderPass first");
        }
        if (pipeline == null) {
            throw new IllegalArgumentException("bindPipeline(graphics): pipeline must not be null");
        }
        if (!(pipeline instanceof VulkanGraphicsPipeline vkGraphicsPipeline)) {
            throw new IllegalArgumentException("bindPipeline(graphics): invalid pipeline type: " + pipeline.getClass().getName());
        }

        VkCommandBuffer cmd = vcb.getNativeCommandBuffer();
        vkGraphicsPipeline.ensurePipelineCreated();

        VulkanPipelineDescriptorSet vkDescriptorSet = (VulkanPipelineDescriptorSet) pipeline.descriptorSet();
        long pipelineHandle = vkGraphicsPipeline.getPipeline();
        withLabel(cmd, "Bind Render Pipeline", () -> {
            if (!vcb.isNativePipelineBound(VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineHandle)) {
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineHandle);
                vcb.recordNativePipelineBind(VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineHandle);
            }
            // 采样纹理的布局转换：与 compute 路径对称。否则上一帧作为颜色附件写过的纹理
            // （或 compute 以 storage image 写过的纹理）会以旧布局被采样，
            // 而描述符里声明的是 SHADER_READ_ONLY_OPTIMAL —— 布局不匹配。
            // 转换只涉及非当前附件的图像，在 render pass 内做是合法的。
            prepareDescriptorResources(cmd, vkDescriptorSet, VK_PIPELINE_BIND_POINT_GRAPHICS);
            vkDescriptorSet.pushDescriptorsIfNeeded(vcb, cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, vkGraphicsPipeline.getPipelineLayout());
            pipeline.applyDynamicStates(commandBuffer);
            vcb.bindGraphicsPipeline(vkGraphicsPipeline);
        });
    }

    @Override
    public void bindPipeline(ICommandBuffer commandBuffer, ComputePipeline pipeline) {
        if (!(commandBuffer instanceof VulkanCommandBuffer vcb)) {
            throw new IllegalArgumentException("bindPipeline(compute): invalid commandBuffer type: " + commandBuffer.getClass().getName());
        }
        if (vcb.isRenderPassActive()) {
            throw new IllegalStateException("bindPipeline(compute): cannot bind a compute pipeline while a render pass is active");
        }
        if (pipeline == null) {
            throw new IllegalArgumentException("bindPipeline(compute): pipeline must not be null");
        }
        if (!(pipeline instanceof VulkanComputePipeline vkComputePipeline)) {
            throw new IllegalArgumentException("bindPipeline(compute): invalid pipeline type: " + pipeline.getClass().getName());
        }

        VkCommandBuffer cmd = vcb.getNativeCommandBuffer();
        VulkanPipelineDescriptorSet vkDescriptorSet = (VulkanPipelineDescriptorSet) pipeline.descriptorSet();
        prepareDescriptorResources(cmd, vkDescriptorSet, VK_PIPELINE_BIND_POINT_COMPUTE);

        long pipelineHandle = vkComputePipeline.getPipeline();
        withLabel(cmd, "Bind Compute Pipeline", () -> {
            if (!vcb.isNativePipelineBound(VK_PIPELINE_BIND_POINT_COMPUTE, pipelineHandle)) {
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineHandle);
                vcb.recordNativePipelineBind(VK_PIPELINE_BIND_POINT_COMPUTE, pipelineHandle);
            }
            vkDescriptorSet.pushDescriptorsIfNeeded(vcb, cmd, VK_PIPELINE_BIND_POINT_COMPUTE, vkComputePipeline.getPipelineLayout());
            vcb.bindComputePipeline(vkComputePipeline);
        });
    }

    @Override
    public void draw(ICommandBuffer commandBuffer, IVertexBuffer vertexBuffer, int vertexCount, int firstVertex) {
        VulkanCommandBuffer vcb = (VulkanCommandBuffer) commandBuffer;
        if (!vcb.isRenderPassActive()) {
            throw new IllegalStateException("draw: no render pass is active; call beginRenderPass first");
        }
        VulkanGraphicsPipeline vkGraphicsPipeline = vcb.getBoundGraphicsPipeline();
        if (vkGraphicsPipeline == null) {
            throw new IllegalStateException("draw: no graphics pipeline is bound; call bindPipeline(graphics) first");
        }

        VkCommandBuffer cmd = vcb.getNativeCommandBuffer();
        GraphicsPipeline graphicsPipeline = vkGraphicsPipeline;

        if (vertexBuffer == null) {
            throw new IllegalArgumentException("draw: vertexBuffer must not be null");
        }
        if (vertexCount <= 0) {
            throw new IllegalArgumentException("draw: vertexCount must be positive");
        }
        if (firstVertex < 0) {
            throw new IllegalArgumentException("draw: firstVertex must not be negative");
        }

        withLabel(cmd, "Draw", () -> {
            if (vertexBuffer != null) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    withLabel(cmd, "Setup Vertex Buffer", () -> vkCmdBindVertexBuffers(cmd, 0, stack.longs(vertexBuffer.handle()), stack.longs(0)));
                }
            }

            vkCmdDraw(cmd, vertexCount, 1, firstVertex, 0);
        });
    }

    @Override
    public void dispatch(ICommandBuffer commandBuffer, int groupCountX, int groupCountY, int groupCountZ) {
        VulkanCommandBuffer vcb = (VulkanCommandBuffer) commandBuffer;
        if (vcb.isRenderPassActive()) {
            throw new IllegalStateException("dispatch: cannot execute a compute dispatch while a render pass is active");
        }
        VulkanComputePipeline vkComputePipeline = vcb.getBoundComputePipeline();
        if (vkComputePipeline == null) {
            throw new IllegalStateException("dispatch: no compute pipeline is bound; call bindPipeline(compute) first");
        }
        VkCommandBuffer cmd = vcb.getNativeCommandBuffer();

        withLabel(cmd, "Compute", () -> vkCmdDispatch(cmd, groupCountX, groupCountY, groupCountZ));
    }

    @Override
    public void memoryBarrier(ICommandBuffer commandBuffer, MemoryBarrierType... barriers) {
        VulkanCommandBuffer vcb = (VulkanCommandBuffer) commandBuffer;
        VkCommandBuffer cmd = vcb.getNativeCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer memBarrier = VkMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
            withLabel(cmd, "Memory Barrier", () -> vkCmdPipelineBarrier(
                    cmd,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0,
                    memBarrier,
                    null,
                    null
            ));
        }
    }

    @Override
    public IDevice getDevice() {
        return vulkanDevice;
    }

    void insertTransferWriteBarrier(VkCommandBuffer commandBuffer, VulkanBuffer buffer, long offset, long size, int dstStageMask, int dstAccessMask) {
        if (dstStageMask == 0 || dstAccessMask == 0) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(dstAccessMask)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .buffer(buffer.handle())
                    .offset(offset)
                    .size(size);
            withLabel(commandBuffer, "Transfer Write Barrier", () -> vkCmdPipelineBarrier(
                    commandBuffer,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    dstStageMask,
                    0,
                    null,
                    barrier,
                    null
            ));
        }
    }

    private void transitionTexture(VkCommandBuffer cmd, ITexture texture, ResourceAccessType target) {
        transitionTexture(cmd, texture, vkStateFor(target), "Texture Layout Transition");
    }

    private void transitionTexture(VkCommandBuffer cmd, ITexture texture, VulkanResourceState target, String label) {
        if (!(texture instanceof VulkanLayoutTracked vlt)) {
            return;
        }

        VulkanResourceState current = vlt.getCurrentResourceState();
        if (!requiresBarrier(current, target)) {
            vlt.setCurrentResourceState(target);
            return;
        }

        long imageHandle = resolveImageHandle(texture);
        int aspectMask = resolveAspectMask(texture);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .srcAccessMask(current.accessMask())
                    .dstAccessMask(target.accessMask())
                    .oldLayout(current.layout())
                    .newLayout(target.layout())
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(imageHandle)
                    .subresourceRange(VkImageSubresourceRange.calloc(stack)
                            .aspectMask(aspectMask)
                            .baseMipLevel(0)
                            .levelCount(VK_REMAINING_MIP_LEVELS)
                            .baseArrayLayer(0)
                            .layerCount(1));
            withLabel(cmd, label, () -> vkCmdPipelineBarrier(
                    cmd,
                    current.stageMask(),
                    target.stageMask(),
                    0, null, null, barrier
            ));
        }
        vlt.setCurrentResourceState(target);
    }

    private boolean requiresBarrier(VulkanResourceState current, VulkanResourceState target) {
        if (current == null) {
            return true;
        }
        if (current.layout() != target.layout()) {
            return true;
        }
        if (current.accessType() == target.accessType() && current.stageMask() == target.stageMask() && current.accessMask() == target.accessMask()) {
            return false;
        }
        return current.accessType().includesWrite() || target.accessType().includesWrite();
    }

    private void markTextureState(ITexture texture, ResourceAccessType target) {
        if (texture instanceof VulkanLayoutTracked vlt) {
            vlt.setCurrentResourceState(vkStateFor(target));
        }
    }

    private void prepareDescriptorResources(VkCommandBuffer cmd, VulkanPipelineDescriptorSet descriptorSet, int bindPoint) {
        for (Map.Entry<String, PipelineDescriptorSet.ResourceBinding> entry : descriptorSet.getBindings().entrySet()) {
            PipelineDescriptorSet.ResourceBinding binding = entry.getValue();
            ITexture texture = resolveTrackingTarget(binding);
            if (texture == null) {
                continue;
            }
            ResourceAccessType accessType = switch (binding.type()) {
                case SAMPLER_TEXTURE -> ResourceAccessType.SAMPLED_READ;
                case STORAGE_IMAGE -> storageAccessFor(descriptorSet, entry.getKey());
                default -> null;
            };
            if (accessType != null) {
                transitionTexture(cmd, texture, vkStateFor(accessType, bindPoint), "Prepare Descriptor Resource");
            }
        }
    }

    private ResourceAccessType storageAccessFor(VulkanPipelineDescriptorSet descriptorSet, String resourceName) {
        ShaderResourceDescription desc = descriptorSet.getShader()
                .getDescription()
                .resourcesLayout()
                .getResource(resourceName);
        ShaderResourceAccess access = desc != null ? desc.access() : ShaderResourceAccess.Both;
        return switch (access) {
            case Read -> ResourceAccessType.STORAGE_READ;
            case Write -> ResourceAccessType.STORAGE_WRITE;
            case Both -> ResourceAccessType.STORAGE_READ_WRITE;
        };
    }

    private ITexture resolveTrackingTarget(PipelineDescriptorSet.ResourceBinding binding) {
        if (binding.resource() instanceof ITextureView view) {
            return view.getParent();
        }
        if (binding.resource() instanceof ITexture texture) {
            return texture;
        }
        return null;
    }

    private long resolveImageHandle(ITexture texture) {
        if (texture instanceof VulkanTexture vt) {
            return vt.handle();
        }
        if (texture instanceof VulkanExternalTexture vet) {
            return vet.handle();
        }
        throw new IllegalArgumentException("Cannot resolve image handle from: " + texture.getClass());
    }

    private int resolveAspectMask(ITexture texture) {
        if (texture.getTextureFormat().isDepthStencil()) {
            return VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT;
        } else if (texture.getTextureFormat().isDepth()) {
            return VK_IMAGE_ASPECT_DEPTH_BIT;
        }
        return VK_IMAGE_ASPECT_COLOR_BIT;
    }
}
