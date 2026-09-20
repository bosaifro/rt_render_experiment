package dev.rt_render_experiment.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import dev.rt_render_experiment.contract.ResourceViews;
import dev.rt_render_experiment.contract.SceneInputs;
import dev.rt_render_experiment.contract.TextureInputs;
import dev.rt_render_experiment.contract.HostExecution;
import dev.rt_render_experiment.vulkan.VulkanBarriers;
import dev.rt_render_experiment.vulkan.VulkanResources;
import dev.rt_render_experiment.vulkan.VulkanSamplers;
import dev.rt_render_experiment.vulkan.VulkanTextureTable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;


public final class SceneTextures implements AutoCloseable {
    public static final TextureInputs.Texture NEUTRAL=new TextureInputs.Texture(new SceneInputs.Key(0,0),1,TextureInputs.Encoding.SRGB,TextureInputs.Address.CLAMP,
        List.of(new TextureInputs.Level(1,1,java.nio.ByteBuffer.wrap(new byte[]{-1,-1,-1,-1}))));
    private record Mip(int width,int height,int pixelBytes) { int bytes() { return Math.multiplyExact(Math.multiplyExact(width,height),pixelBytes); } }
    private static final class Item implements AutoCloseable {
        final TextureInputs.Resource input;
        final List<Mip> mips;
        final VulkanResources.Image image;
        final VulkanResources.Buffer staging;
        final int bytes;
        final long sampler;
        private int owners=1;
        private boolean recorded;
        private long submitted;
        Item(TextureInputs.Resource input,List<Mip> mips,VulkanResources.Image image,VulkanResources.Buffer staging,int bytes,long sampler) {
            this.input=input;this.mips=List.copyOf(mips);this.image=image;this.staging=staging;this.bytes=bytes;this.sampler=sampler;
        }
        boolean borrowed() { return input instanceof TextureInputs.GpuTexture gpu && gpu.use()==TextureInputs.GpuUse.FRAME_READ; }
        ResourceViews.Image view() { return image==null?((TextureInputs.GpuTexture)input).source().view():image.view().withContentEpoch(input.revision()); }
        Item retain() { if(owners<=0)throw new IllegalStateException("Retired texture image");owners=Math.incrementExact(owners);return this; }
        @Override public void close() {
            if(owners<=0)throw new IllegalStateException("Texture image released twice");
            if(--owners==0) { if(staging!=null)staging.close();if(image!=null)image.close(); }
        }
    }
    private final VulkanResources resources;
    private final List<Item> items=new ArrayList<>();
    private final Map<SceneInputs.Key,Integer> slots=new HashMap<>();
    private final List<TextureInputs.Resource> inputs;
    private VulkanTextureTable.Snapshot table;
    private VulkanTextureTable.Snapshot.Lease lease;
    private boolean closed;
    private int owners=1;
    public SceneTextures(VulkanResources resources,List<? extends TextureInputs.Resource> sources) {
        this(resources,sources,null);
    }
    public SceneTextures(VulkanResources resources,List<? extends TextureInputs.Resource> sources,SceneTextures predecessor) {
        this(resources,sources,predecessor,false);
    }
    SceneTextures(VulkanResources resources,List<? extends TextureInputs.Resource> sources,SceneTextures predecessor,boolean samplerAnisotropy) {
        this.resources=resources;
        this.inputs=List.copyOf(sources);
        if(predecessor!=null && (predecessor.resources!=resources || predecessor.closed))
            throw new IllegalArgumentException("Invalid predecessor texture generation");
        var compiler=new TextureCompiler();
        try {
            for (var input:sources) {
                if(input.sampling().anisotropy()>1 && !samplerAnisotropy)throw new IllegalArgumentException("Anisotropic source sampling requires an explicit enabled device grant");
                if (slots.putIfAbsent(input.key(),items.size())!=null) throw new IllegalArgumentException("Duplicate texture identity");
                Integer priorSlot=predecessor==null?null:predecessor.slots.get(input.key());
                Item prior=priorSlot==null?null:predecessor.items.get(priorSlot);
                if(prior!=null && prior.submitted>0 && sameInput(prior.input,input)) { items.add(prior.retain());continue; }
                TextureInputs.Texture cpu=input instanceof TextureInputs.Texture texture?compiler.compile(texture):null;
                int format=cpu==null?TextureImporter.destinationFormat(((TextureInputs.GpuTexture)input).source().view().format()):VK12.VK_FORMAT_R8G8B8A8_UNORM;
                boolean borrowed=input instanceof TextureInputs.GpuTexture gpu && gpu.use()==TextureInputs.GpuUse.FRAME_READ;
                int sampledFormat=borrowed?((TextureInputs.GpuTexture)input).source().view().format():format;
                TextureImporter.validateSampling(resources,sampledFormat,input.sampling(),!borrowed);
                var mips=new ArrayList<Mip>();
                if(cpu!=null)for(var level:cpu.levels())mips.add(new Mip(level.width(),level.height(),4));
                else {
                    var gpu=(TextureInputs.GpuTexture)input;TextureImporter.validate(gpu);
                    var view=gpu.source().view();int width=view.width(),height=view.height();
                    for(int level=0;level<view.mipCount();level++) { mips.add(new Mip(width,height,TextureImporter.pixelBytes(format)));width=Math.max(1,width/2);height=Math.max(1,height/2); }
                }
                var base=mips.getFirst();
                var image=borrowed?null:resources.allocateImageLevels(format,VK12.VK_IMAGE_USAGE_SAMPLED_BIT | VK12.VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                    base.width(),base.height(),VK12.VK_IMAGE_ASPECT_COLOR_BIT,mips.size(),"RtRenderExperiment texture "+input.key());
                VulkanResources.Buffer staging=null;
                try {
                    int bytes=0; if(!borrowed)for (var level:mips) bytes=Math.addExact(bytes,level.bytes());
                    staging=borrowed?null:cpu==null?resources.allocateDevice(bytes,VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT|VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
                        :resources.allocateMapped(bytes,VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
                    if(cpu!=null)try (var mapping=staging.map(0,bytes)) { for (var level:cpu.levels()) mapping.data().put(level.pixels()); }
                    var sampling=input.sampling();
                    long sampler=resources.samplers().get(new VulkanSamplers.State(address(sampling.u()),address(sampling.v()),filter(sampling.minification()),filter(sampling.magnification()),
                        sampling.mipmap()==TextureInputs.Filter.LINEAR?VK12.VK_SAMPLER_MIPMAP_MODE_LINEAR:VK12.VK_SAMPLER_MIPMAP_MODE_NEAREST,
                        sampling.maximumLod(),sampling.anisotropy()));
                    items.add(new Item(input,mips,image,staging,borrowed?0:bytes,sampler));
                } catch (RuntimeException | Error failure) { if (staging!=null) staging.close(); if(image!=null)image.close(); throw failure; }
            }
            if (items.isEmpty()) throw new IllegalArgumentException("Texture generation requires an explicit neutral resource");
        } catch (RuntimeException | Error failure) { close(); throw failure; }
    }

    public boolean matches(List<? extends TextureInputs.Resource> sources) {
        if (closed || sources.size()!=inputs.size()) return false;
        for(var item:items)if(item.borrowed() && ((TextureInputs.GpuTexture)item.input).source().validThroughRecording()!=resources.recording())return false;
        for (int i=0;i<sources.size();i++) {
            if(!sameInput(inputs.get(i),sources.get(i)))return false;
        }
        return true;
    }
    private static boolean sameInput(TextureInputs.Resource a,TextureInputs.Resource b) {
        if(a==b)return true;
        if(!a.key().equals(b.key()) || a.revision()!=b.revision())return false;
        if(a.encoding()!=b.encoding() || !a.sampling().equals(b.sampling()))
            throw new IllegalArgumentException("A texture revision changed its interpretation without invalidation");
        if(a instanceof TextureInputs.Texture x && b instanceof TextureInputs.Texture y) {
            if(x.levels().size()!=y.levels().size() || x.generateMipmaps()!=y.generateMipmaps())
                throw new IllegalArgumentException("A texture revision changed its interpretation without invalidation");
            for(int j=0;j<x.levels().size();j++) {
                var first=x.levels().get(j);var second=y.levels().get(j);
                if(first.width()!=second.width() || first.height()!=second.height() || first.pixels().mismatch(second.pixels())!=-1)
                    throw new IllegalArgumentException("A texture revision changed its content without invalidation");
            }
        } else if(a instanceof TextureInputs.GpuTexture x && b instanceof TextureInputs.GpuTexture y) {
            if(x.use()!=y.use())return false;
            var first=x.source().view();var second=y.source().view();TextureImporter.validate(y);
            if(first.format()!=second.format() || first.width()!=second.width() || first.height()!=second.height() || first.mipCount()!=second.mipCount())
                throw new IllegalArgumentException("A texture revision changed its interpretation without invalidation");
            if(x.use()==TextureInputs.GpuUse.FRAME_READ && !x.source().equals(y.source()))return false;
        } else {


            return false;
        }
        return true;
    }
    private static int address(TextureInputs.Address value) { return value==TextureInputs.Address.CLAMP?VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE:VK12.VK_SAMPLER_ADDRESS_MODE_REPEAT; }
    private static int filter(TextureInputs.Filter value) { return value==TextureInputs.Filter.NEAREST?VK12.VK_FILTER_NEAREST:VK12.VK_FILTER_LINEAR; }
    public SceneTextures retain() { if (closed) throw new IllegalStateException("Retired textures"); owners=Math.incrementExact(owners); return this; }
    public boolean recorded() { return table!=null; }
    public int slot(SceneInputs.Key key) {
        Integer slot=slots.get(key); if (slot==null) throw new IllegalArgumentException("Missing texture resource "+key); return slot;
    }
    public boolean linear(SceneInputs.Key key) { return items.get(slot(key)).input.encoding()==TextureInputs.Encoding.LINEAR; }
    public long revision(SceneInputs.Key key) { return items.get(slot(key)).input.revision(); }
    record SurfaceState(int slot,long revision,TextureInputs.Encoding encoding) {}
    SurfaceState surfaceState(SceneInputs.Key key) {
        int slot=slot(key);var input=items.get(slot).input;return new SurfaceState(slot,input.revision(),input.encoding());
    }
    public int width(SceneInputs.Key key) { return items.get(slot(key)).mips.getFirst().width(); }
    public int height(SceneInputs.Key key) { return items.get(slot(key)).mips.getFirst().height(); }
    public void record(VkCommandBuffer command) {
        record(null,command,null);
    }
    void record(HostExecution.Window window,VkCommandBuffer command,TextureImporter importer) {
        if (closed || table!=null) throw new IllegalStateException("Texture generation is not recordable");
        for(var item:items)if((!item.recorded || item.borrowed()) && item.input instanceof TextureInputs.GpuTexture gpu) {
            if(importer==null)throw new IllegalArgumentException("Texture source lacks an import execution grant");
            importer.validate(window,gpu);
        }
        resources.initializeImages(command);
        try (MemoryStack stack=MemoryStack.stackPush()) {
            var sampled=new ArrayList<ResourceViews.SampledImage>();
            for (var item:items) {
                if(item.borrowed()) { requireFrameGrant(item);item.recorded=true; }
                if(!item.recorded) {
                    if(item.input instanceof TextureInputs.GpuTexture gpu)importer.record(command,gpu,item.staging);
                    var regions=VkBufferImageCopy.calloc(item.mips.size(),stack); long offset=0;
                    for (int i=0;i<regions.remaining();i++) {
                        var level=item.mips.get(i); var region=regions.get(i);
                        region.bufferOffset(offset); region.imageSubresource().aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(i).layerCount(1);
                        region.imageExtent().set(level.width(),level.height(),1); offset+=level.bytes();
                    }
                    item.staging.markUsed();
                    VK12.vkCmdCopyBufferToImage(command,item.staging.view().handle(),item.image.view().image(),VK12.VK_IMAGE_LAYOUT_GENERAL,regions);
                    item.recorded=true;item.staging.close();
                }
                if(item.image!=null)item.image.markUsed();resources.samplers().markUsed(item.sampler);
                sampled.add(new ResourceViews.SampledImage(item.view(),item.sampler));
            }
            VulkanBarriers.record(command,stack,new VulkanBarriers.Scope(VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT,VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR|VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,VK13.VK_ACCESS_2_SHADER_READ_BIT));
            table=resources.textures().prepare(sampled,sampled.getFirst().view(),sampled.getFirst().sampler());
            lease=table.retain();
        }
    }
    public void bind(VkCommandBuffer command,long layout) { bind(command,KHRRayTracingPipeline.VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR,layout); }
    public void bind(VkCommandBuffer command,int bindPoint,long layout) {
        if (closed || table==null) throw new IllegalStateException("Texture generation is not ready");
        for (var item:items) { requireFrameGrant(item);if(item.image!=null)item.image.markUsed();resources.samplers().markUsed(item.sampler); }
        if(borrowedImages()>0)try(var stack=MemoryStack.stackPush()) {
            VulkanBarriers.record(command,stack,new VulkanBarriers.Scope(VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,VK13.VK_ACCESS_2_MEMORY_WRITE_BIT,
                VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT|KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,VK13.VK_ACCESS_2_SHADER_READ_BIT));
        }
        table.bind(command,bindPoint,layout);
    }
    private void requireFrameGrant(Item item) {
        if(item.borrowed() && ((TextureInputs.GpuTexture)item.input).source().validThroughRecording()!=resources.recording())
            throw new IllegalStateException("Borrowed texture requires a fresh grant for this recording");
    }
    public int borrowedImages() { return (int)items.stream().filter(Item::borrowed).count(); }

    public void finishReads(VkCommandBuffer command) {
        if(closed || command.getDevice()!=resources.device())throw new IllegalStateException("Invalid texture read completion window");
        for(var item:items)requireFrameGrant(item);
        if(borrowedImages()>0)try(var stack=MemoryStack.stackPush()) {
            VulkanBarriers.record(command,stack,new VulkanBarriers.Scope(
                VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT|KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,VK13.VK_ACCESS_2_SHADER_READ_BIT,
                VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,VK13.VK_ACCESS_2_MEMORY_READ_BIT|VK13.VK_ACCESS_2_MEMORY_WRITE_BIT));
        }
    }
    public long logicalBytes() { return items.stream().mapToLong(item->item.bytes).sum(); }
    public long pendingUploadBytes() { return items.stream().filter(item->!item.recorded).mapToLong(item->item.bytes).sum(); }
    public int reusedImages() { return (int)items.stream().filter(item->item.recorded).count(); }
    public void submitted(long serial) {
        if(closed || table==null || serial<=0)throw new IllegalStateException("Texture publication lacks submission");
        for(var item:items)if(item.submitted==0)item.submitted=serial;
    }
    @Override public void close() {
        if (!closed && --owners==0) {
            closed=true;
            if (lease!=null) lease.close();
            for (var item:items) item.close();
        }
    }
}
