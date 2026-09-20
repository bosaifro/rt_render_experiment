#include "minimal/common.slang"
#include "abi/resources/handoff.slang"

struct R2ViewmodelTarget { float4 color : SV_Target0; float depth : SV_Depth; };

[shader("fragment")]
R2ViewmodelTarget r2ViewmodelHandoff(float4 position : SV_Position) {
    uint2 pixel = uint2(position.xy);
    uint index = min(pixel.y * Frame.outputExtent.x + pixel.x, Frame.outputExtent.x * Frame.outputExtent.y - 1u);
    GuideRecord guide = Guides[index];
    if ((guide.path.x & R2_GUIDE_PRIMARY_HIT) == 0u) discard;
    R2ViewmodelTarget result;
    result.color = float4(max(Color[index].rgb, 0.0), 1.0);
    result.depth = HostDepth[index];
    return result;
}
