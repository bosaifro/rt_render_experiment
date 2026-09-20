#ifndef R2_RESOURCES_RECONSTRUCTION
#define R2_RESOURCES_RECONSTRUCTION
#include "abi/records.slang"
[[vk::binding(R2_BIND_RECONSTRUCTION_FRAME, 0)]] ConstantBuffer<FrameRecord> Frame;
[[vk::binding(R2_BIND_RECONSTRUCTION_SIGNALS, 0)]] StructuredBuffer<SignalRecord> Signals;
[[vk::binding(R2_BIND_RECONSTRUCTION_GUIDES, 0)]] StructuredBuffer<GuideRecord> Guides;
[[vk::binding(R2_BIND_RECONSTRUCTION_PREVIOUS, 0)]] StructuredBuffer<HistoryRecord> Previous;
[[vk::binding(R2_BIND_RECONSTRUCTION_HISTORY, 0)]] RWStructuredBuffer<HistoryRecord> History;
[[vk::binding(R2_BIND_RECONSTRUCTION_OUTPUT, 0)]] RWStructuredBuffer<float4> Output;
[[vk::push_constant]] DispatchRecord Dispatch;
#endif
