package dev.rt_render_experiment.reentry.mixin;
import dev.rt_render_experiment.reentry.PhysicalPlayer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
@Mixin(value=EntityRenderState.class,remap=false)
public abstract class EntityIdentityMixin implements PhysicalPlayer.State {
    @Unique private java.util.UUID rt_render_experiment$entity;
    @Unique private boolean rt_render_experiment$physical;
    @Override public java.util.UUID rt_render_experiment$entity() { return rt_render_experiment$entity; }
    @Override public void rt_render_experiment$entity(java.util.UUID id) { rt_render_experiment$entity=id; }
    @Override public boolean rt_render_experiment$physical() { return rt_render_experiment$physical; }
    @Override public void rt_render_experiment$physical(boolean physical) { rt_render_experiment$physical=physical; }
}
