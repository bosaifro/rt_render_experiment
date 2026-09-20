package dev.rt_render_experiment.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import dev.rt_render_experiment.contract.SceneInputs;


public final class CompilationQueue implements AutoCloseable {
    public record Budget(int jobs,long bytes) {
        public Budget { if(jobs<=0 || bytes<=0)throw new IllegalArgumentException("Invalid compiler pressure budget"); }
    }

    public record Outcome(SceneInputs.PreparationRequest request,boolean published,String failure) {}
    public record Statistics(int pendingCohorts,long submittedCohorts,long installedCohorts,long rejectedCohorts,int largestCohort,
                             long reusedGeometryPublications,long reusedGeometryBytes) {}
    private record Member(SceneInputs.PreparedSource prepared,SceneCompiler.Compiled predecessor,Future<SceneStore.CompiledSource> result) {}
    private record Work(List<Member> members,long bytes,java.util.concurrent.atomic.AtomicBoolean cancelled) {}
    private final ExecutorService workers;
    private final Budget budget;
    private final Thread owner=Thread.currentThread();
    private final ArrayDeque<Work> pending=new ArrayDeque<>();
    private long bytes,submittedCohorts,installedCohorts,rejectedCohorts,reusedGeometryPublications,reusedGeometryBytes;
    private int jobs,largestCohort;
    private boolean closed;
    public CompilationQueue(int threads,Budget budget) {
        this(executor(threads),budget);
    }

    CompilationQueue(ExecutorService workers,Budget budget) {
        this.workers=java.util.Objects.requireNonNull(workers);this.budget=java.util.Objects.requireNonNull(budget);
    }
    private static ExecutorService executor(int threads) {
        if(threads<=0)throw new IllegalArgumentException("Invalid worker count");
        return Executors.newFixedThreadPool(threads,Thread.ofPlatform().daemon().name("RtRenderExperiment scene compiler-",0).factory());
    }
    public boolean submit(SceneInputs.PreparationResult prepared) { return submit(SceneInputs.PreparedSource.geometryOnly(prepared)); }
    public boolean submit(SceneInputs.PreparedSource prepared) { return submitCohort(List.of(prepared)); }





    public boolean submitCohort(List<SceneInputs.PreparedSource> input) {
        return submitCohort(input,java.util.Map.of());
    }
    boolean submitCohort(List<SceneInputs.PreparedSource> input,java.util.Map<SceneInputs.Key,SceneCompiler.Compiled> previous) {
        requireOwner();if(closed)throw new IllegalStateException("Compiler queue is closed");
        var prepared=List.copyOf(input);if(prepared.isEmpty())throw new IllegalArgumentException("Empty source cohort");
        var keys=new HashSet<SceneInputs.Key>();long weight=0,world=prepared.getFirst().content().request().expected().world();
        for(var source:prepared) {
            var content=source.content();
            if(content.status()!=SceneInputs.PreparationStatus.READY || content.geometry().size()>1 || content.request().expected().world()!=world
                || !keys.add(content.request().source()))throw new IllegalArgumentException("Cohort requires complete distinct sources from one world");
            weight=Math.addExact(weight,SceneStore.workBytes(source));
        }
        long demand=Math.addExact(bytes,weight);
        if(prepared.size()>budget.jobs()-jobs || demand>budget.bytes())return false;


        var retained=new java.util.HashMap<SceneInputs.Key,SceneCompiler.Compiled>();
        for(var source:prepared) {
            var key=source.content().request().source();var before=previous.get(key);
            if(before!=null && before.bytes()<=budget.bytes()-demand) { retained.put(key,before);weight=Math.addExact(weight,before.bytes());demand=Math.addExact(demand,before.bytes()); }
        }
        for(var work:pending)for(var member:work.members)for(var source:prepared)
            if(member.prepared.content().request().equals(source.content().request()))throw new IllegalArgumentException("Source request already captured");
        var cancelled=new java.util.concurrent.atomic.AtomicBoolean();var members=new ArrayList<Member>();
        try {
            for(var source:prepared) {
                var before=retained.get(source.content().request().source());
                members.add(new Member(source,before,workers.submit(()->cancelled.get()?null:SceneStore.compile(source,before))));
            }
        } catch(RuntimeException|Error failure) {


            cancelled.set(true);
            if(!members.isEmpty()) {
                pending.addLast(new Work(List.copyOf(members),weight,cancelled));bytes=demand;jobs+=members.size();submittedCohorts++;
                largestCohort=Math.max(largestCohort,prepared.size());
            }
            throw failure;
        }
        pending.addLast(new Work(List.copyOf(members),weight,cancelled));bytes=demand;jobs+=members.size();submittedCohorts++;
        largestCohort=Math.max(largestCohort,members.size());return true;
    }

    public void cancel(SceneInputs.Key source) {
        requireOwner();for(var work:pending)if(work.members.stream().anyMatch(m->m.prepared.content().request().source().equals(source)))work.cancelled.set(true);
    }
    public void cancelAll() { requireOwner();for(var work:pending)work.cancelled.set(true); }





    public List<Outcome> publishReady(SceneStore store,int maximumJobs,long maximumNanos) {
        requireOwner();if(maximumJobs<=0 || maximumNanos<=0)throw new IllegalArgumentException("Invalid publication window");
        long start=System.nanoTime();int selected=0;var outcomes=new ArrayList<Outcome>();var ready=new ArrayList<List<SceneStore.CompiledSource>>();
        for(var iterator=pending.iterator();iterator.hasNext() && selected<maximumJobs && System.nanoTime()-start<maximumNanos;) {
            var work=iterator.next();if(work.members.stream().anyMatch(m->!m.result.isDone()))continue;
            if(selected>0 && work.members.size()>maximumJobs-selected)continue;
            iterator.remove();bytes-=work.bytes;jobs-=work.members.size();selected+=work.members.size();
            var results=new ArrayList<SceneStore.CompiledSource>();String failure=work.cancelled.get()?"Cancelled":"";
            if(failure.isEmpty())for(var member:work.members)try { results.add(member.result.get()); }
            catch(InterruptedException interrupted) {
                Thread.currentThread().interrupt();failure="Interrupted compilation completion";break;
            } catch(java.util.concurrent.ExecutionException|RuntimeException error) {
                failure=error.getCause()==null?error.toString():error.getCause().toString();break;
            }
            if(!failure.isEmpty()) {
                rejectedCohorts++;
                for(var member:work.members) { var request=member.prepared.content().request();store.cancel(request);outcomes.add(new Outcome(request,false,failure)); }
            } else ready.add(List.copyOf(results));
        }
        if(!ready.isEmpty())try {
            var accepted=store.publishCompiledCohorts(ready);
            for(int i=0;i<ready.size();i++)complete(store,ready.get(i),accepted.get(i),"",outcomes);
        } catch(RuntimeException failedBatch) {


            for(var cohort:ready)try { complete(store,cohort,store.publishCompiledCohort(cohort),"",outcomes); }
            catch(RuntimeException failure) { complete(store,cohort,false,failure.toString(),outcomes); }
        }
        return List.copyOf(outcomes);
    }
    private void complete(SceneStore store,List<SceneStore.CompiledSource> cohort,boolean accepted,String failure,List<Outcome> outcomes) {
        if(accepted)installedCohorts++;else rejectedCohorts++;
        for(var source:cohort) {
            if(accepted && source.reusedGeometryBytes()>0) { reusedGeometryPublications++;reusedGeometryBytes=Math.addExact(reusedGeometryBytes,source.reusedGeometryBytes()); }
            if(!accepted)store.cancel(source.request());
            outcomes.add(new Outcome(source.request(),accepted,failure));
        }
    }
    public int pendingJobs() { requireOwner();return jobs; }
    public long pendingBytes() { requireOwner();return bytes; }
    int availableJobs() { requireOwner();return budget.jobs()-jobs; }
    long availableBytes() { requireOwner();return budget.bytes()-bytes; }
    public Statistics statistics() { requireOwner();return new Statistics(pending.size(),submittedCohorts,installedCohorts,rejectedCohorts,largestCohort,reusedGeometryPublications,reusedGeometryBytes); }
    public java.util.Set<SceneInputs.Key> resourceKeys() {
        requireOwner();var keys=new HashSet<SceneInputs.Key>();
        for(var work:pending)for(var member:work.members)for(var texture:member.prepared.textures())keys.add(texture.key());
        return java.util.Set.copyOf(keys);
    }
    private void requireOwner() { if(Thread.currentThread()!=owner)throw new IllegalStateException("Compiler scheduling requires its owner thread"); }
    @Override public void close() {
        requireOwner();if(closed)return;closed=true;
        for(var work:pending) { work.cancelled.set(true);for(var member:work.members)member.result.cancel(true); }
        pending.clear();bytes=0;jobs=0;workers.shutdownNow();
    }
}
