package dev.rt_render_experiment.reentry.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.rt_render_experiment.reentry.HostDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value=VulkanDevice.class,remap=false)
public abstract class VulkanDeviceLifetimeMixin {
    @WrapOperation(method="close",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/vulkan/VulkanCommandEncoder;destroy()V"),require=1,allow=1)
    private void rt_render_experiment$lifetime(VulkanCommandEncoder encoder,Operation<Void> original) {
        var device=(VulkanDevice)(Object)this;
        HostDevice.beforeDestroy(device);original.call(encoder);HostDevice.afterDestroy(device);
    }
}
