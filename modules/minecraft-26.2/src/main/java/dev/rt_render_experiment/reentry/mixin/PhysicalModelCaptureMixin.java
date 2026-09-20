package dev.rt_render_experiment.reentry.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.rt_render_experiment.reentry.FeatureProducer;
import dev.rt_render_experiment.reentry.PhysicalPlayer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
@Mixin(value=ModelFeatureRenderer.class,remap=false)
public abstract class PhysicalModelCaptureMixin {
    @WrapMethod(method="prepareModel")
    private void rt_render_experiment$physical(ModelFeatureRenderer.Submit<?> submit,Operation<Void> original) {
        boolean physical=((PhysicalPlayer.ModelSubmit)(Object)submit).rt_render_experiment$physicalModel();
        if(physical && !FeatureProducer.accepts(submit.renderType()))return;
        boolean previous=FeatureProducer.physical(physical);
        try { original.call(submit); }finally { FeatureProducer.physical(previous); }
    }
    @WrapOperation(method="prepareModel",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer;getVertexBuilder(Lnet/minecraft/client/renderer/rendertype/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"),require=1,allow=1)
    private VertexConsumer rt_render_experiment$buffer(ModelFeatureRenderer renderer,RenderType type,Operation<VertexConsumer> original) {
        var physical=FeatureProducer.physicalBuffer(type);return physical==null?original.call(renderer,type):physical;
    }
}
