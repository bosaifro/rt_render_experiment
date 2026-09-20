#include "minimal/common.slang"
#include "abi/resources/producerDecode.slang"

static const uint R2_LAYOUT_BLOCK28 = 0u;
static const uint R2_TOPOLOGY_QUADS = 0u;

float4 r2UnpackUnorm(uint value) {
    return float4(float(value & 255u), float((value >> 8u) & 255u), float((value >> 16u) & 255u), float((value >> 24u) & 255u)) * (1.0 / 255.0);
}

float4 r2UnpackArgb(uint value) {
    return float4(float((value >> 16u) & 255u), float((value >> 8u) & 255u), float(value & 255u), float((value >> 24u) & 255u)) * (1.0 / 255.0);
}

float3 r2UnpackSnorm(uint value) {
    int3 raw = int3(int(value << 24u) >> 24, int(value << 16u) >> 24, int(value << 8u) >> 24);
    return clamp(float3(raw) * (1.0 / 127.0), -1.0, 1.0);
}

[shader("compute")]
[numthreads(R2_THREADS_R2DECODEPRODUCER_X, R2_THREADS_R2DECODEPRODUCER_Y, R2_THREADS_R2DECODEPRODUCER_Z)]
void r2DecodeProducer(uint3 thread : SV_DispatchThreadID) {
    uint vertex = thread.x;
    uint vertexCount = Decode.control.x;
    if (vertex >= vertexCount) return;

    uint layout = Decode.control.y;
    uint topology = Decode.control.z;
    uint flags = Decode.control.w;
    bool uniformPrimitives = (flags & 2u) != 0u;

    uint words = layout == R2_LAYOUT_BLOCK28 ? 7u : 9u;
    uint corners = topology == R2_TOPOLOGY_QUADS ? 4u : 3u;
    uint base = vertex * words;

    uint geometric = vertex / corners;
    uint corner = vertex - geometric * corners;
    uint metadata = uniformPrimitives ? 0u : geometric;

    float3 position = float3(asfloat(Source[base]), asfloat(Source[base + 1u]), asfloat(Source[base + 2u]));
    float4 vertexColor = r2UnpackUnorm(Source[base + 3u]);
    float2 uv = float2(asfloat(Source[base + 4u]), asfloat(Source[base + 5u]));

    uint lightWord = layout == R2_LAYOUT_BLOCK28 ? Source[base + 6u] : Source[base + 7u];
    uint blockLight = min((lightWord & 0xFFFFu) / 16u, 15u);
    uint skyLight = min(((lightWord >> 16u) & 0xFFFFu) / 16u, 15u);

    float3 normal = float3(0.0, 0.0, 0.0);
    if (layout != R2_LAYOUT_BLOCK28) normal = r2UnpackSnorm(Source[base + 8u]);

    float4 factor = r2UnpackArgb(Tint[metadata * corners + corner]);
    SurfaceRecord surface = Surfaces[metadata];

    PositionRecord positionRecord;
    positionRecord.value = float4(position, 1.0);
    Positions[vertex] = positionRecord;

    CornerRecord record = (CornerRecord)0;
    record.uv = uv;
    record.material = surface.definition.x;
    record.properties = surface.definition.y | (blockLight << R2_STATE_LIGHT_SHIFT) | (skyLight << (R2_STATE_LIGHT_SHIFT + 4u));
    record.tint = float4(vertexColor.rgb * factor.rgb, vertexColor.a * factor.a);
    record.normal = float4(normal, 0.0);
    record.tangent = float4(1.0, 0.0, 0.0, 1.0);
    record.layerUv = float4(uv, 0.0, 0.0);
    record.emissionTint = record.tint;
    Corners[vertex] = record;

    if (corner != 0u) return;

    TriangleRecord facts = Facts[metadata];
    bool active = facts.indicesSurface.x != 0u;
    uint trianglesPerPrimitive = topology == R2_TOPOLOGY_QUADS ? 2u : 1u;
    uint firstTriangle = geometric * trianglesPerPrimitive;

    uint3 layouts[2];
    if (topology == R2_TOPOLOGY_QUADS) {
        layouts[0] = uint3(0u, 1u, 2u);
        layouts[1] = uint3(2u, 3u, 0u);
    } else {
        layouts[0] = uint3(0u, 1u, 2u);
        layouts[1] = uint3(0u, 0u, 0u);
    }

    for (uint i = 0u; i < trianglesPerPrimitive; i++) {
        uint3 indices = active ? vertex + layouts[i] : uint3(vertex, vertex, vertex);
        uint triangle = firstTriangle + i;
        Indices[triangle * 3u] = indices.x;
        Indices[triangle * 3u + 1u] = indices.y;
        Indices[triangle * 3u + 2u] = indices.z;
        TriangleRecord output = facts;
        output.indicesSurface = uint4(indices, metadata);
        Triangles[triangle] = output;
    }
}
