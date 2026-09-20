package dev.rt_render_experiment.engine;

import dev.rt_render_experiment.engine.abi.R2Abi;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import dev.rt_render_experiment.contract.SceneInputs;
import dev.rt_render_experiment.contract.TriangleGeometry;
import dev.rt_render_experiment.contract.TextureInputs;
import dev.rt_render_experiment.contract.LightInputs;
import dev.rt_render_experiment.contract.MaterialInputs;
import dev.rt_render_experiment.contract.HostExecution;
import dev.rt_render_experiment.vulkan.VulkanAccelerationStructures;
import dev.rt_render_experiment.vulkan.VulkanBarriers;
import dev.rt_render_experiment.vulkan.VulkanResources;
import dev.rt_render_experiment.vulkan.VulkanUploads;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkCommandBuffer;


public final class GpuScene implements AutoCloseable {
    private static final int ADDRESSABLE=VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT | VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    private static final int BUILD_INPUT=ADDRESSABLE | KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
    private final VulkanResources resources;
    private final VulkanUploads uploads;
    private final VulkanAccelerationStructures acceleration;
    private final TextureImporter textureImporter;
    private final DefinitionBank materialCompiler,atmosphereCompiler;
    private final boolean samplerAnisotropy;
    private final LightCompiler lightCompiler;
    private final String shaderIdentity;
    public record CompactionBudget(int queries,int copies,long copyBytes,long minimumSourceBytes,long minimumSavingBytes) {
        public CompactionBudget {
            if(queries<0 || queries>256 || copies<0 || copyBytes<0 || minimumSourceBytes<0 || minimumSavingBytes<0)
                throw new IllegalArgumentException("Invalid static compaction budget");
        }
        public static CompactionBudget standard() { return new CompactionBudget(64,8,16L<<20,16L<<10,1024); }
        public static CompactionBudget disabled() { return new CompactionBudget(0,0,0,0,0); }
    }
    private final CompactionBudget compactionBudget;

    public record UpdatePolicy(int maximumChainLength) {
        public UpdatePolicy { if(maximumChainLength<0 || maximumChainLength>1024)throw new IllegalArgumentException("Invalid BLAS update-chain limit"); }
        public static UpdatePolicy standard() { return new UpdatePolicy(32); }
        public static UpdatePolicy rebuildOnly() { return new UpdatePolicy(0); }
    }
    private final UpdatePolicy updatePolicy;
    private record SurfaceContent(String prepared,String shading,long color,long coverage,long emission,long overlay,dev.rt_render_experiment.contract.MediumInputs.Definition medium) {}
    private record SurfaceIdentity(SceneInputs.Key object,SceneInputs.Key part,long ordinal,long topology,long appearance,long resources,long coverage,long world,
                                   SurfaceContent content) {}
    private int nextSurfaceIdentity=1;
    private long preparation;
    private Snapshot current;

    public GpuScene(VulkanResources resources,ShaderModules shaders) {
        this(resources,shaders,null);
    }
    public GpuScene(VulkanResources resources,ShaderModules shaders,HostExecution.Capabilities capabilities) {
        this(resources,shaders,capabilities,CompactionBudget.standard());
    }
    public GpuScene(VulkanResources resources,ShaderModules shaders,HostExecution.Capabilities capabilities,CompactionBudget compactionBudget) {
        this(resources,shaders,capabilities,compactionBudget,UpdatePolicy.standard());
    }
    public GpuScene(VulkanResources resources,ShaderModules shaders,HostExecution.Capabilities capabilities,CompactionBudget compactionBudget,UpdatePolicy updatePolicy) {
        this(resources,shaders,capabilities,compactionBudget,updatePolicy,new LightCompiler());
    }
    GpuScene(VulkanResources resources,ShaderModules shaders,HostExecution.Capabilities capabilities,CompactionBudget compactionBudget,UpdatePolicy updatePolicy,LightCompiler lightCompiler) {
        if(capabilities!=null && capabilities.device()!=resources.deviceIdentity())throw new IllegalArgumentException("Foreign scene device grant");
        this.samplerAnisotropy=capabilities!=null && capabilities.enabled().contains(HostExecution.Capability.SAMPLER_ANISOTROPY);
        this.resources=resources; this.uploads=new VulkanUploads(resources); this.acceleration=new VulkanAccelerationStructures(resources);
        this.shaderIdentity=shaders.executionIdentity();
        this.compactionBudget=java.util.Objects.requireNonNull(compactionBudget);
        this.updatePolicy=java.util.Objects.requireNonNull(updatePolicy);
        this.lightCompiler=java.util.Objects.requireNonNull(lightCompiler);
        this.materialCompiler=new DefinitionBank(resources,shaders,DefinitionBank.Kind.MATERIALS);
        this.atmosphereCompiler=new DefinitionBank(resources,shaders,DefinitionBank.Kind.ATMOSPHERE);
        try { this.textureImporter=new TextureImporter(resources,shaders); }
        catch(RuntimeException | Error failure) { acceleration.close();uploads.close();throw failure; }
    }
    public Snapshot current() { return current; }
    public Prepared prepare(SceneStore.Revision revision, SceneInputs.Origin origin) {
        return prepare(revision,origin,List.of());
    }
    public Prepared prepare(SceneStore.Revision revision, SceneInputs.Origin origin,List<? extends TextureInputs.Resource> textures) {
        return prepare(revision,origin,textures,LightInputs.Publication.empty());
    }
    public Prepared prepare(SceneStore.Revision revision, SceneInputs.Origin origin,List<? extends TextureInputs.Resource> textures,LightInputs.Publication lights) {
        return prepare(revision,origin,textures,lights,HostExecution.DepthSources.none());
    }
    public Prepared prepare(SceneStore.Revision revision,SceneInputs.Origin origin,List<? extends TextureInputs.Resource> textures,
                            LightInputs.Publication lights,HostExecution.DepthSources depthSources) {
        return prepare(revision,origin,textures,lights,depthSources,null);
    }
    public Prepared prepare(SceneStore.Revision revision,SceneInputs.Origin origin,List<? extends TextureInputs.Resource> textures,
                            LightInputs.Publication lights,HostExecution.DepthSources depthSources,dev.rt_render_experiment.contract.MediumInputs.Domain mediumDomain) {
        if(mediumDomain==null && revision.geometry().stream().anyMatch(g->!g.clipContacts().isEmpty()))
            throw new IllegalArgumentException("Clipped boundary geometry requires its coherent medium domain");
        ClipGeometry.requireSources(revision.geometry());
        var keys=new java.util.HashSet<SceneInputs.Key>();
        for(var compiled:revision.geometry())if(!keys.add(compiled.input().key()) || compiled.input().revision().world()!=revision.world())
            throw new IllegalArgumentException("Duplicate or foreign-world scene geometry");
        var eligible=new java.util.HashSet<SceneInputs.Key>();
        for(var compiled:revision.geometry())if(compiled.triangleCount()>0 && compiled.input().participation().primaryVisible()
            && (compiled.input().participation().rayMask()&dev.rt_render_experiment.contract.Participation.CAMERA_PATH)!=0)eligible.add(compiled.input().key());
        if(!eligible.containsAll(depthSources.geometry()))throw new IllegalArgumentException("Source depth declares absent or non-primary geometry");
        var media=MediumDefinitions.prepare(mediumDomain,revision.geometry(),current==null?null:current.compiledMedia);
        preparation=Math.incrementExact(preparation);uploads.beginFrame(); acceleration.beginBatch();
        Prepared pending=new Prepared(revision.serial(),origin);
        pending.snapshot.world=revision.world();
        try {
            pending.snapshot.compiledMedia=media;
            if(media!=null && !media.domain.volumes().isEmpty())pending.snapshot.mediumDefinitions=pending.mediumData(media.bytes(),current==null?null:current.mediumDefinitions);
            pending.snapshot.materials=materialCompiler.prepare(current==null?null:current.materials);
            pending.snapshot.atmosphere=atmosphereCompiler.prepare(current==null?null:current.atmosphere);
            var allTextures=new ArrayList<TextureInputs.Resource>();
            allTextures.add(SceneTextures.NEUTRAL);
            allTextures.addAll(revision.textures());
            allTextures.addAll(textures);
            pending.snapshot.textures=current!=null && current.textures.matches(allTextures) ? current.textures.retain()
                : new SceneTextures(resources,allTextures,current==null?null:current.textures,samplerAnisotropy);
            List<SceneCompiler.Compiled> geometry=revision.geometry().stream()
                .filter(g->g.triangleCount()>0).sorted(java.util.Comparator.comparing(g->g.input().key())).toList();
            var allLights=new ArrayList<>(revision.lights());allLights.addAll(lights.sources());
            pending.snapshot.worldLightDomain=new WorldLightDomain(revision.world(),geometry,allLights,allTextures,mediumDomain);
            var priorLights=current==null?null:current.compiledLights;
            var lightPreparation=lightCompiler.prepare(revision.world(),geometry,allLights,origin,priorLights);
            var compiledLights=lightPreparation.compiled();pending.lightWork=lightPreparation.work();pending.snapshot.compiledLights=compiledLights;
            pending.snapshot.sourceRecords=pending.sourceData(compiledLights.sources(),current==null?null:current.sourceRecords,compiledLights.sharesSources(priorLights));
            pending.snapshot.lightCells=pending.sourceData(compiledLights.cells(),current==null?null:current.lightCells,compiledLights.sharesCells(priorLights));
            var sourceHeader=bytes(R2Abi.SourceTableRecord.SIZE);
            R2Abi.SourceTableRecord.sources(sourceHeader,0,pending.snapshot.sourceRecords.buffer.view().address());
            R2Abi.SourceTableRecord.cells(sourceHeader,0,pending.snapshot.lightCells.buffer.view().address());
            R2Abi.SourceTableRecord.counts(sourceHeader,0,compiledLights.worldCount(),compiledLights.dynamicCount(),compiledLights.attachedCount(),compiledLights.cellCount());
            pending.snapshot.sourceHeader=pending.sourceData(sourceHeader,current==null?null:current.sourceHeader);
            ByteBuffer records=bytes(Math.max(1,geometry.size())*R2Abi.GeometryRecord.SIZE);
            ByteBuffer instances=bytes(Math.max(1,geometry.size())*64);
            var changedAssociations=changedAssociations(priorLights,compiledLights);
            long mediumAddress=pending.snapshot.mediumDefinitions==null?0:pending.snapshot.mediumDefinitions.buffer.view().address();
            int slot=0;
            for (var compiled:geometry) {
                var input=compiled.input();
                boolean sameAssociations=priorLights!=null && !changedAssociations.contains(input.key());
                var sourceIndices=compiled.indices();
                var before=current==null?null:current.attributes.get(input.key());
                var attributes=new Attributes();pending.snapshot.attributes.put(input.key(),attributes);
                boolean opaque;
                if(before!=null && sameAssociations && before.matches(compiled,pending.snapshot.textures,media,mediumAddress)) {
                    attributes.reuse(before);opaque=attributes.opaque;
                    pending.snapshot.filterPrimitives=Math.addExact(pending.snapshot.filterPrimitives,attributes.filters);
                    pending.reusedRecordBytes=Math.addExact(pending.reusedRecordBytes,attributes.bytes());
                    pending.reusedAttributeMeshes++;
                } else if(before!=null && sameAssociations && before.matchesInputs(compiled,media,mediumAddress)) {
                    refreshTextures(pending,attributes,before,compiled,pending.snapshot.textures);
                    opaque=attributes.opaque;
                    pending.snapshot.filterPrimitives=Math.addExact(pending.snapshot.filterPrimitives,attributes.filters);
                } else {
                int priorFilters=pending.snapshot.filterPrimitives;


                boolean sameGeometry=before!=null && before.matchesGeometry(compiled);
                boolean sameTriangles=sameGeometry && sameAssociations;
                if(sameGeometry) {
                    attributes.corners=before.corners.retain();pending.reusedRecordBytes+=before.corners.bytes();
                } else attributes.corners=pending.data(compiled.traceCorners(),before==null?null:before.corners);
                ByteBuffer triangles=sameTriangles?null:bytes(compiled.traceTriangleCount()*R2Abi.TriangleRecord.SIZE);
                ByteBuffer surfaces=bytes((compiled.parts().size()+compiled.clipContacts().size())*R2Abi.SurfaceRecord.SIZE);
                int partSlot=0;
                attributes.identities=new SurfaceIdentity[compiled.parts().size()];attributes.tokens=new int[attributes.identities.length];
                if(sameGeometry)attributes.resourceParts=before.resourceParts;
                opaque=true;
                for (var part:compiled.parts()) {
                    var s=part.surface();
                    if (s.coverage()==SceneInputs.Coverage.FILTER) {
                        if(input.participation().primaryVisible() && (input.participation().rayMask()&1)!=0)
                            throw new IllegalArgumentException("A filter requires an explicit host-residual primary domain");
                        pending.snapshot.filterPrimitives=Math.addExact(pending.snapshot.filterPrimitives,part.indices()/3);
                    }
                    int base=partSlot*R2Abi.SurfaceRecord.SIZE;
                    var layers=s.layers();
                    int flags=(s.doubleSided()?1:0)|(layers.modelResponse()?2:0)|(layers.extendedRayClearance()?4:0);
                    R2Abi.SurfaceRecord.definition(surfaces,base,s.material(),s.properties(),s.coverage().ordinal(),flags);
                    R2Abi.SurfaceRecord.coverage(surfaces,base,s.cutoff(),0,0,0);
                    int emission=layers.emission()==MaterialInputs.Emission.NONE?-1:pending.snapshot.textures.slot(s.emissionResource());
                    int overlay=layers.overlay().equals(new SceneInputs.Key(0,0))?-1:pending.snapshot.textures.slot(layers.overlay());
                    int encodings=(pending.snapshot.textures.linear(s.colorResource())?1:0)
                        |(emission>=0 && pending.snapshot.textures.linear(s.emissionResource())?2:0)
                        |(overlay>=0 && pending.snapshot.textures.linear(layers.overlay())?4:0);







                    var identity=surfaceIdentity(compiled,part,pending.snapshot.textures,media);
                    Integer token=before==null?null:before.token(identity,partSlot,sameGeometry);
                    if(token==null)token=surfaceToken();
                    attributes.identities[partSlot]=identity;attributes.tokens[partSlot]=token;
                    R2Abi.SurfaceRecord.textures(surfaces,base,pending.snapshot.textures.slot(s.colorResource()),emission,
                        encodings,token);
                    R2Abi.SurfaceRecord.colorFactor(surfaces,base,1,1,1,1);
                    R2Abi.SurfaceRecord.emissionFactor(surfaces,base,layers.calibratedRadiance().x(),layers.calibratedRadiance().y(),layers.calibratedRadiance().z(),0);
                    R2Abi.SurfaceRecord.layers(surfaces,base,pending.snapshot.textures.slot(layers.coverage()),overlay,layers.emission().ordinal(),
                        (layers.coverageUsesTint()?R2Abi.LAYER_COVERAGE_TINT:0)
                            |(layers.sampling()==MaterialInputs.Sampling.TEXTURE_SAMPLER?R2Abi.LAYER_TEXTURE_SAMPLER:0)
                            |(layers.tintEncoding()==TextureInputs.Encoding.SRGB?R2Abi.LAYER_TINT_SRGB:0)
                            |(layers.emissionTintEncoding()==TextureInputs.Encoding.SRGB?R2Abi.LAYER_EMISSION_TINT_SRGB:0));
                    R2Abi.SurfaceRecord.medium(surfaces,base,s.medium());
                    if(media!=null && s.boundary()!=SceneInputs.Boundary.UNQUALIFIED)
                        R2Abi.SurfaceRecord.mediumDefinition(surfaces,base,Math.addExact(pending.snapshot.mediumDefinitions.buffer.view().address(),media.offset(s.medium())));
                    R2Abi.SurfaceRecord.boundary(surfaces,base,switch(s.boundary()) {
                        case NESTED_VOLUME -> R2Abi.ROLE_VOLUME;
                        case OPAQUE_CONTACT -> R2Abi.ROLE_CONTACT;
                        case UNQUALIFIED -> R2Abi.ROLE_NONE;
                    });
                    R2Abi.SurfaceRecord.revision(surfaces,base,input.revision().appearance());
                    R2Abi.SurfaceRecord.hostOcclusion(surfaces,base,s.hostOcclusion().ordinal());
                    opaque &= R2Abi.automaticHitCoverage(s.coverage().ordinal(),s.doubleSided());
                    if(!sameTriangles) {
                    var partIdentity=key(part.key());
                    var association=compiledLights.associations().get(new LightCompiler.Association(input.key(),part.key(),part.ordinal()));
                    for (int first=part.firstIndex();first<part.firstIndex()+part.indices();first+=3) {
                        int triangle=(first/3)*R2Abi.TriangleRecord.SIZE;
                        R2Abi.TriangleRecord.indicesSurface(triangles,triangle,sourceIndices.getInt(first*4),
                            sourceIndices.getInt((first+1)*4),sourceIndices.getInt((first+2)*4),partSlot);
                        R2Abi.TriangleRecord.partIdentity(triangles,triangle,partIdentity);
                        R2Abi.TriangleRecord.ordinalSource(triangles,triangle,(int)part.ordinal(),(int)(part.ordinal()>>>32),
                            association==null?0:association.token(),association==null?-1:association.slot());
                    }
                    }
                    partSlot++;
                }
                int clipTriangle=compiled.triangleCount();
                for(var contact:compiled.clipContacts()) {
                    if(media==null)throw new IllegalArgumentException("Trace-only boundary has no medium publication");
                    int base=partSlot*R2Abi.SurfaceRecord.SIZE;
                    R2Abi.SurfaceRecord.definition(surfaces,base,1,0,0,1);
                    R2Abi.SurfaceRecord.medium(surfaces,base,contact.medium());
                    R2Abi.SurfaceRecord.mediumDefinition(surfaces,base,Math.addExact(pending.snapshot.mediumDefinitions.buffer.view().address(),media.offset(contact.medium())));
                    R2Abi.SurfaceRecord.boundary(surfaces,base,R2Abi.ROLE_CLIP);


                    if(!sameTriangles)for(int i=0;i<contact.triangles();i++,clipTriangle++) {
                        int target=clipTriangle*R2Abi.TriangleRecord.SIZE,first=clipTriangle*12;var trace=compiled.traceIndices();
                        R2Abi.TriangleRecord.indicesSurface(triangles,target,trace.getInt(first),trace.getInt(first+4),trace.getInt(first+8),partSlot);
                        R2Abi.TriangleRecord.ordinalSource(triangles,target,0,0,0,-1);
                    }
                    opaque=false;partSlot++;
                }
                if(sameTriangles) {
                    attributes.triangles=before.triangles.retain();pending.reusedRecordBytes+=before.triangles.bytes();
                } else attributes.triangles=pending.data(triangles,before==null?null:before.triangles);
                attributes.surfaces=pending.data(surfaces,before==null?null:before.surfaces);
                pending.encodedAttributeBytes=Math.addExact(pending.encodedAttributeBytes,(triangles==null?0L:triangles.remaining())+surfaces.remaining());
                attributes.remember(compiled,pending.snapshot.textures,media,mediumAddress,opaque,pending.snapshot.filterPrimitives-priorFilters);
                }
                Mesh mesh=current==null?null:current.meshes.get(input.key());
                if (mesh!=null && mesh.matches(compiled,opaque)) {
                    mesh=pending.reuse(mesh,input.motion()==SceneInputs.Motion.STATIC);pending.reusedMeshes++;
                }
                else mesh=pending.geometry(compiled,opaque,mesh);
                pending.snapshot.meshes.put(input.key(),mesh);
                pending.snapshot.blasBytes=Math.addExact(pending.snapshot.blasBytes,mesh.blas.size());
                if(input.motion()==SceneInputs.Motion.STATIC && mesh.blas.readyForCompactionQuery() && mesh.compaction==null
                    && mesh.blas.size()>=compactionBudget.minimumSourceBytes() && pending.measurements.size()<compactionBudget.queries())pending.measurements.add(mesh);
                pending.snapshot.compiled.put(input.key(),compiled);
                var previousCompiled=current==null?null:current.compiled.get(input.key());
                boolean previousValid=previousCompiled!=null && previousCompiled.topologyHash().equals(compiled.topologyHash())
                    && previousCompiled.input().revision().topology()==input.revision().topology()
                    && previousCompiled.input().revision().world()==input.revision().world()
                    && (input.previousValid() || previousCompiled==compiled || input.motion()==SceneInputs.Motion.STATIC);
                Mesh previousMesh=previousValid?current.meshes.get(input.key()):null;

                if(previousMesh!=null && previousMesh.positionData!=mesh.positionData)
                    pending.snapshot.previousPositions.add(previousMesh.positionData.retain());
                float[] transform=relative(input.current(),input.origin(),origin);
                float[] previous=previousValid?relative(previousCompiled.input().current(),previousCompiled.input().origin(),origin):transform;
                int base=slot*R2Abi.GeometryRecord.SIZE;
                R2Abi.GeometryRecord.positions(records,base,mesh.positions.view().address());
                R2Abi.GeometryRecord.corners(records,base,attributes.corners.buffer.view().address());
                R2Abi.GeometryRecord.triangles(records,base,attributes.triangles.buffer.view().address());
                R2Abi.GeometryRecord.surfaces(records,base,attributes.surfaces.buffer.view().address());
                R2Abi.GeometryRecord.current(records,base,transform);
                R2Abi.GeometryRecord.inverse(records,base,inverse(transform));
                R2Abi.GeometryRecord.previous(records,base,previous);
                R2Abi.GeometryRecord.previousPositions(records,base,previousMesh==null?0:previousMesh.positions.view().address());
                R2Abi.GeometryRecord.identity(records,base,key(input.key()));
                R2Abi.GeometryRecord.revisions(records,base,(int)input.revision().topology(),(int)input.revision().deformation(),
                    (int)input.revision().appearance(),(int)input.revision().resources());
                R2Abi.GeometryRecord.state(records,base,input.motion()==SceneInputs.Motion.STATIC?0:input.motion()==SceneInputs.Motion.DEFORMING?3:1,previousValid?1:0,compiled.traceTriangleCount(),
                    depthSources.geometry().contains(input.key())?R2Abi.GEOMETRY_SOURCE_DEPTH:0);
                for (int i=0;i<12;i++) instances.putFloat(slot*64+i*4,transform[i]);
                int mask=input.participation().rayMask();
                if (!input.participation().primaryVisible()) mask &= ~1;
                instances.putInt(slot*64+48,slot | (mask<<24));
                instances.putInt(slot*64+52,KHRAccelerationStructure.VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR<<24);
                instances.putLong(slot*64+56,mesh.blas.address());
                slot++;
            }
            pending.snapshot.records=pending.upload(records,ADDRESSABLE);
            if(current!=null && current.tlas.matches(revision.world(),slot,instances)) {
                pending.snapshot.tlas=current.tlas.retain();
                pending.reusedInstanceBytes=instances.remaining();
            } else {
                var instanceBuffer=pending.upload(instances,BUILD_INPUT);
                pending.tlas=acceleration.topLevel(instanceBuffer.view(),slot,null);
                try { pending.snapshot.tlas=new TopLevel(revision.world(),slot,instances,pending.tlas.target()); }
                catch(RuntimeException|Error failure) { pending.tlas.target().close();throw failure; }
            }
            return pending;
        } catch (RuntimeException | Error failure) { pending.close(); throw failure; }
    }
    private static java.util.Set<SceneInputs.Key> changedAssociations(LightCompiler.Compiled before,LightCompiler.Compiled next) {
        if(before==null || before.associations().equals(next.associations()))return java.util.Set.of();
        var changed=new java.util.HashSet<SceneInputs.Key>();
        for(var entry:next.associations().entrySet())if(!entry.getValue().equals(before.associations().get(entry.getKey())))changed.add(entry.getKey().geometry());
        for(var entry:before.associations().entrySet())if(!entry.getValue().equals(next.associations().get(entry.getKey())))changed.add(entry.getKey().geometry());
        return changed;
    }
    private int surfaceToken() {
        if(nextSurfaceIdentity==Integer.MAX_VALUE)throw new IllegalStateException("Surface identity space exhausted");
        return nextSurfaceIdentity++;
    }
    private static SurfaceIdentity surfaceIdentity(SceneCompiler.Compiled compiled,SceneCompiler.Part part,SceneTextures textures,MediumDefinitions media) {
        var input=compiled.input();var surface=part.surface();var layers=surface.layers();
        var content=new SurfaceContent(compiled.appearanceHash(),compiled.shadingHash(),textureIndependentBoundary(surface)?0:textures.revision(surface.colorResource()),
            surface.coverage()==SceneInputs.Coverage.CUTOUT?textures.revision(layers.coverage()):0,
            layers.emission()!=MaterialInputs.Emission.NONE?textures.revision(surface.emissionResource()):0,
            layers.overlay().high()!=0 || layers.overlay().low()!=0?textures.revision(layers.overlay()):0,media==null?null:media.definition(surface.medium()));
        return new SurfaceIdentity(input.key(),part.key(),part.ordinal(),input.revision().topology(),input.revision().appearance(),
            input.revision().resources(),input.revision().coverage(),input.revision().world(),content);
    }

    private void refreshTextures(Prepared pending,Attributes attributes,Attributes before,SceneCompiler.Compiled compiled,SceneTextures textures) {
        attributes.reuse(before);
        var changed=new java.util.HashSet<SceneInputs.Key>();
        for(var entry:before.textures.entrySet())if(!entry.getValue().equals(textures.surfaceState(entry.getKey())))changed.add(entry.getKey());
        var affected=before.affected(changed);attributes.resourceParts=before.resourceParts;
        ByteBuffer updated=null;
        for(int i=affected.nextSetBit(0);i>=0;i=affected.nextSetBit(i+1)) {
            var part=compiled.parts().get(i);var surface=part.surface();var layers=surface.layers();
            var identity=surfaceIdentity(compiled,part,textures,before.media);
            int token=before.identities[i].equals(identity)?before.tokens[i]:surfaceToken();
            int color=textures.slot(surface.colorResource()),emission=layers.emission()==MaterialInputs.Emission.NONE?-1:textures.slot(surface.emissionResource());
            int overlay=layers.overlay().high()==0 && layers.overlay().low()==0?-1:textures.slot(layers.overlay()),coverage=textures.slot(layers.coverage());
            int encodings=(textures.linear(surface.colorResource())?1:0)|(emission>=0 && textures.linear(surface.emissionResource())?2:0)|(overlay>=0 && textures.linear(layers.overlay())?4:0);
            int base=i*R2Abi.SurfaceRecord.SIZE,tex=base+R2Abi.SurfaceRecord.TEXTURES,layer=base+R2Abi.SurfaceRecord.LAYERS;
            var old=before.surfaces.content;
            if(token==before.tokens[i] && old.getInt(tex)==color && old.getInt(tex+4)==emission && old.getInt(tex+8)==encodings
                && old.getInt(layer)==coverage && old.getInt(layer+4)==overlay)continue;
            if(updated==null) {
                updated=bytes(old.remaining());updated.put(old.duplicate()).flip();
                attributes.identities=before.identities.clone();attributes.tokens=before.tokens.clone();attributes.lookup=null;
                pending.copiedAttributeBytes+=updated.remaining();
            }
            attributes.identities[i]=identity;attributes.tokens[i]=token;
            R2Abi.SurfaceRecord.textures(updated,base,color,emission,encodings,token);
            updated.putInt(layer,coverage).putInt(layer+4,overlay);
            pending.encodedAttributeBytes+=R2Abi.SurfaceRecord.SIZE;
        }
        pending.reusedRecordBytes+=before.corners.bytes()+before.triangles.bytes();
        if(updated==null)pending.reusedRecordBytes+=before.surfaces.bytes();
        else {
            var replacement=pending.data(updated,before.surfaces);attributes.surfaces.close();attributes.surfaces=replacement;
        }
        attributes.remember(compiled,textures,before.media,before.mediumAddress,before.opaque,before.filters);
    }
    private static int[] key(SceneInputs.Key key) { return new int[]{(int)key.high(),(int)(key.high()>>>32),(int)key.low(),(int)(key.low()>>>32)}; }
    static float[] relative(SceneInputs.Transform transform,SceneInputs.Origin origin,SceneInputs.Origin reference) {
        float[] rows=transform.rows(); rows[3]+=(float)(origin.x()-reference.x()); rows[7]+=(float)(origin.y()-reference.y()); rows[11]+=(float)(origin.z()-reference.z()); return rows;
    }
    private static float[] inverse(float[] r) {
        Matrix4f m=new Matrix4f(r[0],r[4],r[8],0,r[1],r[5],r[9],0,r[2],r[6],r[10],0,r[3],r[7],r[11],1).invert();
        return new float[]{m.m00(),m.m10(),m.m20(),m.m30(),m.m01(),m.m11(),m.m21(),m.m31(),m.m02(),m.m12(),m.m22(),m.m32()};
    }
    private static ByteBuffer bytes(int size) { return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN); }

    private static final class Data implements AutoCloseable {
        private final ByteBuffer content;
        private final VulkanResources.Buffer buffer;
        private int owners=1;
        private Data(ByteBuffer content,VulkanResources.Buffer buffer) { this.content=content.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);this.buffer=buffer; }
        private boolean matches(ByteBuffer candidate) { return owners>0 && content.mismatch(candidate)==-1; }
        private Data retain() { if(owners<=0)throw new IllegalStateException("Retired immutable records");owners=Math.incrementExact(owners);return this; }
        private void markUsed() { buffer.markUsed(); }
        private long bytes() { return buffer.view().length(); }
        @Override public void close() {
            if(owners<=0)throw new IllegalStateException("Record ownership released twice");
            if(--owners==0)buffer.close();
        }
    }
    private static final class Attributes implements AutoCloseable {
        private Data corners,triangles,surfaces;
        private SceneCompiler.Compiled source;
        private MediumDefinitions media;
        private long mediumAddress;
        private boolean opaque;
        private int filters;
        private java.util.Map<SceneInputs.Key,SceneTextures.SurfaceState> textures;
        private SurfaceIdentity[] identities;
        private int[] tokens;
        private java.util.Map<SurfaceIdentity,Integer> lookup;
        private java.util.Map<SceneInputs.Key,int[]> resourceParts;
        private Integer token(SurfaceIdentity identity,int slot,boolean sameOrder) {
            if(sameOrder)return identities[slot].equals(identity)?tokens[slot]:null;
            if(lookup==null) {
                lookup=new java.util.HashMap<>();
                for(int i=0;i<identities.length;i++)lookup.put(identities[i],tokens[i]);
            }
            return lookup.get(identity);
        }

        private java.util.BitSet affected(java.util.Set<SceneInputs.Key> changed) {
            if(resourceParts==null) {
                var lists=new java.util.HashMap<SceneInputs.Key,java.util.ArrayList<Integer>>();
                for(int i=0;i<source.parts().size();i++) {
                    var surface=source.parts().get(i).surface();var layers=surface.layers();
                    var keys=new SceneInputs.Key[]{surface.colorResource(),layers.coverage(),layers.overlay(),surface.emissionResource()};
                    int count=layers.emission()==MaterialInputs.Emission.NONE?3:4;
                    for(int n=0;n<count;n++) {
                        boolean duplicate=false;for(int j=0;j<n;j++)duplicate|=keys[j].equals(keys[n]);
                        if(!duplicate)lists.computeIfAbsent(keys[n],ignored->new java.util.ArrayList<>()).add(i);
                    }
                }
                resourceParts=new java.util.HashMap<>();
                for(var entry:lists.entrySet())resourceParts.put(entry.getKey(),entry.getValue().stream().mapToInt(Integer::intValue).toArray());
            }
            var result=new java.util.BitSet();
            for(var key:changed)for(int part:resourceParts.getOrDefault(key,new int[0]))result.set(part);
            return result;
        }
        private boolean matchesGeometry(SceneCompiler.Compiled candidate) {
            return source!=null && source.sharesStorage(candidate) && source.parts().equals(candidate.parts());
        }
        private boolean matchesInputs(SceneCompiler.Compiled candidate,MediumDefinitions definitions,long address) {
            return matchesGeometry(candidate)
                && source.appearanceHash().equals(candidate.appearanceHash()) && source.shadingHash().equals(candidate.shadingHash())
                && source.input().key().equals(candidate.input().key()) && source.input().revision().equals(candidate.input().revision())
                && media==definitions && mediumAddress==address;
        }
        private boolean matches(SceneCompiler.Compiled candidate,SceneTextures next,MediumDefinitions definitions,long address) {
            if(!matchesInputs(candidate,definitions,address))return false;
            for(var entry:textures.entrySet())if(!entry.getValue().equals(next.surfaceState(entry.getKey())))return false;
            return true;
        }
        private void reuse(Attributes before) {
            corners=before.corners.retain();triangles=before.triangles.retain();surfaces=before.surfaces.retain();
            source=before.source;media=before.media;mediumAddress=before.mediumAddress;opaque=before.opaque;filters=before.filters;
            textures=before.textures;identities=before.identities;tokens=before.tokens;lookup=before.lookup;resourceParts=before.resourceParts;
        }
        private void remember(SceneCompiler.Compiled compiled,SceneTextures resources,MediumDefinitions definitions,long address,boolean opaque,int filters) {
            this.source=compiled;this.media=definitions;this.mediumAddress=address;this.opaque=opaque;this.filters=filters;
            var dependencies=new java.util.HashMap<SceneInputs.Key,SceneTextures.SurfaceState>();
            for(var key:compiled.renderResourceKeys())dependencies.put(key,resources.surfaceState(key));
            textures=java.util.Map.copyOf(dependencies);
        }
        private void markUsed() { if(corners!=null)corners.markUsed();if(triangles!=null)triangles.markUsed();if(surfaces!=null)surfaces.markUsed(); }
        private long bytes() { return (corners==null?0:corners.bytes())+(triangles==null?0:triangles.bytes())+(surfaces==null?0:surfaces.bytes()); }
        @Override public void close() { if(corners!=null)corners.close();if(triangles!=null)triangles.close();if(surfaces!=null)surfaces.close(); }
    }
    private final class QueryBatch implements AutoCloseable {
        private final VulkanAccelerationStructures.CompactionQuery query;
        private int owners=1;
        private QueryBatch(List<Mesh> meshes) { query=acceleration.compactionQuery(meshes.stream().map(m->m.blas).toList()); }
        private CompactionTicket ticket(int index) { owners=Math.incrementExact(owners);return new CompactionTicket(this,index); }
        @Override public void close() { if(owners<=0)throw new IllegalStateException("Compaction query released twice");if(--owners==0)query.close(); }
    }
    private final class CompactionTicket implements AutoCloseable {
        private QueryBatch batch;
        private final int index;
        private VulkanAccelerationStructures.CompactionSize result;
        private CompactionTicket(QueryBatch batch,int index) { this.batch=batch;this.index=index; }
        private VulkanAccelerationStructures.CompactionSize ready() {
            if(result==null && batch!=null) {
                result=batch.query.result(index);
                if(result!=null) { batch.close();batch=null; }
            }
            return result;
        }
        @Override public void close() { if(batch!=null) { batch.close();batch=null; } }
    }





    static boolean textureIndependentBoundary(SceneInputs.Surface surface) {
        var layers=surface.layers();


        return surface.coverage()==SceneInputs.Coverage.DIELECTRIC && surface.doubleSided() && surface.properties()==0
            && surface.material()==8
            && !layers.modelResponse() && layers.emission()==MaterialInputs.Emission.NONE
            && layers.overlay().high()==0 && layers.overlay().low()==0;
    }

    private final class Mesh implements AutoCloseable {
        private final String topology,positionsIdentity;
        private final boolean opaque,deformable;
        private final VulkanResources.Buffer positions,indices;
        private final Data positionData,indexData;
        private final VulkanAccelerationStructures.BottomLevel blas;
        private final int updateChain;
        private final boolean finitePositions;
        private CompactionTicket compaction;
        private int owners=1;
        private Mesh(SceneCompiler.Compiled compiled,boolean opaque,Data positions,Data indices,VulkanAccelerationStructures.BottomLevel blas,int updateChain,boolean finitePositions) {
            this.topology=compiled.traceTopologyHash(); this.positionsIdentity=compiled.tracePositionHash(); this.opaque=opaque;
            this.deformable=compiled.input().motion()==SceneInputs.Motion.DEFORMING; this.positions=positions.buffer; this.indices=indices.buffer; this.blas=blas;
            this.positionData=positions;this.indexData=indices;this.updateChain=updateChain;
            this.finitePositions=finitePositions;
        }
        private Mesh(Mesh source,VulkanAccelerationStructures.BottomLevel blas) {
            topology=source.topology;positionsIdentity=source.positionsIdentity;opaque=source.opaque;deformable=source.deformable;
            positionData=source.positionData.retain();indexData=source.indexData.retain();positions=positionData.buffer;indices=indexData.buffer;this.blas=blas;
            updateChain=source.updateChain;finitePositions=source.finitePositions;
        }
        private boolean matches(SceneCompiler.Compiled compiled,boolean opaque) {
            return owners>0 && this.opaque==opaque && deformable==(compiled.input().motion()==SceneInputs.Motion.DEFORMING)
                && topology.equals(compiled.traceTopologyHash()) && positionsIdentity.equals(compiled.tracePositionHash());
        }
        private void retain() { if (owners<=0) throw new IllegalStateException("Retired mesh"); owners=Math.incrementExact(owners); }
        private void markUsed() { positions.markUsed(); indices.markUsed(); blas.markUsed(); }
        private long bytes() { return positions.view().length()+indices.view().length()+blas.size(); }
        @Override public void close() {
            if (owners<=0) throw new IllegalStateException("Mesh ownership released twice");
            if (--owners==0) { if(compaction!=null)compaction.close();blas.close();positionData.close();indexData.close(); }
        }
    }
    private static boolean finitePositions(ByteBuffer data) {
        for(int base=0;base<data.remaining();base+=R2Abi.PositionRecord.SIZE)
            for(int axis=0;axis<3;axis++)if(!Float.isFinite(data.getFloat(base+axis*4)))return false;
        return true;
    }

    private static final class TopLevel implements AutoCloseable {
        private final long world;
        private final int count;
        private final ByteBuffer instances;
        private final VulkanResources.AccelerationStructure structure;
        private int owners=1;
        private TopLevel(long world,int count,ByteBuffer instances,VulkanResources.AccelerationStructure structure) {
            this.world=world;this.count=count;this.instances=instances.asReadOnlyBuffer();this.structure=structure;
        }
        private boolean matches(long world,int count,ByteBuffer instances) {
            return owners>0 && this.world==world && this.count==count && this.instances.mismatch(instances)==-1;
        }
        private TopLevel retain() { if(owners<=0)throw new IllegalStateException("Retired TLAS");owners=Math.incrementExact(owners);return this; }
        private void markUsed() { structure.markUsed(); }
        private long size() { return structure.size(); }
        private long handle() { return structure.handle(); }
        @Override public void close() { if(owners<=0)throw new IllegalStateException("TLAS released twice");if(--owners==0)structure.close(); }
    }
    public final class Prepared implements AutoCloseable {
        private final long recording=resources.recording();
        private final long generation=preparation;
        private final Snapshot snapshot;
        private final List<VulkanUploads.Transfer> transfers=new ArrayList<>();
        private final List<VulkanAccelerationStructures.Build> builds=new ArrayList<>();
        private final List<Mesh> updateSources=new ArrayList<>();
        private final List<VulkanAccelerationStructures.CompactCopy> copies=new ArrayList<>();
        private final List<Mesh> measurements=new ArrayList<>();
        private QueryBatch queries;
        private long compactedBeforeBytes,compactedAfterBytes;
        private int compactionAttempts,compactionAllocationFailures;
        private VulkanAccelerationStructures.Build tlas;
        private boolean recorded,committed,closed;
        private int reusedMeshes;
        private long reusedGeometryBytes;
        private long reusedInstanceBytes;
        private long textureUploadBytes;
        private long reusedRecordBytes;
        private int reusedAttributeMeshes;
        private long copiedAttributeBytes;
        private long encodedAttributeBytes;
        private long reusedSourceBytes;
        private int compiledMaterialDefinitions,compiledAtmosphereCells;
        private LightCompiler.Work lightWork;
        private Prepared(long serial,SceneInputs.Origin origin) { snapshot=new Snapshot(serial,origin); }
        private Mesh geometry(SceneCompiler.Compiled compiled,boolean opaque,Mesh before) {
            Data positions=null,indices=null;VulkanAccelerationStructures.BottomLevel blas=null;
            try {
                positions=geometryData(compiled.tracePositions(),before==null?null:before.positionData);
                indices=geometryData(compiled.traceIndices(),before==null?null:before.indexData);
                var input=compiled.input();var previous=current==null?null:current.compiled.get(input.key());
                boolean finite=finitePositions(compiled.tracePositions());
                boolean update=before!=null && before.deformable && input.motion()==SceneInputs.Motion.DEFORMING
                    && before.opaque==opaque && before.topology.equals(compiled.traceTopologyHash())
                    && indices==before.indexData && before.finitePositions && finite
                    && previous.input().revision().world()==input.revision().world()
                    && previous.input().revision().topology()==input.revision().topology()
                    && before.updateChain<updatePolicy.maximumChainLength() && before.blas.readyForUpdate();
                long topology=Long.parseUnsignedLong(compiled.traceTopologyHash().substring(0,16),16)&Long.MAX_VALUE;
                var fact=new TriangleGeometry(positions.buffer.view(),R2Abi.PositionRecord.SIZE,compiled.traceVertexCount(),indices.buffer.view(),4,
                    compiled.traceTriangleCount(),Math.max(1,topology),opaque);
                blas=acceleration.allocateBottomLevel(List.of(fact),input.motion()==SceneInputs.Motion.DEFORMING,
                    input.motion()==SceneInputs.Motion.STATIC && compiled.triangleCount()>=128 && compactionBudget.queries()>0);
                var build=update?acceleration.update(before.blas,blas,List.of(fact)):acceleration.build(blas,List.of(fact),false);
                var mesh=new Mesh(compiled,opaque,positions,indices,blas,update?before.updateChain+1:0,finite);
                snapshot.meshes.put(input.key(),mesh);
                positions=null;indices=null;blas=null;
                if(update) { before.retain();updateSources.add(before); }
                builds.add(build);return mesh;
            } finally { if(blas!=null)blas.close();if(indices!=null)indices.close();if(positions!=null)positions.close(); }
        }
        private Data geometryData(ByteBuffer content,Data before) {
            if(before!=null && before.matches(content)) { reusedGeometryBytes=Math.addExact(reusedGeometryBytes,content.remaining());return before.retain(); }
            var buffer=upload(content,BUILD_INPUT);var data=new Data(content,buffer);snapshot.buffers.remove(buffer);return data;
        }
        private Mesh reuse(Mesh before,boolean immutableTerrain) {
            var size=before.compaction==null?null:before.compaction.ready();
            if(immutableTerrain && size!=null && size.sourceBytes()-size.bytes()>=Math.max(compactionBudget.minimumSavingBytes(),size.sourceBytes()/10)
                && compactionAttempts<compactionBudget.copies() && size.bytes()<=compactionBudget.copyBytes()-compactedAfterBytes) {
                compactionAttempts++;
                VulkanAccelerationStructures.CompactCopy copy;
                try { copy=acceleration.compact(size); }
                catch(dev.rt_render_experiment.vulkan.VulkanObjects.Failure failure) {
                    if(!failure.allocationFailure())throw failure;
                    compactionAllocationFailures++;before.retain();return before;
                }
                Mesh mesh;
                try { mesh=new Mesh(before,copy.target()); }
                catch(RuntimeException|Error failure) { copy.target().close();throw failure; }
                copies.add(copy);compactedBeforeBytes=Math.addExact(compactedBeforeBytes,before.blas.size());
                compactedAfterBytes=Math.addExact(compactedAfterBytes,copy.target().size());return mesh;
            }
            before.retain();return before;
        }
        private VulkanResources.Buffer upload(ByteBuffer data,int usage) {
            var buffer=resources.allocateDevice(data.remaining(),usage); snapshot.buffers.add(buffer);
            transfers.add(uploads.stage(buffer,0,data)); return buffer;
        }
        private Data data(ByteBuffer content,Data before) {
            var result=sharedData(content,before);
            if(result==before)reusedRecordBytes=Math.addExact(reusedRecordBytes,content.remaining());
            return result;
        }
        private Data mediumData(ByteBuffer content,Data before) {
            if(before!=null && before.matches(content)) { reusedRecordBytes=Math.addExact(reusedRecordBytes,content.remaining());return before.retain(); }


            var buffer=upload(content,ADDRESSABLE|VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
            if((buffer.view().address()&15L)!=0)throw new IllegalStateException("Medium definition address is not aligned for its GPU schema");
            var result=new Data(content,buffer);snapshot.buffers.remove(buffer);return result;
        }
        private Data sourceData(ByteBuffer content,Data before) {
            return sourceData(content,before,false);
        }
        private Data sourceData(ByteBuffer content,Data before,boolean shared) {
            if(shared) { reusedSourceBytes=Math.addExact(reusedSourceBytes,content.remaining());return before.retain(); }
            var result=sharedData(content,before);
            if(result==before)reusedSourceBytes=Math.addExact(reusedSourceBytes,content.remaining());
            return result;
        }
        private Data sharedData(ByteBuffer content,Data before) {
            if(before!=null && before.matches(content))return before.retain();
            var buffer=upload(content,ADDRESSABLE);var result=new Data(content,buffer);snapshot.buffers.remove(buffer);return result;
        }
        public void record(VkCommandBuffer command) {
            record(null,command);
        }
        public void record(HostExecution.Window window,VkCommandBuffer command) {
            if (recorded || closed || recording!=resources.recording() || generation!=preparation) throw new IllegalStateException("Expired scene preparation");
            compiledMaterialDefinitions=materialCompiler.record(command,snapshot.materials);
            compiledAtmosphereCells=atmosphereCompiler.record(command,snapshot.atmosphere);
            if (!snapshot.textures.recorded()) { textureUploadBytes=snapshot.textures.pendingUploadBytes(); snapshot.textures.record(window,command,textureImporter); }

            snapshot.markUsed();
            uploads.recordBatch(command,transfers);
            try (MemoryStack stack=MemoryStack.stackPush()) {
                VulkanBarriers.record(command,stack,VulkanBarriers.PRIOR_ACCESS_TO_AS_BUILD);
                for (var build:builds) build.record(command);
                for (var copy:copies) copy.record(command);
                VulkanBarriers.record(command,stack,VulkanBarriers.BLAS_TO_TLAS);
                if(!measurements.isEmpty())try { queries=new QueryBatch(measurements);queries.query.record(command); }
                catch(dev.rt_render_experiment.vulkan.VulkanObjects.Failure failure) {
                    if(!failure.allocationFailure())throw failure;
                    if(queries!=null) { queries.close();queries=null; }measurements.clear();compactionAllocationFailures++;
                }
                if(tlas!=null)tlas.record(command);
                VulkanBarriers.record(command,stack,new VulkanBarriers.Scope(VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT | KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT | KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
                    KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                    VK13.VK_ACCESS_2_SHADER_READ_BIT | KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR));
            }
            recorded=true;
        }
        public Snapshot recordedScene() {
            if (!recorded || closed) throw new IllegalStateException("Scene is not recorded");
            return snapshot;
        }
        public int builtMeshes() { return (int)builds.stream().filter(b->!b.updates()).count(); }
        public int updatedMeshes() { return (int)builds.stream().filter(VulkanAccelerationStructures.Build::updates).count(); }
        public long reusedGeometryBytes() { return reusedGeometryBytes; }
        public boolean builtTopLevel() { return tlas!=null; }
        public long reusedInstanceBytes() { return reusedInstanceBytes; }
        public int queriedCompactions() { return measurements.size(); }
        public int compactedMeshes() { return copies.size(); }
        public long compactedBeforeBytes() { return compactedBeforeBytes; }
        public long compactedAfterBytes() { return compactedAfterBytes; }
        public int compactionAllocationFailures() { return compactionAllocationFailures; }
        public int compiledMaterialDefinitions() { return compiledMaterialDefinitions; }
        public int compiledAtmosphereCells() { return compiledAtmosphereCells; }
        public int reusedMeshes() { return reusedMeshes; }
        public long reusedRecordBytes() { return reusedRecordBytes; }
        public int reusedAttributeMeshes() { return reusedAttributeMeshes; }
        public long copiedAttributeBytes() { return copiedAttributeBytes; }
        public long encodedAttributeBytes() { return encodedAttributeBytes; }
        public long reusedSourceBytes() { return reusedSourceBytes; }
        public LightCompiler.Work lightWork() { return lightWork; }
        public long textureStagedBytes() { return textureUploadBytes; }
        public long stagedBytes() { return Math.addExact(textureUploadBytes,transfers.stream().mapToLong(VulkanUploads.Transfer::bytes).sum()); }

        public void submitted(long submitted) {
            if (!recorded || closed || committed || submitted!=recording) throw new IllegalStateException("Scene publication lacks exact submission");
            for(var mesh:measurements)if(mesh.compaction!=null)throw new IllegalStateException("Duplicate compaction query publication");
            snapshot.textures.submitted(submitted);
            snapshot.materials.submitted(submitted);
            snapshot.atmosphere.submitted(submitted);
            for(var build:builds)build.submitted(submitted);
            for(var copy:copies)copy.submitted(submitted);
            if(queries!=null) {
                queries.query.submitted(submitted);
                for(int i=0;i<measurements.size();i++) {
                    var mesh=measurements.get(i);
                    mesh.compaction=queries.ticket(i);
                }
                queries.close();queries=null;
            }
            Snapshot predecessor=current; current=snapshot; committed=true;
            if (predecessor!=null) predecessor.close();
        }
        @Override public void close() {
            if (!closed) {
                closed=true;if(queries!=null) { queries.close();queries=null; }if (!committed) snapshot.close();
                for(var source:updateSources)source.close();updateSources.clear();
            }
        }
    }
    public final class Snapshot implements AutoCloseable, R2Scene {
        private final long serial;
        private long world;
        private final SceneInputs.Origin origin;
        private final List<VulkanResources.Buffer> buffers=new ArrayList<>();
        private final java.util.Map<SceneInputs.Key,Mesh> meshes=new java.util.HashMap<>();
        private final java.util.Map<SceneInputs.Key,SceneCompiler.Compiled> compiled=new java.util.HashMap<>();
        private final java.util.Map<SceneInputs.Key,Attributes> attributes=new java.util.HashMap<>();


        private final List<Data> previousPositions=new ArrayList<>();
        private VulkanResources.Buffer records;
        private Data sourceRecords,lightCells,sourceHeader,mediumDefinitions;
        private MediumDefinitions compiledMedia;
        private LightCompiler.Compiled compiledLights;
        private WorldLightDomain worldLightDomain;
        private SceneTextures textures;
        private DefinitionBank.Version materials,atmosphere;
        private TopLevel tlas;
        private int filterPrimitives;
        private long blasBytes;
        private boolean closed;
        private Snapshot(long serial,SceneInputs.Origin origin) { this.serial=serial; this.origin=origin; }
        public long serial() { return serial; }
        public void requireInitialMedia(RenderFrame frame) {
            if(closed)throw new IllegalStateException("Retired scene");
            InitialMedia.validate(frame,world,serial,compiled.values());
            if(compiledMedia!=null)compiledMedia.requireFrameOrigin(frame);
            else if(frame.clippedMediumPrimitives()!=0)throw new IllegalArgumentException("Clipped medium traversal requires published definitions");
        }
        public WorldLightDomain worldLightDomain() { if(closed)throw new IllegalStateException("Retired scene");return worldLightDomain; }
        public int filterPrimitives() { if(closed)throw new IllegalStateException("Retired scene");return filterPrimitives; }
        public boolean represents(dev.rt_render_experiment.contract.SceneInputs.Geometry input) {
            if(closed)throw new IllegalStateException("Retired scene");
            var found=compiled.get(input.key());return found!=null && found.input()==input;
        }
        public String shaderIdentity() { return shaderIdentity; }
        public SceneInputs.Origin origin() { return origin; }
        public long recordsAddress() { if (closed) throw new IllegalStateException("Retired scene"); return records.view().address(); }
        public long blasBytes() { if(closed)throw new IllegalStateException("Retired scene");return blasBytes; }
        long blasAddress(SceneInputs.Key key) { if(closed)throw new IllegalStateException("Retired scene");return meshes.get(key).blas.address(); }
        boolean compacted(SceneInputs.Key key) { if(closed)throw new IllegalStateException("Retired scene");return meshes.get(key).blas.compacted(); }
        VulkanAccelerationStructures.BottomLevel geometryAcceleration(SceneInputs.Key key) { if(closed)throw new IllegalStateException("Retired scene");return meshes.get(key).blas; }
        long positionsAddress(SceneInputs.Key key) { if(closed)throw new IllegalStateException("Retired scene");return meshes.get(key).positions.view().address(); }
        long indicesAddress(SceneInputs.Key key) { if(closed)throw new IllegalStateException("Retired scene");return meshes.get(key).indices.view().address(); }
        int updateChain(SceneInputs.Key key) { if(closed)throw new IllegalStateException("Retired scene");return meshes.get(key).updateChain; }
        VulkanResources.AccelerationStructure topLevelStorage() { if(closed)throw new IllegalStateException("Retired scene");return tlas.structure; }
        public long sourcesAddress() { if (closed) throw new IllegalStateException("Retired scene"); return sourceHeader.buffer.view().address(); }
        @Override public int sourceCount() { if(closed)throw new IllegalStateException("Retired scene");return Math.addExact(compiledLights.worldCount(),compiledLights.dynamicCount()); }
        public long atmosphereAddress() { if(closed)throw new IllegalStateException("Retired scene");return atmosphere.buffer().view().address(); }
        VulkanResources.Buffer atmosphereDepths() { if(closed)throw new IllegalStateException("Retired scene");return atmosphere.buffer(); }
        public long atmosphereBytes() { if(closed)throw new IllegalStateException("Retired scene");return atmosphere.bytes(); }
        public long materialsAddress() { if(closed)throw new IllegalStateException("Retired scene");return materials.buffer().view().address(); }
        VulkanResources.Buffer materialDefinitions() { if(closed)throw new IllegalStateException("Retired scene");return materials.buffer(); }
        public long materialDefinitionBytes() { if(closed)throw new IllegalStateException("Retired scene");return materials.bytes(); }
        public int retainedLightSources() { if(closed)throw new IllegalStateException("Retired scene");return compiledLights.retainedSources(); }
        public long compiledLightBytes() { if(closed)throw new IllegalStateException("Retired scene");return compiledLights.encodedBytes(); }
        public long mediumDefinitionBytes() { if(closed)throw new IllegalStateException("Retired scene");return mediumDefinitions==null?0:mediumDefinitions.bytes(); }
        VulkanResources.Buffer mediumDefinitions() { if(closed || mediumDefinitions==null)throw new IllegalStateException("No live medium definitions");return mediumDefinitions.buffer; }
        public long accelerationStructure() { if (closed) throw new IllegalStateException("Retired scene"); return tlas.handle(); }
        public void bindTextures(VkCommandBuffer command,long layout) { if (closed) throw new IllegalStateException("Retired scene"); textures.bind(command,layout); }
        public void bindTextures(VkCommandBuffer command,int bindPoint,long layout) { if (closed) throw new IllegalStateException("Retired scene"); textures.bind(command,bindPoint,layout); }
        public void finishTextureReads(VkCommandBuffer command) { if(closed)throw new IllegalStateException("Retired scene");textures.finishReads(command); }
        public int borrowedTextureImages() { return textures.borrowedImages(); }
        public int cloudSlot(RenderFrame.Cloud cloud) {
            if (cloud.resource().equals(new SceneInputs.Key(0,0)) || textures.width(cloud.resource())!=cloud.width() || textures.height(cloud.resource())!=cloud.height())
                throw new IllegalArgumentException("Cloud geometry and prepared field resource disagree");
            return textures.slot(cloud.resource());
        }
        public long logicalBytes() {
            long bytes=(tlas==null?0:tlas.size())+(textures==null?0:textures.logicalBytes())+(materials==null?0:materials.bytes())+(atmosphere==null?0:atmosphere.bytes());
            for (var buffer:buffers) bytes+=buffer.view().length();
            for (var mesh:meshes.values()) bytes+=mesh.bytes();
            for (var data:previousPositions) bytes+=data.bytes();
            for(var data:attributes.values())bytes+=data.bytes();
            bytes+=(sourceRecords==null?0:sourceRecords.bytes())+(lightCells==null?0:lightCells.bytes())+(sourceHeader==null?0:sourceHeader.bytes());
            bytes+=mediumDefinitions==null?0:mediumDefinitions.bytes();
            return bytes;
        }
        public void markUsed() {
            if (closed) throw new IllegalStateException("Retired scene");
            for (var buffer:buffers) buffer.markUsed();
            for (var mesh:meshes.values()) mesh.markUsed();
            for (var data:previousPositions) data.markUsed();
            for(var data:attributes.values())data.markUsed();
            sourceRecords.markUsed();lightCells.markUsed();sourceHeader.markUsed();
            if(mediumDefinitions!=null)mediumDefinitions.markUsed();
            materials.markUsed();atmosphere.markUsed();
            tlas.markUsed();
        }
        @Override public void close() {
            if (closed) return; closed=true;

            compiledLights=null;
            if (tlas!=null) tlas.close();
            if (textures!=null) textures.close();
            if (materials!=null) materials.close();
            if (atmosphere!=null) atmosphere.close();
            for (var mesh:meshes.values()) mesh.close();
            for (var data:previousPositions) data.close();
            for(var data:attributes.values())data.close();
            if(sourceRecords!=null)sourceRecords.close();if(lightCells!=null)lightCells.close();if(sourceHeader!=null)sourceHeader.close();
            if(mediumDefinitions!=null)mediumDefinitions.close();compiledMedia=null;
            for (var buffer:buffers) buffer.close();
        }
    }
    @Override public void close() { if (current!=null) { current.close(); current=null; } acceleration.close(); uploads.close();textureImporter.close();materialCompiler.close();atmosphereCompiler.close(); }
}
