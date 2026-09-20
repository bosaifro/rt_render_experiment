package dev.rt_render_experiment.engine;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import dev.rt_render_experiment.contract.LightInputs;
import dev.rt_render_experiment.contract.MaterialInputs;
import dev.rt_render_experiment.contract.SceneInputs;
import dev.rt_render_experiment.contract.TextureInputs;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ModelVolumeCompilerTest {
    static SceneStore.Revision source(boolean convex) {
        var scene=OrientedMediumFixtures.scene(false,0);if(convex)scene=ConvexVolumeTest.tapered(scene);
        var meshes=new ArrayList<SceneCompiler.Compiled>();
        for(var mesh:scene.geometry()) {
            var input=mesh.input();
            if(input.primitives().getFirst().surface().boundary()!=SceneInputs.Boundary.NESTED_VOLUME) { meshes.add(mesh);continue; }
            var primitives=input.primitives().stream().map(p->{
                var s=p.surface();var plain=new SceneInputs.Surface(s.key(),s.material(),s.properties(),s.colorResource(),s.emissionResource(),s.coverage(),s.cutoff(),s.doubleSided(),0,s.layer(),s.layerSeparation(),s.layers(),s.hostOcclusion());
                return new SceneInputs.Primitive(p.part(),p.ordinal(),plain,p.corners());
            }).toList();
            meshes.add(new SceneCompiler().compile(copy(input,primitives,List.of(new SceneInputs.OpticalModel(primitives.getFirst().part(),primitives)))));
        }
        return new SceneStore.Revision(1,1,meshes,meshes.stream().mapToLong(SceneCompiler.Compiled::bytes).sum());
    }
    static FrameScene frame(long serial,SceneStore.Revision source) { return new FrameScene(serial,source,null,List.of(),LightInputs.Publication.empty()).withModelVolumes(); }
    @Test void actualTrianglesSourceIdentityAndCache() {
        for(boolean convex:new boolean[]{false,true}) {
            var source=source(convex);var compiler=new ModelVolumeCompiler();var first=compiler.prepare(frame(1,source));
            assertEquals(ModelVolumeCompiler.Status.READY,first.report().status());assertEquals(2,first.report().volumes());assertTrue(first.report().geometricWork()>0);
            var derived=first.scene().revision().geometry();
            for(int i=0;i<source.geometry().size();i++) {
                var a=source.geometry().get(i);var b=derived.get(i);assertSame(a.input(),b.input());
                assertEquals(-1,a.positions().mismatch(b.positions()));assertEquals(-1,a.corners().mismatch(b.corners()));assertEquals(-1,a.indices().mismatch(b.indices()));
                assertEquals(a.topologyHash(),b.topologyHash());assertEquals(a.bytes(),b.bytes());
            }
            assertNotEquals(source.geometry().getFirst().appearanceHash(),derived.getFirst().appearanceHash());
            assertEquals(1,derived.getFirst().findPart(derived.getFirst().parts().getFirst().key(),0).orElseThrow().surface().medium());
            var second=compiler.prepare(frame(2,source));assertTrue(second.report().reused());assertEquals(0,second.report().geometricWork());
            assertSame(derived.getFirst(),second.scene().revision().geometry().getFirst());assertEquals(2,second.scene().revision().serial());
            compiler.prepare(new FrameScene(3,source,null,List.of(),LightInputs.Publication.empty()));
            assertEquals(first.scene().mediumDomain(),compiler.prepare(frame(4,source)).scene().mediumDomain(),"Temporarily absent preparation grant changed logical volume identity");
            var classified=new SceneMediaCompiler().prepare(second.scene().revision(),second.scene().mediumDomain().orElseThrow()).classify(InitialMediaFixtures.frame());
            assertEquals(List.of(1L,2L),classified.initialMedia().orElseThrow().enclosures().stream().map(e->e.volume()).toList());
            assertThrows(IllegalStateException.class,()->frame(3,source).withMediumDomain(SceneMediaCompilerTest.domain()));
        }
    }
    @Test void wholeSceneRefusalPreservesEveryOriginalSurface() {
        var base=source(false);var original=base.geometry().getFirst().input();
        for(int mode=0;mode<4;mode++) {
            var faces=new ArrayList<>(original.primitives());var models=original.opticalModels();ModelVolumeCompiler.Status expected;
            if(mode==0) { models=List.of();expected=ModelVolumeCompiler.Status.INCOMPLETE_MODEL; }
            else if(mode==1) { var all=List.copyOf(faces);faces.removeLast();models=List.of(new SceneInputs.OpticalModel(all.getFirst().part(),all,Set.of(5L)));expected=ModelVolumeCompiler.Status.HIDDEN_INTERFACE; }
            else if(mode==2) { faces.removeLast();models=List.of(new SceneInputs.OpticalModel(faces.getFirst().part(),faces));expected=ModelVolumeCompiler.Status.GEOMETRY_UNSUPPORTED; }
            else { var p=faces.getFirst();var corners=new ArrayList<>(p.corners());var c=corners.getFirst();corners.set(0,new SceneInputs.Corner(c.position(),c.u(),c.v(),new SceneInputs.Color(.1f,.2f,.3f,1),c.normal(),c.tangent()));
                faces.set(0,new SceneInputs.Primitive(p.part(),p.ordinal(),p.surface(),corners));models=List.of(new SceneInputs.OpticalModel(faces.getFirst().part(),faces));expected=ModelVolumeCompiler.Status.NON_UNIFORM_MEDIUM; }
            var changed=new SceneCompiler().compile(copy(original,faces,models));var meshes=new ArrayList<>(base.geometry());meshes.set(0,changed);
            var input=frame(1,new SceneStore.Revision(1,1,meshes,base.bytes()));var compiler=new ModelVolumeCompiler();var result=compiler.prepare(input);
            assertEquals(expected,result.report().status());assertSame(input,result.scene());assertTrue(result.scene().mediumDomain().isEmpty());
            if(mode!=1)assertTrue(compiler.prepare(input).report().reused());
        }
        var input=frame(1,base);assertSame(input,new ModelVolumeCompiler(new ModelVolumeCompiler.Limits(1,100,100)).prepare(input).scene());
    }
    @Test void hiddenModelsAndNonRenderedFluidsCannotBecomeAir() {
        var base=source(false);var input=base.geometry().getFirst().input();
        var hidden=new SceneInputs.Geometry(new SceneInputs.Key(999,1),input.revision(),new SceneInputs.Origin(128,64,0),input.current(),input.previous(),false,input.motion(),input.participation(),
            List.of(),List.of(),List.of(new SceneInputs.OpticalModel(input.primitives().getFirst().part(),input.primitives(),Set.of(0L,1L,2L,3L,4L,5L))));
        var mesh=new SceneCompiler().compile(hidden);assertEquals(0,mesh.triangleCount());
        var meshes=new ArrayList<>(base.geometry());meshes.add(mesh);
        var request=frame(1,new SceneStore.Revision(1,1,meshes,base.bytes()+mesh.bytes()));
        assertEquals(ModelVolumeCompiler.Status.HIDDEN_INTERFACE,new ModelVolumeCompiler().prepare(request).report().status());
        var key=new SceneInputs.Key(999,2);var neutral=new SceneInputs.Key(0,0);
        var surface=new SceneInputs.Surface(key,8,0,neutral,neutral,SceneInputs.Coverage.DIELECTRIC,0,true,0,0,0,MaterialInputs.Layers.plain(neutral),SceneInputs.HostOcclusion.PASS);
        var cell=new dev.rt_render_experiment.contract.FluidInputs.Cell(key,0,new SceneInputs.Vec3(0,0,0),java.util.Collections.nCopies(9,new dev.rt_render_experiment.contract.FluidInputs.Sample(1,true,true,false)),
            0,0,0,0,new SceneInputs.Color(1,1,1,1),surface,surface,surface);
        var fluid=new SceneCompiler().compile(new SceneInputs.Geometry(key,input.revision(),input.origin(),input.current(),input.previous(),false,input.motion(),input.participation(),List.of(),List.of(cell)));
        assertEquals(0,fluid.triangleCount());meshes.set(meshes.size()-1,fluid);
        var requestInfo=new SceneInputs.PreparationRequest(1,key,fluid.input().revision(),8192);
        var prepared=SceneInputs.PreparedSource.geometryOnly(new SceneInputs.PreparationResult(requestInfo,SceneInputs.PreparationStatus.READY,List.of(fluid.input()),8192,"Hidden fluid source"));
        var stored=SceneStore.compile(prepared);
        assertNotNull(stored.bounds(),"Fully hidden fluid source needs conservative residency bounds");
        assertTrue(stored.bounds().minimum().y()<64 && stored.bounds().maximum().y()>65);
        assertEquals(0,stored.geometry().triangleCount(),"Residency bounds must not manufacture optical geometry");
        var residency=new SourceResidency();var store=new SceneStore(new SceneCompiler(),new SceneStore.Budget(2,16384));store.world(1);
        residency.source(new SourceResidency.Source(key,fluid.input().revision(),stored.bounds(),8192,SourceResidency.BoundsKind.ESTIMATE));
        var distant=new SourceResidency.Demand(new SceneInputs.Origin(0,272,0),32,32);
        assertEquals(1,residency.readiness(distant,store.publication()).wanted(),"Unresolved producer must initially remain discoverable");
        residency.resolved(requestInfo,stored.bounds());
        assertEquals(0,residency.readiness(distant,store.publication()).wanted(),"Known distant hidden fluid retained unknown residency");
        assertEquals(1,residency.readiness(new SourceResidency.Demand(new SceneInputs.Origin(0,64,0),32,32),store.publication()).wanted(),"Nearby hidden fluid was discarded");
        var fluidRequest=frame(2,new SceneStore.Revision(2,1,meshes,base.bytes()));
        assertEquals(ModelVolumeCompiler.Status.INCOMPLETE_MODEL,new ModelVolumeCompiler().prepare(fluidRequest).report().status());
    }
    @Test void explicitFiltersAreNotVolumeBoundaries() {
        var scene=source(false);var original=scene.geometry().getFirst().input();var p=original.primitives().getFirst();var s=p.surface();
        var filter=new SceneInputs.Surface(s.key(),24,0,s.colorResource(),s.emissionResource(),SceneInputs.Coverage.FILTER,.5f,true,0,0,0,s.layers(),s.hostOcclusion());
        var input=new SceneInputs.Geometry(new SceneInputs.Key(1999,1),original.revision(),original.origin(),original.current(),original.previous(),false,original.motion(),
            new dev.rt_render_experiment.contract.Participation(14,false),List.of(new SceneInputs.Primitive(p.part(),0,filter,p.corners())));
        var meshes=new ArrayList<>(scene.geometry());var compiled=new SceneCompiler().compile(input);meshes.add(compiled);
        var result=new ModelVolumeCompiler().prepare(frame(1,new SceneStore.Revision(1,1,meshes,scene.bytes()+compiled.bytes())));
        assertEquals(ModelVolumeCompiler.Status.READY,result.report().status());assertSame(compiled,result.scene().revision().geometry().getLast());
        assertEquals(2,result.scene().mediumDomain().orElseThrow().volumes().size());
    }
    @Test void staleWorldCannotResetCurrentLogicalIdentities() {
        var old=source(false);var compiler=new SceneCompiler();
        var geometry=old.geometry().stream().map(mesh->{
            var g=mesh.input();var r=g.revision();var revision=new SceneInputs.Revision(2,r.topology(),r.deformation(),r.placement(),r.appearance(),r.resources(),r.coverage());
            return compiler.compile(new SceneInputs.Geometry(g.key(),revision,g.origin(),g.current(),g.previous(),g.previousValid(),g.motion(),g.participation(),g.primitives(),g.fluids(),g.opticalModels()));
        }).toList();
        var current=new SceneStore.Revision(1,2,geometry,old.bytes());var models=new ModelVolumeCompiler();
        models.prepare(frame(1,current));models.prepare(frame(2,new SceneStore.Revision(2,2,List.of(),0)));
        var expected=models.prepare(frame(3,current)).scene().mediumDomain();
        assertThrows(IllegalArgumentException.class,()->models.prepare(frame(4,old)));
        assertEquals(expected,models.prepare(frame(5,current)).scene().mediumDomain());
    }
    @Test void exactTextureInputsCoefficientsAndUnloading() {
        var key=new SceneInputs.Key(1221,1);var base=source(false);var original=base.geometry().getFirst().input();
        var faces=original.primitives().stream().map(p->{ var s=p.surface();return new SceneInputs.Primitive(p.part(),p.ordinal(),
            new SceneInputs.Surface(s.key(),s.material(),s.properties(),key,s.emissionResource(),s.coverage(),s.cutoff(),true,0,0,0,MaterialInputs.Layers.plain(key),s.hostOcclusion()),p.corners()); }).toList();
        var mesh=new SceneCompiler().compile(copy(original,faces,List.of(new SceneInputs.OpticalModel(faces.getFirst().part(),faces))));
        var meshes=List.of(mesh,base.geometry().get(2));var compiler=new ModelVolumeCompiler();
        var one=frame(1,new SceneStore.Revision(1,1,meshes,mesh.bytes(),List.of(texture(key,1,128,128)),List.of()));var first=compiler.prepare(one);
        assertEquals(ModelVolumeCompiler.Status.READY,first.report().status());assertEquals(8,first.report().inspectedTextureBytes());
        var next=frame(2,new SceneStore.Revision(2,1,meshes,mesh.bytes(),List.of(texture(key,2,255,255)),List.of()));var second=compiler.prepare(next);
        assertEquals(ModelVolumeCompiler.Status.READY,second.report().status());assertEquals(0,second.report().geometricWork());
        assertSame(first.scene().revision().geometry().getFirst(),second.scene().revision().geometry().getFirst());
        assertNotEquals(first.scene().mediumDomain(),second.scene().mediumDomain());
        var varied=frame(3,new SceneStore.Revision(3,1,meshes,mesh.bytes(),List.of(texture(key,3,255,254)),List.of()));
        assertEquals(ModelVolumeCompiler.Status.NON_UNIFORM_MEDIUM,compiler.prepare(varied).report().status());
        var tiny=new ModelVolumeCompiler(new ModelVolumeCompiler.Limits(2,100,4));assertEquals(ModelVolumeCompiler.Status.PRESSURE,tiny.prepare(one).report().status());
        compiler.prepare(frame(4,new SceneStore.Revision(4,1,List.of(),0)));
        var restored=compiler.prepare(one);assertNotEquals(first.scene().mediumDomain().orElseThrow().volumes().getFirst().identity(),restored.scene().mediumDomain().orElseThrow().volumes().getFirst().identity());
    }
    private static TextureInputs.Texture texture(SceneInputs.Key key,long revision,int a,int b) {
        return new TextureInputs.Texture(key,revision,TextureInputs.Encoding.SRGB,TextureInputs.Address.CLAMP,List.of(new TextureInputs.Level(2,1,ByteBuffer.wrap(new byte[]{(byte)a,(byte)a,(byte)a,0,(byte)b,(byte)b,(byte)b,-1}))),false);
    }
    private static SceneInputs.Geometry copy(SceneInputs.Geometry source,List<SceneInputs.Primitive> primitives,List<SceneInputs.OpticalModel> models) {
        return new SceneInputs.Geometry(source.key(),source.revision(),source.origin(),source.current(),source.previous(),source.previousValid(),source.motion(),source.participation(),primitives,List.of(),models);
    }
}
