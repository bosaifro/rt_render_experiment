package dev.rt_render_experiment.reentry.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.rt_render_experiment.reentry.FeatureProducer;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.feature.MovingBlockFeatureRenderer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
@Mixin(value={ItemFeatureRenderer.class,MovingBlockFeatureRenderer.class},remap=false)
public abstract class FeatureQuadFactsMixin {
    @WrapOperation(method={"prepareMainSubmit","putBakedQuad"},at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/vertex/VertexConsumer;putBakedQuad(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lnet/minecraft/client/resources/model/geometry/BakedQuad;Lcom/mojang/blaze3d/vertex/QuadInstance;)V"),require=1)
    private void rt_render_experiment$quad(VertexConsumer output,PoseStack.Pose pose,BakedQuad quad,QuadInstance instance,Operation<Void> original) {
        original.call(output,pose,quad,instance);FeatureProducer.quad(quad,instance);
    }
}
