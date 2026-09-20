package dev.rt_render_experiment.reentry.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value=LevelRenderer.class,remap=false)
public interface LevelStateAccessor {
    @Accessor("levelRenderState") LevelRenderState rt_render_experiment$state();
    @Accessor("optionsRenderState") OptionsRenderState rt_render_experiment$options();
}
