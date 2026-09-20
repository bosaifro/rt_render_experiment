package dev.rt_render_experiment.reentry.mixin;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import dev.rt_render_experiment.reentry.HdrPipelines;
import dev.rt_render_experiment.reentry.HostRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(value=VulkanRenderPass.class,remap=false)
public abstract class HdrRenderPassMixin implements HostRenderPass {
    @Unique private GpuFormat[] rt_render_experiment$formats;
    @Override public void rt_render_experiment$formats(GpuFormat[] formats) { rt_render_experiment$formats=formats.clone(); }
    @ModifyVariable(method="setPipeline",at=@At("HEAD"),argsOnly=true,ordinal=0)
    private RenderPipeline rt_render_experiment$format(RenderPipeline pipeline) { return HdrPipelines.adapt(pipeline,rt_render_experiment$formats); }
}
