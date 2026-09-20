package dev.rt_render_experiment.engine;

import java.util.Map;
import java.util.List;
import dev.rt_render_experiment.contract.SceneInputs;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;


final class PreparedReuseTest {
    private static final SourceResidency.Demand DEMAND=new SourceResidency.Demand(new SceneInputs.Origin(0,64,0),32,32);
    private static SourceResidency.Source source(int side,int version) {
        return new SourceResidency.Source(new SceneInputs.Key(2111,side+1),new SceneInputs.Revision(1,version,0,0,1,1,1),
            new SourceResidency.Bounds(new SceneInputs.Origin(-1,63,-3),new SceneInputs.Origin(1,65,-3)),4096);
    }
    private static void changed(ScenePreparationPipeline pipeline,int version) {
        var a=source(0,version);var b=source(1,version);pipeline.source(a);pipeline.source(b);
        pipeline.relatedSources(new SceneInputs.RelatedSources(Map.of(a.key(),a.revision(),b.key(),b.revision())));
    }
    private static void complete(ScenePreparationPipeline pipeline,int side) throws Exception {
        var requests=pipeline.requests(DEMAND,SourcePublicationTest.WINDOW);var key=source(side,1).key();
        var selected=requests.stream().filter(r->r.source().equals(key)).findFirst().orElseThrow();
        for(var request:requests)if(request!=selected)pipeline.reject(request);
        pipeline.accept(PreparedCohortFixtures.packet(selected,side,0,false));SourcePublicationTest.drain(pipeline);
    }
    @Test void identicalPreparedGeometryDoesNotDuplicateResidentStorageWhileItsGroupWaits() throws Exception {
        try(var pipeline=SourcePublicationTest.pipeline()) {
            changed(pipeline,1);complete(pipeline,0);complete(pipeline,1);
            var before=pipeline.snapshot();long bytes=pipeline.statistics().residentBytes();
            changed(pipeline,2);complete(pipeline,0);
            assertSame(before,pipeline.snapshot());
            assertEquals(bytes,pipeline.statistics().residentBytes(),"Identical prepared source content was compiled into another resident allocation");
            complete(pipeline,1);
            assertTrue(pipeline.snapshot().geometry().stream().allMatch(g->g.input().revision().topology()==2));
            for(int i=0;i<2;i++)assertTrue(pipeline.snapshot().geometry().get(i).sharesStorage(before.geometry().get(i)));
            assertEquals(2,pipeline.cohortStatistics().reusedGeometryPublications());assertEquals(bytes,pipeline.cohortStatistics().reusedGeometryBytes());
        }
    }
    private static SceneInputs.Geometry input(SceneInputs.Geometry from,SceneInputs.Revision revision,List<SceneInputs.Primitive> primitives,
                                               List<dev.rt_render_experiment.contract.FluidInputs.Cell> fluids,List<SceneInputs.OpticalModel> models) {
        return new SceneInputs.Geometry(from.key(),revision,from.origin(),from.current(),from.previous(),from.previousValid(),from.motion(),from.participation(),primitives,fluids,models);
    }
    private static SceneInputs.PreparedSource packet(SceneInputs.Geometry geometry) {
        long bytes=0;
        for(var primitive:geometry.primitives())bytes+=SceneCompiler.primitiveBytes(primitive.corners().size());
        for(var cell:geometry.fluids())bytes+=6*SceneCompiler.primitiveBytes(4)+cell.occlusionBytes();
        for(var model:geometry.opticalModels())bytes+=ModelBoundary.maximumBytes(model);
        var request=new SceneInputs.PreparationRequest(55,geometry.key(),geometry.revision(),Math.max(1,bytes));
        return SceneInputs.PreparedSource.geometryOnly(new SceneInputs.PreparationResult(request,SceneInputs.PreparationStatus.READY,List.of(geometry),bytes,"Complete reuse qualification"));
    }
    @Test void exactPreparedFactsReuseWhileSameCountCornerIdentityAndSurfaceChangesRecompile() {
        var request=new SceneInputs.PreparationRequest(1,source(0,1).key(),source(0,1).revision(),4096);
        var original=PreparedCohortFixtures.packet(request,0,0,false).content().geometry().getFirst();
        var compiled=SceneStore.compile(packet(original)).geometry();
        var next=input(original,source(0,2).revision(),original.primitives(),original.fluids(),original.opticalModels());
        var reused=SceneStore.compile(packet(next),compiled);
        assertTrue(reused.geometry().sharesStorage(compiled));assertSame(next,reused.geometry().input());assertEquals(compiled.bytes(),reused.reusedGeometryBytes());
        var plain=SceneStore.compile(packet(next)).geometry();
        assertEquals(-1,plain.positions().mismatch(reused.geometry().positions()));assertEquals(-1,plain.corners().mismatch(reused.geometry().corners()));assertEquals(-1,plain.indices().mismatch(reused.geometry().indices()));
        var primitive=next.primitives().getFirst();var first=primitive.corners().getFirst();
        var variants=List.of(
            new SceneInputs.Corner(new SceneInputs.Vec3(Math.nextUp(first.position().x()),first.position().y(),first.position().z()),first.u(),first.v(),first.tint(),first.normal(),first.tangent(),first.emissionUv(),first.emissionTint(),first.overlayTexel()),
            new SceneInputs.Corner(first.position(),.25f,first.v(),first.tint(),first.normal(),first.tangent(),first.emissionUv(),first.emissionTint(),first.overlayTexel()),
            new SceneInputs.Corner(first.position(),first.u(),first.v(),new SceneInputs.Color(.5f,1,1,1),first.normal(),first.tangent(),first.emissionUv(),first.emissionTint(),first.overlayTexel()),
            new SceneInputs.Corner(first.position(),first.u(),first.v(),first.tint(),new SceneInputs.Vec3(0,1,0),first.tangent(),first.emissionUv(),first.emissionTint(),first.overlayTexel()),
            new SceneInputs.Corner(first.position(),first.u(),first.v(),first.tint(),first.normal(),new SceneInputs.Vec3(0,1,0),first.emissionUv(),first.emissionTint(),first.overlayTexel()),
            new SceneInputs.Corner(first.position(),first.u(),first.v(),first.tint(),first.normal(),first.tangent(),new SceneInputs.Vec2(.5f,.5f),first.emissionTint(),first.overlayTexel()));
        for(var corner:variants) {
            var corners=new java.util.ArrayList<>(primitive.corners());corners.set(0,corner);
            var changed=input(next,next.revision(),List.of(new SceneInputs.Primitive(primitive.part(),primitive.ordinal(),primitive.surface(),corners)),List.of(),List.of());
            var fresh=SceneStore.compile(packet(changed),compiled);assertEquals(0,fresh.reusedGeometryBytes());assertFalse(fresh.geometry().sharesStorage(compiled));
        }
        var surface=primitive.surface();var changedSurface=new SceneInputs.Surface(surface.key(),surface.material(),surface.properties(),surface.colorResource(),surface.emissionResource(),
            surface.coverage(),.25f,surface.doubleSided(),surface.medium(),surface.layer(),surface.layerSeparation(),surface.layers(),surface.hostOcclusion(),surface.boundary());
        for(var changed:List.of(new SceneInputs.Primitive(primitive.part(),primitive.ordinal()+1,primitive.surface(),primitive.corners()),
            new SceneInputs.Primitive(primitive.part(),primitive.ordinal(),changedSurface,primitive.corners()),
            new SceneInputs.Primitive(primitive.part(),primitive.ordinal(),primitive.surface(),primitive.corners().reversed())))
            assertEquals(0,SceneStore.compile(packet(input(next,next.revision(),List.of(changed),List.of(),List.of())),compiled).reusedGeometryBytes());
    }
    @Test void opticalBoundariesAndFluidInputsArePartOfTheExactReuseProof() {
        var shell=ModelBoundaryTest.cube();var key=shell.getFirst().part();
        var optical=new SceneInputs.OpticalModel(key,shell,java.util.Set.of(5L));
        var g=new SceneInputs.Geometry(source(0,1).key(),source(0,1).revision(),new SceneInputs.Origin(0,64,0),SceneInputs.Transform.identity(),SceneInputs.Transform.identity(),false,
            SceneInputs.Motion.STATIC,new dev.rt_render_experiment.contract.Participation(15,true),shell.subList(0,5),List.of(),List.of(optical));
        var before=SceneStore.compile(packet(g)).geometry();var next=input(g,source(0,2).revision(),g.primitives(),g.fluids(),g.opticalModels());
        var reuse=SceneStore.compile(packet(next),before);assertTrue(reuse.geometry().sharesStorage(before));assertSame(before.modelBoundaries().getFirst(),reuse.geometry().modelBoundaries().getFirst());
        var hidden=new SceneInputs.OpticalModel(key,shell,java.util.Set.of(0L,1L,2L,3L,4L,5L));
        assertEquals(0,SceneStore.compile(packet(input(g,next.revision(),List.of(),List.of(),List.of(hidden))),before).reusedGeometryBytes());
        assertEquals(0,SceneStore.compile(packet(input(g,next.revision(),g.primitives(),List.of(),List.of())),before).reusedGeometryBytes());
        var derived=new SceneCompiler().withVolumes(before,Map.of(before.parts().getFirst(),92L));
        assertEquals(0,SceneStore.compile(packet(next),derived).reusedGeometryBytes(),"Renderer optical metadata was mistaken for original source compilation");
        var s=shell.getFirst().surface();var samples=java.util.Collections.nCopies(9,new dev.rt_render_experiment.contract.FluidInputs.Sample(.5f,true,false,false));
        var cell=new dev.rt_render_experiment.contract.FluidInputs.Cell(key,0,new SceneInputs.Vec3(0,0,0),samples,63,0,0,0,new SceneInputs.Color(1,1,1,1),s,s,s);
        var fluid=input(g,g.revision(),List.of(),List.of(cell),List.of());var compiled=SceneStore.compile(packet(fluid)).geometry();
        var repeated=input(fluid,next.revision(),List.of(),List.of(cell),List.of());assertTrue(SceneStore.compile(packet(repeated),compiled).reusedGeometryBytes()>0);
        var heights=java.util.Collections.nCopies(9,new dev.rt_render_experiment.contract.FluidInputs.Sample(.75f,true,false,false));
        var changed=new dev.rt_render_experiment.contract.FluidInputs.Cell(key,0,cell.position(),heights,cell.candidateFaces(),cell.overlayFaces(),cell.flowX(),cell.flowZ(),cell.tint(),s,s,s);
        assertEquals(0,SceneStore.compile(packet(input(fluid,next.revision(),List.of(),List.of(changed),List.of())),compiled).reusedGeometryBytes());
    }
    @Test void worldResourcesPlacementMotionAndParticipationRemainExplicitInputs() {
        var request=new SceneInputs.PreparationRequest(1,source(0,1).key(),source(0,1).revision(),4096);
        var original=PreparedCohortFixtures.packet(request,0,0,false).content().geometry().getFirst();var before=SceneStore.compile(packet(original)).geometry();
        var r=original.revision();
        for(var revision:List.of(new SceneInputs.Revision(2,2,0,0,1,1,1),new SceneInputs.Revision(1,2,0,0,1,2,1)))
            assertEquals(0,SceneStore.compile(packet(input(original,revision,original.primitives(),List.of(),List.of())),before).reusedGeometryBytes());
        var transform=new SceneInputs.Transform(new float[]{1,0,0,.1f,0,1,0,0,0,0,1,0});
        var variants=List.of(
            new SceneInputs.Geometry(original.key(),r,new SceneInputs.Origin(.1,64,0),original.current(),original.previous(),false,original.motion(),original.participation(),original.primitives()),
            new SceneInputs.Geometry(original.key(),r,original.origin(),transform,original.previous(),false,original.motion(),original.participation(),original.primitives()),
            new SceneInputs.Geometry(original.key(),r,original.origin(),original.current(),transform,false,original.motion(),original.participation(),original.primitives()),
            new SceneInputs.Geometry(original.key(),r,original.origin(),original.current(),original.previous(),true,original.motion(),original.participation(),original.primitives()),
            new SceneInputs.Geometry(original.key(),r,original.origin(),original.current(),original.previous(),false,SceneInputs.Motion.DEFORMING,original.participation(),original.primitives()),
            new SceneInputs.Geometry(original.key(),r,original.origin(),original.current(),original.previous(),false,original.motion(),new dev.rt_render_experiment.contract.Participation(1,true),original.primitives()));
        for(var variant:variants)assertEquals(0,SceneStore.compile(packet(variant),before).reusedGeometryBytes());
    }
    @Test void optionalPredecessorPressureFallsBackToFreshCompilationAndCancellationRetainsItsCharge() throws Exception {
        var src=source(0,1);var packet=PreparedCohortFixtures.packet(new SceneInputs.PreparationRequest(1,src.key(),src.revision(),4096),0,0,false);
        long weight=SceneStore.workBytes(packet);
        try(var pipeline=new ScenePreparationPipeline(1,new SceneStore.Budget(2,8192),new CompilationQueue.Budget(2,weight))) {
            pipeline.world(1);pipeline.source(src);complete(pipeline,0);pipeline.source(source(0,2));complete(pipeline,0);
            assertEquals(0,pipeline.cohortStatistics().reusedGeometryPublications());assertEquals(2,pipeline.snapshot().geometry().getFirst().input().revision().topology());
        }
        var lane=new PreparedCohortTest.Lane();
        try(var pipeline=new ScenePreparationPipeline(lane,new SceneStore.Budget(2,8192),new CompilationQueue.Budget(2,weight*2))) {
            pipeline.world(1);pipeline.source(src);
            var a=pipeline.requests(DEMAND,SourcePublicationTest.WINDOW).getFirst();pipeline.accept(PreparedCohortFixtures.packet(a,0,0,false));lane.finishAll();pipeline.publishReady(SourcePublicationTest.WINDOW);
            pipeline.source(source(0,2));var b=pipeline.requests(DEMAND,SourcePublicationTest.WINDOW).getFirst();pipeline.accept(PreparedCohortFixtures.packet(b,0,0,false));
            assertEquals(weight*2,pipeline.statistics().compilationBytes());pipeline.remove(src.key());
            assertEquals(weight*2,pipeline.statistics().compilationBytes());lane.finishAll();pipeline.publishReady(SourcePublicationTest.WINDOW);
            assertEquals(0,pipeline.statistics().compilationBytes());assertTrue(pipeline.snapshot().geometry().isEmpty());
        }
    }
}
