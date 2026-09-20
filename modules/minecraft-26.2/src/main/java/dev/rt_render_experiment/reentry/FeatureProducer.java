package dev.rt_render_experiment.reentry;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import dev.rt_render_experiment.contract.*;
import dev.rt_render_experiment.engine.ProducerRegistry;
import dev.rt_render_experiment.reentry.mixin.StagedDrawAccessor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;


public final class FeatureProducer {
    private record Draw(StagedVertexBuffer buffer,StagedVertexBuffer.Draw draw,PreparedRenderType type,boolean primary,int kind,int stride) {}
    private record QuadFact(int material,int tint) {}
    public record Published(List<TextureInputs.Resource> textures,Map<StagedVertexBuffer.ExecuteInfo,SceneInputs.Key> draws) {
        public Published { textures=List.copyOf(textures);draws=Map.copyOf(draws); }
    }
    private static final ThreadLocal<List<Draw>> BUILDING=new ThreadLocal<>();
    private static final ThreadLocal<Boolean> MODEL=ThreadLocal.withInitial(()->false);
    private static final ThreadLocal<Integer> KIND=ThreadLocal.withInitial(()->0);
    private static final ThreadLocal<StagedVertexBuffer.Draw> CURRENT_DRAW=new ThreadLocal<>();
    private static final ThreadLocal<Map<StagedVertexBuffer.Draw,List<QuadFact>>> MATERIALS=new ThreadLocal<>();
    private static Map<StagedVertexBuffer.Draw,List<QuadFact>> readyMaterials=Map.of();
    private static final ThreadLocal<String> BLOCK=new ThreadLocal<>();
    private static final ThreadLocal<Boolean> PHYSICAL=ThreadLocal.withInitial(()->false);
    private static final ThreadLocal<StagedVertexBuffer> BUFFER=new ThreadLocal<>();
    private static List<Draw> ready=List.of();
    private static long generation,world;
    private static Set<SceneInputs.Key> previous=Set.of();
    private FeatureProducer() {}
    public static void begin(FeatureFrameContext context) { if(VulkanAdmission.captureEnabled())BUILDING.set(new ArrayList<>());else BUILDING.remove();BUFFER.set(context.stagedVertexBuffer());MATERIALS.set(new java.util.IdentityHashMap<>());ready=List.of();readyMaterials=Map.of(); }
    public static void end(boolean complete) { var current=BUILDING.get();if(complete && current!=null){ready=List.copyOf(current);readyMaterials=MATERIALS.get();}BUILDING.remove();MODEL.remove();KIND.remove();CURRENT_DRAW.remove();MATERIALS.remove();BLOCK.remove();BUFFER.remove();PHYSICAL.remove(); }
    public static boolean model(boolean value) { boolean previous=MODEL.get();MODEL.set(value);return previous; }
    public static int kind(int value) { int previous=KIND.get();KIND.set(value);MODEL.set(value==1);return previous; }
    public static void currentDraw(StagedVertexBuffer.Draw draw) { CURRENT_DRAW.set(draw); }
    public static String block(String name) { String before=BLOCK.get();if(name==null)BLOCK.remove();else BLOCK.set(name);return before; }
    public static void quad(net.minecraft.client.resources.model.geometry.BakedQuad quad,com.mojang.blaze3d.vertex.QuadInstance instance) {
        var draw=CURRENT_DRAW.get();var materials=MATERIALS.get();if(draw==null || materials==null || KIND.get()<2)return;
        String sprite=quad.materialInfo().sprite().contents().name().toString();String block=BLOCK.get();
        int material=block==null?dev.rt_render_experiment.runtime.ProducerMaterials.sprite(sprite):dev.rt_render_experiment.runtime.ProducerMaterials.block(block,sprite);
        materials.computeIfAbsent(draw,ignored->new ArrayList<>()).add(new QuadFact(material,KIND.get()==3?((QuadTint)instance).rt_render_experiment$albedoTint():-1));
    }
    public static boolean physical(boolean value) { boolean previous=PHYSICAL.get();PHYSICAL.set(value);return previous; }
    public static boolean accepts(RenderType type) {
        var p=type.pipeline();
        return !type.isOutline() && type.format().getVertexSize()==36 && type.primitiveTopology()==PrimitiveTopology.QUADS
            && (p==RenderPipelines.ENTITY_SOLID || p==RenderPipelines.ENTITY_CUTOUT || p==RenderPipelines.ENTITY_CUTOUT_CULL
            || p==RenderPipelines.ARMOR_CUTOUT_NO_CULL || p==RenderPipelines.ENTITY_SOLID_Z_OFFSET_FORWARD);
    }
    public static com.mojang.blaze3d.vertex.VertexConsumer physicalBuffer(RenderType type) {
        if(!PHYSICAL.get())return null;
        var buffer=java.util.Objects.requireNonNull(BUFFER.get());var draw=buffer.appendDraw(type.format(),type.primitiveTopology());
        associate(buffer,draw,type,type.prepare());return buffer.getVertexBuilder(draw);
    }
    public static void associate(StagedVertexBuffer buffer,StagedVertexBuffer.Draw draw,RenderType type,PreparedRenderType prepared) {
        var captures=BUILDING.get();
        int kind=KIND.get();var p=prepared.pipeline();int stride=type.format().getVertexSize();
        boolean accepted=kind==1?accepts(type):kind==2?(p==RenderPipelines.ITEM_CUTOUT || p==RenderPipelines.ITEM_TRANSLUCENT)
            :kind==3?(p==RenderPipelines.SOLID_BLOCK || p==RenderPipelines.CUTOUT_BLOCK || p==RenderPipelines.TRANSLUCENT_BLOCK):false;
        if(captures==null || !accepted || type.isOutline() || type.primitiveTopology()!=PrimitiveTopology.QUADS || stride!=28 && stride!=36)return;
        if(captures.stream().noneMatch(d->d.draw==draw))captures.add(new Draw(buffer,draw,prepared,!PHYSICAL.get(),kind,stride));
    }
    public static Published publish(ProducerRegistry registry,HostDevice host,SceneInputs.Origin origin) {
        if(world!=registry.world()) { world=registry.world();previous=Set.of(); }
        long revision=++generation;
        var textures=new LinkedHashMap<SceneInputs.Key,TextureInputs.Resource>();var represented=new HashMap<StagedVertexBuffer.ExecuteInfo,SceneInputs.Key>();
        var current=new HashSet<SceneInputs.Key>();int index=0;
        for(var draw:ready) {
            var info=draw.buffer.getExecuteInfo(draw.draw);if(info==null)continue;
            int vertices=((StagedDrawAccessor)draw.draw).rt_render_experiment$vertexCount();if(vertices<=0 || vertices%4!=0)continue;
            var quadFacts=readyMaterials.get(draw.draw);
            if(draw.kind!=1 && (quadFacts==null || quadFacts.size()!=vertices/4))continue;
            var base=draw.type.textures().stream().filter(t->t.name().equals("Sampler0")).findFirst().orElse(null);if(base==null)continue;
            var color=texture(host,base);textures.put(color.key(),color);
            var overlayBinding=draw.type.textures().stream().filter(t->t.name().equals("Sampler1")).findFirst().orElse(null);
            var overlay=new SceneInputs.Key(0,0);
            if(overlayBinding!=null) { var value=texture(host,overlayBinding);overlay=value.key();textures.put(value.key(),value); }
            var key=new SceneInputs.Key(world^0x46454154555245L,index++);
            var p=draw.type.pipeline();var coverage=p==RenderPipelines.ENTITY_SOLID || p==RenderPipelines.ENTITY_SOLID_Z_OFFSET_FORWARD || p==RenderPipelines.SOLID_BLOCK?SceneInputs.Coverage.OPAQUE
                :p==RenderPipelines.TRANSLUCENT_BLOCK?SceneInputs.Coverage.DIELECTRIC:p==RenderPipelines.ITEM_TRANSLUCENT?SceneInputs.Coverage.FILTER:SceneInputs.Coverage.CUTOUT;
            var layers=new MaterialInputs.Layers(color.key(),true,overlay,MaterialInputs.Emission.NONE,new SceneInputs.Vec3(0,0,0),draw.kind==1,false,
                MaterialInputs.Sampling.TEXTURE_SAMPLER,TextureInputs.Encoding.SRGB,TextureInputs.Encoding.SRGB);
            int material=p==RenderPipelines.ARMOR_CUTOUT_NO_CULL?GpuLayouts.MATERIAL_ARMOR_METAL:GpuLayouts.MATERIAL_SKIN_CLOTH;
            var surface=new SceneInputs.Surface(key,material,0,color.key(),new SceneInputs.Key(0,0),coverage,draw.kind==3?0.5f:0.1f,!p.isCull(),0,0,0,layers,SceneInputs.HostOcclusion.BLOCK);
            List<ProducerResources.Primitive> primitives;
            if(draw.kind==1)primitives=new ProducerResources.UniformPrimitives(new ProducerResources.Primitive(key,0,surface,-1),vertices/4);
            else {
                var values=new ArrayList<ProducerResources.Primitive>(quadFacts.size());
                for(int q=0;q<quadFacts.size();q++) {
                    var fact=quadFacts.get(q);
                    var description=new SceneInputs.Surface(key,fact.material,0,color.key(),new SceneInputs.Key(0,0),coverage,surface.cutoff(),!p.isCull(),fact.material==8?1:0,0,0,layers,SceneInputs.HostOcclusion.BLOCK);
                    values.add(new ProducerResources.Primitive(key,q,description,fact.tint));
                }
                primitives=values;
            }
            var view=host.buffer(info.vertexBuffer(),(long)info.baseVertex()*draw.stride,(long)vertices*draw.stride);
            var grant=new HostExecution.BufferGrant(view,revision,Set.of(HostExecution.Access.TRANSFER_READ),host.recording());

            var geometry=new ProducerResources.Geometry(key,world,revision,revision,revision,revision,grant,draw.stride==36?ProducerResources.Layout.ENTITY36:ProducerResources.Layout.BLOCK28,
                ProducerResources.Topology.QUADS_012_230,draw.kind==3?ProducerResources.TintMode.PURE_FACT:ProducerResources.TintMode.UNLIT_VERTEX_COLOR,SceneInputs.Motion.DEFORMING,vertices,origin,SceneInputs.Transform.identity(),SceneInputs.Transform.identity(),
                new Participation(Participation.WORLD,draw.primary && coverage!=SceneInputs.Coverage.FILTER),primitives);
            registry.publish(geometry);represented.put(info,key);current.add(key);
        }

        for(var key:previous)if(!current.contains(key)) {
            var input=registry.get(key);if(input!=null)registry.withdraw(new ProducerResources.Withdrawal(key,world,input.generation(),input.revision()));
        }
        previous=Set.copyOf(current);return new Published(new ArrayList<>(textures.values()),represented);
    }
    private static TextureInputs.GpuTexture texture(HostDevice host,PreparedRenderType.Texture texture) {
        var image=host.image(texture.textureView());var sampler=texture.sampler();
        var sampling=new TextureInputs.Sampling(sampler.getAddressModeU()==AddressMode.CLAMP_TO_EDGE?TextureInputs.Address.CLAMP:TextureInputs.Address.REPEAT,
            sampler.getAddressModeV()==AddressMode.CLAMP_TO_EDGE?TextureInputs.Address.CLAMP:TextureInputs.Address.REPEAT,
            sampler.getMinFilter()==FilterMode.LINEAR?TextureInputs.Filter.LINEAR:TextureInputs.Filter.NEAREST,
            sampler.getMagFilter()==FilterMode.LINEAR?TextureInputs.Filter.LINEAR:TextureInputs.Filter.NEAREST,TextureInputs.Filter.LINEAR,
            (float)sampler.getMaxLod().orElse(image.mipCount()-1),sampler.getMaxAnisotropy());
        return new TextureInputs.GpuTexture(host.textureKey(texture.textureView(),sampler),image.contentEpoch(),TextureInputs.Encoding.SRGB,sampling,
            new HostExecution.ImageGrant(image,image.contentEpoch(),Set.of(HostExecution.Access.SHADER_READ),host.recording()),TextureInputs.GpuUse.FRAME_READ);
    }
}
