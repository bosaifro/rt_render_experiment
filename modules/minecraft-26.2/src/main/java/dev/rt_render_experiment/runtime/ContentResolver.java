package dev.rt_render_experiment.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;







final class ContentResolver {
   record Statistics(long resolutions, long fallbackResolutions) {
      Statistics {
         if (resolutions < 0L || fallbackResolutions < 0L || fallbackResolutions > resolutions) {
            throw new IllegalArgumentException("invalid content-resolution statistics");
         }
      }
   }

   private final MaterialManifest manifest;
   private final LongAdder fallbackResolutions = new LongAdder();
   private final LongAdder resolutions = new LongAdder();

   ContentResolver(final MaterialManifest manifest) {
      this.manifest = Objects.requireNonNull(manifest, "manifest");
   }

   MaterialManifest manifest() {
      return this.manifest;
   }

   int blockSurfaceMaterialId(final String blockName, final String spriteRef) {
      Objects.requireNonNull(blockName, "blockName");
      Objects.requireNonNull(spriteRef, "spriteRef");
      this.resolutions.increment();
      Integer sprite = this.manifest.spriteRule(spriteRef);
      if (sprite != null) {
         return sprite;
      }
      Integer block = this.manifest.blockRule(blockName);
      if (block != null) {
         return block;
      }
      this.fallbackResolutions.increment();
      return this.manifest.fallbackMaterialId();
   }


   int blockMaterialId(final String blockName) {
      Objects.requireNonNull(blockName, "blockName");
      this.resolutions.increment();
      Integer block = this.manifest.blockRule(blockName);
      if (block != null) {
         return block;
      }
      this.fallbackResolutions.increment();
      return this.manifest.fallbackMaterialId();
   }

   int fluidMaterialId(final String fluidName) {
      Objects.requireNonNull(fluidName, "fluidName");
      this.resolutions.increment();
      Integer fluid = this.manifest.fluidRule(fluidName);
      if (fluid != null) {
         return fluid;
      }
      this.fallbackResolutions.increment();
      return this.manifest.fallbackMaterialId();
   }

   Statistics statistics() {
      return new Statistics(this.resolutions.sum(), this.fallbackResolutions.sum());
   }


   Statistics statisticsThenReset() {
      return new Statistics(this.resolutions.sumThenReset(), this.fallbackResolutions.sumThenReset());
   }
}
