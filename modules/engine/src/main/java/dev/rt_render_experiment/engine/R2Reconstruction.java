package dev.rt_render_experiment.engine;

import java.util.ArrayList;
import java.util.List;
import dev.rt_render_experiment.engine.abi.R2Abi;
import dev.rt_render_experiment.engine.abi.R2Resources;
import dev.rt_render_experiment.vulkan.CompletionPool;
import dev.rt_render_experiment.vulkan.VulkanBarriers;
import dev.rt_render_experiment.vulkan.VulkanDescriptors;
import dev.rt_render_experiment.vulkan.VulkanObjects;
import dev.rt_render_experiment.vulkan.VulkanResources;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;


public final class R2Reconstruction implements AutoCloseable {
    private static final int STAGE = VK12.VK_SHADER_STAGE_COMPUTE_BIT;
    private final VulkanResources resources;
    private final long descriptorLayout, layout;
    private final long[] pipelines = new long[R2Abi.Reconstruction.ENTRIES.size()];
    private final int spatialIterations;
    private final java.util.Map<RenderFrame.View, History> histories = new java.util.EnumMap<>(RenderFrame.View.class);
    private final java.util.Map<RenderFrame.View, CompletionPool<R2Scratch>> scratch = new java.util.EnumMap<>(RenderFrame.View.class);
    private final java.util.Map<RenderFrame.View, CompletionPool<R2Scratch>> historyStorage = new java.util.EnumMap<>(RenderFrame.View.class);
    private long lastUse;
    private boolean closed;
    public R2Reconstruction(VulkanResources resources, R2ShaderPackage shaders) {
        spatialIterations = R2Abi.RECONSTRUCTION_ITERATIONS;
        this.resources = resources; var device = resources.device(); long descriptors = 0, pipelineLayout = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var bindings = R2Resources.reconstruction(STAGE);
            descriptors = VulkanDescriptors.createPushLayout(device, "RtRenderExperiment R2 reconstruction", bindings);
            var handle = stack.callocLong(1);
            VulkanObjects.requireSuccess(VK12.vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                .pSetLayouts(stack.longs(descriptors)).pPushConstantRanges(VkPushConstantRange.calloc(1, stack).stageFlags(STAGE).size(R2Abi.DispatchRecord.SIZE)), null, handle), "Create R2 reconstruction layout");
            pipelineLayout = handle.get(0);
            for (int i = 0; i < R2Abi.Reconstruction.ENTRIES.size(); i++) {
                String entry = R2Abi.Reconstruction.ENTRIES.get(i);
                long module = VulkanObjects.shaderModule(device, shaders.module(entry), entry);
                try {
                    var create = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default().layout(pipelineLayout).flags(PipelineStatistics.flags(device));
                    create.stage().sType$Default().stage(STAGE).module(module).pName(stack.UTF8("main"));
                    VulkanObjects.requireSuccess(VK12.vkCreateComputePipelines(device, resources.compilationCache(shaders.executionIdentity()), create, null, handle), "Create " + entry);
                    pipelines[i] = handle.get(0); PipelineStatistics.capture(device,pipelines[i],entry);
                } finally { VK12.vkDestroyShaderModule(device, module, null); }
            }
            descriptorLayout = descriptors; layout = pipelineLayout;
        } catch (RuntimeException | Error failure) {
            for (long pipeline : pipelines) if (pipeline != 0) VK12.vkDestroyPipeline(device, pipeline, null);
            if (pipelineLayout != 0) VK12.vkDestroyPipelineLayout(device, pipelineLayout, null);
            if (descriptors != 0) VK12.vkDestroyDescriptorSetLayout(device, descriptors, null);
            throw failure;
        }
    }
    private record History(VulkanResources.Buffer buffer, int width, int height, CompletionPool<R2Scratch>.Lease storage) implements AutoCloseable {
        @Override public void close() { if(storage==null)buffer.close();else storage.releaseAfter(buffer.lastUse()); }
    }
    private History history(int width,int height,RenderFrame.View view,int usage) {
        var lease=historyStorage.computeIfAbsent(view,ignored->new CompletionPool<>(4,()->new R2Scratch(resources),R2Scratch::close))
            .acquire(resources.recording(),resources.completed());
        try { return new History(lease.value().buffer(0,Math.multiplyExact(Math.multiplyExact(width,height),R2Abi.HistoryRecord.SIZE),usage,false),width,height,lease); }
        catch(RuntimeException|Error failure) { lease.close();throw failure; }
    }
    public Prepared record(VkCommandBuffer command, VulkanResources.Buffer uniform, VulkanResources.Buffer signals, VulkanResources.Buffer guides, VulkanResources.Buffer output,
                           int width, int height, RenderFrame.View view, VulkanResources.Buffer reusedFilterInput) {
        if (closed || command.getDevice() != resources.device()) throw new IllegalStateException("Unavailable reconstruction owner");
        Prepared prepared = new Prepared(view);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int pixels = Math.multiplyExact(width, height);
            int usage = VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
            prepared.history = history(width,height,view,usage);
            var previous = histories.get(view);
            if (previous == null || previous.width != width || previous.height != height) {
                var buffer = resources.allocateDevice(Math.multiplyExact(pixels, R2Abi.HistoryRecord.SIZE), usage);
                prepared.transientBuffers.add(buffer); previous = new History(buffer, width, height,null);
                VK12.vkCmdFillBuffer(command, buffer.view().handle(), 0, buffer.view().length(), 0);
            } else prepared.previousValid = true;
            for (var buffer : prepared.transientBuffers) buffer.markUsed();
            previous.buffer.markUsed(); prepared.history.buffer.markUsed(); uniform.markUsed(); signals.markUsed(); guides.markUsed(); output.markUsed(); lastUse = resources.recording();
            VulkanBarriers.record(command, stack, new VulkanBarriers.Scope(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT | VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                VK13.VK_ACCESS_2_SHADER_WRITE_BIT | VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT, VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                VK13.VK_ACCESS_2_SHADER_READ_BIT | VK13.VK_ACCESS_2_SHADER_WRITE_BIT));
            bind(command, stack, uniform, signals, guides, previous.buffer, prepared.history.buffer, output);
            dispatch(command, stack, 0, 0, prepared.previousValid, width, height);
            VulkanBarriers.record(command, stack, VulkanBarriers.SHADE_TO_GUIDES);
            for (int iteration = 0; iteration < spatialIterations; iteration++) {
                bind(command, stack, uniform, signals, guides, previous.buffer, prepared.history.buffer, output);
                dispatch(command, stack, 1, iteration, prepared.previousValid, width, height);
                if (iteration + 1 < spatialIterations) VulkanBarriers.record(command, stack, VulkanBarriers.ATROUS_STEP);
            }
            return prepared;
        } catch (RuntimeException | Error failure) { prepared.close(); throw failure; }
    }
    private void bind(VkCommandBuffer command, MemoryStack stack, VulkanResources.Buffer uniform, VulkanResources.Buffer... buffers) {
        var writes = VkWriteDescriptorSet.calloc(6, stack); VulkanDescriptors.writeUniform(writes.get(0), stack, 0, uniform.view());
        for (int i = 0; i < buffers.length; i++) VulkanDescriptors.writeStorageBuffer(writes.get(i + 1), stack, i + 1, buffers[i].view());
        KHRPushDescriptor.vkCmdPushDescriptorSetKHR(command, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0, writes);
    }
    private void dispatch(VkCommandBuffer command, MemoryStack stack, int pipeline, int iteration, boolean previous, int width, int height) {
        VK12.vkCmdBindPipeline(command, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, pipelines[pipeline]);
        var parameters = stack.malloc(R2Abi.DispatchRecord.SIZE).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        R2Abi.DispatchRecord.control(parameters, 0, iteration, previous ? 1 : 0, 0, 0);
        VK12.vkCmdPushConstants(command, layout, STAGE, 0, parameters);
        ComputeDispatch.record(resources, command, R2Abi.Reconstruction.ENTRIES.get(pipeline), Math.ceilDiv(width, R2Resources.workgroupX(R2Abi.Reconstruction.ENTRIES.get(pipeline))), Math.ceilDiv(height, R2Resources.workgroupY(R2Abi.Reconstruction.ENTRIES.get(pipeline))));
    }
    public final class Prepared implements AutoCloseable {
        private final long recording = resources.recording();
        private final List<VulkanResources.Buffer> transientBuffers = new ArrayList<>();
        private final CompletionPool<R2Scratch>.Lease scratch;
        private History history;
        private boolean previousValid, committed, closed;
        private final RenderFrame.View view;
        private Prepared(RenderFrame.View view) {
            this.view = view;
            scratch = R2Reconstruction.this.scratch.computeIfAbsent(view,
                ignored -> new CompletionPool<>(4, () -> new R2Scratch(resources), R2Scratch::close))
                .acquire(recording, resources.completed());
        }
        public VulkanResources.Buffer history() { if (closed) throw new IllegalStateException("Closed reconstruction recording"); return history.buffer; }
        public void submitted(long serial) {
            if (closed || committed || serial < recording) throw new IllegalStateException("Invalid history submission");
            var previous = histories.put(view, history); committed = true;
            if (previous != null) previous.close();
        }
        @Override public void close() {
            if (!closed) {
                closed = true;
                Throwable failure = null;
                for (var buffer : transientBuffers) failure = dev.rt_render_experiment.vulkan.VulkanRetirement.attempt(failure, buffer::close);
                if (!committed && history != null) failure = dev.rt_render_experiment.vulkan.VulkanRetirement.attempt(failure, history::close);
                scratch.close();
                dev.rt_render_experiment.vulkan.VulkanRetirement.finish(failure);
            }
        }
    }
    public void invalidate(RenderFrame.View view) { var history = histories.remove(view); if (history != null) history.close(); }
    public void invalidate() { for (var history : histories.values()) history.close(); histories.clear(); }
    @Override public void close() {
        if (closed) return;
        resources.retirePrograms(lastUse, pipelines, new long[]{layout}, new long[]{descriptorLayout});
        closed = true;
        Throwable failure = null;
        for (var pool : scratch.values()) failure = dev.rt_render_experiment.vulkan.VulkanRetirement.attempt(failure, pool::close);
        scratch.clear();
        failure = dev.rt_render_experiment.vulkan.VulkanRetirement.attempt(failure, this::invalidate);
        for(var pool:historyStorage.values())failure=dev.rt_render_experiment.vulkan.VulkanRetirement.attempt(failure,pool::close);
        historyStorage.clear();
        dev.rt_render_experiment.vulkan.VulkanRetirement.finish(failure);
    }
}
