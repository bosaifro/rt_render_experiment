#include "minimal/common.slang"
#include "abi/resources/textureCopy.slang"

[shader("compute")]
[numthreads(R2_THREADS_R2TEXTURECOPY_X, R2_THREADS_R2TEXTURECOPY_Y, R2_THREADS_R2TEXTURECOPY_Z)]
void r2TextureCopy(uint3 thread : SV_DispatchThreadID) {
    uint2 pixel = thread.xy;
    uint width = Copy.extentMipOffset.x, height = Copy.extentMipOffset.y;
    if (pixel.x >= width || pixel.y >= height) return;
    float2 uv = (float2(pixel) + 0.5) / float2(max(width, 1u), max(height, 1u));
    float4 value = Source.SampleLevel(uv, float(Copy.extentMipOffset.z));
    uint4 quantised = uint4(saturate(value) * 255.0 + 0.5);
    Pixels[Copy.extentMipOffset.w + pixel.y * width + pixel.x] =
        quantised.x | (quantised.y << 8u) | (quantised.z << 16u) | (quantised.w << 24u);
}
