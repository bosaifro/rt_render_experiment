package dev.rt_render_experiment.engine;

import java.util.List;
import dev.rt_render_experiment.contract.DynamicInputs;
import dev.rt_render_experiment.contract.SceneInputs;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class DynamicReuseTest {
    static DynamicInputs.PreparedObject source(float x,long topology,long appearance) {
        var geometry=SceneFixtures.prepare(false).geometry().getFirst().input();
        var transform=new SceneInputs.Transform(new float[]{1,0,0,x,0,1,0,0,0,0,1,0});
        return new DynamicInputs.PreparedObject(geometry.key(),geometry.origin(),transform,SceneInputs.Motion.RIGID,geometry.participation(),topology,appearance,geometry.primitives());
    }
    @Test void rigidPlacementUsesTheSameImmutableCompiledLocalGeometry() {
        var compiler=new DynamicSceneCompiler();var first=compiler.prepare(1,1,List.of(source(0,1,1)),4096);
        var previous=first.geometry().getFirst();first.submitted();
        var moved=compiler.prepare(1,1,List.of(source(.25f,1,1)),4096);var current=moved.geometry().getFirst();
        assertTrue(current.sharesStorage(previous),"Rigid placement rebuilt unchanged local geometry");
        assertEquals(previous.input().revision().topology(),current.input().revision().topology());
        assertTrue(current.input().revision().placement()>previous.input().revision().placement());
        assertTrue(current.input().previousValid());assertEquals(previous.input().current().get(0,3),current.input().previous().get(0,3));
        assertEquals(1,moved.work().reused());assertEquals(0,moved.work().compiled());
    }
    static DynamicInputs.PreparedObject update(DynamicInputs.PreparedObject source,SceneInputs.Origin origin,SceneInputs.Transform transform,SceneInputs.Motion motion,
                                               long topology,long appearance,List<SceneInputs.Primitive> primitives) {
        return new DynamicInputs.PreparedObject(source.key(),origin,transform,motion,source.participation(),topology,appearance,primitives);
    }
    static List<DynamicInputs.PreparedObject> sequence() {
        var base=source(0,1,1);var moving=source(.25f,1,1);
        var negative=update(moving,moving.origin(),new SceneInputs.Transform(new float[]{-1.25f,0,0,.3f,0,.8f,0,0,0,0,1,0}),SceneInputs.Motion.RIGID,1,1,moving.primitives());
        var topology=update(negative,negative.origin(),negative.transform(),negative.motion(),2,1,negative.primitives());
        var appearance=update(topology,topology.origin(),topology.transform(),topology.motion(),2,2,topology.primitives());
        var p=appearance.primitives().getFirst();var corners=p.corners().stream().map(c->new SceneInputs.Corner(c.position(),c.u()+.1f,c.v(),c.tint(),c.normal(),c.tangent(),c.emissionUv(),c.emissionTint(),c.overlayTexel())).toList();
        var uv=update(appearance,appearance.origin(),appearance.transform(),appearance.motion(),2,2,List.of(new SceneInputs.Primitive(p.part(),p.ordinal(),p.surface(),corners)));
        var deforming=update(uv,uv.origin(),uv.transform(),SceneInputs.Motion.DEFORMING,2,2,uv.primitives());
        var shifted=update(deforming,deforming.origin(),new SceneInputs.Transform(new float[]{-1.25f,0,0,.5f,0,.8f,0,.125f,0,0,1,0}),deforming.motion(),2,2,deforming.primitives());
        return List.of(base,moving,negative,topology,appearance,uv,deforming,deforming,shifted);
    }
    @Test void cacheAndFreshCompilationPreserveAllRevisionsBytesAndCorrespondence() {
        var cached=new DynamicSceneCompiler();var fresh=new DynamicSceneCompiler(false);SceneCompiler.Compiled previous=null;int reused=0;
        var sequence=sequence();
        for(int step=0;step<sequence.size();step++) {
            var a=cached.prepare(1,1,List.of(sequence.get(step)),8192);var b=fresh.prepare(1,1,List.of(sequence.get(step)),8192);
            var x=a.geometry().getFirst();var y=b.geometry().getFirst();same(x,y);reused+=a.work().reused();
            if(step==1 || step==2 || step==3 || step==4 || step==7)assertTrue(x.sharesStorage(previous),"Matching local contents did not reuse at step "+step);
            if(step==3)assertFalse(x.input().previousValid(),"Explicit same-count topology replacement gained false correspondence");
            if(step==5 || step==6 || step==8)assertEquals(1,a.work().compiled(),"Changed prepared/deformed contents did not compile");
            a.submitted();b.submitted();previous=x;
        }
        assertEquals(5,reused);assertEquals(5,cached.statistics().reusedObjects());assertEquals(0,fresh.statistics().reusedObjects());
    }
    static void same(SceneCompiler.Compiled a,SceneCompiler.Compiled b) {
        assertEquals(a.input().key(),b.input().key());assertEquals(a.input().revision(),b.input().revision());assertEquals(a.input().origin(),b.input().origin());
        assertArrayEquals(a.input().current().rows(),b.input().current().rows());assertArrayEquals(a.input().previous().rows(),b.input().previous().rows());
        assertEquals(a.input().previousValid(),b.input().previousValid());assertEquals(a.input().motion(),b.input().motion());assertEquals(a.input().participation(),b.input().participation());
        assertEquals(a.parts(),b.parts());assertEquals(a.topologyHash(),b.topologyHash());assertEquals(a.appearanceHash(),b.appearanceHash());
        assertEquals(a.deformationHash(),b.deformationHash());assertEquals(a.shadingHash(),b.shadingHash());assertEquals(a.positionHash(),b.positionHash());
        assertEquals(-1,a.positions().mismatch(b.positions()));assertEquals(-1,a.corners().mismatch(b.corners()));assertEquals(-1,a.indices().mismatch(b.indices()));
    }
    @Test void abandonedFailedReloadedAndReenteredInputsCannotBecomeTheReuseAuthority() {
        var compiler=new DynamicSceneCompiler();var initial=compiler.prepare(1,1,List.of(source(0,1,1)),4096);initial.submitted();var before=initial.geometry().getFirst();
        var abandoned=compiler.prepare(1,1,List.of(source(2,1,1)),4096);
        assertThrows(IllegalStateException.class,()->compiler.prepare(1,1,List.of(source(.25f,1,1)),1));
        var next=compiler.prepare(1,1,List.of(source(.25f,1,1)),4096);assertEquals(before.input().current().get(0,3),next.geometry().getFirst().input().previous().get(0,3));
        assertEquals(1,compiler.statistics().submittedObjects());next.submitted();assertThrows(IllegalStateException.class,abandoned::submitted);
        var reloaded=compiler.prepare(1,2,List.of(source(.25f,1,1)),4096);assertEquals(0,reloaded.work().reused());assertFalse(reloaded.geometry().getFirst().sharesStorage(next.geometry().getFirst()));reloaded.submitted();
        compiler.clear();var returned=compiler.prepare(1,2,List.of(source(.25f,1,1)),4096);assertEquals(0,returned.work().reused());assertFalse(returned.geometry().getFirst().input().previousValid());returned.submitted();
        var world=compiler.prepare(2,2,List.of(source(.25f,1,1)),4096);assertEquals(0,world.work().reused());assertFalse(world.geometry().getFirst().input().previousValid());
        assertEquals(1,compiler.statistics().reusedObjects());
    }
}
