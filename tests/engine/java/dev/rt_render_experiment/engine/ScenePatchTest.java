package dev.rt_render_experiment.engine;

import java.util.List;
import java.util.Set;
import dev.rt_render_experiment.contract.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ScenePatchTest {
    static final SceneInputs.Key PATCH=new SceneInputs.Key(8100,1);
    static SceneInputs.PreparedSource prepared(SceneInputs.Geometry source) {
        long bytes=source.primitives().stream().mapToLong(p->SceneCompiler.primitiveBytes(p.corners().size())).sum()
            +source.opticalModels().stream().mapToLong(ModelBoundary::maximumBytes).sum();
        var request=new SceneInputs.PreparationRequest(1,source.key(),source.revision(),1<<20);
        return new SceneInputs.PreparedSource(new SceneInputs.PreparationResult(request,SceneInputs.PreparationStatus.READY,List.of(source),bytes,"Current source cell"),List.of(),List.of());
    }
    static SceneInputs.Geometry patchGeometry(SceneInputs.Geometry input) {
        return new SceneInputs.Geometry(PATCH,input.revision(),input.origin(),input.current(),input.previous(),false,SceneInputs.Motion.STATIC,input.participation(),input.primitives(),input.fluids(),input.opticalModels());
    }
    static SceneInputs.SourcePatch patch(long frame,SceneInputs.Geometry original,SceneInputs.PreparedSource replacement,Set<SceneInputs.Key> points) {
        return new SceneInputs.SourcePatch(original.revision().world(),frame,original.key(),
            original.primitives().stream().map(SceneInputs.Primitive::part).collect(java.util.stream.Collectors.toSet()),points,replacement);
    }
    @Test void aCurrentCellClosesTheGapBeforeItsWholeSourcePublicationArrives() {
        var original=DynamicOpticalModelTest.source(Set.of(),JoinedModelVolumesTest.ORIGIN,SceneInputs.Transform.identity());
        var empty=new SceneInputs.Geometry(original.key(),original.revision(),original.origin(),original.current(),original.previous(),false,original.motion(),original.participation(),List.of());
        var old=new SceneCompiler().compile(empty);var resident=new SceneStore.Revision(1,1,List.of(old),old.bytes());
        var replacement=prepared(patchGeometry(original));var compiler=new SceneFrameCompiler(1<<20);
        var scene=compiler.prepare(1,resident,null,List.of(),LightInputs.Publication.empty(),List.of(),List.of(patch(1,original,replacement,Set.of()))).withPreparedVolumes();
        var result=new ModelVolumeCompiler().prepare(scene);assertEquals(ModelVolumeCompiler.Status.READY,result.report().status());assertEquals(1,result.report().volumes());
        assertEquals(0,old.triangleCount());assertEquals(1,compiler.patchWork().compiled());
        var mesh=scene.revision().geometry().stream().filter(g->g.input().key().equals(PATCH)).findFirst().orElseThrow();
        var same=compiler.prepare(2,resident,null,List.of(),LightInputs.Publication.empty(),List.of(),List.of(patch(2,original,replacement,Set.of())));
        assertSame(mesh,same.revision().geometry().stream().filter(g->g.input().key().equals(PATCH)).findFirst().orElseThrow());assertEquals(1,compiler.patchWork().reused());
        var current=new SceneCompiler().compile(original);var published=new SceneStore.Revision(2,1,List.of(current),current.bytes());
        var finished=compiler.prepare(3,published,null,List.of(),LightInputs.Publication.empty(),List.of(),List.of());
        assertEquals(List.of(current),finished.revision().geometry());assertEquals(0,compiler.patchWork().sources());
    }
    @Test void oldGeometryAndEmissionAreReplacedTogetherWithoutTouchingUnrelatedParts() {
        var original=OpaqueContactTest.source().geometry().getLast().input();var p=original.primitives().getFirst();var other=new SceneInputs.Key(12,9);
        var unrelated=new SceneInputs.Primitive(other,0,p.surface(),p.corners());
        var source=new SceneInputs.Geometry(original.key(),original.revision(),original.origin(),original.current(),original.previous(),false,original.motion(),original.participation(),List.of(p,unrelated));
        var mesh=new SceneCompiler().compile(source);var emission=new LightInputs.Emission(0,1,new SceneInputs.Vec3(0,0,0));var pointKey=new SceneInputs.Key(9,1);
        var point=new LightInputs.Point(pointKey,1,emission,original.origin(),0,LightInputs.Stratum.WORLD,original.origin(),.5f);
        var area=new LightInputs.Area(new SceneInputs.Key(9,2),1,emission,original.origin(),0,LightInputs.Stratum.WORLD,source.key(),p.part(),p.ordinal());
        var resident=new SceneStore.Revision(1,1,List.of(mesh),mesh.bytes(),List.of(),List.of(point,area));
        var cell=patchGeometry(new SceneInputs.Geometry(source.key(),source.revision(),source.origin(),source.current(),source.previous(),false,source.motion(),source.participation(),List.of(p)));
        var base=prepared(cell);var newArea=new LightInputs.Area(area.key(),2,emission,area.samplingOrigin(),0,area.stratum(),PATCH,p.part(),p.ordinal());
        var replacement=new SceneInputs.PreparedSource(base.content(),List.of(),List.of(newArea));
        var claim=new SceneInputs.SourcePatch(1,1,source.key(),Set.of(p.part()),Set.of(pointKey),replacement);
        var compiler=new SceneFrameCompiler(1<<20);var frame=compiler.prepare(1,resident,null,List.of(),LightInputs.Publication.empty(),List.of(),List.of(claim));
        assertEquals(List.of(newArea),frame.revision().lights());
        assertEquals(List.of(unrelated),frame.revision().geometry().stream().filter(g->g.input().key().equals(source.key())).findFirst().orElseThrow().input().primitives());
        assertEquals(4,mesh.triangleCount());assertEquals(List.of(point,area),resident.lights());
        assertEquals(4,frame.revision().geometry().stream().filter(g->g.input().key().equals(PATCH)).findFirst().orElseThrow().primitiveIndexBytes());
        assertEquals(frame.revision().bytes(),frame.revision().geometry().stream().mapToLong(SceneCompiler.Compiled::bytes).sum());
    }
    @Test void worldFrameOwnershipAndPressureRejectBeforePublication() {
        var source=OpaqueContactTest.source();var original=source.geometry().getLast().input();var replacement=prepared(patchGeometry(original));var claim=patch(1,original,replacement,Set.of());
        var compiler=new SceneFrameCompiler(1<<20);
        assertThrows(IllegalArgumentException.class,()->compiler.prepare(2,source,null,List.of(),LightInputs.Publication.empty(),List.of(),List.of(claim)));
        assertThrows(IllegalArgumentException.class,()->compiler.prepare(1,source,null,List.of(),LightInputs.Publication.empty(),List.of(),List.of(claim,claim)));
        assertThrows(IllegalStateException.class,()->new SceneFrameCompiler(1).prepare(1,source,null,List.of(),LightInputs.Publication.empty(),List.of(),List.of(claim)));
        assertEquals(SceneFrameCompiler.PatchWork.NONE,compiler.patchWork());
        assertThrows(IllegalArgumentException.class,()->new SceneInputs.SourcePatch(1,1,original.key(),Set.of(new SceneInputs.Key(55,88)),Set.of(),replacement));
    }
    @Test void emptySourceReceiptsAreVisibleAndInvalidatedByRemovalAndWorldExit() {
        try(var pipeline=new ScenePreparationPipeline(1,new SceneStore.Budget(2,1<<20),new CompilationQueue.Budget(2,1<<20))) {
            pipeline.world(1);var source=OpaqueContactTest.source().geometry().getLast().input();var demand=new SourceResidency.Demand(source.origin(),20,40);
            var metadata=new SourceResidency.Source(source.key(),source.revision(),new SourceResidency.Bounds(source.origin(),source.origin()),4096);
            pipeline.source(metadata);assertNull(pipeline.publishedRevision(source.key()));assertTrue(pipeline.demanded(source.key(),demand));
            pipeline.emptySource(metadata);assertEquals(source.revision(),pipeline.publishedRevision(source.key()));
            assertTrue(pipeline.snapshot().geometry().isEmpty());pipeline.remove(source.key());assertNull(pipeline.publishedRevision(source.key()));
            pipeline.emptySource(metadata);pipeline.world(2);assertNull(pipeline.publishedRevision(source.key()));
        }
    }
}
