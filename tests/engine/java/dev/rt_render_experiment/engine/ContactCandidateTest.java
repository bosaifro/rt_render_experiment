package dev.rt_render_experiment.engine;

import java.util.ArrayList;
import java.util.List;
import dev.rt_render_experiment.contract.MaterialInputs;
import dev.rt_render_experiment.contract.Participation;
import dev.rt_render_experiment.contract.SceneInputs;
import dev.rt_render_experiment.contract.SurfaceProperties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ContactCandidateTest {
    private static final SceneInputs.Origin ORIGIN=new SceneInputs.Origin(0,272,-16);
    private static final List<ConvexVolume.Point> INTERFACE=List.of(new ConvexVolume.Point(1,4,14),new ConvexVolume.Point(1,3,14),
        new ConvexVolume.Point(1,3,15),new ConvexVolume.Point(1,4,15));

    static SceneCompiler.Compiled capturedFilter() {
        var zero=new SceneInputs.Key(0,0);var key=new SceneInputs.Key(7997108434171719935L,7997108434171719935L);
        var surface=new SceneInputs.Surface(key,43,SurfaceProperties.TRANSLUCENT,zero,zero,SceneInputs.Coverage.FILTER,0,false,0,0,0,MaterialInputs.Layers.plain(zero),SceneInputs.HostOcclusion.PASS);
        var corners=INTERFACE.stream().map(p->new SceneInputs.Corner(new SceneInputs.Vec3((float)p.x()-1,(float)p.y()-3,(float)p.z()-14),0,0,
            new SceneInputs.Color(.3f,.6f,.9f,1),new SceneInputs.Vec3(-1,0,0),new SceneInputs.Vec3(0,1,0))).toList();
        return new SceneCompiler().compile(new SceneInputs.Geometry(new SceneInputs.Key(-1,80),new SceneInputs.Revision(2,1,1,1,1,2,1),ORIGIN,
            new SceneInputs.Transform(new float[]{1,0,0,1,0,1,0,3,0,0,1,14}),SceneInputs.Transform.identity(),false,SceneInputs.Motion.RIGID,
            new Participation(14,false),List.of(new SceneInputs.Primitive(key,4,surface,corners))));
    }
    private static SceneCompiler.Compiled support(boolean reverse) {
        var zero=new SceneInputs.Key(0,0);var key=new SceneInputs.Key(2,1);var points=reverse?INTERFACE:INTERFACE.reversed();
        var surface=new SceneInputs.Surface(key,1,0,zero,zero,SceneInputs.Coverage.OPAQUE,0,true,0,0,0,MaterialInputs.Layers.plain(zero),SceneInputs.HostOcclusion.BLOCK);
        var corners=points.stream().map(p->new SceneInputs.Corner(new SceneInputs.Vec3((float)p.x(),(float)p.y(),(float)p.z()),0,0,
            new SceneInputs.Color(1,1,1,1),new SceneInputs.Vec3(1,0,0),new SceneInputs.Vec3(0,1,0))).toList();
        return new SceneCompiler().compile(new SceneInputs.Geometry(key,new SceneInputs.Revision(2,1,0,0,1,2,1),ORIGIN,SceneInputs.Transform.identity(),SceneInputs.Transform.identity(),false,
            SceneInputs.Motion.STATIC,new Participation(15,true),List.of(new SceneInputs.Primitive(key,0,surface,corners))));
    }
    @Test void capturedSecondaryFilterCannotInvalidateAnActualOpaqueCover() {
        var filter=capturedFilter();var solid=support(false);var request=new OpaqueContacts.Request(solid.input(),INTERFACE);
        var result=OpaqueContacts.match(List.of(request),List.of(filter,solid),100,new ConvexVolume.Budget(10000));
        assertEquals(1,result.get(request).size());assertSame(solid,result.get(request).getFirst().mesh());
        assertSame(solid.parts().getFirst(),result.get(request).getFirst().part());
    }
    @Test void nonSupportsCannotReplaceMissingCoverageAndOpaqueWindingRemainsStrict() {
        var filter=capturedFilter();var solid=support(false);var request=new OpaqueContacts.Request(solid.input(),INTERFACE);
        assertThrows(IllegalArgumentException.class,()->OpaqueContacts.match(List.of(request),List.of(filter),100,new ConvexVolume.Budget(10000)));
        assertThrows(IllegalArgumentException.class,()->OpaqueContacts.match(List.of(request),List.of(filter,support(true)),100,new ConvexVolume.Budget(10000)));

        assertThrows(IllegalStateException.class,()->OpaqueContacts.match(List.of(request),List.of(filter,solid),1,new ConvexVolume.Budget(10000)));
    }
    @Test void partialParticipationAndCutoutCandidatesDoNotContaminateAnOpaqueCover() {
        var solid=support(false);var g=support(true).input();var p=g.primitives().getFirst();var request=new OpaqueContacts.Request(solid.input(),INTERFACE);
        for(int mode=0;mode<2;mode++) {
            var s=p.surface();var surface=mode==0?new SceneInputs.Surface(s.key(),s.material(),s.properties(),s.colorResource(),s.emissionResource(),SceneInputs.Coverage.CUTOUT,.5f,s.doubleSided(),0,0,0,s.layers(),s.hostOcclusion()):s;
            var unrelated=new SceneCompiler().compile(new SceneInputs.Geometry(new SceneInputs.Key(2,50+mode),g.revision(),g.origin(),g.current(),g.previous(),false,g.motion(),
                mode==1?new Participation(14,false):g.participation(),List.of(new SceneInputs.Primitive(p.part(),p.ordinal(),surface,p.corners()))));
            var result=OpaqueContacts.match(List.of(request),List.of(unrelated,solid),100,new ConvexVolume.Budget(10000));
            assertEquals(1,result.get(request).size());assertSame(solid,result.get(request).getFirst().mesh());
            assertThrows(IllegalArgumentException.class,()->OpaqueContacts.match(List.of(request),List.of(unrelated),100,new ConvexVolume.Budget(10000)));
        }
    }
    @Test void completeSceneRetainsTheFilterAndInvalidatesOnOpaqueOwnerRemoval() {
        var source=OpaqueContactTest.source();var g=source.geometry().getFirst().input();var hidden=g.opticalModels().getFirst();
        var face=hidden.surfaces().stream().filter(p->hidden.occludedOrdinals().contains(p.ordinal())).findFirst().orElseThrow();
        var filter=filter(g,face);var meshes=new ArrayList<>(source.geometry());meshes.add(filter);var compiler=new ModelVolumeCompiler();
        var result=compiler.prepare(ModelVolumeCompilerTest.frame(1,JoinedModelVolumesTest.revision(meshes)));
        assertEquals(ModelVolumeCompiler.Status.READY,result.report().status(),result.report().toString());
        assertSame(filter,result.scene().revision().geometry().getLast());
        assertEquals(SceneInputs.Coverage.FILTER,filter.parts().getFirst().surface().coverage());
        meshes.remove(1);var incomplete=ModelVolumeCompilerTest.frame(2,JoinedModelVolumesTest.revision(meshes));var refused=compiler.prepare(incomplete);
        assertSame(incomplete,refused.scene());assertEquals(ModelVolumeCompiler.Status.HIDDEN_INTERFACE,refused.report().status());
    }
    @Test void identicalStaticAndRigidOpaqueFacesRemainAnOwnershipConflict() {
        var original=support(false);var g=original.input();var p=g.primitives().getFirst();var s=p.surface();
        var surface=new SceneInputs.Surface(s.key(),2,s.properties(),s.colorResource(),s.emissionResource(),s.coverage(),s.cutoff(),s.doubleSided(),0,0,0,s.layers(),s.hostOcclusion());
        var world=new SceneCompiler().compile(new SceneInputs.Geometry(new SceneInputs.Key(2,4398045462545L),g.revision(),g.origin(),g.current(),g.previous(),false,
            SceneInputs.Motion.STATIC,g.participation(),List.of(new SceneInputs.Primitive(new SceneInputs.Key(2,274877899027L),5,surface,p.corners()))));
        var local=p.corners().stream().map(c->new SceneInputs.Corner(new SceneInputs.Vec3(c.position().x(),c.position().y()-3,c.position().z()-14),c.u(),c.v(),c.tint(),c.normal(),c.tangent())).toList();
        var moving=new SceneCompiler().compile(new SceneInputs.Geometry(new SceneInputs.Key(-1,177),g.revision(),g.origin(),new SceneInputs.Transform(new float[]{1,0,0,0,0,1,0,3,0,0,1,14}),g.previous(),false,
            SceneInputs.Motion.RIGID,g.participation(),List.of(new SceneInputs.Primitive(new SceneInputs.Key(7997108434171719935L,7997108434171719935L),4,surface,local))));
        var request=new OpaqueContacts.Request(world.input(),INTERFACE);
        for(var one:List.of(world,moving))assertEquals(1,OpaqueContacts.match(List.of(request),List.of(one),100,new ConvexVolume.Budget(10000)).get(request).size());
        var failure=assertThrows(IllegalArgumentException.class,()->OpaqueContacts.match(List.of(request),List.of(world,moving),100,new ConvexVolume.Budget(10000)));
        assertTrue(failure.getMessage().contains("contributors=2"));assertTrue(failure.getMessage().contains("STATIC"));assertTrue(failure.getMessage().contains("RIGID"));
    }
    static SceneStore.Revision addFilter(SceneStore.Revision source) {
        var g=source.geometry().getFirst().input();var face=new FluidCompiler().interfaces(g.fluids().getFirst(),1<<5).getFirst();
        var geometry=new ArrayList<>(source.geometry());geometry.add(filter(g,face));return JoinedModelVolumesTest.revision(geometry);
    }
    private static SceneCompiler.Compiled filter(SceneInputs.Geometry g,SceneInputs.Primitive face) {
        var zero=new SceneInputs.Key(0,0);var key=new SceneInputs.Key(6100,1);
        var surface=new SceneInputs.Surface(key,43,SurfaceProperties.TRANSLUCENT,zero,zero,SceneInputs.Coverage.FILTER,0,false,0,0,0,MaterialInputs.Layers.plain(zero),SceneInputs.HostOcclusion.PASS);
        var corners=face.corners().stream().map(c->new SceneInputs.Corner(c.position(),c.u(),c.v(),new SceneInputs.Color(.3f,.6f,.9f,1),c.normal(),c.tangent())).toList();
        return new SceneCompiler().compile(new SceneInputs.Geometry(key,g.revision(),g.origin(),g.current(),g.previous(),false,SceneInputs.Motion.RIGID,
            new Participation(14,false),List.of(new SceneInputs.Primitive(key,4,surface,corners))));
    }
}
