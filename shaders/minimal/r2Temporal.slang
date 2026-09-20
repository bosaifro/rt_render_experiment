#include "minimal/common.slang"
#include "abi/resources/reconstruction.slang"

[shader("compute")]
[numthreads(R2_THREADS_R2TEMPORAL_X, R2_THREADS_R2TEMPORAL_Y, R2_THREADS_R2TEMPORAL_Z)]
void r2Temporal(uint3 thread : SV_DispatchThreadID) {
    uint2 pixel = thread.xy;
    if (pixel.x >= Frame.extent.x || pixel.y >= Frame.extent.y) return;
    uint index = pixel.y * Frame.extent.x + pixel.x;

    // Template: copy this frame. Add history reprojection here when needed.
    SignalRecord signal = Signals[index];
    GuideRecord guide = Guides[index];
    HistoryRecord result = (HistoryRecord)0;
    result.normalDepth = guide.normalDepth;
    result.identity = guide.identity;
    result.path = guide.path;
    result.diffuse = signal.diffuse;
    result.glossy = signal.glossy;
    result.media = signal.deterministic;
    result.momentsA = float4(r2Luminance(signal.diffuse.rgb), r2Luminance(signal.diffuse.rgb * signal.diffuse.rgb), 0.0, 1.0);
    History[index] = result;
}
