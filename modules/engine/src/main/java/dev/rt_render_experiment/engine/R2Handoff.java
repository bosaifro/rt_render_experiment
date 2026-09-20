package dev.rt_render_experiment.engine;

import java.util.List;
import dev.rt_render_experiment.contract.ResourceViews;
import dev.rt_render_experiment.engine.abi.R2Abi;
import dev.rt_render_experiment.engine.abi.R2Resources;
import dev.rt_render_experiment.vulkan.VulkanBarriers;
import dev.rt_render_experiment.vulkan.VulkanDescriptors;
import dev.rt_render_experiment.vulkan.VulkanGraphicsPasses;
import dev.rt_render_experiment.vulkan.VulkanGraphicsPipeline;
import dev.rt_render_experiment.vulkan.VulkanObjects;
import dev.rt_render_experiment.vulkan.VulkanResources;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;


public final class R2Handoff implements AutoCloseable {
    private final VulkanResources resources;
    private final VulkanGraphicsPipeline world, display, viewmodel;
    private final long descriptors;
    private final ComputeProgram importer;
    private long lastUse;
    private boolean closed;
    public R2Handoff(VulkanResources resources, R2ShaderPackage shaders) {
        this.resources = resources; var device = resources.device(); long set = 0, vertex = 0, worldFragment = 0, displayFragment = 0, viewFragment = 0;
        VulkanGraphicsPipeline worldPipeline = null, displayPipeline = null, viewPipeline = null;
        int fragment = VK12.VK_SHADER_STAGE_FRAGMENT_BIT;
        try {
            set = VulkanDescriptors.createPushLayout(device, "RtRenderExperiment R2 frame handoff", R2Resources.handoff(fragment));
            vertex = VulkanObjects.shaderModule(device, shaders.module(R2Abi.Handoff.ENTRIES.get(0)), "R2 handoff vertex");
            worldFragment = VulkanObjects.shaderModule(device, shaders.module(R2Abi.Handoff.ENTRIES.get(1)), "R2 world handoff");
            displayFragment = VulkanObjects.shaderModule(device, shaders.module(R2Abi.Handoff.ENTRIES.get(2)), "R2 display handoff");
            viewFragment = VulkanObjects.shaderModule(device, shaders.module(R2Abi.Handoff.ENTRIES.get(3)), "R2 viewmodel handoff");
            worldPipeline = VulkanGraphicsPipeline.create(device, resources.deviceIdentity(), 0, set, 0, VulkanGraphicsPipeline.Kind.PUBLISH_RESOLVE, null, vertex, worldFragment, "RtRenderExperiment R2 world/depth handoff");
            displayPipeline = VulkanGraphicsPipeline.create(device, resources.deviceIdentity(), 0, set, 0, VulkanGraphicsPipeline.Kind.DISPLAY, null, vertex, displayFragment, "RtRenderExperiment R2 display handoff");
            viewPipeline = VulkanGraphicsPipeline.create(device, resources.deviceIdentity(), 0, set, 0, VulkanGraphicsPipeline.Kind.VIEWMODEL, null, vertex, viewFragment, "RtRenderExperiment R2 viewmodel/depth handoff");
            importer = new ComputeProgram(resources, shaders, R2Abi.Import.ENTRIES, R2Resources.importResources(VK12.VK_SHADER_STAGE_COMPUTE_BIT));
            descriptors = set; world = worldPipeline; display = displayPipeline; viewmodel = viewPipeline;
        } catch (RuntimeException | Error failure) {
            if (worldPipeline != null) worldPipeline.destroy(); if (displayPipeline != null) displayPipeline.destroy(); if (viewPipeline != null) viewPipeline.destroy();
            if (set != 0) VK12.vkDestroyDescriptorSetLayout(device, set, null);
            throw failure;
        } finally {
            if (vertex != 0) VK12.vkDestroyShaderModule(device, vertex, null); if (worldFragment != 0) VK12.vkDestroyShaderModule(device, worldFragment, null);
            if (displayFragment != 0) VK12.vkDestroyShaderModule(device, displayFragment, null); if (viewFragment != 0) VK12.vkDestroyShaderModule(device, viewFragment, null);
        }
    }
    public void world(VkCommandBuffer command, VulkanResources.Buffer uniform, VulkanResources.Buffer color, VulkanResources.Buffer hostDepth, VulkanResources.Buffer guides,
                      ResourceViews.Image target, ResourceViews.Image depth, int width, int height) { draw(command, world, uniform, color, hostDepth, guides, target, depth, width, height); }
    public void display(VkCommandBuffer command, VulkanResources.Buffer uniform, VulkanResources.Buffer color, VulkanResources.Buffer hostDepth, VulkanResources.Buffer guides,
                        ResourceViews.Image target, int width, int height) { draw(command, display, uniform, color, hostDepth, guides, target, null, width, height); }
    public void viewmodel(VkCommandBuffer command, VulkanResources.Buffer uniform, VulkanResources.Buffer color, VulkanResources.Buffer hostDepth, VulkanResources.Buffer guides,
                          ResourceViews.Image target, ResourceViews.Image viewDepth, int width, int height) { draw(command, viewmodel, uniform, color, hostDepth, guides, target, viewDepth, width, height); }
    private void draw(VkCommandBuffer command, VulkanGraphicsPipeline pipeline, VulkanResources.Buffer uniform, VulkanResources.Buffer color, VulkanResources.Buffer hostDepth,
                      VulkanResources.Buffer guides, ResourceViews.Image target, ResourceViews.Image depth, int width, int height) {
        if (closed || command.getDevice() != resources.device()) throw new IllegalStateException("Invalid handoff recorder");
        PassTelemetry.begin(resources,command,pipeline==world?"r2WorldHandoff":pipeline==display?"r2DisplayHandoff":"r2ViewmodelHandoff",width,height,false);
        lastUse = resources.recording(); uniform.markUsed(); color.markUsed(); hostDepth.markUsed(); guides.markUsed();
        try (var stack = MemoryStack.stackPush()) {
            VulkanBarriers.record(command, stack, new VulkanBarriers.Scope(VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK13.VK_ACCESS_2_MEMORY_READ_BIT | VK13.VK_ACCESS_2_MEMORY_WRITE_BIT,
                VK13.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT | VK13.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT | VK13.VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK13.VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT,
                VK13.VK_ACCESS_2_SHADER_READ_BIT | VK13.VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT | VK13.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT | VK13.VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT));
            try (var pass = VulkanGraphicsPasses.begin(command, pipeline, target, depth, false, false, width, height)) { pass.r2Frame(uniform.view(), color.view(), hostDepth.view(), guides.view()); }
            PassTelemetry.end(resources,command);
        }
    }
    public void importComposition(VkCommandBuffer command, VulkanResources.Buffer uniform, ResourceViews.Image image, VulkanResources.Buffer color, int width, int height) {
        uniform.markUsed(); color.markUsed();
        try (var stack = MemoryStack.stackPush()) {
            VulkanBarriers.record(command, stack, new VulkanBarriers.Scope(VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK13.VK_ACCESS_2_MEMORY_WRITE_BIT,
                VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK13.VK_ACCESS_2_SHADER_READ_BIT | VK13.VK_ACCESS_2_SHADER_WRITE_BIT));
            var writes = VkWriteDescriptorSet.calloc(3, stack); VulkanDescriptors.writeUniform(writes.get(0), stack, 0, uniform.view());
            VulkanDescriptors.writeStorageImage(writes.get(1), stack, 1, image); VulkanDescriptors.writeStorageBuffer(writes.get(2), stack, 2, color.view());
            KHRPushDescriptor.vkCmdPushDescriptorSetKHR(command, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, importer.layout(), 0, writes);
            importer.dispatchPixels(command, 0, width, height);
        }
    }
    @Override public void close() {
        if (closed) return;
        importer.close();
        resources.retirePrograms(lastUse, new long[]{world.pipeline(), display.pipeline(), viewmodel.pipeline()}, new long[]{world.layout(), display.layout(), viewmodel.layout()}, new long[]{descriptors});
        closed = true;
    }
}
