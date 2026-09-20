package dev.rt_render_experiment.engine;

import java.util.ArrayList;
import java.util.List;
import dev.rt_render_experiment.contract.HostExecution;
import dev.rt_render_experiment.contract.SceneInputs;
import dev.rt_render_experiment.contract.ResourceViews;
import dev.rt_render_experiment.engine.abi.R2Abi;
import dev.rt_render_experiment.engine.abi.R2Resources;
import dev.rt_render_experiment.vulkan.CompletionPool;
import dev.rt_render_experiment.vulkan.VulkanBarriers;
import dev.rt_render_experiment.vulkan.VulkanDescriptors;
import dev.rt_render_experiment.vulkan.VulkanResources;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;






public final class R2Renderer implements FrameRenderer {
    private static final int STAGE = VK12.VK_SHADER_STAGE_COMPUTE_BIT;
    private final VulkanResources resources;
    private final ComputeProgram transport;
    private final String shaderIdentity;
    private final R2ShaderPackage shaders;
    private final java.util.Map<RenderFrame.View, CompletionPool<R2Scratch>> scratch = new java.util.EnumMap<>(RenderFrame.View.class);
    private boolean closed;
    private LightHistoryStorage worldLights;
    private R2Reconstruction portable;
    private R2Presentation presentation;
    private R2Handoff handoff;
    private TransportCounterCapture counterCapture;
    private ComputeProgram resolve;
    private ComputeProgram sourcePreparation;
    public R2Renderer(VulkanResources resources, HostExecution.Capabilities capabilities, R2ShaderPackage shaders) {
        if (resources.deviceIdentity() != capabilities.device()) throw new IllegalArgumentException("Foreign capability grant");
        capabilities.require(HostExecution.Capability.BUFFER_ADDRESS);
        capabilities.require(HostExecution.Capability.ACCELERATION_STRUCTURE);
        capabilities.require(HostExecution.Capability.RAY_QUERY);
        this.resources = resources; this.shaderIdentity = shaders.executionIdentity(); this.shaders = shaders;
        transport = new ComputeProgram(resources, shaders, R2Abi.Transport.ENTRIES, R2Resources.transport(STAGE), resources.textures().layout());
    }
    public String shaderIdentity() { return shaderIdentity; }
    @Override public Output record(HostExecution.Window window, VkCommandBuffer command, GpuScene.Snapshot scene, RenderFrame frame) {
        return record(window, command, scene, frame, false);
    }

    public Output record(HostExecution.Window window, VkCommandBuffer command, GpuScene.Snapshot scene, RenderFrame frame, boolean diagnosticHits) {
        return record(window, command, (R2Scene)scene, frame, diagnosticHits);
    }
    public Output record(HostExecution.Window window, VkCommandBuffer command, R2Scene scene, RenderFrame frame, boolean diagnosticHits) {
        if (closed) throw new IllegalStateException("Closed R2 renderer");
        if (window.device() != resources.deviceIdentity() || window.recording() != resources.recording()
            || window.stage() != (frame.view() == RenderFrame.View.WORLD ? HostExecution.Stage.WORLD : HostExecution.Stage.VIEWMODEL)
            || command.getDevice() != resources.device() || !frame.origin().equals(scene.origin()) || !shaderIdentity.equals(scene.shaderIdentity()))
            throw new IllegalArgumentException("Foreign, expired or wrong-stage R2 window");
        scene.requireInitialMedia(frame);
        int cloudSlot = frame.environment().cloudsVisible() ? scene.cloudSlot(frame.environment().cloud()) : 0;
        if (frame.reconstruction() != RenderFrame.Reconstruction.RAW_DIAGNOSTIC && portable == null) portable = new R2Reconstruction(resources, shaders);
        Output result = new Output(frame.width(), frame.height(), frame.outputWidth(), frame.outputHeight(), window.transaction(), frame.view());
        result.scene = scene;
        try {
            if (worldLights == null) worldLights = new LightHistoryStorage(resources);
            result.worldLights = worldLights.prepare(frame, scene.worldLightDomain());
            int sourceCount=scene.sourceCount();
            var emitterSummaries=result.allocate(Math.multiplyExact(Math.max(1,sourceCount),R2Abi.EmitterRecord.SIZE),
                VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT|VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,false);
            result.uniform = result.allocate(R2Abi.FrameRecord.SIZE, VK12.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, true);
            try (var mapping = result.uniform.map(0, result.uniform.view().length())) {
                mapping.data().put(R2Frame.encode(frame, new R2Frame.Tables(scene.recordsAddress(), scene.sourcesAddress(), scene.materialsAddress(),
                    scene.atmosphereAddress(), scene.serial(), cloudSlot, scene.filterPrimitives(),emitterSummaries.view().address()), diagnosticHits, result.worldLights.enabled(), result.worldLights.valid()));
            }
            int pixels = Math.multiplyExact(frame.width(), frame.height());
            int usage = VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
            result.hits = result.allocate((diagnosticHits || Boolean.getBoolean("rt_render_experiment.counters")) ? Math.multiplyExact(pixels, R2Abi.HitRecord.SIZE) : R2Abi.HitRecord.SIZE, usage, false);
            result.signals = result.allocate(Math.multiplyExact(pixels, R2Abi.SignalRecord.SIZE), usage, false);
            result.guides = result.allocate(Math.multiplyExact(pixels, R2Abi.GuideRecord.SIZE), usage, false);
            result.hostDepth = result.allocate(Math.multiplyExact(pixels, 4), usage, false);
            result.raw = result.allocate(Math.multiplyExact(pixels, 16), usage, false);
            result.radiance = frame.width() == frame.outputWidth() && frame.height() == frame.outputHeight() ? result.raw
                : result.allocate(Math.multiplyExact(Math.multiplyExact(frame.outputWidth(), frame.outputHeight()), 16), usage, false);
            scene.markUsed(); result.worldLights.markUsed();
            for (var buffer : result.buffers) buffer.markUsed();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VulkanBarriers.record(command, stack, new VulkanBarriers.Scope(VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
                    VK13.VK_ACCESS_2_MEMORY_WRITE_BIT, VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                    VK13.VK_ACCESS_2_SHADER_READ_BIT | VK13.VK_ACCESS_2_SHADER_WRITE_BIT | KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR));
                if(sourceCount>0) {
                    if(sourcePreparation==null)sourcePreparation=new ComputeProgram(resources,shaders,R2Abi.SourcePreparation.ENTRIES,R2Resources.sourcePreparation(STAGE));
                    var sourceWrites=VkWriteDescriptorSet.calloc(1,stack);VulkanDescriptors.writeUniform(sourceWrites.get(0),stack,0,result.uniform.view());
                    KHRPushDescriptor.vkCmdPushDescriptorSetKHR(command,VK12.VK_PIPELINE_BIND_POINT_COMPUTE,sourcePreparation.layout(),0,sourceWrites);
                    int groups=Math.ceilDiv(sourceCount,R2Resources.workgroupX("r2PrepareSources"));
                    int columns=Math.min(groups,R2Abi.SOURCE_PREPARATION_COLUMNS);
                    sourcePreparation.dispatch(command,0,columns,Math.ceilDiv(groups,columns));
                    VulkanBarriers.record(command,stack,VulkanBarriers.SHADE_TO_GUIDES);
                }
                var writes = VkWriteDescriptorSet.calloc(9, stack);
                VulkanDescriptors.writeUniform(writes.get(0), stack, R2Abi.Transport.FRAME, result.uniform.view());
                VulkanDescriptors.writeAccelerationStructure(writes.get(1), stack, R2Abi.Transport.WORLDAS, scene.accelerationStructure());
                VulkanDescriptors.writeStorageBuffer(writes.get(2), stack, R2Abi.Transport.HITS, result.hits.view());
                VulkanDescriptors.writeStorageBuffer(writes.get(3), stack, R2Abi.Transport.SIGNALS, result.signals.view());
                VulkanDescriptors.writeStorageBuffer(writes.get(4), stack, R2Abi.Transport.GUIDES, result.guides.view());
                VulkanDescriptors.writeStorageBuffer(writes.get(5), stack, R2Abi.Transport.HOSTDEPTH, result.hostDepth.view());
                VulkanDescriptors.writeStorageBuffer(writes.get(6), stack, R2Abi.Transport.OUTPUT, result.raw.view());
                VulkanDescriptors.writeStorageBuffer(writes.get(7), stack, R2Abi.Transport.PREVIOUSLIGHTHISTORY, result.worldLights.input().view());
                VulkanDescriptors.writeStorageBuffer(writes.get(8), stack, R2Abi.Transport.CURRENTLIGHTHISTORY, result.worldLights.output().view());
                KHRPushDescriptor.vkCmdPushDescriptorSetKHR(command, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, transport.layout(), 0, writes);
                scene.bindTextures(command, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, transport.layout());
                transport.dispatchPixels(command,0,frame.width(),frame.height());
                VulkanBarriers.record(command, stack, new VulkanBarriers.Scope(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK13.VK_ACCESS_2_SHADER_WRITE_BIT,
                    VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT | VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK13.VK_ACCESS_2_SHADER_READ_BIT | VK13.VK_ACCESS_2_TRANSFER_READ_BIT));
            }
            if(Boolean.getBoolean("rt_render_experiment.counters")) {
                if(counterCapture==null)counterCapture=new TransportCounterCapture(resources);
                result.counterCapture=counterCapture.record(command,result.hits,frame);
            }
            if (frame.reconstruction() == RenderFrame.Reconstruction.PORTABLE) {
                result.reconstruction = portable.record(command, result.uniform, result.signals, result.guides, result.raw,
                    frame.width(), frame.height(), frame.view(), null);
            }
            if (result.radiance != result.raw) {
                if (resolve == null) resolve = new ComputeProgram(resources, shaders, R2Abi.Resolve.ENTRIES, R2Resources.resolve(STAGE));
                try (var stack = MemoryStack.stackPush()) {
                    VulkanBarriers.record(command, stack, new VulkanBarriers.Scope(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK13.VK_ACCESS_2_SHADER_WRITE_BIT,
                        VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK13.VK_ACCESS_2_SHADER_READ_BIT | VK13.VK_ACCESS_2_SHADER_WRITE_BIT));
                    var writes = VkWriteDescriptorSet.calloc(3, stack); VulkanDescriptors.writeUniform(writes.get(0), stack, 0, result.uniform.view());
                    VulkanDescriptors.writeStorageBuffer(writes.get(1), stack, 1, result.raw.view()); VulkanDescriptors.writeStorageBuffer(writes.get(2), stack, 2, result.radiance.view());
                    KHRPushDescriptor.vkCmdPushDescriptorSetKHR(command, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, resolve.layout(), 0, writes);
                    resolve.dispatchPixels(command, 0, frame.outputWidth(), frame.outputHeight());
                }
            }
            return result;
        } catch (RuntimeException | Error failure) { result.close(); throw failure; }
    }
    @Override public void publishWorld(HostExecution.Window window, VkCommandBuffer command, FrameOutput published, HostExecution.ImageGrant depth) {
        Output output = own(published);
        requireOutputWindow(window, command, output, HostExecution.Stage.BEFORE_TRANSPARENT);
        if (output.view != RenderFrame.View.WORLD) throw new IllegalArgumentException("Only the world output can replace host world depth");
        requireAttachment(window, depth);
        if (output.compositionView != null) throw new IllegalStateException("World handoff already recorded");
        if (handoff == null) handoff = new R2Handoff(resources, shaders);
        output.composition = output.scratch.value().image(0, R2Abi.Formats.COMPOSITION, VK12.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK12.VK_IMAGE_USAGE_STORAGE_BIT
            | VK12.VK_IMAGE_USAGE_SAMPLED_BIT | VK12.VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK12.VK_IMAGE_USAGE_TRANSFER_DST_BIT, output.outputWidth, output.outputHeight, VK12.VK_IMAGE_ASPECT_COLOR_BIT, "RtRenderExperiment R2 world composition");
        resources.initializeImages(command); output.composition.markUsed();
        output.compositionView = output.composition.view();
        handoff.world(command, output.uniform, output.radiance, output.hostDepth, output.guides, output.composition.view(), depth.view(), output.outputWidth, output.outputHeight);
    }

    public void publishWorld(HostExecution.Window window, VkCommandBuffer command, FrameOutput published,
                             HostExecution.ImageGrant color, HostExecution.ImageGrant depth) {
        Output output=own(published);
        requireOutputWindow(window,command,output,HostExecution.Stage.BEFORE_TRANSPARENT);
        requireAttachment(window,color);requireAttachment(window,depth);
        if(output.view!=RenderFrame.View.WORLD || output.compositionView!=null
            || color.view().format()!=R2Abi.Formats.COMPOSITION || !color.allowed().contains(HostExecution.Access.SHADER_READ)
            || color.view().width()!=output.outputWidth || color.view().height()!=output.outputHeight
            || depth.view().width()!=output.outputWidth || depth.view().height()!=output.outputHeight)
            throw new IllegalArgumentException("Invalid host HDR/depth composition grant");
        if(handoff==null)handoff=new R2Handoff(resources,shaders);
        output.compositionView=color.view();
        handoff.world(command,output.uniform,output.radiance,output.hostDepth,output.guides,color.view(),depth.view(),output.outputWidth,output.outputHeight);
    }
    @Override public void publishDisplay(HostExecution.Window window, VkCommandBuffer command, FrameOutput published, HostExecution.ImageGrant target) {
        Output output = own(published);
        requireOutputWindow(window, command, output, HostExecution.Stage.DISPLAY); requireAttachment(window, target);
        if (output.presentation == null) throw new IllegalStateException("Presentation is not ready");
        if (handoff == null) handoff = new R2Handoff(resources, shaders);
        handoff.display(command, output.uniform, output.display(), output.hostDepth, output.guides, target.view(), output.outputWidth, output.outputHeight);
    }
    @Override public void publishViewmodel(HostExecution.Window window, VkCommandBuffer command, FrameOutput publishedWorld, FrameOutput publishedViewmodel) {
        Output world = own(publishedWorld), viewmodel = own(publishedViewmodel);
        requireOutputWindow(window, command, world, HostExecution.Stage.VIEWMODEL);
        requireOutputWindow(window, command, viewmodel, HostExecution.Stage.VIEWMODEL);
        if (world.view != RenderFrame.View.WORLD || viewmodel.view != RenderFrame.View.VIEWMODEL || world.compositionView == null || viewmodel.viewDepth != null
            || world.width != viewmodel.width || world.height != viewmodel.height) throw new IllegalArgumentException("Invalid view-domain handoff");
        viewmodel.viewDepth = viewmodel.scratch.value().image(0, R2Abi.Formats.DEPTH, VK12.VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK12.VK_IMAGE_USAGE_SAMPLED_BIT
            | VK12.VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK12.VK_IMAGE_USAGE_TRANSFER_DST_BIT, world.width, world.height, VK12.VK_IMAGE_ASPECT_DEPTH_BIT, "RtRenderExperiment R2 viewmodel composition depth");
        resources.initializeImages(command); viewmodel.viewDepth.markUsed(); if(world.composition!=null)world.composition.markUsed();
        handoff.viewmodel(command, viewmodel.uniform, viewmodel.radiance, viewmodel.hostDepth, viewmodel.guides, world.compositionView, viewmodel.viewDepth.view(), world.width, world.height);
        viewmodel.viewPublished = true;
    }
    @Override public void absentViewmodel() { if (portable != null) portable.invalidate(RenderFrame.View.VIEWMODEL); }
    @Override public void recordPresentation(HostExecution.Window window, VkCommandBuffer command, FrameOutput published) {
        Output output = own(published);
        output.requireOpen();
        if (window.device() != resources.deviceIdentity() || window.recording() != resources.recording() || window.stage() != HostExecution.Stage.DISPLAY
            || output.recording != resources.recording() || output.transaction != window.transaction() || command.getDevice() != resources.device()
            || output.presentation != null || output.view != RenderFrame.View.WORLD) throw new IllegalArgumentException("Invalid presentation window/output");
        if (presentation == null) presentation = new R2Presentation(resources, shaders);
        if (output.compositionView != null) {
            if(output.composition!=null)output.composition.markUsed(); handoff.importComposition(command, output.uniform, output.compositionView, output.radiance, output.outputWidth, output.outputHeight);
        }
        output.presentation = presentation.record(command, output.uniform, output.radiance, output.outputWidth, output.outputHeight);
    }
    private Output own(FrameOutput output) {
        if (!(output instanceof Output mine) || mine.owner != this) throw new IllegalArgumentException("Output of another recorder");
        return mine;
    }
    private void requireAttachment(HostExecution.Window window, HostExecution.ImageGrant image) {
        if (!window.images().contains(image) || !image.allowed().contains(HostExecution.Access.ATTACHMENT_WRITE)) throw new IllegalArgumentException("Attachment write was not granted");
    }
    private void requireOutputWindow(HostExecution.Window window, VkCommandBuffer command, Output output, HostExecution.Stage stage) {
        output.requireOpen();
        if (window.device() != resources.deviceIdentity() || window.recording() != resources.recording() || window.stage() != stage
            || output.recording != resources.recording() || output.transaction != window.transaction() || command.getDevice() != resources.device())
            throw new IllegalArgumentException("Invalid output execution window");
    }
    public final class Output implements FrameOutput {
        private final List<VulkanResources.Buffer> buffers = new ArrayList<>();
        private final int width, height, outputWidth, outputHeight;
        private final RenderFrame.View view;
        private final long transaction;
        private final long recording = resources.recording();
        private final R2Renderer owner = R2Renderer.this;
        private final CompletionPool<R2Scratch>.Lease scratch;
        private R2Scene scene;
        private VulkanResources.Buffer uniform, hits, signals, guides, hostDepth, raw, radiance;
        private LightHistoryStorage.Prepared worldLights;
        private R2Reconstruction.Prepared reconstruction;
        private R2Presentation.Prepared presentation;
        private TransportCounterCapture.Capture counterCapture;
        private VulkanResources.Image composition, viewDepth;
        private ResourceViews.Image compositionView;
        private boolean viewPublished, submitted, closed;
        private Output(int width, int height, int outputWidth, int outputHeight, long transaction, RenderFrame.View view) {
            this.width = width; this.height = height; this.outputWidth = outputWidth; this.outputHeight = outputHeight; this.transaction = transaction; this.view = view;
            scratch = R2Renderer.this.scratch.computeIfAbsent(view,
                ignored -> new CompletionPool<>(4, () -> new R2Scratch(resources), R2Scratch::close))
                .acquire(recording, resources.completed());
        }
        private VulkanResources.Buffer allocate(long bytes, int usage, boolean mapped) {
            var buffer = scratch.value().buffer(buffers.size(), bytes, usage, mapped);
            buffers.add(buffer); return buffer;
        }
        @Override public int width() { return outputWidth; }
        @Override public int height() { return outputHeight; }
        public RenderFrame.View view() { return view; }
        public long transaction() { return transaction; }
        @Override public VulkanResources.Buffer hits() { requireOpen(); return hits; }
        @Override public VulkanResources.Buffer signals() { requireOpen(); return signals; }
        public VulkanResources.Buffer guides() { requireOpen(); return guides; }
        public VulkanResources.Buffer hostDepth() { requireOpen(); return hostDepth; }

        public VulkanResources.Buffer raw() { requireOpen(); return raw; }
        @Override public VulkanResources.Buffer radiance() { requireOpen(); return radiance; }
        @Override public VulkanResources.Buffer history() { requireOpen(); if (reconstruction == null) throw new IllegalStateException("Frame has no portable history"); return reconstruction.history(); }
        @Override public VulkanResources.Buffer display() { requireOpen(); if (presentation == null) throw new IllegalStateException("Presentation is not recorded"); return presentation.display(); }
        @Override public VulkanResources.Buffer exposure() { requireOpen(); if (presentation == null) throw new IllegalStateException("Presentation is not recorded"); return presentation.exposure(); }
        @Override public VulkanResources.Image compositionImage() { requireOpen(); if (composition == null) throw new IllegalStateException("World composition handoff is not ready"); return composition; }
        @Override public VulkanResources.Image viewDepthImage() { requireOpen(); if (!viewPublished) throw new IllegalStateException("Viewmodel depth is not published"); return viewDepth; }
        @Override public RenderFrame.Reconstruction reconstructionMode() {
            requireOpen(); return reconstruction != null ? RenderFrame.Reconstruction.PORTABLE : RenderFrame.Reconstruction.RAW_DIAGNOSTIC;
        }
        @Override public boolean worldLightReuseEnabled() { requireOpen(); return worldLights.enabled(); }
        @Override public boolean worldLightHistoryDomainValid() { requireOpen(); return worldLights.valid(); }
        @Override public long worldLightHistoryBytes() { requireOpen(); return worldLights.enabled() ? worldLights.output().view().length() : 0; }
        @Override public VulkanResources.Buffer worldLightHistory() { requireOpen(); if (!worldLights.enabled()) throw new IllegalStateException("World-light memory is disabled"); return worldLights.output(); }
        public VulkanResources.Buffer lightHistory() { return worldLightHistory(); }
        @Override public boolean replaces(SceneInputs.Geometry input, HostExecution.Transaction admission) {
            requireOpen();
            if (admission.id() != transaction || admission.state() != HostExecution.State.ACCEPTED) throw new IllegalArgumentException("World replacement lacks matching host acceptance");
            return scene.represents(input) && input.participation().primaryVisible() && (input.participation().rayMask() & view.primaryMask()) != 0
                && (view == RenderFrame.View.WORLD || viewPublished) && input.primitives().stream().noneMatch(p -> p.surface().coverage() == SceneInputs.Coverage.FILTER);
        }
        @Override public long logicalBytes() { return buffers.stream().mapToLong(buffer -> buffer.view().length()).sum(); }
        @Override public void submitted(long serial) {
            requireOpen(); if (submitted || serial != recording) throw new IllegalStateException("Invalid or duplicate frame submission"); submitted = true;
            if (reconstruction != null) reconstruction.submitted(serial); else if (portable != null) portable.invalidate(view);
            if (presentation != null) presentation.submitted(serial);
            worldLights.submitted(serial);
            if(counterCapture!=null)counterCapture.submitted();
        }
        private void requireOpen() { if (closed || R2Renderer.this.closed) throw new IllegalStateException("Retired R2 output"); }
        @Override public void close() {
            if (!closed) {
                closed = true;
                Throwable failure = null;
                if(counterCapture!=null)counterCapture.close();
                if (presentation != null) failure = dev.rt_render_experiment.vulkan.VulkanRetirement.attempt(failure, presentation::close);
                if (reconstruction != null) failure = dev.rt_render_experiment.vulkan.VulkanRetirement.attempt(failure, reconstruction::close);
                if (worldLights != null) failure = dev.rt_render_experiment.vulkan.VulkanRetirement.attempt(failure, worldLights::close);
                scratch.close();
                dev.rt_render_experiment.vulkan.VulkanRetirement.finish(failure);
            }
        }
    }
    @Override public void close() {
        PassTelemetry.release(resources);
        if(counterCapture!=null)counterCapture.close();
        if (closed) return;
        closed = true;
        Throwable failure = null;
        for (var pool : scratch.values()) failure = dev.rt_render_experiment.vulkan.VulkanRetirement.attempt(failure, pool::close);
        scratch.clear();
        if (handoff != null) failure = dev.rt_render_experiment.vulkan.VulkanRetirement.attempt(failure, handoff::close);
        if (sourcePreparation != null) failure = dev.rt_render_experiment.vulkan.VulkanRetirement.attempt(failure, sourcePreparation::close);
        if (resolve != null) failure = dev.rt_render_experiment.vulkan.VulkanRetirement.attempt(failure, resolve::close);
        if (presentation != null) failure = dev.rt_render_experiment.vulkan.VulkanRetirement.attempt(failure, presentation::close);
        if (portable != null) failure = dev.rt_render_experiment.vulkan.VulkanRetirement.attempt(failure, portable::close);
        if (worldLights != null) failure = dev.rt_render_experiment.vulkan.VulkanRetirement.attempt(failure, worldLights::close);
        failure = dev.rt_render_experiment.vulkan.VulkanRetirement.attempt(failure, transport::close);
        dev.rt_render_experiment.vulkan.VulkanRetirement.finish(failure);
    }
}
