package dev.rt_render_experiment.vulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import dev.rt_render_experiment.contract.ResourceViews;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.VkAccelerationStructureCreateInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureDeviceAddressInfoKHR;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;
import org.jspecify.annotations.Nullable;


public final class VulkanResources {
   private final VkDevice device;
   private final long allocator;
   private final Thread owner = Thread.currentThread();
   private final SubmissionLifetime lifetime;
   private final List<Buffer> buffers = new ArrayList<>();
   private final List<Image> images = new ArrayList<>();
   private final List<AccelerationStructure> accelerationStructures = new ArrayList<>();
   private final List<QueryPool> queryPools = new ArrayList<>();
   private final List<ProgramRetirement> programs = new ArrayList<>();
   private record ProgramRetirement(long lastUse,long[] pipelines,long[] layouts,long[] descriptorLayouts) {
      private ProgramRetirement {
         pipelines=pipelines.clone();layouts=layouts.clone();descriptorLayouts=descriptorLayouts.clone();
         for(long[] group:new long[][]{pipelines,layouts,descriptorLayouts})for(long handle:group)
            if(handle==0)throw new IllegalArgumentException("Null owned program handle");
      }
      private void destroy(VkDevice device) {
         for(long pipeline:pipelines)VK12.vkDestroyPipeline(device,pipeline,null);
         for(long layout:layouts)VK12.vkDestroyPipelineLayout(device,layout,null);
         for(long layout:descriptorLayouts)VK12.vkDestroyDescriptorSetLayout(device,layout,null);
      }
   }
   private long nextId = 1L;
   private boolean closed;
   private int retiring;
   private int imageFailureCountdown = -1;
   private int imageFailureStep;
   private int accelerationFailureCountdown=-1;
   private int accelerationFailureStep;
   private boolean compactionQueryFailure;
   private @Nullable VulkanPipelineCache compilationCache;






   public long compilationCache(final String packageIdentity) {
      requireOpen();
      if (this.compilationCache == null) this.compilationCache = VulkanPipelineCache.create(this.device, packageIdentity, false);
      return this.compilationCache.handle();
   }

   public VulkanResources(final VkDevice device, final long allocator, final ResourceViews.Submission progress) {
      this.device = Objects.requireNonNull(device, "device");
      if (allocator == 0L) {
         throw new IllegalArgumentException("Invalid borrowed Vulkan allocator/device");
      }
      this.allocator = allocator;
      this.lifetime = new SubmissionLifetime(progress);
   }

   public void observe(final ResourceViews.Submission progress) {
      requireOpen();
      this.lifetime.observe(progress);
      if (this.textureTable != null) this.textureTable.completed();
      if (this.samplers != null) this.samplers.completed();
      if (this.retiring == 0) return;
      for(var iterator=this.programs.iterator();iterator.hasNext();) {
         var program=iterator.next();
         if(this.lifetime.reusable(program.lastUse())) { program.destroy(this.device);iterator.remove();this.retiring--; }
      }
      for (var iterator = this.queryPools.iterator(); iterator.hasNext();) {
         QueryPool pool = iterator.next();
         if (pool.retired && this.lifetime.reusable(pool.lastUse)) {
            pool.destroy();
            iterator.remove();
            this.retiring--;
         }
      }

      for (var iterator = this.accelerationStructures.iterator(); iterator.hasNext();) {
         AccelerationStructure structure = iterator.next();
         if (structure.retired && this.lifetime.reusable(structure.lastUse)) {
            structure.destroy();
            iterator.remove();
            this.retiring--;
         }
      }
      for (var iterator = this.buffers.iterator(); iterator.hasNext();) {
         Buffer buffer = iterator.next();
         if (buffer.retired && this.lifetime.reusable(buffer.lastUse)) {
            buffer.destroy();
            iterator.remove();
            this.retiring--;
         }
      }
      for (var iterator = this.images.iterator(); iterator.hasNext();) {
         Image image = iterator.next();
         if (image.retired && this.lifetime.reusable(image.lastUse)) {
            image.destroy();
            iterator.remove();
            this.retiring--;
         }
      }
   }

   public long completed() { requireOpen(); return this.lifetime.completed(); }
   public long recording() { requireOpen(); return this.lifetime.recording(); }






   public void retirePrograms(long lastUse,long[] pipelines,long[] layouts,long[] descriptorLayouts) {
      requireOpen();boolean reusable=this.lifetime.reusable(lastUse);
      var program=new ProgramRetirement(lastUse,pipelines,layouts,descriptorLayouts);
      if(reusable)program.destroy(this.device);
      else { this.programs.add(program);this.retiring++; }
   }
   public int pendingProgramRetirements() { requireOpen();return this.programs.size(); }
   public VkDevice device() { requireOpen(); return this.device; }

   public boolean closed() { requireThread(); return this.closed; }
   public long deviceIdentity() { return this.lifetime.device(); }

   private VulkanTextureTable textureTable;
   private VulkanSamplers samplers;
   public VulkanTextureTable textures() {
      requireOpen();
      if (this.textureTable == null) this.textureTable = new VulkanTextureTable(this);
      return this.textureTable;
   }
   public VulkanSamplers samplers() {
      requireOpen();
      if (this.samplers == null) this.samplers = new VulkanSamplers(this);
      return this.samplers;
   }
   public void releaseSampling() {
      requireOpen();
      if (this.textureTable != null) this.textureTable.releaseWorld();
      if (this.samplers != null) this.samplers.releaseWorld();
   }


   public ResourceViews.ResourceId reserveIdentity() {
      requireOpen();
      long id = this.nextId;
      this.nextId = Math.incrementExact(this.nextId);
      return new ResourceViews.ResourceId(id);
   }

   public record ImageSpecification(int format, int usage, int width, int height, int aspect, String label) {}


   public List<Image> allocateImages(final List<ImageSpecification> specifications) {
      requireOpen();
      List<Image> allocated = new ArrayList<>(specifications.size());
      try {
         for (ImageSpecification spec : specifications) {
            allocated.add(allocateImage(spec.format(), spec.usage(), spec.width(), spec.height(), spec.aspect(), spec.label()));
         }
         return List.copyOf(allocated);
      } catch (RuntimeException | Error failure) {
         for (int i = allocated.size() - 1; i >= 0; i--) VulkanRetirement.suppress(failure, allocated.get(i)::close);
         throw failure;
      }
   }


   void failImageAllocation(final int index, final int step) {
      requireOpen();
      if (index < 0 || step < 1 || step > 2) throw new IllegalArgumentException("Invalid allocation fault point");
      this.imageFailureCountdown = index;
      this.imageFailureStep = step;
   }


   public Image allocateImage(final int format, final int usage, final int width, final int height,
      final int aspect, final String label) {
      return allocateImageLevels(format, usage, width, height, aspect, 1, label);
   }


   public Image allocateImageLevels(final int format, final int usage, final int width, final int height,
      final int aspect, final int mipLevels, final String label) {
      requireOpen();
      if (format <= 0 || usage == 0 || width <= 0 || height <= 0 || aspect == 0 || mipLevels <= 0
         || mipLevels > 32 - Integer.numberOfLeadingZeros(Math.max(width, height))) {
         throw new IllegalArgumentException("Invalid renderer image allocation");
      }
      int failureStep = this.imageFailureCountdown >= 0 && this.imageFailureCountdown-- == 0 ? this.imageFailureStep : 0;
      try (MemoryStack stack = MemoryStack.stackPush()) {
         VkImageCreateInfo create = VkImageCreateInfo.calloc(stack).sType$Default()
            .imageType(VK12.VK_IMAGE_TYPE_2D).format(format).mipLevels(mipLevels).arrayLayers(1)
            .samples(VK12.VK_SAMPLE_COUNT_1_BIT).tiling(VK12.VK_IMAGE_TILING_OPTIMAL).usage(usage)
            .sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK12.VK_IMAGE_LAYOUT_UNDEFINED);
         create.extent().set(width, height, 1);
         var handle = stack.callocLong(1);
         var memory = stack.callocPointer(1);
         var view = stack.callocLong(1);
         VulkanObjects.requireSuccess(Vma.vmaCreateImage(this.allocator, create,
            VmaAllocationCreateInfo.calloc(stack).usage(Vma.VMA_MEMORY_USAGE_AUTO), handle, memory, null),
            "Allocate image " + label);
         boolean complete = false;
         try {
            if (failureStep == 1) throw new IllegalStateException("Injected failure after image allocation");
            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default()
               .image(handle.get(0)).viewType(VK12.VK_IMAGE_VIEW_TYPE_2D).format(format);
            viewInfo.subresourceRange().aspectMask(aspect).baseMipLevel(0).levelCount(mipLevels).baseArrayLayer(0).layerCount(1);
            VulkanObjects.requireSuccess(VK12.vkCreateImageView(this.device, viewInfo, null, view), "Create image view " + label);
            if (failureStep == 2) throw new IllegalStateException("Injected failure after image-view allocation");
            Image image = new Image(new ResourceViews.Image(reserveIdentity(), this.lifetime.device(), handle.get(0), view.get(0),
               1L, 1L, format, width, height, 0, mipLevels, 0, 1, aspect, usage, VK12.VK_IMAGE_LAYOUT_GENERAL), memory.get(0));
            VulkanObjects.name(this.device, VK12.VK_OBJECT_TYPE_IMAGE, handle.get(0), label);
            VulkanObjects.name(this.device, VK12.VK_OBJECT_TYPE_IMAGE_VIEW, view.get(0), label);
            this.images.add(image);
            complete = true;
            return image;
         } finally {
            if (!complete) {
               if (view.get(0) != 0L) VK12.vkDestroyImageView(this.device, view.get(0), null);
               Vma.vmaDestroyImage(this.allocator, handle.get(0), memory.get(0));
            }
         }
      }
   }


   public void initializeImages(final VkCommandBuffer command) {
      requireOpen();
      if (command.getDevice() != this.device) throw new IllegalArgumentException("Foreign image initialization command");
      int count = 0;
      for (Image image : this.images) if (!image.initialized && !image.retired) count++;
      if (count == 0) return;
      try (MemoryStack stack = MemoryStack.stackPush()) {
         VkImageMemoryBarrier.Buffer barriers = VkImageMemoryBarrier.calloc(count, stack);
         int index = 0;
         for (Image image : this.images) {
            if (image.initialized || image.retired) continue;
            VkImageMemoryBarrier barrier = barriers.get(index++).sType$Default()
               .oldLayout(VK12.VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK12.VK_IMAGE_LAYOUT_GENERAL)
               .srcAccessMask(0).dstAccessMask(VK12.VK_ACCESS_MEMORY_READ_BIT | VK12.VK_ACCESS_MEMORY_WRITE_BIT)
               .srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
               .image(image.facts.image());
            barrier.subresourceRange().aspectMask(image.facts.aspectMask()).baseMipLevel(0).levelCount(image.facts.mipCount()).baseArrayLayer(0).layerCount(1);
         }


         VK12.vkCmdPipelineBarrier(command, VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, null, null, barriers);
         for (Image image : this.images) {
            if (!image.initialized && !image.retired) {
               image.initialized = true;
               image.lastUse = this.lifetime.recording();
            }
         }
      }
   }

   public Buffer allocateMapped(final int bytes, final int usage) {
      return allocateBuffer(bytes, usage, true, false);
   }

   public Buffer allocateReadback(final int bytes) {
      return allocateBuffer(bytes, VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT, true, true);
   }

   public Buffer allocateDevice(final long bytes, final int usage) {
      return allocateBuffer(bytes, usage, false, false);
   }

   private Buffer allocateBuffer(final long bytes, final int usage, final boolean mapped, final boolean readback) {
      requireOpen();
      if (bytes <= 0 || usage == 0) throw new IllegalArgumentException("Invalid Vulkan buffer allocation");
      if (mapped) Math.toIntExact(bytes);
      try (MemoryStack stack = MemoryStack.stackPush()) {
         VkBufferCreateInfo create = VkBufferCreateInfo.calloc(stack).sType$Default().size(bytes).usage(usage)
            .sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE);
         VmaAllocationCreateInfo allocation = VmaAllocationCreateInfo.calloc(stack)
            .usage(readback ? Vma.VMA_MEMORY_USAGE_AUTO_PREFER_HOST : Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
         if (mapped) allocation
            .requiredFlags(VK12.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK12.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)
            .preferredFlags(readback ? VK12.VK_MEMORY_PROPERTY_HOST_CACHED_BIT : 0)
            .flags((readback ? Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT
               : Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT) | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);
         var handle = stack.callocLong(1);
         PointerBuffer memory = stack.callocPointer(1);
         VmaAllocationInfo info = VmaAllocationInfo.calloc(stack);
         int result = Vma.vmaCreateBuffer(this.allocator, create, allocation, handle, memory, info);
         if (result != VK12.VK_SUCCESS) throw new IllegalStateException("RtRenderExperiment buffer allocation failed: VkResult=" + result);
         boolean complete = false;
         try {
            if (mapped && info.pMappedData() == 0L) throw new IllegalStateException("RtRenderExperiment mapped allocation has no mapped address");
            long address = (usage & VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT) == 0 ? 0L
               : VK12.vkGetBufferDeviceAddress(this.device, VkBufferDeviceAddressInfo.calloc(stack).sType$Default().buffer(handle.get(0)));
            if ((usage & VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT) != 0 && address == 0L) {
               throw new IllegalStateException("RtRenderExperiment addressable allocation has no device address");
            }
            ResourceViews.Buffer view = new ResourceViews.Buffer(
               reserveIdentity(), this.lifetime.device(), handle.get(0), 1L,
               bytes, 0L, bytes, address, usage
            );
            Buffer buffer = new Buffer(view, memory.get(0), mapped
               ? MemoryUtil.memByteBuffer(info.pMappedData(), Math.toIntExact(bytes)).order(ByteOrder.nativeOrder()) : null);
            this.buffers.add(buffer);
            complete = true;
            return buffer;
         } finally {
            if (!complete) Vma.vmaDestroyBuffer(this.allocator, handle.get(0), memory.get(0));
         }
      }
   }

   public QueryPool allocateTimestampQueries(final int count) {
      return allocateQueries(VK12.VK_QUERY_TYPE_TIMESTAMP,count);
   }

   public QueryPool allocateCompactedSizeQueries(final int count) {
      requireOpen();
      if(compactionQueryFailure) { compactionQueryFailure=false;throw new VulkanObjects.Failure(VK12.VK_ERROR_OUT_OF_DEVICE_MEMORY,"Injected compact-size query allocation failure"); }
      return allocateQueries(KHRAccelerationStructure.VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR,count);
   }
   void failCompactionQueryAllocation() { requireOpen();compactionQueryFailure=true; }

   private QueryPool allocateQueries(final int type,final int count) {
      requireOpen();
      if (count <= 0) throw new IllegalArgumentException("Invalid query count");
      try (MemoryStack stack = MemoryStack.stackPush()) {
         var handle = stack.callocLong(1);
         VulkanObjects.requireSuccess(VK12.vkCreateQueryPool(this.device,
            VkQueryPoolCreateInfo.calloc(stack).sType$Default().queryType(type).queryCount(count),
            null, handle), "Allocate RtRenderExperiment query pool");
         try {
            QueryPool pool = new QueryPool(handle.get(0), count);
            this.queryPools.add(pool);
            return pool;
         } catch (RuntimeException | Error failure) {
            VK12.vkDestroyQueryPool(this.device, handle.get(0), null);
            throw failure;
         }
      }
   }

   public final class QueryPool implements AutoCloseable {
      private final long handle;
      private final int count;
      private long lastUse;
      private boolean retired;
      private boolean destroyed;
      private QueryPool(final long handle, final int count) { this.handle = handle; this.count = count; }
      public long handle() { return this.handle; }
      public boolean destroyed() { return this.destroyed; }

      public void acquire(final VkCommandBuffer command) {
         requireOpen();
         if(command.getDevice()!=VulkanResources.this.device || this.retired || this.destroyed
            || !VulkanResources.this.lifetime.reusable(this.lastUse))throw new IllegalStateException("Query pool is foreign, retired or still in use");
         VK12.vkCmdResetQueryPool(command,this.handle,0,this.count);
         this.lastUse=VulkanResources.this.lifetime.recording();
      }
      public void acquire() {
         requireOpen();
         if (this.retired || this.destroyed || !VulkanResources.this.lifetime.reusable(this.lastUse)) {
            throw new IllegalStateException("Timestamp pool is retired or still in use");
         }
         VK12.vkResetQueryPool(VulkanResources.this.device, this.handle, 0, this.count);
         this.lastUse = VulkanResources.this.lifetime.recording();
      }
      @Override public void close() {
         requireOpen();
         if (!this.retired) { this.retired = true; VulkanResources.this.retiring++; }
      }
      private void destroy() {
         if (!this.destroyed) {
            VK12.vkDestroyQueryPool(VulkanResources.this.device, this.handle, null);
            this.destroyed = true;
         }
      }
   }

   public AccelerationStructure allocateAccelerationStructure(final long bytes, final boolean topLevel) {
      requireOpen();
      int injectedStep=this.accelerationFailureCountdown>=0 && this.accelerationFailureCountdown--==0?this.accelerationFailureStep:0;
      if(injectedStep==1)throw new VulkanObjects.Failure(VK12.VK_ERROR_OUT_OF_DEVICE_MEMORY,"Injected AS backing allocation failure");
      Buffer backing = allocateDevice(bytes, VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
         | KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR);
      long handle = 0L;
      try (MemoryStack stack = MemoryStack.stackPush()) {
         if(injectedStep==2)throw new VulkanObjects.Failure(VK12.VK_ERROR_OUT_OF_DEVICE_MEMORY,"Injected AS handle allocation failure");
         var result = stack.callocLong(1);
         VulkanObjects.requireSuccess(KHRAccelerationStructure.vkCreateAccelerationStructureKHR(this.device,
            VkAccelerationStructureCreateInfoKHR.calloc(stack).sType$Default().buffer(backing.view.handle()).size(bytes)
               .type(topLevel ? KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR
                  : KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR), null, result),
            "Create RtRenderExperiment acceleration structure");
         handle = result.get(0);
         long address = KHRAccelerationStructure.vkGetAccelerationStructureDeviceAddressKHR(this.device,
            VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack).sType$Default().accelerationStructure(handle));
         if (address == 0L) throw new IllegalStateException("RtRenderExperiment acceleration structure has no device address");
         AccelerationStructure structure = new AccelerationStructure(backing, handle, address, topLevel);
         this.accelerationStructures.add(structure);
         return structure;
      } catch (RuntimeException | Error failure) {
         if (handle != 0L) KHRAccelerationStructure.vkDestroyAccelerationStructureKHR(this.device, handle, null);
         backing.close();
         throw failure;
      }
   }


   void failAccelerationAllocation(int index,int step) {
      requireOpen();if(index<0 || step<1 || step>2)throw new IllegalArgumentException("Invalid AS allocation failure point");
      this.accelerationFailureCountdown=index;this.accelerationFailureStep=step;
   }

   public final class AccelerationStructure implements AutoCloseable {
      private final Buffer backing;
      private final long handle;
      private final long address;
      private final boolean topLevel;
      private long lastUse;
      private boolean retired;
      private boolean destroyed;

      private AccelerationStructure(final Buffer backing, final long handle, final long address, final boolean topLevel) {
         this.backing = backing;
         this.handle = handle;
         this.address = address;
         this.topLevel = topLevel;
      }

      public long handle() { return this.handle; }
      public long address() { return this.address; }
      public long size() { return this.backing.view.length(); }
      public long deviceIdentity() { return VulkanResources.this.deviceIdentity(); }
      public boolean topLevel() { return this.topLevel; }
      public boolean closed() { return this.retired || this.destroyed; }
      public boolean destroyed() { return this.destroyed; }

      public void markUsed() {
         requireOpen();
         if (closed()) throw new IllegalStateException("RtRenderExperiment acceleration structure is retired");
         this.lastUse = VulkanResources.this.lifetime.recording();
         this.backing.markUsed();
      }

      @Override public void close() {
         requireOpen();
         if (this.retired) return;


         markUsed();
         this.retired = true;
         VulkanResources.this.retiring++;
         this.backing.close();
      }

      private void destroy() {
         if (!this.destroyed) {
            KHRAccelerationStructure.vkDestroyAccelerationStructureKHR(VulkanResources.this.device, this.handle, null);
            this.destroyed = true;
         }
      }
   }


   public void closeAfterHost() {
      requireThread();
      if (this.closed) return;

      int result = VK12.vkDeviceWaitIdle(this.device);
      if (result != VK12.VK_SUCCESS) throw new IllegalStateException("RtRenderExperiment buffer teardown did not observe GPU idle: VkResult=" + result);
      for (Buffer buffer : this.buffers) {
         if (buffer.mappings != 0) throw new IllegalStateException("Mapped RtRenderExperiment buffer survived device teardown");
      }
      for(var program:this.programs)program.destroy(this.device);
      this.programs.clear();
      if (this.textureTable != null) this.textureTable.destroyAfterIdle();
      if (this.samplers != null) this.samplers.destroyAfterIdle();
      for (AccelerationStructure structure : this.accelerationStructures) structure.destroy();
      this.accelerationStructures.clear();
      for (QueryPool pool : this.queryPools) pool.destroy();
      this.queryPools.clear();
      for (Buffer buffer : this.buffers) buffer.destroy();
      this.buffers.clear();
      for (Image image : this.images) image.destroy();
      this.images.clear();
      if (this.compilationCache != null) {
         this.compilationCache.close();
         this.compilationCache = null;
      }
      this.retiring = 0;
      this.closed = true;
   }

   public final class Image implements AutoCloseable {
      private final ResourceViews.Image facts;
      private final long allocation;
      private long lastUse;
      private boolean initialized;
      private boolean retired;
      private boolean destroyed;

      private Image(final ResourceViews.Image facts, final long allocation) {
         this.facts = facts;
         this.allocation = allocation;
      }


      public long nativeImage() { return this.facts.image(); }
      public long nativeView() { return this.facts.view(); }
      public boolean closed() { return this.retired || this.destroyed; }

      public ResourceViews.Image view() {
         requireOpen();
         if (!this.initialized || closed()) throw new IllegalStateException("Image was not published or has retired");
         return this.facts;
      }

      public void markUsed() {
         view();
         this.lastUse = VulkanResources.this.lifetime.recording();
      }

      @Override public void close() {
         requireOpen();
         if (!this.retired) {
            this.retired = true;
            VulkanResources.this.retiring++;
         }
      }

      private void destroy() {
         if (!this.destroyed) {
            VK12.vkDestroyImageView(VulkanResources.this.device, this.facts.view(), null);
            Vma.vmaDestroyImage(VulkanResources.this.allocator, this.facts.image(), this.allocation);
            this.destroyed = true;
         }
      }
   }

   private void requireThread() {
      if (Thread.currentThread() != this.owner) throw new IllegalStateException("RtRenderExperiment buffer owner used from another thread");
   }

   private void requireOpen() {
      requireThread();
      if (this.closed) throw new IllegalStateException("RtRenderExperiment buffer allocator view is closed");
   }

   public final class Buffer implements AutoCloseable {
      private final ResourceViews.Buffer view;
      private final long allocation;
      private final @Nullable ByteBuffer mapped;
      private long lastUse;
      private int mappings;
      private boolean retired;
      private boolean destroyed;

      private Buffer(final ResourceViews.Buffer view, final long allocation, final @Nullable ByteBuffer mapped) {
         this.view = view;
         this.allocation = allocation;
         this.mapped = mapped;
      }

      public ResourceViews.Buffer view() { return this.view; }
      public boolean closed() { return this.retired || this.destroyed; }
      public boolean destroyed() { return this.destroyed; }
      public long lastUse() { return this.lastUse; }

      public void markUsed() {
         requireOpen();
         if (closed()) throw new IllegalStateException("RtRenderExperiment buffer is retired");
         this.lastUse = VulkanResources.this.lifetime.recording();
      }

      public Mapping map(final long offset, final long length) {
         requireOpen();
         if (closed()) throw new IllegalStateException("RtRenderExperiment buffer is retired");
         if (this.mapped == null) throw new IllegalStateException("RtRenderExperiment device buffer is not mapped");
         if (offset < 0L || length < 0L || offset > this.view.length() || length > this.view.length() - offset) {
            throw new IllegalArgumentException("Mapped range escapes RtRenderExperiment allocation");
         }
         this.mappings++;
         return new Mapping(this.mapped.slice(Math.toIntExact(offset), Math.toIntExact(length)).order(ByteOrder.nativeOrder()));
      }

      @Override public void close() {
         requireOpen();
         if (this.mappings != 0) throw new IllegalStateException("Cannot retire a mapped RtRenderExperiment buffer");
         if (!this.retired) {
            this.retired = true;
            VulkanResources.this.retiring++;
         }
      }

      private void destroy() {
         if (!this.destroyed) {
            Vma.vmaDestroyBuffer(VulkanResources.this.allocator, this.view.handle(), this.allocation);
            this.destroyed = true;
         }
      }

      public final class Mapping implements AutoCloseable {
         private final ByteBuffer data;
         private boolean finished;
         private Mapping(final ByteBuffer data) { this.data = data; }
         public ByteBuffer data() {
            if (this.finished) throw new IllegalStateException("RtRenderExperiment mapped view is closed");
            return this.data;
         }
         @Override public void close() {
            requireThread();
            if (!this.finished) {
               this.finished = true;
               Buffer.this.mappings--;
            }
         }
      }
   }
}
