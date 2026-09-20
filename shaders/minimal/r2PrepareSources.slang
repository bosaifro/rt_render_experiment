#include "minimal/common.slang"
#include "abi/resources/sourcePreparation.slang"

[shader("compute")]
[numthreads(R2_THREADS_R2PREPARESOURCES_X, R2_THREADS_R2PREPARESOURCES_Y, R2_THREADS_R2PREPARESOURCES_Z)]
void r2PrepareSources(uint3 thread : SV_DispatchThreadID) {
    uint source = thread.y * R2_SOURCE_PREPARATION_COLUMNS * R2_THREADS_R2PREPARESOURCES_X + thread.x;
    SourceTableRecord table = ((SourceTableRecord*)Frame.sources)[0];
    uint count = table.counts.x + table.counts.y;
    if (source >= count || Frame.emitters == 0ull) return;

    SourceRecord record = ((SourceRecord*)table.sources)[source];
    EmitterRecord emitter;
    emitter.positionRadius = float4(record.centerRadius.xyz, max(record.centerRadius.w, 0.0));
    emitter.radianceArea = float4(record.importance.rgb * record.importance.w, max(record.normalArea.w, 0.0));
    emitter.sampling = float4(record.normalArea.xyz, record.tangentSupport.w);
    emitter.identity = uint4(record.definition.x, record.association.x, record.association.w, source);
    ((EmitterRecord*)Frame.emitters)[source] = emitter;
}
