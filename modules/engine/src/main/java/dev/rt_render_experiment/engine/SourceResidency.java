package dev.rt_render_experiment.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import dev.rt_render_experiment.contract.SceneInputs;


public final class SourceResidency {
    public record Bounds(SceneInputs.Origin minimum,SceneInputs.Origin maximum) {
        public Bounds {
            if(minimum.x()>maximum.x() || minimum.y()>maximum.y() || minimum.z()>maximum.z())throw new IllegalArgumentException("Inverted source bounds");
        }
        double distanceSquared(SceneInputs.Origin point) {
            double x=Math.max(minimum.x()-point.x(),Math.max(0,point.x()-maximum.x()));
            double y=Math.max(minimum.y()-point.y(),Math.max(0,point.y()-maximum.y()));
            double z=Math.max(minimum.z()-point.z(),Math.max(0,point.z()-maximum.z())); return x*x+y*y+z*z;
        }
    }
    public enum BoundsKind { COMPLETE, ESTIMATE }
    public record Source(SceneInputs.Key key,SceneInputs.Revision revision,Bounds bounds,long maximumBytes,BoundsKind boundsKind) {
        public Source(SceneInputs.Key key,SceneInputs.Revision revision,Bounds bounds,long maximumBytes) { this(key,revision,bounds,maximumBytes,BoundsKind.COMPLETE); }
        public Source { if(maximumBytes<=0)throw new IllegalArgumentException("Invalid source bound"); }
    }
    public record Demand(SceneInputs.Origin camera,double primaryRadius,double secondaryRadius) {
        public Demand {
            if(!Double.isFinite(primaryRadius)||!Double.isFinite(secondaryRadius)||primaryRadius<0||secondaryRadius<0)throw new IllegalArgumentException("Invalid scene demand");
        }
    }
    public record Readiness(int wanted,int current,int available,boolean coherent,int excludedPredecessors) {

        public boolean complete() { return coherent && wanted==current; }

        public boolean renderable() { return coherent && wanted==available; }
        public int updating() { return available-current; }
    }
    private final Thread owner=Thread.currentThread();
    private final Map<SceneInputs.Key,Source> sources=new HashMap<>();

    private final Map<SceneInputs.Key,SceneInputs.Revision> compiled=new HashMap<>();
    private final Map<SceneInputs.Key,SceneInputs.PreparationRequest> pending=new HashMap<>();
    private final Map<SceneInputs.Key,Long> lastPreparation=new HashMap<>();
    private final java.util.Set<SceneInputs.PreparationRequest> started=new java.util.HashSet<>();
    private final java.util.Set<SceneInputs.PreparationRequest> captured=new java.util.HashSet<>();
    private long preparationOrder,schedulingWindows;
    private record Resolved(SceneInputs.Revision revision,Bounds bounds) {}



    private final Map<SceneInputs.Key,Resolved> resolved=new HashMap<>();
    public Source source(SceneInputs.Key key) { requireOwner();return sources.get(key); }
    boolean demanded(SceneInputs.Key key,Demand demand,SceneStore.Publication publication) {
        requireOwner();var source=sources.get(key);return source!=null && wanted(source,demand,publication);
    }
    public void source(Source source) { requireOwner();sources.put(source.key(),source); }

    public boolean source(Source source,SceneStore store) {
        requireOwner();
        var previous=sources.put(source.key(),source);
        if(previous!=null && !previous.revision().equals(source.revision())) {



            var before=resolved.get(source.key());
            if(before!=null && (before.revision().world()!=source.revision().world() || before.revision().resources()!=source.revision().resources()))resolved.remove(source.key());
            var request=pending.get(source.key());
            if(request!=null && !(captured.contains(request) && request.expected().world()==source.revision().world()
                && request.expected().resources()==source.revision().resources())) {
                pending.remove(source.key());started.remove(request);captured.remove(request);store.cancel(request);return true;
            }
        }
        return false;
    }
    public void remove(SceneInputs.Key key,SceneStore store) {
        requireOwner();sources.remove(key);compiled.remove(key);lastPreparation.remove(key);
        var request=pending.remove(key);if(request!=null) { started.remove(request);captured.remove(request); }resolved.remove(key);store.remove(key);
    }
    void cancelPreparation(SceneInputs.Key key,SceneStore store) {
        requireOwner();var request=pending.remove(key);
        if(request!=null) { started.remove(request);captured.remove(request);store.cancel(request); }
    }
    void empty(SceneStore.CompiledSource source) {
        requireOwner();var request=source.request();compiled.put(request.source(),request.expected());
        resolved.put(request.source(),new Resolved(request.expected(),null));
    }

    public boolean preparing(SceneInputs.PreparationRequest request) {
        requireOwner();var source=sources.get(request.source());
        if(source==null || !source.revision().equals(request.expected()) || !request.equals(pending.get(request.source())))return false;
        if(started.add(request))lastPreparation.put(request.source(),preparationOrder=Math.incrementExact(preparationOrder));
        return true;
    }

    public void captured(SceneInputs.PreparationRequest request) {
        requireOwner();if(!request.equals(pending.get(request.source())))throw new IllegalStateException("Capture lacks its active source request");
        captured.add(request);
    }
    public boolean isCaptured(SceneInputs.PreparationRequest request) { requireOwner();return captured.contains(request); }
    public void resolved(SceneInputs.PreparationRequest request,Bounds bounds) {
        requireOwner();var source=sources.get(request.source());
        if(source!=null && source.revision().equals(request.expected()))resolved.put(source.key(),new Resolved(source.revision(),bounds));
    }
    private Bounds completeBounds(Source source) {
        if(source.boundsKind()==BoundsKind.COMPLETE)return source.bounds();
        var result=resolved.get(source.key());return result!=null && result.revision().equals(source.revision())?result.bounds():null;
    }
    private static boolean intersects(Bounds bounds,Demand demand) {
        double radius=Math.max(demand.primaryRadius(),demand.secondaryRadius());
        return bounds!=null && bounds.distanceSquared(demand.camera())<=radius*radius;
    }

    static boolean include(Bounds bounds,Demand demand) { return bounds==null || intersects(bounds,demand); }
    private boolean wanted(Source source,Demand demand,SceneStore.Publication publication) {
        var bounds=completeBounds(source);
        var resident=publication.sources().get(source.key());

        return bounds==null || intersects(bounds,demand) || resident!=null && intersects(resident.bounds(),demand);
    }
    private Resolved excludedPredecessor(Source source,Demand demand) {
        var before=resolved.get(source.key());var expected=source.revision();


        return before!=null && before.revision().world()==expected.world() && before.revision().resources()==expected.resources()
            && !intersects(before.bounds(),demand)?before:null;
    }

    Readiness readiness(Demand demand,SceneStore.Publication publication) {
        requireOwner();int wanted=0,current=0,available=0,excluded=0;
        for(var source:sources.values())if(wanted(source,demand,publication)) {
            wanted++;
            var resident=publication.sources().get(source.key());var expected=source.revision();
            var previous=resident==null?null:resident.request().expected();
            if(previous!=null && previous.world()==expected.world() && previous.resources()==expected.resources()) {
                available++;if(expected.equals(previous))current++;
                if(!include(resident.bounds(),demand))excluded++;
            } else if(resident==null) {
                var exclusion=excludedPredecessor(source,demand);
                if(exclusion!=null) { available++;excluded++;if(expected.equals(exclusion.revision()))current++; }
            }
        }
        return new Readiness(wanted,current,available,publication.scene().world()>0,excluded);
    }
    public List<SceneInputs.PreparationRequest> requests(SceneStore store,Demand demand,int maximumJobs,long maximumBytes) {
        requireOwner();
        if(maximumJobs<=0 || maximumBytes<=0)throw new IllegalArgumentException("Invalid source scheduling window");
        var priority=Comparator.<Source>comparingInt(source->source.bounds().distanceSquared(demand.camera())<=demand.primaryRadius()*demand.primaryRadius()?0:1)
            .thenComparingDouble(source->source.bounds().distanceSquared(demand.camera())).thenComparing(Source::key);
        var oldest=Comparator.<Source>comparingLong(source->lastPreparation.getOrDefault(source.key(),0L)).thenComparing(priority);


        var wanted=new ArrayList<>(sources.values().stream().filter(source->store.related(source.key()) || wanted(source,demand,store.publication()))
            .filter(source->!store.locked(source.key()))
            .filter(source->!source.revision().equals(compiled.get(source.key())))
            .filter(source->!pending.containsKey(source.key())
                || !captured.contains(pending.get(source.key())) && !pending.get(source.key()).expected().equals(source.revision()))
            .sorted(priority).toList());


        boolean fair=(schedulingWindows++ & 1L)!=0;
        long bytes=0; var result=new ArrayList<SceneInputs.PreparationRequest>();
        while(result.size()<maximumJobs) {
            int selected=-1;
            for(int i=0;i<wanted.size();i++) {
                var candidate=wanted.get(i);if(candidate.maximumBytes()>maximumBytes-bytes)continue;
                if(selected<0 || fair && oldest.compare(candidate,wanted.get(selected))<0)selected=i;
                if(!fair)break;
            }
            if(selected<0)break;
            var source=wanted.remove(selected);
            SceneInputs.PreparationRequest request;
            try { request=store.request(source.key(),source.revision(),source.maximumBytes()); }
            catch(IllegalStateException pressure) { break; }
            var previous=pending.put(source.key(),request);if(previous!=null) { started.remove(previous);captured.remove(previous); }
            result.add(request);bytes+=source.maximumBytes();fair=!fair;
        }
        return List.copyOf(result);
    }

    public List<SceneInputs.Key> evictOutside(SceneStore store,Demand demand) {
        requireOwner();
        var removed=new ArrayList<SceneInputs.Key>();
        for(var source:sources.values())if(!store.related(source.key()) && !wanted(source,demand,store.publication())
            && (compiled.containsKey(source.key()) || pending.containsKey(source.key()))) {
            compiled.remove(source.key());var request=pending.remove(source.key());if(request!=null) { started.remove(request);captured.remove(request); }
            store.remove(source.key());removed.add(source.key());
        }
        return List.copyOf(removed);
    }
    public void completed(CompilationQueue.Outcome outcome) {
        requireOwner();
        var current=pending.get(outcome.request().source());
        if(!outcome.request().equals(current))return;
        preparing(outcome.request());
        pending.remove(outcome.request().source());
        started.remove(outcome.request());
        captured.remove(outcome.request());
        if(outcome.published())compiled.put(outcome.request().source(),outcome.request().expected());
    }
    public void rejected(SceneInputs.PreparationRequest request) { requireOwner();pending.remove(request.source(),request);started.remove(request);captured.remove(request); }
    public void clear() { requireOwner();sources.clear();compiled.clear();pending.clear();resolved.clear();lastPreparation.clear();started.clear();captured.clear();preparationOrder=0;schedulingWindows=0; }
    private void requireOwner() { if(Thread.currentThread()!=owner)throw new IllegalStateException("Source demand requires its owner thread"); }
    public int sourceCount() { requireOwner();return sources.size(); }
    int extentCount() { requireOwner();return resolved.size(); }
    long extentFactBytes() {
        requireOwner();long bytes=0;
        for(var value:resolved.values())bytes+=7L*Long.BYTES+(value.bounds()==null?0:6L*Double.BYTES);
        return bytes;
    }
}
