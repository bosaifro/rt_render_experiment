package dev.rt_render_experiment.engine;

import java.util.List;
import dev.rt_render_experiment.contract.SceneInputs;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;


final class DynamicPublicationReuseTest {
    @Test void unchangedMovingSupportReusesItsQualifiedSceneAfterCorrespondenceSettles() {
        for(var motion:List.of(SceneInputs.Motion.RIGID,SceneInputs.Motion.DEFORMING)) {
            var compiler=new DynamicSceneCompiler();var volumes=new ModelVolumeCompiler();var resident=MovingContactTest.resident(MovingContactTest.ORIGIN);
            SceneCompiler.Compiled previous=null;ModelVolumeCompiler.Result before=null;
            for(int frame=0;frame<4;frame++) {
                var input=MovingContactTest.wall(motion,0,false,MovingContactTest.ORIGIN);
                var prepared=compiler.prepare(1,1,List.of(input),1<<20);var geometry=prepared.geometry().getFirst();
                var result=volumes.prepare(MovingContactTest.frame(frame+1,resident,prepared));
                assertEquals(ModelVolumeCompiler.Status.READY,result.report().status(),result.report().toString());
                if(frame>=2) {
                    assertSame(previous,geometry,"Unchanged dynamic facts discarded their immutable publication");
                    assertTrue(result.report().reused(),"Unchanged support repeated geometric contact qualification");
                    assertEquals(0,result.report().geometricWork());
                    assertSame(before.scene().revision().geometry().getLast(),result.scene().revision().geometry().getLast());
                }
                prepared.submitted();previous=geometry;before=result;
            }
        }
    }
    @Test void movementStoppingAndTopologyChangesKeepFreshCompilationCorrespondence() {
        var cached=new DynamicSceneCompiler();var fresh=new DynamicSceneCompiler(false);SceneCompiler.Compiled previous=null;
        for(int frame=0;frame<9;frame++) {
            var source=DynamicReuseTest.source(frame<3?0:.25f,frame<6?1:2,1);
            var a=cached.prepare(1,1,List.of(source),8192);var b=fresh.prepare(1,1,List.of(source),8192);
            var current=a.geometry().getFirst();DynamicReuseTest.same(current,b.geometry().getFirst());
            if(frame==2 || frame==5 || frame==8)assertSame(previous,current);
            if(frame==1 || frame==3 || frame==4 || frame==6 || frame==7)assertNotSame(previous,current,"A correspondence transition reused stale frame facts");
            if(frame==3)assertNotEquals(current.input().current().get(0,3),current.input().previous().get(0,3));
            if(frame==4)assertEquals(current.input().current().get(0,3),current.input().previous().get(0,3));
            if(frame==6)assertFalse(current.input().previousValid());
            a.submitted();b.submitted();previous=current;
        }
    }
    @Test void aRetainedPublicationDoesNotCommitAnAbandonedOrOutOfOrderPreparation() {
        var compiler=new DynamicSceneCompiler();SceneCompiler.Compiled stable=null;
        for(int frame=0;frame<3;frame++) {
            var p=compiler.prepare(1,1,List.of(DynamicReuseTest.source(0,1,1)),8192);stable=p.geometry().getFirst();p.submitted();
        }
        var abandoned=compiler.prepare(1,1,List.of(DynamicReuseTest.source(2,1,1)),8192);
        var a=compiler.prepare(1,1,List.of(DynamicReuseTest.source(0,1,1)),8192);
        var b=compiler.prepare(1,1,List.of(DynamicReuseTest.source(0,1,1)),8192);
        assertSame(stable,a.geometry().getFirst());assertSame(stable,b.geometry().getFirst());
        a.submitted();assertThrows(IllegalStateException.class,b::submitted);assertThrows(IllegalStateException.class,abandoned::submitted);
        assertEquals(4,compiler.statistics().submittedPreparations());
        var next=compiler.prepare(1,1,List.of(DynamicReuseTest.source(.25f,1,1)),8192).geometry().getFirst();
        assertEquals(stable.input().current().get(0,3),next.input().previous().get(0,3));
    }
    @Test void resourceAppearanceAndParticipationChangesCannotReuseTheOldPublication() {
        for(int change=0;change<3;change++) {
            var compiler=new DynamicSceneCompiler();var source=DynamicReuseTest.source(0,1,1);SceneCompiler.Compiled before=null;
            for(int frame=0;frame<3;frame++) {
                var p=compiler.prepare(1,1,List.of(source),8192);before=p.geometry().getFirst();p.submitted();
            }
            var changed=new dev.rt_render_experiment.contract.DynamicInputs.PreparedObject(source.key(),source.origin(),source.transform(),source.motion(),
                change==2?new dev.rt_render_experiment.contract.Participation(14,false):source.participation(),source.topologyRevision(),change==1?2:source.appearanceRevision(),source.primitives());
            var current=compiler.prepare(1,change==0?2:1,List.of(changed),8192).geometry().getFirst();assertNotSame(before,current);
            if(change==0)assertEquals(2,current.input().revision().resources());
            if(change==1)assertNotEquals(before.input().revision().appearance(),current.input().revision().appearance());
            if(change==2)assertFalse(current.input().participation().primaryVisible());
        }
    }
}
