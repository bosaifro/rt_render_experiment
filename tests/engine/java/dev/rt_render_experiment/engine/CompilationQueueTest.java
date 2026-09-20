package dev.rt_render_experiment.engine;

import java.util.List;
import dev.rt_render_experiment.contract.SceneInputs;
import static org.junit.jupiter.api.Assertions.*;

final class CompilationQueueTest {
    @org.junit.jupiter.api.Test void boundedWorkersAndStalePublication() throws Exception {
        var store=new SceneStore(new SceneCompiler(),new SceneStore.Budget(4,8192));store.world(1);
        var source=SceneContractsTest.fixture(1);var first=store.request(source.key(),source.revision(),4096);
        try(var queue=new CompilationQueue(2,new CompilationQueue.Budget(1,4096))) {
            var prepared=new SceneInputs.PreparationResult(first,SceneInputs.PreparationStatus.READY,List.of(source),4096,"Complete immutable fixture");
            assertTrue(queue.submit(prepared)); assertFalse(queue.submit(prepared));
            var replacement=SceneContractsTest.fixture(2);var current=store.request(source.key(),replacement.revision(),4096);
            List<CompilationQueue.Outcome> outcomes=List.of();long deadline=System.nanoTime()+2_000_000_000L;
            while(outcomes.isEmpty() && System.nanoTime()<deadline) { outcomes=queue.publishReady(store,1,1_000_000); if(outcomes.isEmpty())Thread.sleep(1); }
            assertEquals(1,outcomes.size()); assertFalse(outcomes.getFirst().published()); assertEquals(0,queue.pendingBytes());
            assertTrue(queue.submit(new SceneInputs.PreparationResult(current,SceneInputs.PreparationStatus.READY,List.of(replacement),4096,"Updated fixture")));
            outcomes=List.of();deadline=System.nanoTime()+2_000_000_000L;
            while(outcomes.isEmpty() && System.nanoTime()<deadline) { outcomes=queue.publishReady(store,1,1_000_000);if(outcomes.isEmpty())Thread.sleep(1); }
            assertTrue(outcomes.getFirst().published());assertEquals(2,store.snapshot().geometry().getFirst().input().revision().topology());
            var alien=new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var thread=Thread.ofPlatform().start(()->{try{store.world(2);}catch(Throwable failure){alien.set(failure);}});thread.join();
            assertInstanceOf(IllegalStateException.class,alien.get());
        }
    }
}
