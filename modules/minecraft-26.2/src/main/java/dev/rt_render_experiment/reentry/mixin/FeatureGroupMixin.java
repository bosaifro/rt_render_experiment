package dev.rt_render_experiment.reentry.mixin;
import java.util.List;
import dev.rt_render_experiment.reentry.FeatureProducer;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(targets="net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer$Group",remap=false)
public abstract class FeatureGroupMixin {
    @Shadow @Final private StagedVertexBuffer stagedBuffer;
    @Shadow @Final private List<StagedVertexBuffer.Draw> draws;
    @Shadow @Final private List<PreparedRenderType> drawRenderTypes;
    @Shadow private StagedVertexBuffer.Draw lastDraw;
    @Inject(method="getVertexBuilder",at=@At("RETURN"))
    private void rt_render_experiment$current(RenderType type,CallbackInfoReturnable<com.mojang.blaze3d.vertex.VertexConsumer> callback) { FeatureProducer.currentDraw(lastDraw); }
    @Inject(method="getOrAddDraw",at=@At("RETURN"))
    private void rt_render_experiment$draw(RenderType type,CallbackInfoReturnable<StagedVertexBuffer.Draw> callback) {
        var draw=callback.getReturnValue();int index=draws.indexOf(draw);if(index>=0)FeatureProducer.associate(stagedBuffer,draw,type,drawRenderTypes.get(index));
    }
}
