package dev.rt_render_experiment.engine;

import java.nio.ByteBuffer;
import java.util.List;
import dev.rt_render_experiment.contract.LightInputs;
import dev.rt_render_experiment.contract.MaterialInputs;
import dev.rt_render_experiment.contract.SceneInputs;
import dev.rt_render_experiment.contract.TextureInputs;
import static org.junit.jupiter.api.Assertions.*;

final class SceneBundlesTest {
    @org.junit.jupiter.api.Test void appearanceConsumerIndexFollowsRtRenderExperimentderedInputsAndRetirement() {
        var store=store();var owner=new SceneInputs.Key(1,1);var a=texture(1,32);
        var b=new TextureInputs.Texture(new SceneInputs.Key(99,2),10,a.encoding(),a.sampling(),a.levels(),false);
        var first=source(store,owner,1,a);
        store.publish(new SceneInputs.PreparedSource(first.content(),List.of(a,b),first.lights()));
        long before=store.residentBytes();var keys=store.resourceKeys();assertEquals(java.util.Set.of(a.key(),b.key()),keys);
        assertSame(keys,store.resourceKeys());assertEquals(before,store.residentBytes());
        store.updateAppearance(List.of(new SceneStore.AppearanceUpdate(1,1,texture(2,64))));
        var rt_render_experimentdered=source(store,owner,2,texture(2,64));
        store.publish(new SceneInputs.PreparedSource(rt_render_experimentdered.content(),List.of(b,texture(2,64)),rt_render_experimentdered.lights()));
        store.updateAppearance(List.of(new SceneStore.AppearanceUpdate(1,2,texture(3,192))));
        assertEquals(3,store.appearanceRevisions().get(a.key()));assertEquals(10,store.appearanceRevisions().get(b.key()));
        assertEquals(192,Byte.toUnsignedInt(store.snapshot().textures().stream().filter(t->t.key().equals(a.key())).findFirst().orElseThrow().levels().getFirst().pixels().get(0)));
        store.remove(owner);assertEquals(0,store.residentBytes());assertTrue(store.resourceKeys().isEmpty());
        assertThrows(IllegalArgumentException.class,()->store.updateAppearance(List.of(new SceneStore.AppearanceUpdate(1,3,texture(4,255)))));
        assertEquals(java.util.Set.of(a.key(),b.key()),keys,"An old resource-key snapshot was mutated");
    }
    @org.junit.jupiter.api.Test void oneExpiredMemberCannotExposeHalfOfADeclaredCohort() {
        var store=store();var a=new SceneInputs.Key(1,1);var b=new SceneInputs.Key(1,2);
        store.publish(source(store,a,1,texture(1,32)));store.publish(source(store,b,1,texture(1,32)));var before=store.snapshot();
        var first=SceneStore.compile(source(store,a,2,texture(1,32)));var second=SceneStore.compile(source(store,b,2,texture(1,32)));
        store.cancel(second.request());
        assertFalse(store.publishCompiledCohort(List.of(first,second)));
        assertSame(before,store.snapshot(),"An expired companion exposed only one side of a captured update");
    }
    @org.junit.jupiter.api.Test void lateFirstConsumerUsesTheStagedBaselineEvenWhenTheProducerHasNotAdvancedAgain() {
        var store=store();var key=texture(1,32).key();
        var delayed=source(store,new SceneInputs.Key(1,1),1,texture(1,32));
        store.publish(source(store,new SceneInputs.Key(1,2),1,texture(2,192)));
        assertEquals(2,store.snapshot().textures().getFirst().revision());store.publish(delayed);assertTrue(store.coherenceBlocked());
        assertEquals(1,store.appearanceRevisions().get(key));
        store.updateAppearance(List.of(new SceneStore.AppearanceUpdate(1,store.appearanceRevisions().get(key),texture(2,192))));
        assertFalse(store.coherenceBlocked());assertEquals(2,store.snapshot().geometry().size());
    }
    @org.junit.jupiter.api.Test void firstMixedResourceBatchCanConvergeWithoutMoreGeometryJobs() {
        var store=store();var first=SceneStore.compile(source(store,new SceneInputs.Key(1,1),1,texture(1,32)));
        var second=SceneStore.compile(source(store,new SceneInputs.Key(1,2),1,texture(2,192)));
        store.publishCompiledBatch(List.of(first,second));
        assertTrue(store.coherenceBlocked());assertTrue(store.snapshot().textures().isEmpty());assertEquals(0,store.pendingJobs());
        var demand=store.appearanceRevisions();assertEquals(java.util.Map.of(texture(1,32).key(),1L),demand);
        assertSame(demand,store.appearanceRevisions(),"Unchanged preparation demand should reuse its immutable value");
        assertThrows(UnsupportedOperationException.class,()->demand.clear());
        assertThrows(IllegalArgumentException.class,()->store.updateAppearance(List.of(new SceneStore.AppearanceUpdate(1,2,texture(3,255)))));
        assertThrows(IllegalArgumentException.class,()->store.updateAppearance(List.of(new SceneStore.AppearanceUpdate(1,1,texture(2,33)))));
        assertThrows(IllegalArgumentException.class,()->store.updateAppearance(List.of(new SceneStore.AppearanceUpdate(2,1,texture(2,192)))));
        assertTrue(store.coherenceBlocked());assertTrue(store.snapshot().textures().isEmpty());
        store.updateAppearance(List.of(new SceneStore.AppearanceUpdate(1,1,texture(2,192))));
        assertFalse(store.coherenceBlocked());assertEquals(2,store.snapshot().textures().getFirst().revision());
        assertEquals(2,store.appearanceRevisions().get(texture(1,32).key()));assertEquals(1,demand.get(texture(1,32).key()));
        assertSame(first.geometry(),store.snapshot().geometry().get(0));assertSame(second.geometry(),store.snapshot().geometry().get(1));
    }
    @org.junit.jupiter.api.Test void readyCohortHasOneAtomicSceneRevision() {
        var store=store();var a=new SceneInputs.Key(1,1);var b=new SceneInputs.Key(1,2);
        store.publish(source(store,a,1,texture(1,32)));store.publish(source(store,b,1,texture(1,32)));long serial=store.snapshot().serial();
        var first=SceneStore.compile(source(store,a,2,texture(2,192)));var second=SceneStore.compile(source(store,b,2,texture(2,192)));
        assertEquals(List.of(true,true),store.publishCompiledBatch(List.of(second,first)));
        assertEquals(serial+1,store.snapshot().serial());assertFalse(store.coherenceBlocked());assertEquals(0,store.pendingJobs());
        var before=store.snapshot();
        var invalid=SceneStore.compile(source(store,new SceneInputs.Key(1,3),1,texture(2,32)));
        var valid=SceneStore.compile(source(store,new SceneInputs.Key(1,4),1,texture(2,192)));
        assertThrows(IllegalArgumentException.class,()->store.publishCompiledBatch(List.of(valid,invalid)));
        assertSame(before,store.snapshot());assertEquals(2,store.pendingJobs());
    }
    @org.junit.jupiter.api.Test void sharedResourcesPublishCoherentlyAcrossOutOfOrderJobs() {
        var store=store();var texture=texture(1,32);var a=new SceneInputs.Key(1,1);var b=new SceneInputs.Key(1,2);
        assertTrue(store.publish(source(store,a,1,texture)));assertTrue(store.publish(source(store,b,1,texture)));
        var before=store.snapshot();assertEquals(2,before.geometry().size());assertEquals(1,before.textures().size());assertEquals(2,before.lights().size());
        var updated=texture(2,192);
        assertTrue(store.publish(source(store,a,2,updated)));
        assertTrue(store.coherenceBlocked());assertSame(before,store.snapshot());
        assertTrue(store.publish(source(store,b,2,updated)));
        assertFalse(store.coherenceBlocked());assertEquals(2,store.snapshot().textures().getFirst().revision());
        assertTrue(store.snapshot().geometry().stream().allMatch(g->g.input().revision().topology()==2));
        assertTrue(before.textures().getFirst().levels().getFirst().pixels().get(0)==32);
    }
    @org.junit.jupiter.api.Test void appearanceUpdatesDoNotRemeshAndLateJobsCannotRevertThem() {
        var store=store();var a=new SceneInputs.Key(1,1);var b=new SceneInputs.Key(1,2);var old=texture(1,32);
        store.publish(source(store,a,1,old));store.publish(source(store,b,1,old));
        var prior=store.snapshot();var delayed=source(store,a,2,old);
        store.updateAppearance(List.of(new SceneStore.AppearanceUpdate(1,1,texture(3,192))));
        for(int i=0;i<2;i++)assertSame(prior.geometry().get(i),store.snapshot().geometry().get(i));
        assertEquals(3,store.snapshot().textures().getFirst().revision());
        assertTrue(store.publish(delayed));assertFalse(store.coherenceBlocked());assertEquals(3,store.snapshot().textures().getFirst().revision());
        var current=store.snapshot();
        assertThrows(IllegalArgumentException.class,()->store.updateAppearance(List.of(new SceneStore.AppearanceUpdate(1,1,texture(4,255)))));
        var resized=new TextureInputs.Texture(old.key(),4,old.encoding(),old.sampling(),List.of(new TextureInputs.Level(1,1,ByteBuffer.wrap(new byte[]{1,2,3,4}))),false);
        assertThrows(IllegalArgumentException.class,()->store.updateAppearance(List.of(new SceneStore.AppearanceUpdate(1,3,resized))));
        assertSame(current,store.snapshot());
    }
    @org.junit.jupiter.api.Test void explicitAppearanceAdvanceReconcilesAlreadyStagedConsumers() {
        var store=store();var a=new SceneInputs.Key(1,1);var b=new SceneInputs.Key(1,2);
        store.publish(source(store,a,1,texture(1,32)));store.publish(source(store,b,1,texture(1,32)));
        store.publish(source(store,a,2,texture(2,96)));assertTrue(store.coherenceBlocked());
        store.updateAppearance(List.of(new SceneStore.AppearanceUpdate(1,1,texture(3,192))));
        assertFalse(store.coherenceBlocked());assertEquals(3,store.snapshot().textures().getFirst().revision());
        assertEquals(2,store.snapshot().geometry().getFirst().input().revision().topology());
    }
    @org.junit.jupiter.api.Test void missingDependenciesAndContradictoryIdentityFailWithoutDisplacingPredecessor() {
        var store=store();var a=new SceneInputs.Key(1,1);var b=new SceneInputs.Key(1,2);var texture=texture(1,32);
        var complete=source(store,a,1,texture);
        assertThrows(IllegalArgumentException.class,()->store.publish(SceneInputs.PreparedSource.geometryOnly(complete.content())));
        assertTrue(store.publish(complete));var before=store.snapshot();
        var contradictory=source(store,b,1,texture(1,192));
        assertThrows(IllegalArgumentException.class,()->store.publish(contradictory));assertSame(before,store.snapshot());
        var invalidLight=new LightInputs.Area(new SceneInputs.Key(3,1),1,new LightInputs.Emission(0,1,new SceneInputs.Vec3(0,0,0)),new SceneInputs.Origin(0,0,0),0,
            LightInputs.Stratum.WORLD,b,new SceneInputs.Key(999,999),0);
        assertThrows(IllegalArgumentException.class,()->store.publish(new SceneInputs.PreparedSource(contradictory.content(),contradictory.textures(),List.of(invalidLight))));
        assertSame(before,store.snapshot());
    }
    @org.junit.jupiter.api.Test void unloadRemovesBlockedPredecessorAndAllItsAssociations() {
        var store=store();var a=new SceneInputs.Key(1,1);var b=new SceneInputs.Key(1,2);
        store.publish(source(store,a,1,texture(1,32)));store.publish(source(store,b,1,texture(1,32)));
        store.publish(source(store,a,2,texture(2,192)));assertTrue(store.coherenceBlocked());
        store.remove(b);assertFalse(store.coherenceBlocked());assertEquals(1,store.snapshot().geometry().size());assertEquals(1,store.snapshot().lights().size());
        assertEquals(a,store.snapshot().geometry().getFirst().input().key());
        store.remove(a);assertTrue(store.snapshot().textures().isEmpty());assertTrue(store.snapshot().lights().isEmpty());assertEquals(0,store.residentBytes());
    }
    @org.junit.jupiter.api.Test void residencyBudgetIncludesOldAndNewGenerations() {
        var probe=store();var key=new SceneInputs.Key(1,1);long bytes=SceneStore.compile(source(probe,key,1,texture(1,32))).bytes();
        var store=new SceneStore(new SceneCompiler(),new SceneStore.Budget(4,16384,2*bytes-1));store.world(1);
        store.publish(source(store,key,1,texture(1,32)));var before=store.snapshot();


        var replacement=SceneStore.compile(source(store,key,2,texture(2,192)));
        assertThrows(IllegalStateException.class,()->store.publishCompiled(replacement));assertSame(before,store.snapshot());
    }
    @org.junit.jupiter.api.Test void equalSharedTextureKeepsItsCanonicalObjectWhenAnOwnerLeaves() {
        var store=store();var a=new SceneInputs.Key(1,1);var b=new SceneInputs.Key(1,2);
        store.publish(source(store,a,1,texture(1,32)));store.publish(source(store,b,1,texture(1,32)));
        var canonical=store.snapshot().textures().getFirst();store.remove(a);
        assertSame(canonical,store.snapshot().textures().getFirst(),"Equal remaining consumers churned the canonical texture object");
        store.remove(b);assertTrue(store.snapshot().textures().isEmpty());assertEquals(0,store.residentBytes());
    }
    @org.junit.jupiter.api.Test void workerCarriesResourcesAndChargesCancelledWorkUntilItExits() throws Exception {
        var store=store();var packet=source(store,new SceneInputs.Key(1,1),1,texture(1,32));long bytes=SceneStore.workBytes(packet);
        try(var queue=new CompilationQueue(1,new CompilationQueue.Budget(2,bytes))) {
            assertTrue(queue.submit(packet));assertEquals(bytes,queue.pendingBytes());assertFalse(queue.submit(packet));
            queue.cancel(packet.content().request().source());assertEquals(bytes,queue.pendingBytes());
            var outcomes=drain(queue,store);assertFalse(outcomes.getFirst().published());assertTrue(store.snapshot().geometry().isEmpty());assertEquals(0,queue.pendingBytes());
            var replacement=source(store,new SceneInputs.Key(1,1),2,texture(2,192));assertTrue(queue.submit(replacement));
            assertTrue(drain(queue,store).getFirst().published());assertEquals(2,store.snapshot().textures().getFirst().levels().size());assertEquals(1,store.snapshot().lights().size());
        }
    }
    static List<CompilationQueue.Outcome> drain(CompilationQueue queue,SceneStore store) throws InterruptedException {
        long deadline=System.nanoTime()+2_000_000_000L;
        while(System.nanoTime()<deadline) { var outcomes=queue.publishReady(store,10,1_000_000);if(!outcomes.isEmpty())return outcomes;Thread.sleep(1); }
        throw new AssertionError("Compilation did not complete");
    }
    private static SceneStore store() { var store=new SceneStore(new SceneCompiler(),new SceneStore.Budget(4,16384,1_000_000));store.world(1);return store; }
    static TextureInputs.Texture texture(long revision,int red) {
        var bytes=ByteBuffer.allocate(16);for(int i=0;i<4;i++)bytes.put(new byte[]{(byte)red,64,96,-1});
        return new TextureInputs.Texture(new SceneInputs.Key(9,1),revision,TextureInputs.Encoding.SRGB,TextureInputs.Address.CLAMP,List.of(new TextureInputs.Level(2,2,bytes.flip())));
    }
    static SceneInputs.PreparedSource source(SceneStore store,SceneInputs.Key key,long revision,TextureInputs.Texture texture) {
        var source=SceneContractsTest.fixture(revision);var primitive=source.primitives().getFirst();var s=primitive.surface();
        var surface=new SceneInputs.Surface(s.key(),s.material(),s.properties(),texture.key(),s.emissionResource(),s.coverage(),s.cutoff(),s.doubleSided(),s.medium(),s.layer(),s.layerSeparation(),MaterialInputs.Layers.plain(texture.key()),s.hostOcclusion());
        var geometry=new SceneInputs.Geometry(key,source.revision(),source.origin(),source.current(),source.previous(),source.previousValid(),source.motion(),source.participation(),
            List.of(new SceneInputs.Primitive(primitive.part(),primitive.ordinal(),surface,primitive.corners())));
        var request=store.request(key,geometry.revision(),4096);
        var light=new LightInputs.Area(new SceneInputs.Key(3,key.low()),revision,new LightInputs.Emission(0,1,new SceneInputs.Vec3(0,0,0)),geometry.origin(),0,
            LightInputs.Stratum.WORLD,key,primitive.part(),primitive.ordinal());
        return new SceneInputs.PreparedSource(new SceneInputs.PreparationResult(request,SceneInputs.PreparationStatus.READY,List.of(geometry),SceneCompiler.primitiveBytes(4),"Complete source"),List.of(texture),List.of(light));
    }
}
