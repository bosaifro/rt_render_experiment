#include "minimal/common.slang"
#include "abi/resources/handoff.slang"

struct R2HandoffVertex { float4 position : SV_Position; };

[shader("vertex")]
R2HandoffVertex r2HandoffVertex(uint vertexId : SV_VertexID) {
    R2HandoffVertex result;
    float2 corner = float2(float((vertexId << 1u) & 2u), float(vertexId & 2u));
    result.position = float4(corner * 2.0 - 1.0, 0.0, 1.0);
    return result;
}
