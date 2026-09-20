package dev.rt_render_experiment.engine;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import dev.rt_render_experiment.contract.MaterialInputs;
import dev.rt_render_experiment.contract.ProducerResources;
import dev.rt_render_experiment.contract.SceneInputs;
import dev.rt_render_experiment.contract.TextureInputs;
import dev.rt_render_experiment.engine.abi.R2Abi;


final class ProducerSurfaceRecords {
    private ProducerSurfaceRecords() {}
    static List<SceneInputs.Key> textureKeys(ProducerResources.Geometry input) {
        var result=new java.util.LinkedHashSet<SceneInputs.Key>();
        for(var primitive:input.metadata()) {
            var surface=primitive.surface();var layers=surface.layers();
            result.add(surface.colorResource());result.add(layers.coverage());
            if(layers.emission()!=MaterialInputs.Emission.NONE)result.add(surface.emissionResource());
            if(!layers.overlay().equals(new SceneInputs.Key(0,0)))result.add(layers.overlay());
        }
        return List.copyOf(result);
    }
    static List<SceneTextures.SurfaceState> textureState(List<SceneInputs.Key> keys,SceneTextures textures) {
        var result=new ArrayList<SceneTextures.SurfaceState>(keys.size());for(var key:keys)result.add(textures.surfaceState(key));return List.copyOf(result);
    }
    static ByteBuffer encode(ProducerResources.Geometry input,SceneTextures textures,MediumDefinitions media,long mediumAddress,int firstToken) {
        var metadata=input.metadata();
        var out=ByteBuffer.allocate(Math.multiplyExact(metadata.size(),R2Abi.SurfaceRecord.SIZE)).order(ByteOrder.LITTLE_ENDIAN);
        for(int i=0;i<metadata.size();i++) {
            var surface=metadata.get(i).surface();var layers=surface.layers();int base=i*R2Abi.SurfaceRecord.SIZE;
            if(surface.layer()!=0 || surface.layerSeparation()!=0)
                throw new IllegalArgumentException("Producer must publish separated layer geometry explicitly");
            int flags=(surface.doubleSided()?1:0)|(layers.modelResponse()?2:0)|(layers.extendedRayClearance()?4:0);
            R2Abi.SurfaceRecord.definition(out,base,surface.material(),surface.properties(),surface.coverage().ordinal(),flags);
            R2Abi.SurfaceRecord.coverage(out,base,surface.cutoff(),0,0,0);
            int emission=layers.emission()==MaterialInputs.Emission.NONE?-1:textures.slot(surface.emissionResource());
            int overlay=layers.overlay().equals(new SceneInputs.Key(0,0))?-1:textures.slot(layers.overlay());
            int encodings=(textures.linear(surface.colorResource())?1:0)
                |(emission>=0 && textures.linear(surface.emissionResource())?2:0)|(overlay>=0 && textures.linear(layers.overlay())?4:0);
            R2Abi.SurfaceRecord.textures(out,base,textures.slot(surface.colorResource()),emission,encodings,Math.addExact(firstToken,i));
            R2Abi.SurfaceRecord.colorFactor(out,base,1,1,1,1);
            var radiance=layers.calibratedRadiance();
            R2Abi.SurfaceRecord.emissionFactor(out,base,radiance.x(),radiance.y(),radiance.z(),0);
            R2Abi.SurfaceRecord.layers(out,base,textures.slot(layers.coverage()),overlay,layers.emission().ordinal(),
                (layers.coverageUsesTint()?R2Abi.LAYER_COVERAGE_TINT:0)
                    |(layers.sampling()==MaterialInputs.Sampling.TEXTURE_SAMPLER?R2Abi.LAYER_TEXTURE_SAMPLER:0)
                    |(layers.tintEncoding()==TextureInputs.Encoding.SRGB?R2Abi.LAYER_TINT_SRGB:0)
                    |(layers.emissionTintEncoding()==TextureInputs.Encoding.SRGB?R2Abi.LAYER_EMISSION_TINT_SRGB:0));
            R2Abi.SurfaceRecord.medium(out,base,surface.medium());
            if(surface.boundary()!=SceneInputs.Boundary.UNQUALIFIED) {
                if(media==null || mediumAddress==0)throw new IllegalArgumentException("Producer optical interface lacks its medium definitions");
                R2Abi.SurfaceRecord.mediumDefinition(out,base,Math.addExact(mediumAddress,media.offset(surface.medium())));
            }
            R2Abi.SurfaceRecord.boundary(out,base,surface.boundary().ordinal());
            R2Abi.SurfaceRecord.revision(out,base,input.materialRevision());
            R2Abi.SurfaceRecord.hostOcclusion(out,base,surface.hostOcclusion().ordinal());
        }
        return out;
    }
}
