package dev.rt_render_experiment.engine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.TreeMap;
import dev.rt_render_experiment.contract.LightInputs;
import dev.rt_render_experiment.contract.HostExecution;
import dev.rt_render_experiment.contract.SceneInputs;
import dev.rt_render_experiment.contract.TextureInputs;


public final class FrameScene {
    private static final SceneInputs.Key NEUTRAL=new SceneInputs.Key(0,0);
    private final SceneStore.Revision revision;
    private final SceneStore.Revision renderRevision;
    private final List<TextureInputs.GpuTexture> imports;
    private final DynamicSceneCompiler.Prepared dynamic;
    private final boolean viewmodel;
    private final dev.rt_render_experiment.contract.MediumInputs.Domain mediumDomain;
    private final boolean modelVolumes;
    private final boolean fluidVolumes;







    public FrameScene(long serial,SceneStore.Revision resident,DynamicSceneCompiler.Prepared dynamic,
                      List<? extends TextureInputs.Resource> frameTextures,LightInputs.Publication frameLights) {
        if(serial<=0 || resident.world()<=0)throw new IllegalArgumentException("Invalid frame scene identity");
        this.dynamic=dynamic;mediumDomain=null;modelVolumes=false;fluidVolumes=false;
        var geometry=new TreeMap<SceneInputs.Key,SceneCompiler.Compiled>();
        for(var compiled:resident.geometry())addGeometry(geometry,compiled,resident.world());
        if(dynamic!=null) {
            if(dynamic.world()!=resident.world())throw new IllegalArgumentException("Dynamic publication belongs to another world");
            for(var compiled:dynamic.geometry())addGeometry(geometry,compiled,resident.world());
        }
        viewmodel=geometry.values().stream().anyMatch(compiled->(compiled.input().participation().rayMask()&dev.rt_render_experiment.contract.Participation.VIEWMODEL)!=0);
        var textures=new TreeMap<SceneInputs.Key,TextureInputs.Resource>();
        for(var texture:resident.textures())addTexture(textures,texture);
        for(var texture:frameTextures)addTexture(textures,texture);
        long bytes=0;var renderKeys=new HashSet<SceneInputs.Key>();


        for(var texture:frameTextures)renderKeys.add(texture.key());
        for(var compiled:geometry.values()) {
            bytes=Math.addExact(bytes,compiled.bytes());
            for(var key:compiled.requiredResourceKeys())requireTexture(textures,key);
            renderKeys.addAll(compiled.renderResourceKeys());
        }
        var lights=new TreeMap<SceneInputs.Key,LightInputs.Source>();
        var claimed=new HashSet<LightCompiler.Association>();
        var sources=new ArrayList<LightInputs.Source>(resident.lights());sources.addAll(frameLights.sources());
        for(var source:sources) {
            if(lights.putIfAbsent(source.key(),source)!=null)throw new IllegalArgumentException("Duplicate frame source identity");
            if(source instanceof LightInputs.Area area) {
                var association=new LightCompiler.Association(area.geometry(),area.part(),area.ordinal());
                var mesh=geometry.get(area.geometry());

                if(mesh==null || mesh.findPart(area.part(),area.ordinal()).isEmpty() || !claimed.add(association))
                    throw new IllegalArgumentException("Area source lacks an exclusive, exact frame primitive");
                if((geometry.get(area.geometry()).input().participation().rayMask()&dev.rt_render_experiment.contract.Participation.VIEWMODEL)!=0)
                    throw new IllegalArgumentException("A viewmodel cannot publish an area source into world lighting");
            }
        }
        var cpu=new ArrayList<TextureInputs.Texture>();var gpu=new ArrayList<TextureInputs.GpuTexture>();
        for(var texture:textures.values())switch(texture) {
            case TextureInputs.Texture pixels -> cpu.add(pixels);
            case TextureInputs.GpuTexture grant -> gpu.add(grant);
        }
        revision=new SceneStore.Revision(serial,resident.world(),List.copyOf(geometry.values()),bytes,cpu,List.copyOf(lights.values()));


        renderRevision=new SceneStore.Revision(serial,resident.world(),revision.geometry(),bytes,
            cpu.stream().filter(texture->renderKeys.contains(texture.key())).toList(),revision.lights());
        imports=List.copyOf(gpu);
    }
    private FrameScene(FrameScene source,dev.rt_render_experiment.contract.MediumInputs.Domain domain) {
        revision=source.revision;renderRevision=source.renderRevision;imports=source.imports;dynamic=source.dynamic;viewmodel=source.viewmodel;
        mediumDomain=java.util.Objects.requireNonNull(domain);
        modelVolumes=false;fluidVolumes=false;
    }
    public FrameScene withMediumDomain(dev.rt_render_experiment.contract.MediumInputs.Domain domain) {
        if(modelVolumes)throw new IllegalStateException("Two medium publication owners requested");return new FrameScene(this,domain);
    }
    private FrameScene(FrameScene source,boolean fluidVolumes) {
        revision=source.revision;renderRevision=source.renderRevision;imports=source.imports;dynamic=source.dynamic;viewmodel=source.viewmodel;
        mediumDomain=null;modelVolumes=true;this.fluidVolumes=fluidVolumes;
    }





    public FrameScene withModelVolumes() {
        if(mediumDomain!=null)throw new IllegalStateException("Two medium publication owners requested");return new FrameScene(this,false);
    }





    public FrameScene withPreparedVolumes() {
        if(mediumDomain!=null)throw new IllegalStateException("Two medium publication owners requested");return new FrameScene(this,true);
    }
    public boolean modelVolumesRequested() { return modelVolumes; }
    public boolean fluidVolumesRequested() { return fluidVolumes; }
    private FrameScene(FrameScene source,java.util.Map<SceneCompiler.Compiled,SceneCompiler.Compiled> geometry,dev.rt_render_experiment.contract.MediumInputs.Domain domain) {
        var meshes=source.revision.geometry().stream().map(g->geometry.getOrDefault(g,g)).toList();
        long extra=0;for(var entry:geometry.entrySet())extra=Math.addExact(extra,entry.getValue().bytes()-entry.getKey().bytes());
        revision=new SceneStore.Revision(source.revision.serial(),source.revision.world(),meshes,Math.addExact(source.revision.bytes(),extra),source.revision.textures(),source.revision.lights());
        renderRevision=new SceneStore.Revision(revision.serial(),revision.world(),meshes,Math.addExact(source.renderRevision.bytes(),extra),source.renderRevision.textures(),source.renderRevision.lights());
        imports=source.imports;dynamic=source.dynamic;viewmodel=source.viewmodel;modelVolumes=false;fluidVolumes=false;mediumDomain=domain;
    }
    FrameScene withCompiledVolumes(java.util.Map<SceneCompiler.Compiled,SceneCompiler.Compiled> geometry,dev.rt_render_experiment.contract.MediumInputs.Domain domain) {
        return new FrameScene(this,geometry,domain);
    }
    public java.util.Optional<dev.rt_render_experiment.contract.MediumInputs.Domain> mediumDomain() { return java.util.Optional.ofNullable(mediumDomain); }
    private static void addGeometry(TreeMap<SceneInputs.Key,SceneCompiler.Compiled> geometry,SceneCompiler.Compiled compiled,long world) {
        if(compiled.input().revision().world()!=world || geometry.putIfAbsent(compiled.input().key(),compiled)!=null)
            throw new IllegalArgumentException("Duplicate or foreign-world frame geometry");
    }
    private static void addTexture(TreeMap<SceneInputs.Key,TextureInputs.Resource> textures,TextureInputs.Resource texture) {
        if(texture.key().equals(NEUTRAL))throw new IllegalArgumentException("The neutral texture is renderer-owned");
        var before=textures.putIfAbsent(texture.key(),texture);
        if(before==null || before==texture)return;
        boolean same=before instanceof TextureInputs.Texture a && texture instanceof TextureInputs.Texture b
            ? a.revision()==b.revision() && TextureCompiler.sameContent(a,b) : before.equals(texture);
        if(!same)throw new IllegalArgumentException("Conflicting frame resource content or read grant: "+texture.key());
    }
    private static void requireTexture(TreeMap<SceneInputs.Key,TextureInputs.Resource> textures,SceneInputs.Key key) {
        if(!key.equals(NEUTRAL) && !textures.containsKey(key))throw new IllegalArgumentException("Missing frame surface resource: "+key);
    }
    public SceneStore.Revision revision() { return revision; }
    SceneStore.Revision renderRevision(RenderFrame.Environment environment) {
        var key=environment.cloud().resource();
        if(!environment.cloudsVisible() || renderRevision.textures().stream().anyMatch(texture->texture.key().equals(key)))return renderRevision;
        var field=revision.textures().stream().filter(texture->texture.key().equals(key)).findFirst();
        if(field.isEmpty())return renderRevision;
        var textures=new ArrayList<>(renderRevision.textures());textures.add(field.orElseThrow());
        return new SceneStore.Revision(revision.serial(),revision.world(),revision.geometry(),revision.bytes(),textures,revision.lights());
    }
    public List<TextureInputs.GpuTexture> imports() { return imports; }
    public DynamicSceneCompiler.Prepared dynamic() { return dynamic; }
    public boolean hasViewmodel() { return viewmodel; }
    void requireReadGrants(HostExecution.Window window) {
        var granted=new HashSet<>(window.images());
        for(var texture:imports)if(!granted.contains(texture.source()))
            throw new IllegalArgumentException("Frame texture has no exact read grant in this recording window");
    }
    void requireEnvironment(RenderFrame.Environment environment) {
        if(!environment.cloudsVisible())return;
        var key=environment.cloud().resource();
        TextureInputs.Resource field=revision.textures().stream().filter(texture->texture.key().equals(key)).findFirst().orElse(null);
        if(field==null)field=imports.stream().filter(texture->texture.key().equals(key)).findFirst().orElse(null);
        if(key.equals(NEUTRAL) || field==null)throw new IllegalArgumentException("Missing frame cloud resource");
        int width,height;
        switch(field) {
            case TextureInputs.Texture texture -> { width=texture.levels().getFirst().width();height=texture.levels().getFirst().height(); }
            case TextureInputs.GpuTexture texture -> { width=texture.source().view().width();height=texture.source().view().height(); }
        }
        if(width!=environment.cloud().width() || height!=environment.cloud().height())throw new IllegalArgumentException("Frame cloud extent disagrees with its resource");
    }
}
