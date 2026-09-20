package dev.rt_render_experiment.reentry.mixin;
import dev.rt_render_experiment.reentry.HostFrameTiming;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=Minecraft.class,remap=false)
public abstract class HostFrameTimingMixin {
    @Inject(method="renderFrame",at=@At("HEAD"))
    private void rt_render_experiment$begin(CallbackInfo callback) { dev.rt_render_experiment.reentry.WorldRenderer.poll();HostFrameTiming.begin(); }
}
