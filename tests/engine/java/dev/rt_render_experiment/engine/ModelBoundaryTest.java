package dev.rt_render_experiment.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import dev.rt_render_experiment.contract.SceneInputs;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ModelBoundaryTest {
    @Test void completeSurfacesAndConservativeEdgeChecks() {
        var complete=cube();var part=complete.getFirst().part();
        var model=new SceneInputs.OpticalModel(part,complete,Set.of(5L));
        var graph=ModelBoundary.compile(model);
        assertEquals(8*12,graph.positions().remaining());assertEquals(12*16,graph.triangles().remaining());
        assertEquals(1,graph.components().size());assertTrue(graph.components().getFirst().edgeClosed());
        assertEquals(12288,graph.components().getFirst().signedVolume(),1e-9);
        assertEquals(model,graph.source());assertTrue(graph.bytes()<=ModelBoundary.maximumBytes(model));
        assertThrows(java.nio.ReadOnlyBufferException.class,()->graph.positions().putFloat(0,0));
        for(var surface:graph.source().surfaces())assertEquals(SceneInputs.Boundary.UNQUALIFIED,surface.surface().boundary());
        var open=ModelBoundary.compile(new SceneInputs.OpticalModel(part,complete.subList(0,5)));
        assertFalse(open.components().getFirst().edgeClosed());assertEquals(4,open.components().getFirst().boundaryEdges());
        var reversed=new ArrayList<>(complete);var face=complete.getFirst();
        var corners=new ArrayList<>(face.corners());java.util.Collections.reverse(corners);
        reversed.set(0,new SceneInputs.Primitive(part,face.ordinal(),face.surface(),corners));
        var inconsistent=ModelBoundary.compile(new SceneInputs.OpticalModel(part,reversed));
        assertTrue(inconsistent.components().getFirst().windingConflicts()>0);
        var duplicate=new ArrayList<>(complete);duplicate.add(new SceneInputs.Primitive(part,7,face.surface(),face.corners()));
        assertTrue(ModelBoundary.compile(new SceneInputs.OpticalModel(part,duplicate)).components().getFirst().nonManifoldEdges()>0);


        var overlapping=new ArrayList<>(complete);
        for(var primitive:complete) {
            var shifted=primitive.corners().stream().map(c->new SceneInputs.Corner(new SceneInputs.Vec3(c.position().x()+.5f,c.position().y()+.5f,c.position().z()+.5f),
                c.u(),c.v(),c.tint(),c.normal(),c.tangent(),c.emissionUv(),c.emissionTint(),c.overlayTexel())).toList();
            overlapping.add(new SceneInputs.Primitive(part,primitive.ordinal()+10,primitive.surface(),shifted));
        }
        var overlap=ModelBoundary.compile(new SceneInputs.OpticalModel(part,overlapping));
        assertEquals(2,overlap.components().size());assertTrue(overlap.components().stream().allMatch(ModelBoundary.Component::edgeClosed));
    }
    @Test void productionCompilationAndLifetime() {
        var all=cube();var visible=all.subList(0,5);var model=new SceneInputs.OpticalModel(all.getFirst().part(),all,Set.of(5L));
        var compiler=new SceneCompiler();var source=input(1,visible,List.of(model));var full=compiler.compile(source);
        var reference=compiler.compile(input(1,visible,List.of()));
        assertEquals(-1,full.positions().mismatch(reference.positions()));assertEquals(-1,full.corners().mismatch(reference.corners()));
        assertEquals(-1,full.indices().mismatch(reference.indices()));assertEquals(full.topologyHash(),reference.topologyHash());
        assertEquals(full.appearanceHash(),reference.appearanceHash());assertEquals(reference.renderBytes(),full.renderBytes());
        assertTrue(full.boundaryBytes()>0);assertEquals(full.renderBytes()+full.boundaryBytes(),full.bytes());
        long charge=visible.stream().mapToLong(p->SceneCompiler.primitiveBytes(p.corners().size())).sum()+ModelBoundary.maximumBytes(model);
        var store=new SceneStore(compiler,new SceneStore.Budget(2,charge*2));store.world(1);
        var old=store.request(source.key(),source.revision(),charge);var prepared=prepared(old,source,charge);
        assertEquals(charge,SceneStore.workBytes(prepared));assertTrue(store.publish(prepared));
        var published=store.snapshot().geometry().getFirst().modelBoundaries().getFirst();
        assertTrue(store.resourceKeys().isEmpty());assertTrue(store.residentBytes()>=full.bytes());
        var next=input(2,visible,List.of(model));var stale=store.request(source.key(),next.revision(),charge);
        var compiled=SceneStore.compile(prepared(stale,next,charge));
        store.remove(source.key());assertFalse(store.publishCompiled(compiled));assertTrue(store.snapshot().geometry().isEmpty());
        assertTrue(published.components().getFirst().edgeClosed(),"Previously captured immutable boundary changed on unload");
        var low=new SceneInputs.PreparationRequest(3,source.key(),source.revision(),charge);
        assertThrows(IllegalArgumentException.class,()->SceneStore.compile(prepared(low,source,charge-1)));


        var hidden=new SceneInputs.OpticalModel(all.getFirst().part(),all,Set.of(0L,1L,2L,3L,4L,5L));
        var hiddenInput=input(1,List.of(),List.of(hidden));long hiddenCharge=ModelBoundary.maximumBytes(hidden);
        var result=SceneStore.compile(prepared(new SceneInputs.PreparationRequest(4,source.key(),source.revision(),hiddenCharge),hiddenInput,hiddenCharge));
        assertEquals(0,result.geometry().triangleCount());assertNotNull(result.bounds());
        assertTrue(result.geometry().boundaryBytes()>0);
        assertThrows(IllegalArgumentException.class,()->input(1,visible,List.of(new SceneInputs.OpticalModel(all.getFirst().part(),all))));
    }
    @Test void hiddenResourcesRemainCoherentWithoutUnusedGpuUploads() {
        var all=new ArrayList<>(cube());var key=new SceneInputs.Key(17,23);var p=all.get(5);var s=p.surface();
        var surface=new SceneInputs.Surface(s.key(),s.material(),s.properties(),key,s.emissionResource(),s.coverage(),s.cutoff(),true,0,0,0,
            dev.rt_render_experiment.contract.MaterialInputs.Layers.plain(key),s.hostOcclusion());
        all.set(5,new SceneInputs.Primitive(p.part(),p.ordinal(),surface,p.corners()));
        var geometry=new SceneCompiler().compile(input(1,all.subList(0,5),List.of(new SceneInputs.OpticalModel(p.part(),all,Set.of(5L)))));
        assertTrue(geometry.requiredResourceKeys().contains(key));assertFalse(geometry.renderResourceKeys().contains(key));
        var compiler=new SceneCompiler();
        var rebound=compiler.withInputRevision(geometry,input(2,all.subList(0,5),List.of(new SceneInputs.OpticalModel(p.part(),all,Set.of(5L)))));
        assertSame(geometry.requiredResourceKeys(),rebound.requiredResourceKeys());
        assertSame(geometry.renderResourceKeys(),rebound.renderResourceKeys());
        var withdrawn=compiler.withoutParts(rebound,Set.of(p.part()));
        assertFalse(withdrawn.requiredResourceKeys().contains(key),"A withdrawn model retained its hidden resource dependency");
        var changed=compiler.compile(input(3,cube(),List.of()));
        assertFalse(changed.requiredResourceKeys().contains(key),"New prepared surface content inherited stale resource dependencies");
        var bytes=java.nio.ByteBuffer.wrap(new byte[]{-1,-1,-1,-1});
        var texture=new dev.rt_render_experiment.contract.TextureInputs.Texture(key,1,dev.rt_render_experiment.contract.TextureInputs.Encoding.LINEAR,
            dev.rt_render_experiment.contract.TextureInputs.Address.CLAMP,List.of(new dev.rt_render_experiment.contract.TextureInputs.Level(1,1,bytes)));
        var resident=new SceneStore.Revision(1,1,List.of(geometry),geometry.bytes(),List.of(texture),List.of());
        var frame=new FrameScene(1,resident,null,List.of(),dev.rt_render_experiment.contract.LightInputs.Publication.empty());
        assertEquals(1,frame.revision().textures().size());assertTrue(frame.renderRevision(SceneFixtures.frame().environment()).textures().isEmpty());
        var environment=SceneFixtures.frame().environment();
        var cloud=new RenderFrame.Environment(environment.sun(),environment.moon(),environment.dayFraction(),0,0,true,64,128,false,
            new RenderFrame.Cloud(key,1,1,192,4,12));
        frame.requireEnvironment(cloud);assertEquals(List.of(texture),frame.renderRevision(cloud).textures(),"Resident environment input lost its actual GPU consumer");
        assertThrows(IllegalArgumentException.class,()->new FrameScene(1,new SceneStore.Revision(1,1,List.of(geometry),geometry.bytes()),null,List.of(),dev.rt_render_experiment.contract.LightInputs.Publication.empty()));
    }
    private static SceneInputs.PreparedSource prepared(SceneInputs.PreparationRequest request,SceneInputs.Geometry input,long charge) {
        return SceneInputs.PreparedSource.geometryOnly(new SceneInputs.PreparationResult(request,SceneInputs.PreparationStatus.READY,List.of(input),charge,"Complete model facts"));
    }
    private static SceneInputs.Geometry input(long revision,List<SceneInputs.Primitive> visible,List<SceneInputs.OpticalModel> models) {
        var original=OrientedMediumFixtures.scene(false,0).geometry().getFirst().input();
        return new SceneInputs.Geometry(original.key(),new SceneInputs.Revision(1,revision,0,1,1,1,1),original.origin(),original.current(),original.previous(),
            false,original.motion(),original.participation(),visible,List.of(),models);
    }
    static List<SceneInputs.Primitive> cube() {
        var input=OrientedMediumFixtures.scene(false,0).geometry().getFirst().input();
        return input.primitives().stream().map(p->{
            var s=p.surface();var surface=new SceneInputs.Surface(s.key(),s.material(),s.properties(),s.colorResource(),s.emissionResource(),s.coverage(),s.cutoff(),
                s.doubleSided(),0,s.layer(),s.layerSeparation(),s.layers(),s.hostOcclusion());
            return new SceneInputs.Primitive(p.part(),p.ordinal(),surface,p.corners());
        }).toList();
    }
}
