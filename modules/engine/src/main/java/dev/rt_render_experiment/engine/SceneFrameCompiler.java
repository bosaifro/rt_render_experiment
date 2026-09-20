package dev.rt_render_experiment.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import dev.rt_render_experiment.contract.LightInputs;
import dev.rt_render_experiment.contract.SceneInputs;
import dev.rt_render_experiment.contract.TextureInputs;


public final class SceneFrameCompiler {
    public record Work(int declaredSources,int withdrawnParts,int compiledSources,int reusedSources,int removedLights,long derivativeBytes) {
        public static final Work NONE=new Work(0,0,0,0,0,0);
    }
    public record Statistics(long preparations,long compiledSources,long reusedSources,long withdrawnParts,long removedLights,long peakDerivativeBytes) {}
    private record View(SceneCompiler.Compiled source,Set<SceneInputs.Key> removed,SceneCompiler.Compiled result) {}
    private final Thread owner=Thread.currentThread();
    private final long maximumBytes;
    private final SceneCompiler compiler=new SceneCompiler();
    private Map<SceneInputs.Key,View> cached=Map.of();
    private Map<SceneInputs.Key,SceneCompiler.Compiled> patchCache=Map.of();
    public record PatchWork(int sources,int compiled,int reused,long bytes) { public static final PatchWork NONE=new PatchWork(0,0,0,0); }
    private PatchWork patchWork=PatchWork.NONE;
    private long world;
    private Work work=Work.NONE;
    private long preparations,compiledSources,reusedSources,withdrawnParts,removedSources,peakBytes;
    public SceneFrameCompiler(long maximumBytes) {
        if(maximumBytes<=0)throw new IllegalArgumentException("Missing frame derivative byte grant");this.maximumBytes=maximumBytes;
    }
    public Work work() { requireOwner();return work; }
    public PatchWork patchWork() { requireOwner();return patchWork; }
    public Statistics statistics() { requireOwner();return new Statistics(preparations,compiledSources,reusedSources,withdrawnParts,removedSources,peakBytes); }
    public FrameScene prepare(long serial,SceneStore.Revision resident,DynamicSceneCompiler.Prepared dynamic,
                              List<? extends TextureInputs.Resource> textures,LightInputs.Publication lights,List<SceneInputs.SourceAbsence> absence) {
        return prepare(serial,resident,dynamic,textures,lights,absence,List.of());
    }
    public FrameScene prepare(long serial,SceneStore.Revision resident,DynamicSceneCompiler.Prepared dynamic,
                              List<? extends TextureInputs.Resource> textures,LightInputs.Publication lights,List<SceneInputs.SourceAbsence> absence,List<SceneInputs.SourcePatch> patches) {
        requireOwner();
        if(resident.world()<world)throw new IllegalArgumentException("Stale frame-source world");
        var removed=new HashMap<SceneInputs.Key,Set<SceneInputs.Key>>();var points=new HashSet<SceneInputs.Key>();
        for(var fact:absence) {
            if(fact.world()!=resident.world() || fact.frame()!=serial)throw new IllegalArgumentException("Source absence belongs to another world or frame");
            removed.computeIfAbsent(fact.source(),k->new HashSet<>()).addAll(fact.parts());points.addAll(fact.pointSources());
        }
        var patched=new HashMap<SceneInputs.Key,Set<SceneInputs.Key>>();
        for(var patch:patches) {
            if(patch.world()!=resident.world() || patch.frame()!=serial)throw new IllegalArgumentException("Source patch belongs to another world or frame");
            var claims=patched.computeIfAbsent(patch.source(),k->new HashSet<>());
            for(var part:patch.parts())if(!claims.add(part))throw new IllegalArgumentException("Two patches replace one source part");
            removed.computeIfAbsent(patch.source(),k->new HashSet<>()).addAll(patch.parts());points.addAll(patch.pointSources());
        }
        var next=new HashMap<SceneInputs.Key,View>();var geometry=new ArrayList<SceneCompiler.Compiled>();
        long derivatives=0,bytes=0,reservedBytes=0;int compiled=0,reused=0,parts=0;
        for(var source:resident.geometry()) {
            var requested=removed.getOrDefault(source.input().key(),Set.of());var effective=new HashSet<SceneInputs.Key>();
            if(!requested.isEmpty()) {
                for(var p:source.input().primitives())if(requested.contains(p.part()))effective.add(p.part());
                for(var c:source.input().fluids())if(requested.contains(c.part()))effective.add(c.part());
                for(var m:source.input().opticalModels())if(requested.contains(m.part()))effective.add(m.part());
            }
            var value=source;
            if(!effective.isEmpty()) {
                parts=Math.addExact(parts,effective.size());var before=world==resident.world()?cached.get(source.input().key()):null;
                if(before!=null && before.source==source && before.removed.equals(effective)) {
                    value=before.result;reused++;
                    reservedBytes=Math.addExact(reservedBytes,Math.addExact(value.bytes(),4L*value.parts().size()-value.primitiveIndexBytes()));
                    if(reservedBytes>maximumBytes)throw new IllegalStateException("Frame source-withdrawal byte pressure");
                }
                else {


                    long bound=Math.addExact(source.bytes(),4L*source.parts().size()-source.primitiveIndexBytes());
                    reservedBytes=Math.addExact(reservedBytes,bound);
                    if(reservedBytes>maximumBytes)throw new IllegalStateException("Frame source-withdrawal byte pressure");
                    value=compiler.withoutParts(source,effective);compiled++;
                }
                derivatives=Math.addExact(derivatives,value.bytes());
                if(derivatives>maximumBytes)throw new IllegalStateException("Frame source-withdrawal byte pressure");
                next.put(source.input().key(),new View(source,Set.copyOf(effective),value));
            }
            geometry.add(value);bytes=Math.addExact(bytes,value.bytes());
        }
        var sources=new ArrayList<LightInputs.Source>();int removedLights=0;
        for(var source:resident.lights()) {
            boolean withdrawn=source instanceof LightInputs.Area a && removed.getOrDefault(a.geometry(),Set.of()).contains(a.part());
            if(points.contains(source.key())) {
                if(!(source instanceof LightInputs.Point))throw new IllegalArgumentException("Point withdrawal names an area source");
                withdrawn=true;
            }
            if(withdrawn)removedLights++;else sources.add(source);
        }


        for(var source:sources)if(source instanceof LightInputs.Area area) {
            var selected=next.get(area.geometry());if(selected!=null)selected.result.findPart(area.part(),area.ordinal());
        }
        var nextPatches=new HashMap<SceneInputs.Key,SceneCompiler.Compiled>();var frameTextures=new ArrayList<TextureInputs.Resource>(textures);
        long patchBytes=0;int patchCompiled=0,patchReused=0;
        for(var patch:patches) {
            var input=patch.replacement().content().geometry().getFirst();var before=world==resident.world()?patchCache.get(input.key()):null;
            var compiledPatch=compiler.canReuse(before,input)?before:null;
            long bound=0;for(var p:input.primitives())bound=Math.addExact(bound,SceneCompiler.primitiveBytes(p.corners().size()));
            for(var cell:input.fluids())bound=Math.addExact(bound,6*SceneCompiler.primitiveBytes(4)+cell.occlusionBytes());
            for(var model:input.opticalModels())bound=Math.addExact(bound,ModelBoundary.maximumBytes(model));

            bound=Math.addExact(bound,4L*(input.primitives().size()+6L*input.fluids().size()));
            reservedBytes=Math.addExact(reservedBytes,bound);
            if(reservedBytes>maximumBytes)throw new IllegalStateException("Frame source-patch byte pressure");
            if(compiledPatch==null) { compiledPatch=compiler.compile(input);patchCompiled++; } else patchReused++;
            for(var light:patch.replacement().lights())if(light instanceof LightInputs.Area area)compiledPatch.findPart(area.part(),area.ordinal());
            if(nextPatches.put(input.key(),compiledPatch)!=null)throw new IllegalArgumentException("Duplicate patch source identity");
            geometry.add(compiledPatch);patchBytes=Math.addExact(patchBytes,compiledPatch.bytes());
            frameTextures.addAll(patch.replacement().textures());sources.addAll(patch.replacement().lights());
        }
        derivatives=next.values().stream().mapToLong(v->v.result.bytes()).sum();
        if(derivatives>maximumBytes)throw new IllegalStateException("Frame source-withdrawal byte pressure");
        bytes=geometry.stream().mapToLong(SceneCompiler.Compiled::bytes).sum();
        var view=next.isEmpty() && patches.isEmpty() && removedLights==0?resident:new SceneStore.Revision(resident.serial(),resident.world(),geometry,bytes,resident.textures(),sources);
        var result=new FrameScene(serial,view,dynamic,frameTextures,lights);


        cached=Map.copyOf(next);world=resident.world();work=new Work(removed.size(),parts,compiled,reused,removedLights,derivatives);
        patchCache=Map.copyOf(nextPatches);patchWork=new PatchWork(patches.size(),patchCompiled,patchReused,patchBytes);
        preparations++;compiledSources+=compiled;reusedSources+=reused;withdrawnParts+=parts;removedSources+=removedLights;peakBytes=Math.max(peakBytes,derivatives);
        return result;
    }
    public void clear() { requireOwner();cached=Map.of();patchCache=Map.of();work=Work.NONE;patchWork=PatchWork.NONE; }
    private void requireOwner() { if(Thread.currentThread()!=owner)throw new IllegalStateException("Frame source compilation requires its owner thread"); }
}
