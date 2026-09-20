#ifndef R2_RESOURCES_ATMOSPHERE
#define R2_RESOURCES_ATMOSPHERE
#include "abi/records.slang"
[[vk::binding(R2_BIND_ATMOSPHERE_CELLS, 0)]] RWStructuredBuffer<AtmosphereCellRecord> Cells;
#endif
