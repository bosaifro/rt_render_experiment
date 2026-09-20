package dev.rt_render_experiment.reentry.mixin;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(value = VulkanDevice.class, remap = false)
public abstract class VulkanBufferUsageMixin {
    @ModifyVariable(method = "createBuffer(Ljava/util/function/Supplier;IJ)Lcom/mojang/blaze3d/vulkan/VulkanGpuBuffer;", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 1, allow = 1)
    private int rt_render_experiment$readableVertexStorage(int usage) {
        return (usage & GpuBuffer.USAGE_VERTEX) == 0 ? usage : usage | GpuBuffer.USAGE_COPY_SRC;
    }
}
