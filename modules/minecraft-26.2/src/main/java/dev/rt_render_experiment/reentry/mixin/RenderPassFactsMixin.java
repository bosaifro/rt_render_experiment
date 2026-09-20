package dev.rt_render_experiment.reentry.mixin;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import java.util.List;
import dev.rt_render_experiment.reentry.HostRenderPass;
import dev.rt_render_experiment.reentry.HostTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value=VulkanCommandEncoder.class,remap=false)
public abstract class RenderPassFactsMixin {
    @Unique private List<HostTexture> rt_render_experiment$writes=List.of();
    @Inject(method="createRenderPass",at=@At("RETURN"))
    private void rt_render_experiment$attachments(RenderPassDescriptor descriptor,CallbackInfoReturnable<RenderPassBackend> callback) {
        var formats=descriptor.colorAttachments().stream().map(a->a==null?null:a.textureView().texture().getFormat()).toArray(GpuFormat[]::new);
        ((HostRenderPass)callback.getReturnValue()).rt_render_experiment$formats(formats);
        rt_render_experiment$writes=descriptor.colorAttachments().stream().filter(java.util.Objects::nonNull).map(a->(HostTexture)a.textureView().texture()).toList();
    }
    @Inject(method="submitRenderPass",at=@At("RETURN"))
    private void rt_render_experiment$written(CallbackInfo callback) { for(var texture:rt_render_experiment$writes)texture.rt_render_experiment$written();rt_render_experiment$writes=List.of(); }
}
