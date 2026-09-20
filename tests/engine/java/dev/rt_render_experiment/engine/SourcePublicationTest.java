package dev.rt_render_experiment.engine;

import java.util.List;
import dev.rt_render_experiment.contract.SceneInputs;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;


final class SourcePublicationTest {
    static final SourceResidency.Demand NEAR=new SourceResidency.Demand(new SceneInputs.Origin(0,0,0),5,30);
    static final ScenePreparationPipeline.Window WINDOW=new ScenePreparationPipeline.Window(8,524288,8,1_000_000);
    static final SceneInputs.Key A=new SceneInputs.Key(7,21),B=new SceneInputs.Key(7,22);
    static ScenePreparationPipeline pipeline() {
        var pipeline=new ScenePreparationPipeline(1,new SceneStore.Budget(8,524288,2_000_000),new CompilationQueue.Budget(8,524288));pipeline.world(1);return pipeline;
    }
    static SourceResidency.Source source(SceneInputs.Key key,long revision,long resources,double x) {
        return new SourceResidency.Source(key,new SceneInputs.Revision(1,revision,0,0,revision,resources,revision),
            new SourceResidency.Bounds(new SceneInputs.Origin(x,-1,-4),new SceneInputs.Origin(x+16,16,12)),65536,SourceResidency.BoundsKind.ESTIMATE);
    }
    static SceneInputs.PreparedSource packet(SceneInputs.PreparationRequest request,double x,long texture) {
        var before=ScenePreparationPipelineTest.texturedPacket(request,texture);var g=before.content().geometry().getFirst();
        var input=new SceneInputs.Geometry(g.key(),g.revision(),new SceneInputs.Origin(x,0,0),g.current(),g.previous(),g.previousValid(),g.motion(),g.participation(),g.primitives());
        return new SceneInputs.PreparedSource(new SceneInputs.PreparationResult(request,SceneInputs.PreparationStatus.READY,List.of(input),before.content().preparedBytes(),"Complete source extent"),before.textures(),before.lights());
    }
    static void drain(ScenePreparationPipeline pipeline) throws InterruptedException {
        long deadline=System.nanoTime()+2_000_000_000L;
        while(pipeline.statistics().compilationJobs()!=0 && System.nanoTime()<deadline) { pipeline.publishReady(WINDOW);Thread.sleep(1); }
        assertEquals(0,pipeline.statistics().compilationJobs());
    }
    private static void farPredecessor(ScenePreparationPipeline pipeline) throws Exception {
        pipeline.source(source(A,1,1,1000));assertFalse(pipeline.readiness(NEAR).renderable());
        assertTrue(pipeline.accept(packet(pipeline.requests(NEAR,WINDOW).getFirst(),1000,1)));drain(pipeline);
        assertTrue(pipeline.requests(NEAR,WINDOW).isEmpty());
        assertTrue(pipeline.snapshot().geometry().isEmpty());assertEquals(0,pipeline.statistics().residentBytes(),"An exclusion retained geometry or texture bytes");
        assertEquals(1,pipeline.statistics().retainedExtents());assertEquals(104,pipeline.statistics().extentFactBytes());
        pipeline.source(source(A,2,1,1000));
    }
    @Test void dirtyDistantSourceHasAnExcludedPredecessorButStillReceivesPreparation() throws Exception {
        try(var pipeline=pipeline()) {
            farPredecessor(pipeline);var selected=pipeline.publication(NEAR);
            assertEquals(1,selected.readiness().wanted());assertEquals(1,selected.readiness().excludedPredecessors());
            assertTrue(selected.readiness().renderable());assertFalse(selected.readiness().complete());
            assertTrue(selected.scene().geometry().isEmpty());
            var request=pipeline.requests(NEAR,WINDOW).getFirst();assertEquals(2,request.expected().topology());

            assertTrue(pipeline.accept(packet(request,20,1)));drain(pipeline);
            var moved=pipeline.publication(NEAR);assertTrue(moved.readiness().complete());assertEquals(0,moved.readiness().excludedPredecessors());
            assertEquals(20,moved.scene().geometry().getFirst().input().origin().x());assertTrue(selected.scene().geometry().isEmpty());
        }
    }
    @Test void newSourcesAndMovedDemandCannotBorrowAnExclusion() throws Exception {
        try(var pipeline=pipeline()) {
            farPredecessor(pipeline);pipeline.source(source(B,1,1,2000));
            var missing=pipeline.readiness(NEAR);assertEquals(2,missing.wanted());assertEquals(1,missing.available());assertFalse(missing.renderable());
            pipeline.remove(B);assertTrue(pipeline.readiness(NEAR).renderable());
            var moved=new SourceResidency.Demand(new SceneInputs.Origin(1000,0,0),5,30);
            assertFalse(pipeline.readiness(moved).renderable());assertEquals(0,pipeline.readiness(moved).excludedPredecessors());
            var larger=new SourceResidency.Demand(NEAR.camera(),5,2000);
            assertFalse(pipeline.readiness(larger).renderable(),"A secondary-range change reused the old exclusion");
        }
    }
    @Test void reloadUnloadAndWorldChangeRetireExclusions() throws Exception {
        try(var pipeline=pipeline()) {
            farPredecessor(pipeline);pipeline.source(source(A,3,2,1000));
            assertFalse(pipeline.readiness(NEAR).renderable());assertEquals(0,pipeline.readiness(NEAR).excludedPredecessors());
            pipeline.remove(A);pipeline.source(source(A,4,1,1000));
            assertEquals(0,pipeline.statistics().retainedExtents());assertEquals(0,pipeline.statistics().extentFactBytes());
            assertFalse(pipeline.readiness(NEAR).renderable(),"Reused source identity retained an unloaded predecessor");
            pipeline.world(2);var original=source(A,1,1,1000);
            pipeline.source(new SourceResidency.Source(A,new SceneInputs.Revision(2,1,0,0,1,1,1),original.bounds(),65536,SourceResidency.BoundsKind.ESTIMATE));
            assertFalse(pipeline.readiness(NEAR).renderable());
        }
    }
    @Test void newCandidateBoundsCannotEvictTheVisibleCoherentPredecessor() throws Exception {
        try(var pipeline=pipeline()) {
            pipeline.source(source(A,1,1,20));pipeline.source(source(B,1,1,20));
            for(var request:pipeline.requests(NEAR,WINDOW))assertTrue(pipeline.accept(packet(request,20,1)));drain(pipeline);
            var before=pipeline.publication(NEAR);pipeline.source(source(A,2,1,1000));
            assertTrue(pipeline.accept(packet(pipeline.requests(NEAR,WINDOW).getFirst(),1000,2)));drain(pipeline);
            assertTrue(pipeline.requests(NEAR,WINDOW).isEmpty(),"Completed candidate was recompiled while its resource cohort waited");
            var held=pipeline.publication(NEAR);assertTrue(held.candidateCoherenceBlocked());assertSame(before.scene(),held.scene());
            assertTrue(held.readiness().renderable());assertEquals(0,held.readiness().excludedPredecessors());
            assertEquals(2,held.readiness().available());assertEquals(1,held.readiness().current());
            pipeline.source(source(B,2,1,20));assertTrue(pipeline.accept(packet(pipeline.requests(NEAR,WINDOW).getFirst(),20,2)));drain(pipeline);
            assertTrue(pipeline.requests(NEAR,WINDOW).isEmpty());var updated=pipeline.publication(NEAR);
            assertFalse(updated.candidateCoherenceBlocked());assertTrue(updated.readiness().complete());assertEquals(1,updated.scene().geometry().size());
            assertEquals(B,updated.scene().geometry().getFirst().input().key());assertEquals(2,updated.scene().textures().getFirst().revision());
        }
    }
    @Test void stagedNewSourceIsMissingUntilItsCohortPublishes() throws Exception {
        try(var pipeline=pipeline()) {
            pipeline.source(source(A,1,1,20));assertTrue(pipeline.accept(packet(pipeline.requests(NEAR,WINDOW).getFirst(),20,1)));drain(pipeline);
            var before=pipeline.publication(NEAR);pipeline.source(source(B,1,1,20));
            assertTrue(pipeline.accept(packet(pipeline.requests(NEAR,WINDOW).getFirst(),20,2)));drain(pipeline);
            var blocked=pipeline.publication(NEAR);assertSame(before.scene(),blocked.scene());assertTrue(blocked.candidateCoherenceBlocked());
            assertEquals(2,blocked.readiness().wanted());assertEquals(1,blocked.readiness().available());assertFalse(blocked.readiness().renderable());
            assertTrue(pipeline.requests(NEAR,WINDOW).isEmpty(),"An accepted source was repeatedly compiled during resource reconciliation");
            pipeline.updateAppearance(List.of(new SceneStore.AppearanceUpdate(1,1,SceneBundlesTest.texture(2,192))));
            var current=pipeline.publication(NEAR);assertEquals(2,current.scene().geometry().size());assertTrue(current.readiness().complete());
            assertFalse(current.candidateCoherenceBlocked());assertEquals(1,blocked.scene().geometry().size());
            pipeline.remove(A);pipeline.remove(B);assertEquals(0,pipeline.statistics().residentBytes());
        }
    }
    @Test void lateCompleteExtentIsProjectedOutWithoutCancellingItsNewerPreparation() throws Exception {
        try(var pipeline=pipeline()) {
            pipeline.source(source(A,1,1,1000));var first=pipeline.requests(NEAR,WINDOW).getFirst();
            assertTrue(pipeline.accept(packet(first,1000,1)));pipeline.source(source(A,2,1,1000));drain(pipeline);
            assertEquals(1,pipeline.snapshot().geometry().size(),"The late complete source should remain available to the store");
            var outside=pipeline.publication(NEAR);assertTrue(outside.scene().geometry().isEmpty());assertTrue(outside.scene().textures().isEmpty());
            assertTrue(outside.readiness().renderable());assertEquals(1,outside.readiness().excludedPredecessors());
            assertSame(outside.scene(),pipeline.publication(new SourceResidency.Demand(new SceneInputs.Origin(1,0,0),5,30)).scene(),"Stable range membership was rebuilt");
            var nearby=new SourceResidency.Demand(new SceneInputs.Origin(1000,0,0),5,30);
            assertEquals(1,pipeline.publication(nearby).scene().geometry().size());assertEquals(0,pipeline.publication(nearby).readiness().excludedPredecessors());
            var next=pipeline.requests(NEAR,WINDOW).getFirst();assertEquals(2,next.expected().topology());
            assertTrue(pipeline.accept(packet(next,20,1)));drain(pipeline);
            var included=pipeline.publication(NEAR);assertTrue(included.readiness().complete());assertEquals(1,included.scene().geometry().size());
            assertNotEquals(outside.scene().serial(),included.scene().serial());assertTrue(outside.scene().geometry().isEmpty());
        }
    }
    @Test void projectedPredecessorAndStagedNearCandidateKeepDistinctMembership() throws Exception {
        try(var pipeline=pipeline()) {
            pipeline.source(source(A,1,1,1000));pipeline.source(source(B,1,1,20));
            for(var request:pipeline.requests(NEAR,WINDOW))assertTrue(pipeline.accept(packet(request,request.source().equals(A)?1000:20,1)));drain(pipeline);
            pipeline.source(source(A,2,1,1000));
            var before=pipeline.publication(NEAR);assertEquals(1,before.scene().geometry().size());
            assertTrue(pipeline.accept(packet(pipeline.requests(NEAR,WINDOW).getFirst(),20,2)));drain(pipeline);
            var pending=pipeline.publication(NEAR);assertTrue(pending.candidateCoherenceBlocked());assertSame(before.scene(),pending.scene());
            assertTrue(pending.readiness().renderable());assertEquals(1,pending.readiness().excludedPredecessors());
            pipeline.source(source(B,2,1,20));assertTrue(pipeline.accept(packet(pipeline.requests(NEAR,WINDOW).getFirst(),20,2)));drain(pipeline);
            var published=pipeline.publication(NEAR);assertEquals(2,published.scene().geometry().size());assertTrue(published.readiness().complete());
        }
    }
    @Test void selectionKeepsCanonicalSharedResourcesInsteadOfChoosingAnotherSourceCopy() throws Exception {
        try(var pipeline=pipeline()) {
            pipeline.source(source(A,1,1,1000));pipeline.source(source(B,1,1,20));
            for(var request:pipeline.requests(NEAR,WINDOW))assertTrue(pipeline.accept(packet(request,request.source().equals(A)?1000:20,1)));drain(pipeline);
            var canonical=pipeline.snapshot().textures().getFirst();var projected=pipeline.publication(NEAR);
            assertEquals(1,projected.scene().geometry().size());assertSame(canonical,projected.scene().textures().getFirst());
            assertSame(projected.scene(),pipeline.publication(NEAR).scene());
        }
    }
}
