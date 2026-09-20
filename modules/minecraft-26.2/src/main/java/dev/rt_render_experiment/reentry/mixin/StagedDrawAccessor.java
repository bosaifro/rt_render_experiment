package dev.rt_render_experiment.reentry.mixin;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(value=StagedVertexBuffer.Draw.class,remap=false)
public interface StagedDrawAccessor { @Accessor("vertexCount") int rt_render_experiment$vertexCount(); }
