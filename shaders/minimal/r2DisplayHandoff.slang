#include "minimal/common.slang"
#include "abi/resources/handoff.slang"

[shader("fragment")]
float4 r2DisplayHandoff(float4 position : SV_Position) : SV_Target0 {
    uint2 pixel = uint2(position.xy);
    uint index = min(pixel.y * Frame.outputExtent.x + pixel.x, Frame.outputExtent.x * Frame.outputExtent.y - 1u);
    return float4(saturate(Color[index].rgb), 1.0);
}
