package dev.rt_render_experiment.reentry.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.List;
import dev.rt_render_experiment.reentry.FeatureProducer;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.feature.MovingBlockFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(value=RenderTypeFeatureRenderer.class,remap=false)
public abstract class ModelProducerScopeMixin {
    @WrapMethod(method="prepareGroup")
    private void rt_render_experiment$model(FeatureFrameContext context,List<?> submits,boolean ordered,Operation<Void> original) {
        int previous=FeatureProducer.kind((Object)this instanceof ModelFeatureRenderer?1:(Object)this instanceof ItemFeatureRenderer?2:(Object)this instanceof MovingBlockFeatureRenderer?3:0);
        try { original.call(context,submits,ordered); }finally { FeatureProducer.kind(previous); }
    }
}
