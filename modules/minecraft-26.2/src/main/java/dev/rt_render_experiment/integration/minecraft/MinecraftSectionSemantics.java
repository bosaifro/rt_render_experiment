package dev.rt_render_experiment.integration.minecraft;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.resources.Identifier;


public final class MinecraftSectionSemantics {
   private static final ChunkSectionLayer[] LAYERS = ChunkSectionLayer.values();
   public static final Metadata EMPTY = new Metadata(List.of(), Map.of(), Map.of(), List.of());

   private MinecraftSectionSemantics() {}

   public sealed interface Material permits BlockMaterial, FluidMaterial {
   }

   public record Primitive(int blockStateId, Material material) {
      public Primitive {
         if (blockStateId < 0) throw new IllegalArgumentException("blockStateId must be non-negative: " + blockStateId);
         Objects.requireNonNull(material, "material");
      }
   }

   public record BlockMaterial(Identifier sprite, int flags, boolean tinted, boolean shade, int lightEmission)
      implements Material {
      public BlockMaterial {
         validate(sprite, flags, lightEmission);
      }

      static void validate(final Identifier sprite, final int flags, final int lightEmission) {
         Objects.requireNonNull(sprite, "sprite");
         if ((flags & ~0x3) != 0) throw new IllegalArgumentException("unknown block material flags: " + flags);
         if (lightEmission < 0 || lightEmission > 15) {
            throw new IllegalArgumentException("block material emission must be in [0,15]: " + lightEmission);
         }
      }
   }

   public record FluidMaterial(int fluidStateId) implements Material {
      public FluidMaterial {
         if (fluidStateId < 0) throw new IllegalArgumentException("fluidStateId must be non-negative: " + fluidStateId);
      }
   }


   public record Light(int packedPositionEmission, int blockStateId) {
      public Light {
         if ((packedPositionEmission & ~0xFFFF) != 0 || ((packedPositionEmission >>> 12) & 0xF) == 0) {
            throw new IllegalArgumentException("invalid packed local light: " + packedPositionEmission);
         }
         if (blockStateId < 0) throw new IllegalArgumentException("light blockStateId must be non-negative: " + blockStateId);
      }

      static Light of(final int localX, final int localY, final int localZ, final int emission, final int blockStateId) {
         if (localX < 0 || localY < 0 || localZ < 0 || emission < 1
            || localX > 15 || localY > 15 || localZ > 15 || emission > 15) {
            throw new IllegalArgumentException(
               "local light components must be in [0,15]: " + localX + "," + localY + "," + localZ + "," + emission
            );
         }
         return new Light(localX | localY << 4 | localZ << 8 | emission << 12, blockStateId);
      }
   }


   public static final class Metadata {
      private final List<Primitive> semanticEntries;
      private final int[][] primitiveSemanticIndices = new int[LAYERS.length][];
      private final int[][] primitiveAlbedoTints = new int[LAYERS.length][];
      private final List<Light> lightSources;


      Metadata(
         final List<Primitive> semanticEntries,
         final Map<ChunkSectionLayer, int[]> primitiveSemanticIndices,
         final Map<ChunkSectionLayer, int[]> primitiveAlbedoTints,
         final List<Light> lightSources
      ) {
         this.semanticEntries = List.copyOf(semanticEntries);
         this.lightSources = List.copyOf(lightSources);
         Objects.requireNonNull(primitiveSemanticIndices, "primitiveSemanticIndices");
         Objects.requireNonNull(primitiveAlbedoTints, "primitiveAlbedoTints");
         int populatedLayers = 0;
         for (ChunkSectionLayer layer : LAYERS) {
            int[] indices = primitiveSemanticIndices.get(layer);
            int[] tints = primitiveAlbedoTints.get(layer);
            if (indices == null && tints == null) continue;
            if (indices == null || tints == null || indices.length != tints.length) {
               throw new IllegalArgumentException("Incomplete semantic/tint pair for section layer " + layer);
            }
            for (int index : indices) {
               if (index < 0 || index >= this.semanticEntries.size()) {
                  throw new IllegalArgumentException("Semantic index out of range for " + layer + ": " + index);
               }
            }
            this.primitiveSemanticIndices[layer.ordinal()] = indices;
            this.primitiveAlbedoTints[layer.ordinal()] = tints;
            populatedLayers++;
         }
         if (primitiveSemanticIndices.size() != populatedLayers || primitiveAlbedoTints.size() != populatedLayers) {
            throw new IllegalArgumentException("Semantic metadata contains an unknown or null section layer");
         }
      }

      public List<Primitive> semanticEntries() {
         return this.semanticEntries;
      }

      public List<Light> lightSources() {
         return this.lightSources;
      }

      public int primitiveCount(final ChunkSectionLayer layer) {
         int[] indices = this.primitiveSemanticIndices[Objects.requireNonNull(layer, "layer").ordinal()];
         return indices == null ? 0 : indices.length;
      }


      public int semanticIndex(final ChunkSectionLayer layer, final int primitiveIndex) {
         int[] indices = this.primitiveSemanticIndices[Objects.requireNonNull(layer, "layer").ordinal()];
         if (indices == null) throw new IndexOutOfBoundsException("No primitives for section layer " + layer);
         return indices[primitiveIndex];
      }

      public Primitive semantic(final ChunkSectionLayer layer, final int primitiveIndex) {
         return this.semanticEntries.get(this.semanticIndex(layer, primitiveIndex));
      }


      public int albedoTint(final ChunkSectionLayer layer, final int primitiveIndex) {
         int[] tints = this.primitiveAlbedoTints[Objects.requireNonNull(layer, "layer").ordinal()];
         if (tints == null) throw new IndexOutOfBoundsException("No primitive tints for section layer " + layer);
         return tints[primitiveIndex];
      }
   }
}
