#include "abi/resources/reconstruction.slang"

[shader("compute")]
[numthreads(R2_THREADS_R2ATROUS_X, R2_THREADS_R2ATROUS_Y, R2_THREADS_R2ATROUS_Z)]
void r2Atrous(uint3 thread : SV_DispatchThreadID) {
    uint2 pixel = thread.xy;
    if (pixel.x >= Frame.extent.x || pixel.y >= Frame.extent.y) return;
    uint index = pixel.y * Frame.extent.x + pixel.x;

    // Template: combine the signals without filtering neighboring pixels.
    GuideRecord guide = Guides[index];
    HistoryRecord sample = History[index];
    float3 radiance = sample.media.rgb;
    if ((guide.path.x & R2_GUIDE_PRIMARY_HIT) != 0u)
        radiance += guide.diffuseAlbedo.rgb * sample.diffuse.rgb + sample.glossy.rgb;
    Output[index] = float4(max(radiance, 0.0), 1.0);
}
