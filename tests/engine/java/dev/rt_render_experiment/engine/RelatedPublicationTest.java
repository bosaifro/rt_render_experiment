package dev.rt_render_experiment.engine;

import java.util.List;
import dev.rt_render_experiment.contract.SceneInputs;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;


final class RelatedPublicationTest {
    static final SceneInputs.Key A=SourcePublicationTest.A,B=SourcePublicationTest.B,U=new SceneInputs.Key(7,23);
    private static SourceResidency.Source source(SceneInputs.Key key,long revision) { return SourcePublicationTest.source(key,revision,1,20); }
    private static void finish(ScenePreparationPipeline pipeline,SceneInputs.Key key) throws Exception {
        var requests=pipeline.requests(SourcePublicationTest.NEAR,SourcePublicationTest.WINDOW);
        var selected=requests.stream().filter(r->r.source().equals(key)).findFirst().orElseThrow();
        for(var request:requests)if(request!=selected)pipeline.reject(request);
        assertTrue(pipeline.accept(SourcePublicationTest.packet(selected,20,1)));SourcePublicationTest.drain(pipeline);
    }
    private static long version(SceneStore.Revision scene,SceneInputs.Key key) {
        return scene.geometry().stream().filter(g->g.input().key().equals(key)).findFirst().orElseThrow().input().revision().topology();
    }
    @Test void relatedEditCannotExposeOneNeighborFromAnEarlierWorkWindow() throws Exception {
        try(var pipeline=SourcePublicationTest.pipeline()) {
            for(var key:List.of(A,B,U))pipeline.source(source(key,1));
            for(var key:List.of(A,B,U))finish(pipeline,key);
            var before=pipeline.snapshot();
            pipeline.source(source(A,2));pipeline.source(source(B,2));
            pipeline.relatedSources(new SceneInputs.RelatedSources(java.util.Map.of(A,source(A,2).revision(),B,source(B,2).revision())));
            finish(pipeline,A);
            assertSame(before,pipeline.snapshot(),"One neighbor became visible before the companion source was ready");
            assertEquals(1,version(pipeline.snapshot(),A));assertEquals(1,version(pipeline.snapshot(),B));
            pipeline.source(source(U,2));finish(pipeline,U);
            assertEquals(2,version(pipeline.snapshot(),U));assertEquals(1,version(pipeline.snapshot(),A));
            finish(pipeline,B);
            assertEquals(2,version(pipeline.snapshot(),A));assertEquals(2,version(pipeline.snapshot(),B));
            assertTrue(pipeline.publication(SourcePublicationTest.NEAR).readiness().complete());
            assertEquals(0,pipeline.groupStatistics().pending());
        }
    }
    @Test void overlappingEditsSupersedeOnlyRequiredRevisionsAndRetainThePredecessor() throws Exception {
        try(var pipeline=SourcePublicationTest.pipeline()) {
            for(var key:List.of(A,B,U))pipeline.source(source(key,1));for(var key:List.of(A,B,U))finish(pipeline,key);
            var before=pipeline.snapshot();pipeline.source(source(A,2));pipeline.source(source(B,2));
            pipeline.relatedSources(new SceneInputs.RelatedSources(java.util.Map.of(A,source(A,2).revision(),B,source(B,2).revision())));
            finish(pipeline,A);pipeline.source(source(B,3));pipeline.source(source(U,2));
            pipeline.relatedSources(new SceneInputs.RelatedSources(java.util.Map.of(B,source(B,3).revision(),U,source(U,2).revision())));
            assertEquals(1,pipeline.groupStatistics().pending());assertEquals(3,pipeline.groupStatistics().members());
            finish(pipeline,U);assertSame(before,pipeline.snapshot());finish(pipeline,B);
            assertEquals(2,version(pipeline.snapshot(),A));assertEquals(3,version(pipeline.snapshot(),B));assertEquals(2,version(pipeline.snapshot(),U));
            assertEquals(0,pipeline.groupStatistics().pending());
        }
    }
    @Test void completeEmptyContributionWaitsForItsNeighborAndCancelsOldQueuedWork() throws Exception {
        try(var pipeline=SourcePublicationTest.pipeline()) {
            for(var key:List.of(A,B))pipeline.source(source(key,1));for(var key:List.of(A,B))finish(pipeline,key);
            var before=pipeline.snapshot();pipeline.source(source(B,2));
            var request=pipeline.requests(SourcePublicationTest.NEAR,SourcePublicationTest.WINDOW).getFirst();
            pipeline.accept(SourcePublicationTest.packet(request,20,1));
            pipeline.source(source(A,2));pipeline.source(source(B,3));
            pipeline.relatedSources(new SceneInputs.RelatedSources(java.util.Map.of(A,source(A,2).revision(),B,source(B,3).revision())));
            pipeline.emptySource(source(B,3));SourcePublicationTest.drain(pipeline);
            assertSame(before,pipeline.snapshot());assertEquals(2,pipeline.snapshot().geometry().size());
            finish(pipeline,A);assertEquals(1,pipeline.snapshot().geometry().size());assertEquals(2,version(pipeline.snapshot(),A));
            assertTrue(pipeline.requests(SourcePublicationTest.NEAR,SourcePublicationTest.WINDOW).isEmpty());
            assertEquals(0,pipeline.groupStatistics().pending());
        }
    }
    @Test void unloadingAnUnpreparedCompanionNeverResurrectsItAndWorldExitClearsRelations() throws Exception {
        try(var pipeline=SourcePublicationTest.pipeline()) {
            pipeline.source(source(A,1));finish(pipeline,A);pipeline.source(source(A,2));pipeline.source(source(B,1));
            pipeline.relatedSources(new SceneInputs.RelatedSources(java.util.Map.of(A,source(A,2).revision(),B,source(B,1).revision())));
            finish(pipeline,A);assertEquals(1,version(pipeline.snapshot(),A));pipeline.remove(B);
            assertEquals(2,version(pipeline.snapshot(),A));assertEquals(0,pipeline.groupStatistics().pending());
            pipeline.source(source(A,3));pipeline.source(source(B,2));
            pipeline.relatedSources(new SceneInputs.RelatedSources(java.util.Map.of(A,source(A,3).revision(),B,source(B,2).revision())));
            pipeline.world(2);assertEquals(0,pipeline.groupStatistics().members());assertTrue(pipeline.snapshot().geometry().isEmpty());
        }
    }
    @Test void appearanceUpdatesReachRetainedMembersWithoutRecompilation() throws Exception {
        try(var pipeline=SourcePublicationTest.pipeline()) {
            for(var key:List.of(A,B,U))pipeline.source(source(key,1));for(var key:List.of(A,B,U))finish(pipeline,key);
            var original=pipeline.snapshot().geometry();pipeline.source(source(A,2));pipeline.source(source(B,2));
            pipeline.relatedSources(new SceneInputs.RelatedSources(java.util.Map.of(A,source(A,2).revision(),B,source(B,2).revision())));
            finish(pipeline,A);
            pipeline.updateAppearance(List.of(new SceneStore.AppearanceUpdate(1,1,SceneBundlesTest.texture(2,192))));
            assertEquals(original,pipeline.snapshot().geometry());assertEquals(2,pipeline.snapshot().textures().getFirst().revision());
            assertEquals(1,pipeline.groupStatistics().pending());finish(pipeline,B);
            assertEquals(2,version(pipeline.snapshot(),A));assertEquals(2,pipeline.snapshot().textures().getFirst().revision());
        }
    }
    @Test void declarationRequiresActualRegisteredRevisionsAndAnOutOfRangeCompanionStillCompletes() throws Exception {
        try(var pipeline=SourcePublicationTest.pipeline()) {
            pipeline.source(source(A,1));finish(pipeline,A);
            assertThrows(IllegalArgumentException.class,()->pipeline.relatedSources(new SceneInputs.RelatedSources(java.util.Map.of(B,source(B,1).revision()))));
            pipeline.source(source(A,2));var far=SourcePublicationTest.source(B,1,1,1000);
            pipeline.source(new SourceResidency.Source(B,far.revision(),far.bounds(),far.maximumBytes(),SourceResidency.BoundsKind.COMPLETE));
            pipeline.relatedSources(new SceneInputs.RelatedSources(java.util.Map.of(A,source(A,2).revision(),B,far.revision())));
            finish(pipeline,A);var requests=pipeline.requests(SourcePublicationTest.NEAR,SourcePublicationTest.WINDOW);
            assertEquals(1,requests.size());assertEquals(B,requests.getFirst().source());
            pipeline.accept(SourcePublicationTest.packet(requests.getFirst(),1000,1));SourcePublicationTest.drain(pipeline);
            var selected=pipeline.publication(SourcePublicationTest.NEAR);
            assertEquals(1,selected.scene().geometry().size());assertEquals(2,version(selected.scene(),A));assertTrue(selected.readiness().complete());
        }
    }
    @Test void failedCompanionDoesNotPublishOrRecompileItsSuccessfulPeer() throws Exception {
        try(var pipeline=SourcePublicationTest.pipeline()) {
            for(var key:List.of(A,B))pipeline.source(source(key,1));for(var key:List.of(A,B))finish(pipeline,key);
            var before=pipeline.snapshot();pipeline.source(source(A,2));pipeline.source(source(B,2));
            pipeline.relatedSources(new SceneInputs.RelatedSources(java.util.Map.of(A,source(A,2).revision(),B,source(B,2).revision())));
            finish(pipeline,A);var requests=pipeline.requests(SourcePublicationTest.NEAR,SourcePublicationTest.WINDOW);
            assertEquals(1,requests.size());assertEquals(B,requests.getFirst().source());
            var complete=SourcePublicationTest.packet(requests.getFirst(),20,1);
            assertTrue(pipeline.accept(new SceneInputs.PreparedSource(complete.content(),List.of(),complete.lights())));
            SourcePublicationTest.drain(pipeline);assertSame(before,pipeline.snapshot());assertEquals(1,pipeline.groupStatistics().pending());
            assertEquals(1,pipeline.cohortStatistics().rejectedCohorts());finish(pipeline,B);
            assertEquals(2,version(pipeline.snapshot(),A));assertEquals(2,version(pipeline.snapshot(),B));
        }
    }
    @Test void residentPressurePreservesOldBytesAndUnloadCanReleaseTheBlockedUpdate() throws Exception {
        long budget;
        try(var reference=SourcePublicationTest.pipeline()) {
            for(var key:List.of(A,B))reference.source(source(key,1));for(var key:List.of(A,B))finish(reference,key);
            budget=reference.statistics().residentBytes();
        }
        try(var pipeline=new ScenePreparationPipeline(1,new SceneStore.Budget(8,524288,budget),new CompilationQueue.Budget(8,524288))) {
            pipeline.world(1);for(var key:List.of(A,B))pipeline.source(source(key,1));for(var key:List.of(A,B))finish(pipeline,key);
            var before=pipeline.snapshot();pipeline.source(source(A,2));pipeline.source(source(B,2));
            pipeline.relatedSources(new SceneInputs.RelatedSources(java.util.Map.of(A,source(A,2).revision(),B,source(B,2).revision())));
            finish(pipeline,A);assertSame(before,pipeline.snapshot());assertEquals(budget,pipeline.statistics().residentBytes());
            assertEquals(1,pipeline.groupStatistics().pending());assertEquals(1,pipeline.cohortStatistics().rejectedCohorts());
            pipeline.remove(B);finish(pipeline,A);assertEquals(2,version(pipeline.snapshot(),A));assertEquals(0,pipeline.groupStatistics().pending());
            assertTrue(pipeline.statistics().residentBytes()<=budget);
        }
    }
}
