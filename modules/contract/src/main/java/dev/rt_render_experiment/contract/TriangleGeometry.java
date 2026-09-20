package dev.rt_render_experiment.contract;

import java.util.Objects;


public record TriangleGeometry(
   ResourceViews.Buffer positions, int vertexStride, int vertexCount,
   ResourceViews.Buffer indices, int indexBytes, int primitiveCount, long indexContentIdentity, boolean opaque
) {
   public TriangleGeometry {
      Objects.requireNonNull(positions, "positions");
      Objects.requireNonNull(indices, "indices");
      if (positions.device() != indices.device() || vertexStride < 12 || vertexStride % 4 != 0 || vertexCount <= 0
         || (indexBytes != 2 && indexBytes != 4) || primitiveCount <= 0 || indexContentIdentity <= 0L
         || Math.addExact(Math.multiplyExact((long)vertexCount - 1L, vertexStride), 12L) > positions.length()
         || Math.multiplyExact(Math.multiplyExact((long)primitiveCount, 3L), indexBytes) > indices.length()) {
         throw new IllegalArgumentException("Invalid bounded triangle geometry");
      }
   }


   public boolean canRefit(final TriangleGeometry previous) {
      return this.vertexStride == previous.vertexStride && this.vertexCount == previous.vertexCount
         && this.indexBytes == previous.indexBytes && this.primitiveCount == previous.primitiveCount
         && this.indexContentIdentity == previous.indexContentIdentity && this.opaque == previous.opaque;
   }
}
