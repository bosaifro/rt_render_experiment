#ifndef R2_RESOURCES_RESOLVE
#define R2_RESOURCES_RESOLVE
#include "abi/records.slang"
[[vk::binding(R2_BIND_RESOLVE_FRAME, 0)]] ConstantBuffer<FrameRecord> Frame;
[[vk::binding(R2_BIND_RESOLVE_INPUT, 0)]] StructuredBuffer<float4> Input;
[[vk::binding(R2_BIND_RESOLVE_OUTPUT, 0)]] RWStructuredBuffer<float4> Output;
#endif
