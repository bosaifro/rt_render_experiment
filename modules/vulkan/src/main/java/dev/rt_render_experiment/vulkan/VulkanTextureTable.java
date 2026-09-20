package dev.rt_render_experiment.vulkan;

import java.util.ArrayList;
import java.util.List;
import dev.rt_render_experiment.contract.ResourceViews;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;


public final class VulkanTextureTable {

   public static final int FIXED_SAMPLED_IMAGES = 5;
   public static final int FIXED_STAGE_RESOURCES = 15;
   private final VulkanResources resources;
   private final long layout;
   private final int capacity;
   private final TextureSlots slots;
   private final List<Snapshot> snapshots = new ArrayList<>();
   private Snapshot current;
   private ResourceViews.SampledImage fallback;

   VulkanTextureTable(final VulkanResources resources) {
      this.resources = resources;
      try (MemoryStack stack = MemoryStack.stackPush()) {
         var properties = VkPhysicalDeviceProperties.calloc(stack);
         VK12.vkGetPhysicalDeviceProperties(resources.device().getPhysicalDevice(), properties);
         var limits = properties.limits();
         long supported = Math.min(Math.min(Integer.toUnsignedLong(limits.maxPerStageDescriptorSamplers()),
            Integer.toUnsignedLong(limits.maxPerStageDescriptorSampledImages())),
            Math.min(Integer.toUnsignedLong(limits.maxDescriptorSetSamplers()), Integer.toUnsignedLong(limits.maxDescriptorSetSampledImages())))
            - FIXED_SAMPLED_IMAGES;
         supported = Math.min(supported, Integer.toUnsignedLong(limits.maxPerStageResources()) - FIXED_STAGE_RESOURCES);
         if (supported <= 0L) throw new IllegalStateException("Device has no texture-table capacity");
         int stages = VK12.VK_SHADER_STAGE_FRAGMENT_BIT | VK12.VK_SHADER_STAGE_COMPUTE_BIT;
         if (resources.device().getCapabilities().VK_KHR_ray_tracing_pipeline) stages |= KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR
            | KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR | KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR | KHRRayTracingPipeline.VK_SHADER_STAGE_CALLABLE_BIT_KHR;
         var bindings = VkDescriptorSetLayoutBinding.calloc(1, stack).binding(0)
            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount((int)Math.min(supported, Integer.MAX_VALUE))
            .stageFlags(stages);
         var flags = VkDescriptorSetLayoutBindingFlagsCreateInfo.calloc(stack).sType$Default()
            .pBindingFlags(stack.ints(VK12.VK_DESCRIPTOR_BINDING_VARIABLE_DESCRIPTOR_COUNT_BIT));
         var create = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pNext(flags).pBindings(bindings);
         var variableSupport = VkDescriptorSetVariableDescriptorCountLayoutSupport.calloc(stack).sType$Default();
         var support = VkDescriptorSetLayoutSupport.calloc(stack).sType$Default().pNext(variableSupport);
         VK12.vkGetDescriptorSetLayoutSupport(resources.device(), create, support);
         int maximum = (int)Math.min(Math.min(supported, Integer.MAX_VALUE), Integer.toUnsignedLong(variableSupport.maxVariableDescriptorCount()));
         if (maximum <= 0) throw new IllegalStateException("Device cannot allocate variable texture descriptors");
         bindings.descriptorCount(maximum);
         VK12.vkGetDescriptorSetLayoutSupport(resources.device(), create, support);
         if (!support.supported()) throw new IllegalStateException("Texture descriptor layout is unsupported");
         var result = stack.callocLong(1);
         VulkanObjects.requireSuccess(VK12.vkCreateDescriptorSetLayout(resources.device(), create, null, result), "Create RtRenderExperiment texture layout");
         this.layout = result.get(0);
         this.capacity = maximum;
         this.slots = new TextureSlots(maximum);
         org.slf4j.LoggerFactory.getLogger(VulkanTextureTable.class).info("RT_RENDER_EXPERIMENT_TEXTURE_TABLE capacity={} policy=immutable_versions", maximum);
      }
   }

   public int capacity() { return this.capacity; }
   public long layout() { return this.layout; }
   public TextureSlots slots() { return this.slots; }

   public Snapshot prepare(final List<ResourceViews.SampledImage> textures, final ResourceViews.Image fallbackView, final long fallbackSampler) {
      if (this.fallback == null || !this.fallback.view().equals(fallbackView) || this.fallback.sampler() != fallbackSampler) {
         this.fallback = new ResourceViews.SampledImage(fallbackView, fallbackSampler);
      }
      ResourceViews.SampledImage fallback = this.fallback;
      if (textures.size() > this.capacity) throw new IllegalArgumentException("Texture demand exceeds the device descriptor limit");
      int count = Math.max(1, textures.size());
      boolean unchanged = this.current != null && this.current.contents.size() == count;
      for (int index = 0; index < count; index++) {
         var texture = textures.isEmpty() || textures.get(index) == null ? fallback : textures.get(index);
         if (texture.view().device() != this.resources.deviceIdentity()
            || (texture.view().usage() & VK12.VK_IMAGE_USAGE_SAMPLED_BIT) == 0) {
            throw new IllegalArgumentException("Foreign or unsampleable texture-table entry");
         }
         unchanged &= this.current != null && index < this.current.contents.size() && this.current.contents.get(index).equals(texture);
      }
      if (unchanged) {
         this.current.lastUse = this.resources.recording();
         return this.current;
      }
      ArrayList<ResourceViews.SampledImage> contents = new ArrayList<>(count);
      for (int index = 0; index < count; index++) contents.add(textures.isEmpty() || textures.get(index) == null ? fallback : textures.get(index));
      Snapshot replacement = allocate(List.copyOf(contents));
      try { this.snapshots.add(replacement); }
      catch (RuntimeException | Error failure) { replacement.destroy(); throw failure; }
      replacement.lastUse = this.resources.recording();
      this.current = replacement;
      completed();
      return replacement;
   }

   private Snapshot allocate(final List<ResourceViews.SampledImage> contents) {
      long pool = 0L;
      try (MemoryStack stack = MemoryStack.stackPush()) {
         var handle = stack.callocLong(1);
         VulkanObjects.requireSuccess(VK12.vkCreateDescriptorPool(this.resources.device(),
            VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(
               VkDescriptorPoolSize.calloc(1, stack).type(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(contents.size())),
            null, handle), "Create texture descriptor pool");
         pool = handle.get(0);
         var count = VkDescriptorSetVariableDescriptorCountAllocateInfo.calloc(stack).sType$Default().pDescriptorCounts(stack.ints(contents.size()));
         VulkanObjects.requireSuccess(VK12.vkAllocateDescriptorSets(this.resources.device(),
            VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(pool).pSetLayouts(stack.longs(this.layout)).pNext(count), handle),
            "Allocate texture descriptor table");
         long set = handle.get(0);
         var images = VkDescriptorImageInfo.calloc(contents.size(), stack);
         for (int index = 0; index < contents.size(); index++) {
            var entry = contents.get(index);
            images.get(index).imageView(entry.view().view()).imageLayout(entry.view().layout()).sampler(entry.sampler());
         }
         var write = VkWriteDescriptorSet.calloc(1, stack).sType$Default().dstSet(set).dstBinding(0)
            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(contents.size()).pImageInfo(images);
         VK12.vkUpdateDescriptorSets(this.resources.device(), write, null);
         VulkanDescriptors.tableWrite(contents.size());
         return new Snapshot(pool, set, contents);
      } catch (RuntimeException | Error failure) {
         if (pool != 0L) VK12.vkDestroyDescriptorPool(this.resources.device(), pool, null);
         throw failure;
      }
   }

   public final class Snapshot {
      private final long pool;
      private final long set;
      private final List<ResourceViews.SampledImage> contents;
      private long lastUse;
      private int retainers;
      private boolean destroyed;
      private Snapshot(final long pool, final long set, final List<ResourceViews.SampledImage> contents) {
         this.pool = pool; this.set = set; this.contents = contents;
      }
      public int allocatedCount() { return this.contents.size(); }
      public long deviceIdentity() { return VulkanTextureTable.this.resources.deviceIdentity(); }
      public boolean destroyed() { return this.destroyed; }

      public Lease retain() {
         if (this.destroyed) throw new IllegalStateException("Cannot retain a destroyed texture table");
         this.retainers = Math.incrementExact(this.retainers);
         return new Lease();
      }
      public final class Lease implements AutoCloseable {
         private boolean closed;
         private Lease() {}
         @Override public void close() {
            if (!this.closed) {
               this.closed = true;
               Snapshot.this.retainers--;
               VulkanTextureTable.this.completed();
            }
         }
      }
      public void bind(final VkCommandBuffer command, final int bindPoint, final long pipelineLayout) {
         if (this.destroyed || command.getDevice() != VulkanTextureTable.this.resources.device()) {
            throw new IllegalStateException("Texture table is retired or belongs to another device");
         }
         this.lastUse = VulkanTextureTable.this.resources.recording();
         try (MemoryStack stack = MemoryStack.stackPush()) {
            VK12.vkCmdBindDescriptorSets(command, bindPoint, pipelineLayout, 1, stack.longs(this.set), null);
         }
      }
      private void destroy() {
         if (!this.destroyed) {
            VK12.vkDestroyDescriptorPool(VulkanTextureTable.this.resources.device(), this.pool, null);
            this.destroyed = true;
         }
      }
   }


   public void releaseWorld() { this.current = null; this.fallback = null; this.slots.reset(); completed(); }
   void completed() {
      long completed = this.resources.completed();
      this.snapshots.removeIf(snapshot -> {
         if (snapshot != this.current && snapshot.retainers == 0 && snapshot.lastUse <= completed) { snapshot.destroy(); return true; }
         return false;
      });
   }
   void destroyAfterIdle() {
      for (Snapshot snapshot : this.snapshots) snapshot.destroy();
      this.snapshots.clear(); this.current = null;
      VK12.vkDestroyDescriptorSetLayout(this.resources.device(), this.layout, null);
   }
}
