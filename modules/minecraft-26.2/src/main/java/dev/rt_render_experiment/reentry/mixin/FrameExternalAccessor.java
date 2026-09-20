package dev.rt_render_experiment.reentry.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(targets="com.mojang.blaze3d.framegraph.FrameGraphBuilder$ExternalResource",remap=false)
public interface FrameExternalAccessor {
    @Accessor("resource") Object rt_render_experiment$resource();
    @Mutable @Accessor("resource") void rt_render_experiment$resource(Object resource);
}
