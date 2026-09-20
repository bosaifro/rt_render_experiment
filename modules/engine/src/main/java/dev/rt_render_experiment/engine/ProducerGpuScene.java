package dev.rt_render_experiment.engine;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import dev.rt_render_experiment.contract.*;
import dev.rt_render_experiment.engine.abi.R2Abi;
import dev.rt_render_experiment.vulkan.*;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;


public final class ProducerGpuScene implements AutoCloseable {
    private static final int DATA = VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
        | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    private final VulkanResources resources;
    private final R2ShaderPackage shaders;
    private final HostExecution.Capabilities capabilities;
    private final ProducerGeometryDecoder decoder;
    private final VulkanAccelerationStructures acceleration;
    private final VulkanUploads uploads;
    private final DefinitionBank materials, atmosphere;
    private final TextureImporter textureImporter;
    private final LightCompiler lightCompiler = new LightCompiler();
    private Snapshot current;
    private long generation;
    private int nextToken = 1;
    private boolean closed;

    public ProducerGpuScene(VulkanResources resources, HostExecution.Capabilities capabilities, R2ShaderPackage shaders) {
        if (resources.deviceIdentity() != capabilities.device()) throw new IllegalArgumentException("Foreign producer scene device");
        this.resources = resources; this.capabilities = capabilities; this.shaders = shaders;
        ProducerGeometryDecoder createdDecoder=null;
        VulkanAccelerationStructures createdAcceleration=null;
        VulkanUploads createdUploads=null;
        DefinitionBank createdMaterials=null,createdAtmosphere=null;
        TextureImporter createdImporter=null;
        try {
            createdDecoder=new ProducerGeometryDecoder(resources,shaders);
            createdAcceleration=new VulkanAccelerationStructures(resources);createdUploads=new VulkanUploads(resources);
            createdMaterials=new DefinitionBank(resources,shaders,DefinitionBank.Kind.MATERIALS);
            createdAtmosphere=new DefinitionBank(resources,shaders,DefinitionBank.Kind.ATMOSPHERE);
            createdImporter=new TextureImporter(resources,shaders);
        } catch(RuntimeException|Error failure) {
            if(createdImporter!=null)VulkanRetirement.suppress(failure,createdImporter::close);
            if(createdAtmosphere!=null)VulkanRetirement.suppress(failure,createdAtmosphere::close);
            if(createdMaterials!=null)VulkanRetirement.suppress(failure,createdMaterials::close);
            if(createdUploads!=null)VulkanRetirement.suppress(failure,createdUploads::close);
            if(createdAcceleration!=null)VulkanRetirement.suppress(failure,createdAcceleration::close);
            if(createdDecoder!=null)VulkanRetirement.suppress(failure,createdDecoder::close);
            throw failure;
        }
        decoder=createdDecoder;acceleration=createdAcceleration;uploads=createdUploads;materials=createdMaterials;
        atmosphere=createdAtmosphere;textureImporter=createdImporter;
    }

    public Prepared prepare(HostExecution.Window window, ProducerRegistry registry, SceneInputs.Origin origin,
                            List<? extends TextureInputs.Resource> textures, LightInputs.Publication lights, MediumInputs.Domain media) {
        if (closed || window.device()!=resources.deviceIdentity() || window.recording()!=resources.recording()
            || window.stage()!=HostExecution.Stage.SCENE_PREPARATION) throw new IllegalArgumentException("Invalid producer scene window");
        if (current!=null && current.world!=registry.world()) throw new IllegalArgumentException("Release the previous world before reentry");
        var geometry=registry.resident().stream().sorted(java.util.Comparator.comparing(ProducerResources.Geometry::key)).toList();
        var boundaries=new java.util.HashSet<Long>();
        for(var item:geometry)for(var primitive:item.metadata()) {
            var surface=primitive.surface();
            if(surface.boundary()!=SceneInputs.Boundary.UNQUALIFIED)boundaries.add(surface.medium());
            if(surface.coverage()==SceneInputs.Coverage.FILTER && item.participation().primaryVisible())
                throw new IllegalArgumentException("Filter primitives must retain their host primary draw");
        }
        var definitions=MediumDefinitions.prepare(media,boundaries,current==null?null:current.media);
        generation=Math.incrementExact(generation);uploads.beginFrame();acceleration.beginBatch();
        var result=new Prepared(window,registry,geometry,origin);
        var snapshot=result.snapshot;
        try {
            snapshot.media=definitions;
            if(definitions!=null && !definitions.domain.volumes().isEmpty())
                snapshot.mediumData=result.data(definitions.bytes(),current==null?null:current.mediumData,DATA);
            snapshot.materials=materials.prepare(current==null?null:current.materials);
            snapshot.atmosphere=atmosphere.prepare(current==null?null:current.atmosphere);
            var textureInputs=new ArrayList<TextureInputs.Resource>();textureInputs.add(SceneTextures.NEUTRAL);textureInputs.addAll(textures);
            snapshot.textures=current!=null && current.textures.matches(textureInputs)?current.textures.retain()
                :new SceneTextures(resources,textureInputs,current==null?null:current.textures,
                    capabilities.enabled().contains(HostExecution.Capability.SAMPLER_ANISOTROPY));
            long mediumAddress=snapshot.mediumData==null?0:snapshot.mediumData.buffer.view().address();
            int count=geometry.size();
            var records=bytes(Math.max(1,count)*R2Abi.GeometryRecord.SIZE);
            var instances=bytes(Math.max(1,count)*64);
            for(int slot=0;slot<count;slot++) {
                var input=geometry.get(slot);var before=current==null?null:current.meshes.get(input.key());
                var textureKeys=before!=null && before.input==input?before.textureKeys:ProducerSurfaceRecords.textureKeys(input);
                var state=ProducerSurfaceRecords.textureState(textureKeys,snapshot.textures);
                Mesh mesh;
                if(before!=null && before.matches(input,state,definitions,mediumAddress)) { mesh=before.retain();result.reusedMeshes++; }
                else if(before!=null && before.geometry.matches(input)) {
                    int token=nextToken;nextToken=Math.addExact(nextToken,input.metadata().size());
                    var surface=ProducerSurfaceRecords.encode(input,snapshot.textures,definitions,mediumAddress,token);
                    var replacement=result.data(surface,null,DATA);
                    mesh=new Mesh(input,before.geometry.retain(),replacement,textureKeys,state,definitions,mediumAddress);
                    result.reusedMeshes++;
                }
                else {
                    int token=nextToken;nextToken=Math.addExact(nextToken,input.metadata().size());
                    var surface=ProducerSurfaceRecords.encode(input,snapshot.textures,definitions,mediumAddress,token);
                    var decoded=decoder.prepare(window,input,surface);
                    try {
                        boolean opaque=input.metadata().stream().allMatch(p->R2Abi.automaticHitCoverage(p.surface().coverage().ordinal(),p.surface().doubleSided()));
                        var triangles=List.of(decoded.buildInput(opaque));
                        var blas=acceleration.allocateBottomLevel(triangles,false);
                        try { mesh=new Mesh(input,new Geometry(decoded,blas),null,textureKeys,state,definitions,mediumAddress);result.builds.add(acceleration.build(blas,triangles,false)); }
                        catch(RuntimeException|Error failure) { VulkanRetirement.suppress(failure,blas::close);throw failure; }
                    } catch(RuntimeException|Error failure) { VulkanRetirement.suppress(failure,decoded::close);throw failure; }
                    result.decodes.add(decoded);result.copiedSourceBytes+=decoded.copiedSourceBytes();
                }
                snapshot.meshes.put(input.key(),mesh);snapshot.inputs.put(input.key(),input);
                for(var p:input.metadata())if(p.surface().coverage()==SceneInputs.Coverage.FILTER)
                    snapshot.filters+=input.uniformPrimitives()?input.triangleCount():input.topology().trianglesPerPrimitive();
                boolean previousValid=before!=null && before.geometry==mesh.geometry;
                float[] transform=relative(input.current(),input.origin(),origin);
                float[] previous=previousValid?relative(input.previous(),input.origin(),origin):transform;
                int base=slot*R2Abi.GeometryRecord.SIZE;
                R2Abi.GeometryRecord.positions(records,base,mesh.geometry.decoded.positions().view().address());
                R2Abi.GeometryRecord.corners(records,base,mesh.geometry.decoded.corners().view().address());
                R2Abi.GeometryRecord.triangles(records,base,mesh.geometry.decoded.triangles().view().address());
                R2Abi.GeometryRecord.surfaces(records,base,mesh.surfaces().view().address());
                R2Abi.GeometryRecord.current(records,base,transform);R2Abi.GeometryRecord.inverse(records,base,inverse(transform));
                R2Abi.GeometryRecord.previous(records,base,previous);
                R2Abi.GeometryRecord.previousPositions(records,base,previousValid?mesh.geometry.decoded.positions().view().address():0);
                R2Abi.GeometryRecord.identity(records,base,key(input.key()));
                R2Abi.GeometryRecord.revisions(records,base,(int)input.generation(),(int)input.revision(),(int)input.materialRevision(),(int)input.materialRevision());
                R2Abi.GeometryRecord.state(records,base,input.motion()==SceneInputs.Motion.STATIC?0:input.motion()==SceneInputs.Motion.DEFORMING?3:1,previousValid?1:0,input.triangleCount(),0);
                for(int i=0;i<12;i++)instances.putFloat(slot*64+i*4,transform[i]);
                int mask=input.participation().rayMask();if(!input.participation().primaryVisible())mask&=~Participation.CAMERA_PATH;
                instances.putInt(slot*64+48,slot|(mask<<24));
                instances.putInt(slot*64+52,KHRAccelerationStructure.VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR<<24);
                instances.putLong(slot*64+56,mesh.geometry.blas.address());
            }
            snapshot.records=result.data(records,current==null?null:current.records,DATA);
            if(current!=null && current.instances.content.mismatch(instances)==-1) {
                snapshot.instances=current.instances.retain();snapshot.top=current.top.retain();
            } else {
                snapshot.instances=result.data(instances,null,DATA|KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR);
                result.topBuild=acceleration.topLevel(snapshot.instances.buffer.view(),count,null);
                snapshot.top=new Top(result.topBuild.target());
            }

            var prior=current==null?null:current.lights;
            snapshot.lights=lightCompiler.prepare(registry.world(),List.of(),lights.sources(),origin,prior).compiled();
            snapshot.sources=result.data(snapshot.lights.sources(),current==null?null:current.sources,DATA);
            snapshot.cells=result.data(snapshot.lights.cells(),current==null?null:current.cells,DATA);
            var header=bytes(R2Abi.SourceTableRecord.SIZE);
            R2Abi.SourceTableRecord.sources(header,0,snapshot.sources.buffer.view().address());
            R2Abi.SourceTableRecord.cells(header,0,snapshot.cells.buffer.view().address());
            R2Abi.SourceTableRecord.counts(header,0,snapshot.lights.worldCount(),snapshot.lights.dynamicCount(),snapshot.lights.attachedCount(),snapshot.lights.cellCount());
            snapshot.sourceHeader=result.data(header,current==null?null:current.sourceHeader,DATA);
            var staticGeometry=geometry.stream().filter(g->g.motion()==SceneInputs.Motion.STATIC).toList();
            var worldTextures=new java.util.HashSet<SceneInputs.Key>();
            for(var item:staticGeometry)worldTextures.addAll(snapshot.meshes.get(item.key()).textureKeys);
            snapshot.lightDomain=new WorldLightDomain(registry.world(),List.of(staticGeometry,lights.sources().stream().filter(l->l.stratum()==LightInputs.Stratum.WORLD).toList(),
                textureInputs.stream().filter(t->worldTextures.contains(t.key())).map(t->List.of(t.key(),t.revision(),t.encoding(),t.sampling())).toList(),media==null?List.of():media));
            return result;
        } catch(RuntimeException|Error failure) { VulkanRetirement.suppress(failure,result::close);throw failure; }
    }

    private static ByteBuffer bytes(int size) { return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN); }
    private static int[] key(SceneInputs.Key key) { return new int[]{(int)key.high(),(int)(key.high()>>>32),(int)key.low(),(int)(key.low()>>>32)}; }
    private static float[] relative(SceneInputs.Transform transform,SceneInputs.Origin origin,SceneInputs.Origin reference) {
        var rows=transform.rows();rows[3]+=(float)(origin.x()-reference.x());rows[7]+=(float)(origin.y()-reference.y());rows[11]+=(float)(origin.z()-reference.z());return rows;
    }
    private static float[] inverse(float[] r) {
        var m=new Matrix4f(r[0],r[4],r[8],0,r[1],r[5],r[9],0,r[2],r[6],r[10],0,r[3],r[7],r[11],1).invert();
        return new float[]{m.m00(),m.m10(),m.m20(),m.m30(),m.m01(),m.m11(),m.m21(),m.m31(),m.m02(),m.m12(),m.m22(),m.m32()};
    }
    private static final class Data implements AutoCloseable {
        final ByteBuffer content;final VulkanResources.Buffer buffer;int owners=1;
        Data(ByteBuffer content,VulkanResources.Buffer buffer) { this.content=bytes(content.remaining()).put(content.duplicate()).flip().asReadOnlyBuffer();this.buffer=buffer; }
        Data retain() { if(owners<=0)throw new IllegalStateException("Retired scene data");owners++;return this; }
        @Override public void close() { if(--owners==0)buffer.close();else if(owners<0)throw new IllegalStateException("Double scene release"); }
    }
    private static final class Top implements AutoCloseable {
        final VulkanResources.AccelerationStructure value;int owners=1;
        Top(VulkanResources.AccelerationStructure value) { this.value=value; }
        Top retain() { if(owners<=0)throw new IllegalStateException("Retired top level");owners++;return this; }
        @Override public void close() { if(--owners==0)value.close();else if(owners<0)throw new IllegalStateException("Double top-level release"); }
    }
    private static final class Geometry implements AutoCloseable {
        final ProducerGeometryDecoder.Decoded decoded;final VulkanAccelerationStructures.BottomLevel blas;
        private int owners=1;
        Geometry(ProducerGeometryDecoder.Decoded decoded,VulkanAccelerationStructures.BottomLevel blas) { this.decoded=decoded;this.blas=blas; }
        Geometry retain() { if(owners<=0)throw new IllegalStateException("Retired geometry");owners++;return this; }
        boolean matches(ProducerResources.Geometry next) {
            var input=decoded.input();
            if(input.generation()!=next.generation() || input.revision()!=next.revision() || input.layout()!=next.layout()
                || input.topology()!=next.topology() || input.tintMode()!=next.tintMode() || input.vertexCount()!=next.vertexCount()
                || input.uniformPrimitives()!=next.uniformPrimitives())return false;
            for(int i=0;i<input.metadata().size();i++) {
                var a=input.metadata().get(i);var b=next.metadata().get(i);
                if(a.active()!=b.active() || !a.tints().equals(b.tints()) || !a.part().equals(b.part()) || a.ordinal()!=b.ordinal()
                    || a.surface().material()!=b.surface().material() || a.surface().properties()!=b.surface().properties()
                    || a.surface().coverage()!=b.surface().coverage() || a.surface().doubleSided()!=b.surface().doubleSided())return false;
            }
            return true;
        }
        void markUsed() { decoded.markUsed();blas.markUsed(); }
        @Override public void close() {
            if(--owners==0) { Throwable failure=VulkanRetirement.attempt(null,blas::close);failure=VulkanRetirement.attempt(failure,decoded::close);VulkanRetirement.finish(failure); }
            else if(owners<0)throw new IllegalStateException("Double geometry release");
        }
    }
    private static final class Mesh implements AutoCloseable {
        final ProducerResources.Geometry input;final Geometry geometry;final Data surfaceOverride;
        final List<SceneInputs.Key> textureKeys;
        final List<SceneTextures.SurfaceState> textures;final MediumDefinitions media;final long mediumAddress;int owners=1;
        Mesh(ProducerResources.Geometry input,Geometry geometry,Data surfaceOverride,List<SceneInputs.Key> textureKeys,List<SceneTextures.SurfaceState> textures,MediumDefinitions media,long mediumAddress) {
            this.input=input;this.geometry=geometry;this.surfaceOverride=surfaceOverride;this.textureKeys=textureKeys;this.textures=textures;this.media=media;this.mediumAddress=mediumAddress;
        }
        boolean matches(ProducerResources.Geometry next,List<SceneTextures.SurfaceState> textures,MediumDefinitions media,long address) {
            return input.generation()==next.generation() && input.revision()==next.revision() && input.materialRevision()==next.materialRevision()
                && input.layout()==next.layout() && input.topology()==next.topology() && input.tintMode()==next.tintMode() && input.vertexCount()==next.vertexCount()
                && this.textures.equals(textures) && this.media==media && mediumAddress==address;
        }
        Mesh retain() { if(owners<=0)throw new IllegalStateException("Retired mesh");owners++;return this; }
        VulkanResources.Buffer surfaces() { return surfaceOverride==null?geometry.decoded.surfaces():surfaceOverride.buffer; }
        void markUsed() { geometry.markUsed();surfaces().markUsed(); }
        @Override public void close() {
            if(--owners==0) { Throwable failure=VulkanRetirement.attempt(null,geometry::close);
                if(surfaceOverride!=null)failure=VulkanRetirement.attempt(failure,surfaceOverride::close);VulkanRetirement.finish(failure); }
            else if(owners<0)throw new IllegalStateException("Double mesh release");
        }
    }
    public final class Prepared implements AutoCloseable {
        private final long token=generation;
        private final HostExecution.Window window;
        private final ProducerRegistry registry;
        private final ProducerRegistry.Changes changes;
        private final Snapshot snapshot;
        private final List<VulkanUploads.Transfer> transfers=new ArrayList<>();
        private final List<ProducerGeometryDecoder.Decoded> decodes=new ArrayList<>();
        private final List<VulkanAccelerationStructures.Build> builds=new ArrayList<>();
        private VulkanAccelerationStructures.Build topBuild;
        private int reusedMeshes;
        private long copiedSourceBytes;
        private boolean recorded,committed,closed;
        private Prepared(HostExecution.Window window,ProducerRegistry registry,List<ProducerResources.Geometry> geometry,SceneInputs.Origin origin) {
            this.window=window;this.registry=registry;changes=registry.pending();snapshot=new Snapshot(registry.world(),registry.revision(),origin);
        }
        private Data data(ByteBuffer bytes,Data before,int usage) {
            if(before!=null && before.content.mismatch(bytes)==-1)return before.retain();
            var buffer=resources.allocateDevice(bytes.remaining(),usage);
            try { transfers.add(uploads.stage(buffer,0,bytes));return new Data(bytes,buffer); }
            catch(RuntimeException|Error failure) { VulkanRetirement.suppress(failure,buffer::close);throw failure; }
        }
        public void record(VkCommandBuffer command) {
            if(closed || recorded || token!=generation || window.recording()!=resources.recording())throw new IllegalStateException("Expired producer scene preparation");
            materials.record(command,snapshot.materials);atmosphere.record(command,snapshot.atmosphere);
            if(!snapshot.textures.recorded())snapshot.textures.record(window,command,textureImporter);
            for(var decode:decodes)decode.record(command);
            snapshot.markUsed();uploads.recordBatch(command,transfers);
            try(var stack=MemoryStack.stackPush()) {
                VulkanBarriers.record(command,stack,VulkanBarriers.PRIOR_ACCESS_TO_AS_BUILD);
                for(var build:builds)build.record(command);
                VulkanBarriers.record(command,stack,VulkanBarriers.BLAS_TO_TLAS);
                if(topBuild!=null)topBuild.record(command);
                VulkanBarriers.record(command,stack,new VulkanBarriers.Scope(VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,VK13.VK_ACCESS_2_MEMORY_WRITE_BIT,
                    VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,VK13.VK_ACCESS_2_SHADER_READ_BIT|KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR));
            }
            recorded=true;
        }
        public Snapshot scene() { if(closed || !recorded)throw new IllegalStateException("Producer scene has not been recorded");return snapshot; }
        public int builtMeshes() { return builds.size(); }
        public int reusedMeshes() { return reusedMeshes; }
        public long copiedSourceBytes() { return copiedSourceBytes; }
        public void submitted(long serial) {
            if(closed || !recorded || committed || serial!=window.recording())throw new IllegalStateException("Scene lacks its exact submission");
            for(var build:builds)build.submitted(serial);
            snapshot.materials.submitted(serial);snapshot.atmosphere.submitted(serial);snapshot.textures.submitted(serial);
            var old=current;current=snapshot;committed=true;registry.acknowledge(changes);if(old!=null)old.close();
        }
        @Override public void close() { if(!closed) { closed=true;if(!committed)snapshot.close(); } }
    }
    public final class Snapshot implements R2Scene,AutoCloseable {
        private final long world,serial;private final SceneInputs.Origin origin;
        private final Map<SceneInputs.Key,Mesh> meshes=new LinkedHashMap<>();
        private final Map<SceneInputs.Key,ProducerResources.Geometry> inputs=new LinkedHashMap<>();
        private Data records,instances,sources,cells,sourceHeader,mediumData;
        private Top top;private SceneTextures textures;
        private DefinitionBank.Version materials,atmosphere;
        private LightCompiler.Compiled lights;private WorldLightDomain lightDomain;private MediumDefinitions media;
        private int filters;private boolean closed;
        private Snapshot(long world,long serial,SceneInputs.Origin origin) { this.world=world;this.serial=serial;this.origin=origin; }
        private void requireOpen() { if(closed)throw new IllegalStateException("Retired producer scene"); }
        @Override public long serial() { return serial; }
        @Override public String shaderIdentity() { return shaders.executionIdentity(); }
        @Override public SceneInputs.Origin origin() { return origin; }
        @Override public long recordsAddress() { requireOpen();return records.buffer.view().address(); }
        @Override public long sourcesAddress() { requireOpen();return sourceHeader.buffer.view().address(); }
        @Override public int sourceCount() { requireOpen();return Math.addExact(lights.worldCount(),lights.dynamicCount()); }
        @Override public long materialsAddress() { requireOpen();return materials.buffer().view().address(); }
        @Override public long atmosphereAddress() { requireOpen();return atmosphere.buffer().view().address(); }
        @Override public long accelerationStructure() { requireOpen();return top.value.handle(); }
        @Override public int filterPrimitives() { requireOpen();return filters; }
        @Override public WorldLightDomain worldLightDomain() { requireOpen();return lightDomain; }
        @Override public int cloudSlot(RenderFrame.Cloud cloud) {
            requireOpen();if(textures.width(cloud.resource())!=cloud.width() || textures.height(cloud.resource())!=cloud.height())throw new IllegalArgumentException("Cloud field extent mismatch");
            return textures.slot(cloud.resource());
        }
        @Override public void bindTextures(VkCommandBuffer command,int bindPoint,long layout) { requireOpen();textures.bind(command,bindPoint,layout); }
        public void finishTextureReads(VkCommandBuffer command) { requireOpen();textures.finishReads(command); }
        public boolean represents(ProducerResources.Geometry geometry) { requireOpen();return inputs.get(geometry.key())==geometry; }
        @Override public void requireInitialMedia(RenderFrame frame) {
            requireOpen();
            if(frame.clippedMediumPrimitives()!=0)throw new IllegalArgumentException("Producer must publish a qualified near-clip interface prefix");
            if(frame.initialMedia().isPresent()) {
                var initial=frame.initialMedia().orElseThrow();
                if(initial.world()!=world || initial.sceneRevision()!=serial || !initial.position().equals(frame.eye()))
                    throw new IllegalArgumentException("Producer origin classification does not match the scene and eye");
                float maximum=0;var jitter=frame.projectionJitter();
                for(float x:new float[]{-1,1})for(float y:new float[]{-1,1})maximum=Math.max(maximum,frame.camera().ray(x-jitter.x(),y-jitter.y()).minimum());
                if(initial.clearance()<=maximum*(1+1e-4f)+1e-6f)throw new IllegalArgumentException("Producer origin clearance does not cover near clipping");
            }
            if(media!=null)media.requireFrameOrigin(frame);
        }
        @Override public void markUsed() {
            requireOpen();for(var mesh:meshes.values())mesh.markUsed();
            for(var item:new Data[]{records,instances,sources,cells,sourceHeader,mediumData})if(item!=null)item.buffer.markUsed();
            if(materials!=null)materials.markUsed();if(atmosphere!=null)atmosphere.markUsed();if(top!=null)top.value.markUsed();
        }
        @Override public void close() {
            if(closed)return;closed=true;Throwable failure=null;
            for(var mesh:meshes.values())failure=VulkanRetirement.attempt(failure,mesh::close);
            for(var item:new Data[]{records,instances,sources,cells,sourceHeader,mediumData})if(item!=null)failure=VulkanRetirement.attempt(failure,item::close);
            if(top!=null)failure=VulkanRetirement.attempt(failure,top::close);
            if(textures!=null)failure=VulkanRetirement.attempt(failure,textures::close);
            if(materials!=null)failure=VulkanRetirement.attempt(failure,materials::close);
            if(atmosphere!=null)failure=VulkanRetirement.attempt(failure,atmosphere::close);
            meshes.clear();inputs.clear();lights=null;lightDomain=null;
            VulkanRetirement.finish(failure);
        }
    }
    @Override public void close() {
        if(closed)return;closed=true;Throwable failure=null;
        if(current!=null)failure=VulkanRetirement.attempt(failure,current::close);
        current=null;
        failure=VulkanRetirement.attempt(failure,decoder::close);failure=VulkanRetirement.attempt(failure,acceleration::close);
        failure=VulkanRetirement.attempt(failure,uploads::close);failure=VulkanRetirement.attempt(failure,materials::close);
        failure=VulkanRetirement.attempt(failure,atmosphere::close);failure=VulkanRetirement.attempt(failure,textureImporter::close);
        VulkanRetirement.finish(failure);
    }
}
