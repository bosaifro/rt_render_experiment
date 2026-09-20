package dev.rt_render_experiment.reentry;

import com.mojang.blaze3d.vulkan.VulkanDevice;

public interface HostTexture {
    VulkanDevice rt_render_experiment$device();
    long rt_render_experiment$revision();
    int rt_render_experiment$extraUsage();
    void rt_render_experiment$written();
}
