package dev.rt_render_experiment.engine;

import java.util.List;
import java.util.Set;
import dev.rt_render_experiment.contract.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class DynamicOpticalModelTest {
    static SceneInputs.Geometry source(Set<Long> hidden,SceneInputs.Origin origin,SceneInputs.Transform pose) {
        return JoinedModelVolumesTest.cell(1,new JoinedModelVolumesTest.Cell(0,0,0),hidden,origin,pose);
    }
    static DynamicInputs.PreparedObject object(SceneInputs.Geometry source,SceneInputs.Motion motion) {
        return new DynamicInputs.PreparedObject(source.key(),source.origin(),source.current(),motion,source.participation(),1,1,source.primitives(),source.opticalModels());
    }
    @Test void actualDynamicCompilationRetainsTheCompleteModelAndQualifiesItsMedium() {
        var source=source(Set.of(),JoinedModelVolumesTest.ORIGIN,SceneInputs.Transform.identity());
        var unqualified=new DynamicInputs.PreparedObject(source.key(),source.origin(),source.current(),SceneInputs.Motion.RIGID,source.participation(),1,1,source.primitives());
        var lost=new DynamicSceneCompiler().prepare(1,1,List.of(unqualified),1<<20);
        assertEquals(ModelVolumeCompiler.Status.INCOMPLETE_MODEL,qualify(lost).report().status());
        var compiler=new DynamicSceneCompiler();var prepared=compiler.prepare(1,1,List.of(object(source,SceneInputs.Motion.RIGID)),1<<20);
        assertEquals(1,prepared.geometry().getFirst().modelBoundaries().size());
        assertEquals(ModelVolumeCompiler.Status.READY,qualify(prepared).report().status());
        prepared.submitted();var same=compiler.prepare(1,1,List.of(object(source,SceneInputs.Motion.RIGID)),1<<20);same.submitted();
        var stable=compiler.prepare(1,1,List.of(object(source,SceneInputs.Motion.RIGID)),1<<20);
        assertSame(same.geometry().getFirst(),stable.geometry().getFirst());assertEquals(1,stable.work().reused());
    }
    @Test void deformationUsesTheSamePosedCornersForVisibleAndHiddenModelFacts() {
        var pose=new SceneInputs.Transform(new float[]{-1,0,0,3,0,2,0,1,0,0,.5f,-4});
        var source=source(Set.of(0L),new SceneInputs.Origin(30_000_000,64,-30_000_000),pose);
        var prepared=new DynamicSceneCompiler().prepare(1,1,List.of(object(source,SceneInputs.Motion.DEFORMING)),1<<20);
        var input=prepared.geometry().getFirst().input();var model=input.opticalModels().getFirst();
        assertEquals(Set.of(0L),model.occludedOrdinals());assertEquals(6,model.surfaces().size());assertEquals(5,input.primitives().size());
        for(var p:input.primitives())assertSame(p,model.surfaces().stream().filter(q->q.ordinal()==p.ordinal()).findFirst().orElseThrow());
        for(int face=0;face<6;face++)for(int corner=0;corner<4;corner++) {
            int originalCorner=corner==0?0:4-corner;
            var authored=source.opticalModels().getFirst().surfaces().get(face).corners().get(originalCorner);
            var original=authored.position();
            var current=model.surfaces().get(face).corners().get(corner).position();
            assertEquals(authored.u(),model.surfaces().get(face).corners().get(corner).u());
            assertEquals(authored.v(),model.surfaces().get(face).corners().get(corner).v());
            double[] world={source.origin().x(),source.origin().y(),source.origin().z()},anchor={input.origin().x(),input.origin().y(),input.origin().z()};
            for(int axis=0;axis<3;axis++)assertEquals(world[axis]+pose.get(axis,0)*original.x()+pose.get(axis,1)*original.y()+pose.get(axis,2)*original.z()+pose.get(axis,3),
                anchor[axis]+new float[]{current.x(),current.y(),current.z()}[axis],0);
        }
        assertTrue(prepared.geometry().getFirst().modelBoundaries().getFirst().components().getFirst().edgeClosed());
        assertTrue(prepared.geometry().getFirst().modelBoundaries().getFirst().components().getFirst().signedVolume()>0);
    }
    @Test void aBakedReflectionRetainsTheQuadDiagonalButInvalidatesOldTriangleCorrespondence() {
        var positive=source(Set.of(),JoinedModelVolumesTest.ORIGIN,SceneInputs.Transform.identity());var compiler=new DynamicSceneCompiler();
        var first=compiler.prepare(1,1,List.of(object(positive,SceneInputs.Motion.DEFORMING)),1<<20);first.submitted();
        var negative=source(Set.of(),positive.origin(),new SceneInputs.Transform(new float[]{-1,0,0,0,0,1,0,0,0,0,1,0}));
        var reflected=compiler.prepare(1,1,List.of(object(negative,SceneInputs.Motion.DEFORMING)),1<<20);
        assertEquals(ModelVolumeCompiler.Status.READY,qualify(reflected).report().status());
        var mesh=reflected.geometry().getFirst();assertFalse(mesh.input().previousValid());
        assertTrue(mesh.input().revision().topology()>first.geometry().getFirst().input().revision().topology());
        reflected.submitted();
        var stable=compiler.prepare(1,1,List.of(object(negative,SceneInputs.Motion.DEFORMING)),1<<20);assertTrue(stable.geometry().getFirst().input().previousValid());
    }
    @Test void hiddenFactsAreBudgetedAndCannotConcealSourceDisagreement() {
        var source=source(Set.of(0L),JoinedModelVolumesTest.ORIGIN,SceneInputs.Transform.identity());var compiler=new DynamicSceneCompiler();
        long physical=source.primitives().stream().mapToLong(p->SceneCompiler.primitiveBytes(p.corners().size())).sum();
        assertThrows(IllegalStateException.class,()->compiler.prepare(1,1,List.of(object(source,SceneInputs.Motion.RIGID)),physical));
        var bad=new DynamicInputs.PreparedObject(source.key(),source.origin(),source.current(),SceneInputs.Motion.DEFORMING,source.participation(),1,1,
            source.primitives().subList(1,source.primitives().size()),source.opticalModels());
        assertThrows(IllegalArgumentException.class,()->compiler.prepare(1,1,List.of(bad),1<<20));
        var good=compiler.prepare(1,1,List.of(object(source,SceneInputs.Motion.RIGID)),1<<20);good.submitted();
    }
    @Test void hiddenOnlyChangesReloadAndCancelledPosesInvalidateTheirOwnDerivatives() {
        var source=source(Set.of(),JoinedModelVolumesTest.ORIGIN,SceneInputs.Transform.identity());var compiler=new DynamicSceneCompiler();
        var first=compiler.prepare(1,1,List.of(object(source,SceneInputs.Motion.RIGID)),1<<20);first.submitted();
        var pose=new SceneInputs.Transform(new float[]{1,0,0,.25f,0,1,0,0,0,0,1,0});
        compiler.prepare(1,1,List.of(object(source(Set.of(),source.origin(),pose),SceneInputs.Motion.RIGID)),1<<20);
        var again=compiler.prepare(1,1,List.of(object(source,SceneInputs.Motion.RIGID)),1<<20);assertEquals(1,again.work().reused());
        assertEquals(0,again.geometry().getFirst().input().previous().get(0,3));again.submitted();
        var hidden=source(Set.of(0L),source.origin(),pose);var changed=compiler.prepare(1,2,List.of(object(hidden,SceneInputs.Motion.RIGID)),1<<20);
        assertEquals(Set.of(0L),changed.geometry().getFirst().input().opticalModels().getFirst().occludedOrdinals());
        assertEquals(2,changed.geometry().getFirst().input().revision().resources());assertEquals(0,changed.work().reused());
    }
    private static ModelVolumeCompiler.Result qualify(DynamicSceneCompiler.Prepared dynamic) {
        var frame=new FrameScene(1,new SceneStore.Revision(1,1,List.of(),0),dynamic,List.of(),LightInputs.Publication.empty()).withPreparedVolumes();
        return new ModelVolumeCompiler().prepare(frame);
    }
}
