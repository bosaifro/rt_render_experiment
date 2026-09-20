package dev.rt_render_experiment.reentry;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import dev.rt_render_experiment.contract.*;
import dev.rt_render_experiment.engine.*;
import dev.rt_render_experiment.integration.minecraft.MinecraftFrameEnvironment;
import dev.rt_render_experiment.reentry.mixin.LevelStateAccessor;
import dev.rt_render_experiment.vulkan.CompletionPool;
import dev.rt_render_experiment.vulkan.VulkanRetirement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.material.FogType;
import org.joml.Matrix4fc;


public final class WorldRenderer implements AutoCloseable {
    private static final Map<VulkanDevice,WorldRenderer> DEVICES=new IdentityHashMap<>();
    private static final SceneInputs.Key CLOUD=new SceneInputs.Key(0x434c4f5544L,1);
    private final HostDevice host;
    private final R2ShaderPackage shaders;
    private final ProducerTimings timings;
    private final CompletionPool<HdrSlot> targets=new CompletionPool<>(4,HdrSlot::new,HdrSlot::close);
    private ProducerFeed producer;
    private ProducerFramePipeline pipeline;
    private TextureInputs.Texture clouds;
    private int sample;
    private long previousTerrainCpu,previousTerrainJobs;
    private boolean closed;
    private WorldRenderer(HostDevice host) throws java.io.IOException {
        this.host=host;shaders=R2ShaderPackage.embedded();
        timings=Boolean.getBoolean("rt_render_experiment.timings")?new ProducerTimings(host.resources,host.device.getDeviceInfo().timestampPeriod()):null;
        host.own(this::close);
    }
    public static void poll() {
        for(var owner:List.copyOf(DEVICES.values())) {
            owner.host.observe();
            if(owner.producer!=null && owner.producer.closed() && !owner.host.hasUnsubmittedFrames()) {
                owner.pipeline.close();owner.pipeline=null;owner.producer=null;owner.clouds=null;
            }
        }
    }
    public static Frame begin(LevelRenderer renderer,RenderTarget target,float partialTick,boolean sky) throws java.io.IOException {
        if(!(((dev.rt_render_experiment.reentry.mixin.GpuDeviceAccessor)RenderSystem.getDevice()).rt_render_experiment$backend() instanceof VulkanDevice device) || !VulkanAdmission.enabled(device.vkDevice().address()))return null;
        var owner=DEVICES.get(device);
        if(owner==null) { owner=new WorldRenderer(HostDevice.of(device));DEVICES.put(device,owner); }
        return owner.beginFrame(renderer,target,partialTick,sky);
    }
    private Frame beginFrame(LevelRenderer renderer,RenderTarget target,float partialTick,boolean renderSky) throws java.io.IOException {
        long cpu=ProducerCpu.start();
        host.observe();
        var next=ProducerFeeds.current(renderer);
        if(next==null || next.closed())return null;
        var access=(LevelStateAccessor)renderer;var state=access.rt_render_experiment$state();var options=access.rt_render_experiment$options();var camera=state.cameraRenderState;
        if(!camera.initialized || state.skyRenderState.skybox!=net.minecraft.world.level.dimension.DimensionType.Skybox.OVERWORLD)return null;
        if(producer!=next) {
            var nextClouds=cloudMap();
            var nextPipeline=new ProducerFramePipeline(host.resources,VulkanAdmission.capabilities(host.device.vkDevice(),host.identity()),shaders);
            if(pipeline!=null)pipeline.close();
            producer=next;pipeline=nextPipeline;clouds=nextClouds;sample=0;
        }
        var origin=new SceneInputs.Origin(camera.pos.x,camera.pos.y,camera.pos.z);
        var features=FeatureProducer.publish(producer.registry(),host,origin);
        var buffers=producer.drain(host);
        var image=host.image(Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView());
        var grant=new HostExecution.ImageGrant(image,image.contentEpoch(),Set.of(HostExecution.Access.SHADER_READ),host.recording());
        var atlas=new TextureInputs.GpuTexture(TerrainProducer.ATLAS,image.contentEpoch(),TextureInputs.Encoding.SRGB,
            new TextureInputs.Sampling(TextureInputs.Address.CLAMP,TextureInputs.Address.CLAMP,TextureInputs.Filter.LINEAR,TextureInputs.Filter.LINEAR,
                TextureInputs.Filter.LINEAR,image.mipCount()-1,1),grant,TextureInputs.GpuUse.FRAME_READ);
        var sky=state.skyRenderState;
        int cloudMode=switch(options.cloudStatus) { case OFF->0;case FAST->1;case FANCY->2; };
        var facts=MinecraftFrameEnvironment.capture(MinecraftFrameEnvironment.SkyKind.OVERWORLD,sky.sunAngle,sky.rainBrightness,sky.starBrightness,state.cloudHeight,
            cloudMode,options.cloudRange*16,options.renderDistance*16,renderSky,sky.moonPhase.index(),state.gameTime,partialTick);
        var cloud=new RenderFrame.Cloud(CLOUD,clouds.levels().getFirst().width(),clouds.levels().getFirst().height(),state.cloudHeight,4,12);
        float phase=MinecraftFrameEnvironment.animationPhase(state.gameTime,partialTick);phase-=(float)Math.floor(phase);
        var environment=new RenderFrame.Environment(new SceneInputs.Vec3(facts.sunX(),facts.sunY(),facts.sunZ()),new SceneInputs.Vec3(facts.moonX(),facts.moonY(),facts.moonZ()),
            phase,facts.rainAmount(),facts.cloudPhaseX(),facts.cloudsVisible(),facts.referenceAltitude(),facts.renderDistanceBlocks(),camera.fogType==FogType.WATER,cloud);
        var frame=RenderFrame.fromCamera(target.width,target.height,sample++,origin,rows(camera.viewRotationMatrix),rows(HostCamera.projection()),environment,RenderFrame.DepthConvention.REVERSED)
            .withReconstruction(RenderFrame.Reconstruction.PORTABLE).withWorldLightReuse(true).withPresentation(new RenderFrame.Presentation(true,false,1f/60));
        var lease=targets.acquire(host.recording(),host.resources.completed());
        try {
            var hdr=lease.value().target(target);
            var allTextures=new ArrayList<TextureInputs.Resource>();allTextures.add(atlas);allTextures.add(clouds);allTextures.addAll(features.textures());
            var images=allTextures.stream().filter(t->t instanceof TextureInputs.GpuTexture).map(t->((TextureInputs.GpuTexture)t).source()).toList();
            var result=new Frame(pipeline,producer,frame,Integer.toUnsignedLong(facts.continuityKey()),buffers,allTextures,images,features.draws(),target,hdr,lease);
            result.ingressCpu=Math.max(0,ProducerCpu.elapsed(cpu));return result;
        } catch(RuntimeException|Error failure) { lease.close();throw failure; }
    }
    private static float[] rows(Matrix4fc matrix) {
        var rows=new float[16];for(int row=0;row<4;row++)for(int column=0;column<4;column++)rows[row*4+column]=matrix.get(column,row);return rows;
    }
    private static TextureInputs.Texture cloudMap() throws java.io.IOException {
        try(var input=Minecraft.getInstance().getResourceManager().open(Identifier.withDefaultNamespace("textures/environment/clouds.png"));var image=NativeImage.read(input)) {
            var pixels=ByteBuffer.allocate(image.getWidth()*image.getHeight()*4);
            for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++) {
                byte cell=(image.getPixel(x,y)>>>24)!=0?(byte)255:0;pixels.put(cell).put(cell).put(cell).put(cell);
            }
            return new TextureInputs.Texture(CLOUD,1,TextureInputs.Encoding.LINEAR,TextureInputs.Address.REPEAT,
                List.of(new TextureInputs.Level(image.getWidth(),image.getHeight(),pixels.flip())),false);
        }
    }
    private static final class HdrSlot implements AutoCloseable {
        private TextureTarget color;
        RenderTarget target(RenderTarget depth) {
            if(color==null || color.width!=depth.width || color.height!=depth.height) {
                if(color!=null)color.destroyBuffers();color=new TextureTarget("RtRenderExperiment HDR flight slot",depth.width,depth.height,false,GpuFormat.RGBA16_FLOAT);
            }
            return new BorrowedTarget(color,depth);
        }
        @Override public void close() { if(color!=null) { color.destroyBuffers();color=null; } }
    }
    private static final class BorrowedTarget extends RenderTarget {
        BorrowedTarget(RenderTarget color,RenderTarget depth) {
            super("RtRenderExperiment host composition",true,GpuFormat.RGBA16_FLOAT);width=color.width;height=color.height;
            colorTexture=color.getColorTexture();colorTextureView=color.getColorTextureView();
            depthTexture=depth.getDepthTexture();depthTextureView=depth.getDepthTextureView();
        }
        @Override public void destroyBuffers() { }
    }
    public final class Frame implements AutoCloseable {
        private final ProducerFramePipeline pipeline;
        private final ProducerFeed producer;
        private final RenderFrame frame;
        private final long continuity,recording=host.recording();
        private final List<HostExecution.BufferGrant> buffers;
        private final List<TextureInputs.Resource> textures;
        private final List<HostExecution.ImageGrant> images;
        private final Map<net.minecraft.client.renderer.StagedVertexBuffer.ExecuteInfo,SceneInputs.Key> featureDraws;
        private final RenderTarget original,hdr;
        private final CompletionPool<HdrSlot>.Lease lease;
        private ProducerFramePipeline.Frame rendered;
        private ProducerTimings.Frame timing;
        private boolean accepted,tracked,closed;
        private long ingressCpu;
        private Frame(ProducerFramePipeline pipeline,ProducerFeed producer,RenderFrame frame,long continuity,List<HostExecution.BufferGrant> buffers,
                      List<TextureInputs.Resource> textures,List<HostExecution.ImageGrant> images,Map<net.minecraft.client.renderer.StagedVertexBuffer.ExecuteInfo,SceneInputs.Key> featureDraws,
                      RenderTarget original,RenderTarget hdr,CompletionPool<HdrSlot>.Lease lease) {
            this.pipeline=pipeline;this.producer=producer;this.frame=frame;this.continuity=continuity;this.buffers=buffers;this.textures=textures;
            this.images=List.copyOf(images);this.featureDraws=Map.copyOf(featureDraws);this.original=original;this.hdr=hdr;this.lease=lease;
        }
        public RenderTarget original() { return original; }
        public RenderTarget target() { return hdr; }
        public boolean accepted() { return accepted; }
        public int featureCount() { return featureDraws.size(); }
        public boolean cloudsOwned() { return frame.environment().cloudsVisible(); }
        public void world() {
            long cpu=ProducerCpu.start();
            if(timings!=null)timing=timings.begin(recording,frame.width(),frame.height());
            HostCommands.record(host,command->{
                if(timing!=null)timing.stamp(command,0);
                rendered=pipeline.record(new HostExecution.Window(host.identity(),recording,recording,HostExecution.Stage.SCENE_PREPARATION,buffers,images),
                    command,producer.registry(),textures,producer.lights(),null,frame,continuity,timing==null?null:timing::stamp);
                var color=host.image(hdr.getColorTextureView());var depth=host.image(hdr.getDepthTextureView());
                rendered.publishWorld(command,new HostExecution.ImageGrant(color,recording,Set.of(HostExecution.Access.ATTACHMENT_WRITE,HostExecution.Access.SHADER_READ),recording),
                    new HostExecution.ImageGrant(depth,recording,Set.of(HostExecution.Access.ATTACHMENT_WRITE),recording));
                if(timing!=null)timing.stamp(command,3);
            });
            accepted=true;
            ingressCpu+=Math.max(0,ProducerCpu.elapsed(cpu));
        }
        public boolean represents(SceneInputs.Key key) { var input=producer.input(key);return accepted && input!=null && rendered.represents(input); }
        public boolean represents(net.minecraft.client.renderer.StagedVertexBuffer.ExecuteInfo info) { var key=featureDraws.get(info);return key!=null && represents(key); }
        public void display() {
            long cpu=ProducerCpu.start();
            if(!accepted)throw new IllegalStateException("World was not accepted");
            HostCommands.record(host,command->{var target=host.image(original.getColorTextureView());
                if(timing!=null)timing.stamp(command,4);
                rendered.display(command,new HostExecution.ImageGrant(target,recording,Set.of(HostExecution.Access.ATTACHMENT_WRITE),recording));
                if(timing!=null)timing.stamp(command,5);});
            if(timing!=null)timing.complete();
            host.track(recording,rendered::submitted,this::retire);tracked=true;
            ingressCpu+=Math.max(0,ProducerCpu.elapsed(cpu));
            if(timings!=null) {
                long terrainCpu=ProducerCpu.terrainCpu(),jobs=ProducerCpu.terrainJobs();
                RtRenderExperimentClient.LOGGER.info("RT_RENDER_EXPERIMENT_CPU recording={} ingressNs={} featurePrepareNs={} terrainWorkerNs={} terrainJobs={}",recording,ingressCpu,ProducerCpu.featureCpu(),terrainCpu-previousTerrainCpu,jobs-previousTerrainJobs);
                previousTerrainCpu=terrainCpu;previousTerrainJobs=jobs;
            }
            if(frame.sample()%120==0)RtRenderExperimentClient.LOGGER.info("RT_RENDER_EXPERIMENT_PRODUCER_FRAME frame={} copies={} builds={} scratchBytes={} featureDraws={} {}",frame.sample(),rendered.copiedSourceBytes(),rendered.builtMeshes(),rendered.scratchBytes(),featureDraws.size(),producer.statistics());
        }
        private void retire() {
            if(closed)return;closed=true;
            if(!tracked && timing!=null)timing.cancel();
            Throwable failure=null;if(rendered!=null)failure=VulkanRetirement.attempt(failure,rendered::close);lease.close();VulkanRetirement.finish(failure);
        }
        @Override public void close() { if(!tracked)retire(); }
    }
    @Override public void close() {
        if(closed)return;closed=true;DEVICES.remove(host.device);
        Throwable failure=null;if(pipeline!=null)failure=VulkanRetirement.attempt(failure,pipeline::close);
        if(timings!=null)failure=VulkanRetirement.attempt(failure,timings::close);
        failure=VulkanRetirement.attempt(failure,targets::close);VulkanRetirement.finish(failure);
    }
}
