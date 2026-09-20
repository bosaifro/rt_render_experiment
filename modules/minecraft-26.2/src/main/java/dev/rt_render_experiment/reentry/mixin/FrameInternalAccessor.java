package dev.rt_render_experiment.reentry.mixin;
import com.mojang.blaze3d.resource.ResourceDescriptor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(targets="com.mojang.blaze3d.framegraph.FrameGraphBuilder$InternalVirtualResource",remap=false)
public interface FrameInternalAccessor {
    @Accessor("descriptor") ResourceDescriptor<?> rt_render_experiment$descriptor();
    @Mutable @Accessor("descriptor") void rt_render_experiment$descriptor(ResourceDescriptor<?> descriptor);
}
