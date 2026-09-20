package dev.rt_render_experiment.reentry.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import java.nio.ByteBuffer;
import dev.rt_render_experiment.reentry.HostTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=VulkanCommandEncoder.class,remap=false)
public abstract class VulkanTextureWritesMixin {
    @Inject(method="writeToTexture",at=@At("RETURN"))
    private void rt_render_experiment$write(GpuTexture destination,ByteBuffer source,int mip,int layer,int x,int y,int width,int height,CallbackInfo callback) {
        ((HostTexture)destination).rt_render_experiment$written();
    }
    @Inject(method="copyBufferToTexture",at=@At("RETURN"))
    private void rt_render_experiment$buffer(GpuBufferSlice source,int sx,int sy,int sw,int sh,GpuTexture destination,int dx,int dy,int width,int height,int mip,int layer,CallbackInfo callback) {
        ((HostTexture)destination).rt_render_experiment$written();
    }
    @Inject(method="copyTextureToTexture",at=@At("RETURN"))
    private void rt_render_experiment$copy(GpuTexture source,GpuTexture destination,int mip,int dx,int dy,int sx,int sy,int width,int height,CallbackInfo callback) {
        ((HostTexture)destination).rt_render_experiment$written();
    }
}
