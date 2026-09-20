#ifndef R2_RESOURCES_HANDOFF
#define R2_RESOURCES_HANDOFF
#include "abi/records.slang"
[[vk::binding(R2_BIND_HANDOFF_FRAME, 0)]] ConstantBuffer<FrameRecord> Frame;
[[vk::binding(R2_BIND_HANDOFF_COLOR, 0)]] StructuredBuffer<float4> Color;
[[vk::binding(R2_BIND_HANDOFF_HOSTDEPTH, 0)]] StructuredBuffer<float> HostDepth;
[[vk::binding(R2_BIND_HANDOFF_GUIDES, 0)]] StructuredBuffer<GuideRecord> Guides;
#endif
