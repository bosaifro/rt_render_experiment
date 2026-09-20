#include "minimal/scene.slang"
#include "template.slang"

// Engine adapter: one primary ray, then the hooks in template.slang.
[shader("compute")]
[numthreads(R2_THREADS_R2PRIMARYVISIBILITY_X, R2_THREADS_R2PRIMARYVISIBILITY_Y, R2_THREADS_R2PRIMARYVISIBILITY_Z)]
void r2PrimaryVisibility(uint3 thread : SV_DispatchThreadID) {
    uint2 pixel = thread.xy;
    if (pixel.x >= Frame.extent.x || pixel.y >= Frame.extent.y) return;
    uint index = pixel.y * Frame.extent.x + pixel.x;

    float3 origin = Frame.eye.xyz;
    float3 direction = r2CameraRay(pixel, float2(0.5, 0.5) + Frame.jitter.xy * 0.5);
    SignalRecord signal = (SignalRecord)0;
    GuideRecord guide = (GuideRecord)0;
    HitRecord hitRecord = (HitRecord)0;
    R2Surface surface = r2Trace(origin, direction, 1e-3, Frame.eye.w, Frame.control.w);

    if (!surface.hit) {
        float3 background = r2TemplateBackground(direction);
        signal.deterministic = float4(background, 1.0);
        guide.normalDepth = float4(0.0, 0.0, 0.0, Frame.eye.w);
        Output[index] = float4(background, 1.0);
        Signals[index] = signal;
        Guides[index] = guide;
        HostDepth[index] = Frame.depthRange.y;
        if ((Frame.counts.x & R2_FRAME_WRITE_HITS) != 0u) Hits[index] = hitRecord;
        return;
    }

    float3 irradiance = r2TemplateLighting(surface.position, surface.shading, -direction);
    float3 radiance = surface.albedo * irradiance + surface.emission;

    // Preserve depth and motion for the engine's world handoff and optional reconstruction.
    float4 clip = r2Project(surface.position);
    float depth = clip.w > 0.0 ? clamp(clip.z / clip.w, min(Frame.depthRange.x, Frame.depthRange.y), max(Frame.depthRange.x, Frame.depthRange.y)) : Frame.depthRange.y;
    float4 previousClip = r2ProjectPrevious(surface.position + Frame.previousCamera.xyz);
    float2 motion = float2(0.0);
    bool motionValid = previousClip.w > 0.0 && clip.w > 0.0 && (Frame.counts.x & R2_FRAME_PREVIOUS_VALID) != 0u;
    if (motionValid) {
        float2 current = clip.xy / clip.w;
        float2 previous = previousClip.xy / previousClip.w;
        motion = (previous - current) * 0.5 * float2(Frame.extent.xy);
    }

    signal.diffuse = float4(irradiance, 1.0);
    signal.deterministic = float4(surface.emission, 1.0);
    signal.transmittance = float4(1.0);
    guide.normalDepth = float4(surface.shading, surface.distance);
    guide.diffuseAlbedo = float4(surface.albedo, 1.0);
    guide.specularAlbedo = float4(surface.reflectance, surface.roughness);
    guide.motion = float4(motion, 0.0, motionValid ? 1.0 : 0.0);
    guide.identity = uint4(surface.surfaceToken, asuint(surface.distance), Frame.control.y, 0u);
    guide.path = uint4(R2_GUIDE_PRIMARY_HIT | (motionValid ? R2_GUIDE_MOTION_VALID : 0u), 0u, 0u, 0u);

    Output[index] = float4(radiance, 1.0);
    Signals[index] = signal;
    Guides[index] = guide;
    HostDepth[index] = depth;
    if ((Frame.counts.x & R2_FRAME_WRITE_HITS) != 0u) {
        hitRecord.geometry = surface.surfaceToken;
        hitRecord.flags = R2_HIT_HIT | (surface.front ? R2_HIT_FRONT : 0u);
        hitRecord.distance = surface.distance;
        Hits[index] = hitRecord;
    }
}
