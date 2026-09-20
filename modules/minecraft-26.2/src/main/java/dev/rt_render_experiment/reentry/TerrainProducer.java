package dev.rt_render_experiment.reentry;

import com.mojang.blaze3d.buffers.GpuBuffer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import dev.rt_render_experiment.contract.*;
import dev.rt_render_experiment.engine.ProducerRegistry;
import dev.rt_render_experiment.integration.minecraft.MinecraftSectionSemantics;
import dev.rt_render_experiment.runtime.ProducerMaterials;
import net.minecraft.client.renderer.chunk.*;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;


public final class TerrainProducer {
    public static final SceneInputs.Key ATLAS=new SceneInputs.Key(0x41544c4153L,1);
    public record Range(GpuBuffer buffer,long offset,int indices) {}
    private record Publication(int section,long node,long generation,SectionMesh mesh) {}
    private record Event(Publication publication,boolean removed) {}
    private static final AtomicLong WORLDS=new AtomicLong(),GENERATIONS=new AtomicLong();
    private static final Map<SectionRenderDispatcher,State> STATES=new ConcurrentHashMap<>();
    private static final ThreadLocal<Map<Range,SceneInputs.Key>> DRAWS=ThreadLocal.withInitial(HashMap::new);
    private TerrainProducer() {}
    public static State state(SectionRenderDispatcher dispatcher) { return STATES.computeIfAbsent(dispatcher,State::new); }
    public static void published(SectionRenderDispatcher dispatcher,SectionRenderDispatcher.RenderSection section,SectionMesh mesh) {
        if(!VulkanAdmission.captureEnabled())return;
        if(!(mesh instanceof SemanticMesh))return;
        var state=state(dispatcher);if(state.closed)return;
        var publication=new Publication(section.index,section.getSectionNode(),GENERATIONS.incrementAndGet(),mesh);
        state.bindings.put(mesh,publication);state.events.add(new Event(publication,false));
    }
    public static void released(SectionRenderDispatcher dispatcher,SectionMesh mesh) {
        var state=STATES.get(dispatcher);if(state==null)return;
        var publication=state.bindings.remove(mesh);if(publication!=null)state.events.add(new Event(publication,true));
    }
    public static void closed(SectionRenderDispatcher dispatcher) {
        var state=STATES.remove(dispatcher);if(state!=null){state.closed=true;state.events.clear();state.bindings.clear();}
    }
    public static void beginDraws() { DRAWS.get().clear(); }
    public static Map<Range,SceneInputs.Key> draws() { return Map.copyOf(DRAWS.get()); }
    public static void captureDraw(SectionRenderDispatcher dispatcher,SectionMesh mesh,ChunkSectionLayer layer,SectionRenderDispatcher.RenderSectionBufferSlice slice) {
        var state=STATES.get(dispatcher);if(state==null || slice==null)return;
        var publication=state.bindings.get(mesh);var draw=mesh.getSectionDraw(layer);
        if(publication!=null && draw!=null)DRAWS.get().put(new Range(slice.vertexBuffer(),slice.vertexBufferOffset(),draw.indexCount()),state.key(publication,layer));
    }
    public static final class State implements ProducerFeed {
        public final ProducerRegistry registry=new ProducerRegistry(WORLDS.incrementAndGet());
        private final SectionRenderDispatcher dispatcher;
        private final Map<SectionMesh,Publication> bindings=new ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<Event> events=new ConcurrentLinkedQueue<>();
        private final Map<SceneInputs.Key,ProducerResources.Geometry> inputs=new HashMap<>();
        private final Map<SceneInputs.Key,Publication> sources=new HashMap<>();
        private final Map<Integer,List<LightInputs.Source>> lights=new HashMap<>();
        private final Map<Integer,Long> generations=new HashMap<>();
        private long lightRevision;
        private LightInputs.Publication lightPublication=LightInputs.Publication.empty();
        private volatile boolean closed;
        private int publications,withdrawals,rejected;
        private State(SectionRenderDispatcher dispatcher) { this.dispatcher=dispatcher; }
        @Override public ProducerRegistry registry() { return registry; }
        private SceneInputs.Key key(Publication publication,ChunkSectionLayer layer) { return new SceneInputs.Key(registry.world(),((long)publication.section()<<4)|layer.ordinal()); }
        public boolean closed() { return closed; }
        public ProducerResources.Geometry input(SceneInputs.Key key) { return registry.get(key); }
        public List<HostExecution.BufferGrant> drain(HostDevice host) {
            Event event;
            while((event=events.poll())!=null) {
                var p=event.publication;
                if(event.removed) {
                    for(var layer:ChunkSectionLayer.values()) {
                        var key=key(p,layer);var input=inputs.get(key);
                        if(input!=null && sources.get(key)==p) {
                            registry.withdraw(new ProducerResources.Withdrawal(key,registry.world(),input.generation(),input.revision()));inputs.remove(key);sources.remove(key);withdrawals++;
                        }
                    }
                    if(generations.remove(p.section,p.generation))lights.remove(p.section);
                    continue;
                }
                if(generations.getOrDefault(p.section,0L)>p.generation)continue;
                var metadata=((SemanticMesh)p.mesh).rt_render_experiment$semantics();
                var origin=new SceneInputs.Origin(SectionPos.sectionToBlockCoord(SectionPos.x(p.node)),SectionPos.sectionToBlockCoord(SectionPos.y(p.node)),SectionPos.sectionToBlockCoord(SectionPos.z(p.node)));
                var next=new EnumMap<ChunkSectionLayer,ProducerResources.Geometry>(ChunkSectionLayer.class);
                try {
                    for(var layer:ChunkSectionLayer.values()) {
                        var draw=p.mesh.getSectionDraw(layer);if(draw==null)continue;
                        int quads=metadata.primitiveCount(layer);
                        if(draw.indexCount()!=Math.multiplyExact(quads,6) || quads==0)throw new IllegalArgumentException("Producer semantic count mismatch");
                        var slice=dispatcher.getRenderSectionSlice(p.mesh,layer);if(slice==null)throw new IllegalArgumentException("Uncommitted section range");
                        var key=key(p,layer);var facts=new ArrayList<ProducerResources.Primitive>(quads);
                        for(int i=0;i<quads;i++) {
                            var fact=metadata.semantic(layer,i);var material=fact.material();
                            String block=BuiltInRegistries.BLOCK.getKey(Block.stateById(fact.blockStateId()).getBlock()).toString();
                            int id=material instanceof MinecraftSectionSemantics.BlockMaterial model?ProducerMaterials.block(block,model.sprite().toString())
                                :ProducerMaterials.fluid(BuiltInRegistries.FLUID.getKey(Fluid.FLUID_STATE_REGISTRY.byId(((MinecraftSectionSemantics.FluidMaterial)material).fluidStateId()).getType()).toString());
                            var part=new SceneInputs.Key(key.high(),(key.low()<<32)|Integer.toUnsignedLong(i));
                            var coverage=layer==ChunkSectionLayer.SOLID?SceneInputs.Coverage.OPAQUE:layer==ChunkSectionLayer.CUTOUT?SceneInputs.Coverage.CUTOUT:SceneInputs.Coverage.DIELECTRIC;
                            var surface=new SceneInputs.Surface(part,id,0,ATLAS,new SceneInputs.Key(0,0),coverage,0.5f,true,id==8?1:0,0,0,
                                MaterialInputs.Layers.plain(ATLAS),layer==ChunkSectionLayer.TRANSLUCENT?SceneInputs.HostOcclusion.PASS:SceneInputs.HostOcclusion.BLOCK);
                            facts.add(new ProducerResources.Primitive(part,i,surface,metadata.albedoTint(layer,i)));
                        }
                        var view=host.buffer(slice.vertexBuffer(),slice.vertexBufferOffset(),(long)quads*4*28);
                        var grant=new HostExecution.BufferGrant(view,1,Set.of(HostExecution.Access.TRANSFER_READ),host.recording());
                        next.put(layer,new ProducerResources.Geometry(key,registry.world(),p.generation,1,1,1,grant,ProducerResources.Layout.BLOCK28,
                            ProducerResources.Topology.QUADS_012_230,quads*4,origin,SceneInputs.Transform.identity(),SceneInputs.Transform.identity(),new Participation(Participation.WORLD,true),facts));
                    }
                } catch(IllegalArgumentException|IllegalStateException failure) {
                    rejected++;
                    RtRenderExperimentClient.LOGGER.warn("RT_RENDER_EXPERIMENT_PRODUCER_REFUSED section={} reason={}",p.section,failure.getMessage());
                    continue;
                }
                for(var geometry:next.values()) { registry.publish(geometry);inputs.put(geometry.key(),geometry);sources.put(geometry.key(),p);publications++; }
                var sources=new ArrayList<LightInputs.Source>();
                for(var light:metadata.lightSources()) {
                    int packed=light.packedPositionEmission();
                    var position=new SceneInputs.Origin(origin.x()+(packed&15)+0.5,origin.y()+((packed>>>4)&15)+0.5,origin.z()+((packed>>>8)&15)+0.5);
                    String block=BuiltInRegistries.BLOCK.getKey(Block.stateById(light.blockStateId()).getBlock()).toString();
                    sources.add(new LightInputs.Point(new SceneInputs.Key(registry.world()^0x4c49474854L,((long)p.section<<12)|(packed&4095)),p.generation,
                        new LightInputs.Emission(ProducerMaterials.block(block),((packed>>>12)&15)/15f,new SceneInputs.Vec3(0,0,0)),position,0,LightInputs.Stratum.WORLD,position,0.12f));
                }
                lights.put(p.section,List.copyOf(sources));generations.put(p.section,p.generation);
            }
            if(lightRevision!=registry.revision()) {
                lightRevision=registry.revision();lightPublication=new LightInputs.Publication(lightRevision,lights.values().stream().flatMap(List::stream).toList());
            }
            var grants=new ArrayList<HostExecution.BufferGrant>();
            for(var geometry:registry.pending().published()) {
                if(geometry.vertices().validThroughRecording()!=host.recording()) {
                    var source=sources.get(geometry.key());if(source==null)continue;
                    var layer=ChunkSectionLayer.values()[(int)(geometry.key().low()&15)];
                    var slice=dispatcher.getRenderSectionSlice(source.mesh,layer);
                    if(slice==null) { registry.withdraw(new ProducerResources.Withdrawal(geometry.key(),registry.world(),geometry.generation(),geometry.revision()));inputs.remove(geometry.key());sources.remove(geometry.key());continue; }
                    var view=host.buffer(slice.vertexBuffer(),slice.vertexBufferOffset(),(long)geometry.vertexCount()*geometry.layout().stride());
                    ProducerResources.Geometry renewed;
                    if(view.equals(geometry.vertices().view()))renewed=registry.renew(geometry,new HostExecution.BufferGrant(view,geometry.revision(),geometry.vertices().allowed(),host.recording()));
                    else {
                        var grant=new HostExecution.BufferGrant(view,geometry.revision(),geometry.vertices().allowed(),host.recording());
                        renewed=new ProducerResources.Geometry(geometry.key(),registry.world(),GENERATIONS.incrementAndGet(),geometry.revision(),geometry.transformRevision(),geometry.materialRevision(),grant,
                            geometry.layout(),geometry.topology(),geometry.tintMode(),geometry.motion(),geometry.vertexCount(),geometry.origin(),geometry.current(),geometry.previous(),geometry.participation(),geometry.primitives());
                        registry.publish(renewed);
                    }
                    inputs.put(renewed.key(),renewed);grants.add(renewed.vertices());
                } else grants.add(geometry.vertices());
            }
            return List.copyOf(grants);
        }
        public LightInputs.Publication lights() { return lightPublication; }
        public String statistics() { return "publications="+publications+" withdrawals="+withdrawals+" refused="+rejected+" resident="+inputs.size(); }
    }
}
