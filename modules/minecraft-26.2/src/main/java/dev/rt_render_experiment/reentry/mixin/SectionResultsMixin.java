package dev.rt_render_experiment.reentry.mixin;

import dev.rt_render_experiment.integration.minecraft.MinecraftSectionSemantics;
import dev.rt_render_experiment.reentry.SemanticMesh;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value=SectionCompiler.Results.class,remap=false)
public abstract class SectionResultsMixin implements SemanticMesh {
    @Unique private MinecraftSectionSemantics.Metadata rt_render_experiment$metadata=MinecraftSectionSemantics.EMPTY;
    @Override public MinecraftSectionSemantics.Metadata rt_render_experiment$semantics() { return rt_render_experiment$metadata; }
    @Override public void rt_render_experiment$semantics(MinecraftSectionSemantics.Metadata metadata) { rt_render_experiment$metadata=java.util.Objects.requireNonNull(metadata); }
}
