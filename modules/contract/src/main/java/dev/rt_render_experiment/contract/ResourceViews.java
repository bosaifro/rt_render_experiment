package dev.rt_render_experiment.contract;


public final class ResourceViews {
   private ResourceViews() {}


   public record ResourceId(long value) {
      public ResourceId {
         if (value <= 0L) throw new IllegalArgumentException("Resource ID must be positive");
      }
   }


   public record Buffer(
      ResourceId id, long device, long handle, long allocationGeneration,
      long allocationBytes, long offset, long length, long address, int usage
   ) {
      public Buffer {
         java.util.Objects.requireNonNull(id, "id");
         if (device == 0L || handle == 0L || allocationGeneration <= 0L || allocationBytes <= 0L
            || offset < 0L || length <= 0L || offset > allocationBytes || length > allocationBytes - offset
            || usage == 0) {
            throw new IllegalArgumentException("Invalid Vulkan buffer resource range");
         }
      }

      public Buffer slice(final long relativeOffset, final long bytes) {
         if (relativeOffset < 0L || bytes <= 0L || relativeOffset > this.length || bytes > this.length - relativeOffset) {
            throw new IllegalArgumentException("Slice escapes the registered buffer range");
         }
         return new Buffer(this.id, this.device, this.handle, this.allocationGeneration, this.allocationBytes,
            Math.addExact(this.offset, relativeOffset), bytes,
            this.address == 0L ? 0L : Math.addExact(this.address, relativeOffset), this.usage);
      }
   }


   public record Image(
      ResourceId id, long device, long image, long view, long allocationGeneration, long contentEpoch,
      int format, int width, int height, int baseMip, int mipCount, int baseLayer, int layerCount,
      int aspectMask, int usage, int layout
   ) {
      public Image {
         java.util.Objects.requireNonNull(id, "id");
         if (device == 0L || image == 0L || view == 0L || allocationGeneration <= 0L || contentEpoch <= 0L
            || format <= 0 || width <= 0 || height <= 0 || baseMip < 0 || mipCount <= 0
            || baseLayer < 0 || layerCount <= 0 || aspectMask == 0 || usage == 0 || layout <= 0) {
            throw new IllegalArgumentException("Invalid registered Vulkan image view");
         }
         Math.addExact(baseMip, mipCount);
         Math.addExact(baseLayer, layerCount);
      }
      public Image withContentEpoch(final long epoch) {
         if (epoch < this.contentEpoch) throw new IllegalArgumentException("Image content epoch regressed");
         return epoch == this.contentEpoch ? this : new Image(this.id, this.device, this.image, this.view,
            this.allocationGeneration, epoch, this.format, this.width, this.height, this.baseMip, this.mipCount,
            this.baseLayer, this.layerCount, this.aspectMask, this.usage, this.layout);
      }
   }

   public record SampledImage(Image view, long sampler) {
      public SampledImage {
         java.util.Objects.requireNonNull(view, "view");
         if (sampler == 0L) throw new IllegalArgumentException("Null Vulkan sampler");
      }
   }





   public record Submission(long device, long recording, long submitted, long completed) {
      public Submission {
         if (device == 0L || completed < 0L || submitted < completed || recording <= submitted) {
            throw new IllegalArgumentException("Invalid observed Vulkan submission progress");
         }
      }
   }
}
