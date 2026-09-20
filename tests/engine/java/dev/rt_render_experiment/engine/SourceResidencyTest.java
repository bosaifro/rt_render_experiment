package dev.rt_render_experiment.engine;

import dev.rt_render_experiment.contract.SceneInputs;
import static org.junit.jupiter.api.Assertions.*;

final class SourceResidencyTest {
    @org.junit.jupiter.api.Test void repeatedNearbyDirtinessCannotStarveUnpreparedContributors() {
        var store=new SceneStore(new SceneCompiler(),new SceneStore.Budget(2,128));store.world(1);
        var residency=new SourceResidency();var origin=new SceneInputs.Origin(0,0,0);
        var demand=new SourceResidency.Demand(origin,1,200);var seen=new java.util.HashSet<SceneInputs.Key>();int nearby=0;
        for(int source=1;source<=32;source++)residency.source(source(source,1),store);
        for(int frame=0;frame<132;frame++) {
            residency.source(source(0,frame+1),store);
            var requests=residency.requests(store,demand,2,128);assertTrue(requests.size()<=2);
            if(requests.isEmpty())continue;


            var first=requests.getFirst();
            assertTrue(store.publish(new SceneInputs.PreparationResult(first,SceneInputs.PreparationStatus.READY,java.util.List.of(),0,"Prepared empty contributor")));
            residency.completed(new CompilationQueue.Outcome(first,true,""));
            if(first.source().low()==0)nearby++;else seen.add(first.source());
            for(int i=1;i<requests.size();i++) { store.cancel(requests.get(i));residency.rejected(requests.get(i)); }
        }
        assertEquals(32,seen.size(),"Nearby invalidation monopolized discovery under the real one-preparation time limit");
        assertTrue(nearby>=32,"Discovery starved current primary work");
    }
    private static SourceResidency.Source source(int id,long revision) {
        double x=id==0?0:50+id;var p=new SceneInputs.Origin(x,0,0);
        return new SourceResidency.Source(new SceneInputs.Key(1,id),new SceneInputs.Revision(1,revision,0,0,revision,1,1),new SourceResidency.Bounds(p,p),64);
    }
    @org.junit.jupiter.api.Test void failedPreparationCountsAsWorkButBudgetRejectionDoesNot() {
        var store=new SceneStore(new SceneCompiler(),new SceneStore.Budget(2,128));store.world(1);
        var residency=new SourceResidency();var demand=new SourceResidency.Demand(new SceneInputs.Origin(0,0,0),1,200);
        residency.source(source(0,1),store);residency.source(source(1,1),store);
        var first=residency.requests(store,demand,2,128);
        assertEquals(0,first.getFirst().source().low());
        assertTrue(residency.preparing(first.getFirst()));assertTrue(residency.preparing(first.getFirst()));
        for(var request:first) { store.cancel(request);residency.rejected(request); }
        assertFalse(residency.preparing(first.getFirst()));
        var second=residency.requests(store,demand,2,128);
        assertEquals(1,second.getFirst().source().low(),"The unstarted reservation lost its service priority");
        assertFalse(residency.readiness(demand,store.publication()).complete(),"Failed work became a valid source publication");
        assertFalse(residency.readiness(demand,store.publication()).renderable(),"An attempted producer was treated as available content");
        for(var request:second) { store.cancel(request);residency.rejected(request); }
        residency.remove(new SceneInputs.Key(1,1),store);residency.clear();
        residency.source(source(0,2),store);residency.source(source(1,2),store);
        assertEquals(0,residency.requests(store,demand,2,128).getFirst().source().low(),"World reset retained scheduling state");
    }
    @org.junit.jupiter.api.Test void admissionRetainsCoherentPredecessorsButRejectsMissingOrIncompatibleContent() {
        var store=new SceneStore(new SceneCompiler(),new SceneStore.Budget(2,128));store.world(1);
        var residency=new SourceResidency();var demand=new SourceResidency.Demand(new SceneInputs.Origin(0,0,0),1,200);
        var original=source(0,1);residency.source(original,store);
        var request=residency.requests(store,demand,2,128).getFirst();
        assertFalse(residency.readiness(demand,store.publication()).renderable());
        assertTrue(store.publish(new SceneInputs.PreparationResult(request,SceneInputs.PreparationStatus.READY,java.util.List.of(),0,"Prepared source")));
        residency.completed(new CompilationQueue.Outcome(request,true,""));
        assertTrue(residency.readiness(demand,store.publication()).complete());
        residency.source(source(0,2),store);var ready=residency.readiness(demand,store.publication());
        assertFalse(ready.complete());assertTrue(ready.renderable());assertEquals(1,ready.updating());
        residency.source(source(1,1),store);assertFalse(residency.readiness(demand,store.publication()).renderable(),"Missing first publication was hidden");
        residency.remove(source(1,1).key(),store);
        var reload=new SourceResidency.Source(original.key(),new SceneInputs.Revision(1,3,0,0,3,2,1),original.bounds(),64);
        residency.source(reload,store);assertFalse(residency.readiness(demand,store.publication()).renderable(),"Old resource generation crossed reload");
        var newWorld=new SourceResidency.Source(original.key(),new SceneInputs.Revision(2,3,0,0,3,1,1),original.bounds(),64);
        residency.source(newWorld,store);assertFalse(residency.readiness(demand,store.publication()).renderable(),"Old world content was admitted");
    }
    @org.junit.jupiter.api.Test void independentSecondaryDemandAndBoundedPreparation() {
        var store=new SceneStore(new SceneCompiler(),new SceneStore.Budget(4,8192));store.world(1);
        var residency=new SourceResidency();var revision=new SceneInputs.Revision(1,1,0,0,1,1,1);
        for(int i=0;i<3;i++)residency.source(new SourceResidency.Source(new SceneInputs.Key(1,i),revision,
            new SourceResidency.Bounds(new SceneInputs.Origin(i*20,0,0),new SceneInputs.Origin(i*20+1,1,1)),2048));
        var requests=residency.requests(store,new SourceResidency.Demand(new SceneInputs.Origin(0,0,0),5,30),4,8192);
        assertEquals(2,requests.size());assertEquals(0,requests.getFirst().source().low());assertEquals(1,requests.getLast().source().low());
        assertEquals(0,residency.requests(store,new SourceResidency.Demand(new SceneInputs.Origin(0,0,0),5,30),4,8192).size());
        residency.remove(new SceneInputs.Key(1,1),store);assertEquals(1,store.pendingJobs());
    }
    @org.junit.jupiter.api.Test void anExclusionCannotQualifyIncompatibleBytesStillInTheScene() {
        var store=new SceneStore(new SceneCompiler(),new SceneStore.Budget(2,131072));store.world(1);
        var residency=new SourceResidency();var old=SourcePublicationTest.source(SourcePublicationTest.A,1,1,20);
        residency.source(old,store);var request=residency.requests(store,SourcePublicationTest.NEAR,2,131072).getFirst();
        assertTrue(store.publish(SourcePublicationTest.packet(request,20,1)));residency.completed(new CompilationQueue.Outcome(request,true,""));
        residency.source(SourcePublicationTest.source(old.key(),2,2,1000),store);
        var next=residency.requests(store,SourcePublicationTest.NEAR,2,131072).getFirst();
        var staged=SceneStore.compile(SourcePublicationTest.packet(next,1000,2));residency.resolved(next,staged.bounds());
        assertFalse(residency.readiness(SourcePublicationTest.NEAR,store.publication()).renderable(),"A new outside extent qualified old in-range resource bytes");
        assertEquals(0,residency.readiness(SourcePublicationTest.NEAR,store.publication()).excludedPredecessors());
    }
}
