package dev.rt_render_experiment.vulkan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkSamplerCreateInfo;


public final class VulkanSamplers {
   public record State(int addressU, int addressV, int minFilter, int magFilter, int mipmapMode, float maxLod, float maxAnisotropy) {
      public State {
         if (addressU < 0 || addressU > 4 || addressV < 0 || addressV > 4 || minFilter < 0 || minFilter > 1
            || magFilter < 0 || magFilter > 1 || mipmapMode < 0 || mipmapMode > 1
            || !Float.isFinite(maxLod) || maxLod < 0 || !Float.isFinite(maxAnisotropy) || maxAnisotropy < 1) {
            throw new IllegalArgumentException("Invalid native sampler state");
         }
      }
   }
   private static final class Entry {
      final long handle;
      long lastUse;
      boolean retired;
      Entry(final long handle) { this.handle = handle; }
   }
   private final VulkanResources resources;
   private final Map<State, Entry> active = new HashMap<>();
   private final ArrayList<Entry> entries = new ArrayList<>();
   private final long capacity;
   private final float maxAnisotropy;

   VulkanSamplers(final VulkanResources resources) {
      this.resources = resources;
      try (MemoryStack stack = MemoryStack.stackPush()) {
         var properties = VkPhysicalDeviceProperties.calloc(stack);
         VK12.vkGetPhysicalDeviceProperties(resources.device().getPhysicalDevice(), properties);
         this.capacity = Integer.toUnsignedLong(properties.limits().maxSamplerAllocationCount());
         this.maxAnisotropy = properties.limits().maxSamplerAnisotropy();
      }
      org.slf4j.LoggerFactory.getLogger(VulkanSamplers.class).info("RT_RENDER_EXPERIMENT_SAMPLERS capacity={} policy=completion_observed", this.capacity);
   }

   public long get(final State state) {
      if (state.maxAnisotropy() > this.maxAnisotropy) throw new IllegalArgumentException("Sampler anisotropy exceeds device support");
      Entry entry = this.active.get(state);
      if (entry == null) {
         completed();
         if (this.entries.size() >= this.capacity) throw new IllegalStateException("Native sampler allocation limit reached");
         try (MemoryStack stack = MemoryStack.stackPush()) {
            var result = stack.callocLong(1);
            var info = VkSamplerCreateInfo.calloc(stack).sType$Default().addressModeU(state.addressU()).addressModeV(state.addressV())
               .minFilter(state.minFilter()).magFilter(state.magFilter()).mipmapMode(state.mipmapMode()).maxLod(state.maxLod())
               .maxAnisotropy(state.maxAnisotropy()).anisotropyEnable(state.maxAnisotropy() > 1F);
            VulkanObjects.requireSuccess(VK12.vkCreateSampler(this.resources.device(), info, null, result), "Create RtRenderExperiment sampler");
            long handle = result.get(0);
            try {
               entry = new Entry(handle);
               this.entries.add(entry);
               this.active.put(state, entry);
            } catch (RuntimeException | Error failure) {
               this.entries.remove(entry);
               this.active.remove(state);
               VK12.vkDestroySampler(this.resources.device(), handle, null);
               throw failure;
            }
         }
      }
      entry.lastUse = this.resources.recording();
      return entry.handle;
   }
   public int size() { return this.active.size(); }

   public void markUsed(final long handle) {
      for (Entry entry : this.entries) if (entry.handle == handle) {
         if (entry.retired) throw new IllegalStateException("Sampler belongs to a retired generation");
         entry.lastUse = this.resources.recording();
         return;
      }
      throw new IllegalArgumentException("Unknown renderer sampler");
   }
   public int liveAllocations() { return this.entries.size(); }
   public void releaseWorld() { for (Entry entry : this.active.values()) entry.retired = true; this.active.clear(); completed(); }
   void completed() {
      long completed = this.resources.completed();
      this.entries.removeIf(entry -> {
         if (entry.retired && entry.lastUse <= completed) {
            VK12.vkDestroySampler(this.resources.device(), entry.handle, null);
            return true;
         }
         return false;
      });
   }
   void destroyAfterIdle() {
      for (Entry entry : this.entries) VK12.vkDestroySampler(this.resources.device(), entry.handle, null);
      this.active.clear(); this.entries.clear();
   }
}
