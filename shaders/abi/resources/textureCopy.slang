#ifndef R2_RESOURCES_TEXTURECOPY
#define R2_RESOURCES_TEXTURECOPY
#include "abi/records.slang"
[[vk::binding(R2_BIND_TEXTURECOPY_COPY, 0)]] ConstantBuffer<TextureCopyRecord> Copy;
[[vk::binding(R2_BIND_TEXTURECOPY_SOURCE, 0)]] Sampler2D Source;
[[vk::binding(R2_BIND_TEXTURECOPY_PIXELS, 0)]] RWStructuredBuffer<uint> Pixels;
#endif
