package dev.rt_render_experiment.vulkan;

import java.util.List;
import java.util.Objects;
import dev.rt_render_experiment.contract.ResourceViews;
import dev.rt_render_experiment.contract.TriangleGeometry;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkAccelerationStructureBuildGeometryInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildRangeInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildSizesInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryKHR;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructurePropertiesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkCopyAccelerationStructureInfoKHR;


public final class VulkanAccelerationStructures implements AutoCloseable {
   private final VulkanResources resources;
   private final long scratchAlignment;
   private final long maxGeometries;
   private final long maxPrimitives;
   private final long maxInstances;
   private VulkanResources.@Nullable Buffer scratch;
   private long cursor;
   private long batch;
   private int sizedInstances = -1;
   private long tlasBytes;
   private long tlasScratchBytes;

   public VulkanAccelerationStructures(final VulkanResources resources) {
      this.resources = Objects.requireNonNull(resources, "resources");
      try (MemoryStack stack = MemoryStack.stackPush()) {
         var limits = VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(stack).sType$Default();
         VK12.vkGetPhysicalDeviceProperties2(resources.device().getPhysicalDevice(),
            VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(limits.address()));
         this.scratchAlignment = Integer.toUnsignedLong(limits.minAccelerationStructureScratchOffsetAlignment());
         this.maxGeometries = limits.maxGeometryCount();
         this.maxPrimitives = limits.maxPrimitiveCount();
         this.maxInstances = limits.maxInstanceCount();
      }
      alignUp(0L, this.scratchAlignment);
   }

   public void beginBatch() {
      this.cursor = 0L;
      this.batch = Math.incrementExact(this.batch);
   }

   public long scratchBytes() { return this.scratch == null ? 0L : this.scratch.view().length(); }

   public final class BottomLevel implements AutoCloseable {
      private final VulkanAccelerationStructures owner=VulkanAccelerationStructures.this;
      private final VulkanResources.AccelerationStructure structure;
      private final List<TriangleGeometry> topology;
      private final int flags;
      private final long buildScratchBytes;
      private final long updateScratchBytes;
      private final boolean compacted;
      private long recordedBuild,submittedBuild;

      private BottomLevel(final VulkanResources.AccelerationStructure structure, final List<TriangleGeometry> topology,
         final int flags, final long buildScratchBytes, final long updateScratchBytes,final boolean compacted) {
         this.structure = structure;
         this.topology = topology;
         this.flags = flags;
         this.buildScratchBytes = buildScratchBytes;
         this.updateScratchBytes = updateScratchBytes;
         this.compacted=compacted;
      }

      public long handle() { return this.structure.handle(); }
      public long address() { return this.structure.address(); }
      public long size() { return this.structure.size(); }
      public boolean closed() { return this.structure.closed(); }
      public boolean destroyed() { return this.structure.destroyed(); }
      public boolean compactable() { return !this.compacted && (this.flags & KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_COMPACTION_BIT_KHR)!=0; }
      public boolean compacted() { return this.compacted; }
      public boolean readyForCompactionQuery() { return !closed() && compactable() && submittedBuild>0 && submittedBuild<=resources.completed(); }

      public boolean readyForUpdate() {
         return !closed() && !compacted && (flags & KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR)!=0
            && submittedBuild>0 && recordedBuild==submittedBuild && submittedBuild<resources.recording();
      }
      public void markUsed() { this.structure.markUsed(); }
      @Override public void close() { this.structure.close(); }
   }

   public BottomLevel allocateBottomLevel(final List<TriangleGeometry> geometry, final boolean deformable) {
      return allocateBottomLevel(geometry,deformable,false);
   }


   public BottomLevel allocateBottomLevel(final List<TriangleGeometry> geometry, final boolean deformable,final boolean compactable) {
      if(deformable && compactable)throw new IllegalArgumentException("Compaction policy requires immutable geometry");
      List<TriangleGeometry> facts = List.copyOf(geometry);
      requireTriangles(facts);
      int flags = KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR
         | (deformable ? KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR : 0)
         | (compactable ? KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_COMPACTION_BIT_KHR : 0);
      try (MemoryStack stack = MemoryStack.stackPush()) {
         var sizes = sizes(stack, triangles(stack, facts, flags), counts(facts));
         return new BottomLevel(this.resources.allocateAccelerationStructure(
            Math.max(sizes.accelerationStructureSize(), deformable ? 4096L : 1L), false), facts, flags,
            sizes.buildScratchSize(), sizes.updateScratchSize(),false);
      }
   }

   public Build build(final BottomLevel target, final List<TriangleGeometry> geometry, final boolean update) {
      List<TriangleGeometry> facts = List.copyOf(geometry);
      requireTriangles(facts);
      if (target.structure.deviceIdentity() != this.resources.deviceIdentity() || target.closed()) {
         throw new IllegalArgumentException("Foreign or retired BLAS target");
      }
      if (facts.size() != target.topology.size()) throw new IllegalArgumentException("BLAS geometry count changed");
      for (int i = 0; i < facts.size(); i++) {
         if (!facts.get(i).canRefit(target.topology.get(i))) throw new IllegalArgumentException("BLAS topology changed");
      }
      if (update && (target.flags & KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR) == 0) {
         throw new IllegalArgumentException("BLAS was not allocated for deformation");
      }
      if(target.compacted || target.compactable() && target.recordedBuild!=0)throw new IllegalStateException("Immutable compactable AS storage cannot be overwritten");
      return new Build(target.structure, target, facts, null, counts(facts), target.flags, update,
         claimScratch(update ? target.updateScratchBytes : target.buildScratchBytes),null);
   }


   public Build update(final BottomLevel source,final BottomLevel target,final List<TriangleGeometry> geometry) {
      List<TriangleGeometry> facts=List.copyOf(geometry);
      requireTriangles(facts);
      if(source.owner!=this || target.owner!=this || source==target || !source.readyForUpdate()
         || target.closed() || target.recordedBuild!=0 || source.flags!=target.flags)
         throw new IllegalArgumentException("Update requires a submitted source and a distinct unwritten destination from this owner");
      if(facts.size()!=source.topology.size() || facts.size()!=target.topology.size())throw new IllegalArgumentException("BLAS geometry count changed");
      for(int i=0;i<facts.size();i++)if(!facts.get(i).canRefit(source.topology.get(i)) || !facts.get(i).canRefit(target.topology.get(i)))
         throw new IllegalArgumentException("BLAS update topology changed");

      return new Build(target.structure,target,facts,null,counts(facts),target.flags,true,claimScratch(target.updateScratchBytes),source);
   }

   public Build topLevel(final ResourceViews.Buffer instances, final int count,
      final VulkanResources.@Nullable AccelerationStructure previous) {
      requireInput(instances, 16L);
      if (count < 0 || Long.compareUnsigned(this.maxInstances, Integer.toUnsignedLong(count)) < 0
         || Math.multiplyExact((long)count, 64L) > instances.length()) {
         throw new IllegalArgumentException("TLAS instances exceed bounded input or device limit");
      }
      int flags = KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR;
      try (MemoryStack stack = MemoryStack.stackPush()) {


         if (count > this.sizedInstances) {
            var sizes = sizes(stack, instances(stack, instances, flags), new int[] {count});
            this.tlasBytes = sizes.accelerationStructureSize();
            this.tlasScratchBytes = sizes.buildScratchSize();
            this.sizedInstances = count;
         }
         if (previous != null && (previous.deviceIdentity() != this.resources.deviceIdentity()
            || !previous.topLevel() || previous.closed())) throw new IllegalArgumentException("Invalid previous TLAS");

         long offset = claimScratch(this.tlasScratchBytes);
         var target = previous != null && previous.size() >= this.tlasBytes ? previous
            : this.resources.allocateAccelerationStructure(Math.max(Math.multiplyExact(this.tlasBytes, 2L), 65536L), true);
         return new Build(target, null, List.of(), instances, new int[] {count}, flags, false, offset,null);
      }
   }


   public final class Build {
      private final VulkanResources.AccelerationStructure target;
      private final @Nullable BottomLevel bottomLevel;
      private final @Nullable BottomLevel updateSource;
      private final long sourceSubmission;
      private final List<TriangleGeometry> geometry;
      private final ResourceViews.@Nullable Buffer instances;
      private final int[] primitiveCounts;
      private final int flags;
      private final boolean update;
      private final long scratchOffset;
      private final long preparedBatch = VulkanAccelerationStructures.this.batch;

      private Build(final VulkanResources.AccelerationStructure target,final @Nullable BottomLevel bottomLevel, final List<TriangleGeometry> geometry,
         final ResourceViews.@Nullable Buffer instances, final int[] counts, final int flags, final boolean update, final long offset,
         final @Nullable BottomLevel updateSource) {
         this.target = target;
         this.bottomLevel=bottomLevel;
         this.updateSource=updateSource;
         this.sourceSubmission=updateSource==null?0:updateSource.submittedBuild;
         this.geometry = geometry;
         this.instances = instances;
         this.primitiveCounts = counts;
         this.flags = flags;
         this.update = update;
         this.scratchOffset = offset;
      }

      public VulkanResources.AccelerationStructure target() { return this.target; }
      public boolean updates() { return update; }
      public void submitted(long serial) {
         if(bottomLevel!=null) {
            if(bottomLevel.recordedBuild!=serial || serial<=bottomLevel.submittedBuild)throw new IllegalStateException("BLAS lacks its exact build submission");
            bottomLevel.submittedBuild=serial;
         }
      }

      public void record(final VkCommandBuffer command) {
         if (command.getDevice() != VulkanAccelerationStructures.this.resources.device()
            || this.preparedBatch != VulkanAccelerationStructures.this.batch) {
            throw new IllegalStateException("Foreign command device or expired AS batch");
         }
         this.target.markUsed();
         if(updateSource!=null) {
            if(!updateSource.readyForUpdate() || updateSource.submittedBuild!=sourceSubmission
               || Objects.requireNonNull(bottomLevel).recordedBuild!=0)throw new IllegalStateException("Expired BLAS update source or written destination");
            updateSource.markUsed();
         }
         if(bottomLevel!=null && bottomLevel.compactable() && bottomLevel.recordedBuild!=0)throw new IllegalStateException("Repeated immutable BLAS recording");
         VulkanResources.Buffer scratchBuffer = Objects.requireNonNull(VulkanAccelerationStructures.this.scratch);
         scratchBuffer.markUsed();
         try (MemoryStack stack = MemoryStack.stackPush()) {
            var info = this.instances == null ? triangles(stack, this.geometry, this.flags)
               : instances(stack, this.instances, this.flags);
            info.get(0).dstAccelerationStructure(this.target.handle())
               .mode(this.update ? KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR
                  : KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
               .srcAccelerationStructure(this.update ? (updateSource==null?this.target.handle():updateSource.handle()) : VK12.VK_NULL_HANDLE)
               .scratchData(address -> address.deviceAddress(Math.addExact(
                  alignUp(scratchBuffer.view().address(), VulkanAccelerationStructures.this.scratchAlignment), this.scratchOffset)));
            var ranges = VkAccelerationStructureBuildRangeInfoKHR.calloc(this.primitiveCounts.length, stack);
            for (int i = 0; i < this.primitiveCounts.length; i++) ranges.get(i).primitiveCount(this.primitiveCounts[i]);
            KHRAccelerationStructure.vkCmdBuildAccelerationStructuresKHR(command, info, stack.pointers(ranges));
         }
         if(bottomLevel!=null) { bottomLevel.recordedBuild=VulkanAccelerationStructures.this.resources.recording(); }
      }
   }


   public CompactionQuery compactionQuery(List<BottomLevel> sources) { return new CompactionQuery(sources); }
   public final class CompactionQuery implements AutoCloseable {
      private List<BottomLevel> sources;
      private final VulkanResources.QueryPool pool;
      private final int count;
      private final long recording=resources.recording();
      private long submitted;
      private boolean recorded,closed;
      private @Nullable List<CompactionSize> results;
      private CompactionQuery(List<BottomLevel> sources) {
         this.sources=List.copyOf(sources);this.count=this.sources.size();
         if(count==0)throw new IllegalArgumentException("Empty compaction query");
         for(var source:this.sources)if(source.closed() || source.structure.deviceIdentity()!=resources.deviceIdentity() || !source.compactable())
            throw new IllegalArgumentException("Invalid compaction source");
         pool=resources.allocateCompactedSizeQueries(count);
      }

      public void record(VkCommandBuffer command) {
         if(closed || recorded || command.getDevice()!=resources.device() || recording!=resources.recording())throw new IllegalStateException("Invalid compaction query recording");
         for(var source:sources)if(!source.readyForCompactionQuery())
            throw new IllegalStateException("Compaction source has no completed build publication");
         pool.acquire(command);
         try(var stack=MemoryStack.stackPush()) {
            var handles=stack.mallocLong(count);for(var source:sources) { source.markUsed();handles.put(source.handle()); }handles.flip();
            KHRAccelerationStructure.vkCmdWriteAccelerationStructuresPropertiesKHR(command,handles,
               KHRAccelerationStructure.VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR,pool.handle(),0);
         }
         recorded=true;
      }
      public void submitted(long serial) {
         if(closed || !recorded || submitted!=0 || serial!=recording)throw new IllegalStateException("Compaction query lacks exact submission");
         submitted=serial;
      }

      public @Nullable CompactionSize result(int index) {
         if(closed || index<0 || index>=count)throw new IllegalStateException("Invalid compaction query result");
         if(submitted==0 || resources.completed()<submitted)return null;
         if(results==null)try(var stack=MemoryStack.stackPush()) {
            var values=stack.callocLong(Math.multiplyExact(count,2));
            int status=VK12.vkGetQueryPoolResults(resources.device(),pool.handle(),0,count,values,16,
               VK12.VK_QUERY_RESULT_64_BIT|VK12.VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
            if(status==VK12.VK_NOT_READY)return null;
            VulkanObjects.requireSuccess(status,"Read compacted AS sizes");
            var resolved=new java.util.ArrayList<CompactionSize>(count);
            for(int i=0;i<count;i++) {
               if(values.get(i*2+1)==0)return null;
               long bytes=values.get(i*2);if(bytes<=0)throw new IllegalStateException("Invalid compacted AS size");
               resolved.add(new CompactionSize(sources.get(i),bytes));
            }
            results=List.copyOf(resolved);sources=List.of();
         }
         return results.get(index);
      }
      @Override public void close() { if(!closed) { closed=true;sources=List.of();pool.close(); } }
   }


   public final class CompactionSize {
      private final BottomLevel source;
      private final long bytes,sourceSubmission,sourceBytes;
      private CompactionSize(BottomLevel source,long bytes) { this.source=source;this.bytes=bytes;this.sourceSubmission=source.submittedBuild;this.sourceBytes=source.size(); }
      public long bytes() { return bytes; }
      public long sourceBytes() { return sourceBytes; }
   }

   public CompactCopy compact(CompactionSize size) {
      BottomLevel source=size.source;long bytes=size.bytes;
      if(source.closed() || !source.compactable() || source.structure.deviceIdentity()!=resources.deviceIdentity()
         || source.submittedBuild==0 || source.submittedBuild!=size.sourceSubmission || source.submittedBuild>resources.completed() || bytes<=0 || bytes>=source.size())
         throw new IllegalArgumentException("Compaction requires a completed immutable source and a smaller queried size");
      var destination=new BottomLevel(resources.allocateAccelerationStructure(bytes,false),source.topology,source.flags,0,0,true);
      return new CompactCopy(source,destination);
   }
   public final class CompactCopy {
      private final BottomLevel source,target;
      private final long recording=resources.recording();
      private boolean recorded,submitted;
      private CompactCopy(BottomLevel source,BottomLevel target) { this.source=source;this.target=target; }
      public BottomLevel target() { return target; }
      public void record(VkCommandBuffer command) {
         if(recorded || source.closed() || target.closed() || recording!=resources.recording() || command.getDevice()!=resources.device())
            throw new IllegalStateException("Invalid compact-copy recording");
         source.markUsed();target.markUsed();
         try(var stack=MemoryStack.stackPush()) {
            var info=VkCopyAccelerationStructureInfoKHR.calloc(stack).sType$Default().src(source.handle()).dst(target.handle())
               .mode(KHRAccelerationStructure.VK_COPY_ACCELERATION_STRUCTURE_MODE_COMPACT_KHR);
            KHRAccelerationStructure.vkCmdCopyAccelerationStructureKHR(command,info);
         }
         target.recordedBuild=recording;recorded=true;
      }
      public void submitted(long serial) {
         if(submitted || !recorded || serial!=recording)throw new IllegalStateException("Compact copy lacks exact submission");
         target.submittedBuild=serial;submitted=true;
      }
   }

   private void requireTriangles(final List<TriangleGeometry> geometry) {
      if (geometry.isEmpty() || Long.compareUnsigned(this.maxGeometries, geometry.size()) < 0) {
         throw new IllegalArgumentException("BLAS geometry count exceeds device limit");
      }
      long primitives = 0L;
      for (TriangleGeometry fact : geometry) {
         requireInput(fact.positions(), 4L);
         requireInput(fact.indices(), fact.indexBytes());
         primitives = Math.addExact(primitives, fact.primitiveCount());
      }
      if (Long.compareUnsigned(this.maxPrimitives, primitives) < 0) throw new IllegalArgumentException("BLAS primitive count exceeds device limit");
   }

   private void requireInput(final ResourceViews.Buffer view, final long alignment) {
      int usage = VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
         | KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
      if (view.device() != this.resources.deviceIdentity() || view.address() == 0L || view.address() % alignment != 0
         || (view.usage() & usage) != usage) throw new IllegalArgumentException("Invalid addressable AS input");
   }

   public static int triangleFlags(final boolean opaque) {
      return opaque ? KHRAccelerationStructure.VK_GEOMETRY_OPAQUE_BIT_KHR
         : KHRAccelerationStructure.VK_GEOMETRY_NO_DUPLICATE_ANY_HIT_INVOCATION_BIT_KHR;
   }

   private static int[] counts(final List<TriangleGeometry> geometry) {
      int[] result = new int[geometry.size()];
      for (int i = 0; i < result.length; i++) result[i] = geometry.get(i).primitiveCount();
      return result;
   }

   private VkAccelerationStructureBuildSizesInfoKHR sizes(final MemoryStack stack,
      final VkAccelerationStructureBuildGeometryInfoKHR.Buffer build, final int[] counts) {
      var result = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
      KHRAccelerationStructure.vkGetAccelerationStructureBuildSizesKHR(this.resources.device(),
         KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, build.get(0), stack.ints(counts), result);
      return result;
   }

   private static VkAccelerationStructureBuildGeometryInfoKHR.Buffer triangles(final MemoryStack stack,
      final List<TriangleGeometry> facts, final int flags) {
      var geometry = VkAccelerationStructureGeometryKHR.calloc(facts.size(), stack);
      for (int i = 0; i < facts.size(); i++) {
         TriangleGeometry fact = facts.get(i);
         var item = geometry.get(i).sType$Default().geometryType(KHRAccelerationStructure.VK_GEOMETRY_TYPE_TRIANGLES_KHR)
            .flags(triangleFlags(fact.opaque()));
         item.geometry().triangles().sType$Default().vertexFormat(VK12.VK_FORMAT_R32G32B32_SFLOAT)
            .vertexData(address -> address.deviceAddress(fact.positions().address())).vertexStride(fact.vertexStride())
            .maxVertex(fact.vertexCount() - 1).indexType(fact.indexBytes() == 4 ? VK12.VK_INDEX_TYPE_UINT32 : VK12.VK_INDEX_TYPE_UINT16)
            .indexData(address -> address.deviceAddress(fact.indices().address()));
      }
      return buildInfo(stack, geometry, KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR, flags);
   }

   private static VkAccelerationStructureBuildGeometryInfoKHR.Buffer instances(final MemoryStack stack,
      final ResourceViews.Buffer input, final int flags) {
      var geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack);
      geometry.get(0).sType$Default().geometryType(KHRAccelerationStructure.VK_GEOMETRY_TYPE_INSTANCES_KHR)
         .geometry().instances().sType$Default().arrayOfPointers(false).data(address -> address.deviceAddress(input.address()));
      return buildInfo(stack, geometry, KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR, flags);
   }

   private static VkAccelerationStructureBuildGeometryInfoKHR.Buffer buildInfo(final MemoryStack stack,
      final VkAccelerationStructureGeometryKHR.Buffer geometry, final int type, final int flags) {
      var result = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
      result.get(0).sType$Default().type(type).flags(flags).mode(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
         .geometryCount(geometry.remaining()).pGeometries(geometry);
      return result;
   }


   private long claimScratch(final long bytes) {
      long offset = this.cursor;
      this.cursor = Math.addExact(this.cursor, alignUp(bytes, this.scratchAlignment));
      long required = Math.addExact(this.cursor, this.scratchAlignment);
      if (this.scratch == null || required > this.scratch.view().length()) {
         var replacement = this.resources.allocateDevice(Math.max(Math.multiplyExact(required, 2L), 1L << 20),
            VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT);
         if (this.scratch != null) this.scratch.close();
         this.scratch = replacement;
      }
      return offset;
   }

   private static long alignUp(final long value, final long alignment) {
      if (value < 0L || alignment <= 0L || (alignment & (alignment - 1L)) != 0L) throw new IllegalArgumentException("Invalid AS alignment");
      return Math.addExact(value, alignment - 1L) & -alignment;
   }

   @Override public void close() {
      this.batch = Math.incrementExact(this.batch);
      if (this.scratch != null) this.scratch.close();
      this.scratch = null;
      this.cursor = 0L;
   }
}
