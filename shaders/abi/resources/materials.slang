#ifndef R2_RESOURCES_MATERIALS
#define R2_RESOURCES_MATERIALS
#include "abi/records.slang"
[[vk::binding(R2_BIND_MATERIALS_DEFINITIONS, 0)]] RWStructuredBuffer<MaterialRecord> Definitions;
#endif
