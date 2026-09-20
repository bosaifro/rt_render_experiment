package dev.rt_render_experiment.reentry.mixin;

import dev.rt_render_experiment.reentry.TerrainProducer;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value=SectionRenderDispatcher.RenderSection.class,remap=false)
public abstract class TerrainPublicationMixin {
    @Shadow @Final private SectionRenderDispatcher this$0;
    @Inject(method="setSectionMesh",at=@At("RETURN"))
    private void rt_render_experiment$published(SectionMesh mesh,CallbackInfoReturnable<SectionMesh> callback) {
        TerrainProducer.published(this$0,(SectionRenderDispatcher.RenderSection)(Object)this,mesh);
    }
    @Inject(method="releaseSectionMesh",at=@At("HEAD"))
    private void rt_render_experiment$released(SectionMesh mesh,CallbackInfo callback) { TerrainProducer.released(this$0,mesh); }
}
