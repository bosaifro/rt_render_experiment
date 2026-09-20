package dev.rt_render_experiment.reentry.mixin;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.GpuFormat;
import dev.rt_render_experiment.reentry.HostTexture;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.lwjgl.vulkan.VK12;

@Mixin(value=VulkanGpuTexture.class,remap=false)
public abstract class VulkanTextureMixin implements HostTexture {
    @Shadow @Final private VulkanDevice device;
    @Unique private long rt_render_experiment$revision=1;
    @Unique private int rt_render_experiment$extraUsage;
    @Override public VulkanDevice rt_render_experiment$device() { return device; }
    @Override public long rt_render_experiment$revision() { return rt_render_experiment$revision; }
    @Override public int rt_render_experiment$extraUsage() { return rt_render_experiment$extraUsage; }
    @Override public void rt_render_experiment$written() { rt_render_experiment$revision=Math.incrementExact(rt_render_experiment$revision); }
    @ModifyArg(method="<init>",at=@At(value="INVOKE",target="Lorg/lwjgl/vulkan/VkImageCreateInfo;usage(I)Lorg/lwjgl/vulkan/VkImageCreateInfo;"),index=0,require=1,allow=1)
    private int rt_render_experiment$hdrStorage(int usage) {
        if(dev.rt_render_experiment.reentry.VulkanAdmission.enabled(device.vkDevice().address()) && ((VulkanGpuTexture)(Object)this).getFormat()==GpuFormat.RGBA16_FLOAT)rt_render_experiment$extraUsage=VK12.VK_IMAGE_USAGE_STORAGE_BIT;
        return usage|rt_render_experiment$extraUsage;
    }
}
