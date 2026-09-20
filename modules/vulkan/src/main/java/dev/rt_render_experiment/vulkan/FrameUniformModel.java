package dev.rt_render_experiment.vulkan;

import static dev.rt_render_experiment.contract.GpuLayouts.RECORD_SENTINEL;
import static dev.rt_render_experiment.contract.GpuLayouts.RT_REGION_BLOCKS;
import dev.rt_render_experiment.contract.DynamicEmitter;

import java.util.List;
import java.util.Objects;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;


public final class FrameUniformModel {
   public static final double MAX_HISTORY_CAMERA_DELTA_BLOCKS = 32.0;
   public static final float MAX_CONTINUOUS_SUN_DELTA_RADIANS = 0.02F;
   public static final float MAX_CONTINUOUS_RAIN_DELTA = 0.25F;


   private static final float TWO_PI = (float)(Math.PI * 2.0);
   private static final int[][] JITTER_ORDER = {
      {0},
      {0, 3, 1, 2},
      {0, 4, 8, 2, 6, 1, 5, 7, 3},
      {0, 10, 5, 15, 2, 8, 7, 13, 1, 11, 4, 14, 3, 9, 6, 12}
   };

   private FrameUniformModel() {
   }

   public enum FrameOutcome {
      ABANDONED,
      INVALIDATED,
      COMMITTED
   }


   public static int cellCoordinate(final double coordinate) {
      if (!Double.isFinite(coordinate)) throw new IllegalArgumentException("Cell coordinate must be finite");
      return Math.toIntExact(Math.floorDiv((long)Math.floor(coordinate), 16L));
   }

   public record FrameSequence(long value) {
      public FrameSequence {
         if (value <= 0L) {
            throw new IllegalArgumentException("Frame sequence must be one-based: " + value);
         }
      }

      public int shaderValue() {
         return (int)this.value;
      }
   }

   public record CameraPosition(double x, double y, double z) {
      public CameraPosition {
         if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Camera position must be finite");
         }
      }
   }

   public record RtOrigin(long x, long y, long z) {
      public static final RtOrigin ZERO = new RtOrigin(0L, 0L, 0L);

      public RtOrigin {
         if (Math.floorMod(x, RT_REGION_BLOCKS) != 0L
            || Math.floorMod(y, RT_REGION_BLOCKS) != 0L
            || Math.floorMod(z, RT_REGION_BLOCKS) != 0L) {
            throw new IllegalArgumentException("RT origin must be aligned to " + RT_REGION_BLOCKS + " blocks");
         }
      }
   }

   public record RtOffset(float x, float y, float z) {
      public RtOffset {
         if (!withinRtRegion(x) || !withinRtRegion(y) || !withinRtRegion(z)) {
            throw new IllegalArgumentException("RT offset must be finite and inside one RT region");
         }
      }

      private static boolean withinRtRegion(final float value) {
         return Float.isFinite(value) && value >= 0.0F && value < (float)RT_REGION_BLOCKS;
      }
   }

   public record CameraFrame(
      CameraPosition position,
      RtOrigin rtOrigin,
      RtOffset rtOffset
   ) {
      public CameraFrame {
         Objects.requireNonNull(position, "position");
         Objects.requireNonNull(rtOrigin, "rtOrigin");
         Objects.requireNonNull(rtOffset, "rtOffset");
      }

      public static CameraFrame fromWorld(final double x, final double y, final double z) {
         CameraPosition position = new CameraPosition(x, y, z);
         long blockX = floorBlock(x), blockY = floorBlock(y), blockZ = floorBlock(z);
         RtOrigin origin = new RtOrigin(
            Math.multiplyExact(Math.floorDiv(blockX, RT_REGION_BLOCKS), RT_REGION_BLOCKS),
            Math.multiplyExact(Math.floorDiv(blockY, RT_REGION_BLOCKS), RT_REGION_BLOCKS),
            Math.multiplyExact(Math.floorDiv(blockZ, RT_REGION_BLOCKS), RT_REGION_BLOCKS)
         );
         RtOffset offset = new RtOffset(
            checkedFloat(x - origin.x(), "camera RT x"),
            checkedFloat(y - origin.y(), "camera RT y"),
            checkedFloat(z - origin.z(), "camera RT z")
         );
         return new CameraFrame(position, origin, offset);
      }

      public double x() {
         return this.position.x();
      }

      public double y() {
         return this.position.y();
      }

      public double z() {
         return this.position.z();
      }

      private static long floorBlock(final double value) {
         if (value < -0x1.0p63 || value >= 0x1.0p63) {
            throw new IllegalArgumentException("Camera coordinate is outside the signed-block domain: " + value);
         }
         return (long)Math.floor(value);
      }
   }

   public record CameraDelta(double x, double y, double z) {
      public static final CameraDelta ZERO = new CameraDelta(0.0, 0.0, 0.0);

      public static CameraDelta between(final CameraPosition current, final CameraPosition previous) {
         return new CameraDelta(
            current.x() - previous.x(),
            current.y() - previous.y(),
            current.z() - previous.z()
         );
      }

      public boolean representable() {
         return Double.isFinite(squaredBlocks())
            && Float.isFinite((float)this.x)
            && Float.isFinite((float)this.y)
            && Float.isFinite((float)this.z);
      }

      public double squaredBlocks() {
         return this.x * this.x + this.y * this.y + this.z * this.z;
      }

      public float floatX() {
         return checkedFloat(this.x, "camera delta x");
      }

      public float floatY() {
         return checkedFloat(this.y, "camera delta y");
      }

      public float floatZ() {
         return checkedFloat(this.z, "camera delta z");
      }
   }

   public record Environment(
      float sunAngleRadians, float clearSkyWeight, int renderDistanceBlocks, boolean cloudsVisible,
      int continuityKey, int generation, float cloudPhaseX, float cloudPhaseZ,
      float sunX, float sunY, float sunZ, float moonX, float moonY, float moonZ, float referenceAltitude
   ) {
      public Environment {
         if (!Float.isFinite(sunAngleRadians) || !unit(clearSkyWeight) || renderDistanceBlocks < 0
            || !Float.isFinite(cloudPhaseX) || !Float.isFinite(cloudPhaseZ) || !Float.isFinite(referenceAltitude)
            || !allFinite(sunX, sunY, sunZ) || !allFinite(moonX, moonY, moonZ))
            throw new IllegalArgumentException("Invalid renderer environment facts");
      }

      public boolean discontinuousFrom(final Environment previous) {
         return this.continuityKey != previous.continuityKey
            || angularDistance(this.sunAngleRadians, previous.sunAngleRadians) > MAX_CONTINUOUS_SUN_DELTA_RADIANS
            || Math.abs(this.clearSkyWeight - previous.clearSkyWeight) > MAX_CONTINUOUS_RAIN_DELTA;
      }

      public float rainAmount() { return 1.0F - this.clearSkyWeight; }
   }

   public record GeometryPublication(long recordAddress, int recordCount, long rasterGeneration, long sceneGeneration) {
      public GeometryPublication {
         if (recordCount < 0 || recordCount > RECORD_SENTINEL
            || (recordCount > 0 && (recordAddress == 0L || (recordAddress & 7L) != 0L))
            || rasterGeneration < 0L || sceneGeneration < 0L) {
            throw new IllegalArgumentException("Invalid geometry publication");
         }
      }
   }

   public record LightPublication(
      long cellAddress,
      int cellMask,
      int cellCount,
      List<DynamicEmitter> dynamicEmitters,
      long generation,
      long samplingGeneration
   ) {
      public LightPublication {
         dynamicEmitters = List.copyOf(Objects.requireNonNull(dynamicEmitters, "dynamicEmitters"));
         long capacity = (long)cellMask + 1L;
         boolean powerOfTwo = capacity > 0L && (capacity & capacity - 1L) == 0L;
         if (cellMask < 0 || !powerOfTwo || cellCount < 0 || cellCount > capacity / 2L
            || (cellCount > 0 && (cellAddress == 0L || (cellAddress & 7L) != 0L))
            || generation < 0L || samplingGeneration < 0L) {
            throw new IllegalArgumentException("Invalid light publication");
         }
      }
   }

   public record Publication(GeometryPublication geometry, LightPublication lighting) {
      public Publication {
         Objects.requireNonNull(geometry, "geometry");
         Objects.requireNonNull(lighting, "lighting");
      }
   }

   public record FrameInput(
      Environment environment,
      boolean cameraInWater,
      boolean traversalAvailable,
      boolean targetHistoryValid,
      int transportScale,
      int validationMode,
      boolean telemetryEnabled,
      int rendererDebug,
      int outputWidth,
      int outputHeight,
      boolean projectionJitterEnabled
   ) {
      public FrameInput(
         final Environment environment,
         final boolean cameraInWater,
         final boolean traversalAvailable,
         final boolean targetHistoryValid,
         final int transportScale,
         final int validationMode,
         final boolean telemetryEnabled,
         final int rendererDebug
      ) {
         this(
            environment, cameraInWater, traversalAvailable, targetHistoryValid,
            transportScale, validationMode, telemetryEnabled, rendererDebug,
            1, 1, false
         );
      }

      public FrameInput {
         Objects.requireNonNull(environment, "environment");
         if (transportScale < 1 || transportScale > JITTER_ORDER.length
            || validationMode < 0 || validationMode > 2
            || rendererDebug < 0 || rendererDebug > 11
            || outputWidth <= 0 || outputHeight <= 0) {
            throw new IllegalArgumentException("Invalid normalized frame options");
         }
      }
   }

   public record Jitter(int scale, int x, int y) {
      public Jitter {
         if (scale < 1 || scale > JITTER_ORDER.length || x < 0 || x >= scale || y < 0 || y >= scale) {
            throw new IllegalArgumentException("Jitter is outside its transport cell");
         }
      }

      public static Jitter forFrame(final FrameSequence sequence, final int scale) {
         if (scale < 1 || scale > JITTER_ORDER.length) {
            throw new IllegalArgumentException("Transport scale must be in [1,4]");
         }
         int[] order = JITTER_ORDER[scale - 1];
         int index = order[Math.floorMod(sequence.value(), order.length)];
         return new Jitter(scale, index % scale, index / scale);
      }
   }

   public record ProjectionJitter(float pixelX, float pixelY, float ndcX, float ndcY) {
      public static final ProjectionJitter ZERO = new ProjectionJitter(0.0F, 0.0F, 0.0F, 0.0F);

      public ProjectionJitter {
         if (!allFinite(pixelX, pixelY, ndcX, ndcY)
            || Math.abs(pixelX) > 0.5F || Math.abs(pixelY) > 0.5F
            || Math.abs(ndcX) > 1.0F || Math.abs(ndcY) > 1.0F) {
            throw new IllegalArgumentException("Projection jitter is outside its pixel/NDC contract");
         }
      }

      public static ProjectionJitter forFrame(
         final FrameSequence sequence,
         final int width,
         final int height,
         final boolean enabled
      ) {
         if (!enabled) return ZERO;
         int phase = Math.floorMod(sequence.value() - 1, 32) + 1;
         float x = halton(phase, 2) - 0.5F;
         float y = halton(phase, 3) - 0.5F;
         return new ProjectionJitter(x, y, 2.0F * x / width, 2.0F * y / height);
      }

      private static float halton(final int index, final int base) {
         int value = index;
         float fraction = 1.0F;
         float result = 0.0F;
         while (value > 0) {
            fraction /= base;
            result += fraction * (value % base);
            value /= base;
         }
         return result;
      }
   }

   public record UniformFrame(
      FrameSequence sequence,
      Matrix4fc inverseProjView,
      Matrix4fc currentProjView,
      Matrix4fc previousProjView,
      CameraFrame camera,
      RtOffset previousRtOffset,
      CameraDelta cameraDelta,
      boolean historyValid,
      boolean lightStable,
      boolean sceneStable,
      int previousCellX, int previousCellY, int previousCellZ,
      FrameInput input,
      Publication publication,
      Jitter jitter,
      ProjectionJitter projectionJitter,
      ProjectionJitter previousProjectionJitter
   ) {
      public UniformFrame {
         Objects.requireNonNull(sequence, "sequence");
         Objects.requireNonNull(inverseProjView, "inverseProjView");
         Objects.requireNonNull(currentProjView, "currentProjView");
         Objects.requireNonNull(previousProjView, "previousProjView");
         Objects.requireNonNull(camera, "camera");
         Objects.requireNonNull(previousRtOffset, "previousRtOffset");
         Objects.requireNonNull(cameraDelta, "cameraDelta");
         Objects.requireNonNull(input, "input");
         Objects.requireNonNull(publication, "publication");
         Objects.requireNonNull(jitter, "jitter");
         Objects.requireNonNull(projectionJitter, "projectionJitter");
         Objects.requireNonNull(previousProjectionJitter, "previousProjectionJitter");
      }
   }

   public static final class History {
      private enum Phase {
         IDLE,
         BEGUN,
         PREPARED
      }

      private record Previous(
         Matrix4f projView,
         CameraPosition camera,
         RtOffset rtOffset,
         boolean cameraInWater,
         Environment environment,
         long rasterGeneration,
         long sceneGeneration,
         long lightSamplingGeneration,
         ProjectionJitter projectionJitter,
         boolean valid
      ) {
      }

      private Phase phase = Phase.IDLE;
      private long sequence;
      private Previous previous;
      private Previous pending;
      private UniformFrame prepared;

      public FrameSequence beginFrame() {
         requirePhase(Phase.IDLE, "begin frame");
         this.sequence = Math.addExact(this.sequence, 1L);
         this.phase = Phase.BEGUN;
         return new FrameSequence(this.sequence);
      }

      public UniformFrame prepare(
         final Matrix4fc projection,
         final Matrix4fc viewRotation,
         final CameraFrame camera,
         final FrameInput input,
         final Publication publication
      ) {
         requirePhase(Phase.BEGUN, "prepare frame");
         Objects.requireNonNull(projection, "projection");
         Objects.requireNonNull(viewRotation, "viewRotation");
         Objects.requireNonNull(camera, "camera");
         Objects.requireNonNull(input, "input");
         Objects.requireNonNull(publication, "publication");

         Matrix4f current = new Matrix4f(projection).mul(viewRotation);
         boolean currentMatrixValid = matrixFinite(current);
         if (!currentMatrixValid) current.identity();
         Matrix4f inverse = currentMatrixValid ? new Matrix4f(current).invert() : new Matrix4f();
         if (!matrixFinite(inverse)) {
            inverse.identity(); current.identity();
            currentMatrixValid = false;
         }

         CameraDelta measuredDelta = this.previous == null
            ? CameraDelta.ZERO
            : CameraDelta.between(camera.position(), this.previous.camera());
         boolean deltaValid = measuredDelta.representable()
            && measuredDelta.squaredBlocks() <= MAX_HISTORY_CAMERA_DELTA_BLOCKS * MAX_HISTORY_CAMERA_DELTA_BLOCKS;
         boolean historyValid = input.targetHistoryValid()
            && this.previous != null
            && this.previous.valid()
            && input.traversalAvailable()
            && currentMatrixValid
            && deltaValid
            && input.cameraInWater() == this.previous.cameraInWater();
         boolean lightStable = historyValid
            && publication.lighting().samplingGeneration() == this.previous.lightSamplingGeneration()
            && publication.geometry().rasterGeneration() == this.previous.rasterGeneration();
         boolean sceneStable = historyValid
            && !input.environment().discontinuousFrom(this.previous.environment())
            && publication.geometry().rasterGeneration() == this.previous.rasterGeneration()
            && publication.geometry().sceneGeneration() == this.previous.sceneGeneration();

         Matrix4f previousMatrix = historyValid ? new Matrix4f(this.previous.projView()) : new Matrix4f();
         CameraDelta shaderDelta = historyValid ? measuredDelta : CameraDelta.ZERO;
         RtOffset previousRtOffset = historyValid ? this.previous.rtOffset() : new RtOffset(0.0F, 0.0F, 0.0F);
         FrameSequence currentSequence = new FrameSequence(this.sequence);
         ProjectionJitter currentProjectionJitter = ProjectionJitter.forFrame(
            currentSequence,
            input.outputWidth(),
            input.outputHeight(),
            input.projectionJitterEnabled()
         );
         ProjectionJitter previousProjectionJitter = historyValid
            ? this.previous.projectionJitter()
            : ProjectionJitter.ZERO;
         this.prepared = new UniformFrame(
            currentSequence,
            inverse,
            current,
            previousMatrix,
            camera,
            previousRtOffset,
            shaderDelta,
            historyValid,
            lightStable,
            sceneStable,
            historyValid ? cellCoordinate(this.previous.camera().x()) : 0,
            historyValid ? cellCoordinate(this.previous.camera().y()) : 0,
            historyValid ? cellCoordinate(this.previous.camera().z()) : 0,
            input,
            publication,
            Jitter.forFrame(currentSequence, input.transportScale()),
            currentProjectionJitter,
            previousProjectionJitter
         );
         this.pending = new Previous(
            new Matrix4f(current),
            camera.position(),
            camera.rtOffset(),
            input.cameraInWater(),
            input.environment(),
            publication.geometry().rasterGeneration(),
            publication.geometry().sceneGeneration(),
            publication.lighting().samplingGeneration(),
            currentProjectionJitter,
            currentMatrixValid && input.traversalAvailable()
         );
         this.phase = Phase.PREPARED;
         return this.prepared;
      }

      public FrameOutcome finishFrame(final boolean worldFrameSucceeded) {
         if (this.phase == Phase.IDLE) {
            throw new IllegalStateException("Cannot finish an idle frame-uniform transaction");
         }
         if (!worldFrameSucceeded) {
            clearPending();
            return FrameOutcome.ABANDONED;
         }
         requirePhase(Phase.PREPARED, "commit frame");
         FrameOutcome outcome;
         if (this.pending.valid()) {
            this.previous = this.pending;
            outcome = FrameOutcome.COMMITTED;
         } else {
            this.previous = null;
            outcome = FrameOutcome.INVALIDATED;
         }
         clearPending();
         return outcome;
      }

      public boolean prepared() {
         return this.phase == Phase.PREPARED;
      }

      public Matrix4fc preparedProjView() {
         if (this.phase != Phase.PREPARED || this.prepared == null) {
            throw new IllegalStateException("Frame projection-view requested before preparation");
         }
         return this.prepared.currentProjView();
      }

      public ProjectionJitter preparedProjectionJitter() {
         if (this.phase != Phase.PREPARED || this.prepared == null) {
            throw new IllegalStateException("Projection jitter requested before preparation");
         }
         return this.prepared.projectionJitter();
      }

      public boolean preparedHistoryValid() {
         if (this.phase != Phase.PREPARED || this.prepared == null) {
            throw new IllegalStateException("Frame history validity requested before preparation");
         }
         return this.prepared.historyValid();
      }

      public long sequence() {
         return this.sequence;
      }

      public void reset() {
         this.phase = Phase.IDLE;
         this.sequence = 0L;
         this.previous = null;
         this.pending = null;
         this.prepared = null;
      }

      private void clearPending() {
         this.pending = null;
         this.prepared = null;
         this.phase = Phase.IDLE;
      }

      private void requirePhase(final Phase expected, final String operation) {
         if (this.phase != expected) {
            throw new IllegalStateException("Cannot " + operation + " while frame-uniform phase is " + this.phase);
         }
      }
   }

   public static boolean matrixFinite(final Matrix4fc matrix) {
      Objects.requireNonNull(matrix, "matrix");
      return Float.isFinite(matrix.m00()) && Float.isFinite(matrix.m01())
         && Float.isFinite(matrix.m02()) && Float.isFinite(matrix.m03())
         && Float.isFinite(matrix.m10()) && Float.isFinite(matrix.m11())
         && Float.isFinite(matrix.m12()) && Float.isFinite(matrix.m13())
         && Float.isFinite(matrix.m20()) && Float.isFinite(matrix.m21())
         && Float.isFinite(matrix.m22()) && Float.isFinite(matrix.m23())
         && Float.isFinite(matrix.m30()) && Float.isFinite(matrix.m31())
         && Float.isFinite(matrix.m32()) && Float.isFinite(matrix.m33());
   }

   private static boolean unit(final float value) {
      return Float.isFinite(value) && value >= 0.0F && value <= 1.0F;
   }

   private static boolean allFinite(final float... values) {
      for (float value : values) {
         if (!Float.isFinite(value)) {
            return false;
         }
      }
      return true;
   }

   private static float checkedFloat(final double value, final String label) {
      float result = (float)value;
      if (!Float.isFinite(result)) {
         throw new IllegalArgumentException(label + " is outside the finite float domain: " + value);
      }
      return result;
   }

   private static float angularDistance(final float left, final float right) {
      float distance = Math.abs(normalizedAngle(left) - normalizedAngle(right));
      return Math.min(distance, TWO_PI - distance);
   }

   public static float normalizedAngle(final float value) {
      float normalized = value % TWO_PI;
      return normalized < 0.0F ? normalized + TWO_PI : normalized;
   }

}
