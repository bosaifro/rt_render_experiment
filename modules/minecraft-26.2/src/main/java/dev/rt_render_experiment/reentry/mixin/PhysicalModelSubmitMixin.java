package dev.rt_render_experiment.reentry.mixin;
import dev.rt_render_experiment.reentry.PhysicalPlayer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=ModelFeatureRenderer.Submit.class,remap=false)
public abstract class PhysicalModelSubmitMixin implements PhysicalPlayer.ModelSubmit {
    @Unique private boolean rt_render_experiment$physical;
    @Inject(method="<init>",at=@At("RETURN"))
    private void rt_render_experiment$tag(CallbackInfo callback) { rt_render_experiment$physical=PhysicalPlayer.submitting(); }
    @Override public boolean rt_render_experiment$physicalModel() { return rt_render_experiment$physical; }
}
