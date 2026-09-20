package dev.rt_render_experiment.reentry.mixin;
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.rt_render_experiment.reentry.RtRenderExperimentFrameGraph;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(value=GameRenderer.class,remap=false)
public abstract class WorldTargetMixin {
    @Inject(method="mainRenderTarget",at=@At("RETURN"),cancellable=true)
    private void rt_render_experiment$target(CallbackInfoReturnable<RenderTarget> callback) {
        var scope=RtRenderExperimentFrameGraph.active();if(scope!=null)callback.setReturnValue(scope.redirect(callback.getReturnValue()));
    }
}
