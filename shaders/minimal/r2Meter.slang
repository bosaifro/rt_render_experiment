#include "abi/resources/presentation.slang"

[shader("compute")]
[numthreads(R2_THREADS_R2METER_X, R2_THREADS_R2METER_Y, R2_THREADS_R2METER_Z)]
void r2Meter(uint3 thread : SV_DispatchThreadID) {
    // Template: fixed exposure. Add exposure metering here when needed.
    if (thread.x == 0u) Exposure[0] = float2(1.0, 0.0);
}
