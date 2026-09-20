#ifndef R2_RESOURCES_TRANSPORT
#define R2_RESOURCES_TRANSPORT
#include "abi/records.slang"
[[vk::binding(R2_BIND_TRANSPORT_FRAME, 0)]] ConstantBuffer<FrameRecord> Frame;
[[vk::binding(R2_BIND_TRANSPORT_WORLDAS, 0)]] RaytracingAccelerationStructure WorldAS;
[[vk::binding(R2_BIND_TRANSPORT_HITS, 0)]] RWStructuredBuffer<HitRecord> Hits;
[[vk::binding(R2_BIND_TRANSPORT_SIGNALS, 0)]] RWStructuredBuffer<SignalRecord> Signals;
[[vk::binding(R2_BIND_TRANSPORT_GUIDES, 0)]] RWStructuredBuffer<GuideRecord> Guides;
[[vk::binding(R2_BIND_TRANSPORT_HOSTDEPTH, 0)]] RWStructuredBuffer<float> HostDepth;
[[vk::binding(R2_BIND_TRANSPORT_OUTPUT, 0)]] RWStructuredBuffer<float4> Output;
[[vk::binding(R2_BIND_TRANSPORT_PREVIOUSLIGHTHISTORY, 0)]] StructuredBuffer<LightHistoryRecord> PreviousLightHistory;
[[vk::binding(R2_BIND_TRANSPORT_CURRENTLIGHTHISTORY, 0)]] RWStructuredBuffer<LightHistoryRecord> CurrentLightHistory;
[[vk::binding(0, 1)]] Sampler2D Textures[];
#endif
