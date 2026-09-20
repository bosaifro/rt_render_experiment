package dev.rt_render_experiment.reentry.mixin;
import dev.rt_render_experiment.reentry.RtRenderExperimentFrameGraph;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=PreparedRenderType.class,remap=false)
public abstract class FeatureDrawReceiptMixin {
    @Inject(method="drawFromBuffer(Lnet/minecraft/client/renderer/StagedVertexBuffer$ExecuteInfo;)V",at=@At("HEAD"),cancellable=true)
    private void rt_render_experiment$receipt(StagedVertexBuffer.ExecuteInfo info,CallbackInfo callback) {
        var scope=RtRenderExperimentFrameGraph.active();if(scope!=null && scope.replaceFeature(info))callback.cancel();
    }
}
