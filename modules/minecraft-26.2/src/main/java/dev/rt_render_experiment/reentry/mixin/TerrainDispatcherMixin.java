package dev.rt_render_experiment.reentry.mixin;

import dev.rt_render_experiment.reentry.TerrainProducer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=SectionRenderDispatcher.class,remap=false)
public abstract class TerrainDispatcherMixin {
    @Inject(method="dispose",at=@At("RETURN"))
    private void rt_render_experiment$closed(CallbackInfo callback) { TerrainProducer.closed((SectionRenderDispatcher)(Object)this); }
}
