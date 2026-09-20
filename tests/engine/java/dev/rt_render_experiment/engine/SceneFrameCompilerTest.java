package dev.rt_render_experiment.engine;

import java.util.List;
import java.util.Set;
import dev.rt_render_experiment.contract.DynamicInputs;
import dev.rt_render_experiment.contract.LightInputs;
import dev.rt_render_experiment.contract.SceneInputs;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class SceneFrameCompilerTest {
    @Test void movingGlassMustWithdrawItsVacatedSourceCellAsWellAsItsDestination() {
        var input=DynamicOpticalModelTest.source(Set.of(),JoinedModelVolumesTest.ORIGIN,SceneInputs.Transform.identity());
        var stored=new SceneCompiler().compile(input);var resident=new SceneStore.Revision(1,1,List.of(stored),stored.bytes());
        var moving=new DynamicInputs.PreparedObject(new SceneInputs.Key(-10,8),input.origin(),new SceneInputs.Transform(new float[]{1,0,0,.25f,0,1,0,0,0,0,1,0}),
            SceneInputs.Motion.RIGID,input.participation(),1,1,input.primitives(),input.opticalModels());
        var dynamic=new DynamicSceneCompiler().prepare(1,1,List.of(moving),1<<20);var compiler=new SceneFrameCompiler(1<<20);
        var destination=new SceneInputs.SourceAbsence(1,1,new SceneInputs.Key(999,2),Set.of(new SceneInputs.Key(999,3)),Set.of());
        var duplicated=compiler.prepare(1,resident,dynamic,List.of(),LightInputs.Publication.empty(),List.of(destination)).withPreparedVolumes();
        assertEquals(ModelVolumeCompiler.Status.GEOMETRY_UNSUPPORTED,new ModelVolumeCompiler().prepare(duplicated).report().status());
        var departed=new SceneInputs.SourceAbsence(1,1,input.key(),Set.of(input.primitives().getFirst().part()),Set.of());
        var current=compiler.prepare(1,resident,dynamic,List.of(),LightInputs.Publication.empty(),List.of(destination,departed)).withPreparedVolumes();
        var qualified=new ModelVolumeCompiler().prepare(current);assertEquals(ModelVolumeCompiler.Status.READY,qualified.report().status());assertEquals(1,qualified.report().volumes());
        assertEquals(12,stored.triangleCount());assertEquals(1,stored.modelBoundaries().size());
    }
    private static DynamicSceneCompiler.Prepared moving(SceneCompiler.Compiled source) {
        var g=source.input();
        return new DynamicSceneCompiler().prepare(g.revision().world(),g.revision().resources(),List.of(new DynamicInputs.PreparedObject(new SceneInputs.Key(-10,7),g.origin(),g.current(),
            SceneInputs.Motion.RIGID,g.participation(),1,1,g.primitives())),1<<20);
    }
    private static SceneInputs.SourceAbsence absent(long frame,SceneCompiler.Compiled source,Set<SceneInputs.Key> points) {
        return new SceneInputs.SourceAbsence(source.input().revision().world(),frame,source.input().key(),
            source.input().primitives().stream().map(SceneInputs.Primitive::part).collect(java.util.stream.Collectors.toSet()),points);
    }
    @Test void producerAbsenceRemovesTheOldPhysicalOwnerBeforeVolumeAndGpuPreparation() {
        var source=OpaqueContactTest.source();var solid=source.geometry().getLast();var dynamic=moving(solid);
        var original=new FrameScene(1,source,dynamic,List.of(),LightInputs.Publication.empty()).withPreparedVolumes();
        assertEquals(ModelVolumeCompiler.Status.HIDDEN_INTERFACE,new ModelVolumeCompiler().prepare(original).report().status());
        var compiler=new SceneFrameCompiler(1<<20);var frame=compiler.prepare(2,source,dynamic,List.of(),LightInputs.Publication.empty(),List.of(absent(2,solid,Set.of())));
        var old=frame.revision().geometry().stream().filter(g->g.input().key().equals(solid.input().key())).findFirst().orElseThrow();
        assertEquals(0,old.triangleCount());assertTrue(old.input().primitives().isEmpty());assertNotSame(solid.input(),old.input());
        assertEquals(12,solid.triangleCount());assertFalse(solid.input().primitives().isEmpty());
        assertSame(dynamic.geometry().getFirst(),frame.revision().geometry().getLast());
        assertEquals(ModelVolumeCompiler.Status.READY,new ModelVolumeCompiler().prepare(frame.withPreparedVolumes()).report().status());
        assertSame(source.geometry().getFirst(),frame.revision().geometry().getFirst());
        dynamic.submitted();
    }
    @Test void frameWorldAndByteGrantsAreEnforcedWithoutChangingStoredContent() {
        var source=OpaqueContactTest.source();var solid=source.geometry().getLast();var compiler=new SceneFrameCompiler(1<<20);var dynamic=moving(solid);
        var fact=absent(3,solid,Set.of());
        assertThrows(IllegalArgumentException.class,()->compiler.prepare(4,source,dynamic,List.of(),LightInputs.Publication.empty(),List.of(fact)));
        var foreign=new SceneInputs.SourceAbsence(2,3,fact.source(),fact.parts(),Set.of());
        assertThrows(IllegalArgumentException.class,()->compiler.prepare(3,source,dynamic,List.of(),LightInputs.Publication.empty(),List.of(foreign)));
        assertThrows(IllegalStateException.class,()->new SceneFrameCompiler(1).prepare(3,source,dynamic,List.of(),LightInputs.Publication.empty(),List.of(fact)));
        assertEquals(12,solid.triangleCount());assertEquals(SceneFrameCompiler.Work.NONE,compiler.work());
    }
    @Test void unchangedDerivativesAreCachedButNewSourcesAndEndedAbsenceAreNotHidden() {
        var source=OpaqueContactTest.source();var solid=source.geometry().getLast();var compiler=new SceneFrameCompiler(1<<20);
        var a=compiler.prepare(1,source,null,List.of(),LightInputs.Publication.empty(),List.of(absent(1,solid,Set.of())));
        assertEquals(1,compiler.work().compiledSources());
        var b=compiler.prepare(2,source,null,List.of(),LightInputs.Publication.empty(),List.of(absent(2,solid,Set.of())));
        assertSame(a.revision().geometry().getLast(),b.revision().geometry().getLast());assertEquals(1,compiler.work().reusedSources());
        var c=compiler.prepare(3,source,null,List.of(),LightInputs.Publication.empty(),List.of());assertSame(solid,c.revision().geometry().getLast());
        var renewed=OpaqueContactTest.source();compiler.prepare(4,renewed,null,List.of(),LightInputs.Publication.empty(),List.of(absent(4,renewed.geometry().getLast(),Set.of())));
        assertEquals(1,compiler.work().compiledSources());assertEquals(0,compiler.work().reusedSources());
        compiler.clear();compiler.prepare(5,renewed,null,List.of(),LightInputs.Publication.empty(),List.of(absent(5,renewed.geometry().getLast(),Set.of())));
        assertEquals(1,compiler.work().compiledSources());
    }
    @Test void hiddenModelsFluidsAndEmissionFollowTheirDeclaredSourceParts() {
        var source=OpaqueContactTest.source();var solid=source.geometry().getLast();var part=solid.parts().getFirst();var origin=solid.input().origin();
        var point=new LightInputs.Point(new SceneInputs.Key(8,1),1,new LightInputs.Emission(0,1,new SceneInputs.Vec3(0,0,0)),origin,0,LightInputs.Stratum.WORLD,origin,.5f);
        var unrelated=new LightInputs.Point(new SceneInputs.Key(8,2),1,point.emission(),origin,0,LightInputs.Stratum.WORLD,origin,.5f);
        var area=new LightInputs.Area(new SceneInputs.Key(8,3),1,point.emission(),origin,0,LightInputs.Stratum.WORLD,solid.input().key(),part.key(),part.ordinal());
        var resident=new SceneStore.Revision(1,1,source.geometry(),source.bytes(),List.of(),List.of(point,unrelated,area));
        var compiler=new SceneFrameCompiler(1<<20);var result=compiler.prepare(1,resident,null,List.of(),LightInputs.Publication.empty(),List.of(absent(1,solid,Set.of(point.key()))));
        assertEquals(List.of(unrelated),result.revision().lights());assertEquals(2,compiler.work().removedLights());assertEquals(3,resident.lights().size());

        var replacement=new LightInputs.Point(point.key(),2,point.emission(),origin,0,LightInputs.Stratum.OBJECT,origin,.25f);
        var next=compiler.prepare(2,resident,null,List.of(),new LightInputs.Publication(1,List.of(replacement)),List.of(absent(2,solid,Set.of(point.key()))));
        assertTrue(next.revision().lights().contains(replacement));
        var glass=source.geometry().getFirst();var absentGlass=new SceneInputs.SourceAbsence(1,3,glass.input().key(),Set.of(glass.modelBoundaries().getFirst().source().part()),Set.of());
        assertTrue(compiler.prepare(3,source,null,List.of(),LightInputs.Publication.empty(),List.of(absentGlass)).revision().geometry().getFirst().modelBoundaries().isEmpty());
        var fluid=FluidContactFixtures.scene(false,true,false,new SceneInputs.Origin(0,64,0));var water=fluid.geometry().getFirst();
        var absentWater=new SceneInputs.SourceAbsence(1,4,water.input().key(),Set.of(water.input().fluids().getFirst().part()),Set.of());
        var dry=compiler.prepare(4,fluid,null,List.of(),LightInputs.Publication.empty(),List.of(absentWater));
        assertTrue(dry.revision().geometry().getFirst().input().fluids().isEmpty());assertEquals(0,dry.revision().geometry().getFirst().triangleCount());
    }
    @Test void aRetainedPartKeepsItsAttributesAssociationAndAccountedIndex() {
        var base=OpaqueContactTest.source().geometry().getLast().input();var p=base.primitives().getFirst();var other=new SceneInputs.Key(55,9);
        var second=new SceneInputs.Primitive(other,7,p.surface(),p.corners());
        var input=new SceneInputs.Geometry(base.key(),base.revision(),base.origin(),base.current(),base.previous(),false,base.motion(),base.participation(),List.of(p,second));
        var mesh=new SceneCompiler().compile(input);var emission=new LightInputs.Emission(0,1,new SceneInputs.Vec3(0,0,0));
        var light=new LightInputs.Area(new SceneInputs.Key(8,3),1,emission,base.origin(),0,LightInputs.Stratum.WORLD,base.key(),other,7);
        var source=new SceneStore.Revision(1,1,List.of(mesh),mesh.bytes(),List.of(),List.of(light));
        var compiler=new SceneFrameCompiler(1<<20);var absence=new SceneInputs.SourceAbsence(1,1,base.key(),Set.of(p.part()),Set.of());
        var frame=compiler.prepare(1,source,null,List.of(),LightInputs.Publication.empty(),List.of(absence));var retained=frame.revision().geometry().getFirst();
        assertEquals(List.of(second),retained.input().primitives());assertEquals(List.of(light),frame.revision().lights());
        assertEquals(other,retained.findPart(other,7).orElseThrow().key());assertEquals(4,retained.primitiveIndexBytes());
        assertEquals(retained.bytes(),compiler.work().derivativeBytes());assertEquals(retained.bytes(),frame.revision().bytes());
        assertFalse(new SceneCompiler().canReuseGeometry(retained,retained.input()),"A frame derivative masqueraded as source compilation");
    }
}
