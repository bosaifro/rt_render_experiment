package dev.rt_render_experiment.integration.minecraft;

import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;


public final class MinecraftSectionSemanticCollector {
   private static final int INITIAL_LAYER_QUADS = 512;
   private static final long BLOCK_FINGERPRINT = 0x6A09E667F3BCC909L;
   private static final long FLUID_FINGERPRINT = 0xBB67AE8584CAA73BL;
   private final List<MinecraftSectionSemantics.Primitive> semanticEntries = new ArrayList<>();
   private final Long2IntOpenHashMap semanticIndices = new Long2IntOpenHashMap();
   private final Map<ChunkSectionLayer, IntArrayList> primitiveSemanticIndices = new EnumMap<>(ChunkSectionLayer.class);
   private final Map<ChunkSectionLayer, IntArrayList> primitiveAlbedoTints = new EnumMap<>(ChunkSectionLayer.class);
   private final List<MinecraftSectionSemantics.Light> lightSources = new ArrayList<>();
   private int currentBlockStateId;
   private int currentFluidStateId;

   public MinecraftSectionSemanticCollector() {
      this.semanticIndices.defaultReturnValue(-1);
   }

   public void beginBlock(final BlockState blockState, final int localX, final int localY, final int localZ) {
      this.currentBlockStateId = Block.getId(blockState);
      int emission = blockState.getLightEmission();
      if (emission > 0) {
         this.lightSources.add(MinecraftSectionSemantics.Light.of(localX, localY, localZ, emission, this.currentBlockStateId));
      }
   }

   public void beginFluid(final FluidState fluidState) {
      this.currentFluidStateId = Fluid.FLUID_STATE_REGISTRY.getId(fluidState);
   }

   public void recordBlockQuad(final ChunkSectionLayer layer, final BakedQuad.MaterialInfo materialInfo, final int albedoTint) {
      this.recordBlock(
         layer,
         this.currentBlockStateId,
         materialInfo.sprite().contents().name(),
         materialInfo.flags(),
         materialInfo.isTinted(),
         materialInfo.shade(),
         materialInfo.lightEmission(),
         albedoTint
      );
   }

   void recordBlock(
      final ChunkSectionLayer layer,
      final int blockStateId,
      final Identifier sprite,
      final int flags,
      final boolean tinted,
      final boolean shade,
      final int lightEmission,
      final int albedoTint
   ) {
      this.record(layer, this.indexOfBlock(blockStateId, sprite, flags, tinted, shade, lightEmission), albedoTint);
   }

   public VertexConsumer trackFluidQuads(final ChunkSectionLayer layer, final VertexConsumer delegate) {
      return new FluidSemanticConsumer(this, layer, delegate, this.indexOfFluid(this.currentBlockStateId, this.currentFluidStateId));
   }

   int indexOfBlock(
      final int blockStateId,
      final Identifier sprite,
      final int flags,
      final boolean tinted,
      final boolean shade,
      final int lightEmission
   ) {
      if (blockStateId < 0) throw new IllegalArgumentException("blockStateId must be non-negative: " + blockStateId);
      MinecraftSectionSemantics.BlockMaterial.validate(sprite, flags, lightEmission);
      long fingerprint = mix(BLOCK_FINGERPRINT, blockStateId);
      fingerprint = mix(fingerprint, sprite.hashCode());
      fingerprint = mix(fingerprint, flags);
      fingerprint = mix(fingerprint, tinted ? 1 : 0);
      fingerprint = mix(fingerprint, shade ? 1 : 0);
      fingerprint = mix(fingerprint, lightEmission);
      int candidate = this.semanticIndices.get(fingerprint);
      if (candidate >= 0 && matchesBlock(
         this.semanticEntries.get(candidate), blockStateId, sprite, flags, tinted, shade, lightEmission
      )) return candidate;
      if (candidate >= 0) {
         for (int index = 0; index < this.semanticEntries.size(); index++) {
            if (matchesBlock(this.semanticEntries.get(index), blockStateId, sprite, flags, tinted, shade, lightEmission)) return index;
         }
      }
      int index = this.semanticEntries.size();
      this.semanticEntries.add(new MinecraftSectionSemantics.Primitive(
         blockStateId, new MinecraftSectionSemantics.BlockMaterial(sprite, flags, tinted, shade, lightEmission)
      ));
      if (candidate < 0) this.semanticIndices.put(fingerprint, index);
      return index;
   }

   int indexOfFluid(final int blockStateId, final int fluidStateId) {
      if (blockStateId < 0 || fluidStateId < 0) {
         throw new IllegalArgumentException("block/fluid state IDs must be non-negative: " + blockStateId + "/" + fluidStateId);
      }
      long fingerprint = mix(mix(FLUID_FINGERPRINT, blockStateId), fluidStateId);
      int candidate = this.semanticIndices.get(fingerprint);
      if (candidate >= 0 && matchesFluid(this.semanticEntries.get(candidate), blockStateId, fluidStateId)) return candidate;
      if (candidate >= 0) {
         for (int index = 0; index < this.semanticEntries.size(); index++) {
            if (matchesFluid(this.semanticEntries.get(index), blockStateId, fluidStateId)) return index;
         }
      }
      int index = this.semanticEntries.size();
      this.semanticEntries.add(new MinecraftSectionSemantics.Primitive(
         blockStateId, new MinecraftSectionSemantics.FluidMaterial(fluidStateId)
      ));
      if (candidate < 0) this.semanticIndices.put(fingerprint, index);
      return index;
   }

   int semanticEntryCount() {
      return this.semanticEntries.size();
   }

   int recordedPrimitiveCount(final ChunkSectionLayer layer) {
      IntArrayList indices = this.primitiveSemanticIndices.get(layer);
      return indices == null ? 0 : indices.size();
   }

   private static long mix(final long fingerprint, final int value) {
      return HashCommon.mix(fingerprint ^ Integer.toUnsignedLong(value));
   }

   private static boolean matchesBlock(
      final MinecraftSectionSemantics.Primitive semantic,
      final int blockStateId,
      final Identifier sprite,
      final int flags,
      final boolean tinted,
      final boolean shade,
      final int lightEmission
   ) {
      return semantic.blockStateId() == blockStateId
         && semantic.material() instanceof MinecraftSectionSemantics.BlockMaterial block
         && block.sprite().equals(sprite) && block.flags() == flags && block.tinted() == tinted
         && block.shade() == shade && block.lightEmission() == lightEmission;
   }

   private static boolean matchesFluid(
      final MinecraftSectionSemantics.Primitive semantic, final int blockStateId, final int fluidStateId
   ) {
      return semantic.blockStateId() == blockStateId
         && semantic.material() instanceof MinecraftSectionSemantics.FluidMaterial fluid
         && fluid.fluidStateId() == fluidStateId;
   }

   private void record(final ChunkSectionLayer layer, final int semanticIndex, final int albedoTint) {
      this.primitiveSemanticIndices.computeIfAbsent(layer, ignored -> new IntArrayList(INITIAL_LAYER_QUADS)).add(semanticIndex);
      this.primitiveAlbedoTints.computeIfAbsent(layer, ignored -> new IntArrayList(INITIAL_LAYER_QUADS)).add(albedoTint);
   }

   public MinecraftSectionSemantics.Metadata build(final Map<ChunkSectionLayer, MeshData> renderedLayers) {
      Map<ChunkSectionLayer, int[]> frozenIndices = new EnumMap<>(ChunkSectionLayer.class);
      Map<ChunkSectionLayer, int[]> frozenAlbedoTints = new EnumMap<>(ChunkSectionLayer.class);
      for (Entry<ChunkSectionLayer, MeshData> entry : renderedLayers.entrySet()) {
         int vertexCount = entry.getValue().drawState().vertexCount();
         if (vertexCount % 4 != 0) {
            throw new IllegalStateException("Section layer " + entry.getKey() + " has a non-quad vertex count: " + vertexCount);
         }
         IntArrayList indices = this.primitiveSemanticIndices.get(entry.getKey());
         IntArrayList albedoTints = this.primitiveAlbedoTints.get(entry.getKey());
         int primitiveCount = indices == null ? 0 : indices.size();
         int tintCount = albedoTints == null ? 0 : albedoTints.size();
         if (primitiveCount != vertexCount / 4 || tintCount != primitiveCount) {
            throw new IllegalStateException(
               "Section semantic primitive mismatch for " + entry.getKey() + ": expected " + (vertexCount / 4)
                  + ", recorded semantics=" + primitiveCount + ", tints=" + tintCount
            );
         }
         frozenIndices.put(entry.getKey(), indices.toIntArray());
         frozenAlbedoTints.put(entry.getKey(), albedoTints.toIntArray());
      }
      return new MinecraftSectionSemantics.Metadata(this.semanticEntries, frozenIndices, frozenAlbedoTints, this.lightSources);
   }

   static final class FluidSemanticConsumer implements VertexConsumer {
      private final MinecraftSectionSemanticCollector collector;
      private final ChunkSectionLayer layer;
      private final VertexConsumer delegate;
      private final int semanticIndex;
      private int verticesInPrimitive;

      FluidSemanticConsumer(
         final MinecraftSectionSemanticCollector collector,
         final ChunkSectionLayer layer,
         final VertexConsumer delegate,
         final int semanticIndex
      ) {
         this.collector = collector;
         this.layer = layer;
         this.delegate = delegate;
         this.semanticIndex = semanticIndex;
      }

      private void recordVertex() {
         if (++this.verticesInPrimitive == 4) {
            this.collector.record(this.layer, this.semanticIndex, -1);
            this.verticesInPrimitive = 0;
         }
      }

      @Override
      public VertexConsumer addVertex(final float x, final float y, final float z) {
         this.delegate.addVertex(x, y, z);
         this.recordVertex();
         return this;
      }

      @Override
      public void addVertex(
         final float x, final float y, final float z, final int color, final float u, final float v,
         final int overlayCoords, final int lightCoords, final float nx, final float ny, final float nz
      ) {
         this.delegate.addVertex(x, y, z, color, u, v, overlayCoords, lightCoords, nx, ny, nz);
         this.recordVertex();
      }

      @Override
      public VertexConsumer setColor(final int r, final int g, final int b, final int a) {
         this.delegate.setColor(r, g, b, a);
         return this;
      }

      @Override
      public VertexConsumer setColor(final int color) {
         this.delegate.setColor(color);
         return this;
      }

      @Override
      public VertexConsumer setUv(final float u, final float v) {
         this.delegate.setUv(u, v);
         return this;
      }

      @Override
      public VertexConsumer setUv1(final int u, final int v) {
         this.delegate.setUv1(u, v);
         return this;
      }

      @Override
      public VertexConsumer setUv2(final int u, final int v) {
         this.delegate.setUv2(u, v);
         return this;
      }

      @Override
      public VertexConsumer setNormal(final float x, final float y, final float z) {
         this.delegate.setNormal(x, y, z);
         return this;
      }

      @Override
      public VertexConsumer setLineWidth(final float width) {
         this.delegate.setLineWidth(width);
         return this;
      }
   }
}
