package dev.rt_render_experiment.vulkan;

import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfoKHR;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;


public record VulkanGraphicsPipeline(VkDevice device, long deviceIdentity, long pipeline, long layout, Kind kind) {
   public enum Kind {
      STATIC_SURFACE(VK12.VK_FORMAT_R32G32_UINT, true, true, false, true),
      DYNAMIC_SURFACE(VK12.VK_FORMAT_R32G32_UINT, true, false, false, true),
      TRANSLUCENT(VK12.VK_FORMAT_R16G16B16A16_SFLOAT, true, true, true, true),
      MOTION(VK12.VK_FORMAT_R32G32_SFLOAT, false, false, false, true),
      PUBLISH_DEPTH(VK12.VK_FORMAT_R16G16B16A16_SFLOAT, true, false, false, false),
      PUBLISH_RESOLVE(VK12.VK_FORMAT_R16G16B16A16_SFLOAT, true, false, false, true),
      VIEWMODEL(VK12.VK_FORMAT_R16G16B16A16_SFLOAT, true, false, false, true),
      DISPLAY(VK12.VK_FORMAT_R8G8B8A8_UNORM, false, false, false, true);

      final int colorFormat;
      final boolean depth;
      final boolean vertices;
      final boolean multiply;
      final boolean colorWrite;
      Kind(final int colorFormat, final boolean depth, final boolean vertices, final boolean multiply, final boolean colorWrite) {
         this.colorFormat = colorFormat;
         this.depth = depth;
         this.vertices = vertices;
         this.multiply = multiply;
         this.colorWrite = colorWrite;
      }
   }


   public record Attribute(int location, int format, int offset) {}
   public record VertexLayout(int stride, List<Attribute> attributes) {
      public VertexLayout {
         attributes = List.copyOf(attributes);
         if (stride <= 0 || attributes.size() != 4) throw new IllegalArgumentException("Incomplete raster vertex layout");
         for (int i = 0; i < attributes.size(); i++) {
            Attribute field = attributes.get(i);
            if (field.location() != i || field.format() <= 0 || field.offset() < 0 || field.offset() >= stride) {
               throw new IllegalArgumentException("Invalid raster attribute mapping");
            }
         }
      }
   }

   public VulkanGraphicsPipeline {
      java.util.Objects.requireNonNull(device, "device");
      java.util.Objects.requireNonNull(kind, "kind");
      if (deviceIdentity == 0L || pipeline == 0L || layout == 0L) throw new IllegalArgumentException("Invalid graphics pipeline");
   }

   public static VulkanGraphicsPipeline create(final VkDevice device, final long deviceIdentity, final long cache,
      final long descriptorLayout, final long textureLayout, final Kind kind, final VertexLayout vertices,
      final long vertexModule, final long fragmentModule, final String name) {
      try (MemoryStack stack = MemoryStack.stackPush()) {
         var pipeline = stack.callocLong(1);
         var layout = stack.callocLong(1);
         boolean complete = false;
         try {
            VulkanObjects.requireSuccess(VK12.vkCreatePipelineLayout(device,
               VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(textureLayout == 0L ? stack.longs(descriptorLayout) : stack.longs(descriptorLayout, textureLayout)), null, layout),
               "Create graphics layout " + name);
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType$Default().stage(VK12.VK_SHADER_STAGE_VERTEX_BIT).module(vertexModule).pName(stack.UTF8("main"));
            stages.get(1).sType$Default().stage(VK12.VK_SHADER_STAGE_FRAGMENT_BIT).module(fragmentModule).pName(stack.UTF8("main"));
            VkPipelineVertexInputStateCreateInfo input = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default();
            if (kind.vertices) {
               VkVertexInputAttributeDescription.Buffer attributes = VkVertexInputAttributeDescription.calloc(vertices.attributes().size(), stack);
               for (int i = 0; i < vertices.attributes().size(); i++) {
                  Attribute field = vertices.attributes().get(i);
                  attributes.get(i).location(field.location()).binding(0).format(field.format()).offset(field.offset());
               }
               input.pVertexAttributeDescriptions(attributes).pVertexBindingDescriptions(
                  VkVertexInputBindingDescription.calloc(1, stack).binding(0).stride(vertices.stride()).inputRate(VK12.VK_VERTEX_INPUT_RATE_VERTEX));
            }
            VkPipelineDepthStencilStateCreateInfo depth = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default()
               .depthTestEnable(kind.depth).depthWriteEnable(kind.depth);
            if (kind.depth) depth.depthCompareOp(kind == Kind.PUBLISH_DEPTH || kind == Kind.PUBLISH_RESOLVE || kind == Kind.VIEWMODEL
               ? VK12.VK_COMPARE_OP_ALWAYS : VK12.VK_COMPARE_OP_GREATER_OR_EQUAL);
            VkPipelineColorBlendAttachmentState.Buffer blend = VkPipelineColorBlendAttachmentState.calloc(1, stack)
               .colorWriteMask(kind.colorWrite ? VK12.VK_COLOR_COMPONENT_R_BIT | VK12.VK_COLOR_COMPONENT_G_BIT
                  | VK12.VK_COLOR_COMPONENT_B_BIT | VK12.VK_COLOR_COMPONENT_A_BIT : 0);
            if (kind.multiply) {
               blend.blendEnable(true).srcColorBlendFactor(VK12.VK_BLEND_FACTOR_DST_COLOR).dstColorBlendFactor(VK12.VK_BLEND_FACTOR_ZERO)
                  .colorBlendOp(VK12.VK_BLEND_OP_ADD).srcAlphaBlendFactor(VK12.VK_BLEND_FACTOR_DST_COLOR)
                  .dstAlphaBlendFactor(VK12.VK_BLEND_FACTOR_ZERO).alphaBlendOp(VK12.VK_BLEND_OP_ADD);
            }
            if(kind==Kind.VIEWMODEL) {
               blend.blendEnable(true).srcColorBlendFactor(VK12.VK_BLEND_FACTOR_ONE).dstColorBlendFactor(VK12.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                  .colorBlendOp(VK12.VK_BLEND_OP_ADD).srcAlphaBlendFactor(VK12.VK_BLEND_FACTOR_ONE)
                  .dstAlphaBlendFactor(VK12.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA).alphaBlendOp(VK12.VK_BLEND_OP_ADD);
            }
            VkPipelineRenderingCreateInfoKHR rendering = VkPipelineRenderingCreateInfoKHR.calloc(stack).sType$Default()
               .pColorAttachmentFormats(stack.ints(kind.colorFormat)).depthAttachmentFormat(kind.depth ? VK12.VK_FORMAT_D32_SFLOAT : VK12.VK_FORMAT_UNDEFINED);
            VkGraphicsPipelineCreateInfo.Buffer create = VkGraphicsPipelineCreateInfo.calloc(1, stack).sType$Default()
               .pStages(stages).pVertexInputState(input)
               .pInputAssemblyState(VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default().topology(VK12.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST))
               .pRasterizationState(VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
                  .polygonMode(VK12.VK_POLYGON_MODE_FILL).cullMode(VK12.VK_CULL_MODE_BACK_BIT).frontFace(VK12.VK_FRONT_FACE_CLOCKWISE).lineWidth(1.0F))
               .pDepthStencilState(depth).pColorBlendState(VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default().pAttachments(blend))
               .pViewportState(VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default().viewportCount(1).scissorCount(1))
               .pMultisampleState(VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default().rasterizationSamples(VK12.VK_SAMPLE_COUNT_1_BIT))
               .pDynamicState(VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
                  .pDynamicStates(stack.ints(VK12.VK_DYNAMIC_STATE_SCISSOR, VK12.VK_DYNAMIC_STATE_VIEWPORT)))
               .layout(layout.get(0)).pNext(rendering);
            VulkanObjects.requireSuccess(VK12.vkCreateGraphicsPipelines(device, cache, create, null, pipeline), "Create graphics pipeline " + name);
            VulkanObjects.name(device, VK12.VK_OBJECT_TYPE_PIPELINE, pipeline.get(0), name);
            VulkanObjects.name(device, VK12.VK_OBJECT_TYPE_PIPELINE_LAYOUT, layout.get(0), name);
            VulkanGraphicsPipeline result = new VulkanGraphicsPipeline(device, deviceIdentity, pipeline.get(0), layout.get(0), kind);
            complete = true;
            return result;
         } finally {
            if (!complete) {
               if (pipeline.get(0) != 0L) VK12.vkDestroyPipeline(device, pipeline.get(0), null);
               if (layout.get(0) != 0L) VK12.vkDestroyPipelineLayout(device, layout.get(0), null);
            }
         }
      }
   }

   public void destroy() {
      VK12.vkDestroyPipeline(this.device, this.pipeline, null);
      VK12.vkDestroyPipelineLayout(this.device, this.layout, null);
   }
}
