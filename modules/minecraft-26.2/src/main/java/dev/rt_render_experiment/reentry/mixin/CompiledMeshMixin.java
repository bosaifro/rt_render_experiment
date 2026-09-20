package dev.rt_render_experiment.reentry.mixin;

import dev.rt_render_experiment.integration.minecraft.MinecraftSectionSemantics;
import dev.rt_render_experiment.reentry.SemanticMesh;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.TranslucencyPointOfView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=CompiledSectionMesh.class,remap=false)
public abstract class CompiledMeshMixin implements SemanticMesh {
    @Unique private MinecraftSectionSemantics.Metadata rt_render_experiment$metadata=MinecraftSectionSemantics.EMPTY;
    @Inject(method="<init>",at=@At("RETURN"))
    private void rt_render_experiment$copy(TranslucencyPointOfView point,SectionCompiler.Results results,CallbackInfo callback) { rt_render_experiment$metadata=((SemanticMesh)(Object)results).rt_render_experiment$semantics(); }
    @Override public MinecraftSectionSemantics.Metadata rt_render_experiment$semantics() { return rt_render_experiment$metadata; }
    @Override public void rt_render_experiment$semantics(MinecraftSectionSemantics.Metadata metadata) { rt_render_experiment$metadata=java.util.Objects.requireNonNull(metadata); }
}
