#ifndef R2_RESOURCES_TEXTURECOPYFLOAT
#define R2_RESOURCES_TEXTURECOPYFLOAT
#include "abi/records.slang"
[[vk::binding(R2_BIND_TEXTURECOPYFLOAT_COPY, 0)]] ConstantBuffer<TextureCopyRecord> Copy;
[[vk::binding(R2_BIND_TEXTURECOPYFLOAT_SOURCE, 0)]] Sampler2D Source;
[[vk::binding(R2_BIND_TEXTURECOPYFLOAT_PIXELS, 0)]] RWStructuredBuffer<float4> Pixels;
#endif
