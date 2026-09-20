package dev.rt_render_experiment.engine;

import java.util.List;
import dev.rt_render_experiment.contract.SceneInputs;
import static org.junit.jupiter.api.Assertions.*;

final class ScenePreparationPipelineTest {
    private static final ScenePreparationPipeline.Window WINDOW=new ScenePreparationPipeline.Window(4,16384,4,1_000_000);
    private static final SourceResidency.Demand NEAR=new SourceResidency.Demand(new SceneInputs.Origin(0,0,0),5,30);
    @org.junit.jupiter.api.Test void demandStreamingAndSupersededPreparation() throws Exception {
        try(var pipeline=new ScenePreparationPipeline(1,new SceneStore.Budget(4,16384,1_000_000),new CompilationQueue.Budget(2,8192))) {
            pipeline.world(1);var key=new SceneInputs.Key(7,1);pipeline.source(source(key,1));
            assertFalse(pipeline.readiness(NEAR).complete());
            assertEquals(1,pipeline.readiness(NEAR).wanted());
            var request=pipeline.requests(NEAR,WINDOW).getFirst();pipeline.source(source(key,2));
            assertFalse(pipeline.accept(packet(request)));
            var current=pipeline.requests(NEAR,WINDOW).getFirst();assertEquals(2,current.expected().topology());assertTrue(pipeline.accept(packet(current)));
            assertFalse(pipeline.readiness(NEAR).complete(),"Enqueue is not a ready scene");
            drain(pipeline);assertEquals(1,pipeline.snapshot().geometry().size());assertTrue(pipeline.requests(NEAR,WINDOW).isEmpty());
            assertTrue(pipeline.readiness(NEAR).complete());
            assertTrue(pipeline.requests(new SourceResidency.Demand(new SceneInputs.Origin(1000,0,0),5,30),WINDOW).isEmpty());
            assertTrue(pipeline.snapshot().geometry().isEmpty());assertEquals(1,pipeline.statistics().sources());
            assertFalse(pipeline.readiness(NEAR).complete(),"Evicted source was still admitted");
            var returnRequest=pipeline.requests(NEAR,WINDOW).getFirst();assertTrue(pipeline.accept(packet(returnRequest)));
            pipeline.world(2);drain(pipeline);assertTrue(pipeline.snapshot().geometry().isEmpty());assertEquals(0,pipeline.statistics().preparationRequests());
        }
    }
    @org.junit.jupiter.api.Test void oversizedFirstSourceDoesNotStarveOtherPreparedWork() {
        try(var pipeline=new ScenePreparationPipeline(1,new SceneStore.Budget(4,16384),new CompilationQueue.Budget(2,8192))) {
            pipeline.world(1);var a=source(new SceneInputs.Key(1,1),1);var b=source(new SceneInputs.Key(1,2),1);
            pipeline.source(new SourceResidency.Source(a.key(),a.revision(),a.bounds(),8192));pipeline.source(b);
            var requests=pipeline.requests(NEAR,new ScenePreparationPipeline.Window(2,4096,2,1_000_000));
            assertEquals(1,requests.size());assertEquals(b.key(),requests.getFirst().source());
            pipeline.reject(requests.getFirst());assertEquals(0,pipeline.statistics().preparationRequests());
        }
    }
    @org.junit.jupiter.api.Test void retainedCoherentSceneRemainsQualifiedWhileNewResourceCohortWaits() throws Exception {
        try(var pipeline=new ScenePreparationPipeline(1,new SceneStore.Budget(4,16384,1_000_000),new CompilationQueue.Budget(4,16384))) {
            pipeline.world(1);var a=new SceneInputs.Key(7,11);var b=new SceneInputs.Key(7,12);
            pipeline.source(source(a,1));pipeline.source(source(b,1));
            for(var request:pipeline.requests(NEAR,WINDOW))assertTrue(pipeline.accept(texturedPacket(request,1)));
            drain(pipeline);var coherent=pipeline.snapshot();assertTrue(pipeline.readiness(NEAR).complete());
            pipeline.source(source(a,2));assertTrue(pipeline.accept(texturedPacket(pipeline.requests(NEAR,WINDOW).getFirst(),2)));drain(pipeline);
            assertTrue(pipeline.statistics().coherenceBlocked());assertSame(coherent,pipeline.snapshot());
            assertTrue(pipeline.readiness(NEAR).renderable(),"A waiting candidate stripped qualification from the intact predecessor");
            assertFalse(pipeline.readiness(NEAR).complete(),"Staged source output became published scene content");
        }
    }
    @org.junit.jupiter.api.Test void capturedJobsAdvanceUnderContinuousEditsWhileSuccessorsCoalesce() throws Exception {
        try(var pipeline=new ScenePreparationPipeline(1,new SceneStore.Budget(4,16384,1_000_000),new CompilationQueue.Budget(2,8192))) {
            pipeline.world(1);var key=new SceneInputs.Key(7,4);pipeline.source(source(key,1));
            var first=pipeline.requests(NEAR,WINDOW).getFirst();assertTrue(pipeline.accept(packet(first)));
            for(int revision=2;revision<=100;revision++)pipeline.source(source(key,revision));
            assertTrue(pipeline.requests(NEAR,WINDOW).isEmpty(),"A second job was issued over an admitted immutable input");
            drain(pipeline);
            assertEquals(1,pipeline.snapshot().geometry().size(),"Continuous dirtiness cancelled every prepared predecessor");
            assertEquals(1,pipeline.snapshot().geometry().getFirst().input().revision().topology());
            assertTrue(pipeline.readiness(NEAR).renderable());assertFalse(pipeline.readiness(NEAR).complete());
            var next=pipeline.requests(NEAR,WINDOW).getFirst();assertEquals(100,next.expected().topology());
            assertTrue(pipeline.accept(packet(next)));drain(pipeline);assertTrue(pipeline.readiness(NEAR).complete());
            assertFalse(pipeline.accept(packet(first)),"An obsolete completion overwrote the newer published request");
            pipeline.source(source(key,101));var oldResources=pipeline.requests(NEAR,WINDOW).getFirst();assertTrue(pipeline.accept(packet(oldResources)));
            var original=source(key,102);pipeline.source(new SourceResidency.Source(key,new SceneInputs.Revision(1,102,0,0,1,2,1),original.bounds(),4096));
            drain(pipeline);assertEquals(100,pipeline.snapshot().geometry().getFirst().input().revision().topology(),"Reload-invalid job was published");
            assertFalse(pipeline.readiness(NEAR).renderable());
            var reload=pipeline.requests(NEAR,WINDOW).getFirst();assertTrue(pipeline.accept(packet(reload)));drain(pipeline);
            assertEquals(2,pipeline.snapshot().geometry().getFirst().input().revision().resources());
        }
    }
    @org.junit.jupiter.api.Test void estimatedProducerLocationCannotCullItsUnknownSurfaceExtent() throws Exception {
        try(var pipeline=new ScenePreparationPipeline(1,new SceneStore.Budget(4,16384,1_000_000),new CompilationQueue.Budget(2,8192))) {
            pipeline.world(1);var base=source(new SceneInputs.Key(7,9),1);
            var estimate=new SourceResidency.Bounds(new SceneInputs.Origin(1000,0,0),new SceneInputs.Origin(1016,16,16));
            pipeline.source(new SourceResidency.Source(base.key(),base.revision(),estimate,4096,SourceResidency.BoundsKind.ESTIMATE));
            var request=pipeline.requests(NEAR,WINDOW).getFirst();assertTrue(pipeline.accept(packet(request)));drain(pipeline);

            assertTrue(pipeline.requests(NEAR,WINDOW).isEmpty());assertEquals(1,pipeline.snapshot().geometry().size());
            pipeline.requests(new SourceResidency.Demand(new SceneInputs.Origin(1000,0,0),5,30),WINDOW);
            assertTrue(pipeline.snapshot().geometry().isEmpty());
            var changed=source(base.key(),2);
            pipeline.source(new SourceResidency.Source(changed.key(),changed.revision(),estimate,4096,SourceResidency.BoundsKind.ESTIMATE));
            assertEquals(1,pipeline.requests(NEAR,WINDOW).size(),"Dirty input must invalidate its old measured extent");
        }
    }
    private static SourceResidency.Source source(SceneInputs.Key key,long revision) {
        return new SourceResidency.Source(key,new SceneInputs.Revision(1,revision,0,0,1,1,1),
            new SourceResidency.Bounds(new SceneInputs.Origin(19,-1,0),new SceneInputs.Origin(21,1,0)),4096);
    }
    private static SceneInputs.PreparedSource packet(SceneInputs.PreparationRequest request) {
        var source=SceneContractsTest.fixture(request.expected().topology());
        var geometry=new SceneInputs.Geometry(request.source(),request.expected(),new SceneInputs.Origin(20,0,0),source.current(),source.previous(),source.previousValid(),source.motion(),source.participation(),source.primitives());
        return SceneInputs.PreparedSource.geometryOnly(new SceneInputs.PreparationResult(request,SceneInputs.PreparationStatus.READY,List.of(geometry),SceneCompiler.primitiveBytes(4),"Complete renderer-neutral source"));
    }
    static SceneInputs.PreparedSource texturedPacket(SceneInputs.PreparationRequest request,long textureRevision) {
        var packet=packet(request);var geometry=packet.content().geometry().getFirst();var primitive=geometry.primitives().getFirst();var old=primitive.surface();
        var texture=SceneBundlesTest.texture(textureRevision,textureRevision==1?32:192);
        var surface=new SceneInputs.Surface(old.key(),old.material(),old.properties(),texture.key(),old.emissionResource(),old.coverage(),old.cutoff(),old.doubleSided(),
            old.medium(),old.layer(),old.layerSeparation(),dev.rt_render_experiment.contract.MaterialInputs.Layers.plain(texture.key()),old.hostOcclusion());
        var prepared=new SceneInputs.Geometry(geometry.key(),geometry.revision(),geometry.origin(),geometry.current(),geometry.previous(),geometry.previousValid(),geometry.motion(),geometry.participation(),
            List.of(new SceneInputs.Primitive(primitive.part(),primitive.ordinal(),surface,primitive.corners())));
        return new SceneInputs.PreparedSource(new SceneInputs.PreparationResult(request,SceneInputs.PreparationStatus.READY,List.of(prepared),SceneCompiler.primitiveBytes(4),"Complete shared-resource source"),List.of(texture),List.of());
    }
    private static void drain(ScenePreparationPipeline pipeline) throws InterruptedException {
        long deadline=System.nanoTime()+2_000_000_000L;
        while(pipeline.statistics().compilationJobs()!=0 && System.nanoTime()<deadline) { pipeline.publishReady(WINDOW);Thread.sleep(1); }
        assertEquals(0,pipeline.statistics().compilationJobs());
    }
}
