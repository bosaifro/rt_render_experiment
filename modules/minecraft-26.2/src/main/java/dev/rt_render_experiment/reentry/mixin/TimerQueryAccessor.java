package dev.rt_render_experiment.reentry.mixin;
import com.mojang.blaze3d.systems.TimerQuery;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(value=TimerQuery.class,remap=false)
public interface TimerQueryAccessor {
    @Accessor("results") long[] rt_render_experiment$results();
    @Accessor("currentRotationIndex") int rt_render_experiment$rotation();
}
