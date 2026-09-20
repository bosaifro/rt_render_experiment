package dev.rt_render_experiment.reentry.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "com.mojang.blaze3d.framegraph.FrameGraphBuilder$Pass", remap = false)
public interface FramePassAccessor {
    @Accessor("name") String rt_render_experiment$name();
    @Accessor("task") Runnable rt_render_experiment$task();
    @Accessor("task") void rt_render_experiment$task(Runnable task);
}
