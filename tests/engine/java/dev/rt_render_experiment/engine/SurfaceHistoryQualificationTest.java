package dev.rt_render_experiment.engine;

import java.util.List;
import dev.rt_render_experiment.contract.MaterialInputs;
import dev.rt_render_experiment.contract.SceneInputs;
import dev.rt_render_experiment.contract.SurfaceProperties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class SurfaceHistoryQualificationTest {
    private static final SceneInputs.Key NONE=new SceneInputs.Key(0,0),TEXTURE=new SceneInputs.Key(42,1);
    @Test void onlyTheEstablishedPlainWaterBoundaryIgnoresTextureRevision() {
        var plain=MaterialInputs.Layers.plain(TEXTURE);
        assertTrue(GpuScene.textureIndependentBoundary(surface(8,0,SceneInputs.Coverage.DIELECTRIC,true,plain)));
        for(int material:new int[]{1,7,9,18,24,43,44,48,49,64,68})assertFalse(GpuScene.textureIndependentBoundary(surface(material,0,SceneInputs.Coverage.DIELECTRIC,true,plain)));
        for(int properties:new int[]{SurfaceProperties.WET,SurfaceProperties.THIN,SurfaceProperties.TRANSLUCENT,1<<28})
            assertFalse(GpuScene.textureIndependentBoundary(surface(8,properties,SceneInputs.Coverage.DIELECTRIC,true,plain)));
        for(var coverage:SceneInputs.Coverage.values())if(coverage!=SceneInputs.Coverage.DIELECTRIC)
            assertFalse(GpuScene.textureIndependentBoundary(surface(8,0,coverage,true,plain)));
        assertFalse(GpuScene.textureIndependentBoundary(surface(8,0,SceneInputs.Coverage.DIELECTRIC,false,plain)));
        for(var layers:List.of(
            new MaterialInputs.Layers(TEXTURE,true,TEXTURE,MaterialInputs.Emission.NONE,new SceneInputs.Vec3(0,0,0),false,false,MaterialInputs.Sampling.CRISP),
            new MaterialInputs.Layers(TEXTURE,true,NONE,MaterialInputs.Emission.NONE,new SceneInputs.Vec3(0,0,0),true,false,MaterialInputs.Sampling.CRISP),
            new MaterialInputs.Layers(TEXTURE,true,NONE,MaterialInputs.Emission.CALIBRATED,new SceneInputs.Vec3(1,1,1),false,false,MaterialInputs.Sampling.CRISP),
            new MaterialInputs.Layers(TEXTURE,true,NONE,MaterialInputs.Emission.MERGED,new SceneInputs.Vec3(0,0,0),false,false,MaterialInputs.Sampling.CRISP)))
            assertFalse(GpuScene.textureIndependentBoundary(surface(8,0,SceneInputs.Coverage.DIELECTRIC,true,layers)));
    }
    private static SceneInputs.Surface surface(int material,int properties,SceneInputs.Coverage coverage,boolean sided,MaterialInputs.Layers layers) {
        return new SceneInputs.Surface(new SceneInputs.Key(1,1),material,properties,TEXTURE,layers.emission()==MaterialInputs.Emission.NONE?NONE:TEXTURE,
            coverage,.5f,sided,0,0,0,layers,SceneInputs.HostOcclusion.PASS);
    }
}
