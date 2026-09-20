package dev.rt_render_experiment.reentry.mixin;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = FrameGraphBuilder.class, remap = false)
public interface FrameGraphAccessor {
    @Accessor("passes") List<?> rt_render_experiment$passes();
    @Accessor("externalResources") List<?> rt_render_experiment$external();
    @Accessor("internalResources") List<?> rt_render_experiment$internal();
}
