package dev.rt_render_experiment.vulkan;

import dev.rt_render_experiment.contract.ResourceViews;
import dev.rt_render_experiment.contract.ResourceViews.Buffer;
import dev.rt_render_experiment.contract.ResourceViews.Image;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRDynamicRendering;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfoKHR;
import org.lwjgl.vulkan.VkRenderingInfoKHR;
import org.lwjgl.vulkan.VkViewport;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import static dev.rt_render_experiment.vulkan.VulkanDescriptors.*;


public final class VulkanGraphicsPasses {
   private VulkanGraphicsPasses() {}

   public static Pass begin(final VkCommandBuffer command, final VulkanGraphicsPipeline pipeline,
      final Image color, final Image depth, final boolean clearColor, final boolean clearDepth,
      final int width, final int height) {
      return new Pass(command, pipeline, color, depth, clearColor, clearDepth, width, height);
   }

   public static final class Pass implements AutoCloseable {
      private final VkCommandBuffer command;
      private final long device;
      private final boolean depth;
      private final int colorFormat;
      private VulkanGraphicsPipeline pipeline;
      private long vertexBuffer;
      private long vertexOffset = -1L;
      private long indexBuffer;
      private long indexOffset = -1L;
      private int indexType = -1;
      private boolean closed;

      private Pass(final VkCommandBuffer command, final VulkanGraphicsPipeline pipeline,
         final Image color, final Image depth, final boolean clearColor, final boolean clearDepth,
         final int width, final int height) {
         this.command = command;
         this.device = color.device();
         this.depth = depth != null;
         this.colorFormat = color.format();
         if (width <= 0 || height <= 0 || width > color.width() || height > color.height()
            || depth != null && (depth.device() != this.device || width > depth.width() || height > depth.height()
               || depth.format() != VK12.VK_FORMAT_D32_SFLOAT)) {
            throw new IllegalArgumentException("Mismatched raster attachments or extent");
         }
         requirePipeline(pipeline);
         try (MemoryStack stack = MemoryStack.stackPush()) {
            VkRenderingAttachmentInfoKHR.Buffer colors = VkRenderingAttachmentInfoKHR.calloc(1, stack).sType$Default()
               .imageView(color.view()).imageLayout(color.layout()).loadOp(clearColor ? VK12.VK_ATTACHMENT_LOAD_OP_CLEAR : VK12.VK_ATTACHMENT_LOAD_OP_LOAD)
               .storeOp(VK12.VK_ATTACHMENT_STORE_OP_STORE);
            VkRenderingInfoKHR info = VkRenderingInfoKHR.calloc(stack).sType$Default().layerCount(1).pColorAttachments(colors);
            info.renderArea().offset().set(0, 0);
            info.renderArea().extent().set(width, height);
            if (depth != null) {
               VkRenderingAttachmentInfoKHR attachment = VkRenderingAttachmentInfoKHR.calloc(stack).sType$Default()
                  .imageView(depth.view()).imageLayout(depth.layout())
                  .loadOp(clearDepth ? VK12.VK_ATTACHMENT_LOAD_OP_CLEAR : VK12.VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK12.VK_ATTACHMENT_STORE_OP_STORE);
               attachment.clearValue().depthStencil().set(0.0F, 0);
               info.pDepthAttachment(attachment);
            }
            KHRDynamicRendering.vkCmdBeginRenderingKHR(command, info);
            VkViewport.Buffer viewport = VkViewport.calloc(1, stack).x(0.0F).y(0.0F).width(width).height(height).minDepth(0.0F).maxDepth(1.0F);
            VK12.vkCmdSetViewport(command, 0, viewport);
            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.offset().set(0, 0); scissor.extent().set(width, height);
            VK12.vkCmdSetScissor(command, 0, scissor);
            pipeline(pipeline);
         }
      }

      private void requirePipeline(final VulkanGraphicsPipeline candidate) {
         if (candidate.device() != this.command.getDevice() || candidate.deviceIdentity() != this.device
            || candidate.kind().depth != this.depth || candidate.kind().colorFormat != this.colorFormat) {
            throw new IllegalArgumentException("Graphics pipeline does not match the recording device/attachments");
         }
      }

      public void pipeline(final VulkanGraphicsPipeline candidate) {
         requireOpen();
         requirePipeline(candidate);
         if (this.pipeline != candidate) {
            this.pipeline = candidate;
            VK12.vkCmdBindPipeline(this.command, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, candidate.pipeline());
         }
      }

      private void requireOpen() { if (this.closed) throw new IllegalStateException("Raster pass already ended"); }
      private void buffer(final Buffer view) {
         requireOpen();
         if (view.device() != this.device) throw new IllegalArgumentException("Foreign raster buffer");
      }
      private void image(final Image view) {
         requireOpen();
         if (view.device() != this.device) throw new IllegalArgumentException("Foreign raster image");
      }
      private void push(final VkWriteDescriptorSet.Buffer writes) {
         KHRPushDescriptor.vkCmdPushDescriptorSetKHR(this.command, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, this.pipeline.layout(), 0, writes);
      }



      public void r2Frame(final Buffer frame, final Buffer color, final Buffer hostDepth, final Buffer guides) {
         buffer(frame); buffer(color); buffer(hostDepth); buffer(guides);
         try (MemoryStack stack = MemoryStack.stackPush()) {
            var writes = VkWriteDescriptorSet.calloc(4, stack);
            writeUniform(writes.get(0), stack, 0, frame);
            writeStorageBuffer(writes.get(1), stack, 1, color);
            writeStorageBuffer(writes.get(2), stack, 2, hostDepth);
            writeStorageBuffer(writes.get(3), stack, 3, guides);
            push(writes); VK12.vkCmdDraw(this.command, 3, 1, 0, 0);
         }
      }


      public void terrainInputs(final Buffer frame, final Buffer view, final Image atlas, final long sampler, final boolean translucent) {
         buffer(frame); buffer(view); image(atlas);
         try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(3, stack);
            writeUniform(writes.get(0), stack, 0, frame);
            writeUniform(writes.get(1), stack, 1, view);
            writeCombined(writes.get(2), stack, translucent ? 2 : 3, atlas, sampler);
            push(writes);
         }
      }

      public void terrainDraw(final Buffer vertices, final Buffer indices, final int type, final int firstIndex,
         final int indexCount, final int baseVertex, final Buffer section, final Buffer semantic) {
         buffer(vertices); buffer(indices); buffer(section);
         if (semantic != null) buffer(semantic);
         int indexBytes = type == VK12.VK_INDEX_TYPE_UINT16 ? 2 : type == VK12.VK_INDEX_TYPE_UINT32 ? 4 : 0;
         if (indexBytes == 0 || firstIndex < 0 || indexCount < 0
            || ((long)firstIndex + indexCount) * indexBytes > indices.length()) throw new IllegalArgumentException("Raster index range exceeds its buffer");
         try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(semantic == null ? 1 : 2, stack);
            writeUniform(writes.get(0), stack, semantic == null ? 2 : 3, section);
            if (semantic != null) writeStorageBuffer(writes.get(1), stack, 4, semantic);
            push(writes);
            if (this.vertexBuffer != vertices.handle() || this.vertexOffset != vertices.offset()) {
               this.vertexBuffer = vertices.handle(); this.vertexOffset = vertices.offset();
               VK12.vkCmdBindVertexBuffers(this.command, 0, stack.longs(this.vertexBuffer), stack.longs(this.vertexOffset));
            }
            if (this.indexBuffer != indices.handle() || this.indexOffset != indices.offset() || this.indexType != type) {
               this.indexBuffer = indices.handle(); this.indexOffset = indices.offset(); this.indexType = type;
               VK12.vkCmdBindIndexBuffer(this.command, this.indexBuffer, this.indexOffset, type);
            }
            VK12.vkCmdDrawIndexed(this.command, indexCount, 1, firstIndex, baseVertex, 0);
         }
      }

      public void dynamicInputs(final Buffer frame, final VulkanTextureTable.Snapshot textures) {
         buffer(frame);
         if (textures.deviceIdentity() != this.device) throw new IllegalArgumentException("Foreign texture descriptor table");
         try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(1, stack);
            writeUniform(writes.get(0), stack, 0, frame);
            push(writes);
            textures.bind(this.command, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, this.pipeline.layout());
         }
      }

      public void motionInputs(final Buffer frame, final Image depth, final long sampler) {
         buffer(frame); image(depth);
         try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
            writeUniform(writes.get(0), stack, 0, frame);
            writeCombined(writes.get(1), stack, 2, depth, sampler);
            push(writes);
         }
      }

      public void dynamicDraw(final Buffer draw, final int vertices) {
         buffer(draw);
         if (vertices < 0 || vertices % 3 != 0) throw new IllegalArgumentException("Invalid dynamic triangle count");
         try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(1, stack);
            writeUniform(writes.get(0), stack, 1, draw);
            push(writes);
            VK12.vkCmdDraw(this.command, vertices, 1, 0, 0);
         }
      }

      public void publish(final Image radiance, final long radianceSampler, final Image depth, final long depthSampler) {
         image(radiance); image(depth);
         try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
            writeCombined(writes.get(0), stack, 0, radiance, radianceSampler);
            writeCombined(writes.get(1), stack, 1, depth, depthSampler);
            push(writes);
            VK12.vkCmdDraw(this.command, 3, 1, 0, 0);
         }
      }

      public void display(final Image radiance, final Image exposure, final Image bloom,
         final long linearSampler, final long nearestSampler, final Buffer frame) {
         image(radiance); image(exposure); image(bloom); buffer(frame);
         try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(4, stack);
            writeCombined(writes.get(0), stack, 0, radiance, linearSampler);
            writeCombined(writes.get(1), stack, 1, exposure, nearestSampler);
            writeCombined(writes.get(2), stack, 2, bloom, linearSampler);
            writeUniform(writes.get(3), stack, 3, frame);
            push(writes);
            VK12.vkCmdDraw(this.command, 3, 1, 0, 0);
         }
      }

      @Override
      public void close() {
         if (this.closed) return;
         this.closed = true;
         KHRDynamicRendering.vkCmdEndRenderingKHR(this.command);
         try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanBarriers.record(this.command, stack, VulkanBarriers.GRAPHICS_BOUNDARY);
         }
      }
   }
}
