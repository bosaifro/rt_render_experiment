package dev.rt_render_experiment.contract;










public final class GpuLayouts {

   public static final int MATERIAL_COUNT = 50;
   public static final int MATERIAL_SKIN_CLOTH = 64;
   public static final int MATERIAL_ARMOR_METAL = 65;


   public static final int DYNAMIC_POSITION_POSITION = 0;
   public static final int DYNAMIC_POSITION_PAD = 12;
   public static final int DYNAMIC_POSITION_VERTEX_STRIDE = 16;
   public static final float POSITION_EPSILON = 1.0E-4F;


   public static boolean samePosition(final float left, final float right) {
      float scale = Math.max(1.0F, Math.max(Math.abs(left), Math.abs(right)));
      return Math.abs(left - right) <= POSITION_EPSILON * scale;
   }


   public static final int DYNAMIC_TRACE_POSITION = 0;
   public static final int DYNAMIC_TRACE_TINT = 12;
   public static final int DYNAMIC_TRACE_UV = 16;
   public static final int DYNAMIC_TRACE_RESERVED0 = 24;
   public static final int DYNAMIC_TRACE_OVERLAY = 28;
   public static final int DYNAMIC_TRACE_NORMAL = 32;
   public static final int DYNAMIC_TRACE_FLAGS = 44;
   public static final int DYNAMIC_TRACE_FLAG_EMISSION_UV_VALID = 1;
   public static final int DYNAMIC_TRACE_EMISSION_UV = 48;
   public static final int DYNAMIC_TRACE_EMISSION_TINT = 56;
   public static final int DYNAMIC_TRACE_RESERVED = 60;
   public static final int DYNAMIC_TRACE_VERTEX_STRIDE = 64;


   public static final int SEMANTIC_VARIANT_KEY = 0;
   public static final int SEMANTIC_SURFACE_KEY = 8;
   public static final int SEMANTIC_FACTS = 12;
   public static final int SEMANTIC_ENTRY_STRIDE = 16;


   public static final int GEOMETRY_RECORD_VERTEX_ADDRESS = 0;
   public static final int GEOMETRY_RECORD_DYNAMIC_SURFACE_ADDRESS = 8;
   public static final int GEOMETRY_RECORD_SEMANTIC_ENTRY_ADDRESS = 16;
   public static final int GEOMETRY_RECORD_STATIC_ORIGIN = 24;
   public static final int GEOMETRY_RECORD_STATIC_ORIGIN_Y = 28;
   public static final int GEOMETRY_RECORD_STATIC_ORIGIN_Z = 32;
   public static final int GEOMETRY_RECORD_FLAGS_AND_QUAD_COUNT = 36;
   public static final int GEOMETRY_RECORD_STRIDE = 40;


   public static final int DYNAMIC_SURFACE_OBJECT_KEY = 0;
   public static final int DYNAMIC_SURFACE_PART_KEY = 16;
   public static final int DYNAMIC_SURFACE_MATERIAL_ID = 24;
   public static final int DYNAMIC_SURFACE_FLAGS = 28;
   public static final int DYNAMIC_SURFACE_TEXTURE_SLOTS = 32;
   public static final int DYNAMIC_SURFACE_COLOR_SLOT = 32;
   public static final int DYNAMIC_SURFACE_COVERAGE_SLOT = 36;
   public static final int DYNAMIC_SURFACE_EMISSION_SLOT = 40;
   public static final int DYNAMIC_SURFACE_OVERLAY_SLOT = 44;
   public static final int DYNAMIC_SURFACE_GENERATIONS = 48;
   public static final int DYNAMIC_SURFACE_GEOMETRY_GENERATION = 48;
   public static final int DYNAMIC_SURFACE_MOTION_GENERATION = 52;
   public static final int DYNAMIC_SURFACE_DEFORMATION_GENERATION = 56;
   public static final int DYNAMIC_SURFACE_APPEARANCE_GENERATION = 60;
   public static final int DYNAMIC_SURFACE_MATERIAL_KEY = 64;
   public static final int DYNAMIC_SURFACE_TEXTURE_DEPENDENCY = 72;
   public static final int DYNAMIC_SURFACE_CUTOFF = 80;
   public static final int DYNAMIC_SURFACE_OPACITY_MODE = 84;
   public static final int DYNAMIC_SURFACE_EMISSION_LAW = 88;
   public static final int DYNAMIC_SURFACE_WORLD_EPOCH = 92;
   public static final int DYNAMIC_SURFACE_RECORD_STRIDE = 96;

   public static final int DYNAMIC_SURFACE_FLAG_DOUBLE_SIDED = 1;
   public static final int DYNAMIC_SURFACE_FLAG_ENTITY = 1 << 1;
   public static final int DYNAMIC_SURFACE_FLAG_EXTENDED_RAY_CLEARANCE = 1 << 2;
   public static final int NO_DYNAMIC_TEXTURE_SLOT = -1;
   public static final int MIN_DYNAMIC_TEXTURE_CAPACITY = 16;


   public static final int FRAME_SUN_DIRECTION = 0;
   public static final int FRAME_INV_PROJ_VIEW = 16;
   public static final int FRAME_GEOMETRY = 80;
   public static final int FRAME_WORLD_PARAMETERS = 96;
   public static final int FRAME_LIGHTS = 112;
   public static final int FRAME_CLOUD_MOTION = 128;
   public static final int FRAME_PREVIOUS_PROJ_VIEW = 144;
   public static final int FRAME_CAMERA_DELTA = 208;
   public static final int FRAME_MEDIUM_PARAMETERS = 224;
   public static final int FRAME_RT_ORIGIN_OFFSET = 240;
   public static final int FRAME_TRANSPORT_PARAMETERS = 256;
   public static final int FRAME_MOON_DIRECTION = 272;
   public static final int FRAME_DYNAMIC_EMITTER_TABLE = 288;
   public static final int FRAME_DYNAMIC_EMITTER_STRIDE = 48;
   public static final int DYNAMIC_EMITTER_PROPOSALS = 4;
   public static final int FRAME_PREVIOUS_RT_ORIGIN_OFFSET = 304;
   public static final int FRAME_RECONSTRUCTION_JITTER = 320;
   public static final int FRAME_VIEW_ORIGIN = 336;
   public static final int FRAME_VIEW_OFFSET_PHASE = 352;
   public static final int FRAME_SCENE_RESOURCES = 368;
   public static final int FRAME_CURRENT_PROJ_VIEW = 384;
   public static final int FRAME_ABI_VERSION = 15;


   public static final int ACCELERATION_INSTANCE_STRIDE = 64;
   public static final int SECTION_UNIFORM_STRIDE = 48;
   public static final int TERRAIN_VIEW_UNIFORM_SIZE = 128;
   public static final int TERRAIN_VIEW_PROJECTION = 0;
   public static final int TERRAIN_VIEW_WORLD_TO_VIEW = 64;
   public static final int RIGID_TRANSFORM_STRIDE = 144;
   public static final int FRAME_UNIFORM_SIZE = 448;
   public static final int LIGHT_SOURCE_STRIDE = 8;
   public static final int LIGHT_CELL_STRIDE = 64;


   public static final long RT_REGION_BLOCKS = 256L;


   public static final int RECORD_SENTINEL = 0xFF_FF_FF;
   public static final int RECORDS_PER_STATIC_INSTANCE = 3;
   public static final int MAX_QUADS_PER_GEOMETRY = 0x10_00_00;

   private GpuLayouts() {
   }
}
