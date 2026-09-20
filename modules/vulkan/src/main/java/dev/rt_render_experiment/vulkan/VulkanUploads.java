package dev.rt_render_experiment.vulkan;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import dev.rt_render_experiment.contract.ResourceViews;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;


public final class VulkanUploads implements AutoCloseable {
   private static final int PAGE_BYTES = 256 * 1024;
   private final VulkanResources resources;
   private final List<Page> pages = new ArrayList<>();
   private int cursor;
   private boolean closed;
   private long stagedBytes;
   private final java.util.Map<ResourceViews.ResourceId, long[]> writeRanges = new java.util.HashMap<>();

   private static final class Page {
      final VulkanResources.Buffer buffer;
      long recording;
      int used;
      Page(final VulkanResources.Buffer buffer) { this.buffer = buffer; }
   }

   public VulkanUploads(final VulkanResources resources) {
      this.resources = Objects.requireNonNull(resources, "resources");
   }

   public void beginFrame() { this.stagedBytes = 0L; this.cursor = 0; }
   public Ready ready() { return new Ready(); }


   public final class Ready {
      private final long recording = VulkanUploads.this.resources.recording();
      private final List<Transfer> pending = new ArrayList<>();
      private int recorded = -1;
      public boolean isEmpty() { return this.pending.isEmpty(); }
      public void add(final Transfer transfer) {
         if (transfer.owner() != VulkanUploads.this || transfer.recording != this.recording) {
            throw new IllegalArgumentException("Foreign source-readiness transfer");
         }
         this.pending.add(transfer); this.recorded = -1;
      }
      public void record(final VkCommandBuffer command) {
         VulkanUploads.this.recordBatch(command, this.pending);
         this.recorded = this.pending.size();
      }
      public void accept() {
         if (this.recorded != this.pending.size()) throw new IllegalStateException("Source writes were not recorded");
         this.pending.clear(); this.recorded = -1;
      }
   }
   public long stagedBytes() { return this.stagedBytes; }
   public long residentBytes() {
      long bytes = 0L;
      for (Page page : this.pages) bytes = Math.addExact(bytes, page.buffer.view().length());
      return bytes;
   }


   public @Nullable Transfer stageChanged(final VulkanResources.Buffer destination,
      final ByteBuffer current, final @Nullable ByteBuffer previous, final int elementBytes) {
      if (elementBytes < 4 || (elementBytes & (elementBytes - 1)) != 0 || current.remaining() % elementBytes != 0) {
         throw new IllegalArgumentException("Invalid upload element size");
      }
      int offset = 0;
      int limit = current.remaining();
      if (previous != null && previous.remaining() == limit) {
         int first = current.mismatch(previous);
         if (first < 0) return null;
         offset = first & -elementBytes;
         int last = limit - 1;
         while (last >= first && current.get(current.position() + last) == previous.get(previous.position() + last)) last--;
         limit = (last + elementBytes) & -elementBytes;
      }
      return stage(destination, offset, current.slice(current.position() + offset, limit - offset));
   }

   public Transfer stage(final VulkanResources.Buffer destination, final long offset, final ByteBuffer source) {
      if (this.closed) throw new IllegalStateException("RtRenderExperiment upload owner is closed");
      ResourceViews.Buffer target = destination.view().slice(offset, source.remaining());
      if (destination.closed() || target.device() != this.resources.deviceIdentity()
         || (target.usage() & VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT) == 0
         || (offset & 3L) != 0L || (source.remaining() & 3) != 0) {
         throw new IllegalArgumentException("Invalid native upload destination");
      }
      int bytes = source.remaining();
      long recording = this.resources.recording();
      Page selected = null;
      while (this.cursor < this.pages.size()) {
         Page page = this.pages.get(this.cursor);
         if (page.recording != recording && page.buffer.lastUse() <= this.resources.completed()) {
            page.recording = recording;
            page.used = 0;
         }
         if (page.recording == recording && bytes <= page.buffer.view().length() - page.used) {
            selected = page;
            break;
         }
         this.cursor++;
      }
      if (selected == null) {
         selected = new Page(this.resources.allocateMapped(Math.max(bytes, PAGE_BYTES), VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT));
         selected.recording = recording;
         this.pages.add(selected);
      }
      int start = selected.used;
      try (var mapping = selected.buffer.map(start, bytes)) { mapping.data().put(source.duplicate()); }
      selected.used = Math.addExact(start, bytes);


      selected.buffer.markUsed();


      destination.markUsed();
      this.stagedBytes = Math.addExact(this.stagedBytes, bytes);
      return new Transfer(selected.buffer.view().slice(start, bytes), destination, target, recording);
   }


   public void recordBatch(final VkCommandBuffer command, final List<Transfer> transfers) {
      if (transfers.isEmpty()) return;
      this.writeRanges.clear();
      try (MemoryStack stack = MemoryStack.stackPush()) {
         VulkanBarriers.record(command, stack, VulkanBarriers.PRIOR_ACCESS_TO_UPLOAD);
         for (Transfer transfer : transfers) {
            long start = transfer.target.offset();
            long end = Math.addExact(start, transfer.target.length());
            long[] previous = this.writeRanges.get(transfer.target.id());
            if (previous != null && start < previous[1] && previous[0] < end) {
               VulkanBarriers.record(command, stack, VulkanBarriers.UPLOAD_TO_UPLOAD);
               this.writeRanges.clear();
               previous = null;
            }
            if (previous == null) this.writeRanges.put(transfer.target.id(), new long[] {start, end});
            else { previous[0] = Math.min(previous[0], start); previous[1] = Math.max(previous[1], end); }
            transfer.record(command);
         }
         VulkanBarriers.record(command, stack, VulkanBarriers.UPLOAD_TO_SHADERS);
      } finally {
         this.writeRanges.clear();
      }
   }

   public final class Transfer {
      private final ResourceViews.Buffer source;
      private final VulkanResources.Buffer destination;
      private final ResourceViews.Buffer target;
      private final long recording;

      private Transfer(final ResourceViews.Buffer source, final VulkanResources.Buffer destination,
         final ResourceViews.Buffer target, final long recording) {
         this.source = source;
         this.destination = destination;
         this.target = target;
         this.recording = recording;
      }

      public long bytes() { return this.source.length(); }
      private VulkanUploads owner() { return VulkanUploads.this; }
      public void record(final VkCommandBuffer command) {
         if (VulkanUploads.this.closed || command.getDevice() != VulkanUploads.this.resources.device()
            || this.recording != VulkanUploads.this.resources.recording()) throw new IllegalStateException("Expired native upload");
         if (this.destination.destroyed()) throw new IllegalStateException("Reserved upload allocation was destroyed");
         try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCopy.Buffer copy = VkBufferCopy.calloc(1, stack).srcOffset(this.source.offset())
               .dstOffset(this.target.offset()).size(this.source.length());
            VK12.vkCmdCopyBuffer(command, this.source.handle(), this.target.handle(), copy);
         }
      }
   }

   @Override public void close() {
      if (this.closed) return;
      Throwable failure = null;
      for (Page page : this.pages) failure = VulkanRetirement.attempt(failure, page.buffer::close);
      this.pages.clear();
      this.closed = true;
      VulkanRetirement.finish(failure);
   }
}
