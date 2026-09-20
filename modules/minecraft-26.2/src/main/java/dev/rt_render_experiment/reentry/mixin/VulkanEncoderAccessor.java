package dev.rt_render_experiment.reentry.mixin;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = VulkanCommandEncoder.class, remap = false)
public interface VulkanEncoderAccessor {
    @Accessor("device") VulkanDevice rt_render_experiment$device();
    @Accessor("currentSubmitIndex") long rt_render_experiment$recording();
    @Accessor("completedSubmitIndex") long rt_render_experiment$completed();
    @Accessor("currentRenderPass") VulkanRenderPass rt_render_experiment$renderPass();
}
