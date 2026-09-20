package dev.rt_render_experiment.engine;

import java.util.ArrayList;
import java.util.List;
import dev.rt_render_experiment.contract.SceneInputs;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class TiledContactTest {
    static SceneStore.Revision source(int mode) {
        var source=FluidContactTest.source();var solid=source.geometry().getLast().input();
        var meshes=new ArrayList<>(source.geometry());
        meshes.set(meshes.size()-1,compile(solid,tiles(solid.primitives().getFirst(),mode)));
        return JoinedModelVolumesTest.revision(meshes);
    }
    static SceneCompiler.Compiled compile(SceneInputs.Geometry source,List<SceneInputs.Primitive> primitives) {
        return new SceneCompiler().compile(new SceneInputs.Geometry(source.key(),source.revision(),source.origin(),source.current(),source.previous(),source.previousValid(),
            source.motion(),source.participation(),primitives,source.fluids(),source.opticalModels()));
    }

    static List<SceneInputs.Primitive> tiles(SceneInputs.Primitive source,int mode) {
        var c=source.corners();
        if(mode==0)return List.of(new SceneInputs.Primitive(source.part(),0,source.surface(),List.of(c.get(0),c.get(1),c.get(2))),
            new SceneInputs.Primitive(source.part(),1,source.surface(),List.of(c.get(2),c.get(3),c.get(0))));
        if(mode==1)return List.of(new SceneInputs.Primitive(source.part(),0,source.surface(),List.of(c.get(0),c.get(1),c.get(3))),
            new SceneInputs.Primitive(source.part(),1,source.surface(),List.of(c.get(1),c.get(2),c.get(3))));

        var a=middle(c.get(0),c.get(1));var b=middle(c.get(2),c.get(3));var center=middle(a,b);var side=middle(c.get(1),c.get(2));
        return List.of(new SceneInputs.Primitive(source.part(),0,source.surface(),List.of(c.get(0),a,b,c.get(3))),
            new SceneInputs.Primitive(source.part(),1,source.surface(),List.of(a,c.get(1),side,center)),
            new SceneInputs.Primitive(source.part(),2,source.surface(),List.of(center,side,c.get(2),b)));
    }
    private static SceneInputs.Corner middle(SceneInputs.Corner a,SceneInputs.Corner b) {
        var p=a.position();var q=b.position();return new SceneInputs.Corner(new SceneInputs.Vec3((p.x()+q.x())*.5f,(p.y()+q.y())*.5f,(p.z()+q.z())*.5f),
            (a.u()+b.u())*.5f,(a.v()+b.v())*.5f,a.tint(),a.normal(),a.tangent());
    }
    @Test void actualTrianglesAndTJunctionQuadsCloseThePreparedFluidBoundary() {
        for(int mode=0;mode<3;mode++) {
            var input=source(mode);var result=new ModelVolumeCompiler().prepare(FluidVolumeCompilerTest.frame(1,input));
            assertEquals(ModelVolumeCompiler.Status.READY,result.report().status(),result.report().toString());
            assertEquals(mode==2?3:2,result.scene().revision().geometry().getLast().parts().stream().filter(p->p.surface().boundary()==SceneInputs.Boundary.OPAQUE_CONTACT).count());
            for(int i=0;i<input.geometry().size();i++) {
                var before=input.geometry().get(i);var after=result.scene().revision().geometry().get(i);
                assertSame(before.input(),after.input());assertEquals(before.topologyHash(),after.topologyHash());
                assertEquals(-1,before.positions().mismatch(after.positions()));assertEquals(-1,before.corners().mismatch(after.corners()));assertEquals(-1,before.indices().mismatch(after.indices()));
            }
            var media=new SceneMediaCompiler().prepare(result.scene().revision(),result.scene().mediumDomain().orElseThrow());
            assertEquals(1,media.classify(SceneMediaCompilerTest.at(new SceneInputs.Origin(.5,64.35,-1.5))).initialMedia().orElseThrow().enclosures().size());
        }
    }
    @Test void holesDuplicateAreaWrongWindingAndUnsupportedTilesRefuseAtomically() {
        var baseline=source(2);var g=baseline.geometry().getLast().input();var compiler=new ModelVolumeCompiler();
        var accepted=compiler.prepare(FluidVolumeCompilerTest.frame(1,baseline));assertEquals(ModelVolumeCompiler.Status.READY,accepted.report().status());
        for(int mode=0;mode<7;mode++) {
            var primitives=new ArrayList<>(g.primitives());var p=primitives.getLast();
            if(mode==0)primitives.removeLast();
            if(mode==1)primitives.set(2,new SceneInputs.Primitive(p.part(),p.ordinal(),p.surface(),primitives.get(1).corners()));
            if(mode==2)primitives.add(new SceneInputs.Primitive(p.part(),99,p.surface(),p.corners()));
            if(mode==3) { var corners=new ArrayList<>(p.corners());java.util.Collections.reverse(corners);primitives.set(2,new SceneInputs.Primitive(p.part(),p.ordinal(),p.surface(),corners)); }
            if(mode==4) {
                var s=p.surface();var surface=new SceneInputs.Surface(s.key(),25,0,s.colorResource(),s.emissionResource(),s.coverage(),0,true,0,0,0,s.layers(),s.hostOcclusion());
                primitives.set(2,new SceneInputs.Primitive(p.part(),p.ordinal(),surface,p.corners()));
            }
            if(mode==5)primitives.set(2,new SceneInputs.Primitive(p.part(),p.ordinal(),p.surface(),p.corners().stream().map(c->
                new SceneInputs.Corner(new SceneInputs.Vec3(c.position().x(),c.position().y()+.01f,c.position().z()),c.u(),c.v(),c.tint(),c.normal(),c.tangent())).toList()));
            if(mode==6) {
                var corners=new ArrayList<>(p.corners());var c=corners.getFirst();corners.set(0,new SceneInputs.Corner(new SceneInputs.Vec3(Math.nextUp(c.position().x()),c.position().y(),c.position().z()),c.u(),c.v(),c.tint(),c.normal(),c.tangent()));
                primitives.set(2,new SceneInputs.Primitive(p.part(),p.ordinal(),p.surface(),corners));
            }
            var input=FluidVolumeCompilerTest.frame(2+mode,JoinedModelVolumesTest.revision(List.of(baseline.geometry().getFirst(),compile(g,primitives))));
            var failed=compiler.prepare(input);assertEquals(ModelVolumeCompiler.Status.HIDDEN_INTERFACE,failed.report().status(),"mode="+mode+" "+failed.report());assertSame(input,failed.scene());
            assertTrue(compiler.prepare(input).report().reused());
            assertEquals(accepted.scene().mediumDomain(),compiler.prepare(FluidVolumeCompilerTest.frame(20+mode,baseline)).scene().mediumDomain());
        }
    }
    @Test void sourceTilesCanHaveIndependentKeysOriginsAndAuthoredAttributes() {
        var source=source(2);var solid=source.geometry().getLast().input();var meshes=new ArrayList<SceneCompiler.Compiled>();meshes.add(source.geometry().getFirst());
        for(int i=0;i<solid.primitives().size();i++) {
            var p=solid.primitives().get(i);int offset=(i-1)*16;
            var corners=p.corners().stream().map(c->new SceneInputs.Corner(new SceneInputs.Vec3(c.position().x()-offset,c.position().y(),c.position().z()),
                1-c.v(),c.u(),new SceneInputs.Color(.3f,.7f,.9f,1),c.normal(),new SceneInputs.Vec3(1,0,0))).toList();
            var part=new SceneInputs.Primitive(new SceneInputs.Key(5800,i),117,p.surface(),corners);
            meshes.add(new SceneCompiler().compile(new SceneInputs.Geometry(new SceneInputs.Key(5900,i),solid.revision(),new SceneInputs.Origin(offset,64,0),
                solid.current(),solid.previous(),false,solid.motion(),solid.participation(),List.of(part))));
        }
        var result=new ModelVolumeCompiler().prepare(FluidVolumeCompilerTest.frame(1,JoinedModelVolumesTest.revision(meshes)));
        assertEquals(ModelVolumeCompiler.Status.READY,result.report().status(),result.report().toString());
        for(int i=1;i<meshes.size();i++) {
            var a=meshes.get(i);var b=result.scene().revision().geometry().get(i);assertEquals(-1,a.corners().mismatch(b.corners()));assertEquals(-1,a.positions().mismatch(b.positions()));
            assertEquals(a.parts().getFirst().key(),b.parts().getFirst().key());assertEquals(117,b.parts().getFirst().ordinal());
        }
    }
    @Test void completeModelContactsUseTheSameCoverAcrossNegativeShearedPlacement() {
        var source=OpaqueContactTest.source();
        var pose=new SceneInputs.Transform(new float[]{-2,.5f,0,0,0,.5f,0,0,0,0,1,0});var origin=new SceneInputs.Origin(30_000_000,64,-30_000_000);
        var meshes=new ArrayList<SceneCompiler.Compiled>();
        for(var mesh:source.geometry()) {
            var g=mesh.input();var primitives=new ArrayList<SceneInputs.Primitive>();
            for(var p:g.primitives())if(g.opticalModels().isEmpty() && p.ordinal()==1) {
                for(var tile:tiles(p,2))primitives.add(new SceneInputs.Primitive(tile.part(),100+tile.ordinal(),tile.surface(),tile.corners()));
            } else primitives.add(p);
            meshes.add(new SceneCompiler().compile(new SceneInputs.Geometry(g.key(),g.revision(),origin,pose,pose,false,g.motion(),g.participation(),primitives,g.fluids(),g.opticalModels())));
        }
        var result=new ModelVolumeCompiler().prepare(ModelVolumeCompilerTest.frame(1,JoinedModelVolumesTest.revision(meshes)));
        assertEquals(ModelVolumeCompiler.Status.READY,result.report().status(),result.report().toString());assertEquals(1,result.report().volumes());
        assertEquals(3,result.scene().revision().geometry().getLast().parts().stream().filter(p->p.surface().boundary()==SceneInputs.Boundary.OPAQUE_CONTACT).count());
        var classified=new SceneMediaCompiler().prepare(result.scene().revision(),result.scene().mediumDomain().orElseThrow())
            .classify(SceneMediaCompilerTest.at(new SceneInputs.Origin(origin.x()-.75,64.25,origin.z()+.5)));
        assertEquals(1,classified.initialMedia().orElseThrow().enclosures().size());
    }
    @Test void predicateAndPartPressureRetainTheUnqualifiedSource() {
        var source=source(2);var frame=FluidVolumeCompilerTest.frame(1,source);
        var rejected=new ModelVolumeCompiler(new ModelVolumeCompiler.Limits(2,6,4096)).prepare(frame);
        assertEquals(ModelVolumeCompiler.Status.PRESSURE,rejected.report().status());assertSame(frame,rejected.scene());
        var g=source.geometry().getFirst().input();var points=new FluidCompiler().interfaces(g.fluids().getFirst(),1).getFirst().corners().stream()
            .map(c->new ConvexVolume.Point(c.position().x(),c.position().y(),c.position().z())).toList();
        assertThrows(IllegalStateException.class,()->OpaqueContacts.match(List.of(new OpaqueContacts.Request(g,points)),source.geometry(),100,new ConvexVolume.Budget(20)));
        assertEquals(ModelVolumeCompiler.Status.READY,new ModelVolumeCompiler().prepare(frame).report().status());
    }
    @Test void aWholeFacePlusAnOverlappingTileIsAmbiguousRatherThanASecondLayer() {
        var source=FluidContactTest.source();var g=source.geometry().getLast().input();var p=g.primitives().getFirst();
        var primitives=new ArrayList<>(g.primitives());var tile=tiles(p,2).getFirst();primitives.add(new SceneInputs.Primitive(tile.part(),10,tile.surface(),tile.corners()));
        var frame=FluidVolumeCompilerTest.frame(1,JoinedModelVolumesTest.revision(List.of(source.geometry().getFirst(),compile(g,primitives))));
        var result=new ModelVolumeCompiler().prepare(frame);assertSame(frame,result.scene());assertEquals(ModelVolumeCompiler.Status.HIDDEN_INTERFACE,result.report().status());
    }
    @Test void indexedCandidatesRemainIndependentAcrossSourcesAndRtRenderExperimentderedPublication() {
        var prototype=source(2);var meshes=new ArrayList<SceneCompiler.Compiled>();
        for(int instance=0;instance<16;instance++)for(var mesh:prototype.geometry()) {
            var g=mesh.input();meshes.add(new SceneCompiler().compile(new SceneInputs.Geometry(new SceneInputs.Key(g.key().high(),100+instance),g.revision(),
                new SceneInputs.Origin((instance%4)*2,64,(instance/4)*2),g.current(),g.previous(),false,g.motion(),g.participation(),g.primitives(),g.fluids(),g.opticalModels())));
        }
        var compiler=new ModelVolumeCompiler();var first=compiler.prepare(FluidVolumeCompilerTest.frame(1,JoinedModelVolumesTest.revision(meshes)));
        assertEquals(ModelVolumeCompiler.Status.READY,first.report().status(),first.report().toString());assertEquals(16,first.report().fluidRegions());
        assertEquals(48,first.scene().revision().geometry().stream().flatMap(g->g.parts().stream()).filter(p->p.surface().boundary()==SceneInputs.Boundary.OPAQUE_CONTACT).count());
        java.util.Collections.reverse(meshes);var second=compiler.prepare(FluidVolumeCompilerTest.frame(2,JoinedModelVolumesTest.revision(meshes)));
        assertEquals(ModelVolumeCompiler.Status.READY,second.report().status(),second.report().toString());assertEquals(first.scene().mediumDomain(),second.scene().mediumDomain());
        var media=new SceneMediaCompiler().prepare(second.scene().revision(),second.scene().mediumDomain().orElseThrow());
        for(int instance=0;instance<16;instance++)assertEquals(1,media.classify(SceneMediaCompilerTest.at(new SceneInputs.Origin((instance%4)*2+.5,64.35,(instance/4)*2-1.5))).initialMedia().orElseThrow().enclosures().size());
    }
}
