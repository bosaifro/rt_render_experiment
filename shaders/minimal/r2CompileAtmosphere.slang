#include "minimal/common.slang"
#include "abi/resources/atmosphere.slang"

[shader("compute")]
[numthreads(R2_THREADS_R2COMPILEATMOSPHERE_X, R2_THREADS_R2COMPILEATMOSPHERE_Y, R2_THREADS_R2COMPILEATMOSPHERE_Z)]
void r2CompileAtmosphere(uint3 thread : SV_DispatchThreadID) {
    uint cell = thread.x;
    if (cell >= R2_ATMOSPHERE_WIDTH * R2_ATMOSPHERE_HEIGHT) return;
    uint column = cell % R2_ATMOSPHERE_WIDTH;
    uint row = cell / R2_ATMOSPHERE_WIDTH;

    float elevation = (float(column) + 0.5) / float(R2_ATMOSPHERE_WIDTH) * 2.0 - 1.0;
    float altitude = (float(row) + 0.5) / float(R2_ATMOSPHERE_HEIGHT);

    float height = altitude * 60000.0;
    float rayleigh = exp(-height / 8000.0);
    float mie = exp(-height / 1200.0);
    float ozone = saturate(1.0 - abs(height - 25000.0) / 15000.0);

    float horizon = sqrt(saturate(1.0 - elevation * elevation));
    float path = 1.0 / max(abs(elevation) + horizon * 0.15, 0.02);
    float transmittance = exp(-(rayleigh * 0.09 + mie * 0.012 + ozone * 0.003) * path);

    AtmosphereCellRecord record;
    record.density = float4(rayleigh, mie, ozone, transmittance);
    Cells[cell] = record;
}
