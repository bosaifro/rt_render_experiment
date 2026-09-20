package dev.rt_render_experiment.contract;


public final class RendererAppearance {
   private RendererAppearance() {}

   public static final float ATMOSPHERE_REFERENCE_ALTITUDE = 62.0F;

   public static final float DYNAMIC_TEXTURE_MAX_STRENGTH = 6.0F;


   public static int textureProxyLevel(final float meanCoverage) {
      return Math.max(1, Math.min(15, Math.round(15.0F * (float)Math.sqrt((2.0F / 3.0F) * meanCoverage))));
   }
}
