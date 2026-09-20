package dev.rt_render_experiment.reentry.mixin;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import java.util.List;
import java.util.Optional;
import dev.rt_render_experiment.reentry.HdrPipelines;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(value=RenderPass.class,remap=false)
public abstract class HdrPassValidationMixin {
    @Shadow @Final private List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colorAttachments;
    @ModifyVariable(method="setPipeline",at=@At("HEAD"),argsOnly=true,ordinal=0)
    private RenderPipeline rt_render_experiment$format(RenderPipeline pipeline) {
        if(colorAttachments.size()!=1 || colorAttachments.getFirst()==null)return pipeline;
        return HdrPipelines.adapt(pipeline,new GpuFormat[]{colorAttachments.getFirst().textureView().texture().getFormat()});
    }
}
