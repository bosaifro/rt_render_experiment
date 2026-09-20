#include "minimal/common.slang"
#include "abi/resources/presentation.slang"
#include "template.slang"

[shader("compute")]
[numthreads(R2_THREADS_R2DISPLAY_X, R2_THREADS_R2DISPLAY_Y, R2_THREADS_R2DISPLAY_Z)]
void r2Display(uint3 thread : SV_DispatchThreadID) {
    uint2 pixel = thread.xy;
    if (pixel.x >= Frame.outputExtent.x || pixel.y >= Frame.outputExtent.y) return;
    uint index = pixel.y * Frame.outputExtent.x + pixel.x;

    float3 radiance = max(Composite[index].rgb, 0.0);
    float exposure = Exposure[0].x > 0.0 ? Exposure[0].x : 1.0;
    float3 mapped = r2TemplateDisplay(radiance * exposure);
    DisplayOutput[index] = float4(r2LinearToSrgb(mapped), 1.0);
}
