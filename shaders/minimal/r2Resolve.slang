#include "minimal/common.slang"
#include "abi/resources/resolve.slang"

[shader("compute")]
[numthreads(R2_THREADS_R2RESOLVE_X, R2_THREADS_R2RESOLVE_Y, R2_THREADS_R2RESOLVE_Z)]
void r2Resolve(uint3 thread : SV_DispatchThreadID) {
    uint2 pixel = thread.xy;
    if (pixel.x >= Frame.outputExtent.x || pixel.y >= Frame.outputExtent.y) return;

    float2 scale = float2(Frame.extent.xy) / float2(max(Frame.outputExtent.xy, uint2(1u, 1u)));
    float2 source = (float2(pixel) + 0.5) * scale - 0.5;
    int2 base = int2(floor(source));
    float2 fraction = saturate(source - float2(base));

    int2 limit = int2(Frame.extent.xy) - 1;
    int2 p0 = clamp(base, int2(0, 0), limit);
    int2 p1 = clamp(base + int2(1, 1), int2(0, 0), limit);

    float4 a = Input[uint(p0.y) * Frame.extent.x + uint(p0.x)];
    float4 b = Input[uint(p0.y) * Frame.extent.x + uint(p1.x)];
    float4 c = Input[uint(p1.y) * Frame.extent.x + uint(p0.x)];
    float4 d = Input[uint(p1.y) * Frame.extent.x + uint(p1.x)];

    Output[pixel.y * Frame.outputExtent.x + pixel.x] = lerp(lerp(a, b, fraction.x), lerp(c, d, fraction.x), fraction.y);
}
