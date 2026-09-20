package dev.rt_render_experiment.vulkan;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import dev.rt_render_experiment.contract.ResourceViews;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VK12;


public final class VulkanDiagnostics implements AutoCloseable {
   private final VulkanResources resources;
   private final VulkanResources.Buffer counters;
   private final List<Slot> slots = new ArrayList<>();
   private final int bytes;
   private final int queryCount;
   private boolean timestamps;
   private @Nullable String timestampFailure;
   private int cursor;
   private boolean closed;

   public VulkanDiagnostics(final VulkanResources resources, final int bytes, final int initialSlots, final int queries) {
      if (bytes <= 0 || initialSlots < 0 || queries < 0) throw new IllegalArgumentException("Invalid diagnostic extent");
      this.resources = resources;
      this.bytes = bytes;
      this.queryCount = queries;
      this.timestamps = queries > 0;
      this.counters = resources.allocateDevice(bytes, VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
         | VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT);
      try {
         for (int i = 0; i < initialSlots; i++) this.slots.add(allocateSlot());
      } catch (RuntimeException | Error failure) {
         VulkanRetirement.suppress(failure, this::close);
         throw failure;
      }
   }

   private Slot allocateSlot() {
      var readback = this.resources.allocateReadback(this.bytes);
      VulkanResources.QueryPool queries = null;
      try {
         if (this.timestamps) queries = this.resources.allocateTimestampQueries(this.queryCount);
      } catch (IllegalStateException failure) {

         this.timestamps = false;
         this.timestampFailure = "query-pool-create-failed";
         org.slf4j.LoggerFactory.getLogger(VulkanDiagnostics.class).warn("Timestamp allocation failed; native counters remain available", failure);
         for (Slot slot : this.slots) if (slot.queries != null) slot.queries.close();
      } catch (RuntimeException | Error failure) {
         readback.close();
         throw failure;
      }
      return new Slot(this.slots.size(), readback, queries);
   }

   public ResourceViews.Buffer counters() {
      if (this.closed) throw new IllegalStateException("Diagnostics are closed");
      this.counters.markUsed();
      return this.counters.view();
   }
   public boolean timestampsAvailable() { return this.timestamps; }
   public @Nullable String timestampFailure() { return this.timestampFailure; }
   public long bufferBytes() { return Math.multiplyExact((long)this.bytes, this.slots.size() + 1L); }

   public Slot acquire() {
      if (this.closed) throw new IllegalStateException("Diagnostics are closed");
      for (int examined = 0; examined < this.slots.size(); examined++) {
         int index = (this.cursor + examined) % this.slots.size();
         Slot slot = this.slots.get(index);
         if (!slot.claimed && slot.completed()) {
            this.cursor = (index + 1) % this.slots.size();
            slot.acquire();
            return slot;
         }
      }
      Slot slot = allocateSlot();
      this.slots.add(slot);
      slot.acquire();
      return slot;
   }

   public final class Slot {
      private final int index;
      private final VulkanResources.Buffer readback;
      private final VulkanResources.@Nullable QueryPool queries;
      private boolean claimed;
      private Slot(final int index, final VulkanResources.Buffer readback, final VulkanResources.@Nullable QueryPool queries) {
         this.index = index; this.readback = readback; this.queries = queries;
      }
      private void acquire() {
         if (VulkanDiagnostics.this.timestamps && this.queries != null) this.queries.acquire();
         this.readback.markUsed();
         this.claimed = true;
      }
      public int index() { return this.index; }
      public long queryPool() { return VulkanDiagnostics.this.timestamps && this.queries != null ? this.queries.handle() : 0L; }
      public boolean completed() { return this.readback.lastUse() <= VulkanDiagnostics.this.resources.completed(); }
      public ResourceViews.Buffer readbackTarget() { return this.readback.view(); }
      public VulkanResources.Buffer.Mapping readback() {
         if (!completed()) throw new IllegalStateException("Diagnostic readback has not completed");
         return this.readback.map(0L, this.readback.view().length());
      }
      public int timestamps(final LongBuffer values) {
         if (!completed() || queryPool() == 0L) throw new IllegalStateException("Timestamp results are unavailable");
         return VK12.vkGetQueryPoolResults(VulkanDiagnostics.this.resources.device(), queryPool(), 0,
            VulkanDiagnostics.this.queryCount, values, 2L * Long.BYTES,
            VK12.VK_QUERY_RESULT_64_BIT | VK12.VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
      }

      public void release() { this.claimed = false; }
   }

   @Override public void close() {
      if (this.closed) return;
      this.closed = true;
      Throwable failure = VulkanRetirement.attempt(null, this.counters::close);
      for (Slot slot : this.slots) {
         if (slot.queries != null) failure = VulkanRetirement.attempt(failure, slot.queries::close);
         failure = VulkanRetirement.attempt(failure, slot.readback::close);
      }
      VulkanRetirement.finish(failure);
   }
}
