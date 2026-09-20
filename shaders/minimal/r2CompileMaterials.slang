#include "minimal/common.slang"
#include "materials/catalog_admission.slang"
#include "abi/resources/materials.slang"

[shader("compute")]
[numthreads(R2_THREADS_R2COMPILEMATERIALS_X, R2_THREADS_R2COMPILEMATERIALS_Y, R2_THREADS_R2COMPILEMATERIALS_Z)]
void r2CompileMaterials(uint3 thread : SV_DispatchThreadID) {
    uint definition = thread.x;
    if (definition >= R2_MATERIAL_COUNT) return;

    float roughness = 0.82;
    float metalness = 0.0;
    float reflectance = 0.035;
    float transmission = 0.0;
    float ior = 1.0;
    uint flags = 0u;

    if (r2GlassSheetDefinition(definition)) {
        roughness = 0.045; reflectance = 0.08; transmission = 0.92; ior = R2_GLASS_IOR;
        flags |= R2_MATERIAL_THIN;
    } else if (definition == 8u || definition == 9u) {
        roughness = 0.035; reflectance = 0.02; transmission = 0.75; ior = R2_WATER_IOR;
    } else if (definition == 18u || definition == 24u || definition == 43u || definition == 44u) {
        roughness = 0.06; reflectance = 0.08; transmission = 0.88; ior = R2_GLASS_IOR;
    }

    MaterialRecord record = (MaterialRecord)0;
    record.classification = uint4(flags, definition, 0u, 0u);
    record.tintOxidation = float4(1.0, 1.0, 1.0, 0.0);
    record.finish = float4(roughness, metalness, reflectance, 0.0);
    record.conductorEtaTransmission = float4(ior, ior, ior, transmission);
    record.conductorKThickness = float4(0.0, 0.0, 0.0, transmission > 0.0 ? 0.125 : 0.0);
    record.absorptionEmissionFloor = float4(0.0, 0.0, 0.0, 0.0);
    record.modifiers = float4(1.0, 1.0, 0.0, 0.0);
    record.emissionSpectrumIntensity = float4(0.0, 0.0, 0.0, 0.0);
    Definitions[definition] = record;
}
