package dev.rt_render_experiment.engine;

import dev.rt_render_experiment.contract.HostExecution;
import dev.rt_render_experiment.vulkan.VulkanResources;
import org.lwjgl.vulkan.VkCommandBuffer;


public interface FrameRenderer extends AutoCloseable {
    FrameOutput record(HostExecution.Window window, VkCommandBuffer command, GpuScene.Snapshot scene, RenderFrame frame);
    void publishWorld(HostExecution.Window window, VkCommandBuffer command, FrameOutput output, HostExecution.ImageGrant depth);
    void publishDisplay(HostExecution.Window window, VkCommandBuffer command, FrameOutput output, HostExecution.ImageGrant target);
    void publishViewmodel(HostExecution.Window window, VkCommandBuffer command, FrameOutput world, FrameOutput viewmodel);
    void recordPresentation(HostExecution.Window window, VkCommandBuffer command, FrameOutput output);
    void absentViewmodel();
    @Override void close();

    static FrameRenderer create(VulkanResources resources, HostExecution.Capabilities capabilities, ShaderModules shaders) {
        if (shaders instanceof R2ShaderPackage r2) return new R2Renderer(resources, capabilities, r2);
        throw new IllegalArgumentException("Unknown shader family " + shaders.family());
    }
}
