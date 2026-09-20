#ifndef R2_RESOURCES_IMPORT
#define R2_RESOURCES_IMPORT
#include "abi/records.slang"
[[vk::binding(R2_BIND_IMPORT_FRAME, 0)]] ConstantBuffer<FrameRecord> Frame;
[[vk::binding(R2_BIND_IMPORT_COMPOSITION, 0)]] [[vk::image_format("rgba16f")]] RWTexture2D<float4> Composition;
[[vk::binding(R2_BIND_IMPORT_COLOR, 0)]] RWStructuredBuffer<float4> Color;
#endif
