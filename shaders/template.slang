#ifndef R2_SHADER_TEMPLATE
#define R2_SHADER_TEMPLATE

// Start here. Colors are linear RGB; the engine handles tracing and sRGB output.

#ifdef R2_MINIMAL_SCENE
// Surface hooks have access to Frame and the tracing helpers from scene.slang.
float3 r2TemplateBackground(float3 direction) {
    // TODO: Replace this neutral background with your sky.
    return float3(0.18);
}

float3 r2TemplateLighting(float3 position, float3 normal, float3 viewDirection) {
    // TODO: Return the lighting that multiplies the decoded surface albedo.
    // A value of 1 shows the base texture without lighting effects.
    return float3(1.0);
}
#endif

#ifdef R2_RESOURCES_PRESENTATION
// Display runs separately, after the engine has composited the world.
float3 r2TemplateDisplay(float3 radiance) {
    // TODO: Add tone mapping or grading here if your shader needs it.
    return saturate(radiance);
}
#endif

#endif
