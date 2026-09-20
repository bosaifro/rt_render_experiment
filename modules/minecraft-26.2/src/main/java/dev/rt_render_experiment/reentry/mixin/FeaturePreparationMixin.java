package dev.rt_render_experiment.reentry.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.rt_render_experiment.reentry.FeatureProducer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(value=FeatureRenderDispatcher.class,remap=false)
public abstract class FeaturePreparationMixin {
    @WrapMethod(method="prepareFrameWithContext")
    private FeatureRenderDispatcher.PreparedFrame rt_render_experiment$prepare(FeatureFrameContext context,SubmitNodeStorage submits,Operation<FeatureRenderDispatcher.PreparedFrame> original) {
        FeatureProducer.begin(context);boolean complete=false;
        long cpu=dev.rt_render_experiment.reentry.ProducerCpu.start();
        try { var frame=original.call(context,submits);complete=true;return frame; }
        finally { FeatureProducer.end(complete);dev.rt_render_experiment.reentry.ProducerCpu.feature(cpu); }
    }
}
