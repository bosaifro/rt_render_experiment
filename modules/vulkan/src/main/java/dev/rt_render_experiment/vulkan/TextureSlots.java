package dev.rt_render_experiment.vulkan;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import dev.rt_render_experiment.contract.ResourceViews;


public final class TextureSlots {
   private record Key(long device, ResourceViews.ResourceId resource, long allocationGeneration, long sampler) {
      static Key of(final ResourceViews.SampledImage image) {
         return new Key(image.view().device(), image.view().id(), image.view().allocationGeneration(), image.sampler());
      }
   }
   private final int capacity;
   private Map<Key, Integer> committed = Map.of();
   private List<ResourceViews.SampledImage> published = List.of();
   private long revision;

   public TextureSlots(final int capacity) {
      if (capacity <= 0) throw new IllegalArgumentException("Invalid texture-slot capacity");
      this.capacity = capacity;
   }

   public Plan prepare(final List<ResourceViews.SampledImage> needed) {
      Map<Key, Integer> next = new HashMap<>();
      BitSet occupied = new BitSet();
      for (var image : needed) {
         Key key = Key.of(image);
         Integer previous = this.committed.get(key);
         if (previous != null) {
            if (image.view().contentEpoch() < this.published.get(previous).view().contentEpoch()) {
               throw new IllegalArgumentException("Texture content epoch regressed");
            }
            next.put(key, previous); occupied.set(previous);
         }
      }
      int[] indices = new int[needed.size()];
      int firstFree = 0;
      for (int i = 0; i < needed.size(); i++) {
         Key key = Key.of(needed.get(i));
         Integer slot = next.get(key);
         if (slot == null) {
            firstFree = occupied.nextClearBit(firstFree);
            if (firstFree >= this.capacity) throw new IllegalArgumentException("Texture demand exceeds device capacity");
            slot = firstFree; next.put(key, slot); occupied.set(slot);
         }
         indices[i] = slot;
      }


      int extent = occupied.length();
      if ((long)extent > 2L * Math.max(16, next.size())) {
         next.clear(); extent = 0;
         for (int i = 0; i < needed.size(); i++) {
            Key key = Key.of(needed.get(i));
            Integer slot = next.get(key);
            if (slot == null) { slot = extent++; next.put(key, slot); }
            indices[i] = slot;
         }
      }
      ArrayList<ResourceViews.SampledImage> entries = new ArrayList<>(Collections.nCopies(extent, null));
      for (int i = 0; i < needed.size(); i++) entries.set(indices[i], needed.get(i));
      return new Plan(this.revision, Map.copyOf(next), Collections.unmodifiableList(entries), indices);
   }

   public final class Plan {
      private final long baseRevision;
      private final Map<Key, Integer> slots;
      private final List<ResourceViews.SampledImage> entries;
      private final int[] indices;
      private boolean published;
      private Plan(final long baseRevision, final Map<Key, Integer> slots,
         final List<ResourceViews.SampledImage> entries, final int[] indices) {
         this.baseRevision = baseRevision; this.slots = slots; this.entries = entries; this.indices = indices;
      }
      public int extent() { return this.entries.size(); }

      public List<ResourceViews.SampledImage> entries() { return this.entries; }
      public int remap(final int inputSlot) {
         if (inputSlot == -1) return -1;
         if (inputSlot < 0 || inputSlot >= this.indices.length) throw new IllegalArgumentException("Texture slot escapes the input plan");
         return this.indices[inputSlot];
      }
      public void commit() {
         if (this.published || this.baseRevision != TextureSlots.this.revision) throw new IllegalStateException("Stale texture-slot publication");
         TextureSlots.this.revision = Math.incrementExact(TextureSlots.this.revision);
         TextureSlots.this.committed = this.slots;
         TextureSlots.this.published = this.entries;
         this.published = true;
      }
   }

   public void reset() { this.revision = Math.incrementExact(this.revision); this.committed = Map.of(); this.published = List.of(); }
}
