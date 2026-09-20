package dev.rt_render_experiment.integration.minecraft;

import dev.rt_render_experiment.vulkan.FrameUniformModel;
import static dev.rt_render_experiment.contract.RendererAppearance.ATMOSPHERE_REFERENCE_ALTITUDE;


public final class MinecraftFrameEnvironment {
   public static final long CLOUD_WORLD_PERIOD_BLOCKS = 65_536L;
   public static final long CLOUD_X_CYCLE_TICKS = 655_360L;
   public static final long CLOUD_Z_CYCLE_TICKS = 1_048_576L;
   private MinecraftFrameEnvironment() {}
   public enum SkyKind {
      NONE(0),
      OVERWORLD(1),
      END(2);

      private final int shaderValue;

      SkyKind(final int shaderValue) {
         this.shaderValue = shaderValue;
      }

      public int shaderValue() {
         return this.shaderValue;
      }
   }


   public static FrameUniformModel.Environment capture(
      SkyKind sky, float sunAngleRadians, float rainBrightness, float starBrightness,
      float cloudHeightBlocks, int cloudMode, int cloudRangeBlocks, int renderDistanceBlocks,
      boolean renderSky, int moonPhase, long gameTimeTicks, float partialTick
   ) {
      java.util.Objects.requireNonNull(sky, "sky");
      if (!Float.isFinite(sunAngleRadians) || !unit(rainBrightness) || !unit(starBrightness)
         || !Float.isFinite(cloudHeightBlocks) || cloudMode < 0 || cloudMode > 2
         || cloudRangeBlocks < 0 || (cloudRangeBlocks & 15) != 0
         || renderDistanceBlocks < 0 || (renderDistanceBlocks & 15) != 0
         || moonPhase < 0 || moonPhase >= 8 || !unit(partialTick)) {
         throw new IllegalArgumentException("Invalid Minecraft environment input");
      }
      int hash = 0x4D3A2F19;
      hash = hashStep(hash, sky.shaderValue());
      hash = hashStep(hash, Math.round(FrameUniformModel.normalizedAngle(sunAngleRadians) * 256.0F));
      hash = hashStep(hash, Math.round(rainBrightness * 63.0F));
      hash = hashStep(hash, Math.round(starBrightness * 63.0F));
      hash = hashStep(hash, moonPhase);
      hash = hashStep(hash, Math.round(cloudHeightBlocks * 16.0F));
      hash = hashStep(hash, cloudRangeBlocks);
      hash = hashStep(hash, cloudMode);
      hash = hashStep(hash, renderSky ? 1 : 0);
      int continuity = sky.shaderValue() | cloudMode << 2 | moonPhase << 4 | (renderSky ? 128 : 0);
      float sunX = 0.0F, sunY = 1.0F;
      if (sky == SkyKind.OVERWORLD) { sunX = -(float)Math.sin(sunAngleRadians); sunY = (float)Math.cos(sunAngleRadians); }

      return new FrameUniformModel.Environment(sunAngleRadians, rainBrightness, renderDistanceBlocks, cloudMode != 0,
         continuity, hash, cloudMotionPhase(gameTimeTicks, partialTick, CLOUD_X_CYCLE_TICKS, 3, 10),
         cloudMotionPhase(gameTimeTicks, partialTick, CLOUD_Z_CYCLE_TICKS, 1, 16),
         sunX, sunY, 0.0F, -sunX, -sunY, -0.0F, ATMOSPHERE_REFERENCE_ALTITUDE);
   }

   public static int section(final double worldCoordinate) { return Math.toIntExact(Math.floorDiv((long)Math.floor(worldCoordinate), 16L)); }

   public static float animationPhase(final long gameTimeTicks, final float partialTick) {
      if (!unit(partialTick)) throw new IllegalArgumentException("Invalid Minecraft partial tick");
      return ((float)(gameTimeTicks % 24000L) + partialTick) / 24000.0F;
   }
   private static boolean unit(float value) { return Float.isFinite(value) && value >= 0.0F && value <= 1.0F; }
   private static int hashStep(int hash, int value) { return Integer.rotateLeft(hash ^ value, 5) * 0x9E3779B9; }
   private static float cloudMotionPhase(long gameTime, float partialTick, long cycleTicks, int numerator, int denominator) {
      long wrappedTicks = Math.floorMod(gameTime, cycleTicks);
      double blocks = ((double)wrappedTicks + partialTick) * numerator / denominator;
      return (float)(blocks - Math.floor(blocks / CLOUD_WORLD_PERIOD_BLOCKS) * CLOUD_WORLD_PERIOD_BLOCKS);
   }
}
