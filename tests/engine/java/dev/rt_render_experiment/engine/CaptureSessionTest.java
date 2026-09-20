package dev.rt_render_experiment.engine;

import java.util.List;
import dev.rt_render_experiment.contract.SceneInputs;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class CaptureSessionTest {
    private static ScenePreparationPipeline pipeline() {
        var p=SourcePublicationTest.pipeline();p.source(SourcePublicationTest.source(SourcePublicationTest.A,1,1,20));p.source(SourcePublicationTest.source(SourcePublicationTest.B,1,1,20));return p;
    }
    @Test void neutralFactsSurvivePhasesAndTransferTheirChargeOnlyWhenComplete() throws Exception {
        try(var pipeline=pipeline()) {
            var capture=pipeline.beginCapture(SourcePublicationTest.NEAR,SourcePublicationTest.WINDOW);assertNotNull(capture);
            var first=SourcePublicationTest.packet(capture.remaining().getFirst(),20,1);assertTrue(capture.add(first));
            assertEquals(2,pipeline.captureStatistics().sources());assertEquals(1,pipeline.captureStatistics().completedSources());
            assertEquals(SceneStore.workBytes(first),pipeline.captureStatistics().bytes());assertEquals(0,pipeline.statistics().compilationJobs());
            assertTrue(pipeline.resourceKeys().contains(first.textures().getFirst().key()));assertTrue(pipeline.snapshot().geometry().isEmpty());
            assertFalse(capture.submit());assertSame(capture,pipeline.beginCapture(SourcePublicationTest.NEAR,SourcePublicationTest.WINDOW));
            assertThrows(IllegalStateException.class,()->pipeline.requests(SourcePublicationTest.NEAR,SourcePublicationTest.WINDOW));
            assertTrue(capture.add(SourcePublicationTest.packet(capture.remaining().getFirst(),20,1)));assertTrue(capture.complete());assertTrue(capture.submit());
            assertEquals(ScenePreparationPipeline.CaptureState.QUEUED,capture.state());assertEquals(0,pipeline.captureStatistics().bytes());assertEquals(2,pipeline.statistics().compilationJobs());
            SourcePublicationTest.drain(pipeline);assertEquals(2,pipeline.snapshot().geometry().size());assertEquals(1,pipeline.cohortStatistics().installedCohorts());
            capture.close();assertEquals(0,pipeline.captureStatistics().discarded());
        }
    }
    @Test void changedMemberDropsAllStoredFactsAndOnlyItsOldReservations() {
        try(var pipeline=pipeline()) {
            var capture=pipeline.beginCapture(SourcePublicationTest.NEAR,SourcePublicationTest.WINDOW);var before=capture.requests();
            capture.add(SourcePublicationTest.packet(before.getFirst(),20,1));
            pipeline.source(SourcePublicationTest.source(SourcePublicationTest.B,2,1,20));
            assertEquals(ScenePreparationPipeline.CaptureState.DISCARDED,capture.state());assertEquals(0,pipeline.captureStatistics().bytes());assertEquals(0,pipeline.statistics().preparationRequests());
            var next=pipeline.beginCapture(SourcePublicationTest.NEAR,SourcePublicationTest.WINDOW);assertNotNull(next);
            capture.close();assertTrue(next.current());assertEquals(2,pipeline.statistics().preparationRequests());
            assertEquals(2,next.requests().stream().filter(r->r.source().equals(SourcePublicationTest.B)).findFirst().orElseThrow().expected().topology());
        }
    }
    @Test void duplicateForeignPressureAndWorldExitHaveNoPartialPublication() {
        try(var pipeline=pipeline()) {
            var capture=pipeline.beginCapture(SourcePublicationTest.NEAR,SourcePublicationTest.WINDOW);var first=SourcePublicationTest.packet(capture.remaining().getFirst(),20,1);
            capture.add(first);assertThrows(IllegalArgumentException.class,()->capture.add(first));
            assertThrows(IllegalStateException.class,()->pipeline.acceptCohort(List.of(first)));
            pipeline.remove(SourcePublicationTest.A);assertFalse(capture.current());assertEquals(0,pipeline.captureStatistics().bytes());
            var next=pipeline.beginCapture(SourcePublicationTest.NEAR,SourcePublicationTest.WINDOW);assertNotNull(next);
            pipeline.world(2);assertEquals(ScenePreparationPipeline.CaptureState.DISCARDED,next.state());assertEquals(0,pipeline.statistics().preparationRequests());assertTrue(pipeline.resourceKeys().isEmpty());
        }

        try(var pipeline=new ScenePreparationPipeline(1,new SceneStore.Budget(2,16384),new CompilationQueue.Budget(2,4096))) {
            pipeline.world(1);var source=SourcePublicationTest.source(SourcePublicationTest.A,1,1,20);
            pipeline.source(new SourceResidency.Source(source.key(),source.revision(),source.bounds(),4096));
            var capture=pipeline.beginCapture(SourcePublicationTest.NEAR,SourcePublicationTest.WINDOW);var packet=SourcePublicationTest.packet(capture.requests().getFirst(),20,1);
            var large=new dev.rt_render_experiment.contract.TextureInputs.Texture(new SceneInputs.Key(88,1),1,dev.rt_render_experiment.contract.TextureInputs.Encoding.LINEAR,dev.rt_render_experiment.contract.TextureInputs.Address.CLAMP,
                List.of(new dev.rt_render_experiment.contract.TextureInputs.Level(64,64,java.nio.ByteBuffer.allocate(64*64*4))));
            assertFalse(capture.add(new SceneInputs.PreparedSource(packet.content(),List.of(large),List.of())));
            assertEquals(ScenePreparationPipeline.CaptureState.DISCARDED,capture.state());assertEquals(0,pipeline.captureStatistics().bytes());assertEquals(0,pipeline.statistics().compilationJobs());
        }
    }
}
