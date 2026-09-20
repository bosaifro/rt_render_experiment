#include "minimal/common.slang"
#include "abi/resources/textureCopyFloat.slang"

[shader("compute")]
[numthreads(R2_THREADS_R2TEXTURECOPYFLOAT_X, R2_THREADS_R2TEXTURECOPYFLOAT_Y, R2_THREADS_R2TEXTURECOPYFLOAT_Z)]
void r2TextureCopyFloat(uint3 thread : SV_DispatchThreadID) {
    uint2 pixel = thread.xy;
    uint width = Copy.extentMipOffset.x, height = Copy.extentMipOffset.y;
    if (pixel.x >= width || pixel.y >= height) return;
    float2 uv = (float2(pixel) + 0.5) / float2(max(width, 1u), max(height, 1u));
    Pixels[Copy.extentMipOffset.w + pixel.y * width + pixel.x] = Source.SampleLevel(uv, float(Copy.extentMipOffset.z));
}
