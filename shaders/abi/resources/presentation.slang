#ifndef R2_RESOURCES_PRESENTATION
#define R2_RESOURCES_PRESENTATION
#include "abi/records.slang"
[[vk::binding(R2_BIND_PRESENTATION_FRAME, 0)]] ConstantBuffer<FrameRecord> Frame;
[[vk::binding(R2_BIND_PRESENTATION_COMPOSITE, 0)]] StructuredBuffer<float4> Composite;
[[vk::binding(R2_BIND_PRESENTATION_PREVIOUSEXPOSURE, 0)]] StructuredBuffer<float2> PreviousExposure;
[[vk::binding(R2_BIND_PRESENTATION_EXPOSURE, 0)]] RWStructuredBuffer<float2> Exposure;
[[vk::binding(R2_BIND_PRESENTATION_DISPLAYOUTPUT, 0)]] RWStructuredBuffer<float4> DisplayOutput;
#endif
