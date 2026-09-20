package dev.rt_render_experiment.engine;

import java.util.ArrayList;
import java.util.List;
import dev.rt_render_experiment.contract.DynamicInputs;
import dev.rt_render_experiment.contract.LightInputs;
import dev.rt_render_experiment.contract.SceneInputs;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;


final class MovingContactTest {
    static final SceneInputs.Key WALL=new SceneInputs.Key(5800,1);
    static final SceneInputs.Origin ORIGIN=new SceneInputs.Origin(0,64,0);
    static SceneStore.Revision resident(SceneInputs.Origin origin) {
        var base=CrossingContactTest.decorate(FluidContactFixtures.scene(false,true,false,origin),4);
        var meshes=new ArrayList<>(base.geometry());var g=meshes.get(1).input();
        meshes.set(1,TiledContactTest.compile(g,g.primitives().stream().filter(p->p.part().low()!=2).toList()));
        return JoinedModelVolumesTest.revision(meshes);
    }
    static DynamicInputs.PreparedObject wall(SceneInputs.Motion motion,float displacement,boolean topology,SceneInputs.Origin origin) {
        var source=CrossingContactTest.source(4).geometry().get(1).input();
        var primitives=source.primitives().stream().filter(p->p.part().low()==2).toList();
        if(topology)primitives=primitives.stream().map(p->new SceneInputs.Primitive(p.part(),p.ordinal()+10,p.surface(),p.corners())).toList();
        var rows=SceneInputs.Transform.identity().rows();rows[7]=displacement;
        return new DynamicInputs.PreparedObject(WALL,origin,new SceneInputs.Transform(rows),motion,source.participation(),topology?2:1,1,primitives);
    }
    static FrameScene frame(long serial,SceneStore.Revision resident,DynamicSceneCompiler.Prepared dynamic) {
        return new FrameScene(serial,resident,dynamic,List.of(),LightInputs.Publication.empty()).withPreparedVolumes();
    }
    @Test void preparedRigidAndDeformingSupportsCloseTheCurrentFluidBoundary() {
        for(var motion:List.of(SceneInputs.Motion.RIGID,SceneInputs.Motion.DEFORMING)) {
            var dynamic=new DynamicSceneCompiler();var compiler=new ModelVolumeCompiler();var resident=resident(ORIGIN);
            ModelVolumeCompiler.Result previous=null;
            for(int step=0;step<3;step++) {
                var prepared=dynamic.prepare(1,1,List.of(wall(motion,-step*.25f,false,ORIGIN)),1<<20);
                var result=compiler.prepare(frame(step+1,resident,prepared));
                assertEquals(ModelVolumeCompiler.Status.READY,result.report().status(),motion+" "+result.report());
                var physical=prepared.geometry().getFirst();
                var trace=result.scene().revision().geometry().stream().filter(g->g.input().key().equals(WALL)).findFirst().orElseThrow();
                assertSame(physical.input(),trace.input());assertEquals(2,trace.clipContacts().getFirst().supports().size());
                assertEquals(-1,physical.positions().mismatch(trace.positions()));assertEquals(-1,physical.corners().mismatch(trace.corners()));
                assertEquals(-1,physical.indices().mismatch(trace.indices()));
                assertEquals(physical.triangleCount()+2,trace.traceTriangleCount());
                var media=new SceneMediaCompiler().prepare(result.scene().revision(),result.scene().mediumDomain().orElseThrow());
                assertEquals(1,media.classify(SceneMediaCompilerTest.at(new SceneInputs.Origin(.5,64.35,-1.5))).initialMedia().orElseThrow().enclosures().size());
                assertEquals(0,media.classify(SceneMediaCompilerTest.at(new SceneInputs.Origin(.5,64.9,-1.5))).initialMedia().orElseThrow().enclosures().size());
                if(previous!=null) {
                    assertEquals(previous.scene().mediumDomain(),result.scene().mediumDomain());assertFalse(result.report().reused());
                    assertTrue(physical.input().previousValid());
                }
                prepared.submitted();previous=result;
            }
        }
    }
    @Test void currentCoverageLossAndCancelledPreparationsRetainTheSubmittedPredecessor() {
        for(var motion:List.of(SceneInputs.Motion.RIGID,SceneInputs.Motion.DEFORMING)) {
            var dynamic=new DynamicSceneCompiler();var compiler=new ModelVolumeCompiler();var resident=resident(ORIGIN);
            var first=dynamic.prepare(1,1,List.of(wall(motion,0,false,ORIGIN)),1<<20);
            var accepted=compiler.prepare(frame(1,resident,first));assertEquals(ModelVolumeCompiler.Status.READY,accepted.report().status());first.submitted();
            var before=first.geometry().getFirst();
            for(int missing=0;missing<2;missing++) {
                var candidate=dynamic.prepare(1,1,missing==0?List.of(wall(motion,.25f,false,ORIGIN)):List.of(),1<<20);
                var input=frame(2+missing,resident,candidate);var rejected=compiler.prepare(input);
                assertSame(input,rejected.scene());assertEquals(ModelVolumeCompiler.Status.HIDDEN_INTERFACE,rejected.report().status());
                assertTrue(rejected.scene().mediumDomain().isEmpty());
            }
            var retry=dynamic.prepare(1,1,List.of(wall(motion,-.25f,false,ORIGIN)),1<<20);
            assertArrayEquals(before.input().current().rows(),retry.geometry().getFirst().input().previous().rows());
            var restored=compiler.prepare(frame(4,resident,retry));assertEquals(ModelVolumeCompiler.Status.READY,restored.report().status());
            assertEquals(accepted.scene().mediumDomain(),restored.scene().mediumDomain());retry.submitted();
        }
    }
    @Test void staleMovingSupportIsRejectedBeforeCachedClassificationAndInitialStateAdmission() {
        var dynamic=new DynamicSceneCompiler();var resident=resident(ORIGIN);
        var first=dynamic.prepare(1,1,List.of(wall(SceneInputs.Motion.RIGID,0,false,ORIGIN)),1<<20);
        var result=new ModelVolumeCompiler().prepare(frame(1,resident,first));first.submitted();
        var classifier=new SceneMediaCompiler();var media=classifier.prepare(result.scene().revision(),result.scene().mediumDomain().orElseThrow());
        var initial=media.classify(SceneMediaCompilerTest.at(new SceneInputs.Origin(.5,64.35,-1.5)));
        var moved=dynamic.prepare(1,1,List.of(wall(SceneInputs.Motion.RIGID,-.25f,false,ORIGIN)),1<<20).geometry().getFirst();
        var meshes=new ArrayList<>(result.scene().revision().geometry());
        int index=0;while(!meshes.get(index).input().key().equals(WALL))index++;

        var derivative=meshes.get(index);
        assertThrows(IllegalArgumentException.class,()->new SceneCompiler().withInputRevision(derivative,moved.input()));
        meshes.set(index,moved);var stale=JoinedModelVolumesTest.revision(meshes);
        assertThrows(IllegalArgumentException.class,()->classifier.prepare(stale,result.scene().mediumDomain().orElseThrow()));
        assertThrows(IllegalArgumentException.class,()->InitialMedia.validate(initial,1,1,meshes));
        assertEquals(1,media.classify(SceneMediaCompilerTest.at(new SceneInputs.Origin(.5,64.35,-1.5))).initialMedia().orElseThrow().enclosures().size());
    }
    @Test void sameCountTopologyBreaksPhysicalCorrespondenceAndLargeOriginsKeepBoundaryMeaning() {
        var origin=new SceneInputs.Origin(30_000_000,64,-30_000_000);var resident=resident(origin);
        for(var motion:List.of(SceneInputs.Motion.RIGID,SceneInputs.Motion.DEFORMING)) {
            var dynamic=new DynamicSceneCompiler();var compiler=new ModelVolumeCompiler();
            var first=dynamic.prepare(1,1,List.of(wall(motion,0,false,origin)),1<<20);
            var a=compiler.prepare(frame(1,resident,first));assertEquals(ModelVolumeCompiler.Status.READY,a.report().status());first.submitted();
            var second=dynamic.prepare(1,1,List.of(wall(motion,-.25f,true,origin)),1<<20);
            var b=compiler.prepare(frame(2,resident,second));assertEquals(ModelVolumeCompiler.Status.READY,b.report().status());
            assertEquals(first.geometry().getFirst().triangleCount(),second.geometry().getFirst().triangleCount());
            assertFalse(second.geometry().getFirst().input().previousValid());
            assertNotEquals(first.geometry().getFirst().topologyHash(),second.geometry().getFirst().topologyHash());
            assertEquals(a.scene().mediumDomain(),b.scene().mediumDomain());
            var media=new SceneMediaCompiler().prepare(b.scene().revision(),b.scene().mediumDomain().orElseThrow());
            assertEquals(1,media.classify(SceneMediaCompilerTest.at(new SceneInputs.Origin(origin.x()+.5,64.35,origin.z()-1.5))).initialMedia().orElseThrow().enclosures().size());
        }
    }
    @Test void movingSupportStillRequiresPlanarityOpaqueResponseAndCompleteParticipation() {
        var base=wall(SceneInputs.Motion.DEFORMING,0,false,ORIGIN);var resident=resident(ORIGIN);
        for(int mode=0;mode<3;mode++) {
            var parts=new ArrayList<>(base.primitives());var part=parts.getFirst();
            if(mode==0) {
                var corners=new ArrayList<>(part.corners());var c=corners.getFirst();
                corners.set(0,new SceneInputs.Corner(new SceneInputs.Vec3(c.position().x()+.125f,c.position().y(),c.position().z()),c.u(),c.v(),c.tint(),c.normal(),c.tangent()));
                parts.set(0,new SceneInputs.Primitive(part.part(),part.ordinal(),part.surface(),corners));
            }
            if(mode==1) {
                var s=part.surface();var cutout=new SceneInputs.Surface(s.key(),s.material(),s.properties(),s.colorResource(),s.emissionResource(),SceneInputs.Coverage.CUTOUT,.1f,s.doubleSided(),s.medium(),s.layer(),s.layerSeparation(),s.layers(),s.hostOcclusion());
                parts.set(0,new SceneInputs.Primitive(part.part(),part.ordinal(),cutout,part.corners()));
            }
            var object=new DynamicInputs.PreparedObject(base.key(),base.origin(),base.transform(),base.motion(),mode==2?new dev.rt_render_experiment.contract.Participation(7,true):base.participation(),1,1,parts);
            var prepared=new DynamicSceneCompiler().prepare(1,1,List.of(object),1<<20);var input=frame(1,resident,prepared);
            var result=new ModelVolumeCompiler().prepare(input);assertSame(input,result.scene());assertEquals(ModelVolumeCompiler.Status.HIDDEN_INTERFACE,result.report().status(),mode+" "+result.report());
        }
    }
    @Test void explicitNativeReferenceHasTheSameQualifiedPhysicalAndTraceRecords() {
        for(var motion:List.of(SceneInputs.Motion.RIGID,SceneInputs.Motion.DEFORMING)) {
            var resident=resident(ORIGIN);var dynamic=new DynamicSceneCompiler();var compiler=new ModelVolumeCompiler();
            for(int step=0;step<4;step++) {
                if(step==3)dynamic.prepare(1,1,List.of(),1<<20).submitted();
                var prepared=dynamic.prepare(1,1,List.of(wall(motion,-step*.25f,step==2,ORIGIN)),1<<20);
                var explicit=MovingContactFixtures.content(step+1,resident,prepared,false,false);
                var result=compiler.prepare(frame(step+1,resident,prepared));assertEquals(ModelVolumeCompiler.Status.READY,result.report().status());
                new SceneMediaCompiler().prepare(explicit.revision(),explicit.mediumDomain().orElseThrow());
                assertEquals(explicit.mediumDomain(),result.scene().mediumDomain());
                for(int i=0;i<explicit.revision().geometry().size();i++) {
                    var a=explicit.revision().geometry().get(i);var b=result.scene().revision().geometry().get(i);
                    assertSame(a.input(),b.input());assertEquals(a.parts(),b.parts());
                    assertEquals(-1,a.tracePositions().mismatch(b.tracePositions()));assertEquals(-1,a.traceCorners().mismatch(b.traceCorners()));
                    assertEquals(-1,a.traceIndices().mismatch(b.traceIndices()));assertEquals(a.appearanceHash(),b.appearanceHash());
                }
                prepared.submitted();
            }
        }
    }
    @Test void reappearingDeformationUsesAnExactInterfaceSpaceWhenItsNewOriginCannot() {
        var resident=resident(ORIGIN);var dynamic=new DynamicSceneCompiler();var compiler=new ModelVolumeCompiler();
        var first=dynamic.prepare(1,1,List.of(wall(SceneInputs.Motion.DEFORMING,0,false,ORIGIN)),1<<20);
        var original=compiler.prepare(frame(1,resident,first));assertEquals(ModelVolumeCompiler.Status.READY,original.report().status());first.submitted();
        var absent=dynamic.prepare(1,1,List.of(),1<<20);absent.submitted();
        var returned=dynamic.prepare(1,1,List.of(wall(SceneInputs.Motion.DEFORMING,-.5f,true,ORIGIN)),1<<20);
        assertEquals(48,returned.geometry().getFirst().input().origin().y());
        var result=compiler.prepare(frame(3,resident,returned));
        assertEquals(ModelVolumeCompiler.Status.READY,result.report().status(),result.report().toString());
        var anchor=result.scene().revision().geometry().stream().filter(m->!m.clipContacts().isEmpty()).findFirst().orElseThrow();
        assertSame(resident.geometry().getFirst().input(),anchor.input());
        var contact=anchor.clipContacts().getFirst();assertEquals(2,contact.supports().size());
        assertFalse(anchor.parts().contains(contact.owner()));
        for(var support:contact.supports())assertSame(returned.geometry().getFirst(),support.mesh());
        var media=new SceneMediaCompiler();var certified=media.prepare(result.scene().revision(),result.scene().mediumDomain().orElseThrow());
        var initial=certified.classify(SceneMediaCompilerTest.at(new SceneInputs.Origin(.5,64.35,-1.5)));
        assertEquals(1,initial.initialMedia().orElseThrow().enclosures().size());
        var stale=new ArrayList<>(result.scene().revision().geometry());stale.removeIf(m->m.input().key().equals(WALL));
        assertThrows(IllegalArgumentException.class,()->media.prepare(JoinedModelVolumesTest.revision(stale),result.scene().mediumDomain().orElseThrow()));
        assertThrows(IllegalArgumentException.class,()->InitialMedia.validate(initial,1,3,stale));
    }
    @Test void oneContainingSupportAlsoUsesExactInterfaceSpaceUnderTheSameByteGrant() {
        var original=wall(SceneInputs.Motion.DEFORMING,-.5f,false,ORIGIN);var a=original.primitives().getFirst();
        var corners=new ArrayList<>(a.corners());corners.add(original.primitives().getLast().corners().get(1));
        var quad=new SceneInputs.Primitive(a.part(),a.ordinal(),a.surface(),corners);
        var object=new DynamicInputs.PreparedObject(original.key(),original.origin(),original.transform(),original.motion(),original.participation(),1,1,List.of(quad));
        var dynamic=new DynamicSceneCompiler().prepare(1,1,List.of(object),1<<20);var resident=resident(ORIGIN);var input=frame(1,resident,dynamic);
        var result=new ModelVolumeCompiler().prepare(input);assertEquals(ModelVolumeCompiler.Status.READY,result.report().status(),result.report().toString());
        var anchor=result.scene().revision().geometry().stream().filter(m->!m.clipContacts().isEmpty()).findFirst().orElseThrow();
        assertSame(resident.geometry().getFirst().input(),anchor.input());assertEquals(1,anchor.clipContacts().getFirst().supports().size());
        assertEquals(-1,resident.geometry().getFirst().positions().mismatch(anchor.positions()));
        long bytes=ClipGeometry.allocationBytes(resident.geometry().getFirst(),anchor.clipContacts());
        assertEquals(bytes,anchor.bytes()-resident.geometry().getFirst().bytes());
        var refused=new ModelVolumeCompiler(new ModelVolumeCompiler.Limits(8,1000,4096,32,bytes-1)).prepare(input);
        assertSame(input,refused.scene());assertEquals(ModelVolumeCompiler.Status.PRESSURE,refused.report().status());
    }
}
