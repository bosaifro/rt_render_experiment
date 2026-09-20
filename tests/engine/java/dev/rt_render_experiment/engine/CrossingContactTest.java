package dev.rt_render_experiment.engine;

import java.util.ArrayList;
import java.util.List;
import dev.rt_render_experiment.contract.SceneInputs;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class CrossingContactTest {
    static SceneStore.Revision source(int subdivision) {
        return decorate(FluidContactFixtures.scene(false,true,false,new SceneInputs.Origin(0,64,0)),subdivision);
    }
    static SceneStore.Revision decorate(SceneStore.Revision base,int subdivision) {
        var meshes=new ArrayList<>(base.geometry());var wall=meshes.get(1).input();var primitives=new ArrayList<SceneInputs.Primitive>();
        for(var p:wall.primitives())if(p.part().low()==2) {
            if(subdivision==4) {
                var corners=p.corners().stream().map(c->new SceneInputs.Corner(new SceneInputs.Vec3(c.position().x(),c.position().y()*3,(c.position().z()+2)*2-2),c.u(),c.v(),c.tint(),c.normal(),c.tangent())).toList();
                p=new SceneInputs.Primitive(p.part(),p.ordinal(),p.surface(),corners);
            }
            primitives.addAll(subdivision==3?TessellatedModelJoinsTest.tiles(p,3):TiledContactTest.tiles(p,subdivision==4?0:subdivision));
        } else primitives.add(p);
        meshes.set(1,TiledContactTest.compile(wall,primitives));return JoinedModelVolumesTest.revision(meshes);
    }
    @Test void aWaterInterfaceCanCrossActualTriangleAndQuadSeams() {
        for(int pattern=0;pattern<5;pattern++) {
            var source=source(pattern);var result=new ModelVolumeCompiler().prepare(FluidVolumeCompilerTest.frame(1,source));
            assertEquals(ModelVolumeCompiler.Status.READY,result.report().status(),pattern+" "+result.report());assertEquals(1,result.report().fluidRegions());
            for(int i=0;i<source.geometry().size();i++) {
                var a=source.geometry().get(i);var b=result.scene().revision().geometry().get(i);assertSame(a.input(),b.input());
                assertEquals(-1,a.positions().mismatch(b.positions()));assertEquals(-1,a.corners().mismatch(b.corners()));assertEquals(-1,a.indices().mismatch(b.indices()));
            }
            var media=new SceneMediaCompiler().prepare(result.scene().revision(),result.scene().mediumDomain().orElseThrow());
            assertEquals(1,media.classify(SceneMediaCompilerTest.at(new SceneInputs.Origin(.5,64.35,-1.5))).initialMedia().orElseThrow().enclosures().size());
            assertEquals(0,media.classify(SceneMediaCompilerTest.at(new SceneInputs.Origin(.5,64.9,-1.5))).initialMedia().orElseThrow().enclosures().size());
            var patch=result.scene().revision().geometry().get(1).clipContacts().getFirst();
            var full=new ModelVolumeCompiler().prepare(FluidVolumeCompilerTest.frame(1,FluidContactFixtures.scene(false,true,false,new SceneInputs.Origin(0,64,0))));
            assertEquals(full.scene().revision().geometry().get(1).clipContacts().getFirst().corners(),patch.corners(),"Intersection vertices escaped into the GPU boundary");
            assertEquals(pattern==2?3:2,patch.supports().size());
        }
    }
    @Test void equalAreaOverlapHolesAndOneUlpGapsRefuseWithoutSnapping() {
        var base=source(3);var g=base.geometry().get(1).input();var compiler=new ModelVolumeCompiler();var accepted=compiler.prepare(FluidVolumeCompilerTest.frame(1,base));
        for(int mode=0;mode<4;mode++) {
            var primitives=new ArrayList<>(g.primitives());var a=primitives.get(1);var b=primitives.get(2);
            if(mode==0)primitives.remove(2);
            if(mode==1)primitives.set(2,new SceneInputs.Primitive(b.part(),b.ordinal(),b.surface(),a.corners()));
            if(mode==2)primitives.add(new SceneInputs.Primitive(a.part(),99,a.surface(),a.corners()));
            if(mode==3)primitives.set(2,new SceneInputs.Primitive(b.part(),b.ordinal(),b.surface(),b.corners().stream().map(c->
                new SceneInputs.Corner(new SceneInputs.Vec3(c.position().x(),c.position().y(),c.position().z()==-1.5f?Math.nextUp(c.position().z()):c.position().z()),c.u(),c.v(),c.tint(),c.normal(),c.tangent())).toList()));
            var meshes=new ArrayList<>(base.geometry());meshes.set(1,TiledContactTest.compile(g,primitives));var frame=FluidVolumeCompilerTest.frame(2+mode,JoinedModelVolumesTest.revision(meshes));
            var result=compiler.prepare(frame);assertSame(frame,result.scene());assertEquals(ModelVolumeCompiler.Status.HIDDEN_INTERFACE,result.report().status(),mode+" "+result.report());
            assertEquals(accepted.scene().mediumDomain(),compiler.prepare(FluidVolumeCompilerTest.frame(10+mode,base)).scene().mediumDomain());
        }
    }
    static SceneStore.Revision separateSources() {
        return separateSources(source(0));
    }
    static SceneStore.Revision separateSources(SceneStore.Revision base) {
        var g=base.geometry().get(1).input();var meshes=new ArrayList<>(base.geometry());
        meshes.set(1,TiledContactTest.compile(g,g.primitives().stream().filter(p->p.part().low()!=2).toList()));
        for(var part:g.primitives())if(part.part().low()==2) {
            float offset=part.ordinal()*16;
            var corners=part.corners().stream().map(c->new SceneInputs.Corner(new SceneInputs.Vec3(c.position().x()-offset,c.position().y(),c.position().z()),c.u(),c.v(),c.tint(),c.normal(),c.tangent())).toList();
            meshes.add(new SceneCompiler().compile(new SceneInputs.Geometry(new SceneInputs.Key(5700,part.ordinal()+1),g.revision(),new SceneInputs.Origin(g.origin().x()+offset,g.origin().y(),g.origin().z()),g.current(),g.previous(),false,g.motion(),g.participation(),
                List.of(new SceneInputs.Primitive(part.part(),part.ordinal(),part.surface(),corners)))));
        }
        return JoinedModelVolumesTest.revision(meshes);
    }
    @Test void allSupportingGenerationsMustRemainPresentIncludingNonAnchorMeshes() {
        var source=separateSources();var result=new ModelVolumeCompiler().prepare(FluidVolumeCompilerTest.frame(1,source));assertEquals(ModelVolumeCompiler.Status.READY,result.report().status(),result.report().toString());
        var anchor=result.scene().revision().geometry().stream().filter(g->!g.clipContacts().isEmpty()).findFirst().orElseThrow();
        var patch=anchor.clipContacts().getFirst();assertEquals(2,patch.supports().size());assertEquals(new SceneInputs.Key(5700,1),anchor.input().key());
        var classifier=new SceneMediaCompiler();var original=classifier.prepare(result.scene().revision(),result.scene().mediumDomain().orElseThrow());
        var frame=original.classify(SceneMediaCompilerTest.at(new SceneInputs.Origin(.5,64.35,-1.5)));
        for(int mode=0;mode<2;mode++) {
            var meshes=new ArrayList<>(result.scene().revision().geometry());var last=meshes.removeLast().input();
            if(mode==1) {
                var r=last.revision();var revision=new SceneInputs.Revision(r.world(),r.topology(),r.deformation(),r.placement(),r.appearance(),r.resources()+1,r.coverage());
                meshes.add(new SceneCompiler().compile(new SceneInputs.Geometry(last.key(),revision,last.origin(),last.current(),last.previous(),false,last.motion(),last.participation(),last.primitives())));
            }
            var stale=JoinedModelVolumesTest.revision(meshes);
            assertThrows(IllegalArgumentException.class,()->classifier.prepare(stale,result.scene().mediumDomain().orElseThrow()));
            assertThrows(IllegalArgumentException.class,()->InitialMedia.validate(frame,1,1,meshes));
        }
        assertEquals(1,classifier.prepare(result.scene().revision(),result.scene().mediumDomain().orElseThrow()).classify(SceneMediaCompilerTest.at(new SceneInputs.Origin(.5,64.35,-1.5))).initialMedia().orElseThrow().enclosures().size());
    }
    @Test void largeCoordinatesSourceRtRenderExperimentderAndBytePressureKeepTheSameBoundaryMeaning() {
        var source=separateSources();var shifted=new ArrayList<SceneCompiler.Compiled>();
        for(var mesh:source.geometry()) {
            var g=mesh.input();shifted.add(new SceneCompiler().compile(new SceneInputs.Geometry(g.key(),g.revision(),new SceneInputs.Origin(g.origin().x()+30_000_000,g.origin().y(),g.origin().z()-30_000_000),g.current(),g.previous(),false,g.motion(),g.participation(),g.primitives(),g.fluids(),g.opticalModels())));
        }
        var compiler=new ModelVolumeCompiler();var first=compiler.prepare(FluidVolumeCompilerTest.frame(1,JoinedModelVolumesTest.revision(shifted)));assertEquals(ModelVolumeCompiler.Status.READY,first.report().status(),first.report().toString());
        java.util.Collections.reverse(shifted);var second=compiler.prepare(FluidVolumeCompilerTest.frame(2,JoinedModelVolumesTest.revision(shifted)));assertEquals(first.scene().mediumDomain(),second.scene().mediumDomain());
        var classified=new SceneMediaCompiler().prepare(second.scene().revision(),second.scene().mediumDomain().orElseThrow()).classify(SceneMediaCompilerTest.at(new SceneInputs.Origin(30_000_000.5,64.35,-30_000_001.5)));
        assertEquals(1,classified.initialMedia().orElseThrow().enclosures().size());
        var frame=FluidVolumeCompilerTest.frame(3,source);var rejected=new ModelVolumeCompiler(new ModelVolumeCompiler.Limits(8,1000,4096,32,1)).prepare(frame);assertSame(frame,rejected.scene());assertEquals(ModelVolumeCompiler.Status.PRESSURE,rejected.report().status());
    }
    @Test void aSupportingResourceEpochRequalifiesWithoutChangingTheBoundaryShape() {
        var source=separateSources();var compiler=new ModelVolumeCompiler();var first=compiler.prepare(FluidVolumeCompilerTest.frame(1,source));
        var meshes=new ArrayList<>(source.geometry());var g=meshes.getLast().input();var r=g.revision();
        var epoch=new SceneInputs.Revision(r.world(),r.topology(),r.deformation(),r.placement(),r.appearance(),r.resources()+1,r.coverage());
        var changed=new SceneCompiler().compile(new SceneInputs.Geometry(g.key(),epoch,g.origin(),g.current(),g.previous(),false,g.motion(),g.participation(),g.primitives()));meshes.set(meshes.size()-1,changed);
        var second=compiler.prepare(FluidVolumeCompilerTest.frame(2,JoinedModelVolumesTest.revision(meshes)));
        assertEquals(ModelVolumeCompiler.Status.READY,second.report().status(),second.report().toString());assertFalse(second.report().reused());assertEquals(first.scene().mediumDomain(),second.scene().mediumDomain());
        var a=first.scene().revision().geometry().stream().filter(m->!m.clipContacts().isEmpty()).findFirst().orElseThrow();
        var b=second.scene().revision().geometry().stream().filter(m->!m.clipContacts().isEmpty()).findFirst().orElseThrow();
        assertEquals(a.tracePositionHash(),b.tracePositionHash());assertTrue(b.clipContacts().getFirst().supports().stream().anyMatch(s->s.mesh()==changed));
        ClipGeometry.requireSources(second.scene().revision().geometry());
    }
    @Test void exactConstructionIsBoundedByTheGeometricWorkGrant() {
        var source=source(4);var fluid=source.geometry().getFirst();var request=new FluidCompiler().interfaces(fluid.input().fluids().getFirst(),1<<5).getFirst();
        var points=request.corners().stream().map(c->new ConvexVolume.Point(c.position().x(),c.position().y(),c.position().z())).toList();
        assertThrows(IllegalStateException.class,()->OpaqueContacts.match(List.of(new OpaqueContacts.Request(fluid.input(),points)),source.geometry(),100,new ConvexVolume.Budget(80)));
        assertEquals(ModelVolumeCompiler.Status.READY,new ModelVolumeCompiler().prepare(FluidVolumeCompilerTest.frame(1,source)).report().status());
    }
}
