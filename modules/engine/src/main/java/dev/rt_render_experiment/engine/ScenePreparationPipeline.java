package dev.rt_render_experiment.engine;

import java.util.List;
import dev.rt_render_experiment.contract.SceneInputs;


public final class ScenePreparationPipeline implements AutoCloseable {
    public record Window(int preparationJobs,long preparationBytes,int publicationJobs,long publicationNanos) {
        public Window {
            if(preparationJobs<=0 || preparationBytes<=0 || publicationJobs<=0 || publicationNanos<=0)throw new IllegalArgumentException("Invalid source work window");
        }
    }

    public record Publication(SceneStore.Revision scene,SourceResidency.Readiness readiness,boolean candidateCoherenceBlocked) {}
    public record Statistics(int sources,int preparationRequests,int compilationJobs,long compilationBytes,long residentBytes,boolean coherenceBlocked,
                             int retainedExtents,long extentFactBytes) {}
    public record CaptureStatistics(int sources,int completedSources,long bytes,long submitted,long discarded) {}
    private final SceneStore store;
    private final SourceResidency residency=new SourceResidency();
    private final CompilationQueue queue;
    private final Thread owner=Thread.currentThread();
    private long world;
    private boolean closed;
    private Capture capture;
    private long submittedCaptures,discardedCaptures;

    public ScenePreparationPipeline(int workers,SceneStore.Budget sceneBudget,CompilationQueue.Budget compilationBudget) {
        store=new SceneStore(new SceneCompiler(),sceneBudget);queue=new CompilationQueue(workers,compilationBudget);
    }

    ScenePreparationPipeline(java.util.concurrent.ExecutorService workers,SceneStore.Budget sceneBudget,CompilationQueue.Budget compilationBudget) {
        store=new SceneStore(new SceneCompiler(),sceneBudget);queue=new CompilationQueue(workers,compilationBudget);
    }
    public void world(long epoch) {
        requireOpen();if(epoch<=world)throw new IllegalArgumentException("World epoch must advance");
        if(capture!=null)capture.close();


        queue.cancelAll();residency.clear();store.world(epoch);world=epoch;
    }
    public void source(SourceResidency.Source source) {
        requireOpen();if(source.revision().world()!=world)throw new IllegalArgumentException("Foreign source world");
        if(residency.source(source,store))queue.cancel(source.key());
        store.expected(source.key(),source.revision());
        if(capture!=null && !capture.current())capture.close();
    }
    public SceneInputs.Revision publishedRevision(SceneInputs.Key source) { requireOpen();return store.publishedRevision(source); }
    public boolean demanded(SceneInputs.Key source,SourceResidency.Demand demand) { requireOpen();return residency.demanded(source,demand,store.publication()); }
    public void remove(SceneInputs.Key source) {
        requireOpen();queue.cancel(source);residency.remove(source,store);
        if(capture!=null && !capture.current())capture.close();
    }

    public void relatedSources(SceneInputs.RelatedSources relation) {
        requireOpen();
        for(var member:relation.required().entrySet()) {
            var source=residency.source(member.getKey());
            if(source==null || !source.revision().equals(member.getValue()))throw new IllegalArgumentException("Related source lacks its exact registered revision");
        }
        store.related(relation);
    }

    public void emptySource(SourceResidency.Source source) {
        source(source);queue.cancel(source.key());residency.cancelPreparation(source.key(),store);
        if(capture!=null && !capture.current())capture.close();
        residency.empty(store.publishEmpty(source.key(),source.revision()));
    }

    public Capture beginCapture(SourceResidency.Demand demand,Window window) {
        requireOpen();
        if(capture!=null) { if(capture.current())return capture;capture.close(); }
        int jobs=Math.min(window.preparationJobs(),queue.availableJobs());long bytes=Math.min(window.preparationBytes(),queue.availableBytes());
        if(jobs<=0 || bytes<=0)return null;
        var requests=requests(demand,new Window(jobs,bytes,window.publicationJobs(),window.publicationNanos()));
        if(requests.isEmpty())return null;
        capture=new Capture(requests);return capture;
    }
    public enum CaptureState { OPEN, QUEUED, DISCARDED }
    public final class Capture implements AutoCloseable {
        private final List<SceneInputs.PreparationRequest> requests;
        private final java.util.Map<SceneInputs.Key,SceneInputs.PreparedSource> completed=new java.util.LinkedHashMap<>();
        private long bytes;
        private CaptureState state=CaptureState.OPEN;
        private Capture(List<SceneInputs.PreparationRequest> requests) { this.requests=List.copyOf(requests); }
        public List<SceneInputs.PreparationRequest> requests() { return requests; }
        public CaptureState state() { return state; }
        public boolean current() {
            requireOpen();return state==CaptureState.OPEN && requests.stream().allMatch(store::current);
        }
        public List<SceneInputs.PreparationRequest> remaining() {
            requireOpen();if(state!=CaptureState.OPEN)return List.of();
            return requests.stream().filter(r->!completed.containsKey(r.source())).toList();
        }
        public boolean complete() { requireOpen();return current() && completed.size()==requests.size(); }
        public boolean add(SceneInputs.PreparedSource source) {
            requireOpen();var request=source.content().request();
            if(state!=CaptureState.OPEN || !requests.contains(request) || completed.containsKey(request.source()) || source.content().status()!=SceneInputs.PreparationStatus.READY)
                throw new IllegalArgumentException("Capture requires one complete result for its exact reserved member");
            if(!current()) { close();return false; }
            long next=Math.addExact(bytes,SceneStore.workBytes(source));
            if(next>queue.availableBytes()) { close();return false; }
            completed.put(request.source(),source);bytes=next;return true;
        }
        public boolean submit() {
            requireOpen();if(!complete()) { if(!current())close();return false; }
            var values=List.copyOf(completed.values());capture=null;
            try {
                boolean accepted=acceptCohort(values);state=accepted?CaptureState.QUEUED:CaptureState.DISCARDED;
                if(accepted)submittedCaptures++;else discardedCaptures++;return accepted;
            } catch(RuntimeException|Error failure) { state=CaptureState.DISCARDED;discardedCaptures++;throw failure; }
            finally { completed.clear();bytes=0; }
        }
        @Override public void close() {
            requireOpen();if(state!=CaptureState.OPEN)return;
            state=CaptureState.DISCARDED;completed.clear();bytes=0;if(capture==this)capture=null;
            for(var request:requests)reject(request);discardedCaptures++;
        }
    }

    public List<SceneInputs.PreparationRequest> requests(SourceResidency.Demand demand,Window window) {
        requireOpen();
        if(capture!=null)throw new IllegalStateException("An open capture owns the preparation reservations");
        for(var key:residency.evictOutside(store,demand))queue.cancel(key);
        return residency.requests(store,demand,window.preparationJobs(),window.preparationBytes());
    }
    public boolean accept(SceneInputs.PreparedSource source) {
        requireOpen();var content=source.content();
        if(!store.current(content.request()))return false;
        if(content.status()!=SceneInputs.PreparationStatus.READY) {
            store.publish(source);residency.rejected(content.request());return false;
        }
        return acceptCohort(List.of(source));
    }





    public boolean acceptCohort(List<SceneInputs.PreparedSource> input) {
        requireOpen();var sources=List.copyOf(input);
        if(capture!=null)throw new IllegalStateException("An open producer capture owns this preparation lane");
        if(sources.isEmpty())throw new IllegalArgumentException("Empty source cohort");
        var keys=new java.util.HashSet<SceneInputs.Key>();
        for(var source:sources) {
            var content=source.content();
            if(content.status()!=SceneInputs.PreparationStatus.READY || !keys.add(content.request().source()))throw new IllegalArgumentException("Cohort requires distinct complete sources");
            if(residency.isCaptured(content.request()))throw new IllegalArgumentException("Source request already captured");
        }
        if(sources.stream().anyMatch(source->!store.current(source.content().request()))) {
            for(var source:sources)reject(source.content().request());return false;
        }
        for(var source:sources)residency.preparing(source.content().request());
        try {
            if(queue.submitCohort(sources,store.compilationPredecessors(sources))) {
                for(var source:sources)residency.captured(source.content().request());
                store.captured(sources.stream().map(source->source.content().request()).toList());return true;
            }
        } catch(RuntimeException|Error failure) {
            for(var source:sources)reject(source.content().request());throw failure;
        }
        for(var source:sources)reject(source.content().request());return false;
    }

    public boolean beginPreparation(SceneInputs.PreparationRequest request) {
        requireOpen();return store.current(request) && residency.preparing(request);
    }
    public void reject(SceneInputs.PreparationRequest request) {
        requireOpen();if(residency.isCaptured(request))queue.cancel(request.source());store.cancel(request);residency.rejected(request);
    }
    public List<CompilationQueue.Outcome> publishReady(Window window) {
        requireOpen();var outcomes=queue.publishReady(store,window.publicationJobs(),window.publicationNanos());
        for(var outcome:outcomes) {
            if(outcome.published())residency.resolved(outcome.request(),store.bounds(outcome.request().source()));
            residency.completed(outcome);
        }
        return outcomes;
    }
    public void updateAppearance(List<SceneStore.AppearanceUpdate> updates) { requireOpen();store.updateAppearance(updates); }
    public java.util.Map<SceneInputs.Key,Long> appearanceRevisions() { requireOpen();return store.appearanceRevisions(); }
    public SceneStore.Revision snapshot() { requireOpen();return store.snapshot(); }
    public SourceResidency.Readiness readiness(SourceResidency.Demand demand) {
        return publication(demand).readiness();
    }
    public Publication publication(SourceResidency.Demand demand) {
        requireOpen();var complete=store.publication();var selected=store.select(demand);
        return new Publication(selected.scene(),residency.readiness(demand,complete),store.coherenceBlocked());
    }
    public java.util.Set<SceneInputs.Key> resourceKeys() {
        requireOpen();var keys=new java.util.HashSet<>(store.resourceKeys());keys.addAll(queue.resourceKeys());
        if(capture!=null)for(var source:capture.completed.values())for(var texture:source.textures())keys.add(texture.key());
        return java.util.Set.copyOf(keys);
    }
    public Statistics statistics() {
        requireOpen();return new Statistics(residency.sourceCount(),store.pendingJobs(),queue.pendingJobs(),queue.pendingBytes(),store.residentBytes(),store.coherenceBlocked(),
            residency.extentCount(),residency.extentFactBytes());
    }
    public CompilationQueue.Statistics cohortStatistics() { requireOpen();return queue.statistics(); }
    public SceneStore.GroupStatistics groupStatistics() { requireOpen();return store.groupStatistics(); }
    public CaptureStatistics captureStatistics() {
        requireOpen();return new CaptureStatistics(capture==null?0:capture.requests.size(),capture==null?0:capture.completed.size(),capture==null?0:capture.bytes,submittedCaptures,discardedCaptures);
    }
    private void requireOpen() { if(closed || Thread.currentThread()!=owner)throw new IllegalStateException("Source scheduling requires its live owner thread"); }
    @Override public void close() {
        if(closed)return;requireOpen();if(capture!=null)capture.close();closed=true;queue.close();residency.clear();store.clear();
    }
}
