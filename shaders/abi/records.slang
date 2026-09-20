#ifndef R2_ABI_RECORDS
#define R2_ABI_RECORDS
static const uint R2_ABI_VERSION = 1u;
static const uint R2_SEMANTIC_API_VERSION = 1u;
struct FrameRecord {
    float4 inverseViewProjection[4];
    float4 viewProjection[4];
    float4 previousViewProjection[4];
    int4 anchor;
    float4 anchorOffset;
    float4 eye;
    float4 sun;
    float4 moon;
    float4 environment;
    float4 cloudGeometry;
    uint4 cloudField;
    uint4 extent;
    uint4 outputExtent;
    uint4 control;
    uint4 counts;
    float4 previousCamera;
    float4 jitter;
    float4 presentation;
    float4 depthRange;
    uint64_t geometries;
    uint64_t sources;
    uint64_t materials;
    uint64_t atmosphere;
    uint4 initialMediumIdentity[4];
    float4 initialMediumOptical[4];
    float4 initialMediumScattering[4];
    float initialMediumClearance;
    uint _padding0;
    uint64_t emitters;
};
struct HitRecord {
    uint geometry;
    uint primitive;
    uint flags;
    uint reserved;
    float2 barycentrics;
    float distance;
    float footprint;
};
struct SignalRecord {
    float4 diffuse;
    float4 glossy;
    float4 media;
    float4 deterministic;
    float4 transmittance;
};
struct GuideRecord {
    float4 normalDepth;
    float4 diffuseAlbedo;
    float4 specularAlbedo;
    float4 boundaryDiffuseDepth;
    float4 boundarySpecular;
    float4 boundaryNormalRoughness;
    float4 motion;
    uint4 identity;
    uint4 path;
};
struct HistoryRecord {
    float4 diffuse;
    float4 glossy;
    float4 media;
    float4 momentsA;
    float4 momentsB;
    float4 normalDepth;
    uint4 identity;
    uint4 path;
};
struct FilterRecord {
    float4 diffuse;
    float4 glossy;
    float4 media;
};
struct LightHistoryRecord {
    float4 normalDistance;
    int4 receiverCell;
    uint4 identitySample;
    float2 weightFrames;
    uint _padding0;
    uint _padding1;
};
struct DispatchRecord {
    uint4 control;
};
struct TextureCopyRecord {
    uint4 extentMipOffset;
};
struct PositionRecord {
    float4 value;
};
struct CornerRecord {
    float2 uv;
    uint material;
    uint properties;
    float4 tint;
    float4 normal;
    float4 tangent;
    float4 layerUv;
    float4 emissionTint;
};
struct TriangleRecord {
    uint4 indicesSurface;
    uint4 partIdentity;
    uint4 ordinalSource;
};
struct SurfaceRecord {
    uint4 definition;
    float4 coverage;
    uint4 textures;
    float4 colorFactor;
    float4 emissionFactor;
    uint64_t medium;
    uint64_t revision;
    uint hostOcclusion;
    uint boundary;
    uint64_t mediumDefinition;
    uint4 layers;
};
struct GeometryRecord {
    uint64_t positions;
    uint64_t corners;
    uint64_t triangles;
    uint64_t surfaces;
    float4 current[3];
    float4 inverse[3];
    float4 previous[3];
    uint4 identity;
    uint4 revisions;
    uint4 state;
    uint64_t previousPositions;
    uint _padding0;
    uint _padding1;
};
struct SourceRecord {
    float4 centerRadius;
    float4 normalArea;
    float4 tangentSupport;
    float4 importance;
    uint4 definition;
    uint4 association;
    uint4 identity;
    int4 cell;
    uint4 publication;
};
struct LightCellRecord {
    int4 coordinateCount;
    uint4 range;
};
struct SourceTableRecord {
    uint64_t sources;
    uint64_t cells;
    uint4 counts;
};
struct MaterialRecord {
    uint4 classification;
    float4 tintOxidation;
    float4 finish;
    float4 conductorEtaTransmission;
    float4 conductorKThickness;
    float4 absorptionEmissionFloor;
    float4 modifiers;
    float4 emissionSpectrumIntensity;
};
struct AtmosphereCellRecord {
    float4 density;
};
struct MediumRecord {
    uint64_t identity;
    uint kind;
    uint reserved;
    float4 optical;
    float4 scattering;
};
struct EmitterRecord {
    float4 positionRadius;
    float4 radianceArea;
    float4 sampling;
    uint4 identity;
};
struct PrimaryWorkRecord {
    float4 rayDirectionMin;
    float4 hitBaryDistanceMax;
    uint4 hitIdentity;
    float4 reserved[5];
    uint4 policy;
};
struct PathWorkRecord {
    float4 throughputDiffuse;
    float4 throughputGlossy;
    float4 vertexPositionDistance;
    float4 vertexViewBaryX;
    uint4 vertexLocatorBaryY;
};
struct PathMediumRecord {
    float4 optical[10];
    uint4 parents[2];
    uint4 identityDepth;
};
struct PathResultRecord {
    float4 diffuseDistance;
    float4 glossyFlags;
    float4 endpointPositionDistance;
};
struct PathQueueRecord {
    uint4 dispatchCount;
    uint4 iteration;
};
static const uint R2_VIEW_WORLD = 0u;
static const uint R2_VIEW_VIEWMODEL = 1u;
static const uint R2_RAY_CAMERA_PATH = 1u;
static const uint R2_RAY_REFLECTION = 2u;
static const uint R2_RAY_SHADOW = 4u;
static const uint R2_RAY_TRANSPORT = 8u;
static const uint R2_RAY_VIEWMODEL = 16u;
static const uint R2_RAY_RASTER_ONLY = 128u;
static const uint R2_GEOMETRY_SOURCE_DEPTH = 1u;
static const uint R2_LAYER_COVERAGE_TINT = 1u;
static const uint R2_LAYER_TEXTURE_SAMPLER = 2u;
static const uint R2_LAYER_TINT_SRGB = 4u;
static const uint R2_LAYER_EMISSION_TINT_SRGB = 8u;
static const uint R2_MATERIAL_CONDUCTOR = 1u;
static const uint R2_MATERIAL_EMITTER = 2u;
static const uint R2_MATERIAL_DISTANCE_ROUGHNESS = 4u;
static const uint R2_MATERIAL_THIN = 8u;
static const uint R2_ROLE_NONE = 0u;
static const uint R2_ROLE_VOLUME = 1u;
static const uint R2_ROLE_CONTACT = 2u;
static const uint R2_ROLE_CLIP = 3u;
static const uint R2_MEDIUM_AIR = 0u;
static const uint R2_MEDIUM_WATER = 1u;
static const uint R2_MEDIUM_GLASS = 2u;
static const uint R2_MEDIUM_ICE = 3u;
static const uint R2_HIT_HIT = 1u;
static const uint R2_HIT_FRONT = 2u;
static const uint R2_FRAME_PREVIOUS_VALID = 1u;
static const uint R2_FRAME_WRITE_HITS = 2u;
static const uint R2_FRAME_LIGHT_REUSE = 4u;
static const uint R2_FRAME_LIGHT_REUSE_VALID = 8u;
static const uint R2_FRAME_CAMERA_IN_WATER = 16u;
static const uint R2_FRAME_RAW_CLAMP = 64u;
static const uint R2_FRAME_INITIAL_MEDIA = 128u;
static const uint R2_FRAME_CLOUDS_VISIBLE = 256u;
static const uint R2_GUIDE_PATH_SAMPLED = 1u;
static const uint R2_GUIDE_PATH_GLOSSY = 2u;
static const uint R2_GUIDE_PATH_DELTA = 4u;
static const uint R2_GUIDE_PRIMARY_DIELECTRIC = 8u;
static const uint R2_GUIDE_PRIMARY_REFLECTED = 16u;
static const uint R2_GUIDE_PREFIX_TRUNCATED = 32u;
static const uint R2_GUIDE_TERMINAL_HIT = 64u;
static const uint R2_GUIDE_ENDPOINT_VALID = 128u;
static const uint R2_GUIDE_ENDPOINT_HIT = 256u;
static const uint R2_GUIDE_PLANAR_REFLECTION = 512u;
static const uint R2_GUIDE_REFLECTION_MOTION_VALID = 1024u;
static const uint R2_GUIDE_TERMINAL_MOVED = 2048u;
static const uint R2_GUIDE_TERMINAL_MOTION_INVALID = 4096u;
static const uint R2_GUIDE_MEDIUM_STATUS_SHIFT = 13u;
static const uint R2_GUIDE_MEDIUM_STATUS_MASK = 57344u;
static const uint R2_GUIDE_CLIPPED_MEDIUM = 65536u;
static const uint R2_GUIDE_MOTION_VALID = 131072u;
static const uint R2_GUIDE_PRIMARY_HIT = 262144u;
static const uint R2_COVERAGE_OPAQUE = 0u;
static const uint R2_COVERAGE_CUTOUT = 1u;
static const uint R2_COVERAGE_FILTER = 2u;
static const uint R2_COVERAGE_DIELECTRIC = 3u;
static const uint R2_SOURCE_POINT = 0u;
static const uint R2_SOURCE_QUAD = 1u;
static const uint R2_AUTOMATIC_HIT_COVERAGE_MASK = 9u;
static const uint R2_MATERIAL_COUNT = 70u;
static const uint R2_MEDIUM_CAPACITY = 4u;
static const uint R2_ATMOSPHERE_WIDTH = 512u;
static const uint R2_ATMOSPHERE_HEIGHT = 256u;
static const uint R2_STATE_WET = 1u;
static const uint R2_STATE_THIN = 2u;
static const uint R2_STATE_GLAZED = 4u;
static const uint R2_STATE_HURT_SHIFT = 8u;
static const uint R2_STATE_FLASH_SHIFT = 12u;
static const uint R2_STATE_LIGHT_SHIFT = 16u;
static const uint R2_STATE_TRANSLUCENT = 1048576u;
static const float R2_WATER_IOR = 1.333;
static const float3 R2_WATER_ABSORPTION = float3(0.35,0.1,0.06);
static const float3 R2_WATER_SCATTERING = float3(0.004,0.007,0.012);
static const float R2_GLASS_IOR = 1.5;
static const float3 R2_GLASS_ABSORPTION = float3(0.05,0.05,0.05);
static const float3 R2_GLASS_SCATTERING = float3(0.0,0.0,0.0);
static const float R2_ICE_IOR = 1.31;
static const float3 R2_ICE_ABSORPTION = float3(0.0,0.0,0.0);
static const float3 R2_ICE_SCATTERING = float3(0.01,0.015,0.025);
static const float R2_MEDIUM_MINIMUM_TINT = 0.04;
static const float R2_MEDIUM_MAXIMUM_TINT = 0.98;
static const float R2_MEDIUM_TINT_ABSORPTION = 0.7;
static const uint R2_RECONSTRUCTION_ITERATIONS = 1u;
static const uint R2_SOURCE_PREPARATION_COLUMNS = 65535u;
static const uint R2_PATH_QUEUE_COLUMNS = 256u;
static const uint R2_PATH_BOUNCES = 1u;
#define R2_THREADS_R2PRIMARYVISIBILITY_X 8
#define R2_THREADS_R2PRIMARYVISIBILITY_Y 8
#define R2_THREADS_R2PRIMARYVISIBILITY_Z 1
#define R2_BIND_TRANSPORT_FRAME 0
#define R2_BIND_TRANSPORT_WORLDAS 1
#define R2_BIND_TRANSPORT_HITS 2
#define R2_BIND_TRANSPORT_SIGNALS 3
#define R2_BIND_TRANSPORT_GUIDES 4
#define R2_BIND_TRANSPORT_HOSTDEPTH 5
#define R2_BIND_TRANSPORT_OUTPUT 6
#define R2_BIND_TRANSPORT_PREVIOUSLIGHTHISTORY 7
#define R2_BIND_TRANSPORT_CURRENTLIGHTHISTORY 8
#define R2_THREADS_R2COMPILEMATERIALS_X 64
#define R2_THREADS_R2COMPILEMATERIALS_Y 1
#define R2_THREADS_R2COMPILEMATERIALS_Z 1
#define R2_BIND_MATERIALS_DEFINITIONS 0
#define R2_THREADS_R2COMPILEATMOSPHERE_X 64
#define R2_THREADS_R2COMPILEATMOSPHERE_Y 1
#define R2_THREADS_R2COMPILEATMOSPHERE_Z 1
#define R2_BIND_ATMOSPHERE_CELLS 0
#define R2_THREADS_R2TEXTURECOPY_X 8
#define R2_THREADS_R2TEXTURECOPY_Y 8
#define R2_THREADS_R2TEXTURECOPY_Z 1
#define R2_BIND_TEXTURECOPY_COPY 0
#define R2_BIND_TEXTURECOPY_SOURCE 1
#define R2_BIND_TEXTURECOPY_PIXELS 2
#define R2_THREADS_R2TEXTURECOPYFLOAT_X 8
#define R2_THREADS_R2TEXTURECOPYFLOAT_Y 8
#define R2_THREADS_R2TEXTURECOPYFLOAT_Z 1
#define R2_BIND_TEXTURECOPYFLOAT_COPY 0
#define R2_BIND_TEXTURECOPYFLOAT_SOURCE 1
#define R2_BIND_TEXTURECOPYFLOAT_PIXELS 2
#define R2_THREADS_R2TEMPORAL_X 8
#define R2_THREADS_R2TEMPORAL_Y 8
#define R2_THREADS_R2TEMPORAL_Z 1
#define R2_THREADS_R2ATROUS_X 8
#define R2_THREADS_R2ATROUS_Y 8
#define R2_THREADS_R2ATROUS_Z 1
#define R2_BIND_RECONSTRUCTION_FRAME 0
#define R2_BIND_RECONSTRUCTION_SIGNALS 1
#define R2_BIND_RECONSTRUCTION_GUIDES 2
#define R2_BIND_RECONSTRUCTION_PREVIOUS 3
#define R2_BIND_RECONSTRUCTION_HISTORY 4
#define R2_BIND_RECONSTRUCTION_OUTPUT 5
#define R2_THREADS_R2METER_X 256
#define R2_THREADS_R2METER_Y 1
#define R2_THREADS_R2METER_Z 1
#define R2_THREADS_R2DISPLAY_X 8
#define R2_THREADS_R2DISPLAY_Y 8
#define R2_THREADS_R2DISPLAY_Z 1
#define R2_BIND_PRESENTATION_FRAME 0
#define R2_BIND_PRESENTATION_COMPOSITE 1
#define R2_BIND_PRESENTATION_PREVIOUSEXPOSURE 2
#define R2_BIND_PRESENTATION_EXPOSURE 3
#define R2_BIND_PRESENTATION_DISPLAYOUTPUT 4
#define R2_BIND_HANDOFF_FRAME 0
#define R2_BIND_HANDOFF_COLOR 1
#define R2_BIND_HANDOFF_HOSTDEPTH 2
#define R2_BIND_HANDOFF_GUIDES 3
#define R2_THREADS_R2RESOLVE_X 8
#define R2_THREADS_R2RESOLVE_Y 8
#define R2_THREADS_R2RESOLVE_Z 1
#define R2_BIND_RESOLVE_FRAME 0
#define R2_BIND_RESOLVE_INPUT 1
#define R2_BIND_RESOLVE_OUTPUT 2
#define R2_THREADS_R2IMPORT_X 8
#define R2_THREADS_R2IMPORT_Y 8
#define R2_THREADS_R2IMPORT_Z 1
#define R2_BIND_IMPORT_FRAME 0
#define R2_BIND_IMPORT_COMPOSITION 1
#define R2_BIND_IMPORT_COLOR 2
#define R2_THREADS_R2PREPARESOURCES_X 64
#define R2_THREADS_R2PREPARESOURCES_Y 1
#define R2_THREADS_R2PREPARESOURCES_Z 1
#define R2_BIND_SOURCEPREPARATION_FRAME 0
#define R2_THREADS_R2DECODEPRODUCER_X 64
#define R2_THREADS_R2DECODEPRODUCER_Y 1
#define R2_THREADS_R2DECODEPRODUCER_Z 1
#define R2_BIND_PRODUCERDECODE_DECODE 0
#define R2_BIND_PRODUCERDECODE_SOURCE 1
#define R2_BIND_PRODUCERDECODE_TINT 2
#define R2_BIND_PRODUCERDECODE_FACTS 3
#define R2_BIND_PRODUCERDECODE_SURFACES 4
#define R2_BIND_PRODUCERDECODE_POSITIONS 5
#define R2_BIND_PRODUCERDECODE_CORNERS 6
#define R2_BIND_PRODUCERDECODE_INDICES 7
#define R2_BIND_PRODUCERDECODE_TRIANGLES 8
#endif
