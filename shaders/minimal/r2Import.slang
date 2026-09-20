#include "minimal/common.slang"
#include "abi/resources/import.slang"

[shader("compute")]
[numthreads(R2_THREADS_R2IMPORT_X, R2_THREADS_R2IMPORT_Y, R2_THREADS_R2IMPORT_Z)]
void r2Import(uint3 thread : SV_DispatchThreadID) {
    uint2 pixel = thread.xy;
    if (pixel.x >= Frame.outputExtent.x || pixel.y >= Frame.outputExtent.y) return;
    Color[pixel.y * Frame.outputExtent.x + pixel.x] = float4(max(Composition[pixel].rgb, 0.0), 1.0);
}
