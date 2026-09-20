package dev.rt_render_experiment.engine;

import java.util.List;
import java.util.Map;
import dev.rt_render_experiment.contract.SceneInputs;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;


final class SealedPublicationTest {
    static final SceneInputs.Key A=SourcePublicationTest.A,B=SourcePublicationTest.B;
    static SourceResidency.Source source(SceneInputs.Key key,long revision) { return SourcePublicationTest.source(key,revision,1,20); }
    static void changed(ScenePreparationPipeline pipeline,long revision) {
        pipeline.source(source(A,revision));pipeline.source(source(B,revision));
        pipeline.relatedSources(new SceneInputs.RelatedSources(Map.of(A,source(A,revision).revision(),B,source(B,revision).revision())));
    }
    static void capture(ScenePreparationPipeline pipeline) {
        var requests=pipeline.requests(SourcePublicationTest.NEAR,SourcePublicationTest.WINDOW);
        assertEquals(2,requests.size());
        assertTrue(pipeline.acceptCohort(requests.stream().map(r->SourcePublicationTest.packet(r,20,1)).toList()));
    }
    static long version(ScenePreparationPipeline pipeline,SceneInputs.Key key) {
        return pipeline.snapshot().geometry().stream().filter(g->g.input().key().equals(key)).findFirst().orElseThrow().input().revision().topology();
    }
    private static ScenePreparationPipeline controlled(PreparedCohortTest.Lane lane) {
        var pipeline=new ScenePreparationPipeline(lane,new SceneStore.Budget(8,524288,2_000_000),new CompilationQueue.Budget(8,524288));
        pipeline.world(1);changed(pipeline,1);capture(pipeline);lane.finishAll();pipeline.publishReady(SourcePublicationTest.WINDOW);return pipeline;
    }
    @Test void completeCapturedGroupsAdvanceUnderAnEditBeforeEveryCompilerPublication() throws Exception {
        try(var pipeline=SourcePublicationTest.pipeline()) {
            changed(pipeline,1);capture(pipeline);SourcePublicationTest.drain(pipeline);
            changed(pipeline,2);
            for(int revision=2;revision<=12;revision++) {
                capture(pipeline);
                assertEquals(1,pipeline.groupStatistics().sealed());
                changed(pipeline,revision+1);SourcePublicationTest.drain(pipeline);
                for(var key:List.of(A,B))assertEquals(revision,version(pipeline,key),"A complete captured group never reached the scene");
                var readiness=pipeline.publication(SourcePublicationTest.NEAR).readiness();
                assertTrue(readiness.renderable());assertFalse(readiness.complete());
            }
            capture(pipeline);SourcePublicationTest.drain(pipeline);
            assertTrue(pipeline.publication(SourcePublicationTest.NEAR).readiness().complete());
            assertEquals(13,pipeline.groupStatistics().sealedPublications());assertEquals(0,pipeline.groupStatistics().sealed());
        }
    }
    @Test void aCompletedMemberStaysPinnedWhileUnrelatedWorkCanPublishAndSuccessorsMerge() {
        var lane=new PreparedCohortTest.Lane();var u=new SceneInputs.Key(7,23);
        try(var pipeline=controlled(lane)) {
            changed(pipeline,2);var requests=pipeline.requests(SourcePublicationTest.NEAR,SourcePublicationTest.WINDOW);
            for(var request:requests)pipeline.accept(SourcePublicationTest.packet(request,20,1));
            lane.finish(0);pipeline.publishReady(SourcePublicationTest.WINDOW);changed(pipeline,3);
            assertTrue(pipeline.requests(SourcePublicationTest.NEAR,SourcePublicationTest.WINDOW).isEmpty(),"A successor overwrote the completed member of a sealed group");
            pipeline.source(source(u,1));var independent=pipeline.requests(SourcePublicationTest.NEAR,SourcePublicationTest.WINDOW).getFirst();
            pipeline.accept(SourcePublicationTest.packet(independent,20,1));lane.finish(1);pipeline.publishReady(SourcePublicationTest.WINDOW);
            assertEquals(1,version(pipeline,u));assertEquals(1,version(pipeline,A));assertEquals(1,version(pipeline,B));
            pipeline.relatedSources(new SceneInputs.RelatedSources(Map.of(B,source(B,3).revision(),u,source(u,1).revision())));
            lane.finish(0);pipeline.publishReady(SourcePublicationTest.WINDOW);
            assertEquals(2,version(pipeline,A));assertEquals(2,version(pipeline,B));assertEquals(1,version(pipeline,u));
            assertEquals(1,pipeline.groupStatistics().pending());assertEquals(3,pipeline.groupStatistics().members());assertEquals(0,pipeline.groupStatistics().sealed());
            capture(pipeline);lane.finishAll();pipeline.publishReady(SourcePublicationTest.WINDOW);
            assertEquals(3,version(pipeline,A));assertEquals(3,version(pipeline,B));assertEquals(0,pipeline.groupStatistics().pending());
        }
    }
    @Test void failedCompiledMemberRevokesTheSealAndDoesNotPoisonItsSuccessor() {
        var lane=new PreparedCohortTest.Lane();
        try(var pipeline=controlled(lane)) {
            changed(pipeline,2);var packets=pipeline.requests(SourcePublicationTest.NEAR,SourcePublicationTest.WINDOW).stream().map(r->SourcePublicationTest.packet(r,20,1)).toList();
            var invalid=new SceneInputs.PreparedSource(packets.get(1).content(),List.of(),List.of());
            assertTrue(pipeline.acceptCohort(List.of(packets.getFirst(),invalid)));assertEquals(1,pipeline.groupStatistics().sealed());
            changed(pipeline,3);lane.finishAll();pipeline.publishReady(SourcePublicationTest.WINDOW);
            assertEquals(1,version(pipeline,A));assertEquals(1,version(pipeline,B));assertEquals(0,pipeline.groupStatistics().sealed());
            assertEquals(1,pipeline.groupStatistics().revokedSeals());capture(pipeline);lane.finishAll();pipeline.publishReady(SourcePublicationTest.WINDOW);
            assertEquals(3,version(pipeline,A));assertEquals(3,version(pipeline,B));
        }
    }
    @Test void reloadEmptyUnloadAndWorldRevocationCannotPublishTheSealedOldInputs() {
        for(String reason:List.of("reload","empty","unload","world")) {
            var lane=new PreparedCohortTest.Lane();
            try(var pipeline=controlled(lane)) {
                changed(pipeline,2);capture(pipeline);changed(pipeline,3);
                switch(reason) {
                    case "reload" -> {
                        var a=SourcePublicationTest.source(A,4,2,20);var b=SourcePublicationTest.source(B,4,2,20);
                        pipeline.source(a);pipeline.source(b);pipeline.relatedSources(new SceneInputs.RelatedSources(Map.of(A,a.revision(),B,b.revision())));
                    }
                    case "empty" -> pipeline.emptySource(source(B,3));
                    case "unload" -> pipeline.remove(B);
                    case "world" -> pipeline.world(2);
                    default -> throw new AssertionError(reason);
                }
                lane.finishAll();pipeline.publishReady(SourcePublicationTest.WINDOW);
                assertEquals(0,pipeline.groupStatistics().sealed(),reason);
                assertTrue(pipeline.snapshot().geometry().stream().noneMatch(g->g.input().revision().topology()==2),reason);
                if(reason.equals("unload"))assertTrue(pipeline.snapshot().geometry().stream().noneMatch(g->g.input().key().equals(B)));
                if(reason.equals("world"))assertTrue(pipeline.snapshot().geometry().isEmpty());
            }
        }
    }
    @Test void oldReservationCancellationDoesNotRevokeANewerSeal() {
        var lane=new PreparedCohortTest.Lane();
        try(var pipeline=controlled(lane)) {
            changed(pipeline,2);var old=pipeline.requests(SourcePublicationTest.NEAR,SourcePublicationTest.WINDOW);
            pipeline.acceptCohort(old.stream().map(r->SourcePublicationTest.packet(r,20,1)).toList());
            lane.finishAll();pipeline.publishReady(SourcePublicationTest.WINDOW);changed(pipeline,3);capture(pipeline);
            for(var request:old)pipeline.reject(request);
            assertEquals(1,pipeline.groupStatistics().sealed());lane.finishAll();pipeline.publishReady(SourcePublicationTest.WINDOW);
            assertEquals(3,version(pipeline,A));assertEquals(3,version(pipeline,B));assertEquals(0,pipeline.groupStatistics().revokedSeals());
        }
    }
    @Test void residentPressureRevokesTheSealWithoutPublishingPartialBytes() {
        long budget;
        try(var reference=controlled(new PreparedCohortTest.Lane())) { budget=reference.statistics().residentBytes(); }
        var lane=new PreparedCohortTest.Lane();
        try(var pipeline=new ScenePreparationPipeline(lane,new SceneStore.Budget(8,524288,budget),new CompilationQueue.Budget(8,524288))) {
            pipeline.world(1);changed(pipeline,1);capture(pipeline);lane.finishAll();pipeline.publishReady(SourcePublicationTest.WINDOW);
            changed(pipeline,2);capture(pipeline);changed(pipeline,3);lane.finishAll();pipeline.publishReady(SourcePublicationTest.WINDOW);
            assertEquals(1,version(pipeline,A));assertEquals(1,version(pipeline,B));assertEquals(budget,pipeline.statistics().residentBytes());
            assertEquals(0,pipeline.groupStatistics().sealed());assertEquals(1,pipeline.groupStatistics().revokedSeals());assertEquals(0,pipeline.statistics().compilationJobs());
            pipeline.remove(B);var remaining=pipeline.requests(SourcePublicationTest.NEAR,SourcePublicationTest.WINDOW);
            assertEquals(1,remaining.size());pipeline.accept(SourcePublicationTest.packet(remaining.getFirst(),20,1));
            lane.finishAll();pipeline.publishReady(SourcePublicationTest.WINDOW);assertEquals(3,version(pipeline,A));assertEquals(0,pipeline.groupStatistics().sealed());
        }
    }
}
