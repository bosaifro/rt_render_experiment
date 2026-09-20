package dev.rt_render_experiment.engine;

import dev.rt_render_experiment.contract.HostExecution;
import dev.rt_render_experiment.contract.SceneInputs;
import dev.rt_render_experiment.vulkan.VulkanResources;


public interface FrameOutput extends AutoCloseable {

    int width();
    int height();

    VulkanResources.Buffer hits();

    VulkanResources.Buffer signals();
    VulkanResources.Buffer radiance();
    VulkanResources.Buffer history();
    VulkanResources.Buffer display();
    VulkanResources.Buffer exposure();
    VulkanResources.Image compositionImage();
    VulkanResources.Image viewDepthImage();
    RenderFrame.Reconstruction reconstructionMode();
    boolean worldLightReuseEnabled();
    boolean worldLightHistoryDomainValid();
    long worldLightHistoryBytes();
    VulkanResources.Buffer worldLightHistory();
    boolean replaces(SceneInputs.Geometry input, HostExecution.Transaction admission);
    void submitted(long serial);
    long logicalBytes();
    @Override void close();
}
