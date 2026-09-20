package dev.rt_render_experiment.reentry.mixin;

import com.mojang.blaze3d.vertex.QuadInstance;
import dev.rt_render_experiment.reentry.QuadTint;
import net.minecraft.util.ARGB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=QuadInstance.class,remap=false)
public abstract class QuadTintMixin implements QuadTint {
    @Unique private int rt_render_experiment$albedo=-1;
    @Override public int rt_render_experiment$albedoTint() { return rt_render_experiment$albedo; }
    @Inject(method={"setColor(I)V","setColor(II)V"},at=@At("RETURN"))
    private void rt_render_experiment$reset(CallbackInfo callback) { rt_render_experiment$albedo=-1; }
    @Inject(method="multiplyColor",at=@At("HEAD"))
    private void rt_render_experiment$tint(int color,CallbackInfo callback) { rt_render_experiment$albedo=ARGB.multiply(rt_render_experiment$albedo,color); }
}
