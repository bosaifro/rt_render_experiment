#ifndef R2_RESOURCES_PRODUCERDECODE
#define R2_RESOURCES_PRODUCERDECODE
#include "abi/records.slang"
[[vk::binding(R2_BIND_PRODUCERDECODE_DECODE, 0)]] ConstantBuffer<DispatchRecord> Decode;
[[vk::binding(R2_BIND_PRODUCERDECODE_SOURCE, 0)]] StructuredBuffer<uint> Source;
[[vk::binding(R2_BIND_PRODUCERDECODE_TINT, 0)]] StructuredBuffer<uint> Tint;
[[vk::binding(R2_BIND_PRODUCERDECODE_FACTS, 0)]] StructuredBuffer<TriangleRecord> Facts;
[[vk::binding(R2_BIND_PRODUCERDECODE_SURFACES, 0)]] StructuredBuffer<SurfaceRecord> Surfaces;
[[vk::binding(R2_BIND_PRODUCERDECODE_POSITIONS, 0)]] RWStructuredBuffer<PositionRecord> Positions;
[[vk::binding(R2_BIND_PRODUCERDECODE_CORNERS, 0)]] RWStructuredBuffer<CornerRecord> Corners;
[[vk::binding(R2_BIND_PRODUCERDECODE_INDICES, 0)]] RWStructuredBuffer<uint> Indices;
[[vk::binding(R2_BIND_PRODUCERDECODE_TRIANGLES, 0)]] RWStructuredBuffer<TriangleRecord> Triangles;
#endif
