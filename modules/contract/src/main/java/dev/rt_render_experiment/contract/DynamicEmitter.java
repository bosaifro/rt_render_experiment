package dev.rt_render_experiment.contract;

import static dev.rt_render_experiment.contract.RendererAppearance.DYNAMIC_TEXTURE_MAX_STRENGTH;





public record DynamicEmitter(long worldEpoch, long objectKeyLow, long objectKeyHigh, long partKey, int kind,
      float x, float y, float z, float law, float red, float green, float blue, float strength) {
   public DynamicEmitter {
      boolean finite = Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z) && Float.isFinite(law)
         && Float.isFinite(red) && Float.isFinite(green) && Float.isFinite(blue) && Float.isFinite(strength);
      int packedLaw = (int)law;
      boolean validLaw = law == packedLaw && (packedLaw & DYNAMIC_LEVEL_MASK) != 0
         && ((packedLaw & DYNAMIC_MATERIAL_MASK) >>> DYNAMIC_MATERIAL_SHIFT) < DYNAMIC_MATERIAL_COUNT
         && ((packedLaw & DYNAMIC_MEDIUM_MASK) >>> DYNAMIC_MEDIUM_SHIFT) <= DYNAMIC_MEDIUM_WATER
         && (packedLaw & ~DYNAMIC_LAW_MASK) == 0;
      boolean exactTexture = strength > 0.0F;
      boolean validColor = red >= 0.0F && red <= 1.0F && green >= 0.0F && green <= 1.0F
         && blue >= 0.0F && blue <= 1.0F && strength >= 0.0F && strength <= DYNAMIC_TEXTURE_MAX_STRENGTH;
      boolean zeroPair = red == 0.0F && green == 0.0F && blue == 0.0F && strength == 0.0F;
      if (kind < MATERIAL || kind > CAMERA_ATTACHED || !finite || !validLaw || !validColor
         || (kind == TEXTURE) != exactTexture || (kind != TEXTURE && !zeroPair)) {
         throw new IllegalArgumentException("Invalid dynamic-emitter fact");
      }
   }
   public float component(final int index) { return switch (index) { case 0 -> x; case 1 -> y; case 2 -> z; case 3 -> law; case 4 -> red; case 5 -> green; case 6 -> blue; case 7 -> strength; default -> throw new IndexOutOfBoundsException(index); }; }
   private static final int DYNAMIC_MATERIAL_COUNT = GpuLayouts.MATERIAL_COUNT;
   private static final int DYNAMIC_LEVEL_MASK = 0xF;
   private static final int DYNAMIC_MATERIAL_SHIFT = 4, DYNAMIC_MATERIAL_MASK = 0xFF0;
   private static final int DYNAMIC_MEDIUM_SHIFT = 12, DYNAMIC_MEDIUM_MASK = 0x3000;
   private static final int DYNAMIC_LAW_MASK = DYNAMIC_LEVEL_MASK | DYNAMIC_MATERIAL_MASK | DYNAMIC_MEDIUM_MASK;
   private static final int DYNAMIC_MEDIUM_WATER = 1;

   public static final int MATERIAL = 1, TEXTURE = 2, CAMERA_ATTACHED = 3;
   public boolean sameSource(final DynamicEmitter other) {
      return this.worldEpoch == other.worldEpoch && this.objectKeyLow == other.objectKeyLow
         && this.objectKeyHigh == other.objectKeyHigh && this.partKey == other.partKey && this.kind == other.kind;
   }



   public boolean sameSamplingSource(final DynamicEmitter other) {
      return sameSource(other) && (((int)this.law ^ (int)other.law) & ~DYNAMIC_LEVEL_MASK) == 0;
   }
}
