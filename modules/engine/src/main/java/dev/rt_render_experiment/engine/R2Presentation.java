package dev.rt_render_experiment.engine;

import java.util.ArrayList;
import java.util.List;
import dev.rt_render_experiment.engine.abi.R2Abi;
import dev.rt_render_experiment.engine.abi.R2Resources;
import dev.rt_render_experiment.vulkan.CompletionPool;
import dev.rt_render_experiment.vulkan.VulkanBarriers;
import dev.rt_render_experiment.vulkan.VulkanDescriptors;
import dev.rt_render_experiment.vulkan.VulkanResources;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;


public final class R2Presentation implements AutoCloseable {
    private static final int STAGE = VK12.VK_SHADER_STAGE_COMPUTE_BIT;
    private final VulkanResources resources;
    private final ComputeProgram programs;
    private VulkanResources.Buffer exposure;
    private final CompletionPool<R2Scratch> scratch;
    public R2Presentation(VulkanResources resources, R2ShaderPackage shaders) {
        this.resources = resources;
        scratch = new CompletionPool<>(4, () -> new R2Scratch(resources), R2Scratch::close);
        var bindings = R2Resources.presentation(STAGE);
        programs = new ComputeProgram(resources, shaders, R2Abi.Presentation.ENTRIES, bindings);
    }
    public Prepared record(VkCommandBuffer command, VulkanResources.Buffer uniform, VulkanResources.Buffer composite, int width, int height) {
        Prepared result = new Prepared();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int pixels = Math.multiplyExact(width, height); result.exposure = allocate(8);
            var previous = exposure;
            if (previous == null) { previous = allocate(8); result.transients.add(previous); VK12.vkCmdFillBuffer(command, previous.view().handle(), 0, 8, 0); }
            result.display = result.buffer(0, Math.multiplyExact(pixels, 16));
            previous.markUsed(); result.exposure.markUsed(); result.display.markUsed(); uniform.markUsed(); composite.markUsed();
            VulkanBarriers.record(command, stack, new VulkanBarriers.Scope(VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK13.VK_ACCESS_2_MEMORY_WRITE_BIT,
                VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK13.VK_ACCESS_2_SHADER_READ_BIT | VK13.VK_ACCESS_2_SHADER_WRITE_BIT));

            bind(command, stack, uniform, composite, previous, result.exposure, result.display);
            programs.dispatch(command, 0, 1, 1); VulkanBarriers.record(command, stack, VulkanBarriers.EXPOSURE_TO_BLOOM);
            programs.dispatchPixels(command, 1, width, height);
            return result;
        } catch (RuntimeException | Error failure) { result.close(); throw failure; }
    }
    private VulkanResources.Buffer allocate(int bytes) {
        return resources.allocateDevice(bytes, VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT);
    }
    private void bind(VkCommandBuffer command, MemoryStack stack, VulkanResources.Buffer uniform, VulkanResources.Buffer... buffers) {
        var writes = VkWriteDescriptorSet.calloc(5, stack); VulkanDescriptors.writeUniform(writes.get(0), stack, 0, uniform.view());
        for (int i = 0; i < buffers.length; i++) VulkanDescriptors.writeStorageBuffer(writes.get(i + 1), stack, i + 1, buffers[i].view());
        KHRPushDescriptor.vkCmdPushDescriptorSetKHR(command, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, programs.layout(), 0, writes);
    }
    public final class Prepared implements AutoCloseable {
        private final long recording = resources.recording();
        private final CompletionPool<R2Scratch>.Lease scratch = R2Presentation.this.scratch.acquire(recording, resources.completed());
        private final List<VulkanResources.Buffer> transients = new ArrayList<>();
        private VulkanResources.Buffer exposure, display;
        private boolean committed, closed;
        private Prepared() {}
        private VulkanResources.Buffer buffer(int index, int bytes) {
            return scratch.value().buffer(index, bytes, VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                | VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT, false);
        }
        public VulkanResources.Buffer display() { if (closed) throw new IllegalStateException("Closed presentation"); return display; }
        public VulkanResources.Buffer exposure() { if (closed) throw new IllegalStateException("Closed presentation"); return exposure; }
        public void submitted(long serial) {
            if (closed || committed || serial < recording) throw new IllegalStateException("Invalid presentation submission");
            var previous = R2Presentation.this.exposure; R2Presentation.this.exposure = exposure; committed = true;
            if (previous != null) previous.close();
        }
        @Override public void close() {
            if (!closed) {
                closed = true;
                Throwable failure = null;
                if (!committed && exposure != null) failure = dev.rt_render_experiment.vulkan.VulkanRetirement.attempt(failure, exposure::close);
                for (var buffer : transients) failure = dev.rt_render_experiment.vulkan.VulkanRetirement.attempt(failure, buffer::close);
                scratch.close();
                dev.rt_render_experiment.vulkan.VulkanRetirement.finish(failure);
            }
        }
    }
    public void invalidate() { if (exposure != null) { exposure.close(); exposure = null; } }
    VulkanResources.Buffer exposureInput() { return exposure; }
    @Override public void close() {
        Throwable failure = dev.rt_render_experiment.vulkan.VulkanRetirement.attempt(null, scratch::close);
        if (programs != null) failure = dev.rt_render_experiment.vulkan.VulkanRetirement.attempt(failure, programs::close);
        failure = dev.rt_render_experiment.vulkan.VulkanRetirement.attempt(failure, this::invalidate);
        dev.rt_render_experiment.vulkan.VulkanRetirement.finish(failure);
    }
}
