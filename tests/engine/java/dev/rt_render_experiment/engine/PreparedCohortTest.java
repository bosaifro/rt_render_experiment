package dev.rt_render_experiment.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import dev.rt_render_experiment.contract.SceneInputs;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PreparedCohortTest {

    static final class Lane extends AbstractExecutorService {
        final List<Runnable> jobs=new ArrayList<>();boolean closed;
        int rejectAfter=Integer.MAX_VALUE;
        @Override public void execute(Runnable job) {
            if(closed || jobs.size()>=rejectAfter)throw new java.util.concurrent.RejectedExecutionException("Controlled launch failure");jobs.add(job);
        }
        void finish(int index) { jobs.remove(index).run(); }
        void finishAll() { while(!jobs.isEmpty())finish(0); }
        @Override public void shutdown() { closed=true; }
        @Override public List<Runnable> shutdownNow() { closed=true;var remaining=List.copyOf(jobs);jobs.clear();return remaining; }
        @Override public boolean isShutdown() { return closed; }
        @Override public boolean isTerminated() { return closed && jobs.isEmpty(); }
        @Override public boolean awaitTermination(long timeout,TimeUnit unit) { return isTerminated(); }
    }
    private static final SceneInputs.Key A=new SceneInputs.Key(1,1),B=new SceneInputs.Key(1,2),C=new SceneInputs.Key(1,3);
    private static SceneStore store() {
        var store=new SceneStore(new SceneCompiler(),new SceneStore.Budget(8,131072,4_000_000));store.world(1);
        store.publish(source(store,A,1));store.publish(source(store,B,1));return store;
    }
    private static SceneInputs.PreparedSource source(SceneStore store,SceneInputs.Key key,int revision) { return SceneBundlesTest.source(store,key,revision,SceneBundlesTest.texture(1,32)); }
    @Test void oneWorkerCannotPublishBeforeItsCompanionAndUnrelatedWorkCanPass() {
        var store=store();var before=store.snapshot();var lane=new Lane();
        try(var queue=new CompilationQueue(lane,new CompilationQueue.Budget(4,131072))) {
            var a=source(store,A,2);var b=source(store,B,2);long bytes=SceneStore.workBytes(a)+SceneStore.workBytes(b);
            assertTrue(queue.submitCohort(List.of(a,b)));lane.finish(1);
            assertTrue(queue.publishReady(store,1,1_000_000).isEmpty());assertSame(before,store.snapshot());
            assertEquals(2,queue.pendingJobs());assertEquals(bytes,queue.pendingBytes());
            var c=source(store,C,1);assertTrue(queue.submit(c));lane.finish(1);
            var independent=queue.publishReady(store,1,1_000_000);assertEquals(1,independent.size());assertTrue(independent.getFirst().published());
            assertTrue(store.snapshot().geometry().stream().filter(g->!g.input().key().equals(C)).allMatch(g->g.input().revision().topology()==1));
            long serial=store.snapshot().serial();lane.finish(0);
            var joint=queue.publishReady(store,1,1_000_000);assertEquals(2,joint.size());assertTrue(joint.stream().allMatch(CompilationQueue.Outcome::published));
            assertEquals(serial+1,store.snapshot().serial());assertEquals(0,queue.pendingJobs());assertEquals(0,queue.pendingBytes());
            assertTrue(store.snapshot().geometry().stream().filter(g->!g.input().key().equals(C)).allMatch(g->g.input().revision().topology()==2));
            assertEquals(2,queue.statistics().installedCohorts());assertEquals(2,queue.statistics().largestCohort());
        }
    }
    @Test void cancellingOneMemberRetainsTheWholeChargeUntilAllWorkersExit() {
        var store=store();var before=store.snapshot();var lane=new Lane();
        try(var queue=new CompilationQueue(lane,new CompilationQueue.Budget(4,131072))) {
            var a=source(store,A,2);var b=source(store,B,2);assertTrue(queue.submitCohort(List.of(a,b)));long bytes=queue.pendingBytes();
            lane.finish(0);queue.cancel(B);assertTrue(queue.publishReady(store,4,1_000_000).isEmpty());assertEquals(bytes,queue.pendingBytes());assertEquals(2,queue.pendingJobs());
            lane.finish(0);var failed=queue.publishReady(store,4,1_000_000);assertEquals(2,failed.size());
            assertTrue(failed.stream().allMatch(o->!o.published() && o.failure().equals("Cancelled")));assertSame(before,store.snapshot());assertEquals(0,store.pendingJobs());
            assertEquals(0,queue.pendingBytes());assertEquals(1,queue.statistics().rejectedCohorts());
        }
    }
    @Test void staleCompanionAndInvalidDataCannotFallBackToSingleSourcePublication() {
        for(boolean invalid:List.of(false,true)) {
            var store=store();var before=store.snapshot();var lane=new Lane();
            try(var queue=new CompilationQueue(lane,new CompilationQueue.Budget(4,131072))) {
                var a=source(store,A,2);var b=source(store,B,2);
                if(invalid)b=SceneInputs.PreparedSource.geometryOnly(b.content());
                assertTrue(queue.submitCohort(List.of(a,b)));lane.finishAll();
                SceneInputs.PreparedSource newer=invalid?null:source(store,B,3);
                var result=queue.publishReady(store,4,1_000_000);assertEquals(2,result.size());assertTrue(result.stream().noneMatch(CompilationQueue.Outcome::published));
                assertSame(before,store.snapshot());if(newer!=null)assertTrue(store.current(newer.content().request()));
            }
        }
    }
    @Test void admissionAndPartialExecutorFailureKeepExplicitPressureAndCleanup() {
        var store=store();var a=source(store,A,2);var b=source(store,B,2);var lane=new Lane();
        try(var queue=new CompilationQueue(lane,new CompilationQueue.Budget(1,131072))) {
            assertFalse(queue.submitCohort(List.of(a,b)));assertEquals(0,lane.jobs.size());assertEquals(0,queue.pendingBytes());
        }
        lane=new Lane();lane.rejectAfter=1;
        try(var queue=new CompilationQueue(lane,new CompilationQueue.Budget(4,131072))) {
            assertThrows(java.util.concurrent.RejectedExecutionException.class,()->queue.submitCohort(List.of(a,b)));
            assertEquals(1,queue.pendingJobs());assertEquals(SceneStore.workBytes(a)+SceneStore.workBytes(b),queue.pendingBytes());
            lane.finishAll();assertTrue(queue.publishReady(store,4,1_000_000).stream().noneMatch(CompilationQueue.Outcome::published));assertEquals(0,queue.pendingBytes());
        }
    }
    @Test void failedCohortDoesNotBlockAnotherValidCohortDuringStoreFallback() {
        var store=store();var lane=new Lane();
        try(var queue=new CompilationQueue(lane,new CompilationQueue.Budget(4,131072))) {
            var a=source(store,A,2);var b=SceneBundlesTest.source(store,B,2,SceneBundlesTest.texture(1,99));
            assertTrue(queue.submitCohort(List.of(a,b)));assertTrue(queue.submit(source(store,C,1)));lane.finishAll();
            var result=queue.publishReady(store,4,10_000_000);assertEquals(3,result.size());
            assertEquals(1,result.stream().filter(CompilationQueue.Outcome::published).count());
            assertTrue(store.snapshot().geometry().stream().filter(g->!g.input().key().equals(C)).allMatch(g->g.input().revision().topology()==1));
            assertEquals(3,store.snapshot().geometry().size());
        }
    }
    @Test void pipelinePressureRejectsEveryReservationAndDuplicateCaptureDoesNotCancelWork() throws Exception {
        var window=SourcePublicationTest.WINDOW;var demand=SourcePublicationTest.NEAR;
        try(var pipeline=new ScenePreparationPipeline(1,new SceneStore.Budget(4,262144,1_000_000),new CompilationQueue.Budget(1,262144))) {
            pipeline.world(1);pipeline.source(SourcePublicationTest.source(A,1,1,20));pipeline.source(SourcePublicationTest.source(B,1,1,20));
            var sources=pipeline.requests(demand,window).stream().map(r->SourcePublicationTest.packet(r,20,1)).toList();
            assertFalse(pipeline.acceptCohort(sources));assertEquals(0,pipeline.statistics().preparationRequests());assertEquals(0,pipeline.statistics().compilationJobs());
            assertFalse(pipeline.readiness(demand).renderable());assertEquals(2,pipeline.requests(demand,window).size());
        }
        try(var pipeline=SourcePublicationTest.pipeline()) {
            pipeline.source(SourcePublicationTest.source(A,1,1,20));pipeline.source(SourcePublicationTest.source(B,1,1,20));
            var sources=pipeline.requests(demand,window).stream().map(r->SourcePublicationTest.packet(r,20,1)).toList();
            assertTrue(pipeline.acceptCohort(sources));assertThrows(IllegalArgumentException.class,()->pipeline.acceptCohort(sources));
            SourcePublicationTest.drain(pipeline);assertTrue(pipeline.readiness(demand).complete());assertEquals(2,pipeline.snapshot().geometry().size());
            assertEquals(1,pipeline.cohortStatistics().installedCohorts());
        }
    }
    @Test void staleAdmissionAndReloadCancelTheCohortWithoutPoisoningSuccessorRequests() throws Exception {
        try(var pipeline=SourcePublicationTest.pipeline()) {
            pipeline.source(SourcePublicationTest.source(A,1,1,20));pipeline.source(SourcePublicationTest.source(B,1,1,20));
            var prepared=pipeline.requests(SourcePublicationTest.NEAR,SourcePublicationTest.WINDOW).stream().map(r->SourcePublicationTest.packet(r,20,1)).toList();
            pipeline.source(SourcePublicationTest.source(B,2,1,20));assertFalse(pipeline.acceptCohort(prepared));assertEquals(0,pipeline.statistics().preparationRequests());
            var current=pipeline.requests(SourcePublicationTest.NEAR,SourcePublicationTest.WINDOW).stream().map(r->SourcePublicationTest.packet(r,20,1)).toList();
            assertTrue(pipeline.acceptCohort(current));pipeline.source(SourcePublicationTest.source(B,3,2,20));SourcePublicationTest.drain(pipeline);
            assertTrue(pipeline.snapshot().geometry().isEmpty());assertEquals(0,pipeline.statistics().preparationRequests());
            var next=pipeline.requests(SourcePublicationTest.NEAR,SourcePublicationTest.WINDOW);assertEquals(2,next.size());
            assertEquals(2,next.stream().filter(r->r.source().equals(B)).findFirst().orElseThrow().expected().resources());
            pipeline.world(2);assertTrue(pipeline.snapshot().geometry().isEmpty());assertEquals(0,pipeline.statistics().compilationJobs());
        }
    }
}
