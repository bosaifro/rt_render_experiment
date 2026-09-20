package dev.rt_render_experiment.vulkan;

import java.util.List;
import dev.rt_render_experiment.contract.ResourceViews.Image;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSetAccelerationStructureKHR;
import dev.rt_render_experiment.contract.ResourceViews;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;


public final class VulkanDescriptors {
   private VulkanDescriptors() {}
   private static boolean counting;
   private static long writeRecords;
   private static long descriptorElements;
   public static void frameBegin(final boolean enabled) { counting = enabled; writeRecords = 0L; descriptorElements = 0L; }
   public static long frameWriteRecords() { return writeRecords; }
   public static long frameDescriptorElements() { return descriptorElements; }
   private static void counted(final int elements) {
      if (counting) { writeRecords++; descriptorElements += elements; }
   }
   static void tableWrite(final int elements) { counted(elements); }
   public record Binding(String name, int type, int count, int stages) {
      public Binding {
         int supportedStages = VK12.VK_SHADER_STAGE_VERTEX_BIT | VK12.VK_SHADER_STAGE_FRAGMENT_BIT | VK12.VK_SHADER_STAGE_COMPUTE_BIT
            | org.lwjgl.vulkan.KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR
            | org.lwjgl.vulkan.KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR
            | org.lwjgl.vulkan.KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR
            | org.lwjgl.vulkan.KHRRayTracingPipeline.VK_SHADER_STAGE_MISS_BIT_KHR
            | org.lwjgl.vulkan.KHRRayTracingPipeline.VK_SHADER_STAGE_INTERSECTION_BIT_KHR
            | org.lwjgl.vulkan.KHRRayTracingPipeline.VK_SHADER_STAGE_CALLABLE_BIT_KHR;
         if (name == null || name.isBlank() || count <= 0 || stages == 0 || (stages & ~supportedStages) != 0
            || count > 1 && type != VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER) {
            throw new IllegalArgumentException("Invalid descriptor binding");
         }
      }
   }
   public static long createPushLayout(final VkDevice device, final String name, final List<Binding> entries) {
      try (MemoryStack stack = MemoryStack.stackPush()) {
         VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(entries.size(), stack);
         for (int i = 0; i < entries.size(); i++) {
            Binding entry = entries.get(i);
            bindings.get(i).binding(i).descriptorType(entry.type()).descriptorCount(entry.count()).stageFlags(entry.stages());
         }
         var output = stack.callocLong(1);
         VulkanObjects.requireSuccess(VK12.vkCreateDescriptorSetLayout(device,
            VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default()
               .flags(KHRPushDescriptor.VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR).pBindings(bindings), null, output),
            "Create descriptor layout " + name);
         long handle = output.get(0);
         try {
            VulkanObjects.name(device, VK12.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, handle, name);
            return handle;
         } catch (RuntimeException | Error failure) {
            VK12.vkDestroyDescriptorSetLayout(device, handle, null);
            throw failure;
         }
      }
   }
   public static Binding uniform(final String name, final int stages) { return new Binding(name, VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1, stages); }
   public static Binding sampler(final String name, final int stages) { return new Binding(name, VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, stages); }
   public static Binding samplerArray(final String name, final int count, final int stages) { return new Binding(name, VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, count, stages); }
   public static Binding storageImage(final String name, final int stages) { return new Binding(name, VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1, stages); }
   public static Binding storageBuffer(final String name, final int stages) { return new Binding(name, VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, stages); }
   public static Binding accelerationStructure(final String name, final int stages) { return new Binding(name, KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR, 1, stages); }

   public static void writeUniform(final VkWriteDescriptorSet write, final MemoryStack stack, final int binding, final ResourceViews.Buffer slice) {
      counted(1);
      if ((slice.usage() & VK12.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT) == 0) throw new IllegalArgumentException("Uniform range lacks uniform-buffer usage");
      VkDescriptorBufferInfo.Buffer info = VkDescriptorBufferInfo.calloc(1, stack)
         .buffer(slice.handle())
         .offset(slice.offset())
         .range(slice.length());
      write.sType$Default()
         .dstBinding(binding)
         .dstArrayElement(0)
         .descriptorCount(1)
         .descriptorType(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
         .pBufferInfo(info);
   }

   public static void writeCombined(
      final VkWriteDescriptorSet write, final MemoryStack stack, final int binding, final Image view, final long sampler
   ) {
      counted(1);
      if (sampler == 0L || (view.usage() & VK12.VK_IMAGE_USAGE_SAMPLED_BIT) == 0) throw new IllegalArgumentException("Invalid sampled image binding");
      VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack)
         .sampler(sampler)
         .imageView(view.view())
         .imageLayout(view.layout());
      write.sType$Default()
         .dstBinding(binding)
         .dstArrayElement(0)
         .descriptorCount(1)
         .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
         .pImageInfo(info);
   }

   public static void writeStorageBuffer(final VkWriteDescriptorSet write, final MemoryStack stack, final int binding, final ResourceViews.Buffer buffer) {
      counted(1);
      if ((buffer.usage() & VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT) == 0) throw new IllegalArgumentException("Storage range lacks storage-buffer usage");
      VkDescriptorBufferInfo.Buffer info = VkDescriptorBufferInfo.calloc(1, stack)
         .buffer(buffer.handle())
         .offset(buffer.offset())
         .range(buffer.length());
      write.sType$Default()
         .dstBinding(binding)
         .dstArrayElement(0)
         .descriptorCount(1)
         .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
         .pBufferInfo(info);
   }

   public static void writeStorageImage(final VkWriteDescriptorSet write, final MemoryStack stack, final int binding, final Image view) {
      counted(1);
      if ((view.usage() & VK12.VK_IMAGE_USAGE_STORAGE_BIT) == 0) throw new IllegalArgumentException("Storage image lacks storage-image usage");
      VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack)
         .imageView(view.view())
         .imageLayout(view.layout());
      write.sType$Default()
         .dstBinding(binding)
         .dstArrayElement(0)
         .descriptorCount(1)
         .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
         .pImageInfo(info);
   }

   public static void writeAccelerationStructure(final VkWriteDescriptorSet write, final MemoryStack stack, final int binding, final long accelerationStructure) {
      counted(1);
      VkWriteDescriptorSetAccelerationStructureKHR info = VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack)
         .sType$Default()
         .pAccelerationStructures(stack.longs(accelerationStructure));
      write.sType$Default()
         .dstBinding(binding)
         .dstArrayElement(0)
         .descriptorCount(1)
         .descriptorType(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
         .pNext(info);
   }
}
