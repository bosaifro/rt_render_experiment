#ifndef R2_MINIMAL_SCENE
#define R2_MINIMAL_SCENE
#include "minimal/common.slang"
#include "abi/resources/transport.slang"

struct R2Surface {
    float3 position;
    float3 geometric;
    float3 shading;
    float3 albedo;
    float3 emission;
    float roughness;
    float metalness;
    float3 reflectance;
    float distance;
    float hostLight;
    uint coverage;
    uint surfaceToken;
    bool hit;
    bool front;
};

float4 r2Unproject(float2 ndc, float depth) {
    float4 point = float4(ndc, depth, 1.0);
    return float4(dot(Frame.inverseViewProjection[0], point), dot(Frame.inverseViewProjection[1], point),
                  dot(Frame.inverseViewProjection[2], point), dot(Frame.inverseViewProjection[3], point));
}

float4 r2Project(float3 world) {
    float4 point = float4(world, 1.0);
    return float4(dot(Frame.viewProjection[0], point), dot(Frame.viewProjection[1], point),
                  dot(Frame.viewProjection[2], point), dot(Frame.viewProjection[3], point));
}

float4 r2ProjectPrevious(float3 world) {
    float4 point = float4(world, 1.0);
    return float4(dot(Frame.previousViewProjection[0], point), dot(Frame.previousViewProjection[1], point),
                  dot(Frame.previousViewProjection[2], point), dot(Frame.previousViewProjection[3], point));
}

float3 r2CameraRay(uint2 pixel, float2 offset) {
    float2 ndc = ((float2(pixel) + offset) / float2(Frame.extent.xy)) * 2.0 - 1.0;
    float4 target = r2Unproject(ndc, 0.5);
    return normalize(target.xyz / target.w - Frame.eye.xyz);
}

float3 r2Sample(uint slot, float2 uv, bool linearEncoding) {
    float3 value = Textures[NonUniformResourceIndex(slot)].SampleLevel(uv, 0.0).rgb;
    return linearEncoding ? value : r2SrgbToLinear(value);
}

float r2Coverage(GeometryRecord geometry, uint primitive, float2 bary, out uint coverageKind, out float cutoff) {
    TriangleRecord triangle = ((TriangleRecord*)geometry.triangles)[primitive];
    SurfaceRecord surface = ((SurfaceRecord*)geometry.surfaces)[triangle.indicesSurface.w];
    coverageKind = surface.definition.z;
    cutoff = surface.coverage.x;
    if (coverageKind == R2_COVERAGE_OPAQUE) return 1.0;
    CornerRecord* corners = (CornerRecord*)geometry.corners;
    float3 weights = float3(1.0 - bary.x - bary.y, bary.x, bary.y);
    float2 uv = corners[triangle.indicesSurface.x].uv * weights.x
              + corners[triangle.indicesSurface.y].uv * weights.y
              + corners[triangle.indicesSurface.z].uv * weights.z;
    return Textures[NonUniformResourceIndex(surface.layers.x)].SampleLevel(uv, 0.0).a;
}

bool r2Accepts(uint slot, uint primitive, float2 bary) {
    GeometryRecord geometry = ((GeometryRecord*)Frame.geometries)[slot];
    uint coverageKind; float cutoff;
    float alpha = r2Coverage(geometry, primitive, bary, coverageKind, cutoff);
    if (coverageKind == R2_COVERAGE_CUTOUT) return alpha >= cutoff;
    if (coverageKind == R2_COVERAGE_FILTER) return false;
    return true;
}

R2Surface r2Decode(uint slot, uint primitive, float2 bary, float3 origin, float3 direction, float distance, float3x4 objectToWorld, float3x4 worldToObject, bool front) {
    R2Surface result;
    GeometryRecord geometry = ((GeometryRecord*)Frame.geometries)[slot];
    TriangleRecord triangle = ((TriangleRecord*)geometry.triangles)[primitive];
    SurfaceRecord surface = ((SurfaceRecord*)geometry.surfaces)[triangle.indicesSurface.w];
    CornerRecord* corners = (CornerRecord*)geometry.corners;
    CornerRecord a = corners[triangle.indicesSurface.x];
    CornerRecord b = corners[triangle.indicesSurface.y];
    CornerRecord c = corners[triangle.indicesSurface.z];
    float3 weights = float3(1.0 - bary.x - bary.y, bary.x, bary.y);
    float2 uv = a.uv * weights.x + b.uv * weights.y + c.uv * weights.z;
    float4 tint = a.tint * weights.x + b.tint * weights.y + c.tint * weights.z;

    PositionRecord* positions = (PositionRecord*)geometry.positions;
    float3 p0 = positions[triangle.indicesSurface.x].value.xyz;
    float3 p1 = positions[triangle.indicesSurface.y].value.xyz;
    float3 p2 = positions[triangle.indicesSurface.z].value.xyz;
    float3 objectNormal = cross(p1 - p0, p2 - p0);
    float3 shadingObject = a.normal.xyz * weights.x + b.normal.xyz * weights.y + c.normal.xyz * weights.z;
    if (dot(shadingObject, shadingObject) < 1e-12) shadingObject = objectNormal;

    float3 geometric = normalize(float3(
        dot(worldToObject[0].xyz, objectNormal), dot(worldToObject[1].xyz, objectNormal), dot(worldToObject[2].xyz, objectNormal)));
    float3 shading = normalize(float3(
        dot(worldToObject[0].xyz, shadingObject), dot(worldToObject[1].xyz, shadingObject), dot(worldToObject[2].xyz, shadingObject)));
    if (!front) { geometric = -geometric; shading = -shading; }
    if (dot(geometric, direction) > 0.0) { geometric = -geometric; shading = -shading; }

    uint encodings = surface.textures.z;
    float3 base = r2Sample(surface.textures.x, uv, (encodings & 1u) != 0u);
    float3 vertexTint = (surface.layers.w & R2_LAYER_TINT_SRGB) != 0u ? r2SrgbToLinear(tint.rgb) : tint.rgb;
    result.albedo = saturate(base * vertexTint * surface.colorFactor.rgb);

    float3 emission = float3(0.0);
    if (surface.textures.y != 0xFFFFFFFFu) {
        float3 emissionTexture = r2Sample(surface.textures.y, uv, (encodings & 2u) != 0u);
        float4 emissionTint = a.emissionTint * weights.x + b.emissionTint * weights.y + c.emissionTint * weights.z;
        float3 scaled = (surface.layers.w & R2_LAYER_EMISSION_TINT_SRGB) != 0u ? r2SrgbToLinear(emissionTint.rgb) : emissionTint.rgb;
        emission = emissionTexture * scaled;
    }
    emission += result.albedo * surface.emissionFactor.rgb;

    uint properties = a.properties;
    float blockLight = float((properties >> R2_STATE_LIGHT_SHIFT) & 15u) * (1.0 / 15.0);
    float skyLight = float((properties >> (R2_STATE_LIGHT_SHIFT + 4u)) & 15u) * (1.0 / 15.0);

    MaterialRecord material = ((MaterialRecord*)Frame.materials)[surface.definition.x];
    result.roughness = clamp(material.finish.x, 0.035, 1.0);
    result.metalness = saturate(material.finish.y);
    float reflectance = material.finish.z;
    if ((properties & R2_STATE_WET) != 0u) { result.roughness = min(result.roughness, 0.18); reflectance = max(reflectance, 0.05); }
    result.reflectance = lerp(float3(reflectance), result.albedo, result.metalness);
    result.albedo *= (1.0 - result.metalness);
    if ((material.classification.x & R2_MATERIAL_EMITTER) != 0u)
        emission += material.emissionSpectrumIntensity.rgb * material.emissionSpectrumIntensity.w;

    result.emission = emission;
    result.position = origin + direction * distance;
    result.geometric = geometric;
    result.shading = shading;
    result.distance = distance;
    result.hostLight = max(blockLight * blockLight, skyLight * skyLight * 0.35);
    result.coverage = surface.definition.z;
    result.surfaceToken = surface.textures.w;
    result.hit = true;
    result.front = front;
    return result;
}

R2Surface r2Miss() {
    R2Surface result;
    result.position = float3(0.0); result.geometric = float3(0.0, 1.0, 0.0); result.shading = float3(0.0, 1.0, 0.0);
    result.albedo = float3(0.0); result.emission = float3(0.0); result.roughness = 1.0; result.metalness = 0.0;
    result.reflectance = float3(0.0); result.distance = Frame.eye.w; result.hostLight = 0.0;
    result.coverage = R2_COVERAGE_OPAQUE; result.surfaceToken = 0u; result.hit = false; result.front = true;
    return result;
}

R2Surface r2Trace(float3 origin, float3 direction, float minimum, float maximum, uint mask) {
    RayDesc ray; ray.Origin = origin; ray.Direction = direction; ray.TMin = minimum; ray.TMax = maximum;
    RayQuery<RAY_FLAG_NONE> query;
    query.TraceRayInline(WorldAS, RAY_FLAG_NONE, mask, ray);
    while (query.Proceed()) {
        if (query.CandidateType() == CANDIDATE_NON_OPAQUE_TRIANGLE
            && r2Accepts(query.CandidateInstanceID(), query.CandidatePrimitiveIndex(), query.CandidateTriangleBarycentrics()))
            query.CommitNonOpaqueTriangleHit();
    }
    if (query.CommittedStatus() != COMMITTED_TRIANGLE_HIT) return r2Miss();
    return r2Decode(query.CommittedInstanceID(), query.CommittedPrimitiveIndex(), query.CommittedTriangleBarycentrics(),
        origin, direction, query.CommittedRayT(), query.CommittedObjectToWorld3x4(), query.CommittedWorldToObject3x4(),
        query.CommittedTriangleFrontFace());
}

bool r2Occluded(float3 origin, float3 direction, float maximum) {
    RayDesc ray; ray.Origin = origin; ray.Direction = direction; ray.TMin = 1e-3; ray.TMax = maximum;
    RayQuery<RAY_FLAG_NONE> query;
    query.TraceRayInline(WorldAS, RAY_FLAG_NONE, R2_RAY_SHADOW, ray);
    while (query.Proceed()) {
        if (query.CandidateType() == CANDIDATE_NON_OPAQUE_TRIANGLE
            && r2Accepts(query.CandidateInstanceID(), query.CandidatePrimitiveIndex(), query.CandidateTriangleBarycentrics()))
            query.CommitNonOpaqueTriangleHit();
    }
    return query.CommittedStatus() == COMMITTED_TRIANGLE_HIT;
}

float3 r2Offset(float3 position, float3 normal) { return position + normal * (1e-3 + abs(position.y) * 1e-5); }

#endif
